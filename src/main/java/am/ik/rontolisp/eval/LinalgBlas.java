package am.ik.rontolisp.eval;

import java.util.List;
import java.util.function.Function;

import am.ik.rontolisp.FloatArrayAccessHook;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's opt-in {@code --blas} acceleration: the matrix product, in both of
 * the packages that have one. {@code linalg:dot} -- and through it {@code linalg:matmul}
 * at rank {@code <= 2} -- calls the {@code gemm} / {@code gemv} of a tuned CBLAS found in
 * the operating system ({@link LinalgBlasKernels}), and {@code vec:matvec} /
 * {@code vec:matvec-into} call its {@code gemv} ({@link #installVec}). Everything else
 * declines, so the whole rest of both packages is untouched.
 *
 * <h2>Why only the product</h2>
 *
 * The matrix product is the entire win. Measured on an Apple M4 Max against the
 * {@code --simd} kernel of the same build, Accelerate's {@code cblas_dgemm} is 35-121x
 * faster at {@code linalg}'s DEFAULT width, which no other acceleration rontolisp has can
 * touch, and its {@code cblas_sgemv} 6-9x the lane kernel on the GEMV shapes an LLM
 * decode is made of (1.2-2.0x single-threaded and up to 18x threaded against OpenBLAS on
 * a 64-core Xeon; {@code .kb/linalg-blas.md} has both tables). The memory-bound members
 * ({@code sum}, a vector-vector {@code dot}, {@code axpy}, every element-wise
 * {@code vec:} kernel) would gain nothing from a library call, so they are not
 * intercepted at all. The stacked rank-{@code >= 3} product is a separate interception
 * that {@code --simd} does not have either.
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
		override(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_DOT, 2, LinalgBlas::dot);
	}

	/**
	 * Overrides {@code vec:matvec} and {@code vec:matvec-into} in the given (global)
	 * environment with the library GEMV -- the {@code vec:} half of {@code --blas}, and
	 * the seam {@code examples/ml/simd-gemv}, {@code examples/tiny-llm} and
	 * {@code examples/llama2} spend their time in. Must be called AFTER the
	 * {@code vec.lisp} forms have been evaluated into the environment and after
	 * {@link VecSimd#install} (whichever binding it finds is what it declines to), and
	 * only when {@link #available()} is {@code true}.
	 *
	 * <p>
	 * It is a separate entry point from {@link #install} for the reason
	 * {@link LinalgGpu#installVec} is: the two Lisp libraries load lazily and
	 * independently, and a program may reach {@code vec:} before {@code linalg:} or never
	 * reach {@code linalg:} at all.
	 * @param globalEnv the global environment holding the loaded vec library
	 * @param evaluator the evaluator used to apply the captured binding on decline
	 */
	public static void installVec(Environment globalEnv, LispEvaluator evaluator) {
		override(globalEnv, evaluator, LispNames.VEC_PKG + ":" + LispNames.VEC_MATVEC, 2, LinalgBlas::matvec);
		override(globalEnv, evaluator, LispNames.VEC_PKG + ":" + LispNames.VEC_MATVEC_INTO, 3, LinalgBlas::matvecInto);
	}

	/**
	 * Installs one partial native over whatever the name is bound to now, declining to
	 * that binding -- the lane kernel when {@code --simd} installed one, the scalar
	 * {@code vec.lisp} defun otherwise.
	 */
	private static void override(Environment globalEnv, LispEvaluator evaluator, String qualified, int arity,
			Function<List<LispVal>, @Nullable LispVal> kernel) {
		LispVal declined = globalEnv.lookupFunctionOrNull(qualified);
		if (declined == null) {
			throw new IllegalStateException(
					"the library defining " + qualified + " must be loaded before it can be accelerated");
		}
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() == arity) {
				LispVal fast = kernel.apply(args);
				if (fast != null) {
					return fast;
				}
			}
			return evaluator.applyGlobal(declined, args);
		}));
	}

	/**
	 * {@code (vec:matvec w x)} on the library: a packed rank-2 matrix by a packed rank-1
	 * vector of the same width and matching extent, above the size threshold. Everything
	 * else declines -- to the lane kernel or the scalar defun, both of which are TOTAL
	 * and will produce the answer or the {@code vec:} error, so nothing here has to
	 * reproduce either.
	 */
	private static @Nullable LispVal matvec(List<LispVal> args) {
		LispFloatArray w = matrix(args.get(0), args.get(1));
		if (w == null) {
			return null;
		}
		LispFloatArray x = (LispFloatArray) args.get(1);
		int rows = w.dims()[0];
		int cols = w.dims()[1];
		return switch (w) {
			case LispSingleFloatArray m -> {
				float[] y = new float[rows];
				LinalgBlasKernels.gemvF(m.data(), 0, rows, cols, floats(x), 0, y, 0, false);
				yield new LispSingleFloatArray(y, new int[] { rows });
			}
			case LispDoubleFloatArray m -> {
				double[] y = new double[rows];
				LinalgBlasKernels.gemv(m.data(), 0, rows, cols, doubles(x), 0, y, 0, false);
				yield new LispDoubleFloatArray(y, new int[] { rows });
			}
		};
	}

	/**
	 * {@code (vec:matvec-into out w x)}: the same product straight into a caller-supplied
	 * destination, which is what {@code cblas_?gemv} does natively -- so this form drops
	 * the result allocation as well as the loop, and answers {@code out} itself
	 * ({@code eq} to the argument, the {@code -into} contract). A destination sharing
	 * storage with {@code w} or {@code x} declines: each output element folds over all of
	 * {@code x}, and the rung below signals that for us.
	 */
	private static @Nullable LispVal matvecInto(List<LispVal> args) {
		LispFloatArray w = matrix(args.get(1), args.get(2));
		if (w == null || !(args.get(0) instanceof LispFloatArray out) || out.getClass() != w.getClass()
				|| out.rank() != 1) {
			return null;
		}
		LispFloatArray x = (LispFloatArray) args.get(2);
		int rows = w.dims()[0];
		int cols = w.dims()[1];
		if (out.dims()[0] != rows) {
			return null;
		}
		return switch (out) {
			case LispSingleFloatArray y -> {
				float[] data = y.data();
				if (data == floats(w) || data == floats(x)) {
					yield null;
				}
				LinalgBlasKernels.gemvF(floats(w), 0, rows, cols, floats(x), 0, data, 0, false);
				FloatArrayAccessHook.written(y.storage());
				yield out;
			}
			case LispDoubleFloatArray y -> {
				double[] data = y.data();
				if (data == doubles(w) || data == doubles(x)) {
					yield null;
				}
				LinalgBlasKernels.gemv(doubles(w), 0, rows, cols, doubles(x), 0, data, 0, false);
				FloatArrayAccessHook.written(y.storage());
				yield out;
			}
		};
	}

	/**
	 * The matrix operand both {@code vec:} entry points require, or {@code null} when
	 * this pair is not a GEMV the library should take: a packed rank-2 matrix and a
	 * packed rank-1 vector of the same width whose extent matches, big enough to pay for
	 * the downcall.
	 */
	private static @Nullable LispFloatArray matrix(LispVal matrixArg, LispVal vectorArg) {
		if (!(matrixArg instanceof LispFloatArray w) || !(vectorArg instanceof LispFloatArray x)
				|| w.getClass() != x.getClass() || w.rank() != 2 || x.rank() != 1) {
			return null;
		}
		int rows = w.dims()[0];
		int cols = w.dims()[1];
		return x.dims()[0] == cols && LinalgBlasKernels.worth(rows, cols, 1) ? w : null;
	}

	/**
	 * The three product shapes a CBLAS covers: matrix by matrix (gemm), matrix by vector
	 * and vector by matrix (gemv, the second one transposed). A vector-by-vector dot is
	 * memory-bound and declines, as does anything that is not a pair of packed arrays of
	 * the same width, and any product too small to pay for the call.
	 */
	private static @Nullable LispVal dot(List<LispVal> args) {
		LispFloatArray a = packed(args.get(0));
		LispFloatArray b = packed(args.get(1));
		if (a == null || b == null || a.getClass() != b.getClass()) {
			return null;
		}
		if (a.rank() == 2 && b.rank() == 2) {
			int n = a.dims()[0];
			int m = a.dims()[1];
			int p = b.dims()[1];
			if (m != b.dims()[0] || !LinalgBlasKernels.worth(n, m, p)) {
				return null;
			}
			return gemm(a, b, n, m, p);
		}
		if (a.rank() == 2 && b.rank() == 1) {
			int rows = a.dims()[0];
			int cols = a.dims()[1];
			if (cols != b.dims()[0] || !LinalgBlasKernels.worth(rows, cols, 1)) {
				return null;
			}
			return gemv(a, b, rows, cols, rows, false);
		}
		if (a.rank() == 1 && b.rank() == 2) {
			// A row vector times a matrix contracts b's FIRST axis, which is b^T x.
			int rows = b.dims()[0];
			int cols = b.dims()[1];
			if (a.dims()[0] != rows || !LinalgBlasKernels.worth(rows, cols, 1)) {
				return null;
			}
			return gemv(b, a, rows, cols, cols, true);
		}
		return null;
	}

	/** {@code c = a b}, both rank 2 and of the same width, the result fresh. */
	private static LispVal gemm(LispFloatArray a, LispFloatArray b, int n, int m, int p) {
		int[] dims = { n, p };
		return switch (a) {
			case LispSingleFloatArray x -> {
				float[] c = new float[n * p];
				LinalgBlasKernels.gemmF(x.data(), 0, floats(b), 0, c, 0, n, m, p);
				yield new LispSingleFloatArray(c, dims);
			}
			case LispDoubleFloatArray x -> {
				double[] c = new double[n * p];
				LinalgBlasKernels.gemm(x.data(), 0, doubles(b), 0, c, 0, n, m, p);
				yield new LispDoubleFloatArray(c, dims);
			}
		};
	}

	/** {@code y = matrix x} (or {@code matrix^T x}), the result a fresh rank-1 array. */
	private static LispVal gemv(LispFloatArray matrix, LispFloatArray vector, int rows, int cols, int out,
			boolean transposed) {
		int[] dims = { out };
		return switch (matrix) {
			case LispSingleFloatArray m -> {
				float[] y = new float[out];
				LinalgBlasKernels.gemvF(m.data(), 0, rows, cols, floats(vector), 0, y, 0, transposed);
				yield new LispSingleFloatArray(y, dims);
			}
			case LispDoubleFloatArray m -> {
				double[] y = new double[out];
				LinalgBlasKernels.gemv(m.data(), 0, rows, cols, doubles(vector), 0, y, 0, transposed);
				yield new LispDoubleFloatArray(y, dims);
			}
		};
	}

	private static @Nullable LispFloatArray packed(LispVal value) {
		return value instanceof LispFloatArray array ? array : null;
	}

	/**
	 * The backing of an operand ALREADY PROVEN to be a double-float array -- every caller
	 * is inside the arm of an exhaustive {@code switch} over a sibling operand whose
	 * class it was checked against, so the narrowing cannot fail whatever widths exist.
	 */
	private static double[] doubles(LispFloatArray array) {
		return ((LispDoubleFloatArray) array).data();
	}

	/** The single-float counterpart of {@link #doubles}, under the same proof. */
	private static float[] floats(LispFloatArray array) {
		return ((LispSingleFloatArray) array).data();
	}

}
