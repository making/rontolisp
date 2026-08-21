package am.ik.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

import static am.ik.gpu.CudaDriver.I;
import static am.ik.gpu.CudaDriver.L;
import static am.ik.gpu.CudaDriver.P;

/**
 * The device side of {@code --gpu}: one primary context, one module JIT-compiled from the
 * checked-in PTX, and the six kernels it exports -- a tiled matrix product, a STACK of
 * them and an element-wise map, at each width. Everything that can fail fails into a
 * decline; nothing here throws.
 *
 * <h2>The kernel comes from a PTX text, not from a toolkit</h2>
 *
 * {@code gemm.ptx} is generated at DEVELOPER time by {@code nvcc -arch=compute_75 -ptx}
 * (the exact command is at the top of {@code gemm.cu}, beside it in the resources) and
 * checked in. At run time {@code cuModuleLoadData} hands that text to the driver, which
 * JIT-compiles it for whatever card is present -- measured on this project's spike
 * machine at 26 ms the first time a given text is seen and 1.4 ms on every run after
 * that, because the driver keeps its own on-disk cache in {@code ~/.nv/ComputeCache} and
 * the resource is a fixed text. So there is no {@code cuModuleLoadDataEx} cache plumbing
 * of our own, and no toolkit on the user's machine.
 *
 * <h2>Lifetime</h2>
 *
 * The context is retained once and the module loaded once, both for the life of the
 * process -- they are what a per-call intercept must NOT pay for. Every partial failure
 * during {@link #probe()} unwinds what it had already acquired, so a machine that
 * declines leaves no retained primary context behind. Per call, the three device buffers
 * are freed on every path, success and failure alike, and they are allocated from the
 * DRIVER's pool ({@code cuMemAllocAsync}) rather than by {@code cuMemAlloc}, which is 126
 * us per allocate-and-free pair and would otherwise be the dominant cost of every small
 * product. Nothing is cached between calls: the pool is the driver's, so this class owns
 * no device memory at all once a call returns.
 *
 * <h2>A sticky error retires the feature</h2>
 *
 * Most CUDA failures are local -- a product too big for device memory declines and the
 * next one may fit. The {@linkplain CuResult#sticky() sticky} ones are not: they leave
 * the context unusable, so every later call would pay a full round trip to fail. On one
 * of those this object marks itself unusable and {@link Gpu} declines from then on
 * without touching the driver again.
 *
 * @see Gpu
 * @see CudaDriver
 */
final class CudaGemm {

	/** The virtual architecture {@code gemm.ptx} was generated for. */
	static final int PTX_COMPUTE_CAPABILITY = 75;

	/** The checked-in PTX, beside this class in the resources. */
	static final String PTX_RESOURCE = "gemm.ptx";

	/**
	 * The kernels an EMBEDDER supplied, when this library's classes travel without its
	 * resources -- see {@link Gpu#useKernels(String)}. Read once, by {@link #probe()},
	 * ahead of {@link #PTX_RESOURCE}.
	 */
	private static volatile byte @Nullable [] embeddedPtx;

	/** The kernels {@link #PTX_RESOURCE} must export. */
	static final String KERNEL_F64 = "gemm_f64", KERNEL_F32 = "gemm_f32";

	/**
	 * The STACKED siblings, one tile walk per {@code blockIdx.z} over the same slab
	 * shape. A batch is one launch rather than one per matrix, and a broadcast operand
	 * passes stride 0 -- so it needs no special case, exactly as on the CPU
	 * ({@code .kb/linalg-simd.md}).
	 */
	static final String KERNEL_BATCHED_F64 = "gemm_batched_f64", KERNEL_BATCHED_F32 = "gemm_batched_f32";

	/**
	 * The ELEMENT-WISE map kernels, one per width. The member is an op-code PARAMETER
	 * rather than an entry point of its own ({@link Gpu#MAP_EXP} and its siblings, which
	 * {@code gemm.cu} switches on), so the module lookup is fixed however the member set
	 * grows and the branch is uniform across the grid.
	 */
	static final String KERNEL_MAP_F64 = "map_f64", KERNEL_MAP_F32 = "map_f32";

	/**
	 * The STRIDED tier's kernels, in the order {@link #strided} holds them: a BROADCAST
	 * binary op, a strided GATHER (an axes transpose, and any other permuted copy) and an
	 * AXIS fold, at each width. Their op codes are parameters exactly as the map's are.
	 *
	 * <p>
	 * They exist because the CPU twins of these three shapes are scalar ODOMETER walks
	 * rather than lane loops, which is a different comparison from the one the
	 * element-wise tier lost at equal shapes ({@code .kb/gpu.md}).
	 */
	static final String[] KERNELS_STRIDED = { "bcast_f64", "bcast_f32", "gather_f64", "gather_f32", "fold_f64",
			"fold_f32" };

	private static final int BCAST_F64 = 0, BCAST_F32 = 1, GATHER_F64 = 2, GATHER_F32 = 3, FOLD_F64 = 4, FOLD_F32 = 5;

	/**
	 * Threads per block for the element-wise maps. They are one-dimensional and each
	 * thread does one element, so the only requirement is a multiple of the warp; 256 is
	 * the usual answer and the kernel is bandwidth-bound at f32 either way.
	 */
	private static final int MAP_BLOCK = 256;

	/**
	 * Threads per block for the strided tier. One thread per OUTPUT element (per output
	 * CELL, for a fold), so the same warp-multiple rule the maps follow applies.
	 */
	private static final int STRIDED_BLOCK = 256;

	/**
	 * What one output element of the strided tier is charged as, for the safepoint
	 * threshold. A broadcast index is one integer division and one multiply-add per axis
	 * and the op itself is one instruction, so 16 is the order of it; a fold's own charge
	 * multiplies this by the axis length, because that is what the thread walks.
	 */
	private static final long STRIDED_FLOPS_PER_ELEMENT = 16;

