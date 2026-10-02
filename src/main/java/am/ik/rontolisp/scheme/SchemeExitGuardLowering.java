package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * The exit guards of the Scheme lowering: the {@code catch} around the top-level forms a
 * file or a session entry runs, so an {@code exit} inside one unwinds through the
 * outstanding dynamic-wind afters and ends the process through {@code %scheme-exit} with
 * the thrown code -- and the session's value guard, which answers the guarded form's
 * values instead of its first.
 *
 * <p>
 * One slice of {@link SchemeLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class SchemeExitGuardLowering {

	private SchemeExitGuardLowering() {
	}

	private static LispVal quotedExitTag() {
		return SchemeLowering.list(SchemeLowering.symbol("QUOTE"), SchemeLowering.symbol(SchemeLowering.EXIT_TAG_NAME));
	}

	// Wraps one file top-level form -- a STATEMENT nobody reads the value of -- so an
	// exit inside it unwinds through the outstanding dynamic-wind afters to this
	// catch, which ends the process through %scheme-exit with the thrown code. A defun
	// or defstruct stays bare: the backends only hoist one that is a direct child of
	// the program, and defining never throws -- a body only runs inside some value
	// form's extent. The fresh cell tells a throw from normal completion, whatever the
	// code is: (exit '()) throws NIL, which a literal marker could not tell apart.
	static LispVal exitGuard(SchemeLowering s, LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& ("DEFUN".equals(head.name()) || "DEFSTRUCT".equals(head.name()))) {
			return form;
		}
		if (isTopLevelLoadCall(form)) {
			// A bare top-level (load "literal"): the compile path's LoadInliner only
			// inlines one it sees at the top level, and the guard's LET/CATCH shape
			// would hide it -- a path string merely SPELLING exit or eval would turn
			// every guard on and silence the inline. The forms that run under it carry
			// their own guards on both paths that execute them (the inlined file's
			// lowering here, the interpreter's per-file lowering at run time), so the
			// load call itself needs no guard.
			return form;
		}
		LispSymbol done = s.fresh("EXIT-DONE");
		LispSymbol code = s.fresh("EXIT-CODE");
		LispVal caught = SchemeLowering.list(SchemeLowering.symbol("CATCH"), quotedExitTag(),
				SchemeLowering.list(SchemeLowering.symbol("PROGN"), form, done));
		LispVal guarded = SchemeLowering.list(SchemeLowering.symbol("LET"),
				SchemeLowering.listOf(List.of(SchemeLowering.list(done,
						SchemeLowering.list(SchemeLowering.symbol("LIST"), LispNil.INSTANCE)))),
				SchemeLowering.list(SchemeLowering.symbol("LET"),
						SchemeLowering.listOf(List.of(SchemeLowering.list(code, caught))),
						SchemeLowering.list(SchemeLowering.symbol("IF"),
								SchemeLowering.list(SchemeLowering.symbol("EQ"), code, done), LispNil.INSTANCE,
								SchemeLowering.list(SchemeLowering.symbol(SchemeLowering.EXIT_FUNCTION_NAME), code))));
		return form instanceof LispCons lowered ? SchemeLowering.inherit(lowered, guarded) : guarded;
	}

	// Wraps one session entry the same way. A session has no whole file to wrap and no
	// artifact to keep small, so every entry is wrapped unconditionally -- including a
	// definition, whose value may throw -- while a defun or defstruct stays bare like
	// in a file. Every form but the last takes the statement guard (the session
	// discards their values); the last answers the entry's value, which is what the
	// prompt echoes. A last form that is itself a syntactic multiple-value producer --
	// in lowered code only (VALUES ...) can stand there alone -- keeps its shape with
	// its ARGUMENTS guarded instead: the prompt echoes through evalValues, which takes
	// the multi-value path only for that shape, so (values) echoes nothing and
	// (values 1 'a) echoes both.
	static List<LispVal> exitGuardEntry(SchemeLowering s, List<LispVal> forms) {
		if (forms.isEmpty()) {
			return forms;
		}
		List<LispVal> out = new ArrayList<>();
		for (int i = 0; i < forms.size() - 1; i++) {
			out.add(exitGuard(s, forms.get(i)));
		}
		LispVal last = forms.get(forms.size() - 1);
		if (last instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& ("DEFUN".equals(head.name()) || "DEFSTRUCT".equals(head.name()))) {
			out.add(last);
		}
		else if (isMvProducer(last)) {
			out.add(guardProducerArgs(s, (LispCons) last));
		}
		else {
			out.add(exitGuardValue(s, last));
		}
		return List.copyOf(out);
	}

	// The session's value guard: like the file's, but answers the form's VALUES -- all
	// of them, held as a list while the catch and the exit test run, because a value
	// that went through a variable and an (eq ...) is one value in Common Lisp
	// (.kb/multiple-values.md): (values 1 2) from a procedure echoes both lines, a
	// (values) echoes none.
	static LispVal exitGuardValue(SchemeLowering s, LispVal form) {
		LispSymbol done = s.fresh("EXIT-DONE");
		LispSymbol values = s.fresh("EXIT-VALUES");
		LispSymbol code = s.fresh("EXIT-CODE");
		LispVal caught = SchemeLowering.list(SchemeLowering.symbol("CATCH"), quotedExitTag(),
				SchemeLowering.list(SchemeLowering.symbol("PROGN"), SchemeLowering.list(SchemeLowering.symbol("SETQ"),
						values, SchemeLowering.list(SchemeLowering.symbol("MULTIPLE-VALUE-LIST"), form)), done));
		LispVal guarded = SchemeLowering.list(SchemeLowering.symbol("LET"),
				SchemeLowering.listOf(List.of(
						SchemeLowering.list(done, SchemeLowering.list(SchemeLowering.symbol("LIST"), LispNil.INSTANCE)),
						SchemeLowering.list(values, LispNil.INSTANCE))),
				SchemeLowering.list(SchemeLowering.symbol("LET"),
						SchemeLowering.listOf(List.of(SchemeLowering.list(code, caught))),
						SchemeLowering.list(SchemeLowering.symbol("IF"),
								SchemeLowering.list(SchemeLowering.symbol("EQ"), code, done),
								SchemeLowering.list(SchemeLowering.symbol("VALUES-LIST"), values),
								SchemeLowering.list(SchemeLowering.symbol(SchemeLowering.EXIT_FUNCTION_NAME), code))));
		return form instanceof LispCons lowered ? SchemeLowering.inherit(lowered, guarded) : guarded;
	}

	// Guards the arguments of a syntactic multiple-value producer in place, keeping
	// the producer's shape (and a zero-argument (VALUES) bare). Mirrors
	// LispMacroExpander's producer recognition, which the scheme package may not
	// import; in lowered code a producer head is always the real operator -- a user
	// binding of values lowers to a distinct lowercase symbol.
	private static LispVal guardProducerArgs(SchemeLowering s, LispCons form) {
		if (!form.isProperList()) {
			return exitGuardValue(s, form);
		}
		List<LispVal> parts = form.toList();
		List<LispVal> guarded = new ArrayList<>();
		guarded.add(parts.get(0));
		for (int i = 1; i < parts.size(); i++) {
			guarded.add(exitGuardValue(s, parts.get(i)));
		}
		return SchemeLowering.inherit(form, SchemeLowering.listOf(guarded));
	}

	private static boolean isMvProducer(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return false;
		}
		int size = cons.toList().size();
		return switch (op.name()) {
			case "VALUES" -> true;
			case "FLOOR", "CEILING", "ROUND", "TRUNCATE", "FFLOOR", "FCEILING", "FROUND", "FTRUNCATE" ->
				size == 2 || size == 3;
			case "GETHASH" -> size == 3 || size == 4;
			case "ARRAY-DISPLACEMENT" -> size == 2;
			case "SUBTYPEP" -> size == 3;
			case "FIND-SYMBOL", "INTERN" -> size == 2 || size == 3;
			case "READ-FROM-STRING" -> size == 2;
			default -> false;
		};
	}

	// Whether any top-level datum can reach the throwing exit: it spells exit -- as a
	// call, a first-class value or quoted data an eval may take apart -- or a string
	// one may read it out of (the same line the run-time procedure table draws), or it
	// spells eval, whose run-time data may name exit. Anything else cannot throw to
	// the tag, so it is emitted exactly as before and a program that never quits
	// compiles to the same bytes.
	static boolean mayThrowExit(List<LispVal> forms) {
		for (LispVal form : forms) {
			if (spellsExitOrEval(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean spellsExitOrEval(LispVal form) {
		while (form instanceof LispCons cons) {
			if (spellsExitOrEval(cons.car())) {
				return true;
			}
			form = cons.cdr();
		}
		return switch (form) {
			case LispSymbol symbol -> symbol.name().equals("exit") || symbol.name().equals("eval");
			case LispString string -> string.value().contains("exit");
			case am.ik.rontolisp.LispArray array -> {
				for (LispVal element : array.data()) {
					if (spellsExitOrEval(element)) {
						yield true;
					}
				}
				yield false;
			}
			case null, default -> false;
		};
	}

	/** Whether the form is the lowered shape of a {@code load} procedure call. */
	private static boolean isTopLevelLoadCall(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head && "LOAD".equals(head.name());
	}

}
