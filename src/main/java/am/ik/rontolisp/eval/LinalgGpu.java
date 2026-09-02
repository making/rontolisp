package am.ik.rontolisp.eval;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import am.ik.rontolisp.FloatArrayAccessHook;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's opt-in {@code --gpu} acceleration: part of {@code linalg:} runs on an
 * NVIDIA GPU or on Apple Silicon ({@code am.ik.gpu}, via {@link LinalgGpuKernels}) --
 * {@code linalg:dot}'s MATRIX-BY-MATRIX case, {@code linalg::%la-matmul-nd}, the STACKED
 * product behind {@code linalg:matmul} at rank &gt;= 3 ({@code torch.bmm}, hence every
 * attention layer and every {@code torch:linear} over a {@code (B T C)} activation), and
 * the twelve ELEMENT-WISE members whose scalar cost is a libm call ({@code exp}
 * {@code log} {@code tanh} {@code sin} {@code cos} {@code tan} {@code asin} {@code acos}
 * {@code atan} {@code sinh} {@code cosh} {@code erf}). Everything else declines, so the
 * whole rest of {@code linalg:} is untouched -- including {@code sqrt}, {@code abs},
 * {@code negative}, {@code sign} and the binary {@code add} / {@code sub} / {@code mul} /
 * {@code div}, which are one machine instruction per element and cannot pay for a round
 * trip. That is a measurement ({@code .kb/gpu.md}), not a staging decision. Since
 * 2026-08-22 there is ONE member outside {@code linalg:}: {@code vec:matvec}, the GEMV a
 * decode loop is made of, installed by {@link #installVec} when the {@code vec} library
 * loads and accepted only over a matrix that STAYS on the device (below).
 *
 * <h2>Only the big shapes, and there are two size rules</h2>
 *
 * A round trip to a device has a floor -- context, allocation, launch, copy back --
 * measured at ~15 us and flat in the operand size, so what a GPU has to beat on a small
 * array is not CPU arithmetic but rontolisp's own per-call cost. Below
 * {@code n * m * p = 2^17} (about a 51x51x51 product) the CPU wins and the kernel
 * declines; the size threshold therefore needs no mechanism of its own, it is one more
 * decline. The {@code linalg:} matrix-by-vector shapes {@code --blas} takes are
 * memory-bound, so they are not offered here: a gemv's cost is one pass over an operand
 * that would have to be copied to the device anyway -- UNLESS the matrix is already
 * there, which is the whole case for {@code vec:matvec} and the reason that member is
 * accepted only once its matrix has been offered twice without being written.
 *
 * <p>
 * A STACK of products is one round trip and ONE launch -- the device carries the batch on
 * {@code blockIdx.z} -- so the same floor is paid once for the whole stack and the
 * threshold applies to the TOTAL work, {@code batch * n * m * p}. That is what makes the
 * batched shape the one this flag pays on: a batch of sixteen 64x64 products is sixteen
 * times the work behind one 15 us floor, while the CPU pays for all of it.
 *
 * <p>
 * An element-wise call is measured in ELEMENTS instead -- one library call each, not
 * {@code n} of them per output -- and declines below 16384 of them. Above that the device
 * wins by 8-11x at {@code #d} and 20-30x at {@code #f}, and by 100-300x for {@code erf},
 * which is the member the CPU is slowest at and the one the exact {@code torch:gelu} is
 * written over.
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
 * An accelerated ELEMENT-WISE call is close to the scalar defun and can never be equal to
 * it: the device carries its own libm, and at {@code #f} it evaluates AT the operand
 * width where the defun evaluates in double and narrows on the store. Measured, one to
 * two ulps of the width ({@code .kb/gpu.md} has the per-member table). This is the first
 * thing in rontolisp whose results a user should not expect to match the other backends
 * elementwise, and it is why the tests pin a RELATIVE tolerance rather than the
 * byte-identity every other flag keeps.
 *
 * <p>
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
	 * with neither {@code libcuda.so.1} nor Metal -- and the caller then runs
	 * unaccelerated. The first call runs the probe, which is why nothing may ask this on
	 * a path that did not request the flag.
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
			return "no GPU is available: " + ex;
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
		hooks();
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_DOT, 2, LinalgGpu::dot);
		// The stacked product behind linalg:matmul at rank >= 3. A %-prefixed member is
		// an internal symbol, whose canonical qualified spelling carries the double colon
		// (.kb/linalg-simd.md).
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_MATMUL_ND, 2, LinalgGpu::matmulNd);
		// The same product with one operand read in the orientation it is already
		// STORED in: the two matmul adjoints, which the tape would otherwise reach
		// through a full strided copy of an activation.
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_MATMUL_ND_TA, 2,
				args -> matmulNd(args, true, false));
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_MATMUL_ND_TB, 2,
				args -> matmulNd(args, false, true));
		// The ELEMENT-WISE tier: the twelve unary ufuncs whose scalar cost is a libm
		// call. linalg:sqrt / abs / negative / sign and the binary add / sub / mul / div
		// are NOT here -- they move one or three streams for one machine instruction, so
		// a round trip cannot pay for them, and that is a measurement (.kb/gpu.md) rather
		// than an assumption.
		for (Map.Entry<String, Integer> member : MAP_MEMBERS.entrySet()) {
			int op = member.getValue();
			define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + member.getKey(), 1, args -> map(op, args));
		}
		// The binary members, at three shapes: a BROADCAST pair (the strided tier, whose
		// CPU twin is a scalar odometer walk, taken from the size threshold), and -- the
		// resident tier -- an EQUAL-shaped pair or an array with a scalar, whose CPU twin
		// is a lane loop a round trip cannot beat, taken only over a RESIDENT operand
		// (see bcast). The five comparison masks are the same three shapes.
		for (Map.Entry<String, Integer> member : BIN_MEMBERS.entrySet()) {
			int op = member.getValue();
			define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + member.getKey(), 2, args -> bcast(op, args));
		}
		// The rest of the resident tier: the three-way select behind torch:masked-fill
		// and
		// the fused Adam update, both over resident operands only.
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_WHERE, 3, LinalgGpu::where);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_ADAM_STEP, 5, LinalgGpu::adamStep);
		// The axis folds and the axes transpose live at the EXTENDED call shapes, so
		// their
		// arity is a range: `(linalg:sum a :axis 0 :keepdims t)` is five arguments and
		// `(linalg:transpose a '(0 2 1))` is two. The base shapes are not offered -- a
		// whole-array sum is one output cell, which is a single-threaded device loop.
		for (Map.Entry<String, Integer> member : FOLD_MEMBERS.entrySet()) {
			int op = member.getValue();
			define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + member.getKey(), 3, 5,
					args -> foldAxis(op, args));
		}
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_TRANSPOSE, 1, 2,
				LinalgGpu::transpose);
		// The COPY members of the resident tier: reshape, the rank-2 transpose (above, at
		// arity 1), slice's strided gather, concatenate and the in-place clip scale --
		// each a copy the CPU would have had to download the operand for, and each a
		// launch over a resident one.
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_RESHAPE, 2, LinalgGpu::reshape);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_GATHER_STRIDED, 5,
				LinalgGpu::gatherStrided);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_CONCATENATE, 1, 3,
				LinalgGpu::concatenate);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_SCALE, 2, LinalgGpu::scaleInPlace);
		// The seeded generator's fill (linalg:rand / randn / uniform): no operand goes
		// up, the draws come back, and the closed-form jump makes it bit-identical to
		// the sequential walk -- the one member here whose result is byte-for-byte the
		// CPU's at any size. It was a fifth of a --gpu --simd training step as the
		// dropout masks (.kb/gpu.md).
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_RNG_FILL, 5, LinalgGpu::rngFill);
		// The INDEX tier: the embedding lookup, its scatter-add adjoint and the
		// cross-entropy pick -- index-driven copies, hence bit-identical, hence members
		// over a resident operand at any size like the rest of that tier. And the clip
		// norm's sum of squares, the one member here whose fold ORDER is not the defun's
		// (.kb/gpu.md, "The tiers that exist only over a resident operand").
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_TAKE_ROWS, 2, LinalgGpu::takeRows);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_GATHER, 2, LinalgGpu::pick);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_SCATTER_ROWS, 3,
				LinalgGpu::scatterRows);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_SUM_SQUARES, 2,
				LinalgGpu::sumSquares);
		// The FUSED tier (.todo/499): the compositions a transformer step spent a third
		// of its device time on, each as one pass -- linalg:softmax in its :axis form
		// (the last axis), the exact torch:gelu and layer-norm's normalization through
		// the internal members torch.lisp now calls, their three adjoints, and the
		// dropout mask. Every one replays its composition's arithmetic rounding for
		// rounding (.kb/gpu.md, "The fused tier").
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_SOFTMAX, 3, LinalgGpu::softmax);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_SOFTMAX_GRAD, 3,
				LinalgGpu::softmaxGrad);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_SCALED_MASKED_SOFTMAX, 5,
				LinalgGpu::scaledMaskedSoftmax);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_SCALED_MASKED_SOFTMAX_GRAD, 5,
				LinalgGpu::scaledMaskedSoftmaxGrad);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_LOG_SOFTMAX, 3,
				LinalgGpu::logSoftmax);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_LOG_SOFTMAX_GRAD, 3,
				LinalgGpu::logSoftmaxGrad);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_GELU, 1, LinalgGpu::gelu);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_GELU_GRAD, 3, LinalgGpu::geluGrad);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_LAYER_NORM, 2,
				LinalgGpu::layerNorm);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_LAYER_NORM_GRAD, 4,
				LinalgGpu::layerNormGrad);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_LAYER_NORM_AFFINE, 4,
				LinalgGpu::layerNormAffine);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_LAYER_NORM_AFFINE_GRAD, 5,
				LinalgGpu::layerNormAffineGrad);
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_DROPOUT_MASK, 4,
				LinalgGpu::dropoutMask);
	}

	/**
	 * Overrides {@code vec:matvec} in the given (global) environment with the device GEMV
	 * -- the one member of {@code --gpu} outside {@code linalg:}, and the seam
	 * {@code examples/llama2}'s decode loop runs on. Must be called AFTER the
	 * {@code vec.lisp} forms have been evaluated into the environment and after
	 * {@link VecSimd#install} (whichever binding it finds is what it declines to), and
	 * only when {@link #available()} is {@code true}. It is a separate entry point from
	 * {@link #install} because the two libraries load lazily and independently: a program
	 * may reach {@code vec:} before {@code linalg:}, or never reach {@code linalg:} at
	 * all, and the write hook has to be in place from the first device member either way.
	 * @param globalEnv the global environment holding the loaded vec library
	 * @param evaluator the evaluator used to apply the captured binding on decline
	 */
	public static void installVec(Environment globalEnv, LispEvaluator evaluator) {
		hooks();
		define(globalEnv, evaluator, LispNames.VEC_PKG + ":" + LispNames.VEC_MATVEC, 2, LinalgGpu::matvec);
	}

	/**
	 * Device residency, and its lazy results: the members keep a copy of each operand and
	 * result on the device, keyed by the identity of the packed array's storage, and
	 * since {@code .todo/491} a RESULT stays there until the host first reads it. So
	 * every in-place write to a packed array has to reach the library before it happens,
	 * and every host read has to let the library bring the bytes home first. Both go
	 * through {@link FloatArrayAccessHook}: the records' element setter and the in-place
	 * {@code --simd} kernels report writes, and the records' {@code data()} accessor --
	 * the one way to a packed array's storage on this backend -- reports reads
	 * ({@code .kb/gpu.md}, "The two seams, and what must report through them"). The
	 * interceptor itself hands the device {@code storage()}, which does not.
	 */
	private static void hooks() {
		FloatArrayAccessHook.install(LinalgGpuKernels::written, LinalgGpuKernels::materialize);
		LinalgGpuKernels.lazyResults();
	}

	/**
	 * {@code (vec:matvec w x)} on the device: a packed rank-2 matrix and a packed rank-1
	 * vector of the same width and matching extent, above the size threshold -- and,
	 * since a matrix-by-vector product pays only over a RESIDENT matrix, only once the
	 * same matrix has been offered before and not written since (the library's rule,
	 * {@code .kb/gpu.md}); everything else declines to the {@code --simd} lane kernel or
	 * the scalar defun. The kernel accumulates in double and narrows on the store, which
	 * is the defun's rule: at {@code #f} it lands on the defun's own bits in practice and
	 * closer to them than the lane kernel does; at {@code #d} it is the product's few-ulp
	 * story. Neither is asserted as byte-identity.
	 */
	private static @Nullable LispVal matvec(List<LispVal> args) {
		if (!(args.get(0) instanceof LispFloatArray w) || !(args.get(1) instanceof LispFloatArray x)
				|| w.getClass() != x.getClass() || w.rank() != 2 || x.rank() != 1) {
			return null;
		}
		int rows = w.dims()[0];
		int cols = w.dims()[1];
		if (x.dims()[0] != cols || !LinalgGpuKernels.worthMatvec(rows, cols)) {
			return null;
		}
		int[] dims = { rows };
		if (w instanceof LispSingleFloatArray single) {
			float[] y = LinalgGpuKernels.matvec(single.storage(), ((LispSingleFloatArray) x).storage(), rows, cols);
			return y == null ? null : new LispSingleFloatArray(y, dims);
		}
		double[] y = LinalgGpuKernels.matvec(((LispDoubleFloatArray) w).storage(), ((LispDoubleFloatArray) x).storage(),
				rows, cols);
		return y == null ? null : new LispDoubleFloatArray(y, dims);
	}

	/**
	 * {@code (linalg:take-rows a idx)} on the device: the axis-0 slabs of a packed array
	 * named by the index vector, over a RESIDENT table only -- an embedding table is one
	 * after the optimizer has updated it there, and the lookup is then a launch with no
	 * copy whose result stays on the device for the layers above. A pure gather, so it is
	 * the CPU kernel's bits.
	 */
	private static @Nullable LispVal takeRows(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		if (a == null || a.dims().length < 1 || a.dims()[0] == 0) {
			return null;
		}
		int[] rows = LinalgSimd.rowIndexes(args.get(1), a.dims()[0]);
		if (rows == null || rows.length == 0) {
			return null;
		}
		int slab = a.totalSize() / a.dims()[0];
		int[] od = a.dims().clone();
		od[0] = rows.length;
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.takeRows(single.storage(), a.totalSize(), rows, slab);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.takeRows(((LispDoubleFloatArray) a).storage(), a.totalSize(), rows, slab);
		return c == null ? null : new LispDoubleFloatArray(c, od);
	}

	/**
	 * {@code (linalg:gather a idx)} on the device: one element per row of a packed
	 * matrix, the column chosen by the index vector -- the cross-entropy pick, over a
	 * resident matrix (which the log-softmax above it leaves behind) only. A pure gather
	 * again.
	 */
	private static @Nullable LispVal pick(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		if (a == null || a.dims().length != 2) {
			return null;
		}
		int cols = a.dims()[1];
		int[] columns = LinalgSimd.rowIndexes(args.get(1), cols);
		if (columns == null || columns.length != a.dims()[0]) {
			return null;
		}
		int[] od = { columns.length };
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.pick(single.storage(), columns, cols);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.pick(((LispDoubleFloatArray) a).storage(), columns, cols);
		return c == null ? null : new LispDoubleFloatArray(c, od);
	}

	/**
	 * {@code (linalg::%la-scatter-rows z g idx)} on the device, IN PLACE: the adjoint of
	 * take-rows, offered once either array is resident -- the gradient normally is, and
	 * {@code z} (a fresh zero table) then stays on the device for the optimizer instead
	 * of bringing the gradient home. A repeated index accumulates in INDEX order, which
	 * is the defun's own value: the library sorts the indices by destination rather than
	 * using atomics, so this is the CPU kernel's bits too. {@code z} is NOT reported
	 * written -- the device holds it now.
	 */
	private static @Nullable LispVal scatterRows(List<LispVal> args) {
		LispFloatArray z = LinalgSimd.packed(args.get(0));
		LispFloatArray g = LinalgSimd.packed(args.get(1));
		if (z == null || g == null || z.getClass() != g.getClass() || z.dims().length < 1 || z.dims()[0] == 0) {
			return null;
		}
		int[] rows = LinalgSimd.rowIndexes(args.get(2), z.dims()[0]);
		int slab = z.totalSize() / z.dims()[0];
		if (rows == null || slab < 1 || (long) rows.length * slab != g.totalSize()) {
			return null;
		}
		boolean ran = z instanceof LispSingleFloatArray single
				? LinalgGpuKernels.scatterRows(single.storage(), z.dims()[0], ((LispSingleFloatArray) g).storage(),
						rows, slab)
				: LinalgGpuKernels.scatterRows(((LispDoubleFloatArray) z).storage(), z.dims()[0],
						((LispDoubleFloatArray) g).storage(), rows, slab);
		return ran ? z : null;
	}

	/**
	 * {@code (linalg::%la-sum-squares g acc)} on the device, over a RESIDENT gradient
	 * only: the first half of {@code torch:clip-grad-norm}, which otherwise reads every
	 * gradient of the model on the host and is the largest download a lazy training step
	 * still makes. This is the ONE member of this flag that does not compute the defun's
	 * arithmetic in the defun's ORDER -- a single left fold has no parallel form -- and
	 * the break is stated in {@code .kb/gpu.md} rather than hidden: the terms are the
	 * same and rounded in the same places, the association is by block, and the block
	 * count is a function of the length so the answer is reproducible.
	 */
	private static @Nullable LispVal sumSquares(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		Double acc = LinalgSimd.scalar(args.get(1));
		if (g == null || acc == null) {
			return null;
		}
		Double total = switch (g) {
			case LispSingleFloatArray f -> LinalgGpuKernels.sumSquares(f.storage(), f.totalSize(), acc);
			case LispDoubleFloatArray d -> LinalgGpuKernels.sumSquares(d.storage(), d.totalSize(), acc);
		};
		return total == null ? null : new LispDouble(total);
	}

	/**
	 * {@code (linalg::%la-rng-fill out st mode lo span)} on the device, for a packed
	 * destination of either width above the size threshold and a state vector in the
	 * generator's range; everything else declines to whatever was bound before (the
	 * {@code --simd} kernel or the defun, which agree with it bit for bit).
	 */
	private static @Nullable LispVal rngFill(List<LispVal> args) {
		if (!(args.get(0) instanceof LispFloatArray out) || !(args.get(1) instanceof LispDoubleFloatArray st)
				|| st.data().length != 3 || !(args.get(2) instanceof LispInteger modeV) || modeV.value() < 0
				|| modeV.value() > 2) {
			return null;
		}
		Double lo = number(args.get(3)), span = number(args.get(4));
		if (lo == null || span == null) {
			return null;
		}
		int n = out.totalSize();
		if (!LinalgGpuKernels.worthRng(n)) {
			return null;
		}
		int[] w = new int[3];
		for (int i = 0; i < 3; i++) {
			double v = st.data()[i];
			int u = (int) v;
			if (u != v || u < 0 || u >= 1 << 23) {
				return null;
			}
			w[i] = u;
		}
		int mode = (int) modeV.value();
		double[] end = switch (out) {
			case LispDoubleFloatArray d ->
				LinalgGpuKernels.rngFill(d.storage(), d.totalSize(), mode, lo, span, w[0], w[1], w[2]);
			case LispSingleFloatArray f ->
				LinalgGpuKernels.rngFill(f.storage(), f.totalSize(), mode, lo, span, w[0], w[1], w[2]);
		};
		return end == null ? null : new LispDoubleFloatArray(end, new int[] { 3 });
	}

	// --- the fused tier (.todo/499) --------------------------------------------------

	/**
	 * {@code (linalg:softmax a :axis ax)} over the LAST axis of a packed operand, as one
	 * pass per row: the five-member chain's arithmetic exactly (its {@code exp} at the
	 * width, so the result stands to the CPU as an accelerated {@code exp} does). Any
	 * other axis, the whole-array form, a boxed operand and a small one decline to the
	 * defun, whose members the device then takes one by one as before.
	 */
	private static @Nullable LispVal softmax(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		LispVal[] opts = LinalgSimd.options(args, 1, "AXIS");
		if (a == null || opts == null) {
			return null;
		}
		int[] d = a.dims();
		Integer axis = LinalgSimd.normAxis(opts[0], d.length);
		if (axis == null || axis != d.length - 1) {
			return null;
		}
		int len = d[axis];
		int rows = len == 0 ? 0 : a.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.softmax(single.storage(), rows, len);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.softmax(((LispDoubleFloatArray) a).storage(), rows, len);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * {@code (linalg::%la-softmax-grad g out ax)} over the last axis, one pass per row
	 * and bit-identical to the four-member chain; declined at any other axis.
	 */
	private static @Nullable LispVal softmaxGrad(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		LispFloatArray out = LinalgSimd.packed(args.get(1));
		if (g == null || out == null || g.getClass() != out.getClass() || !Arrays.equals(g.dims(), out.dims())) {
			return null;
		}
		int[] d = g.dims();
		Integer axis = LinalgSimd.normAxis(args.get(2), d.length);
		if (axis == null || axis != d.length - 1) {
			return null;
		}
		int len = d[axis];
		int rows = len == 0 ? 0 : g.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		if (g instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.softmaxGrad(single.storage(), ((LispSingleFloatArray) out).storage(), rows,
					len);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.softmaxGrad(((LispDoubleFloatArray) g).storage(),
				((LispDoubleFloatArray) out).storage(), rows, len);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * The mask of a scaled-masked softmax as the kernel reads it: {@code null} for nil,
	 * else a packed array of either width whose dims, leading 1s dropped, are a SUFFIX of
	 * the operand's -- a {@code (1 s s)} mask over a {@code (b s s)} score -- so that
	 * cell {@code i} of the operand reads {@code mask[i % maskLen]}. Any other mask, a
	 * boxed one, or a scalar declines (a null in the returned pair's second slot).
	 * @return the mask's storage and length ({@code null} and 0 for no mask), or
	 * {@code null} to decline
	 */
	private static @Nullable SoftmaxMask softmaxMask(LispVal mv, int[] dims) {
		if (mv instanceof LispNil) {
			return new SoftmaxMask(null, 0);
		}
		LispFloatArray m = LinalgSimd.packed(mv);
		if (m == null) {
			return null;
		}
		int maskLen = suffixLength(m.dims(), dims);
		return maskLen < 1 ? null : new SoftmaxMask(storage(m), maskLen);
	}

	/**
	 * The mask of a scaled-masked softmax as the kernel takes it: its storage and length.
	 */
	private record SoftmaxMask(@Nullable Object storage, int len) {
	}

	/**
	 * The element count of {@code md} when, its leading extent-1 axes dropped, it is a
	 * suffix of {@code dims}; else {@code -1}.
	 */
	static int suffixLength(int[] md, int[] dims) {
		int k = 0;
		while (k < md.length && md[k] == 1) {
			k++;
		}
		int tail = md.length - k;
		if (tail > dims.length) {
			return -1;
		}
		int n = 1;
		for (int i = 0; i < tail; i++) {
			if (md[k + i] != dims[dims.length - tail + i]) {
				return -1;
			}
			n *= md[k + i];
		}
		return n;
	}

	/**
	 * {@code (linalg::%la-scaled-masked-softmax x scale mask fill ax)} over the LAST axis
	 * of a packed operand, as one pass per row (2026-09-02): the division by
	 * {@code scale} (nil for none), the fill where {@code mask} is non-zero (nil for
	 * none) and the five-member softmax chain, rounding for rounding. Any other axis, a
	 * mask that is not a trailing block of the operand, or a boxed operand decline to the
	 * defun, whose members the device then takes one by one.
	 */
	private static @Nullable LispVal scaledMaskedSoftmax(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		if (a == null) {
			return null;
		}
		int[] d = a.dims();
		Integer axis = LinalgSimd.normAxis(args.get(4), d.length);
		if (axis == null || axis != d.length - 1) {
			return null;
		}
		Double scale = args.get(1) instanceof LispNil ? null : number(args.get(1));
		Double fill = number(args.get(3));
		SoftmaxMask mask = softmaxMask(args.get(2), d);
		if ((scale == null && !(args.get(1) instanceof LispNil)) || fill == null || mask == null) {
			return null;
		}
		int scaleOp = scale == null ? 0 : LinalgGpuKernels.BIN_DIV;
		double sf = scale == null ? 0.0 : scale;
		int len = d[axis];
		int rows = len == 0 ? 0 : a.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		int maskLen = mask.len();
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.scaledMaskedSoftmax(single.storage(), mask.storage(), maskLen, rows, len,
					scaleOp, sf, fill);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.scaledMaskedSoftmax(((LispDoubleFloatArray) a).storage(), mask.storage(), maskLen,
				rows, len, scaleOp, sf, fill);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * {@code (linalg::%la-scaled-masked-softmax-grad g out ax scale mask)} over the last
	 * axis, one pass per row and bit-identical to the chain (2026-09-02); declined at any
	 * other axis or mask shape.
	 */
	private static @Nullable LispVal scaledMaskedSoftmaxGrad(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		LispFloatArray out = LinalgSimd.packed(args.get(1));
		if (g == null || out == null || g.getClass() != out.getClass() || !Arrays.equals(g.dims(), out.dims())) {
			return null;
		}
		int[] d = g.dims();
		Integer axis = LinalgSimd.normAxis(args.get(2), d.length);
		if (axis == null || axis != d.length - 1) {
			return null;
		}
		Double scale = args.get(3) instanceof LispNil ? null : number(args.get(3));
		SoftmaxMask mask = softmaxMask(args.get(4), d);
		if ((scale == null && !(args.get(3) instanceof LispNil)) || mask == null) {
			return null;
		}
		int scaleOp = scale == null ? 0 : LinalgGpuKernels.BIN_DIV;
		double sf = scale == null ? 0.0 : scale;
		int len = d[axis];
		int rows = len == 0 ? 0 : g.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		int maskLen = mask.len();
		if (g instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.scaledMaskedSoftmaxGrad(single.storage(),
					((LispSingleFloatArray) out).storage(), mask.storage(), maskLen, rows, len, scaleOp, sf);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.scaledMaskedSoftmaxGrad(((LispDoubleFloatArray) g).storage(),
				((LispDoubleFloatArray) out).storage(), mask.storage(), maskLen, rows, len, scaleOp, sf);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * {@code (linalg:log-softmax a :axis ax)} over the LAST axis of a packed operand, as
	 * one pass per row where the chain ran six members. Any other axis, the whole-array
	 * form, a boxed operand and a small one decline to the defun (todo-629).
	 */
	private static @Nullable LispVal logSoftmax(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		LispVal[] opts = LinalgSimd.options(args, 1, "AXIS");
		if (a == null || opts == null) {
			return null;
		}
		int[] d = a.dims();
		Integer axis = LinalgSimd.normAxis(opts[0], d.length);
		if (axis == null || axis != d.length - 1) {
			return null;
		}
		int len = d[axis];
		int rows = len == 0 ? 0 : a.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.logSoftmax(single.storage(), rows, len);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.logSoftmax(((LispDoubleFloatArray) a).storage(), rows, len);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * {@code (linalg::%la-log-softmax-grad g out ax)} over the last axis, one pass per
	 * row and bit-identical to the four-member chain; declined at any other axis.
	 */
	private static @Nullable LispVal logSoftmaxGrad(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		LispFloatArray out = LinalgSimd.packed(args.get(1));
		if (g == null || out == null || g.getClass() != out.getClass() || !Arrays.equals(g.dims(), out.dims())) {
			return null;
		}
		int[] d = g.dims();
		Integer axis = LinalgSimd.normAxis(args.get(2), d.length);
		if (axis == null || axis != d.length - 1) {
			return null;
		}
		int len = d[axis];
		int rows = len == 0 ? 0 : g.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		if (g instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.logSoftmaxGrad(single.storage(), ((LispSingleFloatArray) out).storage(), rows,
					len);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.logSoftmaxGrad(((LispDoubleFloatArray) g).storage(),
				((LispDoubleFloatArray) out).storage(), rows, len);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/** {@code (linalg::%la-gelu x)}: the exact GELU as one pass over a packed operand. */
	private static @Nullable LispVal gelu(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		if (a == null || a.totalSize() < 1) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.gelu(single.storage(), a.totalSize());
			return c == null ? null : new LispSingleFloatArray(c, a.dims().clone());
		}
		double[] c = LinalgGpuKernels.gelu(((LispDoubleFloatArray) a).storage(), a.totalSize());
		return c == null ? null : new LispDoubleFloatArray(c, a.dims().clone());
	}

	/**
	 * {@code (linalg::%la-gelu-grad g x old)}: the tape's backward through the GELU
	 * composition as one pass, folded onto {@code old} (nil for none). Declined unless
	 * the arrays are packed at one width and one shape.
	 */
	private static @Nullable LispVal geluGrad(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		LispFloatArray x = LinalgSimd.packed(args.get(1));
		LispFloatArray old = args.get(2) instanceof LispNil ? null : LinalgSimd.packed(args.get(2));
		if (g == null || x == null || !sameShape(g, x) || (!(args.get(2) instanceof LispNil) && old == null)
				|| (old != null && !sameShape(g, old)) || g.totalSize() < 1) {
			return null;
		}
		int n = g.totalSize();
		if (g instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.geluGrad(single.storage(), ((LispSingleFloatArray) x).storage(),
					old == null ? null : ((LispSingleFloatArray) old).storage(), n);
			return c == null ? null : new LispSingleFloatArray(c, g.dims().clone());
		}
		double[] c = LinalgGpuKernels.geluGrad(((LispDoubleFloatArray) g).storage(),
				((LispDoubleFloatArray) x).storage(), old == null ? null : ((LispDoubleFloatArray) old).storage(), n);
		return c == null ? null : new LispDoubleFloatArray(c, g.dims().clone());
	}

	/**
	 * {@code (linalg::%la-layer-norm x eps)}: the normalization over the last axis as one
	 * pass per row, bit-identical to the eleven-member chain.
	 */
	private static @Nullable LispVal layerNorm(List<LispVal> args) {
		LispFloatArray x = LinalgSimd.packed(args.get(0));
		Double eps = number(args.get(1));
		if (x == null || eps == null || x.rank() < 1) {
			return null;
		}
		int[] d = x.dims();
		int len = d[d.length - 1];
		int rows = len == 0 ? 0 : x.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		if (x instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.layerNorm(single.storage(), rows, len, eps);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.layerNorm(((LispDoubleFloatArray) x).storage(), rows, len, eps);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * {@code (linalg::%la-layer-norm-grad g x eps old)}: the tape's backward through the
	 * normalization as one pass per row, folded onto {@code old} (nil for none);
	 * bit-identical to the chain.
	 */
	private static @Nullable LispVal layerNormGrad(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		LispFloatArray x = LinalgSimd.packed(args.get(1));
		Double eps = number(args.get(2));
		LispFloatArray old = args.get(3) instanceof LispNil ? null : LinalgSimd.packed(args.get(3));
		if (g == null || x == null || eps == null || !sameShape(g, x)
				|| (!(args.get(3) instanceof LispNil) && old == null) || (old != null && !sameShape(g, old))
				|| g.rank() < 1) {
			return null;
		}
		int[] d = g.dims();
		int len = d[d.length - 1];
		int rows = len == 0 ? 0 : g.totalSize() / len;
		if (rows < 1) {
			return null;
		}
		if (g instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.layerNormGrad(single.storage(), ((LispSingleFloatArray) x).storage(),
					old == null ? null : ((LispSingleFloatArray) old).storage(), rows, len, eps);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.layerNormGrad(((LispDoubleFloatArray) g).storage(),
				((LispDoubleFloatArray) x).storage(), old == null ? null : ((LispDoubleFloatArray) old).storage(), rows,
				len, eps);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * {@code (linalg::%la-layer-norm-affine x w b eps)}: the normalization AND the
	 * module's affine over a {@code (len)} weight and bias as one pass per row,
	 * bit-identical to the chain. Declines anything whose parameters are not two packed
	 * vectors of the operand's own width and last extent -- the shape
	 * {@code torch:layer-norm} builds -- and the composition then runs member by member.
	 */
	private static @Nullable LispVal layerNormAffine(List<LispVal> args) {
		LispFloatArray x = LinalgSimd.packed(args.get(0));
		LispFloatArray w = LinalgSimd.packed(args.get(1));
		LispFloatArray b = LinalgSimd.packed(args.get(2));
		Double eps = number(args.get(3));
		if (x == null || w == null || b == null || eps == null || x.rank() < 1) {
			return null;
		}
		int[] d = x.dims();
		int len = d[d.length - 1];
		int rows = len == 0 ? 0 : x.totalSize() / len;
		if (rows < 1 || !isVector(w, len) || !isVector(b, len) || w.getClass() != x.getClass()
				|| b.getClass() != x.getClass()) {
			return null;
		}
		if (x instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.layerNormAffine(single.storage(), ((LispSingleFloatArray) w).storage(),
					((LispSingleFloatArray) b).storage(), rows, len, eps);
			return c == null ? null : new LispSingleFloatArray(c, d.clone());
		}
		double[] c = LinalgGpuKernels.layerNormAffine(((LispDoubleFloatArray) x).storage(),
				((LispDoubleFloatArray) w).storage(), ((LispDoubleFloatArray) b).storage(), rows, len, eps);
		return c == null ? null : new LispDoubleFloatArray(c, d.clone());
	}

	/**
	 * {@code (linalg::%la-layer-norm-affine-grad g x w eps old)}: its adjoint, and the
	 * one member here that answers a two-element LIST -- the input's gradient folded onto
	 * {@code old}, and {@code g * norm}, whose axis-0 folds are the weight's gradient.
	 */
	private static @Nullable LispVal layerNormAffineGrad(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		LispFloatArray x = LinalgSimd.packed(args.get(1));
		LispFloatArray w = LinalgSimd.packed(args.get(2));
		Double eps = number(args.get(3));
		LispFloatArray old = args.get(4) instanceof LispNil ? null : LinalgSimd.packed(args.get(4));
		if (g == null || x == null || w == null || eps == null || !sameShape(g, x)
				|| (!(args.get(4) instanceof LispNil) && old == null) || (old != null && !sameShape(g, old))
				|| g.rank() < 1) {
			return null;
		}
		int[] d = g.dims();
		int len = d[d.length - 1];
		int rows = len == 0 ? 0 : g.totalSize() / len;
		if (rows < 1 || !isVector(w, len) || w.getClass() != g.getClass()) {
			return null;
		}
		if (g instanceof LispSingleFloatArray single) {
			float @Nullable [][] c = LinalgGpuKernels.layerNormAffineGrad(single.storage(),
					((LispSingleFloatArray) x).storage(), ((LispSingleFloatArray) w).storage(),
					old == null ? null : ((LispSingleFloatArray) old).storage(), rows, len, eps);
			return c == null ? null
					: pair(new LispSingleFloatArray(c[0], d.clone()), new LispSingleFloatArray(c[1], d.clone()));
		}
		double @Nullable [][] c = LinalgGpuKernels.layerNormAffineGrad(((LispDoubleFloatArray) g).storage(),
				((LispDoubleFloatArray) x).storage(), ((LispDoubleFloatArray) w).storage(),
				old == null ? null : ((LispDoubleFloatArray) old).storage(), rows, len, eps);
		return c == null ? null
				: pair(new LispDoubleFloatArray(c[0], d.clone()), new LispDoubleFloatArray(c[1], d.clone()));
	}

	/** Whether a packed operand is a vector of exactly {@code len} elements. */
	private static boolean isVector(LispFloatArray v, int len) {
		return v.rank() == 1 && v.dims()[0] == len;
	}

	/** The two-element list a two-result member answers. */
	private static LispVal pair(LispVal first, LispVal second) {
		return new LispCons(first, new LispCons(second, LispNil.INSTANCE));
	}

	/**
	 * {@code (linalg::%la-dropout-mask shape p st single)}: the inverted-dropout mask
	 * drawn on the device from the state vector {@code st}, which is advanced IN PLACE to
	 * the generator's end state as the defun advances it. Declines what {@link #rngFill}
	 * declines -- a bad state word, a small fill -- plus a ratio probability and a shape
	 * that is not a list of positive integers.
	 */
	private static @Nullable LispVal dropoutMask(List<LispVal> args) {
		int[] shape = LinalgSimd.shape(args.get(0));
		Double p = number(args.get(1));
		if (shape == null || p == null || !(args.get(2) instanceof LispDoubleFloatArray st) || st.data().length != 3) {
			return null;
		}
		long total = 1;
		for (int d : shape) {
			if (d < 0) {
				return null;
			}
			total *= d;
		}
		if (total < 1 || total > Integer.MAX_VALUE || !LinalgGpuKernels.worthRng(total)) {
			return null;
		}
		int[] w = new int[3];
		for (int i = 0; i < 3; i++) {
			double v = st.data()[i];
			int u = (int) v;
			if (u != v || u < 0 || u >= 1 << 23) {
				return null;
			}
			w[i] = u;
		}
		int n = (int) total;
		double span = 1.0 - p;
		double[] end;
		LispVal mask;
		if (args.get(3) instanceof LispNil) {
			double[] out = LinalgGpuKernels.result(n);
			end = LinalgGpuKernels.dropoutMask(out, n, p, span, w[0], w[1], w[2]);
			mask = new LispDoubleFloatArray(out, shape);
		}
		else {
			float[] out = LinalgGpuKernels.resultF(n);
			end = LinalgGpuKernels.dropoutMask(out, n, p, span, w[0], w[1], w[2]);
			mask = new LispSingleFloatArray(out, shape);
		}
		if (end == null) {
			return null;
		}
		for (int i = 0; i < 3; i++) {
			st.setElement(i, end[i]);
		}
		return mask;
	}

	private static boolean sameShape(LispFloatArray a, LispFloatArray b) {
		return a.getClass() == b.getClass() && Arrays.equals(a.dims(), b.dims());
	}

	/** A double or integer scalar as a double; a ratio (or anything else) declines. */
	private static @Nullable Double number(LispVal value) {
		return switch (value) {
			case LispDouble d -> d.value();
			case LispInteger i -> (double) i.value();
			default -> null;
		};
	}

	/**
	 * The binary members the device takes AT A BROADCAST SHAPE, each with the op code its
	 * kernel switches on. {@code maximum} / {@code minimum} are the strict selects, so
	 * the SECOND operand wins a tie -- {@code LinalgSimdKernels.BOP_MAX}'s rule, and the
	 * defun's.
	 */
	private static final Map<String, Integer> BIN_MEMBERS = Map.ofEntries(
			Map.entry(LispNames.LINALG_ADD, LinalgGpuKernels.BIN_ADD),
			Map.entry(LispNames.LINALG_SUB, LinalgGpuKernels.BIN_SUB),
			Map.entry(LispNames.LINALG_MUL, LinalgGpuKernels.BIN_MUL),
			Map.entry(LispNames.LINALG_DIV, LinalgGpuKernels.BIN_DIV),
			Map.entry(LispNames.LINALG_MAXIMUM, LinalgGpuKernels.BIN_MAX),
			Map.entry(LispNames.LINALG_MINIMUM, LinalgGpuKernels.BIN_MIN),
			Map.entry(LispNames.LINALG_GREATER, LinalgGpuKernels.BIN_GT),
			Map.entry(LispNames.LINALG_GREATER_EQUAL, LinalgGpuKernels.BIN_GE),
			Map.entry(LispNames.LINALG_LESS, LinalgGpuKernels.BIN_LT),
			Map.entry(LispNames.LINALG_LESS_EQUAL, LinalgGpuKernels.BIN_LE),
			Map.entry(LispNames.LINALG_EQUAL, LinalgGpuKernels.BIN_EQ));

	/** The axis folds the device takes, each with its own op code. */
	private static final Map<String, Integer> FOLD_MEMBERS = Map.of(LispNames.LINALG_SUM, LinalgGpuKernels.FOLD_SUM,
			LispNames.LINALG_AMAX, LinalgGpuKernels.FOLD_AMAX, LispNames.LINALG_AMIN, LinalgGpuKernels.FOLD_AMIN);

	/**
	 * The element-wise members the device takes, each with the op code its kernel
	 * switches on. The order is the op codes' own, which is {@code gemm.cu}'s.
	 */
	private static final Map<String, Integer> MAP_MEMBERS = Map.ofEntries(
			Map.entry(LispNames.LINALG_EXP, LinalgGpuKernels.MAP_EXP),
			Map.entry(LispNames.LINALG_LOG, LinalgGpuKernels.MAP_LOG),
			Map.entry(LispNames.LINALG_TANH, LinalgGpuKernels.MAP_TANH),
			Map.entry(LispNames.LINALG_SIN, LinalgGpuKernels.MAP_SIN),
			Map.entry(LispNames.LINALG_COS, LinalgGpuKernels.MAP_COS),
			Map.entry(LispNames.LINALG_TAN, LinalgGpuKernels.MAP_TAN),
			Map.entry(LispNames.LINALG_ASIN, LinalgGpuKernels.MAP_ASIN),
			Map.entry(LispNames.LINALG_ACOS, LinalgGpuKernels.MAP_ACOS),
			Map.entry(LispNames.LINALG_ATAN, LinalgGpuKernels.MAP_ATAN),
			Map.entry(LispNames.LINALG_SINH, LinalgGpuKernels.MAP_SINH),
			Map.entry(LispNames.LINALG_COSH, LinalgGpuKernels.MAP_COSH),
			Map.entry(LispNames.LINALG_ERF, LinalgGpuKernels.MAP_ERF),
			// The resident tier's four: one machine instruction each, members over a
			// resident operand only (see map).
			Map.entry(LispNames.LINALG_SQRT, LinalgGpuKernels.MAP_SQRT),
			Map.entry(LispNames.LINALG_ABS, LinalgGpuKernels.MAP_ABS),
			Map.entry(LispNames.LINALG_NEGATIVE, LinalgGpuKernels.MAP_NEGATIVE),
			Map.entry(LispNames.LINALG_SIGN, LinalgGpuKernels.MAP_SIGN));

	/**
	 * Overrides one member with a kernel that declines back to whatever the member was
	 * bound to before -- the {@code --simd} lane kernel, the {@code --blas} library
	 * product or the scalar defun, whichever this invocation installed.
	 */
	private static void define(Environment globalEnv, LispEvaluator evaluator, String qualified, int arity,
			Function<List<LispVal>, @Nullable LispVal> kernel) {
		define(globalEnv, evaluator, qualified, arity, arity, kernel);
	}

	private static void define(Environment globalEnv, LispEvaluator evaluator, String qualified, int minArity,
			int maxArity, Function<List<LispVal>, @Nullable LispVal> kernel) {
		LispVal declined = globalEnv.lookupFunctionOrNull(qualified);
		if (declined == null) {
			throw new IllegalStateException(
					"the library defining " + qualified + " must be loaded before it can be accelerated");
		}
		globalEnv.defineFunction(qualified, new LispFunction(qualified, args -> {
			if (args.size() >= minArity && args.size() <= maxArity) {
				LispVal fast = kernel.apply(args);
				if (fast != null) {
					return fast;
				}
			}
			return evaluator.applyGlobal(declined, args);
		}));
	}

	/**
	 * One element-wise unary ufunc over a packed operand of either width and any rank:
	 * {@code out[i] = op(a[i])}, one round trip for the whole array. Anything else -- a
	 * general boxed array, a plain number, an array below the element threshold --
	 * answers {@code null} and the captured binding runs.
	 *
	 * <p>
	 * The result is NOT bit-identical to the scalar defun, and here the break is bigger
	 * than the fused multiply-add that separates an accelerated product from a scalar
	 * one: the device has its own libm, and at {@code #f} it evaluates at the operand
	 * width where the defun evaluates in double and narrows. That is the one contract
	 * {@code --gpu} breaks that no other flag does, and it is stated in the guide as well
	 * as in {@code .kb/gpu.md}.
	 */
	private static @Nullable LispVal map(int op, List<LispVal> args) {
		if (!(args.get(0) instanceof LispFloatArray a)) {
			return null;
		}
		int n = a.totalSize();
		// A libm member from the size threshold, ANY member over a resident operand --
		// there is no trip to pay for then, and the result stays for the next member.
		Object data = storage(a);
		if (!((op < LinalgGpuKernels.MAP_LIBM_OPS && LinalgGpuKernels.worthMap(n))
				|| LinalgGpuKernels.resident(data))) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.map(op, single.storage(), n);
			return c == null ? null : new LispSingleFloatArray(c, a.dims().clone());
		}
		double[] c = LinalgGpuKernels.map(op, ((LispDoubleFloatArray) a).storage(), n);
		return c == null ? null : new LispDoubleFloatArray(c, a.dims().clone());
	}

	/** The raw storage of a packed array, for the device and the residency question. */
	private static Object storage(LispFloatArray a) {
		return a instanceof LispSingleFloatArray f ? f.storage() : ((LispDoubleFloatArray) a).storage();
	}

	/** Whether the device holds a copy of the array. */
	private static boolean resident(LispFloatArray a) {
		return LinalgGpuKernels.resident(storage(a));
	}

	/**
	 * One BROADCAST binary element-wise op over two packed operands of the same width and
	 * DIFFERENT shapes: {@code out[i] = op(a[ia(i)], b[ib(i)])} with each operand
	 * following its own stride-0-padded strides, which is {@code %la-bcast-loop}'s own
	 * walk. One round trip for the whole output.
	 *
	 * <p>
	 * <strong>Equal shapes are declined on purpose.</strong> That is the case the
	 * element-wise tier measured and refused -- there the CPU runs a lane loop and a
	 * round trip loses outright (measured 65 us against 112 at {@code #f}, and this
	 * member set is the same one that refusal names). The BROADCAST case is a different
	 * comparison: the CPU walks an odometer element by element, which costs it 5.5-8.5x
	 * the same round trip at the shapes {@code torch:softmax} and
	 * {@code torch:layer-norm} produce. So is a scalar operand, a boxed array and a
	 * mixed-width pair, each for the reason the {@code --simd} kernel declines it.
	 *
	 * <p>
	 * Unlike the element-wise tier this is BIT-IDENTICAL to the defun at both widths: the
	 * kernel widens every element to double, computes in double and narrows only on the
	 * store, which is {@code %la-bcast-loop}'s rule, and the four arithmetic ops and two
	 * selects leave no libm to disagree about.
	 */
	private static @Nullable LispVal bcast(int op, List<LispVal> args) {
		LispVal av = args.get(0), bv = args.get(1);
		LispFloatArray a = av instanceof LispFloatArray p ? p : null, b = bv instanceof LispFloatArray q ? q : null;
		if (a != null && b != null) {
			if (a.getClass() != b.getClass()) {
				return null;
			}
			int[] da = a.dims();
			int[] db = b.dims();
			if (Arrays.equals(da, db)) {
				return zip(op, a, b);
			}
			// The size test FIRST, over a bound that costs nothing: a broadcast output is
			// at least as big as either operand. Every linalg:add in a program pays this
			// method, so a declined call must not allocate a shape it is about to throw
			// away. A resident operand is offered at any size.
			boolean resident = resident(a) || resident(b);
			if (!resident && !LinalgGpuKernels.worthStrided(Math.max(a.totalSize(), b.totalSize()))) {
				return null;
			}
			int[] od = bcastShape(da, db);
			if (od == null) {
				return null;
			}
			long total = 1;
			for (int d : od) {
				total *= d;
			}
			if (!resident && !LinalgGpuKernels.worthStrided(total)) {
				return null;
			}
			int[] sa = bcastStrides(da, od);
			int[] sb = bcastStrides(db, od);
			if (a instanceof LispSingleFloatArray single) {
				float[] c = LinalgGpuKernels.bcast(op, single.storage(), sa, ((LispSingleFloatArray) b).storage(), sb,
						od);
				return c == null ? null : new LispSingleFloatArray(c, od);
			}
			double[] c = LinalgGpuKernels.bcast(op, ((LispDoubleFloatArray) a).storage(), sa,
					((LispDoubleFloatArray) b).storage(), sb, od);
			return c == null ? null : new LispDoubleFloatArray(c, od);
		}
		// An array with a scalar, either way round: the resident tier's scalar form, over
		// a resident array only. A commutative op with the scalar on the left is the same
		// kernel; the rest swap.
		if (a != null) {
			Double s = number(bv);
			return s == null || !resident(a) ? null : scale(op, a, s, false);
		}
		if (b != null) {
			Double s = number(av);
			return s == null || !resident(b) ? null : scale(op, b, s, true);
		}
		return null;
	}

	/**
	 * The resident tier's EQUAL-shape binary op: the case the element-wise tier measured
	 * and refused as a round trip -- the CPU runs a lane loop -- and which is a launch
	 * with no copy once an operand is resident. Declined otherwise, at any size, and the
	 * lane kernel runs as before. Bit-identical to it: double arithmetic, narrowed on the
	 * store.
	 */
	private static @Nullable LispVal zip(int op, LispFloatArray a, LispFloatArray b) {
		if (!resident(a) && !resident(b)) {
			return null;
		}
		int n = a.totalSize();
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.zip(op, single.storage(), ((LispSingleFloatArray) b).storage(), n);
			return c == null ? null : new LispSingleFloatArray(c, a.dims().clone());
		}
		double[] c = LinalgGpuKernels.zip(op, ((LispDoubleFloatArray) a).storage(),
				((LispDoubleFloatArray) b).storage(), n);
		return c == null ? null : new LispDoubleFloatArray(c, a.dims().clone());
	}

	/** The resident tier's array-with-scalar form; see {@link #zip}. */
	private static @Nullable LispVal scale(int op, LispFloatArray a, double s, boolean swap) {
		int n = a.totalSize();
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.scale(op, single.storage(), s, swap, n);
			return c == null ? null : new LispSingleFloatArray(c, a.dims().clone());
		}
		double[] c = LinalgGpuKernels.scale(op, ((LispDoubleFloatArray) a).storage(), s, swap, n);
		return c == null ? null : new LispDoubleFloatArray(c, a.dims().clone());
	}

	/**
	 * {@code (linalg:where mask x y)} on the device -- the resident tier's three-way
	 * select, {@code torch:masked-fill}'s member: every operand a packed array of either
	 * width or a plain number, broadcast together, the result at {@code x}'s width when
	 * it is an array, else {@code y}'s, else double -- {@code LinalgSimd.where}'s rule
	 * and the defun's. Offered only when some array operand is resident; a select, so
	 * bit-identical. Declined, and the captured binding answers: no array at all, a boxed
	 * array or a ratio scalar, an incompatible broadcast, a mask whose width is neither.
	 */
	private static @Nullable LispVal where(List<LispVal> args) {
		LispVal mv = args.get(0), xv = args.get(1), yv = args.get(2);
		LispFloatArray m = LinalgSimd.packed(mv), x = LinalgSimd.packed(xv), y = LinalgSimd.packed(yv);
		if (m == null && x == null && y == null) {
			return null;
		}
		if (!((m != null && resident(m)) || (x != null && resident(x)) || (y != null && resident(y)))) {
			return null;
		}
		Double ms = m == null ? number(mv) : null, xs = x == null ? number(xv) : null,
				ys = y == null ? number(yv) : null;
		if ((m == null && ms == null) || (x == null && xs == null) || (y == null && ys == null)) {
			return null;
		}
		boolean single = x != null ? x instanceof LispSingleFloatArray
				: (y != null && y instanceof LispSingleFloatArray);
		if ((x != null && y != null && x.getClass() != y.getClass())) {
			return null;
		}
		int[] od = null;
		for (LispFloatArray a : new LispFloatArray[] { m, x, y }) {
			if (a != null) {
				od = od == null ? a.dims().clone() : bcastShape(od, a.dims());
				if (od == null) {
					return null;
				}
			}
		}
		if (od == null) {
			return null;
		}
		Object mData = m == null ? null : storage(m);
		int[] sm = m == null ? null : bcastStrides(m.dims(), od), sx = x == null ? null : bcastStrides(x.dims(), od),
				sy = y == null ? null : bcastStrides(y.dims(), od);
		double mScalar = ms == null ? 0.0 : ms, xScalar = xs == null ? 0.0 : xs, yScalar = ys == null ? 0.0 : ys;
		if (single) {
			float[] c = LinalgGpuKernels.where(mData, sm, mScalar,
					x == null ? null : ((LispSingleFloatArray) x).storage(), sx, xScalar,
					y == null ? null : ((LispSingleFloatArray) y).storage(), sy, yScalar, od);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.where(mData, sm, mScalar, x == null ? null : ((LispDoubleFloatArray) x).storage(),
				sx, xScalar, y == null ? null : ((LispDoubleFloatArray) y).storage(), sy, yScalar, od);
		return c == null ? null : new LispDoubleFloatArray(c, od);
	}

	/**
	 * {@code (linalg::%la-adam-step x g m v rule)} on the device -- the resident tier's
	 * one writing member: the parameter and both moments are updated IN PLACE on the
	 * device, from a gradient that is usually the previous member's result, and stay
	 * there as the authoritative copies; a model's weights then never come home between
	 * steps. Offered only when one of the four is resident; declined otherwise, and the
	 * {@code --simd} kernel or the defun runs -- bit-identically, since the kernel spells
	 * the same arithmetic in the same order. The rule is the eleven-element vector
	 * {@code LinalgSimd.adamStep} takes.
	 */
	private static @Nullable LispVal adamStep(List<LispVal> args) {
		LispFloatArray x = LinalgSimd.packed(args.get(0)), g = LinalgSimd.packed(args.get(1)),
				m = LinalgSimd.packed(args.get(2)), v = LinalgSimd.packed(args.get(3));
		if (x == null || g == null || m == null || v == null || x.getClass() != g.getClass()
				|| x.getClass() != m.getClass() || x.getClass() != v.getClass()) {
			return null;
		}
		int n = x.totalSize();
		if (g.totalSize() != n || m.totalSize() != n || v.totalSize() != n) {
			return null;
		}
		if (!(LinalgSimd.packed(args.get(4)) instanceof LispDoubleFloatArray ps) || ps.data().length != 11) {
			return null;
		}
		double[] rule = ps.data();
		if (!(resident(x) || resident(g) || resident(m) || resident(v))) {
			return null;
		}
		boolean ran = x instanceof LispSingleFloatArray xs
				? LinalgGpuKernels.adamStep(xs.storage(), ((LispSingleFloatArray) g).storage(),
						((LispSingleFloatArray) m).storage(), ((LispSingleFloatArray) v).storage(), n, rule)
				: LinalgGpuKernels.adamStep(((LispDoubleFloatArray) x).storage(), ((LispDoubleFloatArray) g).storage(),
						((LispDoubleFloatArray) m).storage(), ((LispDoubleFloatArray) v).storage(), n, rule);
		return ran ? x : null;
	}

	/**
	 * The AXIS form of {@code sum} / {@code amax} / {@code amin} ({@code %la-fold-axis}):
	 * every slice along one axis folded on its own, the axis dropped from the result or
	 * kept at extent 1 under {@code :keepdims}. One thread per output cell, walking its
	 * axis in the defun's ascending order in a {@code double} accumulator, so this is
	 * BIT-IDENTICAL to the defun at both widths.
	 *
	 * <p>
	 * Declined, and the captured binding then answers: the whole-array form (no
	 * {@code :axis}), a nil / non-integer / out-of-range axis, an empty axis, a malformed
	 * keyword tail, a boxed operand, a fold below the size threshold -- and one shape of
	 * its own, a fold with fewer than a few hundred OUTPUT cells, which on a device is a
	 * single-threaded loop and loses to any CPU. A vector reduced without
	 * {@code :keepdims} is exactly that shape.
	 */
	private static @Nullable LispVal foldAxis(int op, List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		LispVal[] opts = LinalgSimd.options(args, 1, "AXIS", "KEEPDIMS");
		if (a == null || opts == null) {
			return null;
		}
		Integer axis = LinalgSimd.normAxis(opts[0], a.rank());
		if (axis == null) {
			return null;
		}
		int[] d = a.dims();
		int len = d[axis];
		if (len == 0) {
			return null;
		}
		int outer = 1;
		int inner = 1;
		for (int i = 0; i < axis; i++) {
			outer *= d[i];
		}
		for (int i = axis + 1; i < d.length; i++) {
			inner *= d[i];
		}
		if ((!LinalgGpuKernels.worthFold((long) outer * inner * len) && !resident(a)) || (long) outer * inner < 2) {
			return null;
		}
		int[] od = LinalgSimd.axisShape(d, axis, !(opts[1] instanceof LispNil));
		if (od.length == 0) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.fold(op, single.storage(), outer, len, inner);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.fold(op, ((LispDoubleFloatArray) a).storage(), outer, len, inner);
		return c == null ? null : new LispDoubleFloatArray(c, od);
	}

	/**
	 * {@code linalg:transpose} at both call shapes: the axes form (a rank-n permutation,
	 * from the size threshold or over a resident operand) and -- since {@code .todo/491}
	 * -- the plain form, the matrix transpose, over a resident operand only, as a strided
	 * copy. Both pure copies, so bit-identical. The plain form over a vector or a rank
	 * above 2 is the defun's (it answers the vector itself, and an error).
	 */
	private static @Nullable LispVal transpose(List<LispVal> args) {
		if (args.size() == 2) {
			return transposeAxes(args);
		}
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		if (a == null || a.rank() != 2 || !resident(a)) {
			return null;
		}
		int r = a.dims()[0], c = a.dims()[1];
		int[] od = { c, r };
		return copyInto(a, 0, new int[] { 1, c }, od, new int[] { r, 1 }, od);
	}

	/**
	 * A strided copy of {@code a} into a fresh array of shape {@code od}, walked over
	 * {@code dims} from {@code base} by {@code sa} on the source and {@code so} on the
	 * destination -- the resident tier's copy member, or {@code null} when it declined.
	 */
	private static @Nullable LispVal copyInto(LispFloatArray a, int base, int[] sa, int[] dims, int[] so, int[] od) {
		int n = 1;
		for (int d : od) {
			n *= d;
		}
		int na = a.totalSize();
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.resultF(n);
			return LinalgGpuKernels.copy(single.storage(), base, sa, na, c, 0, so, n, dims)
					? new LispSingleFloatArray(c, od) : null;
		}
		double[] c = LinalgGpuKernels.result(n);
		return LinalgGpuKernels.copy(((LispDoubleFloatArray) a).storage(), base, sa, na, c, 0, so, n, dims)
				? new LispDoubleFloatArray(c, od) : null;
	}

	/**
	 * {@code (linalg:reshape a shape)} over a resident operand: the same elements under a
	 * new header, one contiguous copy. A shape with {@code -1} is resolved against the
	 * operand's element count ({@link LinalgSimd#reshapeShape}), the same as the defun.
	 */
	private static @Nullable LispVal reshape(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		if (a == null || !resident(a)) {
			return null;
		}
		int n = a.totalSize();
		int[] od = LinalgSimd.reshapeShape(args.get(1), n);
		if (od == null) {
			return null;
		}
		long total = 1;
		for (int d : od) {
			total *= d;
		}
		if (total != n || n < 1) {
			return null;
		}
		return copyInto(a, 0, new int[] { 1 }, new int[] { n }, new int[] { 1 }, od);
	}

	/**
	 * {@code (linalg::%la-gather-strided a od rs base single)} -- the walk behind
	 * {@code linalg:slice} and {@code broadcast-to} -- over a resident operand: one
	 * strided copy, the innermost-first strides reversed into the device's per-axis
	 * order, the base as the walk's origin, a negative stride allowed. Declines a width
	 * flag that is not the operand's (the CPU widens; the device copies), an empty
	 * output, and a walk outside the operand, like {@code LinalgSimd.gatherStrided}.
	 */
	private static @Nullable LispVal gatherStrided(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		int[] od = LinalgSimd.shape(args.get(1));
		int[] rs = LinalgSimd.ints(args.get(2));
		Integer base = LinalgSimd.smallInt(args.get(3));
		if (a == null || od == null || rs == null || base == null || rs.length != od.length || !resident(a)) {
			return null;
		}
		boolean single = !(args.get(4) instanceof LispNil);
		if (single != (a instanceof LispSingleFloatArray)) {
			return null;
		}
		int rank = od.length;
		int[] sa = new int[rank];
		long total = 1;
		for (int k = 0; k < rank; k++) {
			sa[k] = rs[rank - 1 - k];
			total *= od[k];
		}
		if (total < 1 || total > Integer.MAX_VALUE || !LinalgSimd.gatherInBounds(od, sa, base, a.totalSize())) {
			return null;
		}
		return copyInto(a, base, sa, od, rowMajorStrides(od), od);
	}

	/**
	 * {@code (linalg:concatenate arrays :axis ax)} -- {@code torch:cat} -- over packed
	 * inputs of one width of which at least one is resident: one strided copy per input
	 * into its slab of the output, the resident input first so that the output is
	 * resident for the rest (which are then uploaded into it). The defun's shape rules;
	 * anything it would signal on declines to it.
	 */
	private static @Nullable LispVal concatenate(List<LispVal> args) {
		LispVal[] opts = LinalgSimd.options(args, 1, "AXIS");
		if (opts == null) {
			return null;
		}
		List<LispFloatArray> inputs = new java.util.ArrayList<>();
		LispVal cursor = args.get(0);
		while (cursor instanceof am.ik.rontolisp.LispCons cons) {
			LispFloatArray a = LinalgSimd.packed(cons.car());
			if (a == null) {
				return null;
			}
			inputs.add(a);
			cursor = cons.cdr();
		}
		if (!(cursor instanceof LispNil) || inputs.isEmpty()) {
			return null;
		}
		LispFloatArray first = inputs.get(0);
		int rank = first.rank();
		Integer ax = opts[0] instanceof LispNil ? Integer.valueOf(0) : LinalgSimd.normAxis(opts[0], rank);
		if (ax == null) {
			return null;
		}
		int[] d0 = first.dims();
		long total = 0;
		boolean anyResident = false;
		for (LispFloatArray a : inputs) {
			if (a.getClass() != first.getClass() || a.rank() != rank) {
				return null;
			}
			for (int k = 0; k < rank; k++) {
				if (k != ax && a.dims()[k] != d0[k]) {
					return null;
				}
			}
			total += a.dims()[ax];
			anyResident |= resident(a);
		}
		if (!anyResident || total < 1 || total > Integer.MAX_VALUE) {
			return null;
		}
		int[] od = d0.clone();
		od[ax] = (int) total;
		int[] so = rowMajorStrides(od);
		long n = 1;
		for (int d : od) {
			n *= d;
		}
		if (n > Integer.MAX_VALUE - 8) {
			return null;
		}
		// The resident input first: its copy makes the output resident, and every other
		// input's copy then finds the output there.
		int lead = 0;
		while (!resident(inputs.get(lead))) {
			lead++;
		}
		int[] offsets = new int[inputs.size()];
		for (int i = 0, cum = 0; i < inputs.size(); i++) {
			offsets[i] = cum * so[ax];
			cum += inputs.get(i).dims()[ax];
		}
		boolean single = first instanceof LispSingleFloatArray;
		float[] cf = single ? LinalgGpuKernels.resultF((int) n) : null;
		double[] cd = single ? null : LinalgGpuKernels.result((int) n);
		for (int step = 0; step < inputs.size(); step++) {
			int i = step == 0 ? lead : (step <= lead ? step - 1 : step);
			LispFloatArray a = inputs.get(i);
			int[] dims = a.dims();
			boolean ok = single
					? LinalgGpuKernels.copy(((LispSingleFloatArray) a).storage(), 0, rowMajorStrides(dims),
							a.totalSize(), java.util.Objects.requireNonNull(cf), offsets[i], so, (int) n, dims)
					: LinalgGpuKernels.copy(((LispDoubleFloatArray) a).storage(), 0, rowMajorStrides(dims),
							a.totalSize(), java.util.Objects.requireNonNull(cd), offsets[i], so, (int) n, dims);
			if (!ok) {
				// A later slab declining after the first was written: the output is a
				// resident half-filled array nobody holds; the defun runs from scratch.
				return null;
			}
		}
		return single ? new LispSingleFloatArray(java.util.Objects.requireNonNull(cf), od)
				: new LispDoubleFloatArray(java.util.Objects.requireNonNull(cd), od);
	}

	/** The row-major strides of a shape, in elements. */
	private static int[] rowMajorStrides(int[] dims) {
		int[] s = new int[dims.length];
		int acc = 1;
		for (int k = dims.length - 1; k >= 0; k--) {
			s[k] = acc;
			acc *= dims[k];
		}
		return s;
	}

	/**
	 * {@code (linalg::%la-scale g s)} -- gradient clipping's in-place multiply -- over a
	 * resident array: the kernel reads and writes the one resident buffer, which stays
	 * the authoritative copy, so the Adam update that follows finds the gradient there.
	 * Answers {@code g}, as the CPU kernel does.
	 */
	private static @Nullable LispVal scaleInPlace(List<LispVal> args) {
		LispFloatArray g = LinalgSimd.packed(args.get(0));
		Double s = number(args.get(1));
		if (g == null || s == null || !resident(g)) {
			return null;
		}
		boolean ran = g instanceof LispSingleFloatArray f ? LinalgGpuKernels.scaleInPlace(f.storage(), f.totalSize(), s)
				: LinalgGpuKernels.scaleInPlace(((LispDoubleFloatArray) g).storage(), g.totalSize(), s);
		return ran ? g : null;
	}

	/**
	 * The AXES form of {@code linalg:transpose} ({@code %la-transpose-axes}): a rank-n
	 * permutation, which is a pure permuted COPY and therefore trivially bit-identical.
	 * The device reads it as one source stride per output axis.
	 *
	 * <p>
	 * Declined: the plain (no-axes) form, a bad permutation, a boxed operand, and
	 * anything below the size threshold.
	 */
	private static @Nullable LispVal transposeAxes(List<LispVal> args) {
		LispFloatArray a = LinalgSimd.packed(args.get(0));
		if (a == null) {
			return null;
		}
		int rank = a.rank();
		// The size test first, for the reason bcast's is first: a transpose's output is
		// its operand's own element count, so this costs nothing and allocates nothing.
		boolean resident = resident(a);
		if (!resident && !LinalgGpuKernels.worthStrided(a.totalSize())) {
			return null;
		}
		int[] axes = LinalgSimd.permutation(args.get(1), rank);
		if (axes == null) {
			return null;
		}
		int[] d = a.dims();
		int[] source = new int[rank];
		int acc = 1;
		for (int i = rank - 1; i >= 0; i--) {
			source[i] = acc;
			acc *= d[i];
		}
		int[] od = new int[rank];
		int[] sa = new int[rank];
		long total = 1;
		for (int k = 0; k < rank; k++) {
			od[k] = d[axes[k]];
			sa[k] = source[axes[k]];
			total *= od[k];
		}
		if (!resident && !LinalgGpuKernels.worthStrided(total)) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.gather(single.storage(), sa, od);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.gather(((LispDoubleFloatArray) a).storage(), sa, od);
		return c == null ? null : new LispDoubleFloatArray(c, od);
	}

	/**
	 * Row-major strides of the dims-{@code d} operand aligned to the broadcast shape
	 * {@code od}, with 0 on every stretched axis -- {@code %la-bcast-strides} verbatim,
	 * and the same code {@code LinalgSimdKernels} walks its odometer with.
	 */
	private static int[] bcastStrides(int[] d, int[] od) {
		int[] s = new int[od.length];
		int acc = 1;
		for (int k = od.length - 1, i = d.length - 1; k >= 0; k--, i--) {
			int n = i >= 0 ? d[i] : 1;
			s[k] = n == 1 ? 0 : acc;
			acc *= n;
		}
		return s;
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
		if (m != b.dims()[0] || !(LinalgGpuKernels.worth(n, m, p) || resident(a) || resident(b))) {
			return null;
		}
		int[] dims = { n, p };
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.multiply(single.storage(), ((LispSingleFloatArray) b).storage(), n, m, p);
			return c == null ? null : new LispSingleFloatArray(c, dims);
		}
		double[] c = LinalgGpuKernels.multiply(((LispDoubleFloatArray) a).storage(),
				((LispDoubleFloatArray) b).storage(), n, m, p);
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
		return matmulNd(args, false, false);
	}

	/**
	 * The same product with either operand read TRANSPOSED IN PLACE
	 * ({@code linalg::%la-matmul-nd-ta} and {@code -tb}): its last two axes are exchanged
	 * as the product sees it, so a slab the caller holds as {@code m x n} is the
	 * {@code n x m} left operand and the device indexes it where it lies. That is the
	 * shape of both matmul adjoints ({@code g . b^T} and {@code a^T . g}), and the
	 * transpose they used to reach it through was a full strided copy of an activation
	 * per backward call.
	 *
	 * <p>
	 * The per-batch strides are the operand's OWN either way -- a transposed slab holds
	 * the same {@code n * m} elements -- and the fold is untouched, so the result is the
	 * plain product of the transposed copy bit for bit. Everything declines exactly as
	 * the plain member does; both backends carry the orientation, so a decline here is
	 * never about the orientation and the defun's own transpose-then-multiply answers
	 * whatever the plain member's decline would have answered.
	 */
	private static @Nullable LispVal matmulNd(List<LispVal> args, boolean ta, boolean tb) {
		if (!(args.get(0) instanceof LispFloatArray a) || !(args.get(1) instanceof LispFloatArray b)
				|| a.getClass() != b.getClass() || a.rank() < 2 || b.rank() < 2) {
			return null;
		}
		int[] da = a.dims();
		int[] db = b.dims();
		int n = da[da.length - (ta ? 1 : 2)];
		int m = da[da.length - (ta ? 2 : 1)];
		int p = db[db.length - (tb ? 2 : 1)];
		if (m != db[db.length - (tb ? 1 : 2)] || n < 1 || m < 1 || p < 1) {
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
		if (batches < 1 || total > Integer.MAX_VALUE - 8
				|| !(LinalgGpuKernels.worth(batches, n, m, p) || resident(a) || resident(b))) {
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
		boolean transposed = ta || tb;
		if (a instanceof LispSingleFloatArray single) {
			float[] sb2 = ((LispSingleFloatArray) b).storage();
			float[] c = transposed
					? LinalgGpuKernels.multiply(single.storage(), (int) sa, ta, sb2, (int) sb, tb, batch, n, m, p)
					: LinalgGpuKernels.multiply(single.storage(), (int) sa, sb2, (int) sb, batch, n, m, p);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] da2 = ((LispDoubleFloatArray) a).storage();
		double[] db2 = ((LispDoubleFloatArray) b).storage();
		double[] c = transposed ? LinalgGpuKernels.multiply(da2, (int) sa, ta, db2, (int) sb, tb, batch, n, m, p)
				: LinalgGpuKernels.multiply(da2, (int) sa, db2, (int) sb, batch, n, m, p);
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
