package am.ik.rontolisp.eval;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compile-path expansion of user macros defined with {@code defmacro}. Runs in the CLI
 * before the JVM/WASM compilers (after {@link am.ik.rontolisp.cli.LoadInliner} splices
 * {@code load}ed files), mirroring how the compilers never see {@code load}: each
 * top-level {@code (defmacro ...)} is evaluated into an internal {@link LispEvaluator}
 * (which runs the macro bodies at compile time) and removed from the program, and every
 * macro call in the remaining forms is fully expanded. The backends therefore compile
 * only ordinary forms and need no macro support of their own. Top-level {@code defun}s
 * are also registered into the macro-time evaluator (registration only, no body runs) so
 * a macro body can call helper functions defined in the same program. The interpreter
 * does not use this pass; it expands user macros natively at evaluation time.
 */
public final class UserMacroExpander {

	private UserMacroExpander() {
	}

	/**
	 * Expands user macros in the program and removes the {@code defmacro} forms. Returns
	 * the program unchanged when it defines no macros.
	 * @param program the top-level forms
	 * @return the program with macro definitions consumed and macro calls expanded
	 */
	public static List<LispVal> expand(List<LispVal> program) {
		// Splice top-level (progn ...)/(eval-when ...) first so a defmacro nested in
		// the (eval-when (:compile-toplevel ...) (defmacro ...)) idiom is seen (and
		// nested defuns become top-level for the compilers' Pass 1).
		program = LispMacroExpander.flattenTopLevel(program);
		// Also activate for macroexpand/macroexpand-1 calls: their literal quoted
		// arguments are folded to the expansion here, even when no macro is defined.
		if (program.stream().noneMatch(form -> isOperator(form, LispNames.DEFMACRO))
				&& program.stream().noneMatch(UserMacroExpander::usesMacroexpand)
				&& program.stream().noneMatch(UserMacroExpander::usesMacrolet)) {
			return program;
		}
		LispEvaluator macroEval = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
		List<LispVal> result = new ArrayList<>();
		for (LispVal form : program) {
			// A package directive updates the macro evaluator's resolver state (so a
			// defmacro under (in-package P) registers its canonical qualified name and
			// its template symbols resolve against P) and is kept verbatim for the
			// compilers' own resolution pass (which tracks the same state itself).
			if (isPackageDirective(form)) {
				macroEval.resolvePackages(form);
				result.add(form);
				continue;
			}
			// Resolve through the same resolver so macro CALL SITES match the
			// canonical registered names (a defmacro under (in-package P) registers
			// P-qualified).
			LispVal resolved = macroEval.resolvePackages(form);
			if (isOperator(resolved, LispNames.DEFMACRO)) {
				macroEval.eval(resolved);
				continue;
			}
			LispVal expanded = expandAll(resolved, macroEval);
			if (isOperator(expanded, LispNames.DEFMACRO)) {
				// A macro expanded into a macro definition: consume it as well.
				macroEval.eval(expanded);
				continue;
			}
			if (isOperator(expanded, LispNames.DEFUN)) {
				// Register (no body execution) so later macro bodies can call it.
				macroEval.eval(expanded);
			}
			// A form the walk did not touch keeps its ORIGINAL spelling: the resolved
			// canonical form is not always re-resolvable by the compilers' own pass
			// (a cl: symbol canonicalizes to a bare name, which is an error to spell
			// under a package that does not use cl).
			result.add(expanded.print().equals(resolved.print()) ? form : expanded);
		}
		return result;
	}

