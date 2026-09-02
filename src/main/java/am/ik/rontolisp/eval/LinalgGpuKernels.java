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
			MAP_ATAN = Gpu.MAP_ATAN, MAP_SINH = Gpu.MAP_SINH, MAP_COSH = Gpu.MAP_COSH, MAP_ERF = Gpu.MAP_ERF,
			MAP_SQRT = Gpu.MAP_SQRT, MAP_ABS = Gpu.MAP_ABS, MAP_NEGATIVE = Gpu.MAP_NEGATIVE, MAP_SIGN = Gpu.MAP_SIGN;

	/** The first map op that is a member over a RESIDENT operand only ({@code sqrt}). */
	static final int MAP_LIBM_OPS = Gpu.MAP_LIBM_OPS;

	/**
	 * The op codes {@link #bcast} and {@link #fold} take, re-exported for the same reason
	 * the {@code MAP_*} ones are. {@code BIN_MAX} / {@code BIN_MIN} are the strict
	 * selects, so the SECOND operand wins a tie, exactly as
	 * {@code LinalgSimdKernels.BOP_MAX} does.
	 */
	static final int BIN_ADD = Gpu.BIN_ADD, BIN_SUB = Gpu.BIN_SUB, BIN_MUL = Gpu.BIN_MUL, BIN_DIV = Gpu.BIN_DIV,
			BIN_MAX = Gpu.BIN_MAX, BIN_MIN = Gpu.BIN_MIN, BIN_GT = Gpu.BIN_GT, BIN_GE = Gpu.BIN_GE, BIN_LT = Gpu.BIN_LT,
			BIN_LE = Gpu.BIN_LE, BIN_EQ = Gpu.BIN_EQ, FOLD_SUM = Gpu.FOLD_SUM, FOLD_AMAX = Gpu.FOLD_AMAX,
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
	 * A member's RESULT storage of {@code n} elements -- or, while results stay on the
	 * device ({@code Gpu.lazyResultsOn}), a STUB: an empty array, distinct per result,
	 * that the record holds as its storage and the library keys its residency on, with
	 * the elements allocated by the library the first time the host reads them
	 * ({@code Gpu.materialize}, answered through the records' {@code data()}). A result
	 * nobody reads then costs the host no array ({@code .kb/gpu.md}, "A lazy result
	 * allocates no host array").
	 */
	static float[] resultF(int n) {
		return new float[Gpu.lazyResultsOn() ? 0 : n];
	}

	/** The double-float sibling of {@link #resultF}. */
	static double[] result(int n) {
		return new double[Gpu.lazyResultsOn() ? 0 : n];
	}

	/**
	 * Tells the library that a packed array's storage was written in place, so any
	 * resident device copy of it is stale ({@code Gpu.written}). The interpreter's half
	 * of the invalidation enumeration: installed on {@code FloatArrayWriteHook} by
	 * {@link LinalgGpu#install}, it is reached from every element setter and every
	 * in-place {@code --simd} kernel. Answers the array the write must land in
	 * ({@code Gpu.written}: the storage, or a result stub's backing).
	 * @param data the {@code double[]} or {@code float[]} that was written
	 * @return the array to write into
	 */
	static Object written(Object data) {
		return Gpu.written(data);
	}

	/**
	 * Tells the library that a packed array's storage is about to be READ on the host, so
	 * that a result the device still holds the only copy of comes home first
	 * ({@code Gpu.materialize}). The interpreter's half of the reader enumeration:
	 * installed on {@code FloatArrayAccessHook} by {@link LinalgGpu#install}, it is
	 * reached from the records' {@code data()} accessor and their own element reads.
	 * Answers the array to read ({@code Gpu.materialize}: the storage, or a result stub's
	 * backing).
	 * @param data the {@code double[]} or {@code float[]} about to be read
	 * @return the array holding its bytes
	 */
	static Object materialize(Object data) {
		return Gpu.materialize(data);
	}

	/**
	 * Whether the device holds a copy of the array -- what lets a member be offered below
	 * its size threshold, or at all for the resident tier.
	 * @param data the {@code double[]} or {@code float[]}
	 * @return {@code true} when a copy is resident
	 */
	static boolean resident(Object data) {
		return Gpu.resident(data);
	}

	/**
	 * Switches lazy results on where the device says they pay (CUDA; not Metal,
	 * measured): every member's result then stays on the device until the host first
	 * reads it, which the hooks above make safe ({@code .kb/gpu.md}).
	 */
	static void lazyResults() {
		Gpu.lazyResultsIfWorthwhile();
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
		double[] out = result(n);
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
		float[] out = resultF(n);
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
	 * @param n how many elements it holds
	 * @param mode 0 one uniform draw, 1 the Irwin-Hall normal, 2 {@code lo + span * draw}
	 * @param lo rule 2's lower bound
	 * @param span rule 2's range
	 * @param s1 the first state word
	 * @param s2 the second state word
	 * @param s3 the third state word
	 * @return the end state, or {@code null}
	 */
	static double @Nullable [] rngFill(double[] out, int n, int mode, double lo, double span, int s1, int s2, int s3) {
		if (!Gpu.rngFill(out, 0, n, mode, lo, span, s1, s2, s3)) {
			return null;
		}
		return endState(n, mode, s1, s2, s3);
	}

	/**
	 * The single-float sibling of
	 * {@link #rngFill(double[], int, int, double, double, int, int, int)}.
	 */
	static double @Nullable [] rngFill(float[] out, int n, int mode, double lo, double span, int s1, int s2, int s3) {
		if (!Gpu.rngFill(out, 0, n, mode, lo, span, s1, s2, s3)) {
			return null;
		}
		return endState(n, mode, s1, s2, s3);
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
		double[] out = result(count(dims));
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
		float[] out = resultF(count(dims));
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
		double[] out = result(count(dims));
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
		float[] out = resultF(count(dims));
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
		double[] out = result(outer * inner);
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
		float[] out = resultF(outer * inner);
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
	 * Whether a {@code rows x cols} matrix-by-vector product is worth offering at all --
	 * the GEMV's driver-free size predicate. {@code true} is "worth unwrapping for": the
	 * device then also asks whether the matrix is RESIDENT, which no size can tell.
	 * @param rows rows of the matrix
	 * @param cols columns of the matrix
	 * @return {@code true} when the product is worth unwrapping for
	 */
	static boolean worthMatvec(long rows, long cols) {
		return Gpu.worthMatvec(rows, cols);
	}

	/**
	 * {@code W x} for a row-major {@code rows x cols} double-float matrix, or
	 * {@code null} when the device declined it -- which it does on the FIRST sight of any
	 * matrix, and always for one the program writes between calls ({@code .kb/gpu.md}).
	 * @param w the matrix, row-major
	 * @param x the vector
	 * @param rows rows of {@code w} and of the result
	 * @param cols columns of {@code w} and length of {@code x}
	 * @return a fresh {@code rows} result, or {@code null}
	 */
	static double @Nullable [] matvec(double[] w, double[] x, int rows, int cols) {
		double[] out = result(rows);
		return Gpu.matvec(w, 0, x, 0, out, 0, rows, cols) ? out : null;
	}

	/**
	 * The single-float sibling of {@link #matvec(double[], double[], int, int)}.
	 * @param w the matrix, row-major
	 * @param x the vector
	 * @param rows rows of {@code w} and of the result
	 * @param cols columns of {@code w} and length of {@code x}
	 * @return a fresh {@code rows} result, or {@code null}
	 */
	static float @Nullable [] matvec(float[] w, float[] x, int rows, int cols) {
		float[] out = resultF(rows);
		return Gpu.matvec(w, 0, x, 0, out, 0, rows, cols) ? out : null;
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
		double[] out = result(n * p);
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
		float[] out = resultF(n * p);
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
		double[] out = result(batch * n * p);
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
		float[] out = resultF(batch * n * p);
		return Gpu.multiply(a, 0, sa, b, 0, sb, out, 0, batch, n, m, p) ? out : null;
	}

	/**
	 * The same stack with either operand read TRANSPOSED IN PLACE -- stored with the last
	 * two axes exchanged, which is the orientation the two matmul adjoints have. Nothing
	 * else changes: the strides are the operand's own, and the result is the plain
	 * product of a transposed COPY bit for bit.
	 * @param a the left operands, row-major
	 * @param sa elements from one left operand to the next, or 0 to broadcast
	 * @param ta whether each left slab is stored {@code m x n}
	 * @param b the right operands, row-major
	 * @param sb elements from one right operand to the next, or 0 to broadcast
	 * @param tb whether each right slab is stored {@code p x m}
	 * @param batch how many products are stacked
	 * @param n rows of each {@code a} and of each result
	 * @param m columns of each {@code a} and rows of each {@code b}
	 * @param p columns of each {@code b} and of each result
	 * @return a fresh {@code batch * n * p} result, or {@code null}
	 */
	static double @Nullable [] multiply(double[] a, int sa, boolean ta, double[] b, int sb, boolean tb, int batch,
			int n, int m, int p) {
		double[] out = result(batch * n * p);
		return Gpu.multiply(a, 0, sa, ta, b, 0, sb, tb, out, 0, batch, n, m, p) ? out : null;
	}

	/**
	 * The single-float sibling of
	 * {@link #multiply(double[], int, boolean, double[], int, boolean, int, int, int, int)}.
	 * @param a the left operands, row-major
	 * @param sa elements from one left operand to the next, or 0 to broadcast
	 * @param ta whether each left slab is stored {@code m x n}
	 * @param b the right operands, row-major
	 * @param sb elements from one right operand to the next, or 0 to broadcast
	 * @param tb whether each right slab is stored {@code p x m}
	 * @param batch how many products are stacked
	 * @param n rows of each {@code a} and of each result
	 * @param m columns of each {@code a} and rows of each {@code b}
	 * @param p columns of each {@code b} and of each result
	 * @return a fresh {@code batch * n * p} result, or {@code null}
	 */
	static float @Nullable [] multiply(float[] a, int sa, boolean ta, float[] b, int sb, boolean tb, int batch, int n,
			int m, int p) {
		float[] out = resultF(batch * n * p);
		return Gpu.multiply(a, 0, sa, ta, b, 0, sb, tb, out, 0, batch, n, m, p) ? out : null;
	}

	// --- the resident tier (.todo/491) -------------------------------------------------

	/**
	 * {@code op} over two SAME-SHAPED double-float operands, or {@code null} when the
	 * device declined it -- which it does unless one of them is already resident.
	 * @param op one of the {@code BIN_*} constants
	 * @param a the left operand
	 * @param b the right operand
	 * @param n how many elements each holds
	 * @return a fresh {@code n} result, or {@code null}
	 */
	static double @Nullable [] zip(int op, double[] a, double[] b, int n) {
		double[] out = result(n);
		return Gpu.zip(op, a, 0, b, 0, out, 0, n) ? out : null;
	}

	/** The single-float sibling of {@link #zip(int, double[], double[], int)}. */
	static float @Nullable [] zip(int op, float[] a, float[] b, int n) {
		float[] out = resultF(n);
		return Gpu.zip(op, a, 0, b, 0, out, 0, n) ? out : null;
	}

	/**
	 * {@code op} over a resident double-float array and a double scalar -- the scalar on
	 * the left when {@code swap} -- or {@code null} when the device declined it.
	 * @param op one of the {@code BIN_*} constants
	 * @param a the array
	 * @param s the scalar
	 * @param swap whether the scalar is the left operand
	 * @param n how many elements
	 * @return a fresh {@code n} result, or {@code null}
	 */
	static double @Nullable [] scale(int op, double[] a, double s, boolean swap, int n) {
		double[] out = result(n);
		return Gpu.scale(op, a, 0, s, swap, out, 0, n) ? out : null;
	}

	/**
	 * The single-float sibling of {@link #scale(int, double[], double, boolean, int)}.
	 */
	static float @Nullable [] scale(int op, float[] a, double s, boolean swap, int n) {
		float[] out = resultF(n);
		return Gpu.scale(op, a, 0, s, swap, out, 0, n) ? out : null;
	}

	/**
	 * {@code linalg:where} over three operands broadcast to {@code dims}, any of which
	 * may be a scalar ({@code null} array, its value beside it), at double width; or
	 * {@code null} when the device declined it.
	 * @param m the mask, either width, or null
	 * @param sm the mask's strides over the output, or null
	 * @param ms the mask's value when scalar
	 * @param x the where-true operand or null
	 * @param sx its strides or null
	 * @param xs its value when scalar
	 * @param y the where-false operand or null
	 * @param sy its strides or null
	 * @param ys its value when scalar
	 * @param dims the output shape
	 * @return a fresh result of {@code dims} elements, or {@code null}
	 */
	static double @Nullable [] where(@Nullable Object m, int @Nullable [] sm, double ms, double @Nullable [] x,
			int @Nullable [] sx, double xs, double @Nullable [] y, int @Nullable [] sy, double ys, int[] dims) {
		int[] zero = new int[dims.length];
		double[] out = result(count(dims));
		return Gpu.where(m, 0, sm == null ? zero : sm, ms, x, 0, sx == null ? zero : sx, xs, y, 0,
				sy == null ? zero : sy, ys, out, 0, dims) ? out : null;
	}

	/** The single-float sibling of {@link #where}. */
	static float @Nullable [] where(@Nullable Object m, int @Nullable [] sm, double ms, float @Nullable [] x,
			int @Nullable [] sx, double xs, float @Nullable [] y, int @Nullable [] sy, double ys, int[] dims) {
		int[] zero = new int[dims.length];
		float[] out = resultF(count(dims));
		return Gpu.where(m, 0, sm == null ? zero : sm, ms, x, 0, sx == null ? zero : sx, xs, y, 0,
				sy == null ? zero : sy, ys, out, 0, dims) ? out : null;
	}

	/**
	 * {@code linalg::%la-adam-step}'s fused update IN PLACE on the device, over four
	 * double-float arrays of {@code n} elements; {@code false} when the device declined
	 * (nothing written), which it does unless one of the four is resident.
	 * @param x the parameter
	 * @param g the gradient
	 * @param m the first moment
	 * @param v the second moment
	 * @param n the element count
	 * @param rule the eleven-number rule
	 * @return {@code true} when the update ran
	 */
	static boolean adamStep(double[] x, double[] g, double[] m, double[] v, int n, double[] rule) {
		return Gpu.adamStep(x, 0, g, 0, m, 0, v, 0, n, rule);
	}

	/**
	 * The single-float sibling of
	 * {@link #adamStep(double[], double[], double[], double[], int, double[])}.
	 */
	static boolean adamStep(float[] x, float[] g, float[] m, float[] v, int n, double[] rule) {
		return Gpu.adamStep(x, 0, g, 0, m, 0, v, 0, n, rule);
	}

	/**
	 * The strided copy {@code out[oOut + so.i] = a[oa + sa.i]} over {@code dims} into a
	 * caller-allocated destination, or {@code false} when the device declined it (it does
	 * unless {@code a}, or {@code out} already, is resident). The interpreter's spans are
	 * whole arrays from 0: {@code na} and {@code nOut} elements, taken from the records'
	 * dims and never from the arrays' lengths, since either may be a stub.
	 */
	static boolean copy(double[] a, int oa, int[] sa, int na, double[] out, int oOut, int[] so, int nOut, int[] dims) {
		return Gpu.copy(a, oa, sa, new int[] { 0, na }, out, oOut, so, new int[] { 0, nOut }, dims);
	}

	/**
	 * The single-float sibling of
	 * {@link #copy(double[], int, int[], int, double[], int, int[], int, int[])}.
	 */
	static boolean copy(float[] a, int oa, int[] sa, int na, float[] out, int oOut, int[] so, int nOut, int[] dims) {
		return Gpu.copy(a, oa, sa, new int[] { 0, na }, out, oOut, so, new int[] { 0, nOut }, dims);
	}

	/**
	 * {@code %la-scale}'s in-place multiply of a resident array of {@code n} elements by
	 * a double scalar.
	 */
	static boolean scaleInPlace(double[] a, int n, double s) {
		return Gpu.scale(Gpu.BIN_MUL, a, 0, s, false, a, 0, n);
	}

	static boolean scaleInPlace(float[] a, int n, double s) {
		return Gpu.scale(Gpu.BIN_MUL, a, 0, s, false, a, 0, n);
	}

	// --- the index tier and the clip norm
	// -----------------------------------

	/**
	 * {@code linalg:take-rows} on the device: the {@code slab}-sized axis-0 slabs of
	 * {@code a} named by {@code rows}, as a fresh array, or {@code null} when the device
	 * declined (it does unless {@code a} is resident).
	 */
	static double @Nullable [] takeRows(double[] a, int lenA, int[] rows, int slab) {
		double[] out = result(rows.length * slab);
		return Gpu.takeRows(a, 0, lenA, out, 0, rows, slab) ? out : null;
	}

	/** The single-float sibling of {@link #takeRows(double[], int, int[], int)}. */
	static float @Nullable [] takeRows(float[] a, int lenA, int[] rows, int slab) {
		float[] out = resultF(rows.length * slab);
		return Gpu.takeRows(a, 0, lenA, out, 0, rows, slab) ? out : null;
	}

	/**
	 * {@code linalg:gather} on the device: {@code out[i] = a[i, cols[i]]} over a matrix
	 * of {@code cols.length} rows, or {@code null} on a decline.
	 */
	static double @Nullable [] pick(double[] a, int[] columns, int cols) {
		double[] out = result(columns.length);
		return Gpu.pick(a, 0, out, 0, columns, cols) ? out : null;
	}

	/** The single-float sibling of {@link #pick(double[], int[], int)}. */
	static float @Nullable [] pick(float[] a, int[] columns, int cols) {
		float[] out = resultF(columns.length);
		return Gpu.pick(a, 0, out, 0, columns, cols) ? out : null;
	}

	/**
	 * {@code linalg::%la-scatter-rows} on the device, IN PLACE: {@code z} is written on
	 * the device and stays there, so the caller must NOT report it written.
	 */
	static boolean scatterRows(double[] z, int rowsZ, double[] g, int[] rows, int slab) {
		return Gpu.scatterRows(z, 0, g, 0, rows, rowsZ, slab);
	}

	/**
	 * The single-float sibling of
	 * {@link #scatterRows(double[], int, double[], int[], int)}.
	 */
	static boolean scatterRows(float[] z, int rowsZ, float[] g, int[] rows, int slab) {
		return Gpu.scatterRows(z, 0, g, 0, rows, rowsZ, slab);
	}

	/**
	 * {@code linalg::%la-sum-squares} on the device: {@code acc} plus the sum of the
	 * squares, folded in BLOCKS rather than in the defun's single left fold -- the one
	 * result of this flag that is not the caller's own arithmetic in the caller's own
	 * order ({@code .kb/gpu.md}). {@code null} on a decline.
	 */
	static @Nullable Double sumSquares(double[] a, int n, double acc) {
		return Gpu.sumSquares(a, 0, n, acc);
	}

	// --- the fused tier (.todo/499) --------------------------------------------------

	/** {@code linalg::%la-gelu} on the device: one pass, or {@code null}. */
	static double @Nullable [] gelu(double[] a, int n) {
		double[] out = result(n);
		return Gpu.gelu(a, 0, out, 0, n) ? out : null;
	}

	static float @Nullable [] gelu(float[] a, int n) {
		float[] out = resultF(n);
		return Gpu.gelu(a, 0, out, 0, n) ? out : null;
	}

	/**
	 * {@code linalg::%la-gelu-grad} on the device: the gradient the input holds after the
	 * node, folded onto {@code old} (or nothing), or {@code null}.
	 */
	static double @Nullable [] geluGrad(double[] g, double[] x, double @Nullable [] old, int n) {
		double[] out = result(n);
		return Gpu.geluGrad(g, 0, x, 0, old, 0, out, 0, n) ? out : null;
	}

	static float @Nullable [] geluGrad(float[] g, float[] x, float @Nullable [] old, int n) {
		float[] out = resultF(n);
		return Gpu.geluGrad(g, 0, x, 0, old, 0, out, 0, n) ? out : null;
	}

	/**
	 * {@code linalg:softmax} over the last axis of {@code rows x len}, or {@code null}.
	 */
	static double @Nullable [] softmax(double[] a, int rows, int len) {
		double[] out = result(rows * len);
		return Gpu.softmax(a, 0, out, 0, rows, len) ? out : null;
	}

	static float @Nullable [] softmax(float[] a, int rows, int len) {
		float[] out = resultF(rows * len);
		return Gpu.softmax(a, 0, out, 0, rows, len) ? out : null;
	}

	/** {@code linalg::%la-softmax-grad} over the last axis, or {@code null}. */
	static double @Nullable [] softmaxGrad(double[] g, double[] s, int rows, int len) {
		double[] out = result(rows * len);
		return Gpu.softmaxGrad(g, 0, s, 0, out, 0, rows, len) ? out : null;
	}

	static float @Nullable [] softmaxGrad(float[] g, float[] s, int rows, int len) {
		float[] out = resultF(rows * len);
		return Gpu.softmaxGrad(g, 0, s, 0, out, 0, rows, len) ? out : null;
	}

	/** {@code linalg:log-softmax} over the last axis of {@code rows x len}, or null. */
	static double @Nullable [] logSoftmax(double[] a, int rows, int len) {
		double[] out = result(rows * len);
		return Gpu.logSoftmax(a, 0, out, 0, rows, len) ? out : null;
	}

	static float @Nullable [] logSoftmax(float[] a, int rows, int len) {
		float[] out = resultF(rows * len);
		return Gpu.logSoftmax(a, 0, out, 0, rows, len) ? out : null;
	}

	/** {@code linalg::%la-log-softmax-grad} over the last axis, or {@code null}. */
	static double @Nullable [] logSoftmaxGrad(double[] g, double[] o, int rows, int len) {
		double[] out = result(rows * len);
		return Gpu.logSoftmaxGrad(g, 0, o, 0, out, 0, rows, len) ? out : null;
	}

	static float @Nullable [] logSoftmaxGrad(float[] g, float[] o, int rows, int len) {
		float[] out = resultF(rows * len);
		return Gpu.logSoftmaxGrad(g, 0, o, 0, out, 0, rows, len) ? out : null;
	}

	/** {@code linalg::%la-layer-norm} over the last axis, or {@code null}. */
	static double @Nullable [] layerNorm(double[] x, int rows, int len, double eps) {
		double[] out = result(rows * len);
		return Gpu.layerNorm(x, 0, out, 0, rows, len, eps) ? out : null;
	}

	static float @Nullable [] layerNorm(float[] x, int rows, int len, double eps) {
		float[] out = resultF(rows * len);
		return Gpu.layerNorm(x, 0, out, 0, rows, len, eps) ? out : null;
	}

	/** {@code linalg::%la-layer-norm-grad} over the last axis, or {@code null}. */
	static double @Nullable [] layerNormGrad(double[] g, double[] x, double @Nullable [] old, int rows, int len,
			double eps) {
		double[] out = result(rows * len);
		return Gpu.layerNormGrad(g, 0, x, 0, old, 0, out, 0, rows, len, eps) ? out : null;
	}

	static float @Nullable [] layerNormGrad(float[] g, float[] x, float @Nullable [] old, int rows, int len,
			double eps) {
		float[] out = resultF(rows * len);
		return Gpu.layerNormGrad(g, 0, x, 0, old, 0, out, 0, rows, len, eps) ? out : null;
	}

	/**
	 * {@code linalg::%la-dropout-mask}'s fill on the device: the mask into {@code out},
	 * answering the generator's end state as {@link #rngFill} does, or {@code null}.
	 */
	static double @Nullable [] dropoutMask(double[] out, int n, double p, double span, int s1, int s2, int s3) {
		return Gpu.dropoutMask(out, 0, n, p, span, s1, s2, s3) ? endState(n, 0, s1, s2, s3) : null;
	}

	static double @Nullable [] dropoutMask(float[] out, int n, double p, double span, int s1, int s2, int s3) {
		return Gpu.dropoutMask(out, 0, n, p, span, s1, s2, s3) ? endState(n, 0, s1, s2, s3) : null;
	}

	/** The single-float sibling of {@link #sumSquares(double[], int, double)}. */
	static @Nullable Double sumSquares(float[] a, int n, double acc) {
		return Gpu.sumSquares(a, 0, n, acc);
	}

}
