package am.ik.rontolisp.eval;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's opt-in {@code --gpu} acceleration: the {@code linalg:} matrix product
 * runs on an NVIDIA GPU ({@code am.ik.gpu}, via {@link LinalgGpuKernels}) --
 * {@code linalg:dot}'s MATRIX-BY-MATRIX case, and {@code linalg::%la-matmul-nd}, the
 * STACKED product behind {@code linalg:matmul} at rank &gt;= 3 ({@code torch.bmm}, hence
 * every attention layer and every {@code torch:linear} over a {@code (B T C)}
 * activation). Everything else declines, so the whole rest of {@code linalg:} is
 * untouched.
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
 * <p>
 * A STACK of products is one round trip and ONE launch -- the device carries the batch on
 * {@code blockIdx.z} -- so the same floor is paid once for the whole stack and the
 * threshold applies to the TOTAL work, {@code batch * n * m * p}. That is what makes the
 * batched shape the one this flag pays on: a batch of sixteen 64x64 products is sixteen
 * times the work behind one 15 us floor, while the CPU pays for all of it.
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
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_DOT, LinalgGpu::dot);
		// The stacked product behind linalg:matmul at rank >= 3. A %-prefixed member is
		// an internal symbol, whose canonical qualified spelling carries the double colon
		// (.kb/linalg-simd.md).
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_MATMUL_ND, LinalgGpu::matmulNd);
	}

	/**
	 * Overrides one member with a kernel that declines back to whatever the member was
	 * bound to before -- the {@code --simd} lane kernel, the {@code --blas} library
	 * product or the scalar defun, whichever this invocation installed.
	 */
	private static void define(Environment globalEnv, LispEvaluator evaluator, String qualified,
			Function<List<LispVal>, @Nullable LispVal> kernel) {
		LispVal declined = globalEnv.lookupFunctionOrNull(qualified);
		if (declined == null) {
			throw new IllegalStateException("linalg.lisp must be loaded before " + qualified + " can be accelerated");
		}
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() == 2) {
				LispVal fast = kernel.apply(args);
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

	/**
	 * {@code (linalg::%la-matmul-nd a b)}, the STACKED matrix product: the last two axes
	 * are the matrix and every leading axis broadcasts. One launch for the whole stack,
	 * with each operand's per-batch ELEMENT stride handed to the device -- a broadcast
	 * leading axis is a 0 stride and needs no special case, exactly as on the CPU
	 * ({@code .kb/linalg-simd.md}).
	 *
	 * <p>
	 * Declined -- and the captured binding then answers, keeping the dispatch and both
	 * error messages in the library: {@code --simd}'s whole list (a general boxed
	 * operand, mixed widths, a RANK-1 operand on either side, a non-broadcastable batch
	 * shape, a mismatched inner dimension, any empty extent), a stack below the size
	 * threshold or too big for the launch grid, and one shape of its own -- a batch whose
	 * offsets are not AFFINE in the batch index, which is what a broadcast axis UNDER a
	 * non-broadcast one produces ({@link #batchStride}). The device walks the batch with
	 * one stride per operand; anything the odometer can express and a stride cannot is
	 * the CPU's.
	 */
	private static @Nullable LispVal matmulNd(List<LispVal> args) {
		if (!(args.get(0) instanceof LispFloatArray a) || !(args.get(1) instanceof LispFloatArray b)
				|| a.getClass() != b.getClass() || a.rank() < 2 || b.rank() < 2) {
			return null;
		}
		int[] da = a.dims();
		int[] db = b.dims();
		int n = da[da.length - 2];
		int m = da[da.length - 1];
		int p = db[db.length - 1];
		if (m != db[db.length - 2] || n < 1 || m < 1 || p < 1) {
			return null;
		}
		int[] ba = Arrays.copyOf(da, da.length - 2);
		int[] bb = Arrays.copyOf(db, db.length - 2);
		int[] bd = bcastShape(ba, bb);
		if (bd == null) {
			return null;
		}
		long batches = 1;
		for (int d : bd) {
			batches *= d;
		}
		long total = batches * n * p;
		if (batches < 1 || total > Integer.MAX_VALUE - 8 || !LinalgGpuKernels.worth(batches, n, m, p)) {
			return null;
		}
		long sa = batchStride(ba, bd, (long) n * m);
		long sb = batchStride(bb, bd, (long) m * p);
		if (sa < 0 || sb < 0 || sa > Integer.MAX_VALUE || sb > Integer.MAX_VALUE) {
			return null;
		}
		int[] od = Arrays.copyOf(bd, bd.length + 2);
		od[bd.length] = n;
		od[bd.length + 1] = p;
		int batch = (int) batches;
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.multiply(single.data(), (int) sa, ((LispSingleFloatArray) b).data(), (int) sb,
					batch, n, m, p);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.multiply(((LispDoubleFloatArray) a).data(), (int) sa,
				((LispDoubleFloatArray) b).data(), (int) sb, batch, n, m, p);
		return c == null ? null : new LispDoubleFloatArray(c, od);
	}

	/**
	 * The numpy broadcast shape of two batch-dims arrays ({@code %la-bcast-shape} over
	 * the leading axes only): trailing axes align, a pair agrees when equal or either is
	 * 1, the output extent is the larger. {@code null} on any other disagreement.
	 */
	private static int @Nullable [] bcastShape(int[] dx, int[] dy) {
		int rank = Math.max(dx.length, dy.length);
		int[] od = new int[rank];
		long total = 1;
		for (int k = 0; k < rank; k++) {
			int i = dx.length - rank + k;
			int j = dy.length - rank + k;
			int x = i >= 0 ? dx[i] : 1;
			int y = j >= 0 ? dy[j] : 1;
			if (x != y && x != 1 && y != 1) {
				return null;
			}
			od[k] = Math.max(x, y);
			total *= od[k];
			if (total > Integer.MAX_VALUE) {
				return null;
			}
		}
		return od;
	}

	/**
	 * The ONE per-batch element stride that reproduces the {@code %la-batch-strides}
	 * odometer, or {@code -1} when no single stride does.
	 *
	 * <p>
	 * The CPU kernel walks the batch axes as a mixed-radix counter and adds a per-axis
	 * stride; the device adds {@code blockIdx.z * stride}. The two agree exactly when
	 * every axis's stride is that one stride times the axis's own weight in the counter
	 * -- true for a contiguous batch (any rank) and for a wholly broadcast operand
	 * (stride 0, which is every rank-2 right operand under a rank-3 left one), false for
	 * a broadcast axis sitting UNDER a non-broadcast one. Deriving it is O(rank), and it
	 * is exact rather than conservative: a shape that passes computes the same offsets
	 * the odometer would.
	 * @param d the operand's own batch dims
	 * @param od the broadcast batch shape they align to, outermost first
	 * @param base the trailing matrix size, which is the innermost stride
	 * @return the per-batch stride, or {@code -1} when the offsets are not affine
	 */
	private static long batchStride(int[] d, int[] od, long base) {
		long weight = 1;
		long stride = -1;
		long acc = base;
		long[] s = new long[od.length];
		for (int k = od.length - 1, i = d.length - 1; k >= 0; k--, i--) {
			int extent = i >= 0 ? d[i] : 1;
			s[k] = extent == 1 ? 0 : acc;
			acc *= extent;
		}
		for (int k = od.length - 1; k >= 0; k--) {
			if (od[k] > 1) {
				if (s[k] % weight != 0) {
					return -1;
				}
				long candidate = s[k] / weight;
				if (stride < 0) {
					stride = candidate;
				}
				else if (stride != candidate) {
					return -1;
				}
			}
			weight *= od[k];
		}
		return stride < 0 ? 0 : stride;
	}

}
