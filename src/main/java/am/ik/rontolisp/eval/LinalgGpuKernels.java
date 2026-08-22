package am.ik.rontolisp.eval;

import am.ik.gpu.Gpu;
import org.jspecify.annotations.Nullable;

/**
 * The thin layer between {@link LinalgGpu} and {@code am.ik.gpu}: it unwraps a product
 * onto the library's primitive-array API, allocates the result the interpreter's packed
 * arrays want, and rewraps a decline as Java {@code null}. Reached only through
 * {@link LinalgGpu}, which is what makes {@code src/web/java/.../Target_LinalgGpu.java}
 * enough to keep {@code am.ik.gpu} -- and with it {@code java.lang.foreign} -- out of the
 * browser Web Image build.
 *
 * <h2>Why this class exists at all, when {@code am.ik.gpu} is already the whole
 * binding</h2>
 *
 * {@code --blas}'s sibling ({@link LinalgBlasKernels}) holds the CBLAS binding itself,
 * because there is nowhere else for it to live. A GPU's binding is a library of its own
 * ({@code .kb/gpu.md}), so what is left here is only the two things an interpreter
 * interceptor adds: the null-versus-boolean impedance match, and the ONE reference to
 * {@code am.ik.gpu} that the Web Image substitution has to be able to cut. Keeping it a
 * class rather than folding it into {@link LinalgGpu} is what makes that cut possible:
 * substituting {@link LinalgGpu}'s three entry points leaves this class unreachable, and
 * a substituted method body never mentions the library.
 *
 * <h2>Never throws</h2>
 *
 * {@code am.ik.gpu}'s own invariant is that every failure -- no driver, no device, an old
 * card, a dead context, device memory exhausted -- is a decline rather than an exception,
 * so nothing here has to translate errors. The {@code catch} in {@link #available()} and
 * {@link #description()} guards against the one case the library cannot: a runtime that
 * cannot LINK it at all (no {@code java.lang.foreign}, a forbidden native access check
 * failing during class initialization).
 *
 * @see LinalgGpu
 */
final class LinalgGpuKernels {

	/**
	 * The op codes {@link #map} takes, re-exported from the library so that
	 * {@link LinalgGpu} -- which must not name {@code am.ik.gpu} at all, or the Web Image
	 * substitution would not cut it -- can still say which member it is asking for. They
	 * are compile-time constants, so they inline into the caller and leave no reference
	 * behind.
	 */
	static final int MAP_EXP = Gpu.MAP_EXP, MAP_LOG = Gpu.MAP_LOG, MAP_TANH = Gpu.MAP_TANH, MAP_SIN = Gpu.MAP_SIN,
			MAP_COS = Gpu.MAP_COS, MAP_TAN = Gpu.MAP_TAN, MAP_ASIN = Gpu.MAP_ASIN, MAP_ACOS = Gpu.MAP_ACOS,
			MAP_ATAN = Gpu.MAP_ATAN, MAP_SINH = Gpu.MAP_SINH, MAP_COSH = Gpu.MAP_COSH, MAP_ERF = Gpu.MAP_ERF;

	/**
	 * The op codes {@link #bcast} and {@link #fold} take, re-exported for the same reason
	 * the {@code MAP_*} ones are. {@code BIN_MAX} / {@code BIN_MIN} are the strict
	 * selects, so the SECOND operand wins a tie, exactly as
	 * {@code LinalgSimdKernels.BOP_MAX} does.
	 */
	static final int BIN_ADD = Gpu.BIN_ADD, BIN_SUB = Gpu.BIN_SUB, BIN_MUL = Gpu.BIN_MUL, BIN_DIV = Gpu.BIN_DIV,
			BIN_MAX = Gpu.BIN_MAX, BIN_MIN = Gpu.BIN_MIN, FOLD_SUM = Gpu.FOLD_SUM, FOLD_AMAX = Gpu.FOLD_AMAX,
			FOLD_AMIN = Gpu.FOLD_AMIN;

	private LinalgGpuKernels() {
	}

