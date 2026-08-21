package am.ik.rontolisp.codegen.jvm;

import am.ik.gpu.Gpu;
import org.jspecify.annotations.Nullable;

/**
 * The GPU bridge injected into a compiled {@code .class} when the {@code --gpu} flag is
 * passed: {@code linalg:dot} over two packed rank-2 operands -- and through it
 * {@code linalg:matmul} at rank 2 and {@code linalg:solve} -- is lowered to a call on
 * {@link #gpuDot}, and {@code linalg::%la-matmul-nd}, the STACKED product behind
 * {@code linalg:matmul} at rank &gt;= 3, onto {@link #gpuMatmulNd}. The ELEMENT-WISE tier
 * is the twelve {@link #gpuExp} siblings, one per {@code linalg:} unary ufunc whose
 * scalar cost is a libm call. All of them offer their work to an NVIDIA device and
 * decline everything else.
 *
 * <p>
 * The compiled sibling of {@code eval/LinalgGpuKernels}, and unlike
 * {@link JvmBlasTemplate} NOT a copy of it: the binding itself is {@code am.ik.gpu},
 * whose class files travel in the same blob as this one ({@link JvmGpuRuntimeBuilder}),
 * renamed into the emitted program's own package. So the compiled backend runs the very
 * bytes the interpreter runs -- one probe, one allocator discipline, one status table,
 * one precision contract -- and there is nothing here to keep in sync. What this class
 * holds is only the two things a call site adds: the compiled packed-array
 * representation, and the {@code null} sentinel.
 *
 * <p>
 * A packed float array here is that representation: a bare {@code double[]} or
 * {@code float[]} carrying the dimension header {@code [rank, dim_0, ..., dim_{rank-1}]}
 * ahead of the elements, so a rank-2 operand's elements start at index 3. Nothing is
 * copied to reach them -- {@code am.ik.gpu} takes an element OFFSET on every operand, the
 * result included, for exactly this reason.
 *
 * <p>
 * {@link #gpuDot} is PARTIAL, like every kernel over this seam: it returns {@code null}
 * for a product it does not take -- no device on the machine, a general (boxed) array,
 * mixed widths, a vector on either side, a shape mismatch, a product below the size
 * threshold or too big for device memory -- and the emitted call site then runs whatever
 * is below it: the CBLAS bridge when {@code --blas} emitted one, the lane kernel when
 * {@code --simd} did, and the scalar {@code linalg.lisp} defun otherwise.
 *
 * <p>
 * Design constraints (as for {@link JavaBridgeTemplate}): no nested classes or records,
 * and no reference to any class that is not either the JDK's or in the blob.
 */
final class JvmGpuTemplate {

	private JvmGpuTemplate() {
	}

	/**
	 * Hands the embedded PTX text to the library, which has no classpath resource of its
	 * own once its classes are renamed into this program's package. Emitted by
	 * {@link JvmGpuRuntimeBuilder} into {@code _gpuInit}, before anything can probe.
	 * @param ptx the kernel text
	 */
	static void gpuKernels(String ptx) {
		Gpu.useKernels(ptx);
	}

	/**
	 * The Apple sibling of {@link #gpuKernels(String)}: the Metal Shading Language
	 * SOURCE, which the OS compiles at run time. Both texts travel in every {@code --gpu}
	 * class, whichever machine emitted it, because the class is standalone and the
	 * machine that runs it is not the machine that wrote it.
	 * @param msl the MSL text
	 */
	static void gpuMetalKernels(String msl) {
		Gpu.useMetalKernels(msl);
	}

