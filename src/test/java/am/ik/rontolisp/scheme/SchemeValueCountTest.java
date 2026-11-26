package am.ik.rontolisp.scheme;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.SchemeLibrary;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeValueCountTest {

	private static boolean several(String form) {
		return SchemeValueCount.mayAnswerSeveral(LispReader.readAllFromString(form).getFirst());
	}

	@Test
	void aUserProcedureCallMayAnswerSeveralAndACommonLispFunctionAnswersOne() {
		assertThat(several("(|two|)")).isTrue();
		assertThat(several("(|s%F| 1)")).isTrue();
		assertThat(several("(FUNCALL (RONTOLISP::%SCHEME-ENSURE-PROCEDURE |p|))")).isTrue();
		assertThat(several("(APPLY #'LIST |x|)")).isTrue();
		assertThat(several("(CAR |l|)")).isFalse();
		assertThat(several("|l|")).isFalse();
		assertThat(several("'(1 2)")).isFalse();
		assertThat(several("(LAMBDA NIL (|two|))")).isFalse();
	}

	@Test
	void valuesAnswersOneOnlyWithOneArgument() {
		assertThat(several("(VALUES 1)")).isFalse();
		assertThat(several("(VALUES 1 2)")).isTrue();
		assertThat(several("(VALUES)")).isTrue();
	}

	@Test
	void aTailTransparentFormAnswersWhatItsTailsAnswer() {
		assertThat(several("(IF (INTEGERP |a|) (EVENP |a|) (RONTOLISP::%SCHEME-EVEN? |a|))")).isFalse();
		assertThat(several("(IF |a| 1 (|two|))")).isTrue();
		assertThat(several("(IF |a| (|two|))")).isTrue();
		assertThat(several("(LET ((|x| (|two|))) |x|)")).isFalse();
		assertThat(several("(LET ((|x| 1)) (|two|))")).isTrue();
		assertThat(several("(PROGN (|two|) 1)")).isFalse();
		assertThat(several("(COND ((|two|)) (T 1))")).isFalse();
		assertThat(several("(COND (|a| (|two|)) (T 1))")).isTrue();
		assertThat(several("(OR (|two|) 1)")).isFalse();
		assertThat(several("(HANDLER-CASE 1 (ERROR (C) C))")).isTrue();
	}

	@Test
	void aHelperAnswersSeveralOnlyWhenListed() {
		assertThat(several("(RONTOLISP::%SCHEME-EXACT-INTEGER-SQRT 17)")).isTrue();
		assertThat(several("(RONTOLISP::%SCHEME-EVEN? 1)")).isFalse();
	}

	@Test
	void theListedHelpersAreExactlyTheOnesWhoseTailMayAnswerSeveral() {
		// A helper whose tail hands on other than one value -- a (values a b), a
		// values-list, a funcall of a user procedure -- must be listed, or a loop
		// exiting through it keeps the first value only; a listed helper that
		// answers one value costs its loops a block for nothing.
		Set<String> passing = new TreeSet<>();
		for (LispVal form : SchemeLibrary.everyVariantForms()) {
			String name = helperName(form);
			// A defun's body is a block of its name: a return-from answers for it too.
			if (name != null && (tailMayAnswerSeveral(((LispCons) form).toList().getLast()) || anyReturn(form))) {
				passing.add(name);
			}
		}
		assertThat(passing).containsExactlyInAnyOrderElementsOf(new TreeSet<>(SchemeValueCount.PASSING_HELPERS));
	}

	private static @Nullable String helperName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && head.name().equals("DEFUN")
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
				&& name.name().startsWith("RONTOLISP::")) {
			return name.name().substring("RONTOLISP::".length());
		}
		return null;
	}

	// The library is hand-written Common Lisp, so this walk knows more forms than the
	// lowering's: it is conservative, an operator it does not know passes values along.
	private static boolean tailMayAnswerSeveral(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (!(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
			return true;
		}
		List<LispVal> parts = cons.toList();
		String name = head.name();
		return switch (name) {
			case "QUOTE", "FUNCTION", "LAMBDA", "SETQ", "SETF", "PSETQ", "INCF", "DECF", "PUSH", "POP", "ERROR",
					"TAGBODY", "GO", "DECLARE", "MULTIPLE-VALUE-LIST", "RPLACA", "RPLACD" ->
				false;
			case "VALUES" -> parts.size() != 2;
			case "FUNCALL", "APPLY", "VALUES-LIST", "MULTIPLE-VALUE-CALL", "MULTIPLE-VALUE-PROG1", "EVAL" -> true;
			case "IF" -> anyTail(parts.subList(2, parts.size()));
			case "PROGN", "LOCALLY", "AND", "OR" -> parts.size() > 1 && tailMayAnswerSeveral(parts.getLast());
			case "LET", "LET*", "MULTIPLE-VALUE-BIND", "DESTRUCTURING-BIND", "WHEN", "UNLESS", "HANDLER-BIND",
					"RONTOLISP:WITH-MUTEX" ->
				parts.size() > 2 && tailMayAnswerSeveral(parts.getLast());
			case "PROG1" -> parts.size() > 1 && tailMayAnswerSeveral(parts.get(1));
			case "UNWIND-PROTECT", "IGNORE-ERRORS" -> parts.size() > 1 && tailMayAnswerSeveral(parts.get(1));
			case "COND" -> anyClause(parts.subList(1, parts.size()));
			case "CASE", "ECASE", "TYPECASE", "ETYPECASE" -> anyClause(parts.subList(2, parts.size()));
			case "HANDLER-CASE" -> tailMayAnswerSeveral(parts.get(1)) || anyClause(parts.subList(2, parts.size()));
			// Every exit of a block or a loop: its result forms and each return's value.
			case "BLOCK", "CATCH" -> tailMayAnswerSeveral(parts.getLast()) || anyReturn(cons);
			case "DO", "DO*" ->
				anyTail(((LispCons) parts.get(2)).toList().subList(1, ((LispCons) parts.get(2)).toList().size()))
						|| anyReturn(cons);
			case "DOLIST", "DOTIMES" -> {
				List<LispVal> spec = ((LispCons) parts.get(1)).toList();
				yield (spec.size() > 2 && tailMayAnswerSeveral(spec.get(2))) || anyReturn(cons);
			}
			case "FLET", "LABELS", "MACROLET" -> throw new AssertionError("teach this walk local functions: " + form);
			// Never returns here: the block or catch it leaves counts its values.
			case "RETURN", "RETURN-FROM", "THROW" -> false;
			// The library defines no local function, so any other operator is a Common
			// Lisp or a rontolisp function or a macro answering one value.
			default -> name.startsWith("RONTOLISP::")
					&& SchemeValueCount.PASSING_HELPERS.contains(name.substring("RONTOLISP::".length()));
		};
	}

	private static boolean anyTail(List<LispVal> forms) {
		return forms.stream().anyMatch(SchemeValueCountTest::tailMayAnswerSeveral);
	}

	private static boolean anyClause(List<LispVal> clauses) {
		for (LispVal clause : clauses) {
			if (clause instanceof LispCons cons && cons.isProperList()) {
				List<LispVal> parts = cons.toList();
				if (parts.size() > 1 && tailMayAnswerSeveral(parts.getLast())) {
					return true;
				}
			}
		}
		return false;
	}

	// Whether a return or return-from inside the form carries other than one value.
	private static boolean anyReturn(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol head && cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			if (head.name().equals("RETURN") && parts.size() == 2 && tailMayAnswerSeveral(parts.get(1))) {
				return true;
			}
			if ((head.name().equals("RETURN-FROM") || head.name().equals("THROW")) && parts.size() == 3
					&& tailMayAnswerSeveral(parts.get(2))) {
				return true;
			}
		}
		for (LispVal cur = cons; cur instanceof LispCons cell; cur = cell.cdr()) {
			if (anyReturn(cell.car())) {
				return true;
			}
		}
		return false;
	}

}
