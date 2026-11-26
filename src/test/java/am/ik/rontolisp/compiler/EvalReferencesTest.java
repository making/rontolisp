package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReferencesTest {

	private static Set<String> definitions(String source) {
		Set<String> names = new TreeSet<>();
		for (LispVal form : EvalReferences.process(LispReader.readAllFromString(source))) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DEFUN.equals(head.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol name) {
				names.add(name.name());
			}
		}
		return names;
	}

	@Test
	void aProgramWhoseFormsSpellNoMacroCarriesNoTable() {
		List<LispVal> program = LispReader.readAllFromString("(print (eval '(+ 1 2)))");
		assertThat(EvalReferences.process(program)).isSameAs(program);
	}

	@Test
	void aMacroBringsItsRowAndWhatItExpandsTo() {
		assertThat(definitions("(eval '(prog2 1 2 3))")).contains(LispNames.EVAL_EXPANSION, "%EVAL-ROW-PROG2",
				"%EVAL-ROW-PROG1");
		assertThat(definitions("(eval (list 'incf 'x))")).contains("%EVAL-ROW-INCF");
		assertThat(definitions("(compile nil '(lambda () (let* ((a 1)) a)))")).contains("%EVAL-ROW-LET*");
	}

	@Test
	void anIterationReachesTheCatchOfABlockOnlyWhenItsFormsSpellAnExit() {
		assertThat(definitions("(eval '(dolist (x '(1)) x))")).contains("%EVAL-ROW-DOLIST")
			.doesNotContain("%EVAL-ROW-BLOCK", "%EVAL-CATCH");
		assertThat(definitions("(eval '(dolist (x '(1)) (return x)))")).contains("%EVAL-ROW-RETURN", "%EVAL-CATCH");
	}

	@Test
	void aLetAsksWhetherItBindsASpecialOnlyWhenItCanBindOne() {
		assertThat(definitions("(eval '(let ((x 1)) x))")).isEmpty();
		assertThat(definitions("(eval '(let ((*x* 1)) *x*))")).contains(LispNames.EVAL_SPECIAL_LET);
		assertThat(definitions("(defvar y 1) (eval '(let ((y 2)) y))")).contains(LispNames.EVAL_SPECIAL_LET);
		assertThat(definitions("(eval '(defvar *x* 1))")).contains(LispNames.EVAL_SPECIAL_LET);
	}

	@Test
	void aFormReadAtRunTimeCanReachTheWholeTable() {
		assertThat(definitions("(eval (read))")).contains(LispNames.EVAL_EXPANSION, LispNames.EVAL_PLACE_EXPANSION,
				LispNames.EVAL_SPECIAL_LET, LispNames.EVAL_ROW_FLET, "%EVAL-ROW-HANDLER-CASE");
		assertThat(definitions("(coerce (read) 'list)")).isEmpty();
	}

}
