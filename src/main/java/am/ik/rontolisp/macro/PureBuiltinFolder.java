package am.ik.rontolisp.macro;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import org.jspecify.annotations.Nullable;

/**
 * The one rule behind every one-off literal fold in the codebase, written down:
 *
 * <blockquote>if an operator is a PURE built-in and every argument is a literal, the
 * call's value is known at compile time.</blockquote>
 *
 * The point is not the arithmetic, it is REACHABILITY. A call to a generic runtime helper
 * pins its whole dispatch tree -- {@code (length "abc")} pins the sequence-length
 * dispatch, {@code (concatenate 'string ...)} the concatenate family,
 * {@code (princ (* 6 7))} the entire generic printer through its integer arm. A fold is a
 * reference DELETED, which is exactly the currency the WASM tree shaker and the JVM class
 * shaker spend. It also composes with the folds that were already there: a folded
 * argument is a literal, so the print family's static-text fold
 * ({@code WasmLiteralPrint}) and {@code format}'s literal lowering see it and drop the
 * runtime printer too.
 *
 * <p>
 * <b>Who is the authority on the value.</b> The fold evaluates in JAVA at compile time;
 * the program would have evaluated on one of four backends, and the pass is only correct
 * where those two answers are byte-identical. So {@link #TABLE} is a CURATED table with a
 * written reason per group, never a "looks pure" heuristic, and every entry has a row in
 * the differential harness that runs the folded call against the same call with its
 * arguments hidden behind a function parameter, on all four backends
 * ({@code PureBuiltinFolderTest.FOLD_PROBES} + its per-backend consumers). An entry with
 * no row does not ship. What is deliberately OUT, and why, is in
 * {@code .kb/pure-builtin-fold.md}.
 *
 * <p>
 * <b>Where it runs.</b> From the first lines of
 * {@link LispMacroExpander#expandTopLevelDefinitions}, the one whole-program pass both
 * compilers already call -- the {@code hoistLoadTimeValues} precedent -- so it needs no
 * registration in the CLI, the playground, the corpus tree-shaker tests or the ASDF
 * harness. It therefore lives in {@code macro}, not {@code compiler}: {@code compiler}
 * depends on {@code macro} and never the reverse, so a pass the expander itself invokes
 * cannot live there. The INTERPRETER does not fold -- it has no reachability to win and
 * it is the reference the harness compares against.
 *
 * <p>
 * <b>The three things that make it safe.</b>
 * <ul>
 * <li><b>Only an EVALUATED position folds.</b> {@code (let ((max 3)) ...)} holds a cons
 * whose head is a built-in name and whose argument is a literal, and it is a binding, not
 * a call. {@link #foldForm} therefore classifies the positions of every surface form that
 * has non-evaluated ones (the {@code UserMacroExpander.expandAllLocated} case list is the
 * reference) and never folds in one.</li>
 * <li><b>A user definition of the name wins.</b> {@link #shadowedOperators} blocks every
 * table name the program defines -- {@code defun}/{@code defmethod}/{@code defgeneric}/
 * {@code defmacro}/{@code define-compiler-macro} at top level, a {@code flet}/{@code
 * labels}/{@code macrolet} local anywhere -- which is strictly more conservative than
 * either half of the existing answer ({@code compiler.ShadowedBuiltins} on the compile
 * path, {@code LispEvaluator.defineDispatcher} in the interpreter). Under
 * {@code --dynamic} every name resolves at run time, so nothing folds at all.</li>
 * <li><b>A fold that would SIGNAL declines.</b> {@code (length 5)} is a runtime error the
 * program may never reach on a cold branch, so every entry returns {@code null} rather
 * than throwing, and {@link #foldCall} swallows a {@link RuntimeException} from one --
 * {@code expandFormat}'s fallback shape.</li>
 * </ul>
 *
 * <p>
 * <b>Identity.</b> A folded value is only ever a number, a character, a string, a symbol
 * or {@code t}/{@code nil} ({@link #foldedLiteral}) -- never an array, a cons or an
 * instance, whose identity a fold duplicated at two sites would forge. A STRING result is
 * safe because a string literal materializes FRESH on each evaluation on both compile
 * backends (the interpreter, where a literal is one shared object, is not folded).
 *
 * <p>
 * <b>Source positions.</b> An unchanged form is handed back as it was
 * ({@code LispCons.rebuiltList}); a rewritten one inherits the position of the cons it
 * replaces ({@link SourceProvenance#inherit}), so folding does not blank out the
 * {@code file:line:column} of everything between a top-level form and the fold.
 */
public final class PureBuiltinFolder {

	private PureBuiltinFolder() {
	}

	// ---------------------------------------------------------------- entry points