	/** Whether this machine has a GPU the kernels can run on. Runs the probe once. */
	static boolean available() {
		try {
			return Gpu.available();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * Tells the library that a packed array's storage was written in place, so any
	 * resident device copy of it is stale ({@code Gpu.written}). The interpreter's half
	 * of the invalidation enumeration: installed on {@code FloatArrayWriteHook} by
	 * {@link LinalgGpu#install}, it is reached from every element setter and every
	 * in-place {@code --simd} kernel.
	 * @param data the {@code double[]} or {@code float[]} that was written
	 */
	static void written(Object data) {
		Gpu.written(data);
	}

	/** What was found, or why nothing was -- the text the CLI reports. */
	static String description() {
		try {
			return Gpu.description();
		}
		catch (Throwable ex) {
			return "no GPU could be probed: " + ex;
		}
	}

	/**
	 * Whether an {@code n x m} by {@code m x p} product is big enough to be offered to
	 * the device at all. A pure size predicate that touches no driver, so the caller can
	 * ask it before unwrapping its operands; {@code multiply} re-asks the real question
	 * and may still decline.
	 * @param n rows of the left operand and of the result
	 * @param m the inner dimension
	 * @param p columns of the right operand and of the result
	 * @return {@code true} when the product is worth unwrapping for
	 */
	static boolean worth(long n, long m, long p) {
		return Gpu.worth(n, m, p);
	}

	/**
	 * The same question for a STACK of {@code batch} such products, which is one round
	 * trip and one launch -- so the threshold is over the TOTAL work rather than over one
	 * matrix.
	 * @param batch how many products are stacked
	 * @param n rows of each left operand and of each result
	 * @param m the inner dimension
	 * @param p columns of each right operand and of each result
	 * @return {@code true} when the stack is worth unwrapping for
	 */
	static boolean worth(long batch, long n, long m, long p) {
		return Gpu.worth(batch, n, m, p);
	}

	/**
	 * Whether an element-wise map over {@code n} elements is worth a round trip -- the
	 * element-wise tier's size predicate, which touches no driver.
	 * @param n how many elements the map covers
	 * @return {@code true} when the map is worth unwrapping for
	 */
	static boolean worthMap(long n) {
		return Gpu.worthMap(n);
	}

	/**
	 * {@code op} applied to every element of a double-float array, or {@code null} when
	 * the device declined it.
	 * @param op one of {@code am.ik.gpu.Gpu}'s {@code MAP_*} constants
	 * @param a the operand
	 * @param n how many elements it holds
	 * @return a fresh {@code n} result, or {@code null}
	 */
	static double @Nullable [] map(int op, double[] a, int n) {
		double[] out = new double[n];
		return Gpu.map(op, a, 0, out, 0, n) ? out : null;
	}

	/**
	 * The single-float sibling of {@link #map(int, double[], int)}.
	 * @param op one of {@code am.ik.gpu.Gpu}'s {@code MAP_*} constants
	 * @param a the operand
	 * @param n how many elements it holds
	 * @return a fresh {@code n} result, or {@code null}
	 */
	static float @Nullable [] map(int op, float[] a, int n) {
		float[] out = new float[n];
		return Gpu.map(op, a, 0, out, 0, n) ? out : null;
	}

	/**
	 * Whether a generator fill of {@code n} elements is worth a round trip -- the one
	 * member with no operand to copy up, so its threshold is the lowest of the set.
	 * @param n how many elements the fill writes
	 * @return {@code true} when the fill is worth unwrapping for
	 */
	static boolean worthRng(long n) {
		return Gpu.worthRng(n);
	}

	/**
	 * {@code linalg::%la-rng-fill}'s loop on the device: fills {@code out} from the state
	 * {@code (s1, s2, s3)} by the closed-form jump and answers the state the generator
	 * ends on as a fresh three-element vector -- or {@code null} when the device
	 * declined, in which case {@code out} is untouched.
	 * @param out the destination
	 * @param mode 0 one uniform draw, 1 the Irwin-Hall normal, 2 {@code lo + span * draw}
	 * @param lo rule 2's lower bound
	 * @param span rule 2's range
	 * @param s1 the first state word
	 * @param s2 the second state word
	 * @param s3 the third state word
	 * @return the end state, or {@code null}
	 */
	static double @Nullable [] rngFill(double[] out, int mode, double lo, double span, int s1, int s2, int s3) {
		if (!Gpu.rngFill(out, 0, out.length, mode, lo, span, s1, s2, s3)) {
			return null;
		}
		return endState(out.length, mode, s1, s2, s3);
	}

	/**
	 * The single-float sibling of
	 * {@link #rngFill(double[], int, double, double, int, int, int)}.
	 */
	static double @Nullable [] rngFill(float[] out, int mode, double lo, double span, int s1, int s2, int s3) {
		if (!Gpu.rngFill(out, 0, out.length, mode, lo, span, s1, s2, s3)) {
			return null;
		}
		return endState(out.length, mode, s1, s2, s3);
	}

	private static double[] endState(int n, int mode, int s1, int s2, int s3) {
		int[] end = Gpu.rngAdvance(s1, s2, s3, (long) n * (mode == 1 ? 12 : 1));
		return new double[] { end[0], end[1], end[2] };
	}

	/**
	 * Whether a broadcast binary op or an axes transpose over {@code n} OUTPUT elements
	 * is worth a round trip -- the strided tier's size predicate, which touches no
	 * driver.
	 * @param n how many elements the result holds
	 * @return {@code true} when the call is worth unwrapping for
	 */
	static boolean worthStrided(long n) {
		return Gpu.worthStrided(n);
	}

	/**
	 * Whether an axis fold over {@code n} INPUT elements is worth a round trip.
	 * @param n how many elements the operand holds
	 * @return {@code true} when the fold is worth unwrapping for
	 */
	static boolean worthFold(long n) {
		return Gpu.worthFold(n);
	}

	/**
	 * A BROADCAST binary element-wise op over two double-float operands, or {@code null}
	 * when the device declined it. Each operand follows its own per-axis stride over the
	 * output shape, 0 on an axis it is stretched across.
	 * @param op one of the {@code BIN_*} constants
	 * @param a the left operand
	 * @param sa {@code a}'s stride along each output axis
	 * @param b the right operand
	 * @param sb {@code b}'s stride along each output axis
	 * @param dims the output shape
	 * @return a fresh result of {@code dims} elements, or {@code null}
	 */
	static double @Nullable [] bcast(int op, double[] a, int[] sa, double[] b, int[] sb, int[] dims) {
		double[] out = new double[count(dims)];
		return Gpu.bcast(op, a, 0, sa, b, 0, sb, out, 0, dims) ? out : null;
	}

	/**
	 * The single-float sibling of
	 * {@link #bcast(int, double[], int[], double[], int[], int[])}.
	 * @param op one of the {@code BIN_*} constants
	 * @param a the left operand
	 * @param sa {@code a}'s stride along each output axis
	 * @param b the right operand
	 * @param sb {@code b}'s stride along each output axis
	 * @param dims the output shape
	 * @return a fresh result of {@code dims} elements, or {@code null}
	 */
	static float @Nullable [] bcast(int op, float[] a, int[] sa, float[] b, int[] sb, int[] dims) {
		float[] out = new float[count(dims)];
		return Gpu.bcast(op, a, 0, sa, b, 0, sb, out, 0, dims) ? out : null;
	}

	/**
	 * The permuted copy behind an axes transpose, or {@code null} when the device
	 * declined it.
	 * @param a the operand
	 * @param sa the source stride along each output axis
	 * @param dims the output shape
	 * @return a fresh result of {@code dims} elements, or {@code null}
	 */
	static double @Nullable [] gather(double[] a, int[] sa, int[] dims) {
		double[] out = new double[count(dims)];
		return Gpu.gather(a, 0, sa, out, 0, dims) ? out : null;
	}

	/**
	 * The single-float sibling of {@link #gather(double[], int[], int[])}.
	 * @param a the operand
	 * @param sa the source stride along each output axis
	 * @param dims the output shape
	 * @return a fresh result of {@code dims} elements, or {@code null}
	 */
	static float @Nullable [] gather(float[] a, int[] sa, int[] dims) {
		float[] out = new float[count(dims)];
		return Gpu.gather(a, 0, sa, out, 0, dims) ? out : null;
	}

	/**
	 * The fold of one axis of a double-float array, or {@code null} when the device
	 * declined it: {@code outer * inner} cells, each folding {@code len} elements in
	 * ascending order in a double accumulator.
	 * @param op one of the {@code FOLD_*} constants
	 * @param a the operand
	 * @param outer the product of the axes before the folded one
	 * @param len the folded axis's extent
	 * @param inner the product of the axes after it
	 * @return a fresh {@code outer * inner} result, or {@code null}
	 */
	static double @Nullable [] fold(int op, double[] a, int outer, int len, int inner) {
		double[] out = new double[outer * inner];
		return Gpu.fold(op, a, 0, out, 0, outer, len, inner) ? out : null;
	}

	/**
	 * The single-float sibling of {@link #fold(int, double[], int, int, int)}. The
	 * accumulator is still a double and only the store narrows, which is the defun's own
	 * rule.
	 * @param op one of the {@code FOLD_*} constants
	 * @param a the operand
	 * @param outer the product of the axes before the folded one
	 * @param len the folded axis's extent
	 * @param inner the product of the axes after it
	 * @return a fresh {@code outer * inner} result, or {@code null}
	 */
	static float @Nullable [] fold(int op, float[] a, int outer, int len, int inner) {
		float[] out = new float[outer * inner];
		return Gpu.fold(op, a, 0, out, 0, outer, len, inner) ? out : null;
	}

	/** The element count of a shape. The caller has already bounded it to an int. */
	private static int count(int[] dims) {
		int n = 1;
		for (int d : dims) {
			n *= d;
		}
		return n;
	}

	/**
	 * {@code a x b} for a row-major {@code n x m} by {@code m x p} double-float pair, or
	 * {@code null} when the device declined it.
	 * @param a the left operand, row-major
	 * @param b the right operand, row-major
	 * @param n rows of {@code a} and of the result
	 * @param m columns of {@code a} and rows of {@code b}
	 * @param p columns of {@code b} and of the result
	 * @return a fresh {@code n * p} result, or {@code null}
	 */
	static double @Nullable [] multiply(double[] a, double[] b, int n, int m, int p) {
		double[] out = new double[n * p];
		return Gpu.multiply(a, 0, b, 0, out, 0, n, m, p) ? out : null;
	}

	/**
	 * The single-float sibling of {@link #multiply(double[], double[], int, int, int)},
	 * and the width this hardware is for.
	 * @param a the left operand, row-major
	 * @param b the right operand, row-major
	 * @param n rows of {@code a} and of the result
	 * @param m columns of {@code a} and rows of {@code b}
	 * @param p columns of {@code b} and of the result
	 * @return a fresh {@code n * p} result, or {@code null}
	 */
	static float @Nullable [] multiply(float[] a, float[] b, int n, int m, int p) {
		float[] out = new float[n * p];
		return Gpu.multiply(a, 0, b, 0, out, 0, n, m, p) ? out : null;
	}

	/**
	 * A STACK of {@code batch} double-float products, or {@code null} when the device
	 * declined it. {@code sa} / {@code sb} are per-batch ELEMENT strides and either may
	 * be 0, which is what a broadcast operand passes.
	 * @param a the left operands, row-major
	 * @param sa elements from one left operand to the next, or 0 to broadcast
	 * @param b the right operands, row-major
	 * @param sb elements from one right operand to the next, or 0 to broadcast
	 * @param batch how many products are stacked
	 * @param n rows of each {@code a} and of each result
	 * @param m columns of each {@code a} and rows of each {@code b}
	 * @param p columns of each {@code b} and of each result
	 * @return a fresh {@code batch * n * p} result, or {@code null}
	 */
	static double @Nullable [] multiply(double[] a, int sa, double[] b, int sb, int batch, int n, int m, int p) {
		double[] out = new double[batch * n * p];
		return Gpu.multiply(a, 0, sa, b, 0, sb, out, 0, batch, n, m, p) ? out : null;
	}

	/**
	 * The single-float sibling of
	 * {@link #multiply(double[], int, double[], int, int, int, int, int)}.
	 * @param a the left operands, row-major
	 * @param sa elements from one left operand to the next, or 0 to broadcast
	 * @param b the right operands, row-major
	 * @param sb elements from one right operand to the next, or 0 to broadcast
	 * @param batch how many products are stacked
	 * @param n rows of each {@code a} and of each result
	 * @param m columns of each {@code a} and rows of each {@code b}
	 * @param p columns of each {@code b} and of each result
	 * @return a fresh {@code batch * n * p} result, or {@code null}
	 */
	static float @Nullable [] multiply(float[] a, int sa, float[] b, int sb, int batch, int n, int m, int p) {
		float[] out = new float[batch * n * p];
		return Gpu.multiply(a, 0, sa, b, 0, sb, out, 0, batch, n, m, p) ? out : null;
	}

}
