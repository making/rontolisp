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

	/** What was found, or why nothing was -- the text the CLI reports. */
	static String description() {
		try {
			return Gpu.description();
		}
		catch (Throwable ex) {
			return "the CUDA driver could not be probed: " + ex;
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

}
