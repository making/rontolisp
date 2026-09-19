package am.ik.rontolisp.scheme;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Whether a lowered form may answer other than ONE value: the question a loop's leaf asks
 * to choose between storing its value in the loop's result variable, which keeps one, and
 * leaving the loop's block with every value (.kb/scheme-frontend.md, "Destination-driven
 * lowering"). Conservative in one direction only: a form it calls single-valued answers
 * one value, a form it cannot tell about is assumed to pass values along.
 */
final class SchemeValueCount {

	/**
	 * The Common Lisp operators that hand on the values of the code they run.
	 */
	private static final Set<String> PASSING_OPERATORS = Set.of("FUNCALL", "APPLY", "VALUES-LIST",
			"MULTIPLE-VALUE-CALL", "MULTIPLE-VALUE-PROG1", "BLOCK", "CATCH", "HANDLER-CASE", "HANDLER-BIND",
			"UNWIND-PROTECT", "THE", "PROGV", "EVAL");

	/**
	 * The {@code scheme.lisp} helpers whose tail may answer other than one value: a
	 * {@code (values ...)} of other than one argument, or a call that hands on a user
	 * procedure's values. Every other helper answers one. Pinned against the library
	 * source by {@code SchemeValueCountTest}.
	 */
	static final Set<String> PASSING_HELPERS = Set.of("%SCHEME-CALL-WITH-PORT", "%SCHEME-CALL/CC",
			"%SCHEME-DYNAMIC-WIND", "%SCHEME-EVAL", "%SCHEME-EVAL-APPLY", "%SCHEME-EVAL-BODY", "%SCHEME-EVAL-IN",
			"%SCHEME-EVAL-NAMED", "%SCHEME-EVAL-QUASI", "%SCHEME-EVAL-RECEIVER", "%SCHEME-EXACT-INTEGER-SQRT",
			"%SCHEME-GUARD", "%SCHEME-NUMBER-PREFIX", "%SCHEME-PARAMETERIZE", "%SCHEME-WITH-EXCEPTION-HANDLER",
			"%SCHEME-WITH-FILE", "%SCHEME-WITH-PORT-STREAMS");

	private static final String HELPER_PREFIX = "RONTOLISP::";

	private SchemeValueCount() {
	}

	/**
	 * Returns whether the form may answer other than one value.
	 * @param form a lowered form
	 * @return {@code false} only when the form answers exactly one value
	 */
	static boolean mayAnswerSeveral(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (!(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
			return true;
		}
		List<LispVal> parts = cons.toList();
		String name = head.name();
		return switch (name) {
			case "QUOTE", "FUNCTION", "LAMBDA" -> false;
			case "VALUES" -> parts.size() != 2;
			case "IF" -> parts.size() < 3 || mayAnswerSeveral(parts.get(2))
					|| (parts.size() > 3 && mayAnswerSeveral(parts.get(3)));
			case "PROGN", "LOCALLY", "AND", "OR" -> parts.size() > 1 && mayAnswerSeveral(parts.getLast());
			case "LET", "LET*", "MULTIPLE-VALUE-BIND", "WHEN", "UNLESS" ->
				parts.size() > 2 && mayAnswerSeveral(parts.getLast());
			case "COND" -> anyClauseAnswersSeveral(parts.subList(1, parts.size()));
			case "CASE" -> parts.size() < 2 || anyClauseAnswersSeveral(parts.subList(2, parts.size()));
			default -> passes(name);
		};
	}

	// A cond or case clause answers its last form; a clause of a test alone answers the
	// test's primary.
	private static boolean anyClauseAnswersSeveral(List<LispVal> clauses) {
		for (LispVal clause : clauses) {
			if (!(clause instanceof LispCons cons) || !cons.isProperList()) {
				return true;
			}
			List<LispVal> parts = cons.toList();
			if (parts.size() > 1 && mayAnswerSeveral(parts.getLast())) {
				return true;
			}
		}
		return false;
	}

	// An operator's own answer: a Common Lisp operator answers one value unless it hands
	// values on, a helper as listed, and anything else -- a user procedure, whose name
	// always has a lower-case letter (SchemeNames.mangle), or another package's
	// function -- may answer any number.
	private static boolean passes(String name) {
		if (name.startsWith(HELPER_PREFIX)) {
			return PASSING_HELPERS.contains(name.substring(HELPER_PREFIX.length()));
		}
		if (name.indexOf(':') >= 0) {
			return true;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c >= 'a' && c <= 'z') {
				return true;
			}
		}
		return PASSING_OPERATORS.contains(name);
	}

}
