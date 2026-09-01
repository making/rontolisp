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
 * The interpreter's opt-in {@code --blas} acceleration: {@code linalg:dot} -- and through
 * it {@code linalg:matmul} at rank {@code <= 2} -- calls the {@code gemm} / {@code gemv}
 * of a tuned CBLAS found in the operating system ({@link LinalgBlasKernels}). Everything
 * else declines, so the whole rest of {@code linalg:} is untouched.
 *
 * <h2>Why only the product</h2>
 *
 * The matrix product is the entire win. Measured on an Apple M4 Max against the
 * {@code --simd} kernel of the same build, Accelerate's {@code cblas_dgemm} is 35-121x
 * faster at {@code linalg}'s DEFAULT width, which no other acceleration rontolisp has can
 * touch -- and the memory-bound members ({@code sum}, a vector-vector {@code dot},
 * {@code axpy}) would gain nothing from a library call, so they are not intercepted at
 * all. The stacked rank-{@code >= 3} product is a separate interception that
 * {@code --simd} does not have either.
 *
 * <h2>The protocol is {@code --simd}'s, one layer up</h2>
 *
 * This installs the same partial-kernel override {@link LinalgSimd} uses: the native
 * returns Java {@code null} for anything it does not handle and the wrapper then applies
 * whatever {@code linalg:dot} was bound to BEFORE it. So with {@code --simd --blas} the
 * chain is blas -> simd -> the scalar {@code linalg.lisp} defun, and with {@code --blas}
 * alone it is blas -> the defun. {@link #install} must therefore run AFTER
 * {@link LinalgSimd#install}.
 *
 * <h2>The precision contract</h2>
 *
 * A tuned library blocks and reorders its reduction, so an intercepted product is close
 * to the scalar defun rather than equal to it -- and, unlike every other acceleration
 * rontolisp ships, WHICH library and which version is installed is part of the answer.
 * That is why this is a flag of its own rather than something {@code --simd} started
 * doing: an existing {@code --simd} build keeps computing exactly what it computed
 * before. The scalar defun stays the cross-backend oracle. See
 * {@code .kb/linalg-blas.md}.
 *
 * @see LinalgBlasKernels
 * @see LinalgSimd
 */
public final class LinalgBlas {

	private LinalgBlas() {
	}

	/**
	 * Returns whether a TUNED CBLAS was found in this operating system. False is the
	 * ordinary answer on a machine that has none, and the caller runs unaccelerated.
	 * @return {@code true} when {@code linalg:dot} can be routed to a library gemm
	 */
	public static boolean available() {
		try {
			return LinalgBlasKernels.available();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * The library that was bound, or the reason none was -- the text the CLI reports when
	 * {@code --blas} cannot be honoured.
	 * @return a one-line description of the binding attempt
	 */
	public static String description() {
		try {
			return LinalgBlasKernels.description();
		}
		catch (Throwable ex) {
			return "the foreign function API is unavailable: " + ex;
		}
	}

	/**
	 * Overrides {@code linalg:dot} in the given (global) environment with the library
	 * product. Must be called AFTER the {@code linalg.lisp} forms have been evaluated
	 * into it and after {@link LinalgSimd#install} (whichever binding it finds is what it
	 * declines to), and only when {@link #available()} is {@code true}.
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
	 * The three product shapes a CBLAS covers: matrix by matrix (gemm), matrix by vector
	 * and vector by matrix (gemv, the second one transposed). A vector-by-vector dot is
	 * memory-bound and declines, as does anything that is not a pair of packed arrays of
	 * the same width, and any product too small to pay for the call.
	 */
	private static @Nullable LispVal dot(List<LispVal> args) {
		if (!LinalgBlasKernels.available()) {
			return null;
		}
		LispFloatArray a = packed(args.get(0));
		LispFloatArray b = packed(args.get(1));
		if (a == null || b == null || a.getClass() != b.getClass()) {
			return null;
		}
		boolean single = a instanceof LispSingleFloatArray;
		if (a.rank() == 2 && b.rank() == 2) {
			int n = a.dims()[0];
			int m = a.dims()[1];
			int p = b.dims()[1];
			if (m != b.dims()[0] || !LinalgBlasKernels.worth(n, m, p)) {
				return null;
			}
			int[] dims = { n, p };
			if (single) {
				float[] c = new float[n * p];
				LinalgBlasKernels.gemmF(floats(a), 0, floats(b), 0, c, 0, n, m, p);
				return new LispSingleFloatArray(c, dims);
			}
			double[] c = new double[n * p];
			LinalgBlasKernels.gemm(doubles(a), 0, doubles(b), 0, c, 0, n, m, p);
			return new LispDoubleFloatArray(c, dims);
		}
		if (a.rank() == 2 && b.rank() == 1) {
			int rows = a.dims()[0];
			int cols = a.dims()[1];
			if (cols != b.dims()[0] || !LinalgBlasKernels.worth(rows, cols, 1)) {
				return null;
			}
			return gemv(a, b, rows, cols, rows, false, single);
		}
		if (a.rank() == 1 && b.rank() == 2) {
			// A row vector times a matrix contracts b's FIRST axis, which is b^T x.
			int rows = b.dims()[0];
			int cols = b.dims()[1];
			if (a.dims()[0] != rows || !LinalgBlasKernels.worth(rows, cols, 1)) {
				return null;
			}
			return gemv(b, a, rows, cols, cols, true, single);
		}
		return null;
	}

	/** {@code y = matrix x} (or {@code matrix^T x}), the result a fresh rank-1 array. */
	private static LispVal gemv(LispFloatArray matrix, LispFloatArray vector, int rows, int cols, int out,
			boolean transposed, boolean single) {
		int[] dims = { out };
		if (single) {
			float[] y = new float[out];
			LinalgBlasKernels.gemvF(floats(matrix), 0, rows, cols, floats(vector), 0, y, 0, transposed);
			return new LispSingleFloatArray(y, dims);
		}
		double[] y = new double[out];
		LinalgBlasKernels.gemv(doubles(matrix), 0, rows, cols, doubles(vector), 0, y, 0, transposed);
		return new LispDoubleFloatArray(y, dims);
	}

	private static @Nullable LispFloatArray packed(LispVal value) {
		return value instanceof LispFloatArray array ? array : null;
	}

	private static double[] doubles(LispFloatArray array) {
		return ((LispDoubleFloatArray) array).data();
	}

	private static float[] floats(LispFloatArray array) {
		return ((LispSingleFloatArray) array).data();
	}

}
