package am.ik.rontolisp.eval;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
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
		// Also activate for macroexpand/macroexpand-1 calls: their literal quoted
		// arguments are folded to the expansion here, even when no macro is defined.
		if (program.stream().noneMatch(form -> isOperator(form, LispNames.DEFMACRO))
				&& program.stream().noneMatch(UserMacroExpander::usesMacroexpand)) {
			return program;
		}
		LispEvaluator macroEval = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
		List<LispVal> result = new ArrayList<>();
		for (LispVal form : program) {
			if (isOperator(form, LispNames.DEFMACRO)) {
				macroEval.eval(form);
				continue;
			}
			LispVal expanded = expandAll(form, macroEval);
			if (isOperator(expanded, LispNames.DEFMACRO)) {
				// A macro expanded into a macro definition: consume it as well.
				macroEval.eval(expanded);
				continue;
			}
			if (isOperator(expanded, LispNames.DEFUN)) {
				// Register (no body execution) so later macro bodies can call it.
				macroEval.eval(expanded);
			}
			result.add(expanded);
		}
		return result;
	}

	private static boolean isOperator(LispVal form, String name) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol sym && name.equals(sym.name());
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
			requireProperCallForm(cons);
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
