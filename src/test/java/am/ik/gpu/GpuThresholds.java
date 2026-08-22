package am.ik.gpu;

/**
 * The in-force thresholds and the width answer, for tests OUTSIDE this package. Nothing
 * in the feature needs them -- {@code Gpu.worth} is the probe-free question a caller asks
 * and {@code Gpu.multiply} is the real one, deliberately ({@code .kb/gpu.md}) -- but the
 * interceptor's tests have to size their shapes off the threshold ACTUALLY in force, or
 * they hard-code one backend's crossover and read as a kernel regression on the other.
 *
 * <p>
 * A test-scope shim rather than a widening of {@link Gpu}: making these public would
 * invite a caller to probe (a {@code dlopen}, a context, a kernel compile) on a path that
 * may never touch the device, which is the one thing the {@code worth} / {@code multiply}
 * split exists to prevent.
 */
public final class GpuThresholds {

	private GpuThresholds() {
	}

	/** The minimum {@code batch * n * m * p} a product is accepted at here. */
	public static long minWork() {
		return Gpu.minWork();
	}

	/** The minimum element count an element-wise map is accepted at here. */
	public static long mapMinElements() {
		return Gpu.mapMinElements();
	}

	/** The minimum OUTPUT element count a broadcast or a gather is accepted at here. */
	public static long stridedMinElements() {
		return Gpu.stridedMinElements();
	}

	/**
	 * The minimum INPUT element count an axis fold is accepted at here, or
	 * {@link Long#MAX_VALUE} on a backend that is not a member of that tier at all.
	 * @return the fold threshold in force
	 */
	public static long foldMinElements() {
		return Gpu.foldMinElements();
	}

	public static long rngMinElements() {
		return Gpu.rngMinElements();
	}

	/**
	 * Whether a {@code #d} operand can reach the device at all. {@code false} on Metal,
	 * where MSL has no {@code double}.
	 * @return {@code true} when the double half of the library is live here
	 */
	public static boolean supportsDouble() {
		GpuDevice device = Gpu.device();
		return device != null && device.supportsDouble();
	}

}
