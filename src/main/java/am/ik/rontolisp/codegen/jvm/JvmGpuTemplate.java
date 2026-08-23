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
		// Lazy results, where the device says they pay (CUDA; not Metal, measured): a
		// member's result stays on the device until the host first reads it. Safe here
		// because the compiled program materializes before every host read of
		// packed-array storage -- _fvAref*, _fvToGeneral*, every host rung of every
		// accelerated call site, the typed loops, the bulk write-sequence, Java interop
		// -- through _gpuMaterialize (.kb/gpu.md, "A result comes home on first host
		// touch"). The MSL text arrives AFTER this (gpuMetalKernels), so the wish must
		// not run the probe, and it does not: it is applied when the probe runs.
		Gpu.lazyResultsIfWorthwhile();
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
	 * A packed float array was written in place, so the device copy the library may be
	 * holding of it is stale. The compiled half of the residency invalidation: the
	 * emitted {@code _gpuWritten} guard calls this from {@code _fvAset1/2/N}, from the
	 * in-place {@code --simd} kernels' call sites and from every {@code vec:}
	 * {@code -into} call site, but only once the bridge is defined -- before that nothing
	 * can be resident ({@code .kb/gpu.md}).
	 * @param array the {@code double[]} or {@code float[]} that was written; anything
	 * else is ignored
	 */
	static void gpuWritten(@Nullable Object array) {
		if (array instanceof double[] || array instanceof float[]) {
			Gpu.written(array);
		}
	}

	/**
	 * A packed float array is about to be READ on the host: if the device holds its only
	 * bytes (a result left there lazily), they come home first. The compiled half of the
	 * reader enumeration: the emitted {@code _gpuMaterialize} guard calls this from every
	 * host read of packed-array storage, once the bridge is defined -- before that
	 * nothing can be resident. Anything that is not a packed array is ignored, so a call
	 * site may report every argument without looking.
	 * @param array the value about to be read
	 */
	static void gpuMaterialize(@Nullable Object array) {
		if (array instanceof double[] || array instanceof float[]) {
			Gpu.materialize(array);
		}
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
		if (m != dim(b, 0) || !(Gpu.worth(n, m, p) || resident(a) || resident(b))) {
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
		if (batches < 1 || total + 1 + rank > Integer.MAX_VALUE - 8
				|| !(Gpu.worth(batches, n, m, p) || resident(a) || resident(b))) {
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
	 * {@code (vec:matvec w x)} over a packed rank-2 matrix and a packed rank-1 vector of
	 * the same width and matching extent -- the one device member outside
	 * {@code linalg:}, the GEMV a decode loop is made of. The library accepts it only
	 * over a matrix that is RESIDENT, or that it has been offered before and not written
	 * since (the first sight of any matrix declines; {@code .kb/gpu.md}), so the emitted
	 * chain falls through to the lane kernel or the defun exactly as it does for a shape
	 * the device turns down.
	 * @param w the matrix
	 * @param x the vector
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuMatvec(@Nullable Object w, @Nullable Object x) {
		if (!(w instanceof double[]) && !(w instanceof float[])) {
			return null;
		}
		boolean single = w instanceof float[];
		if (single != (x instanceof float[]) || (!single && !(x instanceof double[]))) {
			return null;
		}
		if (rank(w) != 2 || rank(x) != 1) {
			return null;
		}
		int rows = dim(w, 0);
		int cols = dim(w, 1);
		if (rows < 1 || cols < 1 || dim(x, 0) != cols || !Gpu.worthMatvec(rows, cols)) {
			return null;
		}
		if (!Gpu.available()) {
			return null;
		}
		if (single) {
			float[] y = newVecF(rows);
			return Gpu.matvec(floats(w), 3, floats(x), 2, y, 2, rows, cols) ? y : null;
		}
		double[] y = newVec(rows);
		return Gpu.matvec(doubles(w), 3, doubles(x), 2, y, 2, rows, cols) ? y : null;
	}

	/**
	 * {@code (linalg::%la-rng-fill out st mode lo span)} on the device: fills the packed
	 * destination of either width from the three-word state vector by the closed-form
	 * jump and answers the end state as a fresh {@code (3)} vector -- byte-identical to
	 * the sequential fill, which is what {@code linalg:seed} promises. Declines a boxed
	 * destination, a state vector that is not three packed doubles in the generator's
	 * range, a mode outside 0..2, a non-numeric bound and a fill below the threshold; the
	 * {@code --simd} kernel or the defun then answer the same bytes.
	 * @param out the destination
	 * @param st the state vector
	 * @param modev the element rule
	 * @param lov rule 2's lower bound
	 * @param spanv rule 2's range
	 * @return the end state, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuRngFill(@Nullable Object out, @Nullable Object st, @Nullable Object modev,
			@Nullable Object lov, @Nullable Object spanv) {
		if ((!(out instanceof double[]) && !(out instanceof float[])) || !(st instanceof double[] s) || s.length != 5
				|| s[0] != 1.0 || s[1] != 3.0 || !(modev instanceof Long mv) || mv < 0 || mv > 2) {
			return null;
		}
		Double lo = scalar(lov), span = scalar(spanv);
		if (lo == null || span == null) {
			return null;
		}
		int off = 1 + rank(out);
		int n = (out instanceof float[] f ? f.length : doubles(out).length) - off;
		if (n < 1 || !Gpu.worthRng(n)) {
			return null;
		}
		int[] w = new int[3];
		for (int i = 0; i < 3; i++) {
			int u = (int) s[2 + i];
			if (u != s[2 + i] || u < 0 || u >= 1 << 23) {
				return null;
			}
			w[i] = u;
		}
		if (!Gpu.available()) {
			return null;
		}
		int mode = (int) (long) mv;
		boolean filled = out instanceof float[] f ? Gpu.rngFill(f, off, n, mode, lo, span, w[0], w[1], w[2])
				: Gpu.rngFill(doubles(out), off, n, mode, lo, span, w[0], w[1], w[2]);
		if (!filled) {
			return null;
		}
		int[] end = Gpu.rngAdvance(w[0], w[1], w[2], (long) n * (mode == 1 ? 12 : 1));
		return new double[] { 1.0, 3.0, end[0], end[1], end[2] };
	}

	/** A Lisp number the kernels take as a bound, as a {@code Double}, else null. */
	private static @Nullable Double scalar(@Nullable Object o) {
		if (o instanceof Double d) {
			return d;
		}
		if (o instanceof Long l) {
			return (double) l;
		}
		return null;
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
	 * {@code (linalg:sqrt a)} on the device -- the resident tier: one machine instruction
	 * per element, which a round trip cannot pay for, so it is offered over a RESIDENT
	 * operand only, where there is no trip. Bit-identical to the lane kernel.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuSqrt(@Nullable Object a) {
		return map(Gpu.MAP_SQRT, a);
	}

	/**
	 * {@code (linalg:abs a)} on the device, over a resident operand only.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAbs(@Nullable Object a) {
		return map(Gpu.MAP_ABS, a);
	}

	/**
	 * {@code (linalg:negative a)} on the device, over a resident operand only.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuNegative(@Nullable Object a) {
		return map(Gpu.MAP_NEGATIVE, a);
	}

	/**
	 * {@code (linalg:sign a)} on the device, over a resident operand only.
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuSign(@Nullable Object a) {
		return map(Gpu.MAP_SIGN, a);
	}

	/**
	 * {@code (linalg:greater a b)} on the device: the comparison mask ({@code 1.0} where
	 * the relation holds) at the three shapes the binary members take.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuGreater(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_GT, a, b);
	}

	/**
	 * {@code (linalg:greater-equal a b)} on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuGreaterEqual(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_GE, a, b);
	}

	/**
	 * {@code (linalg:less a b)} on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuLess(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_LT, a, b);
	}

	/**
	 * {@code (linalg:less-equal a b)} on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuLessEqual(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_LE, a, b);
	}

	/**
	 * {@code (linalg:equal a b)} on the device.
	 * @param a the left operand
	 * @param b the right operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuEqual(@Nullable Object a, @Nullable Object b) {
		return bcast(Gpu.BIN_EQ, a, b);
	}

	/**
	 * {@code (linalg:where mask x y)} on the device -- the resident tier's three-way
	 * select, {@code torch:masked-fill}'s member: every operand a packed array of either
	 * width or a plain number, broadcast together; the result at {@code x}'s width when
	 * it is an array, else {@code y}'s, else double ({@code laWhere}'s rule and the
	 * defun's). Offered only when some array operand is resident; a select, so
	 * bit-identical. Declines no array at all, a boxed array or a ratio, an incompatible
	 * broadcast, and a mixed-width x / y.
	 * @param m the mask
	 * @param x the where-true operand
	 * @param y the where-false operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuWhere(@Nullable Object m, @Nullable Object x, @Nullable Object y) {
		boolean ma = packed(m), xa = packed(x), ya = packed(y);
		if (!ma && !xa && !ya) {
			return null;
		}
		if (!((ma && resident(m)) || (xa && resident(x)) || (ya && resident(y)))) {
			return null;
		}
		Double ms = ma ? null : scalar(m), xs = xa ? null : scalar(x), ys = ya ? null : scalar(y);
		if ((!ma && ms == null) || (!xa && xs == null) || (!ya && ys == null)) {
			return null;
		}
		boolean single = xa ? x instanceof float[] : (ya && y instanceof float[]);
		if (xa && ya && (x instanceof float[]) != (y instanceof float[])) {
			return null;
		}
		int[] od = null;
		for (Object o : new Object[] { m, x, y }) {
			if (packed(o)) {
				int[] d = dims(o, rank(o));
				od = od == null ? d : bcastShape(od, d);
				if (od == null) {
					return null;
				}
			}
		}
		if (od == null) {
			return null;
		}
		int rank = od.length;
		long total = count(od);
		if (total + 1 + rank > Integer.MAX_VALUE - 8) {
			return null;
		}
		int off = 1 + rank;
		int[] sm = ma ? bcastStrides(dims(m, rank(m)), od) : new int[rank];
		int[] sx = xa ? bcastStrides(dims(x, rank(x)), od) : new int[rank];
		int[] sy = ya ? bcastStrides(dims(y, rank(y)), od) : new int[rank];
		double mv = ms == null ? 0.0 : ms, xv = xs == null ? 0.0 : xs, yv = ys == null ? 0.0 : ys;
		int om = ma ? 1 + rank(m) : 0, ox = xa ? 1 + rank(x) : 0, oy = ya ? 1 + rank(y) : 0;
		if (single) {
			float[] c = newLike(od, off + (int) total);
			return Gpu.where(ma ? m : null, om, sm, mv, xa ? floats(x) : null, ox, sx, xv, ya ? floats(y) : null, oy,
					sy, yv, c, off, od) ? c : null;
		}
		double[] c = newLikeD(od, off + (int) total);
		return Gpu.where(ma ? m : null, om, sm, mv, xa ? doubles(x) : null, ox, sx, xv, ya ? doubles(y) : null, oy, sy,
				yv, c, off, od) ? c : null;
	}

	/**
	 * {@code (linalg::%la-adam-step x g m v rule)} on the device -- the resident tier's
	 * one writing member: the parameter and both moments are updated IN PLACE on the
	 * device from a gradient that is usually the previous member's result, and stay there
	 * as the authoritative copies, so a model's weights never come home between steps.
	 * Offered only when one of the four is resident; declines a boxed or mixed-width
	 * quadruple, a length mismatch and a malformed rule, and the lane kernel or the defun
	 * then runs -- bit-identically. Answers {@code x}, as the kernel does.
	 * @param x the parameter
	 * @param g the gradient
	 * @param m the first moment
	 * @param v the second moment
	 * @param rule the eleven-element rule vector
	 * @return {@code x}, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuAdamStep(@Nullable Object x, @Nullable Object g, @Nullable Object m, @Nullable Object v,
			@Nullable Object rule) {
		if (!(rule instanceof double[] ps) || ps.length != 13 || ps[0] != 1.0 || ps[1] != 11.0) {
			return null;
		}
		double[] r = java.util.Arrays.copyOfRange(ps, 2, 13);
		if (!packed(x) || !packed(g) || !packed(m) || !packed(v)) {
			return null;
		}
		boolean single = x instanceof float[];
		if ((g instanceof float[]) != single || (m instanceof float[]) != single || (v instanceof float[]) != single) {
			return null;
		}
		int ox = 1 + rank(x), og = 1 + rank(g), om = 1 + rank(m), ov = 1 + rank(v);
		int n = length(x) - ox;
		if (n < 1 || length(g) - og != n || length(m) - om != n || length(v) - ov != n) {
			return null;
		}
		if (!(resident(x) || resident(g) || resident(m) || resident(v))) {
			return null;
		}
		boolean ran = single ? Gpu.adamStep(floats(x), ox, floats(g), og, floats(m), om, floats(v), ov, n, r)
				: Gpu.adamStep(doubles(x), ox, doubles(g), og, doubles(m), om, doubles(v), ov, n, r);
		return ran ? x : null;
	}

	/**
	 * {@code (linalg:reshape a shape)} over a resident operand: the same elements under a
	 * new header, one contiguous device copy ({@code laReshape}'s rule, a {@code -1}
	 * shape declined). Over anything else declines, and the lane kernel or the defun
	 * copies on the host.
	 * @param a the operand
	 * @param shape the new shape, a number or a proper list of numbers
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuReshape(@Nullable Object a, @Nullable Object shape) {
		if (!packed(a) || !resident(a)) {
			return null;
		}
		int[] od = shapeOf(shape);
		if (od == null) {
			return null;
		}
		int rank = rank(a);
		int n = length(a) - 1 - rank;
		long total = 1;
		for (int d : od) {
			total *= d;
		}
		if (n < 1 || total != n) {
			return null;
		}
		return copyInto(java.util.Objects.requireNonNull(a), 1 + rank, new int[] { 1 }, new int[] { n },
				new int[] { 1 }, od);
	}

	/**
	 * {@code (linalg:transpose a)}, the plain matrix transpose, over a resident rank-2
	 * operand: one strided copy. A vector or a rank above 2 is the defun's (the vector
	 * itself; an error).
	 * @param a the operand
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuTranspose(@Nullable Object a) {
		if (!packed(a) || rank(a) != 2 || !resident(a)) {
			return null;
		}
		int r = dim(a, 0), c = dim(a, 1);
		int[] od = { c, r };
		return copyInto(java.util.Objects.requireNonNull(a), 3, new int[] { 1, c }, od, new int[] { r, 1 }, od);
	}

	/**
	 * {@code (linalg::%la-gather-strided a od rs base single)} -- the walk behind
	 * {@code linalg:slice} and {@code broadcast-to} -- over a resident operand: one
	 * strided copy, the innermost-first strides reversed into the device's per-axis order
	 * and the base as the walk's origin; a negative stride is allowed. Declines a width
	 * flag that is not the operand's (the CPU widens, the device copies), an empty output
	 * and a walk outside the operand, as {@code laGatherStrided} does.
	 * @param a the operand
	 * @param odv the output shape
	 * @param rsv the innermost-first strides
	 * @param basev the flat index the walk starts at
	 * @param singlev non-null for a single-float result
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuGatherStrided(@Nullable Object a, @Nullable Object odv, @Nullable Object rsv,
			@Nullable Object basev, @Nullable Object singlev) {
		if (!packed(a) || !(basev instanceof Long bl) || bl < 0 || bl > Integer.MAX_VALUE || !resident(a)) {
			return null;
		}
		if ((singlev != null) != (a instanceof float[])) {
			return null;
		}
		int[] od = shapeOf(odv);
		int[] rs = ints(rsv);
		if (od == null || rs == null || rs.length != od.length) {
			return null;
		}
		int rank = od.length;
		int[] sa = new int[rank];
		long total = 1;
		for (int k = 0; k < rank; k++) {
			sa[k] = rs[rank - 1 - k];
			total *= od[k];
		}
		if (total < 1 || total + 1 + rank > Integer.MAX_VALUE - 8) {
			return null;
		}
		int base = (int) (long) bl;
		return copyInto(java.util.Objects.requireNonNull(a), 1 + rank(a) + base, sa, od, rowMajorStrides(od), od);
	}

	/**
	 * {@code (linalg:concatenate arrays :axis ax)} -- {@code torch:cat} -- over packed
	 * inputs of one width of which at least one is resident: one strided copy per input
	 * into its slab of the output, the resident input first so that the output is
	 * resident for the rest (which are then uploaded into it). The defun's shape rules;
	 * anything it would signal on declines to it.
	 * @param list the inputs, a compiled proper list
	 * @param axis the axis, or {@code null} for 0
	 * @return the packed result, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuConcatenate(@Nullable Object list, @Nullable Object axis) {
		java.util.List<Object> inputs = new java.util.ArrayList<>();
		Object cursor = list;
		while (cursor instanceof Object[] cell && cell.length == 2) {
			if (!packed(cell[0])) {
				return null;
			}
			inputs.add(cell[0]);
			cursor = cell[1];
		}
		if (cursor != null || inputs.isEmpty()) {
			return null;
		}
		Object first = inputs.get(0);
		boolean single = first instanceof float[];
		int rank = rank(first);
		long ax;
		if (axis == null) {
			ax = 0;
		}
		else if (axis instanceof Long l) {
			ax = l < 0 ? l + rank : l;
		}
		else {
			return null;
		}
		if (ax < 0 || ax >= rank) {
			return null;
		}
		int[] d0 = dims(first, rank);
		long total = 0;
		boolean anyResident = false;
		for (Object a : inputs) {
			if ((a instanceof float[]) != single || rank(a) != rank) {
				return null;
			}
			int[] d = dims(a, rank);
			for (int k = 0; k < rank; k++) {
				if (k != ax && d[k] != d0[k]) {
					return null;
				}
			}
			total += d[(int) ax];
			anyResident |= resident(a);
		}
		if (!anyResident || total < 1 || total > Integer.MAX_VALUE) {
			return null;
		}
		int[] od = d0.clone();
		od[(int) ax] = (int) total;
		int[] so = rowMajorStrides(od);
		long n = count(od);
		int off = 1 + rank;
		if (n + off > Integer.MAX_VALUE - 8) {
			return null;
		}
		int lead = 0;
		while (!resident(inputs.get(lead))) {
			lead++;
		}
		int[] offsets = new int[inputs.size()];
		for (int i = 0, cum = 0; i < inputs.size(); i++) {
			offsets[i] = off + cum * so[(int) ax];
			cum += dims(inputs.get(i), rank)[(int) ax];
		}
		float[] cf = single ? newLike(od, off + (int) n) : null;
		double[] cd = single ? null : newLikeD(od, off + (int) n);
		for (int step = 0; step < inputs.size(); step++) {
			int i = step == 0 ? lead : (step <= lead ? step - 1 : step);
			Object a = inputs.get(i);
			int[] d = dims(a, rank);
			int[] spanOut = { off, (int) n };
			boolean ok = single
					? Gpu.copy(floats(a), off, rowMajorStrides(d), new int[] { off, (int) count(d) },
							java.util.Objects.requireNonNull(cf), offsets[i], so, spanOut, d)
					: Gpu.copy(doubles(a), off, rowMajorStrides(d), new int[] { off, (int) count(d) },
							java.util.Objects.requireNonNull(cd), offsets[i], so, spanOut, d);
			if (!ok) {
				return null;
			}
		}
		return single ? cf : cd;
	}

	/**
	 * {@code (linalg::%la-scale g s)} -- gradient clipping's in-place multiply -- over a
	 * resident array: the kernel reads and writes the one resident buffer, which stays
	 * the authoritative copy, so the Adam update that follows finds the gradient there.
	 * Answers {@code g}, as the lane kernel does.
	 * @param g the array, scaled in place
	 * @param sv the scalar
	 * @return {@code g}, or {@code null} when the device declined it
	 */
	static @Nullable Object gpuScale(@Nullable Object g, @Nullable Object sv) {
		Double s = scalar(sv);
		if (!packed(g) || s == null || !resident(g)) {
			return null;
		}
		int off = 1 + rank(g);
		int n = length(g) - off;
		if (n < 1) {
			return null;
		}
		boolean ran = g instanceof float[] f ? Gpu.scale(Gpu.BIN_MUL, f, off, s, false, f, off, n)
				: Gpu.scale(Gpu.BIN_MUL, doubles(g), off, s, false, doubles(g), off, n);
		return ran ? g : null;
	}

	/**
	 * A strided copy of {@code a} into a fresh {@code od}-shaped array of its width,
	 * walked over {@code dims} from element {@code origin} of {@code a} by {@code sa},
	 * and from the new array's first element by {@code so}. {@code null} when the device
	 * declined it.
	 */
	private static @Nullable Object copyInto(Object a, int origin, int[] sa, int[] dims, int[] so, int[] od) {
		int off = 1 + od.length;
		int n = (int) count(od);
		int rank = rank(a);
		if (a instanceof float[] x) {
			float[] c = newLike(od, off + n);
			return Gpu.copy(x, origin, sa, new int[] { 1 + rank, x.length - 1 - rank }, c, off, so,
					new int[] { off, n }, dims) ? c : null;
		}
		double[] x = doubles(a);
		double[] c = newLikeD(od, off + n);
		return Gpu.copy(x, origin, sa, new int[] { 1 + rank, x.length - 1 - rank }, c, off, so, new int[] { off, n },
				dims) ? c : null;
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

	/** A shape designator -- a non-negative Long, or a proper list of them -- as ints. */
	private static int @Nullable [] shapeOf(@Nullable Object shape) {
		if (shape instanceof Long n) {
			return n >= 0 && n <= Integer.MAX_VALUE ? new int[] { (int) (long) n } : null;
		}
		int[] out = ints(shape);
		if (out == null) {
			return null;
		}
		for (int d : out) {
			if (d < 0) {
				return null;
			}
		}
		return out;
	}

	/** A proper list of ints, of either sign. */
	private static int @Nullable [] ints(@Nullable Object list) {
		int count = 0;
		Object cursor = list;
		while (cursor instanceof Object[] cell && cell.length == 2 && cell[0] instanceof Long l
				&& l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
			count++;
			cursor = cell[1];
		}
		if (cursor != null) {
			return null;
		}
		int[] out = new int[count];
		cursor = list;
		for (int i = 0; i < count; i++) {
			Object[] cell = (Object[]) java.util.Objects.requireNonNull(cursor);
			out[i] = (int) (long) (Long) java.util.Objects.requireNonNull(cell[0]);
			cursor = cell[1];
		}
		return out;
	}

	/** Whether the value is a packed float array of either width. */
	private static boolean packed(@Nullable Object o) {
		return o instanceof double[] || o instanceof float[];
	}

	/**
	 * Whether the device holds a copy of the value (a packed array); false for the rest.
	 */
	private static boolean resident(@Nullable Object o) {
		return o != null && Gpu.resident(o);
	}

	/** The Java length of a packed array of either width. */
	private static int length(@Nullable Object o) {
		return o instanceof float[] f ? f.length : doubles(o).length;
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
		boolean resident = resident(a);
		if (!resident && !Gpu.worthStrided(count(d))) {
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
		if ((!resident && !Gpu.worthStrided(total)) || !Gpu.available()) {
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
	 * is the measurement the element-wise tier made and this does not reverse. A scalar
	 * operand, a boxed array and a mixed-width pair decline for the reasons the lane
	 * kernel declines them.
	 */
	private static @Nullable Object bcast(int op, @Nullable Object a, @Nullable Object b) {
		if (!packed(a)) {
			// A scalar on the left of a packed array: the resident tier's scalar form,
			// with the scalar as the LEFT operand of a non-commutative op.
			if (packed(b)) {
				Double s = scalar(a);
				return s == null ? null : scale(op, java.util.Objects.requireNonNull(b), s, true);
			}
			return null;
		}
		if (!packed(b)) {
			Double s = scalar(b);
			return s == null ? null : scale(op, java.util.Objects.requireNonNull(a), s, false);
		}
		boolean single = a instanceof float[];
		if (single != (b instanceof float[])) {
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
			return zip(op, java.util.Objects.requireNonNull(a), java.util.Objects.requireNonNull(b));
		}
		// The size test FIRST, over a bound that costs nothing: a broadcast output is at
		// least as big as either operand. Every linalg:add call site in the program runs
		// this method, so a declined call must not allocate a shape it will throw away. A
		// resident operand is offered at any size.
		boolean resident = resident(a) || resident(b);
		if (!resident && !Gpu.worthStrided(Math.max(count(da), count(db)))) {
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
		if (total + 1 + rank > Integer.MAX_VALUE - 8 || (!resident && !Gpu.worthStrided(total)) || !Gpu.available()) {
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
	 * The resident tier's EQUAL-shape binary op -- the case the element-wise tier
	 * measured and refused as a round trip (the lane kernel below wins), and a launch
	 * with no copy once an operand is resident. Declined otherwise, at any size.
	 * Bit-identical to the lane kernel: double arithmetic, narrowed on the store.
	 */
	private static @Nullable Object zip(int op, Object a, Object b) {
		if (!resident(a) && !resident(b)) {
			return null;
		}
		int off = 1 + rank(a);
		int n = length(a) - off;
		if (n < 1 || length(b) - off != n) {
			return null;
		}
		if (a instanceof float[] x) {
			float[] c = new float[x.length];
			System.arraycopy(x, 0, c, 0, off);
			return Gpu.zip(op, x, off, floats(b), off, c, off, n) ? c : null;
		}
		double[] x = doubles(a);
		double[] c = new double[x.length];
		System.arraycopy(x, 0, c, 0, off);
		return Gpu.zip(op, x, off, doubles(b), off, c, off, n) ? c : null;
	}

	/**
	 * The resident tier's array-with-scalar form ({@code laEwFS} / {@code laEwSF}'s
	 * shape): over a resident array only, with the scalar a double whatever the array's
	 * width, as the lane kernel keeps it.
	 */
	private static @Nullable Object scale(int op, Object a, double s, boolean swap) {
		if (!resident(a)) {
			return null;
		}
		int off = 1 + rank(a);
		int n = length(a) - off;
		if (n < 1) {
			return null;
		}
		if (a instanceof float[] x) {
			float[] c = new float[x.length];
			System.arraycopy(x, 0, c, 0, off);
			return Gpu.scale(op, x, off, s, swap, c, off, n) ? c : null;
		}
		double[] x = doubles(a);
		double[] c = new double[x.length];
		System.arraycopy(x, 0, c, 0, off);
		return Gpu.scale(op, x, off, s, swap, c, off, n) ? c : null;
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
		if ((!Gpu.worthFold((long) outer * inner * len) && !resident(a)) || (long) outer * inner < 2) {
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
		// A libm member from the size threshold; any member -- the resident tier's four
		// included -- over a resident operand, where there is no trip to pay for.
		if (count < 1 || count != length - off || !((op < Gpu.MAP_LIBM_OPS && Gpu.worthMap(count)) || resident(a))) {
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

	/** A fresh rank-1 packed double vector of {@code n} elements, header written. */
	private static double[] newVec(int n) {
		double[] v = new double[2 + n];
		v[0] = 1.0;
		v[1] = n;
		return v;
	}

	private static float[] newVecF(int n) {
		float[] v = new float[2 + n];
		v[0] = 1.0f;
		v[1] = n;
		return v;
	}

}