	/**
	 * What one element of an element-wise map is charged as, for the safepoint threshold
	 * {@link #SYNC_FLOPS_PER_MULTIPROCESSOR} sets. A libm call is dozens of operations
	 * and 64 is the order of the slowest of them; on the 48-SM machine this was
	 * calibrated on it puts the explicit wait at n = 2^22, where the f64 {@code erf}
	 * kernel measures ~0.9 ms and is therefore past the ~0.6 ms budget (at 1.5 M elements
	 * it is 0.34 ms and no wait is paid). See {@code .kb/gpu.md}.
	 */
	private static final long MAP_FLOPS_PER_ELEMENT = 64;

	/**
	 * The tile the kernel is written around, and therefore its block shape: 16x16
	 * threads, one output element each.
	 */
	private static final int TILE = 16;

	/**
	 * {@code gridDim.y} and {@code gridDim.z} are 16-bit on every CUDA device, so a
	 * product with more than {@code 65535 * TILE} rows -- or a batch of more than 65535
	 * matrices, which rides on {@code gridDim.z} -- cannot be launched in one go and
	 * declines. {@code gridDim.x} is 32-bit, so the column axis has no such limit.
	 */
	private static final int MAX_GRID_Y = 65535;

	/**
	 * The largest slice handed to one critical copy. A {@code critical(true)} downcall
	 * takes a HEAP segment, which removes the host copy an operand would otherwise need
	 * -- worth 1.1x at n=8 and 3.1x at n=1024 against staging it in a confined arena --
	 * but it also holds the thread off a safepoint for its whole duration. So a copy
	 * bigger than this is SPLIT rather than staged: the driver moves 64 MB, the thread
	 * becomes safepointable, and the next chunk goes. 64 MB is ~1.1 ms of copy on the
	 * machine this was measured on, and one extra downcall per chunk is nothing beside
	 * it. See {@code .kb/gpu.md}.
	 */
	private static final long CRITICAL_CHUNK_BYTES = 1L << 26;

	/**
	 * At or above this many flops ({@code 2*n*m*p}) PER MULTIPROCESSOR the kernel is
	 * awaited by an explicit {@code cuCtxSynchronize} before the result is copied back. A
	 * device-to-host copy on the null stream waits for the kernel by itself, which is
	 * free -- until the copy is a CRITICAL call, when the kernel's whole runtime would
	 * sit inside the window in which the thread cannot reach a safepoint (36 ms at
	 * n=2048, measured).
	 *
	 * <p>
	 * A flop count is not a duration, so it cannot be a fixed number: the same product
	 * that runs for 0.6 ms on a 48-SM device runs for several ms on a small one. Scaling
	 * by {@code CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT} makes the ceiling a duration
	 * budget instead, and the constant is calibrated on a device whose fp64 units already
	 * run at 1/44 of its fp32 rate -- the bad case -- so the width does not need a second
	 * factor. 2^22 flops per SM is 2^28 on the 48-SM machine this was measured on, which
	 * is the ~0.6 ms the budget is meant to be; the extra synchronize costs 0.26 us idle,
	 * so it is paid only where it is noise.
	 */
	private static final long SYNC_FLOPS_PER_MULTIPROCESSOR = 1L << 22;

	/**
	 * How much free device memory a product leaves untouched. The device is shared --
	 * with the display, with other CUDA processes, and with whatever the driver itself
	 * wants -- so a product that would fit only by taking the last byte declines instead.
	 */
	private static final long ALLOCATION_HEADROOM = 64L << 20;

	private final CudaDriver driver;

	private final int device;

	private final MemorySegment context;

	private final MemorySegment module;

	private final MemorySegment gemmF64;

	private final MemorySegment gemmF32;

	private final MemorySegment gemmBatchedF64;

	private final MemorySegment gemmBatchedF32;

	private final MemorySegment mapF64;

	private final MemorySegment mapF32;

	/** The strided tier's six kernels, indexed by {@link #BCAST_F64} and its siblings. */
	private final MemorySegment[] strided;

	private final String description;

	/**
	 * The device's default memory pool, or {@link MemorySegment#NULL} when this driver
	 * has none. Held only so that an out-of-memory decline can hand back what the failed
	 * allocation grew the pool by.
	 */
	private final MemorySegment memoryPool;

	private final long syncFlopCeiling;

	/**
	 * Whether per-call memory comes from the driver's pool. Not final: the fallback path
	 * is otherwise unreachable on a machine whose driver has a pool, and it is a path
	 * that has to keep computing the same answers. Flipped only by
	 * {@code GpuTest.withPooledAllocation}.
	 */
	private volatile boolean pooled;

	private volatile boolean usable = true;

	private CudaGemm(CudaDriver driver, int device, MemorySegment context, MemorySegment module, MemorySegment gemmF64,
			MemorySegment gemmF32, MemorySegment gemmBatchedF64, MemorySegment gemmBatchedF32, MemorySegment mapF64,
			MemorySegment mapF32, MemorySegment[] strided, boolean pooled, MemorySegment memoryPool,
			long syncFlopCeiling, String description) {
		this.driver = driver;
		this.device = device;
		this.context = context;
		this.module = module;
		this.gemmF64 = gemmF64;
		this.gemmF32 = gemmF32;
		this.gemmBatchedF64 = gemmBatchedF64;
		this.gemmBatchedF32 = gemmBatchedF32;
		this.mapF64 = mapF64;
		this.mapF32 = mapF32;
		this.strided = strided;
		this.pooled = pooled;
		this.memoryPool = memoryPool;
		this.syncFlopCeiling = syncFlopCeiling;
		this.description = description;
	}

	/**
	 * The outcome of a probe: the usable device, or {@code null} and the reason there is
	 * none. There is always a reason, and it is always printable.
	 *
	 * @param gemm the opened device, or {@code null} when this machine has none
	 * @param description what was found, or why nothing was
	 */
	record Probe(@Nullable CudaGemm gemm, String description) {
	}

