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
	void theFlagChangesNothingObservableAboutAnExactStackedProduct() {
		// The same for linalg:matmul at rank >= 3, which routes to the intercepted
		// linalg::%la-matmul-nd: a plain stack, a BROADCAST right operand (the shape
		// every torch:linear over a (B T C) activation has), and rank 4. All three are
		// above the size threshold, so on a GPU machine the device really is asked.
		String stack = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 8193) '(2 64 64)))
				(defparameter *b* (linalg:add (linalg:ones '(2 64 64)) 2.0))
				(defparameter *m* (linalg:add (linalg:ones '(64 64)) 1.0))
				(defparameter *r4* (linalg:reshape (linalg:arange 1 12289) '(2 3 32 64)))
				(defparameter *s4* (linalg:add (linalg:ones '(2 3 64 32)) 2.0))
				(list (linalg:sum (linalg:matmul *a* *b*))
				      (linalg:to-list (linalg:flatten (linalg:matmul *a* *m*)))
				      (linalg:shape (linalg:matmul *r4* *s4*))
				      (linalg:sum (linalg:matmul *r4* *s4*)))
				""";
		assertThat(eval(stack, true)).isEqualTo(eval(stack, false));
	}

	@Test
	void aStackedProductBelowTheSizeThresholdIsUntouchedEverywhere() {
		// The threshold for a stack is the TOTAL work, and every example in the
		// repository is under it: 4 x 8x8x8 is 2048 multiply-adds.
		String product = """
				(defparameter *a* (linalg:reshape (linalg:arange 1 257) '(4 8 8)))
				(linalg:sum (linalg:matmul *a* *a*))
				""";
		assertThat(eval(product, true)).isEqualTo(eval(product, false));
	}

	@Test
	void anElementWiseCallBelowTheThresholdIsUntouchedEverywhere() {
		// 16383 elements is one short of the element-wise threshold, so the device is
		// never asked and the answer is the CPU's on every machine -- which is what keeps
		// every example in the repository byte-identical with the flag on.
		String program = """
				(defparameter *a* (linalg:linspace -3.0 3.0 16383))
				(list (linalg:sum (linalg:erf *a*)) (linalg:sum (linalg:exp *a*))
				      (linalg:sum (linalg:tanh *a*)) (linalg:sum (linalg:sin *a*)))
				""";
		assertThat(eval(program, true)).isEqualTo(eval(program, false));
	}

	@Test
	void theDeclinedHalfOfTheElementWiseTierIsUntouchedAtAnySize() {
		// sqrt / abs / negative / sign and the binary add / sub / mul / div are not
		// members: measured, a round trip wins them by 1.4-2x at best and loses them at
		// #f, so they stay on the CPU however big the array is. Unlike the transcendental
		// half this IS a byte-identity claim, and it holds on every machine.
		String program = """
				(defparameter *a* (linalg:linspace 0.01 9.0 200000))
				(defparameter *b* (linalg:linspace 0.02 3.0 200000))
				(list (linalg:sum (linalg:sqrt *a*)) (linalg:sum (linalg:abs *a*))
				      (linalg:sum (linalg:negative *a*)) (linalg:sum (linalg:sign *a*))
				      (linalg:sum (linalg:add *a* *b*)) (linalg:sum (linalg:mul *a* *b*)))
				""";
		assertThat(eval(program, true)).isEqualTo(eval(program, false));
	}

	@Test
	void theStridedTierIsByteIdenticalWithTheFlagOnEveryMachine() {
		// The STRIDED tier -- a BROADCAST binary op, an AXIS fold, an axes TRANSPOSE --
		// is the one part of --gpu that keeps byte-identity, and this is where that claim
		// is pinned on a machine with no device as well as on one with. The kernels widen
		// to double, compute in double and narrow only on the store, which is
		// %la-bcast-loop's and %la-fold-axis's rule; there is no libm in them to
		// disagree about. The shapes are above the thresholds and the data is INEXACT, so
		// on a GPU machine the device really is asked.
		String program = """
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 262144) '(64 4096)))
				(defparameter *m* (linalg:amax *x* :axis 1 :keepdims t))
				(defparameter *s* (linalg:sub *x* *m*))
				(list (linalg:sum *s*)
				      (linalg:sum (linalg:div *s* (linalg:sum *s* :axis 1 :keepdims t)))
				      (linalg:sum (linalg:mul *x* *m*)) (linalg:sum (linalg:add *x* *m*))
				      (linalg:sum (linalg:maximum *x* *m*)) (linalg:sum (linalg:minimum *x* *m*))
				      (linalg:to-list (linalg:flatten (linalg:amin *x* :axis 1)))
				      (linalg:to-list (linalg:flatten (linalg:sum *x* :axis 0)))
				      (linalg:sum (linalg:transpose *x* '(1 0)))
				      (linalg:sum (linalg:var *x* :axis 1 :keepdims t)))
				""";
		assertThat(eval(program, true)).isEqualTo(eval(program, false));
	}

	@Test
	void anEqualShapedBinaryOpIsUntouchedAtAnySize() {
		// The guard on the measurement, and the reason the strided tier is not a
		// reversal of phase 4b's refusal: at EQUAL shapes the CPU runs a lane loop and a
		// round trip loses (measured 65 us against 112 at #f), so the device must not be
		// offered them however big the arrays are. It is the BROADCAST shape -- where the
		// CPU walks an odometer element by element -- that is taken.
		String program = """
				(defparameter *a* (linalg:reshape (linalg:linspace 0.01 9.0 262144) '(64 4096)))
				(defparameter *b* (linalg:reshape (linalg:linspace 0.02 3.0 262144) '(64 4096)))
				(list (linalg:sum (linalg:add *a* *b*)) (linalg:sum (linalg:sub *a* *b*))
				      (linalg:sum (linalg:mul *a* *b*)) (linalg:sum (linalg:div *a* *b*))
				      (linalg:sum (linalg:maximum *a* *b*)) (linalg:sum (linalg:minimum *a* *b*)))
				""";
		assertThat(eval(program, true)).isEqualTo(eval(program, false));
	}

	@Test
	void aStridedCallBelowTheThresholdIsUntouchedEverywhere() {
		// 2^15 output elements for a broadcast or a transpose and 2^17 input elements for
		// a fold; below either the device is never asked, which is what keeps every
		// example in the repository byte-identical with the flag on.
		String program = """
				(defparameter *x* (linalg:reshape (linalg:linspace 0.013 3.7 32000) '(50 640)))
				(defparameter *m* (linalg:amax *x* :axis 1 :keepdims t))
				(list (linalg:sum (linalg:sub *x* *m*))
				      (linalg:to-list (linalg:flatten (linalg:sum *x* :axis 1 :keepdims t)))
				      (linalg:sum (linalg:transpose *x* '(1 0))))
				""";
		assertThat(eval(program, true)).isEqualTo(eval(program, false));
	}

	@Test
	void anAcceleratedElementWiseCallStaysWithinARelativeToleranceOfTheOracle() {
		// The one place this file cannot assert byte-identity, and the reason is the
		// feature's: above the threshold a transcendental runs on the DEVICE's libm, so
		// on a machine with a GPU the last digits differ. What must hold everywhere is
		// the tolerance -- 1e-12 relative at #d, against a measured worst case of 1.0e-15
		// -- and on a machine without a device the difference is exactly zero.
		String program = """
				(defparameter *a* (linalg:linspace -3.0 3.0 20000))
				(linalg:to-list (linalg:erf *a*))
				""";
		double[] accelerated = doubles(eval(program, true));
		double[] oracle = doubles(eval(program, false));
		assertThat(accelerated).hasSameSizeAs(oracle);
		for (int i = 0; i < oracle.length; i++) {
			if (oracle[i] != 0) {
				assertThat(Math.abs(accelerated[i] - oracle[i]) / Math.abs(oracle[i])).as("element %d", i)
					.isLessThan(1e-12);
			}
		}
	}

	/** The elements of a printed {@code (a b c)} list of doubles. */
	private static double[] doubles(String printed) {
		String[] parts = printed.substring(1, printed.length() - 1).split(" ");
		double[] values = new double[parts.length];
		for (int i = 0; i < parts.length; i++) {
			values[i] = Double.parseDouble(parts[i]);
		}
		return values;
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
