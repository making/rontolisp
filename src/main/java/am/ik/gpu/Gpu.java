package am.ik.gpu;

import org.jspecify.annotations.Nullable;

/**
 * A matrix product on the GPU, or {@code null} because this call declines. That is the
 * whole public surface of this library, and it is deliberately the shape of a PARTIAL
 * function: a caller offers a product, gets a fresh result array when the device took it,
 * and gets {@code null} -- never an exception -- when it did not.
 *
 * <h2>What "declines" covers</h2>
 *
 * Everything. No NVIDIA driver, no device, a card older than the PTX this library
 * carries, a JVM that forbids native access, a platform with no {@code libcuda.so.1} (so
 * far, anything but Linux), a shape too big for the launch grid, a product too small to
 * be worth a round trip, device memory exhausted, a failed {@code CUresult} of any kind,
 * or a context that a previous failure left unusable. All of them answer {@code null}
 * quietly, and the caller runs whatever it would have run anyway. This is the same
 * posture {@code --simd} has toward a JDK without {@code jdk.incubator.vector} and
 * {@code --blas} has toward a machine with no tuned CBLAS ({@code .kb/linalg-simd.md},
 * {@code .kb/linalg-blas.md}).
 *
 * <h2>The probe runs once</h2>
 *
 * Binding the driver, retaining the primary context and JIT-compiling the kernels is done
 * on first use and cached for the life of the process, failure included: a machine
 * without a GPU pays one failed {@code dlopen} and never touches the driver again.
 * {@link #description()} says what was found or why nothing was, and is what a caller
 * should print when it was asked for a GPU and cannot have one.
 *
 * <h2>Offsets, because the arrays have headers</h2>
 *
 * Both products take an element offset per operand. rontolisp's compiled backends carry a
 * {@code [rank, dim..., data...]} header inside the same array as the data, so a caller
 * must be able to say where the elements start; an interpreter whose arrays hold data
 * alone passes 0. The result is a fresh array of exactly {@code n * p} elements with no
 * header, which the caller wraps as it likes.
 *
 * @see CuResult
 */
public final class Gpu {

	/**
	 * Below this many multiply-adds a product declines and the caller's own kernel wins.
	 *
	 * <p>
	 * A round trip to the device has a FLOOR -- context, allocation, launch, latency --
	 * that does not shrink with the operand, measured at 15 us on the machine this was
	 * tuned on. What it has to beat there is not CPU arithmetic but rontolisp's fastest
	 * kernel on a small array, and that is cheap: a JIT-warm {@code --simd} product on
	 * the JVM costs 6-10 us at n=32 and only passes the floor between n=32 and n=64 (f64:
	 * 23 us at n=48 and 49 us at n=64, against 19 and 22 here; f32: 14 and 28.5 against
	 * 16.5 and 17.3). 2^17 is a 50.8x50.8x50.8 product, which is where the later of the
	 * two crossovers falls. Declining costs nothing, so the threshold sits where the win
	 * is unambiguous rather than where it first appears.
	 *
	 * <p>
	 * {@code --blas} puts the same predicate at 64 rather than 131072, because a critical
	 * downcall into a CPU library floors at 30 ns and a GPU round trip at 15 us -- three
	 * orders of magnitude of fixed cost, and hence three orders of magnitude of
	 * threshold. See {@code .kb/gpu.md} for the measurement.
	 */
	private static final long POOLED_MIN_WORK = 1L << 17;

	/**
	 * The threshold for a driver whose stream-ordered allocator this library could not
	 * use: {@code cuMemAlloc} and {@code cuMemFree} cost 126 us a pair, three pairs are
	 * needed, and the floor is 170 us rather than 15. The crossover moves with it, to
	 * between n=96 and n=128 (131 us and 384 us on the CPU).
	 */
	private static final long UNPOOLED_MIN_WORK = 1L << 21;

	private static final @Nullable CudaGemm DEVICE;

	private static final String DESCRIPTION;

	private static final long MIN_WORK;

	static {
		CudaGemm device;
		String description;
		try {
			CudaGemm.Probe probe = CudaGemm.probe();
			device = probe.gemm();
			description = probe.description();
		}
		catch (Throwable ex) {
			// The probe is written not to throw; if it ever does, the answer is still no.
			device = null;
			description = "the CUDA driver could not be probed: " + ex;
		}
		DEVICE = device;
		DESCRIPTION = description;
		MIN_WORK = device == null || device.pooled() ? POOLED_MIN_WORK : UNPOOLED_MIN_WORK;
	}

	private Gpu() {
	}

