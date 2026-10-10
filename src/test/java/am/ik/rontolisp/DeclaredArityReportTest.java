package am.ik.rontolisp;

import java.util.List;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a function body's {@code %arity-report} declaration is found, and the message it
 * spells.
 */
class DeclaredArityReportTest {

	private static final DeclaredArityReport REPORT = new DeclaredArityReport("W (", ") f");

	@Test
	void theMessageIsThePrefixTheCountAndTheSuffix() {
		assertThat(REPORT.message(3)).isEqualTo("W (3) f");
		assertThat(REPORT.declaration().print()).isEqualTo("(DECLARE (%ARITY-REPORT \"W (\" \") f\"))");
	}

	@Test
	void aLeadingDeclarationIsFoundPastADocstringAndOtherDeclarations() {
		assertThat(DeclaredArityReport.of(body("(declare (%arity-report \"W (\" \") f\")) x"))).isEqualTo(REPORT);
		assertThat(DeclaredArityReport
			.of(body("\"doc\" (declare (ignorable x)) (declare (%arity-report \"W (\" \") f\")) x"))).isEqualTo(REPORT);
		assertThat(DeclaredArityReport.of(body("(declare (fixnum x) (rontolisp::%arity-report \"W (\" \") f\")) x")))
			.isEqualTo(REPORT);
		// inside the block a defun body is wrapped in on its way to a backend
		assertThat(DeclaredArityReport.of(body("(block f (declare (%arity-report \"W (\" \") f\")) x)")))
			.isEqualTo(REPORT);
	}

	@Test
	void aDeclarationPastTheBodysHeadOrMalformedDeclaresNothing() {
		assertThat(DeclaredArityReport.of(body("x (declare (%arity-report \"W (\" \") f\"))"))).isNull();
		assertThat(DeclaredArityReport.of(body("(let ((y 1)) (declare (%arity-report \"W (\" \") f\")) y)"))).isNull();
		assertThat(DeclaredArityReport.of(body("(declare (%arity-report \"W (\"))"))).isNull();
		assertThat(DeclaredArityReport.of(body("(declare (%arity-report \"W (\" \") f\" 1))"))).isNull();
		assertThat(DeclaredArityReport.of(body("\"doc\""))).isNull();
		assertThat(DeclaredArityReport.of(List.of())).isNull();
	}

	@Test
	void aLambdaFormsReportIsItsBodys() {
		assertThat(DeclaredArityReport
			.ofLambda((LispCons) LispReader.readFromString("(lambda (x) (declare (%arity-report \"W (\" \") f\")) x)")))
			.isEqualTo(REPORT);
		assertThat(DeclaredArityReport.ofLambda((LispCons) LispReader.readFromString("(lambda (x) x)"))).isNull();
	}

	private static List<LispVal> body(String source) {
		return LispReader.readAllFromString(source);
	}

}
