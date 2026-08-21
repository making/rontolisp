package am.ik.rontolisp.codegen.jvm;

import am.ik.gpu.Gpu;
import org.jspecify.annotations.Nullable;

/**
 * The GPU bridge injected into a compiled {@code .class} when the {@code --gpu} flag is
 * passed: {@code linalg:dot} over two packed rank-2 operands -- and through it
 * {@code linalg:matmul} at rank 2 and {@code linalg:solve} -- is lowered to a call on
 * {@link #gpuDot}, which offers the product to an NVIDIA device and declines everything
 * else.
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

	// --- the compiled packed float-array representation --------------------------------

	private static int rank(@Nullable Object o) {
		return o instanceof float[] f ? (int) f[0] : (int) ((double[]) java.util.Objects.requireNonNull(o))[0];
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