	/**
	 * Whether this machine has a GPU these kernels can run on. {@code false} is an
	 * ordinary answer, not an error, and it is the answer on most machines.
	 * @return {@code true} when a product may be offered to {@link #multiply}
	 */
	public static boolean available() {
		CudaGemm device = DEVICE;
		return device != null && device.usable();
	}

	/**
	 * What was found -- device model, architecture, driver version -- or why nothing was.
	 * Always a printable one-liner, on every machine.
	 * @return a one-line description of the probe's outcome
	 */
	public static String description() {
		return DESCRIPTION;
	}

	/**
	 * The one device this process has probed, or {@code null}. Package-private and for
	 * the tests: probing again would retain a second reference to the primary context and
	 * JIT the module a second time, so nothing may call {@code CudaGemm.probe()} twice.
	 * @return the probed device, or {@code null} when there is none
	 */
	static @Nullable CudaGemm device() {
		return DEVICE;
	}

	/**
	 * Whether an {@code n x m} by {@code m x p} product is big enough to be worth a round
	 * trip to the device at all. A caller should ask this BEFORE it goes to the trouble
	 * of unwrapping its operands; {@link #multiply} asks it again anyway.
	 * @param n rows of the left operand and of the result
	 * @param m the inner dimension
	 * @param p columns of the right operand and of the result
	 * @return {@code true} when the product is above the size threshold
	 */
	public static boolean worth(long n, long m, long p) {
		return n > 0 && m > 0 && p > 0 && n * m * p >= MIN_WORK;
	}

	/**
	 * {@code a x b} for a row-major {@code n x m} by {@code m x p} pair of double-float
	 * arrays.
	 *
	 * <p>
	 * Double is the width this device class is WORST at -- a tenth to a fortieth of its
	 * single-float throughput, depending on the card -- so this product wins by less than
	 * its single-float sibling does, and on some machines a threaded CPU BLAS draws level
	 * with it. It is still the width {@code linalg} defaults to, so it is here.
	 *
	 * <p>
	 * The result is NOT bit-identical to a scalar row-by-column product: the tiled kernel
	 * reorders the reduction. Callers that need identity must not offer the product.
	 * @param a the left operand, row-major, elements starting at {@code offsetA}
	 * @param offsetA the index of {@code a}'s first element
	 * @param b the right operand, row-major, elements starting at {@code offsetB}
	 * @param offsetB the index of {@code b}'s first element
	 * @param n rows of {@code a} and of the result
	 * @param m columns of {@code a} and rows of {@code b}
	 * @param p columns of {@code b} and of the result
	 * @return a fresh {@code n * p} array, or {@code null} when this call declines
	 */
	public static double @Nullable [] multiply(double[] a, int offsetA, double[] b, int offsetB, int n, int m, int p) {
		CudaGemm device = DEVICE;
		if (device == null || !offered(a.length, offsetA, b.length, offsetB, n, m, p)) {
			return null;
		}
		double[] c = new double[n * p];
		return device.gemm(a, offsetA, b, offsetB, c, n, m, p) ? c : null;
	}

	/**
	 * The single-float sibling of
	 * {@link #multiply(double[], int, double[], int, int, int, int)}, and the one the
	 * hardware is for.
	 * @param a the left operand, row-major, elements starting at {@code offsetA}
	 * @param offsetA the index of {@code a}'s first element
	 * @param b the right operand, row-major, elements starting at {@code offsetB}
	 * @param offsetB the index of {@code b}'s first element
	 * @param n rows of {@code a} and of the result
	 * @param m columns of {@code a} and rows of {@code b}
	 * @param p columns of {@code b} and of the result
	 * @return a fresh {@code n * p} array, or {@code null} when this call declines
	 */
	public static float @Nullable [] multiply(float[] a, int offsetA, float[] b, int offsetB, int n, int m, int p) {
		CudaGemm device = DEVICE;
		if (device == null || !offered(a.length, offsetA, b.length, offsetB, n, m, p)) {
			return null;
		}
		float[] c = new float[n * p];
		return device.gemmF(a, offsetA, b, offsetB, c, n, m, p) ? c : null;
	}

	/**
	 * Whether a product is one this library will attempt: worth the trip, launchable in
	 * one grid, and actually present in the arrays it was handed. A caller that gets its
	 * own offsets wrong is declined rather than signalled -- the point of the whole
	 * mechanism is that it never changes what a program computes.
	 */
	private static boolean offered(int lengthA, int offsetA, int lengthB, int offsetB, int n, int m, int p) {
		return worth(n, m, p) && CudaGemm.launchable(n, m, p) && offsetA >= 0 && offsetB >= 0
				&& (long) offsetA + (long) n * m <= lengthA && (long) offsetB + (long) m * p <= lengthB;
	}

}
