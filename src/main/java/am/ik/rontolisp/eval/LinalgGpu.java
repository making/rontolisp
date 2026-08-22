package am.ik.rontolisp.eval;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
 * trip. That is a measurement ({@code .kb/gpu.md}), not a staging decision.
 *
 * <h2>Only the big shapes, and there are two size rules</h2>
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
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_DOT, 2, LinalgGpu::dot);
		// The stacked product behind linalg:matmul at rank >= 3. A %-prefixed member is
		// an internal symbol, whose canonical qualified spelling carries the double colon
		// (.kb/linalg-simd.md).
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_MATMUL_ND, 2, LinalgGpu::matmulNd);
		// The ELEMENT-WISE tier: the twelve unary ufuncs whose scalar cost is a libm
		// call. linalg:sqrt / abs / negative / sign and the binary add / sub / mul / div
		// are NOT here -- they move one or three streams for one machine instruction, so
		// a round trip cannot pay for them, and that is a measurement (.kb/gpu.md) rather
		// than an assumption.
		for (Map.Entry<String, Integer> member : MAP_MEMBERS.entrySet()) {
			int op = member.getValue();
			define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + member.getKey(), 1, args -> map(op, args));
		}
		// The STRIDED tier: the three shapes whose CPU twin is a scalar ODOMETER walk.
		// The binary ops are the same names the element-wise tier refuses -- and this is
		// not a reversal of that refusal: it declines an EQUAL-shaped pair, where the CPU
		// runs a lane loop, and takes only a genuine BROADCAST (see bcast).
		for (Map.Entry<String, Integer> member : BIN_MEMBERS.entrySet()) {
			int op = member.getValue();
			define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + member.getKey(), 2, args -> bcast(op, args));
		}
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
		define(globalEnv, evaluator, LispNames.LINALG_PKG + ":" + LispNames.LINALG_TRANSPOSE, 2, 2,
				LinalgGpu::transposeAxes);
		// The seeded generator's fill (linalg:rand / randn / uniform): no operand goes
		// up, the draws come back, and the closed-form jump makes it bit-identical to
		// the sequential walk -- the one member here whose result is byte-for-byte the
		// CPU's at any size. It was a fifth of a --gpu --simd training step as the
		// dropout masks (.kb/gpu.md).
		define(globalEnv, evaluator, LispNames.LINALG_PKG + "::" + LispNames.LINALG_RNG_FILL, 5, LinalgGpu::rngFill);
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
			case LispDoubleFloatArray d -> LinalgGpuKernels.rngFill(d.data(), mode, lo, span, w[0], w[1], w[2]);
			case LispSingleFloatArray f -> LinalgGpuKernels.rngFill(f.data(), mode, lo, span, w[0], w[1], w[2]);
		};
		return end == null ? null : new LispDoubleFloatArray(end, new int[] { 3 });
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
	private static final Map<String, Integer> BIN_MEMBERS = Map.of(LispNames.LINALG_ADD, LinalgGpuKernels.BIN_ADD,
			LispNames.LINALG_SUB, LinalgGpuKernels.BIN_SUB, LispNames.LINALG_MUL, LinalgGpuKernels.BIN_MUL,
			LispNames.LINALG_DIV, LinalgGpuKernels.BIN_DIV, LispNames.LINALG_MAXIMUM, LinalgGpuKernels.BIN_MAX,
			LispNames.LINALG_MINIMUM, LinalgGpuKernels.BIN_MIN);

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
			Map.entry(LispNames.LINALG_ERF, LinalgGpuKernels.MAP_ERF));

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
			throw new IllegalStateException("linalg.lisp must be loaded before " + qualified + " can be accelerated");
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
		if (!LinalgGpuKernels.worthMap(n)) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.map(op, single.data(), n);
			return c == null ? null : new LispSingleFloatArray(c, a.dims().clone());
		}
		double[] c = LinalgGpuKernels.map(op, ((LispDoubleFloatArray) a).data(), n);
		return c == null ? null : new LispDoubleFloatArray(c, a.dims().clone());
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
		if (!(args.get(0) instanceof LispFloatArray a) || !(args.get(1) instanceof LispFloatArray b)
				|| a.getClass() != b.getClass()) {
			return null;
		}
		int[] da = a.dims();
		int[] db = b.dims();
		if (Arrays.equals(da, db)) {
			return null;
		}
		// The size test FIRST, over a bound that costs nothing: a broadcast output is at
		// least as big as either operand. Every linalg:add in a program pays this method,
		// so a declined call must not allocate a shape it is about to throw away.
		if (!LinalgGpuKernels.worthStrided(Math.max(a.totalSize(), b.totalSize()))) {
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
		if (!LinalgGpuKernels.worthStrided(total)) {
			return null;
		}
		int[] sa = bcastStrides(da, od);
		int[] sb = bcastStrides(db, od);
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.bcast(op, single.data(), sa, ((LispSingleFloatArray) b).data(), sb, od);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.bcast(op, ((LispDoubleFloatArray) a).data(), sa,
				((LispDoubleFloatArray) b).data(), sb, od);
		return c == null ? null : new LispDoubleFloatArray(c, od);
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
		if (!LinalgGpuKernels.worthFold((long) outer * inner * len) || (long) outer * inner < 2) {
			return null;
		}
		int[] od = LinalgSimd.axisShape(d, axis, !(opts[1] instanceof LispNil));
		if (od.length == 0) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.fold(op, single.data(), outer, len, inner);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.fold(op, ((LispDoubleFloatArray) a).data(), outer, len, inner);
		return c == null ? null : new LispDoubleFloatArray(c, od);
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
		if (!LinalgGpuKernels.worthStrided(a.totalSize())) {
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
		if (!LinalgGpuKernels.worthStrided(total)) {
			return null;
		}
		if (a instanceof LispSingleFloatArray single) {
			float[] c = LinalgGpuKernels.gather(single.data(), sa, od);
			return c == null ? null : new LispSingleFloatArray(c, od);
		}
		double[] c = LinalgGpuKernels.gather(((LispDoubleFloatArray) a).data(), sa, od);
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
