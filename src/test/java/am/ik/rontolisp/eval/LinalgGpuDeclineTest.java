package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What {@code --gpu} must do on EVERY machine, including the overwhelming majority that
 * have no NVIDIA device: run the same programs to the same output, and say so quietly.
 * Unlike {@link LinalgGpuTest} nothing here is conditional -- this is the half a CI
 * runner actually executes, and it is the half that must never regress.
 *
 * <p>
 * The sibling of {@link LinalgBlasDeclineTest} one layer up, and of
 * {@code am.ik.gpu.GpuDeclineTest} one layer down: that one pins that the LIBRARY answers
 * without throwing, this one pins that the INTERCEPTOR built on it changes nothing
 * observable.
 */
class LinalgGpuDeclineTest {

	private String eval(String input, boolean gpu) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setGpu(gpu);
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

	@Test
	void theFlagChangesNothingObservableAboutAnExactProduct() {
		// True whether or not this machine has a device: with one the fused multiply-adds
		// of exact integers land on the same bits, without one the defun runs. The shape
		// is above the size threshold, so on a GPU machine the device really is asked.
		String product = """
				(defparameter *a* (linalg:add (linalg:ones '(64 64)) 2.0))
				(defparameter *b* (linalg:reshape (linalg:arange 1 4097) '(64 64)))
				(linalg:to-list (linalg:matmul *a* *b*))
				""";
		assertThat(eval(product, true)).isEqualTo(eval(product, false));
	}

	@Test
	void aProductBelowTheSizeThresholdIsUntouchedEverywhere() {
		// The threshold is not a mechanism of its own -- it is one more decline -- so the
		// small shapes every example runs must be byte-identical with the flag on.
		String product = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 65) '(8 8)))
				(linalg:matmul *a* *a*)
				""";
		assertThat(eval(product, true)).isEqualTo(eval(product, false));
	}

	@Test
	void availabilityIsAPropertyOfTheMachineAndIsAlwaysDescribed() {
		// The CLI prints description() when the flag cannot be honoured, so it must say
		// something on every machine rather than throw on one with no driver at all.
		assertThatCode(LinalgGpu::available).doesNotThrowAnyException();
		assertThat(LinalgGpu.description()).isNotBlank();
		// Cached, and the cache is not a second code path.
		assertThat(LinalgGpu.available()).isEqualTo(LinalgGpu.available());
		assertThat(LinalgGpu.description()).isEqualTo(LinalgGpu.description());
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
