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
 * checked-in PTX, and the two tiled GEMM kernels it exports. Everything that can fail
 * fails into a decline; nothing here throws.
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
	 * The tile the kernel is written around, and therefore its block shape: 16x16
	 * threads, one output element each.
	 */
	private static final int TILE = 16;

	/**
	 * {@code gridDim.y} and {@code gridDim.z} are 16-bit on every CUDA device, so a
	 * product with more than {@code 65535 * TILE} rows cannot be launched in one go and
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
			MemorySegment gemmF32, boolean pooled, MemorySegment memoryPool, long syncFlopCeiling, String description) {
		this.driver = driver;
		this.device = device;
		this.context = context;
		this.module = module;
		this.gemmF64 = gemmF64;
		this.gemmF32 = gemmF32;
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
			return new Probe(
					new CudaGemm(driver, device, context, module, f64, f32, pooled, pool, ceiling, description),
					description);
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
		if (!this.usable) {
			return false;
		}
		long aBytes = (long) n * m * Double.BYTES, bBytes = (long) m * p * Double.BYTES,
				cBytes = (long) n * p * Double.BYTES;
		long[] buffers = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, aBytes, bBytes, cBytes)) {
				return false;
			}
			boolean sync = 2L * n * m * p >= this.syncFlopCeiling;
			if (!upload(buffers[0], MemorySegment.ofArray(a), (long) oa * Double.BYTES, aBytes)
					|| !upload(buffers[1], MemorySegment.ofArray(b), (long) ob * Double.BYTES, bBytes)
					|| !launch(arena, this.gemmF64, buffers, n, m, p, sync)) {
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
		if (!this.usable) {
			return false;
		}
		long aBytes = (long) n * m * Float.BYTES, bBytes = (long) m * p * Float.BYTES,
				cBytes = (long) n * p * Float.BYTES;
		long[] buffers = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return false;
			}
			if (!allocate(arena, buffers, aBytes, bBytes, cBytes)) {
				return false;
			}
			boolean sync = 2L * n * m * p >= this.syncFlopCeiling;
			if (!upload(buffers[0], MemorySegment.ofArray(a), (long) oa * Float.BYTES, aBytes)
					|| !upload(buffers[1], MemorySegment.ofArray(b), (long) ob * Float.BYTES, bBytes)
					|| !launch(arena, this.gemmF32, buffers, n, m, p, sync)) {
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

	/** Whether this shape can be launched at all: the grid's row axis is 16 bits. */
	static boolean launchable(long n, long m, long p) {
		return n > 0 && m > 0 && p > 0 && n <= Integer.MAX_VALUE && m <= Integer.MAX_VALUE && p <= Integer.MAX_VALUE
				&& n * p <= Integer.MAX_VALUE && (n + TILE - 1) / TILE <= MAX_GRID_Y;
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
	 * One 16x16-tiled launch over the whole output, plus -- for a product long enough
	 * that it would matter -- the explicit wait that keeps the kernel's runtime out of
	 * the following critical copy.
	 */
	private boolean launch(Arena arena, MemorySegment function, long[] buffers, int n, int m, int p, boolean sync)
			throws Throwable {
		MemorySegment a = arena.allocate(L), b = arena.allocate(L), c = arena.allocate(L);
		a.set(L, 0, buffers[0]);
		b.set(L, 0, buffers[1]);
		c.set(L, 0, buffers[2]);
		MemorySegment rows = arena.allocate(I), columns = arena.allocate(I), inner = arena.allocate(I);
		rows.set(I, 0, n);
		columns.set(I, 0, p);
		inner.set(I, 0, m);
		MemorySegment parameters = arena.allocate(P, 6);
		parameters.setAtIndex(P, 0, a);
		parameters.setAtIndex(P, 1, b);
		parameters.setAtIndex(P, 2, c);
		parameters.setAtIndex(P, 3, rows);
		parameters.setAtIndex(P, 4, columns);
		parameters.setAtIndex(P, 5, inner);
		int status = this.driver.launchKernel(function, (p + TILE - 1) / TILE, (n + TILE - 1) / TILE, 1, TILE, TILE, 1,
				0, MemorySegment.NULL, parameters, MemorySegment.NULL);
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
