package am.ik.gpu;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Every {@code CUresult} the CUDA driver API can return, with the one property this
 * library actually reasons about: whether the status leaves the context UNUSABLE. The 101
 * constants below are diffed against {@code cuda.h} at {@code CUDA_VERSION 13000} and
 * agree with it exactly -- which is a thing to RE-CHECK when the table is extended,
 * because an invented constant is worse than a missing one (see {@link #isSticky(int)}).
 *
 * <h2>Why the whole table and not just "non-zero"</h2>
 *
 * {@code --gpu} declines rather than signals, so a failed status is never propagated to a
 * Lisp program -- but it is not all the same failure. {@link #CUDA_ERROR_NO_DEVICE} means
 * this machine has no GPU and the answer will never change;
 * {@link #CUDA_ERROR_OUT_OF_MEMORY} means this particular product was too big and the
 * next one may well fit; and the seventeen statuses marked {@linkplain #sticky() sticky}
 * mean the context is corrupt and every later call would fail too, so the feature turns
 * itself off for the rest of the process instead of paying a doomed round trip per call.
 * Collapsing those three into "non-zero" is what makes a silent fallback feel like a
 * hang.
 *
 * <p>
 * The human-readable text is NOT duplicated here: the driver itself supplies it through
 * {@code cuGetErrorString}, and {@code CudaDriver.errorString} asks it. This table is the
 * code-to-name mapping and the sticky classification, both of which the driver does not
 * expose.
 *
 * <p>
 * An unrecognised code is not an error either -- a newer driver may return a status this
 * table predates -- so {@link #of(int)} answers {@code null} and {@link #describe(int)}
 * still produces a usable string. Nothing in this class throws.
 *
 * @see Gpu
 */
public enum CuResult {

	/** The call succeeded. Also the answer to a completed query. */
	CUDA_SUCCESS(0),

	CUDA_ERROR_INVALID_VALUE(1),

	CUDA_ERROR_OUT_OF_MEMORY(2),

	CUDA_ERROR_NOT_INITIALIZED(3),

	CUDA_ERROR_DEINITIALIZED(4, true),

	CUDA_ERROR_PROFILER_DISABLED(5),

	CUDA_ERROR_PROFILER_NOT_INITIALIZED(6),

	CUDA_ERROR_PROFILER_ALREADY_STARTED(7),

	CUDA_ERROR_PROFILER_ALREADY_STOPPED(8),

	/** A driver stub is installed rather than a real driver: there is no GPU here. */
	CUDA_ERROR_STUB_LIBRARY(34),

	CUDA_ERROR_CALL_REQUIRES_NEWER_DRIVER(36),

	CUDA_ERROR_DEVICE_UNAVAILABLE(46, true),

	/** No CUDA-capable device: the ordinary answer on a machine without an NVIDIA GPU. */
	CUDA_ERROR_NO_DEVICE(100),

	CUDA_ERROR_INVALID_DEVICE(101),

	CUDA_ERROR_DEVICE_NOT_LICENSED(102),

	CUDA_ERROR_INVALID_IMAGE(200),

	CUDA_ERROR_INVALID_CONTEXT(201),

	CUDA_ERROR_CONTEXT_ALREADY_CURRENT(202),

	CUDA_ERROR_MAP_FAILED(205),

	CUDA_ERROR_UNMAP_FAILED(206),

	CUDA_ERROR_ARRAY_IS_MAPPED(207),

	CUDA_ERROR_ALREADY_MAPPED(208),

	/**
	 * The module holds no code this device can run -- a card older than the PTX floor.
	 */
	CUDA_ERROR_NO_BINARY_FOR_GPU(209),

	CUDA_ERROR_ALREADY_ACQUIRED(210),

	CUDA_ERROR_NOT_MAPPED(211),

	CUDA_ERROR_NOT_MAPPED_AS_ARRAY(212),

	CUDA_ERROR_NOT_MAPPED_AS_POINTER(213),

	CUDA_ERROR_ECC_UNCORRECTABLE(214, true),

	CUDA_ERROR_UNSUPPORTED_LIMIT(215),

	CUDA_ERROR_CONTEXT_ALREADY_IN_USE(216),

	CUDA_ERROR_PEER_ACCESS_UNSUPPORTED(217),

	/** The checked-in PTX did not parse: a build-time defect, caught at the probe. */
	CUDA_ERROR_INVALID_PTX(218),

	CUDA_ERROR_INVALID_GRAPHICS_CONTEXT(219),

	CUDA_ERROR_NVLINK_UNCORRECTABLE(220, true),

	CUDA_ERROR_JIT_COMPILER_NOT_FOUND(221),

	/** The driver is too old to JIT this PTX version. Declines to the CPU. */
	CUDA_ERROR_UNSUPPORTED_PTX_VERSION(222),

	CUDA_ERROR_JIT_COMPILATION_DISABLED(223),

	CUDA_ERROR_UNSUPPORTED_EXEC_AFFINITY(224),

	CUDA_ERROR_UNSUPPORTED_DEVSIDE_SYNC(225),

	CUDA_ERROR_CONTAINED(226),

	CUDA_ERROR_INVALID_SOURCE(300),

	CUDA_ERROR_FILE_NOT_FOUND(301),

	CUDA_ERROR_SHARED_OBJECT_SYMBOL_NOT_FOUND(302),

	CUDA_ERROR_SHARED_OBJECT_INIT_FAILED(303),

	CUDA_ERROR_OPERATING_SYSTEM(304),

	CUDA_ERROR_INVALID_HANDLE(400),

	CUDA_ERROR_ILLEGAL_STATE(401),

	CUDA_ERROR_LOSSY_QUERY(402),

	CUDA_ERROR_NOT_FOUND(500),

	CUDA_ERROR_NOT_READY(600),

	CUDA_ERROR_ILLEGAL_ADDRESS(700, true),

	CUDA_ERROR_LAUNCH_OUT_OF_RESOURCES(701),

	CUDA_ERROR_LAUNCH_TIMEOUT(702, true),

	CUDA_ERROR_LAUNCH_INCOMPATIBLE_TEXTURING(703),

	CUDA_ERROR_PEER_ACCESS_ALREADY_ENABLED(704),

	CUDA_ERROR_PEER_ACCESS_NOT_ENABLED(705),

	CUDA_ERROR_PRIMARY_CONTEXT_ACTIVE(708),

	CUDA_ERROR_CONTEXT_IS_DESTROYED(709, true),

	CUDA_ERROR_ASSERT(710, true),

	CUDA_ERROR_TOO_MANY_PEERS(711),

	CUDA_ERROR_HOST_MEMORY_ALREADY_REGISTERED(712),

	CUDA_ERROR_HOST_MEMORY_NOT_REGISTERED(713),

	CUDA_ERROR_HARDWARE_STACK_ERROR(714, true),

	CUDA_ERROR_ILLEGAL_INSTRUCTION(715, true),

	CUDA_ERROR_MISALIGNED_ADDRESS(716, true),

	CUDA_ERROR_INVALID_ADDRESS_SPACE(717, true),

	CUDA_ERROR_INVALID_PC(718, true),

	CUDA_ERROR_LAUNCH_FAILED(719, true),

	CUDA_ERROR_COOPERATIVE_LAUNCH_TOO_LARGE(720),

	CUDA_ERROR_TENSOR_MEMORY_LEAK(721),

	CUDA_ERROR_NOT_PERMITTED(800),

	CUDA_ERROR_NOT_SUPPORTED(801),

	CUDA_ERROR_SYSTEM_NOT_READY(802),

	CUDA_ERROR_SYSTEM_DRIVER_MISMATCH(803, true),

	CUDA_ERROR_COMPAT_NOT_SUPPORTED_ON_DEVICE(804),

	CUDA_ERROR_MPS_CONNECTION_FAILED(805),

	CUDA_ERROR_MPS_RPC_FAILURE(806),

	CUDA_ERROR_MPS_SERVER_NOT_READY(807),

	CUDA_ERROR_MPS_MAX_CLIENTS_REACHED(808),

	CUDA_ERROR_MPS_MAX_CONNECTIONS_REACHED(809),

	CUDA_ERROR_MPS_CLIENT_TERMINATED(810),

	CUDA_ERROR_CDP_NOT_SUPPORTED(811),

	CUDA_ERROR_CDP_VERSION_MISMATCH(812),

	CUDA_ERROR_STREAM_CAPTURE_UNSUPPORTED(900),

	CUDA_ERROR_STREAM_CAPTURE_INVALIDATED(901),

	CUDA_ERROR_STREAM_CAPTURE_MERGE(902),

	CUDA_ERROR_STREAM_CAPTURE_UNMATCHED(903),

	CUDA_ERROR_STREAM_CAPTURE_UNJOINED(904),

	CUDA_ERROR_STREAM_CAPTURE_ISOLATION(905),

	CUDA_ERROR_STREAM_CAPTURE_IMPLICIT(906),

	CUDA_ERROR_CAPTURED_EVENT(907),

	CUDA_ERROR_STREAM_CAPTURE_WRONG_THREAD(908),

	CUDA_ERROR_TIMEOUT(909),

	CUDA_ERROR_GRAPH_EXEC_UPDATE_FAILURE(910),

	CUDA_ERROR_EXTERNAL_DEVICE(911, true),

	CUDA_ERROR_INVALID_CLUSTER_SIZE(912),

	CUDA_ERROR_FUNCTION_NOT_LOADED(913),

	CUDA_ERROR_INVALID_RESOURCE_TYPE(914),

	CUDA_ERROR_INVALID_RESOURCE_CONFIGURATION(915),

	CUDA_ERROR_KEY_ROTATION(916),

	/** An unspecified internal error. Treated as sticky: nothing here can recover it. */
	CUDA_ERROR_UNKNOWN(999, true);

	/**
	 * The value {@code cuInit} and every other driver entry point returns for success.
	 */
	public static final int SUCCESS = 0;

	private static final Map<Integer, CuResult> BY_CODE = new HashMap<>();

	static {
		for (CuResult result : values()) {
			BY_CODE.put(result.code, result);
		}
	}

	private final int code;

	private final boolean sticky;

	CuResult(int code) {
		this(code, false);
	}

	CuResult(int code, boolean sticky) {
		this.code = code;
		this.sticky = sticky;
	}

	/**
	 * The numeric {@code CUresult} this constant stands for.
	 * @return the driver's own status code
	 */
	public int code() {
		return this.code;
	}

	/**
	 * Whether this status leaves the CUDA context permanently unusable, so that every
	 * later call in the process would fail as well. The kernel launch failures and the
	 * uncorrectable memory errors are sticky; running out of device memory or being
	 * handed a bad argument is not.
	 * @return {@code true} when the feature should turn itself off rather than retry
	 */
	public boolean sticky() {
		return this.sticky;
	}

	/**
	 * The constant for a driver status code, or {@code null} for one this table does not
	 * know -- a newer driver may return a status this release predates, which is not an
	 * error condition of its own.
	 * @param code a value returned by a CUDA driver entry point
	 * @return the matching constant, or {@code null}
	 */
	public static @Nullable CuResult of(int code) {
		return BY_CODE.get(code);
	}

	/**
	 * Whether a status code is one that leaves the context unusable. An unrecognised code
	 * is treated as sticky, because a status this table predates is more likely a new
	 * failure mode than a new success.
	 * @param code a value returned by a CUDA driver entry point
	 * @return {@code true} when the feature should turn itself off
	 */
	public static boolean isSticky(int code) {
		CuResult result = of(code);
		return result == null ? code != SUCCESS : result.sticky;
	}

	/**
	 * A diagnostic string for a status code: the constant's name where it is known, the
	 * bare number where it is not. Never throws and never allocates a driver call -- the
	 * driver's own sentence is added by {@code CudaDriver.errorString}.
	 * @param code a value returned by a CUDA driver entry point
	 * @return a one-line description, always non-null
	 */
	public static String describe(int code) {
		CuResult result = of(code);
		return result == null ? "CUresult " + code : result.name() + " (" + code + ")";
	}

}
