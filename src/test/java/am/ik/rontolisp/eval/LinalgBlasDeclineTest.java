package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.NativeImageDowncalls;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code --blas} must do on EVERY machine, including one with no tuned CBLAS: run
 * the same programs to the same output. Unlike {@link LinalgBlasTest} nothing here is
 * conditional -- this is the half of the feature that a machine which ignored the
 * recommendation still gets, and it is the half that must never regress.
 */
class LinalgBlasDeclineTest {

	private String eval(String input, boolean blas) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setBlas(blas);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

	@Test
	void theFlagChangesNothingObservableAboutAnExactProduct() {
		// True whether or not this machine has a library: with one the reordered
		// reduction of exact integers lands on the same bits, without one the defun runs.
		String product = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(linalg:matmul *a* *a*)
				""";
		assertThat(eval(product, true)).isEqualTo(eval(product, false));
	}

	@Test
	void availabilityIsAPropertyOfTheMachineAndIsAlwaysDescribed() {
		// The CLI prints description() when the flag cannot be honoured, so it must say
		// something either way rather than throw on a machine with no library at all.
		assertThat(LinalgBlas.description()).isNotBlank();
		assertThat(LinalgBlas.available()).isEqualTo(LinalgBlas.description().contains("("));
	}

	@Test
	void everyDowncallShapeIsRegisteredForTheNativeImage() {
		// Binds the six handles against a lookup that finds everything -- they are made,
		// never called -- so the shapes are recorded on a machine with no CBLAS too.
		LinalgBlasKernels.bind(NativeImageDowncalls.EVERYTHING);
		assertThat(NativeImageDowncalls.missing(LinalgBlasKernels.signatures(), LinalgBlasKernels.criticalSignatures()))
			.as("CBLAS downcall shapes with no entry in the native-image metadata -- the binary refuses to bind "
					+ "them, so --blas declines on a machine whose tuned library is right there")
			.isEmpty();
	}

	@Test
	void theWholeRestOfLinalgIsUntouched() {
		String program = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 17) '(4 4)))
				(list (linalg:sum *a*) (linalg:trace *a*) (linalg:amax *a*)
				      (linalg:to-list (linalg:transpose *a*))
				      (linalg:to-list (linalg:add *a* *a*))
				      (linalg:to-list (linalg:outer (linalg:arange 1 4) (linalg:arange 1 4))))
				""";
		assertThat(eval(program, true)).isEqualTo(eval(program, false));
	}

}