	/**
	 * {@code (linalg:dot a b)} over two packed rank-2 operands of the same width: the
	 * matrix-by-matrix product, and nothing else. The matrix-by-vector shapes
	 * {@code --blas} takes are memory-bound, so a round trip cannot win them and they are
	 * not offered here at all.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed product, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuDot(@Nullable Object a, @Nullable Object b) {
		if (!(a instanceof double[]) && !(a instanceof float[])) {
			return null;
		}
		boolean single = a instanceof float[];
		if (single != (b instanceof float[]) || (!single && !(b instanceof double[]))) {
			return null;
		}
		if (rank(a) != 2 || rank(b) != 2) {
			return null;
		}
		int n = dim(a, 0);
		int m = dim(a, 1);
		int p = dim(b, 1);
		if (m != dim(b, 0) || !Gpu.worth(n, m, p)) {
			return null;
		}
		// Asked before the result is allocated, and cached from then on: on a machine
		// with no device this is what keeps a big product from allocating an n x p array
		// it is about to throw away on every call.
		if (!Gpu.available()) {
			return null;
		}
		if (single) {
			float[] c = newMatF(n, p);
			return Gpu.multiply(floats(a), 3, floats(b), 3, c, 3, n, m, p) ? c : null;
		}
		double[] c = newMat(n, p);
		return Gpu.multiply(doubles(a), 3, doubles(b), 3, c, 3, n, m, p) ? c : null;
	}

	/**
	 * {@code (linalg::%la-matmul-nd a b)} over two packed operands of rank &gt;= 2 and
	 * the same width: the STACKED matrix product ({@code torch.bmm}), where the last two
	 * axes are the matrix and every leading axis broadcasts. One round trip and ONE
	 * launch for the whole stack -- the device carries the batch on {@code blockIdx.z} --
	 * with each operand's per-batch ELEMENT stride handed to it, so a broadcast leading
	 * axis is a 0 stride and needs no special case.
	 *
	 * <p>
	 * Declined, exactly as in the interpreter ({@code eval/LinalgGpu}): a general boxed
	 * operand, mixed widths, a rank-1 operand on either side, a non-broadcastable batch
	 * shape, a mismatched inner dimension, any empty extent, a stack below the size
	 * threshold or too big for the grid, and a batch whose offsets are not AFFINE in the
	 * batch index (see {@link #batchStride}).
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed stacked product, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuMatmulNd(@Nullable Object a, @Nullable Object b) {
		if (!(a instanceof double[]) && !(a instanceof float[])) {
			return null;
		}
		boolean single = a instanceof float[];
		if (single != (b instanceof float[]) || (!single && !(b instanceof double[]))) {
			return null;
		}
		int ra = rank(a);
		int rb = rank(b);
		if (ra < 2 || rb < 2) {
			return null;
		}
		int[] da = dims(a, ra);
		int[] db = dims(b, rb);
		int n = da[ra - 2];
		int m = da[ra - 1];
		int p = db[rb - 1];
		if (m != db[rb - 2] || n < 1 || m < 1 || p < 1) {
			return null;
		}
		int[] ba = java.util.Arrays.copyOf(da, ra - 2);
		int[] bb = java.util.Arrays.copyOf(db, rb - 2);
		int[] bd = bcastShape(ba, bb);
		if (bd == null) {
			return null;
		}
		long batches = 1;
		for (int d : bd) {
			batches *= d;
		}
		int rank = bd.length + 2;
		long total = batches * n * p;
		if (batches < 1 || total + 1 + rank > Integer.MAX_VALUE - 8 || !Gpu.worth(batches, n, m, p)) {
			return null;
		}
		long sa = batchStride(ba, bd, (long) n * m);
		long sb = batchStride(bb, bd, (long) m * p);
		if (sa < 0 || sb < 0 || sa > Integer.MAX_VALUE || sb > Integer.MAX_VALUE) {
			return null;
		}
		// Asked before the result is allocated, and cached from then on: on a machine
		// with no device this is what keeps a big stack from allocating a result it is
		// about to throw away on every call.
		if (!Gpu.available()) {
			return null;
		}
		int batch = (int) batches;
		int off = 1 + rank;
		if (single) {
			float[] c = new float[off + (int) total];
			c[0] = rank;
			for (int i = 0; i < bd.length; i++) {
				c[1 + i] = bd[i];
			}
			c[rank - 1] = n;
			c[rank] = p;
			return Gpu.multiply(floats(a), 1 + ra, (int) sa, floats(b), 1 + rb, (int) sb, c, off, batch, n, m, p) ? c
					: null;
		}
		double[] c = new double[off + (int) total];
		c[0] = rank;
		for (int i = 0; i < bd.length; i++) {
			c[1 + i] = bd[i];
		}
		c[rank - 1] = n;
		c[rank] = p;
		return Gpu.multiply(doubles(a), 1 + ra, (int) sa, doubles(b), 1 + rb, (int) sb, c, off, batch, n, m, p) ? c
				: null;
	}

	/**
	 * {@code (linalg:exp a)} and its eleven siblings over a packed operand of either
	 * width and any rank -- the ELEMENT-WISE tier. One method per member, because the
	 * emitted call site names its kernel by an {@code ops} key and loads the member's own
	 * arguments; they all reach the same {@link #map}.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuExp(@Nullable Object a) {
		return map(Gpu.MAP_EXP, a);
	}

	/**
	 * {@code (linalg:log a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuLog(@Nullable Object a) {
		return map(Gpu.MAP_LOG, a);
	}

	/**
	 * {@code (linalg:tanh a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuTanh(@Nullable Object a) {
		return map(Gpu.MAP_TANH, a);
	}

	/**
	 * {@code (linalg:sin a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuSin(@Nullable Object a) {
		return map(Gpu.MAP_SIN, a);
	}

	/**
	 * {@code (linalg:cos a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuCos(@Nullable Object a) {
		return map(Gpu.MAP_COS, a);
	}

	/**
	 * {@code (linalg:tan a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuTan(@Nullable Object a) {
		return map(Gpu.MAP_TAN, a);
	}

	/**
	 * {@code (linalg:asin a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAsin(@Nullable Object a) {
		return map(Gpu.MAP_ASIN, a);
	}

	/**
	 * {@code (linalg:acos a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAcos(@Nullable Object a) {
		return map(Gpu.MAP_ACOS, a);
	}

	/**
	 * {@code (linalg:atan a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAtan(@Nullable Object a) {
		return map(Gpu.MAP_ATAN, a);
	}

	/**
	 * {@code (linalg:sinh a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuSinh(@Nullable Object a) {
		return map(Gpu.MAP_SINH, a);
	}

	/**
	 * {@code (linalg:cosh a)} on the device.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuCosh(@Nullable Object a) {
		return map(Gpu.MAP_COSH, a);
	}

	/**
	 * {@code (linalg:erf a)} on the device -- the exact {@code torch:gelu}'s inner
	 * member, and the one the CPU is slowest at by an order of magnitude.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuErf(@Nullable Object a) {
		return map(Gpu.MAP_ERF, a);
	}

	/**
	 * {@code (linalg:add a b)} at a BROADCAST shape on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAdd(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_ADD, a, b);
	}

	/**
	 * {@code (linalg:sub a b)} at a BROADCAST shape on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuSub(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_SUB, a, b);
	}

	/**
	 * {@code (linalg:mul a b)} at a BROADCAST shape on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuMul(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_MUL, a, b);
	}

	/**
	 * {@code (linalg:div a b)} at a BROADCAST shape on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuDiv(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_DIV, a, b);
	}

	/**
	 * {@code (linalg:maximum a b)} at a BROADCAST shape: the STRICT select, so the second
	 * operand wins a tie and a NaN, exactly as the defun's does.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuMaximum(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_MAX, a, b);
	}

	/**
	 * {@code (linalg:minimum a b)} at a BROADCAST shape.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuMinimum(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_MIN, a, b);
	}

	/**
	 * {@code (linalg:sum a :axis ax :keepdims k)} on the device.
	 * @param a the operand
	 * @param axis the axis argument, or {@code null} for a missing one
	 * @param keepdims the keepdims argument, or {@code null} for a missing one
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuSumAxis(@Nullable Object a, @Nullable Object axis, @Nullable Object keepdims) {
		return foldAxis(Gpu.FOLD_SUM, a, axis, keepdims);
	}

	/**
	 * {@code (linalg:amax a :axis ax :keepdims k)} on the device.
	 * @param a the operand
	 * @param axis the axis argument, or {@code null} for a missing one
	 * @param keepdims the keepdims argument, or {@code null} for a missing one
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAmaxAxis(@Nullable Object a, @Nullable Object axis, @Nullable Object keepdims) {
		return foldAxis(Gpu.FOLD_AMAX, a, axis, keepdims);
	}

	/**
	 * {@code (linalg:amin a :axis ax :keepdims k)} on the device.
	 * @param a the operand
	 * @param axis the axis argument, or {@code null} for a missing one
	 * @param keepdims the keepdims argument, or {@code null} for a missing one
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAminAxis(@Nullable Object a, @Nullable Object axis, @Nullable Object keepdims) {
		return foldAxis(Gpu.FOLD_AMIN, a, axis, keepdims);
	}

	/**
	 * {@code (linalg:transpose a axes)}, the rank-n axis permutation, on the device: one
	 * source stride per output axis, a pure permuted copy.
	 * @param a the operand
	 * @param axes the permutation, as a compiled proper list
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuTransposeAxes(@Nullable Object a, @Nullable Object axes) {
		if (!(a instanceof double[]) && !(a instanceof float[])) {
			return null;
		}
		int rank = rank(a);
		if (rank < 1) {
			return null;
		}
		int[] d = dims(a, rank);
		// The size test first, for the reason bcast's is first.
		if (!Gpu.worthStrided(count(d))) {
			return null;
		}
		int[] perm = permutation(axes, rank);
		if (perm == null) {
			return null;
		}
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
			od[k] = d[perm[k]];
			sa[k] = source[perm[k]];
			total *= od[k];
		}
		int off = 1 + rank;
		if (!Gpu.worthStrided(total) || !Gpu.available()) {
			return null;
		}
		if (a instanceof float[] x) {
			float[] c = newLike(od, off + (int) total);
			return Gpu.gather(x, off, sa, c, off, od) ? c : null;
		}
		double[] x = doubles(a);
		double[] c = newLikeD(od, off + (int) total);
		return Gpu.gather(x, off, sa, c, off, od) ? c : null;
	}

	/**
	 * One BROADCAST binary element-wise op. Equal shapes are declined ON PURPOSE: there
	 * the {@code --simd} rung below runs a lane loop that a round trip cannot beat, which
	 * is the measurement phase 4b made and this does not reverse. A scalar operand, a
	 * boxed array and a mixed-width pair decline for the reasons the lane kernel declines
	 * them.
	 */
	private static @Nullable Object bcast(int op, @Nullable Object a, @Nullable Object b) {
		if (!(a instanceof double[]) && !(a instanceof float[])) {
			return null;
		}
		boolean single = a instanceof float[];
		if (single != (b instanceof float[]) || (!single && !(b instanceof double[]))) {
			return null;
		}
		int ra = rank(a);
		int rb = rank(b);
		if (ra < 1 || rb < 1) {
			return null;
		}
		int[] da = dims(a, ra);
		int[] db = dims(b, rb);
		if (java.util.Arrays.equals(da, db)) {
			return null;
		}
		// The size test FIRST, over a bound that costs nothing: a broadcast output is at
		// least as big as either operand. Every linalg:add call site in the program runs
		// this method, so a declined call must not allocate a shape it will throw away.
		if (!Gpu.worthStrided(Math.max(count(da), count(db)))) {
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
		int rank = od.length;
		if (total + 1 + rank > Integer.MAX_VALUE - 8 || !Gpu.worthStrided(total) || !Gpu.available()) {
			return null;
		}
		int[] sa = bcastStrides(da, od);
		int[] sb = bcastStrides(db, od);
		int off = 1 + rank;
		if (single) {
			float[] c = newLike(od, off + (int) total);
			return Gpu.bcast(op, floats(a), 1 + ra, sa, floats(b), 1 + rb, sb, c, off, od) ? c : null;
		}
		double[] c = newLikeD(od, off + (int) total);
		return Gpu.bcast(op, doubles(a), 1 + ra, sa, doubles(b), 1 + rb, sb, c, off, od) ? c : null;
	}

	/**
	 * One AXIS fold. Declines the whole-array form, a nil / non-integer / out-of-range
	 * axis, an empty axis, a boxed operand, a fold below the size threshold and one with
	 * too few OUTPUT cells to be worth a grid -- which is what a vector reduced without
	 * {@code :keepdims} is.
	 */
	private static @Nullable Object foldAxis(int op, @Nullable Object a, @Nullable Object axisv,
			@Nullable Object keepdims) {
		if (!(a instanceof double[]) && !(a instanceof float[])) {
			return null;
		}
		int rank = rank(a);
		if (rank < 1 || !(axisv instanceof Long axl)) {
			return null;
		}
		long axis = axl < 0 ? axl + rank : axl;
		if (axis < 0 || axis >= rank) {
			return null;
		}
		int ax = (int) axis;
		int[] d = dims(a, rank);
		int len = d[ax];
		if (len == 0) {
			return null;
		}
		int outer = 1;
		int inner = 1;
		for (int i = 0; i < ax; i++) {
			outer *= d[i];
		}
		for (int i = ax + 1; i < d.length; i++) {
			inner *= d[i];
		}
		if (!Gpu.worthFold((long) outer * inner * len) || (long) outer * inner < 2) {
			return null;
		}
		int[] od = axisShape(d, ax, keepdims != null);
		if (od.length == 0 || !Gpu.available()) {
			return null;
		}
		int off = 1 + od.length;
		int cells = outer * inner;
		if (a instanceof float[] x) {
			float[] c = newLike(od, off + cells);
			return Gpu.fold(op, x, 1 + rank, c, off, outer, len, inner) ? c : null;
		}
		double[] x = doubles(a);
		double[] c = newLikeD(od, off + cells);
		return Gpu.fold(op, x, 1 + rank, c, off, outer, len, inner) ? c : null;
	}

	/**
	 * Row-major strides of the dims-{@code d} operand aligned to the broadcast shape
	 * {@code od}, 0 on every stretched axis -- {@code %la-bcast-strides} verbatim.
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

	/** The element count of a shape. */
	private static long count(int[] d) {
		long n = 1;
		for (int x : d) {
			n *= x;
		}
		return n;
	}

	/** The dims with the axis dropped -- or kept as extent 1 ({@code %la-axis-shape}). */
	private static int[] axisShape(int[] d, int ax, boolean keep) {
		int[] od = new int[keep ? d.length : d.length - 1];
		int k = 0;
		for (int i = 0; i < d.length; i++) {
			if (i != ax) {
				od[k++] = d[i];
			}
			else if (keep) {
				od[k++] = 1;
			}
		}
		return od;
	}

	/** A compiled proper list of integers forming a permutation of {@code 0..rank-1}. */
	private static int @Nullable [] permutation(@Nullable Object axes, int rank) {
		int[] out = new int[rank];
		boolean[] seen = new boolean[rank];
		int count = 0;
		Object cursor = axes;
		while (cursor instanceof Object[] cell && cell.length == 2) {
			if (count >= rank || !(cell[0] instanceof Long l)) {
				return null;
			}
			long v = l;
			if (v < 0 || v >= rank || seen[(int) v]) {
				return null;
			}
			seen[(int) v] = true;
			out[count++] = (int) v;
			cursor = cell[1];
		}
		return cursor == null && count == rank ? out : null;
	}

	/** A fresh single-float result of the given shape, header written, elements zero. */
	private static float[] newLike(int[] od, int length) {
		float[] c = new float[length];
		c[0] = od.length;
		for (int k = 0; k < od.length; k++) {
			c[1 + k] = od[k];
		}
		return c;
	}

	/** The double-float sibling of {@link #newLike}. */
	private static double[] newLikeD(int[] od, int length) {
		double[] c = new double[length];
		c[0] = od.length;
		for (int k = 0; k < od.length; k++) {
			c[1 + k] = od[k];
		}
		return c;
	}

	/**
	 * One element-wise unary ufunc: {@code out[i] = op(a[i])} over the whole packed
	 * operand, one round trip, the result carrying the operand's own header. Declines a
	 * general (boxed) array, a plain number, and an array below the element threshold --
	 * and, as every kernel over this seam does, everything else it does not handle.
	 */
	private static @Nullable Object map(int op, @Nullable Object a) {
		if (!(a instanceof double[]) && !(a instanceof float[])) {
			return null;
		}
		boolean single = a instanceof float[];
		int rank = rank(a);
		if (rank < 1) {
			return null;
		}
		int off = 1 + rank;
		long count = 1;
		for (int i = 0; i < rank; i++) {
			count *= dim(a, i);
		}
		int length = single ? floats(a).length : doubles(a).length;
		if (count < 1 || count != length - off || !Gpu.worthMap(count)) {
			return null;
		}
		// Asked before the result is allocated, and cached from then on: on a machine
		// with no device this is what keeps a big map from allocating a result it is
		// about to throw away on every call.
		if (!Gpu.available()) {
			return null;
		}
		int n = (int) count;
		if (single) {
			float[] source = floats(a);
			float[] c = new float[length];
			System.arraycopy(source, 0, c, 0, off);
			return Gpu.map(op, source, off, c, off, n) ? c : null;
		}
		double[] source = doubles(a);
		double[] c = new double[length];
		System.arraycopy(source, 0, c, 0, off);
		return Gpu.map(op, source, off, c, off, n) ? c : null;
	}

	/**
	 * The numpy broadcast shape of two batch-dims arrays: trailing axes align, a pair
	 * agrees when equal or either is 1, the output extent is the larger. {@code null} on
	 * any other disagreement.
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
	 * odometer the CPU kernels walk, or {@code -1} when no single stride does. The device
	 * adds {@code blockIdx.z * stride}, which agrees with the odometer exactly when every
	 * axis's stride is that stride times the axis's weight in the counter -- true for a
	 * contiguous batch of any rank and for a wholly broadcast operand (stride 0, which is
	 * every rank-2 right operand under a rank-3 left one), false for a broadcast axis
	 * sitting UNDER a non-broadcast one.
	 */
	private static long batchStride(int[] d, int[] od, long base) {
		long[] s = new long[od.length];
		long acc = base;
		for (int k = od.length - 1, i = d.length - 1; k >= 0; k--, i--) {
			int extent = i >= 0 ? d[i] : 1;
			s[k] = extent == 1 ? 0 : acc;
			acc *= extent;
		}
		long weight = 1;
		long stride = -1;
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

	// --- the compiled packed float-array representation --------------------------------

	private static int rank(@Nullable Object o) {
		return o instanceof float[] f ? (int) f[0] : (int) ((double[]) java.util.Objects.requireNonNull(o))[0];
	}

	private static int[] dims(@Nullable Object o, int rank) {
		int[] d = new int[rank];
		for (int i = 0; i < rank; i++) {
			d[i] = dim(o, i);
		}
		return d;
	}

	private static int dim(@Nullable Object o, int i) {
		return o instanceof float[] f ? (int) f[1 + i] : (int) ((double[]) java.util.Objects.requireNonNull(o))[1 + i];
	}

	private static double[] doubles(@Nullable Object o) {
		return (double[]) java.util.Objects.requireNonNull(o);
	}

	private static float[] floats(@Nullable Object o) {
		return (float[]) java.util.Objects.requireNonNull(o);
	}

	private static double[] newMat(int rows, int cols) {
		double[] m = new double[3 + rows * cols];
		m[0] = 2.0;
		m[1] = rows;
		m[2] = cols;
		return m;
	}

	private static float[] newMatF(int rows, int cols) {
		float[] m = new float[3 + rows * cols];
		m[0] = 2.0f;
		m[1] = rows;
		m[2] = cols;
		return m;
	}

}