	/**
	 * Asks this machine, once, whether it can run the kernels: driver present, device
	 * present, new enough for the PTX, primary context retained, module loaded, both
	 * kernels resolved. Any failure -- including one this code did not anticipate --
	 * answers "no" with a reason and leaves nothing acquired.
	 * @return the device, or the reason there is none
	 */
	static Probe probe() {
		CudaDriver driver;
		try {
			// Inside the try because binding the linker itself can fail: this method
			// promises an answer, and "the class that holds the handles would not
			// initialize" is one.
			driver = CudaDriver.open();
		}
		catch (Throwable ex) {
			return new Probe(null, "the CUDA driver could not be bound: " + describeThrowable(ex));
		}
		if (driver == null) {
			return new Probe(null, CudaDriver.LIBRARY + " is not present: this machine has no NVIDIA driver");
		}
		int device = -1;
		boolean contextRetained = false;
		MemorySegment module = MemorySegment.NULL;
		try (Arena arena = Arena.ofConfined()) {
			int status = driver.init();
			if (status != CuResult.SUCCESS) {
				return new Probe(null, "cuInit: " + driver.errorString(status));
			}
			MemorySegment out = arena.allocate(I);
			status = driver.deviceGetCount(out);
			if (status != CuResult.SUCCESS) {
				return new Probe(null, "cuDeviceGetCount: " + driver.errorString(status));
			}
			if (out.get(I, 0) < 1) {
				return new Probe(null, "no CUDA device is present");
			}
			status = driver.deviceGet(out, 0);
			if (status != CuResult.SUCCESS) {
				return new Probe(null, "cuDeviceGet: " + driver.errorString(status));
			}
			device = out.get(I, 0);
			int major = attribute(driver, arena, CudaDriver.ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, device);
			int minor = attribute(driver, arena, CudaDriver.ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, device);
			if (major * 10 + minor < PTX_COMPUTE_CAPABILITY) {
				return new Probe(null, "device compute capability " + major + "." + minor + " is below the compute_"
						+ PTX_COMPUTE_CAPABILITY + " floor the checked-in PTX targets");
			}
			MemorySegment contextOut = arena.allocate(P);
			status = driver.devicePrimaryCtxRetain(contextOut, device);
			if (status != CuResult.SUCCESS) {
				return new Probe(null, "cuDevicePrimaryCtxRetain: " + driver.errorString(status));
			}
			contextRetained = true;
			MemorySegment context = contextOut.get(P, 0);
			status = driver.ctxSetCurrent(context);
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module, "cuCtxSetCurrent: " + driver.errorString(status));
			}
			byte[] ptx = readPtx();
			if (ptx == null) {
				return unwind(driver, device, true, module,
						PTX_RESOURCE + " is missing from the classpath: this build carries no kernels");
			}
			MemorySegment image = arena.allocate(ptx.length + 1L);
			MemorySegment.copy(ptx, 0, image, ValueLayout.JAVA_BYTE, 0, ptx.length);
			MemorySegment moduleOut = arena.allocate(P);
			status = driver.moduleLoadData(moduleOut, image);
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module, "cuModuleLoadData: " + driver.errorString(status));
			}
			module = moduleOut.get(P, 0);
			MemorySegment functionOut = arena.allocate(P);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_F64));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_F64 + ": " + driver.errorString(status));
			}
			MemorySegment f64 = functionOut.get(P, 0);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_F32));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_F32 + ": " + driver.errorString(status));
			}
			MemorySegment f32 = functionOut.get(P, 0);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_BATCHED_F64));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_BATCHED_F64 + ": " + driver.errorString(status));
			}
			MemorySegment batchedF64 = functionOut.get(P, 0);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_BATCHED_F32));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_BATCHED_F32 + ": " + driver.errorString(status));
			}
			MemorySegment batchedF32 = functionOut.get(P, 0);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_MAP_F64));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_MAP_F64 + ": " + driver.errorString(status));
			}
			MemorySegment mapF64 = functionOut.get(P, 0);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_MAP_F32));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_MAP_F32 + ": " + driver.errorString(status));
			}
			MemorySegment mapF32 = functionOut.get(P, 0);
			MemorySegment[] strided = new MemorySegment[KERNELS_STRIDED.length];
			for (int i = 0; i < KERNELS_STRIDED.length; i++) {
				status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNELS_STRIDED[i]));
				if (status != CuResult.SUCCESS) {
					return unwind(driver, device, true, module,
							"cuModuleGetFunction " + KERNELS_STRIDED[i] + ": " + driver.errorString(status));
				}
				strided[i] = functionOut.get(P, 0);
			}
			MemorySegment pool = MemorySegment.NULL;
			boolean pooled = pooledAllocationWorks(driver, arena);
			if (pooled) {
				MemorySegment poolOut = arena.allocate(P);
				pooled = driver.deviceGetDefaultMemPool(poolOut, device) == CuResult.SUCCESS;
				if (pooled) {
					pool = poolOut.get(P, 0);
				}
			}
			int multiprocessors = attribute(driver, arena, CudaDriver.ATTRIBUTE_MULTIPROCESSOR_COUNT, device);
			long ceiling = SYNC_FLOPS_PER_MULTIPROCESSOR * Math.max(1, multiprocessors);
			String description = describe(driver, arena, device) + (pooled ? "" : ", unpooled allocation");
			return new Probe(new CudaGemm(driver, device, context, module, f64, f32, batchedF64, batchedF32, mapF64,
					mapF32, strided, pooled, pool, ceiling, description), description);
		}
		catch (Throwable ex) {
			// Anything at all: a descriptor defect, a JVM that forbids native access, a
			// driver that returned something impossible. The flag is a no-op either way.
			return unwind(driver, device, contextRetained, module,
					"the CUDA driver could not be used: " + describeThrowable(ex));
		}
	}

	/**
	 * Releases whatever the probe had acquired before it failed, so a declining machine
	 * leaks neither a module nor a retained primary context, and returns the decline.
	 */
	private static Probe unwind(CudaDriver driver, int device, boolean contextRetained, MemorySegment module,
			String reason) {
		try {
			if (!module.equals(MemorySegment.NULL)) {
				driver.moduleUnload(module);
			}
			if (contextRetained && device >= 0) {
				// Order matters, and it is not the obvious one: unlike cuCtxDestroy,
				// cuDevicePrimaryCtxRelease does NOT clear the calling thread's current
				// context. Releasing without this leaves the probing thread -- usually
				// main -- holding a dangling CUcontext that any co-resident CUDA consumer
				// would then fail against with CUDA_ERROR_INVALID_CONTEXT.
				driver.ctxSetCurrent(MemorySegment.NULL);
				driver.devicePrimaryCtxRelease(device);
			}
		}
		catch (Throwable ex) {
			// Nothing to do about a failed release on a path that is already declining.
		}
		return new Probe(null, reason);
	}

	/**
	 * A throwable's text, defensively: this is only ever reached on a path that is
	 * already declining, and a {@code toString} that throws must not turn a decline into
	 * a failed class initialization.
	 */
	private static String describeThrowable(Throwable ex) {
		try {
			return String.valueOf(ex);
		}
		catch (Throwable nested) {
			return ex.getClass().getName();
		}
	}

	/**
	 * Whether this driver and device can serve a per-call allocation from the pool the
	 * DRIVER keeps ({@code cuMemAllocAsync}, CUDA 11.2 and a device with memory-pool
	 * support). It is asked by trying it rather than by reading a device attribute,
	 * because a trial answers for the exact pair of driver and card in front of us.
	 *
	 * <p>
	 * The answer matters more than anything else in this class: a plain
	 * {@code cuMemAlloc} / {@code cuMemFree} pair costs 126 us on the machine this was
	 * measured on, three pairs are needed per product, and that alone is ten times the
	 * whole rest of a small intercepted call. The spike this feature is built on quoted a
	 * 16-18 us floor because every one of its probes allocated ONCE and then looped; a
	 * real per-call intercept cannot.
	 */
	private static boolean pooledAllocationWorks(CudaDriver driver, Arena arena) throws Throwable {
		if (!driver.hasPooledAllocation()) {
			return false;
		}
		MemorySegment out = arena.allocate(L);
		if (driver.memAllocAsync(out, 1024) != CuResult.SUCCESS) {
			return false;
		}
		long trial = out.get(L, 0);
		if (driver.memFreeAsync(trial) == CuResult.SUCCESS) {
			return true;
		}
		// The pool would not take it back. Hand it to the plain allocator's free rather
		// than leaking the trial buffer for the life of the process, and use the
		// fallback route from here on.
		driver.memFree(trial);
		return false;
	}

	private static int attribute(CudaDriver driver, Arena arena, int attribute, int device) throws Throwable {
		MemorySegment out = arena.allocate(I);
		return driver.deviceGetAttribute(out, attribute, device) == CuResult.SUCCESS ? out.get(I, 0) : 0;
	}

	private static String describe(CudaDriver driver, Arena arena, int device) throws Throwable {
		MemorySegment name = arena.allocate(256);
		String model = driver.deviceGetName(name, 256, device) == CuResult.SUCCESS ? name.getString(0) : "CUDA device";
		int major = attribute(driver, arena, CudaDriver.ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR, device);
		int minor = attribute(driver, arena, CudaDriver.ATTRIBUTE_COMPUTE_CAPABILITY_MINOR, device);
		int multiprocessors = attribute(driver, arena, CudaDriver.ATTRIBUTE_MULTIPROCESSOR_COUNT, device);
		MemorySegment out = arena.allocate(I);
		int version = driver.driverGetVersion(out) == CuResult.SUCCESS ? out.get(I, 0) : 0;
		return model + " (sm_" + major + minor + ", " + multiprocessors + " SMs, driver API " + version / 1000 + "."
				+ version % 1000 / 10 + ")";
	}

	/**
	 * Records the kernels an embedder carries in place of the resource. Taking effect
	 * requires only that this runs before the probe does, which is the contract
	 * {@link Gpu#useKernels(String)} states; a call afterwards changes nothing, because
	 * the module is already loaded.
	 * @param ptx the PTX text
	 */
	static void embeddedPtx(String ptx) {
		embeddedPtx = ptx.getBytes(StandardCharsets.ISO_8859_1);
	}

	/**
	 * The PTX text this build carries: what an embedder supplied, else the checked-in
	 * resource, else {@code null} when there is neither.
	 */
	private static byte @Nullable [] readPtx() {
		byte[] embedded = embeddedPtx;
		if (embedded != null) {
			return embedded;
		}
		try (InputStream in = CudaGemm.class.getResourceAsStream(PTX_RESOURCE)) {
			return in == null ? null : in.readAllBytes();
		}
		catch (IOException ex) {
			return null;
		}
	}

	/**
	 * What was found: the device model, its architecture and the driver's API version.
	 */
	String description() {
		return this.description;
	}

	/** Whether the context is still usable -- see the sticky-error rule on the class. */
	boolean usable() {
		return this.usable;
	}

	/**
	 * Whether per-call device memory comes from the driver's pool. It decides the size
	 * threshold, because it decides the floor: 15 us a call pooled, 170 us unpooled.
	 */
	boolean pooled() {
		return this.pooled;
	}

	/**
	 * Switches the allocator and answers what it was. Package-private and for the tests:
	 * the fallback route is unreachable on a machine whose driver HAS a pool, and it is a
	 * route that still has to compute the same answers, free its buffers and honour the
	 * threshold. Production never calls this -- {@link #probe()} decides once.
	 * @param wanted whether to allocate from the driver's pool
	 * @return the setting that was in force before this call
	 */
	boolean setPooledAllocation(boolean wanted) {
		boolean previous = this.pooled;
		this.pooled = wanted && !this.memoryPool.equals(MemorySegment.NULL);
		return previous;
	}

	/**
	 * Free device memory in bytes, or {@code -1} when the driver would not say. Exists
	 * for the leak test: a run of products must not move it, because every buffer a
	 * product allocates it also frees.
	 */
	long freeDeviceMemory() {
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return -1;
			}
			return freeDeviceMemory(arena);
		}
		catch (Throwable ex) {
			return -1;
		}
	}

	/**
	 * The same, on a context that is already current and an arena that already exists --
	 * the form the per-call pre-flight uses, where it costs 0.6 us. A driver that will
	 * not answer returns {@code -1}, and the pre-flight then simply does not happen.
	 */
	private long freeDeviceMemory(Arena arena) throws Throwable {
		MemorySegment free = arena.allocate(L), total = arena.allocate(L);
		return this.driver.memGetInfo(free, total) == CuResult.SUCCESS ? free.get(L, 0) : -1;
	}

	/**
	 * {@code c = a x b} for a row-major {@code n x m} by {@code m x p} pair of packed
	 * double-float arrays, each read from its own element offset.
	 * @return {@code true} when {@code c} was filled, {@code false} when the product
	 * declined or the device failed -- in which case {@code c} is untouched
	 */
	boolean gemm(double[] a, int oa, double[] b, int ob, double[] c, int oc, int n, int m, int p) {
		return gemm(a, oa, 0, b, ob, 0, c, oc, 1, n, m, p);
	}

	/**
	 * {@code c = a x b} for a STACK of {@code batch} row-major {@code n x m} by
	 * {@code m x p} products, one launch for the whole stack: {@code blockIdx.z} carries
	 * the batch axis and each slab is the same tile walk {@link #gemm} runs, so a batched
	 * cell folds {@code k} exactly as an unbatched one does.
	 *
	 * <p>
	 * {@code sa} and {@code sb} are per-batch ELEMENT strides and either may be 0, which
	 * is what a BROADCAST operand passes: the whole batch then reads the same slab and
	 * only that slab is copied to the device. {@code c} is always contiguous, {@code n*p}
	 * per batch.
	 * @return {@code true} when {@code c} was filled, {@code false} when the product
	 * declined or the device failed -- in which case {@code c} is untouched
	 */
	boolean gemm(double[] a, int oa, int sa, double[] b, int ob, int sb, double[] c, int oc, int batch, int n, int m,
			int p) {
		if (!this.usable) {
			return false;
		}
		long aBytes = span(batch, sa, (long) n * m) * Double.BYTES,
				bBytes = span(batch, sb, (long) m * p) * Double.BYTES, cBytes = (long) batch * n * p * Double.BYTES;
		long[] buffers = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, aBytes, bBytes, cBytes)) {
				return false;
			}
			boolean sync = 2L * batch * n * m * p >= this.syncFlopCeiling;
			if (!upload(buffers[0], MemorySegment.ofArray(a), (long) oa * Double.BYTES, aBytes)
					|| !upload(buffers[1], MemorySegment.ofArray(b), (long) ob * Double.BYTES, bBytes) || !launch(arena,
							batch == 1 ? this.gemmF64 : this.gemmBatchedF64, buffers, batch, sa, sb, n, m, p, sync)) {
				return false;
			}
			return download(MemorySegment.ofArray(c), (long) oc * Double.BYTES, buffers[2], cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(buffers);
		}
	}

	/**
	 * The single-float sibling of {@link #gemm} -- the width the device is actually good
	 * at, by a factor of 44 on the hardware this was measured on.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean gemmF(float[] a, int oa, float[] b, int ob, float[] c, int oc, int n, int m, int p) {
		return gemmF(a, oa, 0, b, ob, 0, c, oc, 1, n, m, p);
	}

	/**
	 * The single-float sibling of
	 * {@link #gemm(double[], int, int, double[], int, int, double[], int, int, int, int, int)}
	 * -- the stacked product at the width the device is actually good at.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean gemmF(float[] a, int oa, int sa, float[] b, int ob, int sb, float[] c, int oc, int batch, int n, int m,
			int p) {
		if (!this.usable) {
			return false;
		}
		long aBytes = span(batch, sa, (long) n * m) * Float.BYTES, bBytes = span(batch, sb, (long) m * p) * Float.BYTES,
				cBytes = (long) batch * n * p * Float.BYTES;
		long[] buffers = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, aBytes, bBytes, cBytes)) {
				return false;
			}
			boolean sync = 2L * batch * n * m * p >= this.syncFlopCeiling;
			if (!upload(buffers[0], MemorySegment.ofArray(a), (long) oa * Float.BYTES, aBytes)
					|| !upload(buffers[1], MemorySegment.ofArray(b), (long) ob * Float.BYTES, bBytes) || !launch(arena,
							batch == 1 ? this.gemmF32 : this.gemmBatchedF32, buffers, batch, sa, sb, n, m, p, sync)) {
				return false;
			}
			return download(MemorySegment.ofArray(c), (long) oc * Float.BYTES, buffers[2], cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(buffers);
		}
	}

	/**
	 * {@code c[i] = op(a[i])} over {@code n} elements of a packed double-float array,
	 * each read from its own element offset -- the ELEMENT-WISE tier, where the member is
	 * the op code rather than the kernel.
	 *
	 * <p>
	 * Two buffers, not three, and no fold: the whole cost is one pass up, one libm call
	 * per element and one pass back. That is why only the members whose scalar cost IS a
	 * libm call are offered here ({@link Gpu#MAP_EXP} and its siblings) -- an op the CPU
	 * does in one instruction cannot pay for the two copies.
	 * @return {@code true} when {@code c} was filled, {@code false} when the call
	 * declined or the device failed -- in which case {@code c} is untouched
	 */
	boolean map(int op, double[] a, int oa, double[] c, int oc, int n) {
		if (!this.usable) {
			return false;
		}
		long bytes = (long) n * Double.BYTES;
		long[] buffers = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, bytes, bytes)) {
				return false;
			}
			boolean sync = (long) n * MAP_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!upload(buffers[0], MemorySegment.ofArray(a), (long) oa * Double.BYTES, bytes)
					|| !launchMap(arena, this.mapF64, buffers, n, op, sync)) {
				return false;
			}
			return download(MemorySegment.ofArray(c), (long) oc * Double.BYTES, buffers[1], bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(buffers);
		}
	}

	/**
	 * The single-float sibling of {@link #map(int, double[], int, double[], int, int)}.
	 * The kernel computes at the OPERAND width -- {@code expf} and not
	 * {@code (float) exp((double) x)} -- which is the one place it deliberately does not
	 * follow the CPU kernels' widen-compute-narrow rule: an f64 transcendental costs a
	 * consumer card 32-64x an f32 one, so following the rule would turn the width the
	 * hardware is for into the slower of the two. The divergence that buys is measured in
	 * {@code .kb/gpu.md}'s precision table.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean mapF(int op, float[] a, int oa, float[] c, int oc, int n) {
		if (!this.usable) {
			return false;
		}
		long bytes = (long) n * Float.BYTES;
		long[] buffers = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, bytes, bytes)) {
				return false;
			}
			boolean sync = (long) n * MAP_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!upload(buffers[0], MemorySegment.ofArray(a), (long) oa * Float.BYTES, bytes)
					|| !launchMap(arena, this.mapF32, buffers, n, op, sync)) {
				return false;
			}
			return download(MemorySegment.ofArray(c), (long) oc * Float.BYTES, buffers[1], bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(buffers);
		}
	}

	// --- the strided tier ------------------------------------------------------------
	// Four buffers for a broadcast binary op, three for a gather, two for a fold: the
	// operands, the result, and -- for the first two -- a small int buffer holding the
	// output dims followed by one source stride per output axis per operand. That fourth
	// buffer is 3 * rank ints, so it costs one pooled allocation (0.7-2.3 us) and a copy
	// of at most 192 bytes; passing the layout as a by-value kernel parameter would save
	// that and cost a second parameter-packing shape, which is not a trade worth making
	// at these sizes.

	/**
	 * {@code out[i] = op(a[ia(i)], b[ib(i)])} over a BROADCAST binary element-wise op:
	 * the output is {@code dims} row-major and each operand follows its own per-axis
	 * stride, 0 where it is stretched. Bit-identical to a scalar widen-compute-narrow
	 * walk at both widths -- the kernel computes in double and narrows only on the store.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean bcast(int op, double[] a, int oa, int[] sa, double[] b, int ob, int[] sb, double[] c, int oc, int[] dims) {
		return bcast(op, MemorySegment.ofArray(a), oa, sa, MemorySegment.ofArray(b), ob, sb, MemorySegment.ofArray(c),
				oc, dims, Double.BYTES, this.strided[BCAST_F64]);
	}

	/**
	 * The single-float sibling of
	 * {@link #bcast(int, double[], int, int[], double[], int, int[], double[], int, int[])}.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean bcastF(int op, float[] a, int oa, int[] sa, float[] b, int ob, int[] sb, float[] c, int oc, int[] dims) {
		return bcast(op, MemorySegment.ofArray(a), oa, sa, MemorySegment.ofArray(b), ob, sb, MemorySegment.ofArray(c),
				oc, dims, Float.BYTES, this.strided[BCAST_F32]);
	}

	/**
	 * One width-independent broadcast round trip; the two public forms differ only in it.
	 */
	private boolean bcast(int op, MemorySegment a, int oa, int[] sa, MemorySegment b, int ob, int[] sb, MemorySegment c,
			int oc, int[] dims, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int rank = dims.length;
		int n = count(dims);
		long aBytes = (span(dims, sa) + 1L) * width, bBytes = (span(dims, sb) + 1L) * width, cBytes = (long) n * width,
				metaBytes = 3L * rank * Integer.BYTES;
		long[] buffers = { 0, 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, aBytes, bBytes, cBytes, metaBytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!upload(buffers[0], a, (long) oa * width, aBytes) || !upload(buffers[1], b, (long) ob * width, bBytes)
					|| !uploadLayout(arena, buffers[3], dims, sa, sb)
					|| !launchStrided(arena, kernel, n,
							new long[] { op, buffers[0], buffers[1], buffers[2], n, rank, buffers[3] },
							new boolean[] { false, true, true, true, false, false, true }, sync)) {
				return false;
			}
			return download(c, (long) oc * width, buffers[2], cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(buffers);
		}
	}

	/**
	 * {@code out[i] = a[ia(i)]}: the permuted COPY behind an axes transpose, one source
	 * stride per output axis. A copy, so trivially bit-identical.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean gather(double[] a, int oa, int[] sa, double[] c, int oc, int[] dims) {
		return gather(MemorySegment.ofArray(a), oa, sa, MemorySegment.ofArray(c), oc, dims, Double.BYTES,
				this.strided[GATHER_F64]);
	}

	/**
	 * The single-float sibling of
	 * {@link #gather(double[], int, int[], double[], int, int[])}.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean gatherF(float[] a, int oa, int[] sa, float[] c, int oc, int[] dims) {
		return gather(MemorySegment.ofArray(a), oa, sa, MemorySegment.ofArray(c), oc, dims, Float.BYTES,
				this.strided[GATHER_F32]);
	}

	private boolean gather(MemorySegment a, int oa, int[] sa, MemorySegment c, int oc, int[] dims, int width,
			MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int rank = dims.length;
		int n = count(dims);
		long aBytes = (span(dims, sa) + 1L) * width, cBytes = (long) n * width, metaBytes = 2L * rank * Integer.BYTES;
		long[] buffers = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, aBytes, cBytes, metaBytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!upload(buffers[0], a, (long) oa * width, aBytes) || !uploadLayout(arena, buffers[2], dims, sa, null)
					|| !launchStrided(arena, kernel, n, new long[] { buffers[0], buffers[1], n, rank, buffers[2] },
							new boolean[] { true, true, false, false, true }, sync)) {
				return false;
			}
			return download(c, (long) oc * width, buffers[1], cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(buffers);
		}
	}

	/**
	 * The fold of one axis: {@code outer * inner} output cells, each walking its own
	 * {@code len} elements in ASCENDING order in a double accumulator. Sequential per
	 * cell on purpose -- a tree reduction would be faster and would not be the caller's
	 * sum.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean fold(int op, double[] a, int oa, double[] c, int oc, int outer, int len, int inner) {
		return fold(op, MemorySegment.ofArray(a), oa, MemorySegment.ofArray(c), oc, outer, len, inner, Double.BYTES,
				this.strided[FOLD_F64]);
	}

	/**
	 * The single-float sibling of
	 * {@link #fold(int, double[], int, double[], int, int, int, int)}.
	 * @return {@code true} when {@code c} was filled
	 */
	boolean foldF(int op, float[] a, int oa, float[] c, int oc, int outer, int len, int inner) {
		return fold(op, MemorySegment.ofArray(a), oa, MemorySegment.ofArray(c), oc, outer, len, inner, Float.BYTES,
				this.strided[FOLD_F32]);
	}

	private boolean fold(int op, MemorySegment a, int oa, MemorySegment c, int oc, int outer, int len, int inner,
			int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int cells = outer * inner;
		long aBytes = (long) cells * len * width, cBytes = (long) cells * width;
		long[] buffers = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, aBytes, cBytes)) {
				return false;
			}
			boolean sync = (long) cells * len * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!upload(buffers[0], a, (long) oa * width, aBytes) || !launchStrided(arena, kernel, cells,
					new long[] { op, buffers[0], buffers[1], outer, len, inner },
					new boolean[] { false, true, true, false, false, false }, sync)) {
				return false;
			}
			return download(c, (long) oc * width, buffers[1], cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(buffers);
		}
	}

	/** The output element count of a strided shape; the caller has already bounded it. */
	private static int count(int[] dims) {
		int n = 1;
		for (int d : dims) {
			n *= d;
		}
		return n;
	}

	/** The highest element index a stride vector reaches over {@code dims}. */
	private static long span(int[] dims, int[] stride) {
		long span = 0;
		for (int k = 0; k < dims.length; k++) {
			span += (long) (dims[k] - 1) * stride[k];
		}
		return span;
	}

	/**
	 * Copies the layout -- the output dims, then one source stride per output axis per
	 * operand -- into the small device buffer the strided kernels index it out of. Plain
	 * ints, so a rank-8 broadcast is 96 bytes.
	 */
	private boolean uploadLayout(Arena arena, long destination, int[] dims, int[] sa, int @Nullable [] sb)
			throws Throwable {
		int rank = dims.length;
		int words = rank * (sb == null ? 2 : 3);
		MemorySegment host = arena.allocate(I, words);
		for (int k = 0; k < rank; k++) {
			host.setAtIndex(I, k, dims[k]);
			host.setAtIndex(I, rank + k, sa[k]);
			if (sb != null) {
				host.setAtIndex(I, 2 * rank + k, sb[k]);
			}
		}
		int status = this.driver.memcpyHtoD(destination, host, (long) words * Integer.BYTES);
		return status == CuResult.SUCCESS || fail(status);
	}

	/**
	 * One flat launch over {@code n} output cells, one thread each. The parameter block
	 * is described by two parallel arrays -- the values, and whether each is a device
	 * POINTER (8 bytes) or an {@code int} (4) -- because the three strided kernels take
	 * three different mixtures of the two.
	 */
	private boolean launchStrided(Arena arena, MemorySegment function, int n, long[] values, boolean[] pointer,
			boolean sync) throws Throwable {
		MemorySegment parameters = arena.allocate(P, values.length);
		for (int i = 0; i < values.length; i++) {
			MemorySegment slot = arena.allocate(pointer[i] ? L : I);
			if (pointer[i]) {
				slot.set(L, 0, values[i]);
			}
			else {
				slot.set(I, 0, (int) values[i]);
			}
			parameters.setAtIndex(P, i, slot);
		}
		int status = this.driver.launchKernel(function, (n + STRIDED_BLOCK - 1) / STRIDED_BLOCK, 1, 1, STRIDED_BLOCK, 1,
				1, 0, MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		if (sync) {
			status = this.driver.ctxSynchronize();
			if (status != CuResult.SUCCESS) {
				return fail(status);
			}
		}
		return true;
	}

	/**
	 * How many elements of an operand one launch spans: the last batch's slab plus
	 * everything the stride skipped over on the way to it. A broadcast operand (stride 0)
	 * spans ONE slab however long the batch is, which is why only that slab is copied.
	 */
	private static long span(int batch, int stride, long matrix) {
		return (long) (batch - 1) * stride + matrix;
	}

	/** Whether this shape can be launched at all: the grid's row axis is 16 bits. */
	static boolean launchable(long n, long m, long p) {
		return launchable(1, n, m, p);
	}

	/**
	 * The same for a STACK: the batch rides on {@code gridDim.z}, which is 16 bits too,
	 * and the whole result has to be one Java array.
	 */
	static boolean launchable(long batch, long n, long m, long p) {
		return batch > 0 && batch <= MAX_GRID_Y && n > 0 && m > 0 && p > 0 && n <= Integer.MAX_VALUE
				&& m <= Integer.MAX_VALUE && p <= Integer.MAX_VALUE && batch * n * p <= Integer.MAX_VALUE
				&& (n + TILE - 1) / TILE <= MAX_GRID_Y;
	}

	/**
	 * The three device buffers, or a decline that costs the device NOTHING.
	 *
	 * <h4>Why a failed allocation needs more care than a successful one</h4>
	 *
	 * A pooled allocation that fails grows the driver's pool as far as it can on the way
	 * to failing, and returns no pointer -- so there is nothing for {@link #release} to
	 * give back, and the pool keeps the high-water mark for the life of the PROCESS and
	 * against every other CUDA process on the card. Measured before this was handled: one
	 * declined 80 GB product took a 128 GB device from 69 GB free to 1 GB free, and it
	 * never came back. A decline that costs 68 GB is the exact opposite of the invariant
	 * this library is sold on, so two things guard it: the total is checked against free
	 * memory FIRST, which stops the pool ever growing for a product that cannot fit, and
	 * a failure trims the pool back afterwards, which covers the race between the check
	 * and the allocation.
	 */
	private boolean allocate(Arena arena, long[] buffers, long... sizes) throws Throwable {
		MemorySegment out = arena.allocate(L);
		long total = 0;
		for (long size : sizes) {
			total += size;
		}
		long free = freeDeviceMemory(arena);
		if (free >= 0 && total > free - ALLOCATION_HEADROOM) {
			return false;
		}
		for (int i = 0; i < sizes.length; i++) {
			int status = this.pooled ? this.driver.memAllocAsync(out, sizes[i]) : this.driver.memAlloc(out, sizes[i]);
			if (status != CuResult.SUCCESS) {
				// Order matters, and getting it wrong is silent: the buffers that DID
				// allocate have to go back to the pool before the pool is trimmed, or the
				// trim finds them still in use and keeps their memory. Measured with the
				// two swapped, a declined product held 78 GB of a 128 GB device.
				release(buffers);
				trimMemoryPool();
				return fail(status);
			}
			buffers[i] = out.get(L, 0);
		}
		return true;
	}

	/**
	 * Hands the driver's pool back everything it is holding and nothing is using. Called
	 * only after a failed allocation -- on the success path the pool is exactly the
	 * optimization that makes a per-call intercept affordable, and trimming it would
	 * throw that away.
	 *
	 * <p>
	 * The {@code cuCtxSynchronize} is load-bearing and its absence is silent.
	 * {@code cuMemFreeAsync} is STREAM-ordered: the buffers this call is trying to give
	 * back are not in the pool yet, only queued to be, so a trim issued before the stream
	 * has reached that point finds them still in use and keeps their memory. Measured
	 * without it, a declined product held 78 GB of a 128 GB device; with it, the same
	 * product costs nothing.
	 */
	private void trimMemoryPool() {
		if (!this.pooled || this.memoryPool.equals(MemorySegment.NULL)) {
			return;
		}
		try {
			this.driver.ctxSynchronize();
			this.driver.memPoolTrimTo(this.memoryPool, 0);
		}
		catch (Throwable ex) {
			// Already declining; there is nothing further to try.
		}
	}

	/**
	 * Copies one operand up, straight off the Java heap and in chunks of at most
	 * {@link #CRITICAL_CHUNK_BYTES}. There is no host copy at any size: the chunk is what
	 * bounds the critical window, so a big operand costs one extra downcall per 64 MB
	 * rather than a staging buffer.
	 */
	private boolean upload(long destination, MemorySegment heap, long offset, long bytes) throws Throwable {
		for (long done = 0; done < bytes; done += CRITICAL_CHUNK_BYTES) {
			long chunk = Math.min(CRITICAL_CHUNK_BYTES, bytes - done);
			int status = this.driver.memcpyHtoD(destination + done, heap.asSlice(offset + done, chunk), chunk);
			if (status != CuResult.SUCCESS) {
				return fail(status);
			}
		}
		return true;
	}

	/** The mirror of {@link #upload}, for the result. */
	private boolean download(MemorySegment heap, long offset, long source, long bytes) throws Throwable {
		for (long done = 0; done < bytes; done += CRITICAL_CHUNK_BYTES) {
			long chunk = Math.min(CRITICAL_CHUNK_BYTES, bytes - done);
			int status = this.driver.memcpyDtoH(heap.asSlice(offset + done, chunk), source + done, chunk);
			if (status != CuResult.SUCCESS) {
				return fail(status);
			}
		}
		return true;
	}

	/**
	 * One 16x16-tiled launch over the whole output -- {@code gridDim.z} is the batch, so
	 * a stack of products is still ONE launch -- plus, for a product long enough that it
	 * would matter, the explicit wait that keeps the kernel's runtime out of the
	 * following critical copy.
	 */
	private boolean launch(Arena arena, MemorySegment function, long[] buffers, int batch, long sa, long sb, int n,
			int m, int p, boolean sync) throws Throwable {
		MemorySegment a = arena.allocate(L), b = arena.allocate(L), c = arena.allocate(L);
		a.set(L, 0, buffers[0]);
		b.set(L, 0, buffers[1]);
		c.set(L, 0, buffers[2]);
		MemorySegment rows = arena.allocate(I), columns = arena.allocate(I), inner = arena.allocate(I);
		rows.set(I, 0, n);
		columns.set(I, 0, p);
		inner.set(I, 0, m);
		// A single product is the plain kernel with the parameter block it has always
		// had; only a stack pays for the two stride parameters.
		boolean batched = batch > 1;
		MemorySegment parameters = arena.allocate(P, batched ? 8 : 6);
		parameters.setAtIndex(P, 0, a);
		parameters.setAtIndex(P, 1, b);
		parameters.setAtIndex(P, 2, c);
		parameters.setAtIndex(P, 3, rows);
		parameters.setAtIndex(P, 4, columns);
		parameters.setAtIndex(P, 5, inner);
		if (batched) {
			MemorySegment strideA = arena.allocate(L), strideB = arena.allocate(L);
			strideA.set(L, 0, sa);
			strideB.set(L, 0, sb);
			parameters.setAtIndex(P, 6, strideA);
			parameters.setAtIndex(P, 7, strideB);
		}
		int status = this.driver.launchKernel(function, (p + TILE - 1) / TILE, (n + TILE - 1) / TILE, batch, TILE, TILE,
				1, 0, MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		if (sync) {
			status = this.driver.ctxSynchronize();
			if (status != CuResult.SUCCESS) {
				return fail(status);
			}
		}
		return true;
	}

	/**
	 * One flat launch over {@code n} elements, one thread each, plus the explicit wait
	 * where the kernel is long enough for it to matter. The op code rides in the
	 * parameter block exactly as the dimensions do.
	 */
	private boolean launchMap(Arena arena, MemorySegment function, long[] buffers, int n, int op, boolean sync)
			throws Throwable {
		MemorySegment a = arena.allocate(L), c = arena.allocate(L);
		a.set(L, 0, buffers[0]);
		c.set(L, 0, buffers[1]);
		MemorySegment count = arena.allocate(I), which = arena.allocate(I);
		count.set(I, 0, n);
		which.set(I, 0, op);
		MemorySegment parameters = arena.allocate(P, 4);
		parameters.setAtIndex(P, 0, a);
		parameters.setAtIndex(P, 1, c);
		parameters.setAtIndex(P, 2, count);
		parameters.setAtIndex(P, 3, which);
		int status = this.driver.launchKernel(function, (n + MAP_BLOCK - 1) / MAP_BLOCK, 1, 1, MAP_BLOCK, 1, 1, 0,
				MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		if (sync) {
			status = this.driver.ctxSynchronize();
			if (status != CuResult.SUCCESS) {
				return fail(status);
			}
		}
		return true;
	}

	/** Frees every device buffer that was allocated, on the failure path too. */
	private void release(long[] buffers) {
		for (int i = 0; i < buffers.length; i++) {
			if (buffers[i] != 0) {
				try {
					if (this.pooled) {
						this.driver.memFreeAsync(buffers[i]);
					}
					else {
						this.driver.memFree(buffers[i]);
					}
				}
				catch (Throwable ex) {
					// A free that fails means the context is already gone; the sticky
					// rule below has retired the feature, and there is nothing to undo.
				}
				buffers[i] = 0;
			}
		}
	}

	/**
	 * Records a failed status and answers {@code false} so a call site can
	 * {@code return fail(status)}. A sticky status retires the feature for the rest of
	 * the process.
	 */
	private boolean fail(int status) {
		if (CuResult.isSticky(status)) {
			this.usable = false;
		}
		return false;
	}

}
