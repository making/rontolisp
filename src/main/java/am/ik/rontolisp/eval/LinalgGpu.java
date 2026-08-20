package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's opt-in {@code --gpu} acceleration: {@code linalg:dot} -- and through
 * it {@code linalg:matmul} at rank 2 -- runs its MATRIX-BY-MATRIX case on an NVIDIA GPU
 * ({@code am.ik.gpu}, via {@link LinalgGpuKernels}). Everything else declines, so the
 * whole rest of {@code linalg:} is untouched.
 *
 * <h2>Only the matrix product, and only when it is big</h2>
 *
 * A round trip to a device has a floor -- context, allocation, launch, copy back --
 * measured at ~15 us and flat in the operand size, so what a GPU has to beat on a small
 * array is not CPU arithmetic but rontolisp's own per-call cost. Below
 * {@code n * m * p = 2^17} (about a 51x51x51 product) the CPU wins and the kernel
 * declines; the size threshold therefore needs no mechanism of its own, it is one more
 * decline. The matrix-by-vector shapes {@code --blas} takes are memory-bound, so they are
 * not offered here at all: a gemv's cost is one pass over an operand that would have to
 * be copied to the device anyway.
 *
 * <h2>The protocol is {@code --simd}'s, one layer further up</h2>
 *
 * This installs the same partial-kernel override {@link LinalgSimd} and
 * {@link LinalgBlas} use: the kernel returns Java {@code null} for anything it does not
 * handle and the wrapper then applies whatever {@code linalg:dot} was bound to BEFORE it.
 * {@link #install} runs LAST of the three, so the chain with all the flags on is
 *
 * <pre>
 * --gpu --blas --simd  -&gt;  device -&gt; library gemm -&gt; lane kernel -&gt; scalar linalg.lisp defun
 * </pre>
 *
 * and every prefix of it works the same way. The GPU is asked FIRST because its threshold
 * is three orders of magnitude above the other two ({@code 2^17} against {@code --blas}'s
 * 64), so it declines everything small without touching the driver, and where it does
 * accept it is at worst level with a threaded CPU BLAS and up to 2.3x ahead of one at
 * single width. Installing it under {@code --blas} would instead let the CPU library take
 * every product the device would have won. A declined product always lands on the best
 * CPU path the invocation asked for, never on the scalar defun when a faster one was
 * enabled.
 *
 * <h2>The precision contract</h2>
 *
 * An accelerated product is CLOSE to the scalar defun rather than equal to it, at both
 * widths. The cause is not the tile walk -- the kernel folds {@code k} in the defun's own
 * ascending order -- it is that every one of its multiply-adds is FUSED
 * ({@code fma.rn.f64} / {@code fma.rn.f32}), so each term is rounded once where the defun
 * rounds twice. Over inputs exact at the operand width (small integers, powers of two)
 * that cannot show, and the results match exactly; over inexact ones they differ, and at
 * {@code #d} that is a real break with the bit-identity {@code --simd} keeps. The scalar
 * {@code linalg.lisp} defun remains the cross-backend oracle and {@code --gpu} stays out
 * of {@code ci-spec.yaml}. See {@code .kb/gpu.md}.
 *
 * @see LinalgGpuKernels
 * @see LinalgBlas
 * @see LinalgSimd
 */
public final class LinalgGpu {

	private LinalgGpu() {
	}

	/**
	 * Returns whether this machine has a GPU the matrix product can run on. False is the
	 * ordinary answer -- no driver, no device, a card older than the kernels, a platform
	 * without {@code libcuda.so.1} -- and the caller then runs unaccelerated. The first
	 * call runs the probe, which is why nothing may ask this on a path that did not
	 * request the flag.
	 * @return {@code true} when {@code linalg:dot} can be routed to a device
	 */
	public static boolean available() {
		try {
			return LinalgGpuKernels.available();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * The device that was found, or the reason none was -- the text the CLI reports when
	 * {@code --gpu} cannot be honoured. The first call runs the probe.
	 * @return a one-line description of the probe's outcome
	 */
	public static String description() {
		try {
			return LinalgGpuKernels.description();
		}
		catch (Throwable ex) {
			return "the CUDA driver is unavailable: " + ex;
		}
	}

	/**
	 * Overrides {@code linalg:dot} in the given (global) environment with the device
	 * product. Must be called AFTER the {@code linalg.lisp} forms have been evaluated
	 * into it and after {@link LinalgSimd#install} and {@link LinalgBlas#install}
	 * (whichever binding it finds is what it declines to), and only when
	 * {@link #available()} is {@code true}.
	 * @param globalEnv the global environment holding the loaded linalg library
	 * @param evaluator the evaluator used to apply the captured binding on decline
	 */
	public static void install(Environment globalEnv, LispEvaluator evaluator) {
		String qualified = LispNames.LINALG_PKG + ":" + LispNames.LINALG_DOT;
		LispVal declined = globalEnv.lookupFunctionOrNull(qualified);
		if (declined == null) {
			throw new IllegalStateException("linalg.lisp must be loaded before " + qualified + " can be accelerated");
		}
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() == 2) {
				LispVal fast = dot(args);
				if (fast != null) {
					return fast;
				}
			}
			return evaluator.applyGlobal(declined, args);
		}));
	}

	/**
	 * The one accelerated shape: two packed rank-2 arrays of the same width whose product
	 * is above the size threshold. Anything else -- a boxed array, mixed widths, a scalar
	 * operand, a vector on either side, a mismatched inner dimension, a product too small
	 * or too big for the device -- answers {@code null} and the captured binding runs.
	 */
	private static @Nullable LispVal dot(List<LispVal> args) {
		if (!(args.get(0) instanceof LispFloatArray a) || !(args.get(1) instanceof LispFloatArray b)
				|| a.getClass() != b.getClass() || a.rank() != 2 || b.rank() != 2) {
			return null;
		}
		int n = a.dims()[0];
		int m = a.dims()[1];
		int p = b.dims()[1];
		if (m != b.dims()[0] || !LinalgGpuKernels.worth(n, m, p)) {
			return null;
		}
		int[] dims = { n, p };
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.multiply(single.data(), ((LispSingleFloatArray) b).data(), n, m, p);
			return c == null ? null : new LispSingleFloatArray(c, dims);
		}
		double[] c = LinalgGpuKernels.multiply(((LispDoubleFloatArray) a).data(), ((LispDoubleFloatArray) b).data(), n,
				m, p);
		return c == null ? null : new LispDoubleFloatArray(c, dims);
	}

}
