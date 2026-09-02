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

	/**
	 * The minimum element count a fused row member (the softmax, log-softmax, GELU and
	 * layer-norm pairs) is offered at here: the fold threshold on CUDA, the map threshold
	 * on Metal, whose fold threshold is {@link Long#MAX_VALUE}.
	 * @return the fused threshold in force
	 */
	public static long fusedMinElements() {
		return Gpu.fusedMinElements();
	}

	public static long rngMinElements() {
		return Gpu.rngMinElements();
	}

	/**
	 * The minimum {@code rows * cols} a matrix-by-vector product is offered at here, or
	 * {@link Long#MAX_VALUE} on a backend that keeps no resident copies and so is not a
	 * member of it.
	 * @return the GEMV threshold in force
	 */
	public static long matvecMinElements() {
		return Gpu.matvecMinElements();
	}

	/**
	 * Residency lookups answered from the cache since the process started, or {@code -1}
	 * with no device -- the one observable that says a call over a resident operand
	 * REALLY ran on the device, which no printed value can, since the accepted member is
	 * written to land on the oracle's own bits. Both backends keep a cache.
	 * @return the hit count, or -1
	 */
	public static long residencyHits() {
		DeviceResidency residency = Gpu.residency();
		return residency != null ? residency.hits() : -1;
	}

	/**
	 * Residency lookups that missed since the process started, or {@code -1} with no
	 * device -- with {@link #residencyHits()}, what says whether a call uploaded.
	 * @return the miss count, or -1
	 */
	public static long residencyMisses() {
		DeviceResidency residency = Gpu.residency();
		return residency != null ? residency.misses() : -1;
	}

	/**
	 * How many resident copies are DIRTY right now -- results left on the device that the
	 * host has not read ({@code Gpu.lazyResults}) -- or {@code -1} with no device. The
	 * one observable that says a result really stayed on the device.
	 * @return the dirty count, or -1
	 */
	public static int dirtyCount() {
		DeviceResidency residency = Gpu.residency();
		return residency != null ? residency.dirtyCount() : -1;
	}

	/**
	 * How many result STUBS hold a backing right now -- lazy results the host has read,
	 * or written, at least once -- or {@code -1} with no device. With
	 * {@link #dirtyCount}, the observable that says a lazy result allocated no host array
	 * until it was read.
	 * @return the backing count, or -1
	 */
	public static int backingCount() {
		DeviceResidency residency = Gpu.residency();
		return residency != null ? residency.backingCount() : -1;
	}

	/**
	 * Whether lazy results are in force on the device the interceptors found -- the mode
	 * they switch on where the backend says it pays
	 * ({@code Gpu.lazyResultsIfWorthwhile}): CUDA yes, Metal no, measured. The tests that
	 * assert a result STAYED assume it.
	 * @return {@code true} while results stay on the device until read
	 */
	public static boolean lazyResultsOn() {
		return Gpu.lazyResultsOn();
	}

	/**
	 * Whether lazy results PAY on the device found -- what decides whether an
	 * interceptor, in this process or in a compiled class's own copy of the library,
	 * switches them on ({@code GpuDevice.lazyResultsPay}): CUDA yes, and Metal since
	 * todo-495.
	 * @return {@code true} when an interceptor over this device runs with lazy results
	 */
	public static boolean lazyResultsPay() {
		GpuDevice device = Gpu.device();
		return device != null && device.lazyResultsPay();
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