	private static boolean isOperator(LispVal form, String name) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol sym && name.equals(sym.name());
	}

	// in-package/defpackage in any package spelling ((cl:in-package ...) included) --
	// the resolver consumes these, so they must be recognized BEFORE resolution to be
	// kept verbatim for the compilers.
	private static boolean isPackageDirective(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		String name = sym.name();
		int colon = name.lastIndexOf(':');
		String member = colon >= 0 ? name.substring(colon + 1) : name;
		return LispNames.IN_PACKAGE.equals(member) || LispNames.DEFPACKAGE.equals(member);
	}

	private static boolean usesMacroexpand(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym
				&& (LispNames.MACROEXPAND.equals(sym.name()) || LispNames.MACROEXPAND_1.equals(sym.name()))) {
			return true;
		}
		return usesMacroexpand(cons.car()) || usesMacroexpand(cons.cdr());
	}

	// A macrolet anywhere (top level or nested in a body) forces the pass to run so its
	// local macros are expanded away; the compilers have no macrolet support of their
	// own.
	private static boolean usesMacrolet(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.MACROLET.equals(sym.name())) {
			return true;
		}
		return usesMacrolet(cons.car()) || usesMacrolet(cons.cdr());
	}

	/**
	 * Recursively expands every user macro call in the form. The walk is aware of the
	 * special-form shapes whose subforms are not expressions (binding lists, parameter
	 * lists, case keys), so a macro name reused there is left alone.
	 */
	static LispVal expandAll(LispVal form, LispEvaluator macroEval) {
		// Expand head-position user macros repeatedly; the expansion may itself be a
		// macro call (or an atom, which needs no further walking).
		while (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym
				&& macroEval.isUserMacro(sym.name())) {
			form = macroEval.expandUserMacro(cons);
		}
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol sym) {
			if (LispNames.QUOTE.equals(sym.name()) || LispNames.DEFMACRO.equals(sym.name())) {
				return form;
			}
			if (!cons.isProperList()) {
				// An improper list is never a call form; the legitimate appearances
				// are data patterns (a loop destructuring pattern like
				// (value . remaining)) whose elements are plain variables.
				return form;
			}
			List<LispVal> parts = cons.toList();
			switch (sym.name()) {
				case LispNames.LET, LispNames.LET_STAR, LispNames.DO, LispNames.DO_STAR:
					// (let ((name init)...) body...) / (do ((var init step)...) (end
					// result...) body...): binding names stay, init/step/end/result and
					// the body are expressions.
					return rebuild(parts, 2, macroEval, expandBindings(parts.get(1), macroEval));
				case LispNames.LAMBDA:
					return rebuild(parts, 2, macroEval, parts.get(1));
				case LispNames.DEFUN:
					return rebuild(parts, 3, macroEval, parts.get(1), parts.get(2));
				case LispNames.DEFSTRUCT: {
					// (defstruct name (slot default)...): the struct and slot names stay,
					// only slot defaults are expressions.
					List<LispVal> newParts = new ArrayList<>();
					newParts.add(parts.get(0));
					newParts.add(parts.get(1));
					for (int i = 2; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons slotCons) {
							List<LispVal> slotParts = slotCons.toList();
							List<LispVal> newSlot = new ArrayList<>();
							newSlot.add(slotParts.get(0));
							for (int j = 1; j < slotParts.size(); j++) {
								newSlot.add(expandAll(slotParts.get(j), macroEval));
							}
							newParts.add(properList(newSlot));
						}
						else {
							newParts.add(parts.get(i));
						}
					}
					return properList(newParts);
				}
				case LispNames.FLET, LispNames.LABELS: {
					// (flet ((name lambda-list body...)...) body...): the definition
					// names and lambda lists stay, definition bodies and the body are
					// expressions.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					if (!(parts.get(1) instanceof LispCons defsCons)) {
						return rebuild(parts, 2, macroEval, parts.get(1));
					}
					List<LispVal> newDefs = new ArrayList<>();
					for (LispVal def : defsCons.toList()) {
						if (def instanceof LispCons defCons && defCons.isProperList() && defCons.toList().size() >= 2) {
							List<LispVal> dp = defCons.toList();
							List<LispVal> newDef = new ArrayList<>();
							newDef.add(dp.get(0));
							newDef.add(dp.get(1));
							for (int i = 2; i < dp.size(); i++) {
								newDef.add(expandAll(dp.get(i), macroEval));
							}
							newDefs.add(properList(newDef));
						}
						else {
							newDefs.add(def);
						}
					}
					return rebuild(parts, 2, macroEval, properList(newDefs));
				}
				case LispNames.MACROLET: {
					// (macrolet ((name lambda-list body...)...) body...): register the
					// local macros for the extent of the body walk, expand the body with
					// them active, and drop the macrolet (its definitions are consumed
					// here,
					// like defmacro). Lexically correct: the locals are removed again
					// after
					// the body, so they do not leak to sibling forms.
					if (parts.size() < 2 || !(parts.get(1) instanceof LispCons || parts.get(1) instanceof LispNil)) {
						return form; // malformed; leave for the interpreter/compiler to
										// report
					}
					return expandMacrolet(parts, macroEval);
				}
				case LispNames.MULTIPLE_VALUE_BIND: {
					// (multiple-value-bind (vars...) values-form body...): the variable
					// list stays, the values form and the body are expressions.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					return rebuild(parts, 2, macroEval, parts.get(1));
				}
				case LispNames.MULTIPLE_VALUE_SETQ: {
					// (multiple-value-setq (vars...) values-form): the variable list
					// stays,
					// the values form is an expression.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					return rebuild(parts, 2, macroEval, parts.get(1));
				}
				case LispNames.DESTRUCTURING_BIND: {
					// (destructuring-bind pattern form body...): the pattern stays (like
					// a lambda list), the form and the body are expressions.
					if (parts.size() < 2) {
						return form; // malformed; the expansion reports it
					}
					return rebuild(parts, 2, macroEval, parts.get(1));
				}
				case LispNames.DOLIST, LispNames.DOTIMES: {
					// (dolist (var listform result) body...): var stays.
					LispVal spec = parts.get(1);
					if (spec instanceof LispCons specCons) {
						List<LispVal> specParts = specCons.toList();
						List<LispVal> newSpec = new ArrayList<>();
						newSpec.add(specParts.get(0));
						for (int i = 1; i < specParts.size(); i++) {
							newSpec.add(expandAll(specParts.get(i), macroEval));
						}
						spec = properList(newSpec);
					}
					return rebuild(parts, 2, macroEval, spec);
				}
				case LispNames.MACROEXPAND, LispNames.MACROEXPAND_1: {
					// Fold a literal quoted argument to its expansion at compile time
					// (the
					// only form the compilers can support: the macro table does not exist
					// at runtime). A computed argument is left as-is and fails naturally.
					if (parts.size() == 2 && parts.get(1) instanceof LispCons quoted
							&& quoted.car() instanceof LispSymbol qs && LispNames.QUOTE.equals(qs.name())
							&& quoted.cdr() instanceof LispCons quotedCdr) {
						LispVal target = quotedCdr.car();
						LispVal expanded = LispNames.MACROEXPAND.equals(sym.name()) ? macroEval.macroexpand(target)
								: macroEval.macroexpand1(target);
						return properList(List.of(new LispSymbol(LispNames.QUOTE), expanded));
					}
					return rebuild(parts, 1, macroEval);
				}
				case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE: {
					// (case keyform (keys body...)...): keys are unevaluated data.
					List<LispVal> newParts = new ArrayList<>();
					newParts.add(parts.get(0));
					newParts.add(expandAll(parts.get(1), macroEval));
					for (int i = 2; i < parts.size(); i++) {
						if (parts.get(i) instanceof LispCons clause) {
							List<LispVal> clauseParts = clause.toList();
							List<LispVal> newClause = new ArrayList<>();
							newClause.add(clauseParts.get(0));
							for (int j = 1; j < clauseParts.size(); j++) {
								newClause.add(expandAll(clauseParts.get(j), macroEval));
							}
							newParts.add(properList(newClause));
						}
						else {
							newParts.add(parts.get(i));
						}
					}
					return properList(newParts);
				}
				default:
					// Every other operator: the head stays, all arguments are walked.
					return rebuild(parts, 1, macroEval);
			}
		}
		// Non-symbol head, e.g. ((lambda (x) ...) arg...): walk every element.
		requireProperCallForm(cons);
		List<LispVal> parts = cons.toList();
		List<LispVal> newParts = new ArrayList<>();
		for (LispVal part : parts) {
			newParts.add(expandAll(part, macroEval));
		}
		return properList(newParts);
	}

	// Expands a macrolet: installs each local macro into the macro-time evaluator, walks
	// the body with them active (so calls to the locals expand), restores the previous
	// bindings, and returns the expanded body as a (progn ...) so the macrolet wrapper is
	// dropped. The compilers never see macrolet; the interpreter handles it natively.
	private static LispVal expandMacrolet(List<LispVal> parts, LispEvaluator macroEval) {
		List<LispVal> defs = parts.get(1) instanceof LispCons defsCons ? defsCons.toList() : List.of();
		java.util.LinkedHashMap<String, Object> saved = new java.util.LinkedHashMap<>();
		for (LispVal def : defs) {
			if (def instanceof LispCons defCons && defCons.isProperList()) {
				List<LispVal> dp = defCons.toList();
				if (dp.size() >= 2 && dp.get(0) instanceof LispSymbol name && !name.isKeyword()) {
					Object previous = macroEval.pushLocalMacro(name, dp.get(1), dp.subList(2, dp.size()));
					// Only remember the binding present before this macrolet (a duplicate
					// name pushed twice must still restore to the outermost prior value).
					if (!saved.containsKey(name.name())) {
						saved.put(name.name(), previous);
					}
				}
			}
		}
		try {
			List<LispVal> body = new ArrayList<>();
			body.add(new LispSymbol(LispNames.PROGN));
			for (int i = 2; i < parts.size(); i++) {
				body.add(expandAll(parts.get(i), macroEval));
			}
			if (body.size() == 1) {
				return LispNil.INSTANCE;
			}
			if (body.size() == 2) {
				return body.get(1);
			}
			return properList(body);
		}
		finally {
			for (java.util.Map.Entry<String, Object> entry : saved.entrySet()) {
				macroEval.popLocalMacro(entry.getKey(), entry.getValue());
			}
		}
	}

	// A dotted tail is only meaningful as data (inside quote); rebuilding a call form
	// through toList() would silently drop it, so reject it here instead.
	private static void requireProperCallForm(LispCons cons) {
		if (!cons.isProperList()) {
			throw new LispEvalException("Improper list in call position: " + cons.print());
		}
	}

	// Rebuilds a form keeping parts[0] and the given fixed subforms verbatim, walking
	// parts[from..] as expressions.
	private static LispVal rebuild(List<LispVal> parts, int from, LispEvaluator macroEval, LispVal... fixed) {
		List<LispVal> newParts = new ArrayList<>();
		newParts.add(parts.get(0));
		newParts.addAll(List.of(fixed));
		for (int i = from; i < parts.size(); i++) {
			newParts.add(expandAll(parts.get(i), macroEval));
		}
		return properList(newParts);
	}

	// Expands the init/step forms of a let/do binding list, keeping the bound names.
	private static LispVal expandBindings(LispVal bindings, LispEvaluator macroEval) {
		if (!(bindings instanceof LispCons bindingsCons)) {
			return bindings;
		}
		List<LispVal> newBindings = new ArrayList<>();
		for (LispVal binding : bindingsCons.toList()) {
			if (binding instanceof LispCons bindingCons) {
				List<LispVal> pair = bindingCons.toList();
				List<LispVal> newPair = new ArrayList<>();
				newPair.add(pair.get(0));
				for (int i = 1; i < pair.size(); i++) {
					newPair.add(expandAll(pair.get(i), macroEval));
				}
				newBindings.add(properList(newPair));
			}
			else {
				newBindings.add(binding);
			}
		}
		return properList(newBindings);
	}

	private static LispVal properList(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
