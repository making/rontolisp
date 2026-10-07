package am.ik.rontolisp.compiler;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compile paths' one spelling of a built-in's function value: every gate that injects
 * a reference-gated wrapper scans for {@code (function name)}, so a quoted designator and
 * a literal {@code (symbol-function 'name)} of such a built-in are rewritten to it before
 * any gate runs.
 */
class FunctionDesignatorsTest {

	@Test
	void aLiteralSymbolFunctionOfAReferenceGatedBuiltinIsItsFunctionForm() {
		assertThat(normalized("(list (symbol-function 'format) (fdefinition 'arrayp) (funcall 'arrayp 1))"))
			.isEqualTo("(LIST #'FORMAT #'ARRAYP (FUNCALL #'ARRAYP 1))");
		// A catalog wrapper is injected whatever the spelling, and a program's own
		// function is not a built-in.
		assertThat(normalized("(list (symbol-function 'car) (symbol-function 'my-fn))"))
			.isEqualTo("(LIST (SYMBOL-FUNCTION 'CAR) (SYMBOL-FUNCTION 'MY-FN))");
	}

	@Test
	void aSymbolFunctionPlaceAndALocalBindingAreLeftAlone() {
		assertThat(normalized("(setf (symbol-function 'format) (symbol-function 'arrayp))"))
			.isEqualTo("(SETF (SYMBOL-FUNCTION 'FORMAT) #'ARRAYP)");
		assertThat(normalized("(psetf (fdefinition 'arrayp) (fdefinition 'format))"))
			.isEqualTo("(PSETF (FDEFINITION 'ARRAYP) #'FORMAT)");
		assertThat(normalized("(flet ((format (x) x)) (symbol-function 'format))"))
			.isEqualTo("(FLET ((FORMAT (X) X)) (SYMBOL-FUNCTION 'FORMAT))");
	}

	private static String normalized(String source) {
		List<LispVal> program = FunctionDesignators.normalizeBuiltinDesignators(LispReader.readAllFromString(source));
		return program.getFirst().print();
	}

}