	/**
	 * Folds every pure-builtin call over literal arguments in a program, preserving
	 * identity where there is nothing to fold.
	 * @param program the top-level forms
	 * @param dynamic whether the backend compiles in late-binding mode
	 * ({@code --dynamic}), where a name resolves at run time and nothing may be folded
	 * @return the folded program, or {@code program} itself when nothing folded
	 */
	public static List<LispVal> foldProgram(List<LispVal> program, boolean dynamic) {
		if (dynamic) {
			return program;
		}
		Set<String> blocked = shadowedOperators(program);
		if (blocked == null) {
			// The program installs a function binding through (setf (symbol-function
			// ...)) with a computed name: no table entry can be assumed to still be the
			// built-in.
			return program;
		}
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal folded = foldForm(program.get(i), blocked);
			if (folded != program.get(i) && out == null) {
				out = new ArrayList<>(program.subList(0, i));
			}
			if (out != null) {
				out.add(folded);
			}
		}
		return out == null ? program : out;
	}

	/**
	 * The names this pass folds -- the keys of the curated table, so a test can assert
	 * that every one of them has a differential-harness row.
	 * @return the foldable operator names
	 */
	public static Set<String> foldedOperators() {
		return TABLE.keySet();
	}

	/**
	 * The compile-time value of a form, or {@code null} when it has none: a literal
	 * stands for itself, {@code (quote DATUM)} for its datum, and a table call whose
	 * every argument has one for the value the table computes. Recursive, so
	 * {@code (length (concatenate 'string "ab" "cd"))} reduces in one step.
	 *
	 * <p>
	 * <b>The caller owns the position question.</b> This answers "what would this form
	 * evaluate to", not "may this form be replaced": a cons in a binding list is not a
	 * call however call-shaped it looks.
	 */
	private static @Nullable LispVal literalValue(LispVal form, Set<String> blocked) {
		switch (form) {
			case LispString ignored -> {
				return form;
			}
			case LispInteger ignored -> {
				return form;
			}
			case LispBigInteger ignored -> {
				return form;
			}
			case LispChar ignored -> {
				return form;
			}
			case LispNil ignored -> {
				return form;
			}
			case LispTrue ignored -> {
				return form;
			}
			case LispSymbol sym -> {
				// A keyword evaluates to itself; every other bare symbol is a variable
				// reference, whose value the compile path does not know.
				return sym.isKeyword() ? form : null;
			}
			case LispArray ignored -> {
				// A #(...) literal is self-evaluating. Its identity is why no fold may
				// RETURN one (isFoldableResult), but reading its elements as an ARGUMENT
				// is what lets a literal table spelled as a vector fold like the list
				// spelling beside it.
				return form;
			}
			case LispIntVector ignored -> {
				return form;
			}
			case LispCons cons -> {
				if (cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(op.name())
						&& cons.cdr() instanceof LispCons datum && datum.cdr() instanceof LispNil) {
					return datum.car();
				}
				return foldCall(cons, blocked);
			}
			default -> {
				// A float, an array, a hash table, an instance: excluded by
				// .kb/pure-builtin-fold.md, each for its own reason.
				return null;
			}
		}
	}

	/**
	 * The value of a call form when its operator is an unshadowed table entry and every
	 * argument has a compile-time value, {@code null} otherwise. An entry that would
	 * SIGNAL, or that throws, declines: a call the program may never reach must not fail
	 * the compile.
	 */
	private static @Nullable LispVal foldCall(LispCons cons, Set<String> blocked) {
		if (!(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return null;
		}
		Fold fold = TABLE.get(op.name());
		if (fold == null || blocked.contains(op.name())) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		List<LispVal> args = new ArrayList<>(parts.size() - 1);
		for (int i = 1; i < parts.size(); i++) {
			LispVal value = literalValue(parts.get(i), blocked);
			if (value == null) {
				return null;
			}
			args.add(value);
		}
		try {
			LispVal value = fold.apply(args);
			return value != null && isFoldableResult(value) ? value : null;
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Whether a computed value may be baked into the program. Everything here is a value
	 * without identity, so two occurrences of a fold are indistinguishable from the two
	 * evaluations they replace; an array, a cons, a hash table or an instance is not, and
	 * a float is out for the reasons in {@code .kb/pure-builtin-fold.md}.
	 */
	private static boolean isFoldableResult(LispVal value) {
		return value instanceof LispInteger || value instanceof LispBigInteger || value instanceof LispChar
				|| value instanceof LispString || value instanceof LispNil || value instanceof LispTrue
				|| value instanceof LispSymbol || value instanceof LispIntVector;
	}

	/**
	 * The FORM a computed value is spelled as. A non-keyword symbol has to be re-quoted
	 * -- bare, it would read as a variable reference -- and everything else is
	 * self-evaluating.
	 */
	private static LispVal foldedLiteral(LispVal value) {
		if (value instanceof LispSymbol sym && !sym.isKeyword()) {
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(sym, LispNil.INSTANCE));
		}
		return value;
	}

	// ------------------------------------------------------- the shadowing question

	/**
	 * The table names the program takes over, so the fold leaves them alone. Deliberately
	 * coarser than the two existing answers to "is this name still the built-in"
	 * ({@code compiler.ShadowedBuiltins} for a {@code defmethod} on the compile path,
	 * {@code LispEvaluator.defineDispatcher} in the interpreter): a plain
	 * {@code (defun length ...)}, which neither of those covers, blocks the fold too, and
	 * a {@code flet}/{@code labels}/{@code macrolet} local blocks it for the whole
	 * program rather than for its lexical extent. Being MORE conservative than every
	 * consumer is always sound, and no real program defines {@code +}.
	 *
	 * <p>
	 * Matched by both the exact spelling and the package-stripped member name, so a
	 * library's {@code (defun cl-user::length ...)} blocks {@code length} as well.
	 * @return the blocked names, or {@code null} when the whole pass must stand down
	 */
	private static @Nullable Set<String> shadowedOperators(List<LispVal> program) {
		Set<String> blocked = new LinkedHashSet<>();
		for (LispVal form : program) {
			if (!collectShadowed(form, blocked)) {
				return null;
			}
		}
		// A program that mentions *print-case* can rebind it around any print, and NIL /
		// T render as symbols -- (princ-to-string nil) is "nil" under :downcase -- so the
		// two rendering entries stop being constant. Blocking the operators outright
		// rather than the two literal types keeps the rule one line and costs a program
		// that binds the variable nothing measurable.
		if (LispMacroExpander.usesPrintCase(program)) {
			blocked.add(LispNames.PRINC_TO_STRING);
			blocked.add(LispNames.PRIN1_TO_STRING);
		}
		return blocked;
	}

	/** Walks a form recording definitions; false when the pass must stand down. */
	private static boolean collectShadowed(LispVal form, Set<String> blocked) {
		if (!(form instanceof LispCons cons)) {
			return true;
		}
		if (cons.car() instanceof LispSymbol op) {
			switch (op.name()) {
				case LispNames.QUOTE -> {
					return true;
				}
				case LispNames.DEFUN, LispNames.DEFMETHOD, LispNames.DEFGENERIC, LispNames.DEFMACRO,
						LispNames.DEFINE_COMPILER_MACRO -> {
					if (cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
						block(name, blocked);
					}
				}
				case LispNames.FLET, LispNames.LABELS, LispNames.MACROLET -> {
					if (cons.cdr() instanceof LispCons rest && rest.car() instanceof LispCons defs
							&& defs.isProperList()) {
						for (LispVal def : defs.toList()) {
							if (def instanceof LispCons defCons && defCons.car() instanceof LispSymbol name) {
								block(name, blocked);
							}
						}
					}
				}
				case LispNames.SETF, LispNames.PSETF -> {
					// (setf (symbol-function 'f) g) installs a function binding the
					// compile path cannot see through. A literal name blocks that name;
					// a computed one blocks the pass.
					List<LispVal> parts = cons.isProperList() ? cons.toList() : List.of();
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						if (!collectFunctionBindingPlace(parts.get(i), blocked)) {
							return false;
						}
					}
				}
				default -> {
				}
			}
		}
		return collectShadowed(cons.car(), blocked) && collectShadowed(cons.cdr(), blocked);
	}

	/**
	 * False when a {@code (setf (symbol-function ...))} place names a computed function.
	 */
	private static boolean collectFunctionBindingPlace(LispVal place, Set<String> blocked) {
		if (!(place instanceof LispCons cons) || !(cons.car() instanceof LispSymbol accessor)
				|| !(LispNames.SYMBOL_FUNCTION.equals(accessor.name())
						|| LispNames.FDEFINITION.equals(accessor.name()))) {
			return true;
		}
		if (cons.cdr() instanceof LispCons argCell && argCell.car() instanceof LispCons quoted
				&& quoted.car() instanceof LispSymbol quoteOp && LispNames.QUOTE.equals(quoteOp.name())
				&& quoted.cdr() instanceof LispCons datum && datum.car() instanceof LispSymbol named) {
			block(named, blocked);
			return true;
		}
		return false;
	}

	private static void block(LispSymbol name, Set<String> blocked) {
		blocked.add(name.name());
		blocked.add(LispSymbol.memberName(name.name()));
	}

	// ------------------------------------------------------------------- the walker

	/**
	 * Folds a form in an EVALUATED position: every subform is walked in the position the
	 * operator gives it, and the result is folded when the operator is a table entry.
	 *
	 * <p>
	 * The switch is the list of surface forms that have NON-evaluated positions, and it
	 * mirrors {@code UserMacroExpander.expandAllLocated}, which solves the same problem
	 * for a user macro call. The default -- every argument is evaluated -- is right for a
	 * function call and for every special form whose subforms are all expressions
	 * ({@code if}, {@code progn}, {@code and}, {@code block}, {@code tagbody}, ...),
	 * because the elements a form like {@code tagbody} or {@code block} does not evaluate
	 * are ATOMS, which no fold can touch.
	 */
	private static LispVal foldForm(LispVal form, Set<String> blocked) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (!(cons.car() instanceof LispSymbol op)) {
			// ((lambda (x) ...) arg): every element is an expression.
			return cons.isProperList() ? rebuilt(cons, foldEach(cons.toList(), 0, blocked)) : form;
		}
		if (!cons.isProperList()) {
			// An improper list is never a call form; the legitimate appearances are data
			// patterns (a loop destructuring pattern) whose elements are plain names.
			return form;
		}
		List<LispVal> parts = cons.toList();
		switch (op.name()) {
			// -- data, walked into never -------------------------------------------
			case LispNames.QUOTE, LispNames.DECLARE, LispNames.DECLAIM, LispNames.PROCLAIM, LispNames.DEFPACKAGE,
					LispNames.IN_PACKAGE:
				return form;
			// A macro definition is a template the expander instantiates and a setf
			// expander is one too (the ShadowedBuiltins rule); a defstruct/defclass slot
			// spec and a defgeneric option list are data whose shape the expansion reads.
			// Folding inside any of them buys nothing worth the reasoning.
			case LispNames.DEFMACRO, LispNames.MACROLET, LispNames.DEFINE_COMPILER_MACRO, LispNames.DEFSETF,
					LispNames.DEFINE_SETF_EXPANDER, LispNames.DEFSTRUCT, LispNames.DEFCLASS, LispNames.DEFINE_CONDITION,
					LispNames.DEFGENERIC, LispNames.DEFTYPE:
				return form;
			// A place is not a call: (pop (car x)) reads AND writes it. The forms whose
			// argument list is places throughout are skipped whole; the ones that mix
			// places and values are split below.
			case LispNames.POP, LispNames.ROTATEF, LispNames.SHIFTF, LispNames.REMF, LispNames.WITH_SLOTS,
					LispNames.WITH_ACCESSORS, LispNames.DEFINE_MODIFY_MACRO:
				return form;
			// -- a name, then expressions ------------------------------------------
			case LispNames.FUNCTION:
				// #'name is a reference, #'(lambda ...) an expression.
				return parts.size() == 2 && parts.get(1) instanceof LispCons
						? rebuilt(cons, foldFrom(parts, 1, blocked)) : form;
			case LispNames.LAMBDA:
				// The lambda list is data (a default-value form inside it is left alone,
				// as UserMacroExpander leaves it).
				return parts.size() >= 2 ? rebuilt(cons, foldFrom(parts, 2, blocked)) : form;
			case LispNames.DEFUN:
				return parts.size() >= 3 ? rebuilt(cons, foldFrom(parts, 3, blocked)) : form;
			case LispNames.DEFMETHOD: {
				// (defmethod name [qualifier] (params...) body...)
				int lambdaList = parts.size() > 2 && parts.get(2) instanceof LispSymbol ? 3 : 2;
				return parts.size() > lambdaList ? rebuilt(cons, foldFrom(parts, lambdaList + 1, blocked)) : form;
			}
			case LispNames.THE:
				// (the TYPE form): the type specifier can be a cons -- (mod 8), (integer
				// 0 10) -- whose head is a table name.
				return parts.size() == 3 ? rebuilt(cons, foldFrom(parts, 2, blocked)) : form;
			case LispNames.EVAL_WHEN:
				// (eval-when (situation...) form...)
				return parts.size() >= 2 ? rebuilt(cons, foldFrom(parts, 2, blocked)) : form;
			case LispNames.MULTIPLE_VALUE_BIND, LispNames.MULTIPLE_VALUE_SETQ, LispNames.DESTRUCTURING_BIND:
				// The variable list / destructuring pattern is data.
				return parts.size() >= 2 ? rebuilt(cons, foldFrom(parts, 2, blocked)) : form;
			case LispNames.CHECK_TYPE:
				// (check-type place type [string]): both are data.
				return parts.size() >= 3 ? rebuilt(cons, foldFrom(parts, 3, blocked)) : form;
			case LispNames.ASSERT: {
				// (assert test (place...) [datum arg...]): only the datum arguments
				// fold. The TEST form is evaluated, but the failure message quotes its
				// source text ("The assertion (= 1 2) failed."), so folding it would
				// report "The assertion NIL failed." and lose the whole diagnostic --
				// the same reason check-type's place and type stay verbatim.
				if (parts.size() < 4) {
					return form;
				}
				return rebuilt(cons, foldFrom(parts, 3, blocked));
			}
			// A spec whose leading element is a name, or one whose elements are forms the
			// fold has no reason to reach; skipping it whole is the conservative answer
			// for both.
			case LispNames.PRINT_UNREADABLE_OBJECT, LispNames.WITH_PACKAGE_ITERATOR, LispNames.DO_EXTERNAL_SYMBOLS,
					LispNames.WITH_SIMPLE_RESTART, LispNames.PPRINT_LOGICAL_BLOCK:
				return parts.size() >= 2 ? rebuilt(cons, foldFrom(parts, 2, blocked)) : form;
			// -- binding lists ------------------------------------------------------
			case LispNames.LET, LispNames.LET_STAR, LispNames.SYMBOL_MACROLET, LispNames.HANDLER_BIND,
					LispNames.RESTART_BIND, LispNames.PROG, LispNames.PROG_STAR: {
				if (parts.size() < 2) {
					return form;
				}
				List<LispVal> rebuilt = new ArrayList<>(parts.size());
				rebuilt.add(parts.get(0));
				rebuilt.add(foldBindings(parts.get(1), blocked));
				rebuilt.addAll(foldEach(parts, 2, blocked));
				return rebuilt(cons, rebuilt);
			}
			case LispNames.DO, LispNames.DO_STAR: {
				// (do (binding...) (end-test result...) body...): the termination clause
				// is a LIST OF FORMS, not a call -- (do ((i 0)) (max 3)) ends on the
				// value of the variable `max`.
				if (parts.size() < 3) {
					return form;
				}
				List<LispVal> rebuilt = new ArrayList<>(parts.size());
				rebuilt.add(parts.get(0));
				rebuilt.add(foldBindings(parts.get(1), blocked));
				rebuilt.add(foldFormList(parts.get(2), blocked));
				rebuilt.addAll(foldEach(parts, 3, blocked));
				return rebuilt(cons, rebuilt);
			}
			case LispNames.COND: {
				// (cond (test body...) ...): a clause is a LIST OF FORMS whose first
				// element is the test, so (cond (max 3)) tests the variable `max` and
				// must not read as a call.
				List<LispVal> rebuilt = new ArrayList<>(parts.size());
				rebuilt.add(parts.get(0));
				for (int i = 1; i < parts.size(); i++) {
					rebuilt.add(foldFormList(parts.get(i), blocked));
				}
				return rebuilt(cons, rebuilt);
			}
			case LispNames.FLET, LispNames.LABELS: {
				if (parts.size() < 2) {
					return form;
				}
				List<LispVal> rebuilt = new ArrayList<>(parts.size());
				rebuilt.add(parts.get(0));
				rebuilt.add(foldLocalFunctions(parts.get(1), blocked));
				rebuilt.addAll(foldEach(parts, 2, blocked));
				return rebuilt(cons, rebuilt);
			}
			case LispNames.DOLIST, LispNames.DOTIMES, LispNames.WITH_OPEN_FILE, LispNames.WITH_OPEN_STREAM,
					LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING: {
				// (op (var expr...) body...): the var is a binding, the rest evaluates.
				if (parts.size() < 2) {
					return form;
				}
				List<LispVal> rebuilt = new ArrayList<>(parts.size());
				rebuilt.add(parts.get(0));
				rebuilt.add(foldBindingSpec(parts.get(1), blocked));
				rebuilt.addAll(foldEach(parts, 2, blocked));
				return rebuilt(cons, rebuilt);
			}
			// -- clause heads -------------------------------------------------------
			case LispNames.CASE, LispNames.ECASE, LispNames.CCASE, LispNames.TYPECASE, LispNames.ETYPECASE:
				// (case keyform (keys body...)...): the keys are unevaluated data.
				return foldClauses(cons, parts, blocked, 1);
			case LispNames.HANDLER_CASE, LispNames.RESTART_CASE:
				// (handler-case form (type (var...) body...)...): the head and the
				// variable list are both data.
				return foldClauses(cons, parts, blocked, 2);
			// -- places mixed with values -------------------------------------------
			case LispNames.SETF, LispNames.PSETF, LispNames.SETQ, LispNames.PSETQ:
				// (setf place value place value ...)
				return rebuilt(cons, foldAlternating(parts, blocked, 2));
			case LispNames.INCF, LispNames.DECF:
				// (incf place [delta])
				return rebuilt(cons, foldFrom(parts, 2, blocked));
			case LispNames.PUSH, LispNames.PUSHNEW:
				// (push value place ...): only the value evaluates as a form here; the
				// keyword options of pushnew are data.
				return parts.size() >= 2 ? rebuilt(cons, foldSelected(parts, blocked, 1)) : form;
			// -- loop ---------------------------------------------------------------
			case LispNames.LOOP: {
				// Clause keywords are atoms and expressions are walked, but a
				// destructuring pattern is data: a cons-headed or improper element
				// (`for ((x . y) . rest) on args`) must survive verbatim.
				List<LispVal> rebuilt = new ArrayList<>(parts.size());
				rebuilt.add(parts.get(0));
				for (int i = 1; i < parts.size(); i++) {
					LispVal part = parts.get(i);
					boolean pattern = part instanceof LispCons pc
							&& (!pc.isProperList() || !(pc.car() instanceof LispSymbol));
					rebuilt.add(pattern ? part : foldForm(part, blocked));
				}
				return rebuilt(cons, rebuilt);
			}
			default:
				break;
		}
		// An ordinary call: every argument is an expression, and the call itself may now
		// have become foldable.
		LispVal walked = rebuilt(cons, foldFrom(parts, 1, blocked));
		if (walked instanceof LispCons walkedCons) {
			LispVal value = foldCall(walkedCons, blocked);
			if (value != null) {
				return SourceProvenance.inherit(cons, foldedLiteral(value));
			}
		}
		return walked;
	}

	/** {@code parts[0..from)} verbatim, {@code parts[from..]} folded. */
	private static List<LispVal> foldFrom(List<LispVal> parts, int from, Set<String> blocked) {
		List<LispVal> out = new ArrayList<>(parts.size());
		out.addAll(parts.subList(0, Math.min(from, parts.size())));
		out.addAll(foldEach(parts, from, blocked));
		return out;
	}

	/** Each of {@code parts[from..]}, folded. */
	private static List<LispVal> foldEach(List<LispVal> parts, int from, Set<String> blocked) {
		List<LispVal> out = new ArrayList<>(Math.max(0, parts.size() - from));
		for (int i = from; i < parts.size(); i++) {
			out.add(foldForm(parts.get(i), blocked));
		}
		return out;
	}

	/** Only the listed indices are folded; every other element is verbatim data. */
	private static List<LispVal> foldSelected(List<LispVal> parts, Set<String> blocked, int... evaluated) {
		List<LispVal> out = new ArrayList<>(parts);
		for (int index : evaluated) {
			if (index < out.size()) {
				out.set(index, foldForm(out.get(index), blocked));
			}
		}
		return out;
	}

	/**
	 * A LIST OF FORMS -- a {@code cond} clause, a {@code do} termination clause -- rather
	 * than a form: every element folds on its own and the list itself is never a call.
	 */
	private static LispVal foldFormList(LispVal list, Set<String> blocked) {
		if (!(list instanceof LispCons cons) || !cons.isProperList()) {
			return list;
		}
		return rebuilt(cons, foldEach(cons.toList(), 0, blocked));
	}

	/**
	 * Elements at {@code first}, {@code first + 2}, ... are folded; the rest are data.
	 */
	private static List<LispVal> foldAlternating(List<LispVal> parts, Set<String> blocked, int first) {
		List<LispVal> out = new ArrayList<>(parts);
		for (int i = first; i < out.size(); i += 2) {
			out.set(i, foldForm(out.get(i), blocked));
		}
		return out;
	}

	/** A let-style binding list: each binding's name stays, its init/step forms fold. */
	private static LispVal foldBindings(LispVal bindings, Set<String> blocked) {
		if (!(bindings instanceof LispCons cons) || !cons.isProperList()) {
			return bindings;
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal binding : cons.toList()) {
			out.add(foldBindingSpec(binding, blocked));
		}
		return rebuilt(cons, out);
	}

	/** A single {@code (name form...)} spec: the name stays, the rest folds. */
	private static LispVal foldBindingSpec(LispVal spec, Set<String> blocked) {
		if (!(spec instanceof LispCons cons) || !cons.isProperList()) {
			return spec;
		}
		return rebuilt(cons, foldFrom(cons.toList(), 1, blocked));
	}

	/** A {@code (flet ((name lambda-list body...) ...))} definition list. */
	private static LispVal foldLocalFunctions(LispVal defs, Set<String> blocked) {
		if (!(defs instanceof LispCons cons) || !cons.isProperList()) {
			return defs;
		}
		List<LispVal> out = new ArrayList<>();
		for (LispVal def : cons.toList()) {
			if (def instanceof LispCons defCons && defCons.isProperList() && defCons.toList().size() >= 2) {
				out.add(rebuilt(defCons, foldFrom(defCons.toList(), 2, blocked)));
			}
			else {
				out.add(def);
			}
		}
		return rebuilt(cons, out);
	}

	/**
	 * A clause form: the operand at index 1 is an expression and each clause after it
	 * keeps its first {@code keep} elements verbatim.
	 */
	private static LispVal foldClauses(LispCons cons, List<LispVal> parts, Set<String> blocked, int keep) {
		if (parts.size() < 2) {
			return cons;
		}
		List<LispVal> out = new ArrayList<>(parts.size());
		out.add(parts.get(0));
		out.add(foldForm(parts.get(1), blocked));
		for (int i = 2; i < parts.size(); i++) {
			LispVal clause = parts.get(i);
			if (clause instanceof LispCons clauseCons && clauseCons.isProperList()) {
				out.add(rebuilt(clauseCons, foldFrom(clauseCons.toList(), keep, blocked)));
			}
			else {
				out.add(clause);
			}
		}
		return rebuilt(cons, out);
	}

	/**
	 * The rebuild every branch above goes through: {@code original} itself when nothing
	 * changed (the cons-identity rule, {@code .kb/source-positions.md}), otherwise a
	 * fresh list that inherits the original's source position.
	 */
	private static LispVal rebuilt(LispCons original, List<LispVal> elements) {
		return SourceProvenance.inherit(original, LispCons.rebuiltList(original, elements));
	}

	// -------------------------------------------------------------------- the table

	/** The value of one call's already-reduced arguments, or {@code null} to decline. */
	@FunctionalInterface
	private interface Fold {

		@Nullable LispVal apply(List<LispVal> args);

	}

	/**
	 * A folded integer may not grow without bound: {@code (expt 2 10000000)} would make
	 * the COMPILER do the unbounded work and then bake a megabyte of digits into the
	 * output. 4096 bits is past anything a program writes as a literal and far below
	 * either cost.
	 */
	private static final int MAX_FOLDED_BITS = 4096;

	/** The same ceiling for a folded string, in code points. */
	private static final int MAX_FOLDED_CHARS = 4096;

	/**
	 * The ceiling for a folded packed integer table, in elements. Unlike the two above it
	 * is a bound on the COMPILER's work rather than on the output: the elements are
	 * already spelled out in the source, and the folded table is SMALLER than the call it
	 * replaces on every backend, so a big one is exactly what this fold is for. 65,536 is
	 * past any hand-written table (chipz's largest is 288, ironclad's 256).
	 */
	private static final int MAX_FOLDED_ELEMENTS = 65_536;

	/**
	 * The curated table. Insertion-ordered because {@link #foldedOperators} is iterated
	 * by the harness ({@code .kb/emitted-output-determinism.md}).
	 *
	 * <p>
	 * The groups, and why each is byte-identical to what the four backends compute:
	 * <ul>
	 * <li><b>Exact integer arithmetic</b> -- every backend implements the full integer
	 * tower exactly at any magnitude ({@code .kb/wasm-bignum.md}), and Java's
	 * {@link BigInteger} is the same mathematics. {@code /} folds only when the quotient
	 * is an exact integer: a RATIO result would have to survive as a literal, and the
	 * WASM ratio tier has i32 components.</li>
	 * <li><b>Bitwise</b> -- two's complement of an unbounded integer, which is what
	 * {@link BigInteger} and {@code .kb/integer-bitwise-fast-paths.md} both
	 * implement.</li>
	 * <li><b>Numeric comparison and predicates</b> -- exact integer comparison, result
	 * {@code t}/{@code nil}.</li>
	 * <li><b>Characters</b> -- a character is a Unicode CODE POINT on all four backends
	 * ({@code .kb/characters-code-points.md}), so code/char conversion and ordering are
	 * code-point arithmetic. Case conversion is restricted to ASCII: the WASM case-fold
	 * table is not Java's {@code Character} table outside it ({@code .todo/269} is the
	 * fix, and folding is widened when it lands).</li>
	 * <li><b>String and list measurement</b> -- a string is indexed by code point
	 * everywhere, and an out-of-range index DECLINES rather than folds, because the
	 * compile backends do not bounds-check one ({@code .todo/186}) and a fold must not
	 * invent an answer the program would not have got.</li>
	 * <li><b>String production</b> -- the result is a fresh string on both compile
	 * backends, exactly as the call it replaces was. {@code princ-to-string} /
	 * {@code prin1-to-string} render only the types whose emitted renderer reproduces
	 * {@code LispVal.print()} / {@code display()} exactly -- the same list
	 * {@code WasmLiteralPrint} folds, FLOAT excluded for the same reason
	 * ({@code .todo/046}).</li>
	 * </ul>
	 */
	private static final Map<String, Fold> TABLE = table();

	private static Map<String, Fold> table() {
		Map<String, Fold> t = new LinkedHashMap<>();
		// -- exact integer arithmetic ------------------------------------------
		t.put(LispNames.ADD, args -> reduceIntegers(args, BigInteger.ZERO, BigInteger::add));
		t.put(LispNames.MUL, args -> reduceIntegers(args, BigInteger.ONE, BigInteger::multiply));
		t.put(LispNames.SUB, args -> {
			List<BigInteger> ns = integers(args);
			if (ns == null || ns.isEmpty()) {
				return null;
			}
			if (ns.size() == 1) {
				return integerLiteral(ns.get(0).negate());
			}
			BigInteger result = ns.get(0);
			for (int i = 1; i < ns.size(); i++) {
				result = result.subtract(ns.get(i));
			}
			return integerLiteral(result);
		});
		t.put(LispNames.DIV, args -> {
			List<BigInteger> ns = integers(args);
			if (ns == null || ns.size() < 2) {
				// The one-argument reciprocal is a ratio except for +-1, and a ratio
				// result is out of the table (see the class comment).
				return null;
			}
			BigInteger result = ns.get(0);
			for (int i = 1; i < ns.size(); i++) {
				BigInteger divisor = ns.get(i);
				if (divisor.signum() == 0) {
					return null;
				}
				BigInteger[] divRem = result.divideAndRemainder(divisor);
				if (divRem[1].signum() != 0) {
					return null;
				}
				result = divRem[0];
			}
			return integerLiteral(result);
		});
		t.put(LispNames.ONE_PLUS, args -> unaryInteger(args, n -> n.add(BigInteger.ONE)));
		t.put(LispNames.ONE_MINUS, args -> unaryInteger(args, n -> n.subtract(BigInteger.ONE)));
		t.put(LispNames.ABS, args -> unaryInteger(args, BigInteger::abs));
		t.put(LispNames.SIGNUM, args -> unaryInteger(args, n -> BigInteger.valueOf(n.signum())));
		t.put(LispNames.ISQRT, args -> unaryInteger(args, n -> n.signum() < 0 ? null : n.sqrt()));
		t.put(LispNames.MIN, args -> extremumInteger(args, true));
		t.put(LispNames.MAX, args -> extremumInteger(args, false));
		t.put(LispNames.GCD, args -> reduceIntegers(args, BigInteger.ZERO, BigInteger::gcd));
		t.put(LispNames.LCM, args -> reduceIntegers(args, BigInteger.ONE, PureBuiltinFolder::lcm));
		t.put(LispNames.MOD, args -> binaryInteger(args, (n, d) -> {
			if (d.signum() == 0) {
				return null;
			}
			// CL's mod is the FLOOR remainder: its sign follows the divisor.
			BigInteger rem = n.remainder(d);
			return rem.signum() != 0 && rem.signum() != d.signum() ? rem.add(d) : rem;
		}));
		t.put(LispNames.REM, args -> binaryInteger(args, (n, d) -> d.signum() == 0 ? null : n.remainder(d)));
		t.put(LispNames.EXPT, args -> binaryInteger(args, (base, power) -> {
			// A negative power is a ratio unless the base is +-1, and an exponent that
			// does not fit an int is beyond the size ceiling anyway.
			if (power.signum() < 0 || power.bitLength() > 31) {
				return null;
			}
			long bits = (long) Math.max(1, base.abs().bitLength()) * power.intValue();
			return bits > MAX_FOLDED_BITS ? null : base.pow(power.intValue());
		}));
		// -- bitwise ------------------------------------------------------------
		t.put(LispNames.LOGAND, args -> reduceIntegers(args, BigInteger.ONE.negate(), BigInteger::and));
		t.put(LispNames.LOGIOR, args -> reduceIntegers(args, BigInteger.ZERO, BigInteger::or));
		t.put(LispNames.LOGXOR, args -> reduceIntegers(args, BigInteger.ZERO, BigInteger::xor));
		t.put(LispNames.LOGNOT, args -> unaryInteger(args, BigInteger::not));
		t.put(LispNames.ASH, args -> binaryInteger(args, (n, count) -> {
			if (count.bitLength() > 31) {
				return null;
			}
			int shift = count.intValue();
			return (long) n.bitLength() + Math.max(shift, 0) > MAX_FOLDED_BITS ? null : n.shiftLeft(shift);
		}));
		t.put(LispNames.INTEGER_LENGTH, args -> unaryInteger(args, n -> BigInteger.valueOf(n.bitLength())));
		t.put(LispNames.LOGBITP, args -> {
			List<BigInteger> ns = integers(args);
			if (ns == null || ns.size() != 2 || ns.get(0).signum() < 0 || ns.get(0).bitLength() > 31) {
				return null;
			}
			return bool(ns.get(1).testBit(ns.get(0).intValue()));
		});
		// -- numeric comparison and predicates ----------------------------------
		t.put(LispNames.EQ, args -> compareIntegers(args, c -> c == 0));
		t.put(LispNames.LT, args -> compareIntegers(args, c -> c < 0));
		t.put(LispNames.GT, args -> compareIntegers(args, c -> c > 0));
		t.put(LispNames.LE, args -> compareIntegers(args, c -> c <= 0));
		t.put(LispNames.GE, args -> compareIntegers(args, c -> c >= 0));
		t.put(LispNames.NE, args -> {
			List<BigInteger> ns = integers(args);
			if (ns == null || ns.isEmpty()) {
				return null;
			}
			// /= is PAIRWISE distinct, not merely adjacent-distinct.
			for (int i = 0; i < ns.size(); i++) {
				for (int j = i + 1; j < ns.size(); j++) {
					if (ns.get(i).equals(ns.get(j))) {
						return LispNil.INSTANCE;
					}
				}
			}
			return LispTrue.INSTANCE;
		});
		t.put(LispNames.ZEROP, args -> unaryIntegerTest(args, n -> n.signum() == 0));
		t.put(LispNames.PLUSP, args -> unaryIntegerTest(args, n -> n.signum() > 0));
		t.put(LispNames.MINUSP, args -> unaryIntegerTest(args, n -> n.signum() < 0));
		t.put(LispNames.EVENP, args -> unaryIntegerTest(args, n -> !n.testBit(0)));
		t.put(LispNames.ODDP, args -> unaryIntegerTest(args, n -> n.testBit(0)));
		// -- characters ---------------------------------------------------------
		t.put(LispNames.CHAR_CODE,
				args -> args.size() == 1 && args.get(0) instanceof LispChar c ? new LispInteger(c.codePoint()) : null);
		t.put(LispNames.CODE_CHAR, args -> {
			List<BigInteger> ns = integers(args);
			if (ns == null || ns.size() != 1 || ns.get(0).bitLength() > 31) {
				return null;
			}
			int code = ns.get(0).intValue();
			// A surrogate is not a character, and outside the code-point range the
			// backends answer for themselves; only the obviously-equal case folds.
			boolean surrogate = code >= 0xD800 && code <= 0xDFFF;
			return code >= 0 && code <= 0x10FFFF && !surrogate ? new LispChar(code) : null;
		});
		t.put(LispNames.CHAR_UPCASE, args -> caseFoldChar(args, true));
		t.put(LispNames.CHAR_DOWNCASE, args -> caseFoldChar(args, false));
		t.put(LispNames.CHAR_EQ, args -> compareChars(args, c -> c == 0));
		t.put(LispNames.CHAR_LT, args -> compareChars(args, c -> c < 0));
		t.put(LispNames.CHAR_GT, args -> compareChars(args, c -> c > 0));
		t.put(LispNames.CHAR_LE, args -> compareChars(args, c -> c <= 0));
		t.put(LispNames.CHAR_GE, args -> compareChars(args, c -> c >= 0));
		// -- string and list measurement ----------------------------------------
		t.put(LispNames.LENGTH, args -> {
			if (args.size() != 1) {
				return null;
			}
			return switch (args.get(0)) {
				case LispString s -> new LispInteger(s.codePointCount());
				case LispNil ignored -> new LispInteger(0);
				case LispCons cons -> cons.isProperList() ? new LispInteger(cons.toList().size()) : null;
				default -> null;
			};
		});
		t.put(LispNames.CHAR, args -> stringRef(args));
		t.put(LispNames.SCHAR, args -> stringRef(args));
		t.put(LispNames.STRING_EQ,
				args -> args.size() == 2 && args.get(0) instanceof LispString a && args.get(1) instanceof LispString b
						? bool(a.value().equals(b.value())) : null);
		t.put(LispNames.NTH, args -> {
			List<BigInteger> index = args.isEmpty() ? null : integers(args.subList(0, 1));
			if (args.size() != 2 || index == null || index.get(0).signum() < 0 || index.get(0).bitLength() > 31) {
				return null;
			}
			return listElement(args.get(1), index.get(0).intValue());
		});
		t.put(LispNames.CAR, args -> args.size() == 1 ? listElement(args.get(0), 0) : null);
		t.put(LispNames.FIRST, args -> args.size() == 1 ? listElement(args.get(0), 0) : null);
		t.put(LispNames.SECOND, args -> args.size() == 1 ? listElement(args.get(0), 1) : null);
		t.put(LispNames.THIRD, args -> args.size() == 1 ? listElement(args.get(0), 2) : null);
		// -- string production --------------------------------------------------
		t.put(LispNames.SYMBOL_NAME, args -> args.size() == 1 ? symbolName(args.get(0)) : null);
		t.put(LispNames.PRINC_TO_STRING, args -> args.size() == 1 ? renderedLiteral(args.get(0), false) : null);
		t.put(LispNames.PRIN1_TO_STRING, args -> args.size() == 1 ? renderedLiteral(args.get(0), true) : null);
		t.put(LispNames.STRING_UPCASE, args -> caseFoldString(args, true));
		t.put(LispNames.STRING_DOWNCASE, args -> caseFoldString(args, false));
		t.put(LispNames.CONCATENATE, args -> {
			// Only the STRING result family: a list or vector result is an object with
			// identity (.kb/concatenate-result-families.md).
			if (args.size() < 2 || !(args.get(0) instanceof LispSymbol type)
					|| !LispNames.STRING.equals(LispSymbol.memberName(type.name()))) {
				return null;
			}
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i < args.size(); i++) {
				if (!(args.get(i) instanceof LispString s)) {
					return null;
				}
				sb.append(s.value());
			}
			return sb.codePointCount(0, sb.length()) > MAX_FOLDED_CHARS ? null : new LispString(sb.toString());
		});
		// -- packed integer tables ----------------------------------------------
		t.put(LispNames.COERCE,
				args -> args.size() == 2 ? packedIntVector(args.get(0), packedWidth(args.get(1))) : null);
		t.put(LispNames.MAKE_ARRAY, PureBuiltinFolder::foldMakeArray);
		t.put(LispNames.SUBSEQ, args -> {
			if (args.size() < 2 || args.size() > 3 || !(args.get(0) instanceof LispString s)) {
				return null;
			}
			List<BigInteger> bounds = integers(args.subList(1, args.size()));
			if (bounds == null) {
				return null;
			}
			int count = s.codePointCount();
			int start = boundedIndex(bounds.get(0), count);
			int end = bounds.size() == 2 ? boundedIndex(bounds.get(1), count) : count;
			if (start < 0 || end < 0 || start > end) {
				return null;
			}
			String value = s.value();
			int from = value.offsetByCodePoints(0, start);
			return new LispString(value.substring(from, value.offsetByCodePoints(from, end - start)));
		});
		return java.util.Collections.unmodifiableMap(t);
	}

	// ------------------------------------------------------ the packed-table folds

	/**
	 * A literal lookup table costs THREE TIMES its own bytes when it is built at run
	 * time: {@code (coerce '(...) '(vector (unsigned-byte 32)))} -- the way every CL
	 * library spells one -- conses the element list first, at around 11.8 bytes of wasm
	 * per element, where the packed vector it becomes is 4. Folding the call to the
	 * {@link LispIntVector} literal it computes hands the backends the table as DATA,
	 * which is what {@code WasmQuoteCompiler.compileIntVectorLiteral} bakes into the
	 * module's data segment.
	 *
	 * <p>
	 * <b>Why a packed vector may be a fold RESULT when an array may not.</b> The identity
	 * rule excludes an array because two evaluations of one fold would hand out one
	 * object where the program built two. A packed literal does not: both compile
	 * backends materialize it FRESH at every evaluation (the wasm one allocates the array
	 * and copies the segment into it, the JVM one builds a new {@code long[]}), exactly
	 * as a string literal does and for the same measured reason -- so a folded table is
	 * as mutable and as unshared as the call it replaces. The INTERPRETER, where a
	 * literal is one shared object, does not fold.
	 *
	 * <p>
	 * The fold is element-type EXACT: a value that does not fit the declared width
	 * declines rather than being masked, and so does a non-integer element, an improper
	 * list, and an element count past {@link #MAX_FOLDED_ELEMENTS}. A {@code deftype}
	 * alias of a packed designator declines too -- the fold runs before the deftype
	 * registry is populated, so it cannot resolve one, and the run-time
	 * {@code %seq-int-vector} lowering still builds the same value.
	 * @param sequence the already-reduced sequence argument
	 * @param width the packed element width the result type asks for, 0 for none
	 * @return the packed literal, or null to decline
	 */
	private static @Nullable LispVal packedIntVector(LispVal sequence, int width) {
		if (width == 0) {
			return null;
		}
		List<LispVal> elements = literalElements(sequence);
		if (elements == null || elements.size() > MAX_FOLDED_ELEMENTS) {
			return null;
		}
		long limit = width == 32 ? 0xFFFFFFFFL : (1L << width) - 1;
		long[] data = new long[elements.size()];
		for (int i = 0; i < data.length; i++) {
			BigInteger n = switch (elements.get(i)) {
				case LispInteger v -> BigInteger.valueOf(v.value());
				case LispBigInteger v -> v.value();
				default -> null;
			};
			if (n == null || n.signum() < 0 || n.bitLength() > 32 || n.longValue() > limit) {
				return null;
			}
			data[i] = n.longValue();
		}
		return new LispIntVector(width, data);
	}

	/**
	 * {@code (make-array N :element-type '(unsigned-byte 8|16|32) :initial-contents
	 * '(...))} -- the other spelling of the same literal table -- as its packed literal.
	 *
	 * <p>
	 * {@code :initial-contents} is REQUIRED: without it the call is an allocation whose
	 * size is the only thing the compiler knows, and folding
	 * {@code (make-array 8192 :element-type '(unsigned-byte 8))} would bake 8 KB of zeros
	 * into the module in place of one {@code array.new_default}. Any other keyword
	 * ({@code :fill-pointer}, {@code :adjustable}, {@code :displaced-to},
	 * {@code :initial-element}) declines, as does a rank other than 1 and a dimension
	 * that disagrees with the contents.
	 */
	private static @Nullable LispVal foldMakeArray(List<LispVal> args) {
		if (args.isEmpty() || args.size() % 2 != 1) {
			return null;
		}
		LispVal elementType = null;
		LispVal contents = null;
		for (int i = 1; i + 1 < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				return null;
			}
			switch (LispSymbol.memberName(key.name())) {
				case "ELEMENT-TYPE" -> elementType = args.get(i + 1);
				case "INITIAL-CONTENTS" -> contents = args.get(i + 1);
				default -> {
					return null;
				}
			}
		}
		if (contents == null) {
			return null;
		}
		LispVal packed = packedIntVector(contents, LispNames.unsignedByteWidth(elementType));
		return (packed instanceof LispIntVector iv && iv.length() == rankOneLength(args.get(0))) ? packed : null;
	}

	/** The length a rank-1 {@code make-array} dimension argument asks for, or -1. */
	private static int rankOneLength(LispVal dimensions) {
		LispVal size = (dimensions instanceof LispCons cons && cons.cdr() instanceof LispNil) ? cons.car() : dimensions;
		return (size instanceof LispInteger n && n.value() >= 0 && n.value() <= MAX_FOLDED_ELEMENTS) ? (int) n.value()
				: -1;
	}

	/** The elements of a literal sequence -- a proper list or a vector -- or null. */
	private static @Nullable List<LispVal> literalElements(LispVal sequence) {
		return switch (sequence) {
			case LispNil ignored -> List.of();
			case LispCons cons -> cons.isProperList() ? cons.toList() : null;
			case LispArray array -> {
				if (array.dimensions().length != 1) {
					yield null;
				}
				// An unfilled slot is null in the backing store, which is not an integer:
				// it goes in as nil so the element check declines it like any other
				// non-integer, rather than tripping over the null.
				List<LispVal> out = new ArrayList<>(array.data().length);
				for (LispVal element : array.data()) {
					out.add(element == null ? LispNil.INSTANCE : element);
				}
				yield out;
			}
			case LispIntVector iv -> {
				List<LispVal> out = new ArrayList<>(iv.length());
				for (int i = 0; i < iv.length(); i++) {
					out.add(new LispInteger(iv.elementAt(i)));
				}
				yield out;
			}
			default -> null;
		};
	}

	/** The packed element width a literal result-type designator asks for, or 0. */
	private static int packedWidth(LispVal designator) {
		return LispNames.packedVectorWidth(designator);
	}

	// --------------------------------------------------------------- table helpers

	private static @Nullable List<BigInteger> integers(List<LispVal> args) {
		List<BigInteger> out = new ArrayList<>(args.size());
		for (LispVal arg : args) {
			BigInteger n = switch (arg) {
				case LispInteger i -> BigInteger.valueOf(i.value());
				case LispBigInteger b -> b.value();
				default -> null;
			};
			if (n == null) {
				return null;
			}
			out.add(n);
		}
		return out;
	}

	private static LispVal integerLiteral(BigInteger value) {
		// bitLength() < 64 holds exactly for the signed long range.
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	private static LispVal bool(boolean value) {
		return value ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	/**
	 * An n-ary reduction. The ZERO-argument call declines rather than folding to the
	 * operator's identity: {@code (+)} is worth nothing, and a one-element list whose
	 * head is a table name is exactly the shape a non-evaluated position can hand the
	 * walker by mistake, so not folding it removes a whole class of accidents for free.
	 */
	private static @Nullable LispVal reduceIntegers(List<LispVal> args, BigInteger identity,
			java.util.function.BinaryOperator<BigInteger> op) {
		List<BigInteger> ns = integers(args);
		if (ns == null || ns.isEmpty()) {
			return null;
		}
		BigInteger result = identity;
		for (BigInteger n : ns) {
			result = op.apply(result, n);
			if (result.bitLength() > MAX_FOLDED_BITS) {
				return null;
			}
		}
		return integerLiteral(result);
	}

	private static @Nullable LispVal unaryInteger(List<LispVal> args, UnaryIntegerOp op) {
		List<BigInteger> ns = integers(args);
		if (ns == null || ns.size() != 1) {
			return null;
		}
		BigInteger result = op.apply(ns.get(0));
		return result == null || result.bitLength() > MAX_FOLDED_BITS ? null : integerLiteral(result);
	}

	@FunctionalInterface
	private interface UnaryIntegerOp {

		@Nullable BigInteger apply(BigInteger value);

	}

	private static @Nullable LispVal binaryInteger(List<LispVal> args, BinaryIntegerOp op) {
		List<BigInteger> ns = integers(args);
		if (ns == null || ns.size() != 2) {
			return null;
		}
		BigInteger result = op.apply(ns.get(0), ns.get(1));
		return result == null || result.bitLength() > MAX_FOLDED_BITS ? null : integerLiteral(result);
	}

	@FunctionalInterface
	private interface BinaryIntegerOp {

		@Nullable BigInteger apply(BigInteger left, BigInteger right);

	}

	private static @Nullable LispVal unaryIntegerTest(List<LispVal> args,
			java.util.function.Predicate<BigInteger> test) {
		List<BigInteger> ns = integers(args);
		return ns == null || ns.size() != 1 ? null : bool(test.test(ns.get(0)));
	}

	private static @Nullable LispVal extremumInteger(List<LispVal> args, boolean minimum) {
		List<BigInteger> ns = integers(args);
		if (ns == null || ns.isEmpty()) {
			return null;
		}
		BigInteger result = ns.get(0);
		for (BigInteger n : ns) {
			int cmp = n.compareTo(result);
			if (minimum ? cmp < 0 : cmp > 0) {
				result = n;
			}
		}
		return integerLiteral(result);
	}

	private static BigInteger lcm(BigInteger a, BigInteger b) {
		if (a.signum() == 0 || b.signum() == 0) {
			return BigInteger.ZERO;
		}
		return a.divide(a.gcd(b)).multiply(b).abs();
	}

	private static @Nullable LispVal compareIntegers(List<LispVal> args, java.util.function.IntPredicate holds) {
		List<BigInteger> ns = integers(args);
		if (ns == null || ns.isEmpty()) {
			return null;
		}
		for (int i = 0; i + 1 < ns.size(); i++) {
			if (!holds.test(ns.get(i).compareTo(ns.get(i + 1)))) {
				return LispNil.INSTANCE;
			}
		}
		return LispTrue.INSTANCE;
	}

	private static @Nullable LispVal compareChars(List<LispVal> args, java.util.function.IntPredicate holds) {
		if (args.isEmpty()) {
			return null;
		}
		int[] codes = new int[args.size()];
		for (int i = 0; i < args.size(); i++) {
			if (!(args.get(i) instanceof LispChar c)) {
				return null;
			}
			codes[i] = c.codePoint();
		}
		for (int i = 0; i + 1 < codes.length; i++) {
			if (!holds.test(Integer.compare(codes[i], codes[i + 1]))) {
				return LispNil.INSTANCE;
			}
		}
		return LispTrue.INSTANCE;
	}

	/**
	 * Case conversion over the FULL Unicode range. Every backend's
	 * {@code char-upcase}/{@code char-downcase} is
	 * {@code Character.toUpperCase(int)}/{@code toLowerCase(int)} -- the WASM one through
	 * the compressed case-fold table todo-267 generated FROM those methods -- and that
	 * was checked here rather than assumed: a checksum of both mappings over every code
	 * point in {@code [0, 0x10FFFF]} minus the surrogates is identical on the
	 * interpreter, the JVM, both WASM backends and plain Java. (The case-insensitive
	 * COMPARE operators are a different story and are not in the table at all --
	 * {@code .todo/269}.)
	 */
	private static @Nullable LispVal caseFoldChar(List<LispVal> args, boolean upcase) {
		if (args.size() != 1 || !(args.get(0) instanceof LispChar c)) {
			return null;
		}
		return new LispChar(upcase ? Character.toUpperCase(c.codePoint()) : Character.toLowerCase(c.codePoint()));
	}

	/**
	 * {@code string-upcase}/{@code string-downcase}: CL folds CHARACTER by character, so
	 * no mapping changes the string's length and the per-character agreement above
	 * carries over (checked the same way, by a per-code-point checksum on all four
	 * backends).
	 */
	private static @Nullable LispVal caseFoldString(List<LispVal> args, boolean upcase) {
		if (args.size() != 1 || !(args.get(0) instanceof LispString s)) {
			return null;
		}
		String value = s.value();
		if (value.codePointCount(0, value.length()) > MAX_FOLDED_CHARS) {
			return null;
		}
		StringBuilder sb = new StringBuilder(value.length());
		value.codePoints()
			.forEach(cp -> sb.appendCodePoint(upcase ? Character.toUpperCase(cp) : Character.toLowerCase(cp)));
		return new LispString(sb.toString());
	}

	/** {@code (char s i)} / {@code (schar s i)}: an out-of-range index declines. */
	private static @Nullable LispVal stringRef(List<LispVal> args) {
		if (args.size() != 2 || !(args.get(0) instanceof LispString s)) {
			return null;
		}
		List<BigInteger> index = integers(args.subList(1, 2));
		if (index == null || index.get(0).signum() < 0 || index.get(0).bitLength() > 31) {
			return null;
		}
		int i = index.get(0).intValue();
		return i < s.codePointCount() ? new LispChar(s.codePointAt(i)) : null;
	}

	/** An index that is in {@code [0, count]}, or -1. */
	private static int boundedIndex(BigInteger index, int count) {
		if (index.signum() < 0 || index.bitLength() > 31) {
			return -1;
		}
		int value = index.intValue();
		return value <= count ? value : -1;
	}

	/** The nth element of a literal list, or {@code null} on anything else. */
	private static @Nullable LispVal listElement(LispVal list, int index) {
		LispVal current = list;
		for (int i = 0; i < index; i++) {
			if (!(current instanceof LispCons cons)) {
				return current instanceof LispNil ? LispNil.INSTANCE : null;
			}
			current = cons.cdr();
		}
		return switch (current) {
			case LispCons cons -> cons.car();
			case LispNil nil -> nil;
			default -> null;
		};
	}

	/** {@code symbol-name}: the package-stripped, marker-stripped spelling. */
	private static @Nullable LispVal symbolName(LispVal value) {
		return switch (value) {
			case LispSymbol sym -> new LispString(LispSymbol.memberName(sym.name()));
			case LispTrue ignored -> new LispString("T");
			case LispNil ignored -> new LispString("NIL");
			default -> null;
		};
	}

	/**
	 * The text a value renders as, for the types whose emitted renderer reproduces
	 * {@link LispVal#print()} / {@code display()} exactly -- the same set
	 * {@code WasmLiteralPrint} folds. A FLOAT is deliberately absent: {@code _print_f64}
	 * and {@code LispDouble.print()} disagree at large magnitudes ({@code .todo/046}), so
	 * folding one would give a program two spellings of the same value.
	 */
	private static @Nullable LispVal renderedLiteral(LispVal value, boolean readably) {
		String text = switch (value) {
			case LispString s -> readably ? s.print() : s.value();
			case LispInteger i -> i.print();
			case LispBigInteger b -> b.print();
			case LispChar c -> readably ? c.print() : c.display();
			case LispNil nil -> nil.print();
			case LispTrue t -> t.print();
			default -> null;
		};
		return text == null || text.length() > MAX_FOLDED_CHARS ? null : new LispString(text);
	}

}
