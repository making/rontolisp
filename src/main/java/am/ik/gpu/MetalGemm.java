package am.ik.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import static am.ik.gpu.MetalDriver.F;
import static am.ik.gpu.MetalDriver.I;
import static am.ik.gpu.MetalDriver.L;

/**
 * The Apple half of {@code --gpu}: one {@code MTLDevice}, one command queue, one library
 * compiled from {@code gemm.metal} at run time, and the kernels it exports -- a STACKED
 * matrix product, an element-wise map, the strided broadcast/gather pair, the GEMV behind
 * {@code vec:matvec}, and since {@code .todo/494} the resident tier (the equal-shape and
 * scalar binary ops, {@code where}, the Adam step, the strided copy and the axis fold) --
 * plus the rank-2 product, which goes through {@code MPSMatrixMultiplication} instead.
 * Everything that can fail fails into a decline; nothing here throws but
 * {@link #materialize}, which cannot.
 *
 * <h2>Single float, or nothing</h2>
 *
 * MSL rejects {@code double} outright, so {@link #supportsDouble()} is {@code false} and
 * every double-taking method here answers {@code false} without touching the device. That
 * is not a gap to fill later: there is no fp64 on this hardware to fill it with. It makes
 * the decline protocol load-bearing in a way it is not on CUDA -- {@code linalg}'s
 * default width reaches this backend only after {@code torch:}'s single-float default or
 * an explicit {@code #f} array. Where a member's CPU twin computes in double and the
 * float arithmetic cannot land on its bits -- a scalar that is not a float, the Adam
 * step, the sum fold -- {@code gemm.metal} runs binary64 in software, so the resident
 * tier is bit-identical to the CPU kernels here exactly as it is on CUDA.
 *
 * <h2>The kernels compile at run time, from a string</h2>
 *
 * {@code newLibraryWithSource:options:error:} is the whole toolchain: the MSL compiler is
 * in the OS, so unlike the CUDA half there is no generated artifact to check in beside
 * the source and nothing to pin to a virtual architecture. Measured 32 ms the first time
 * a given text is ever seen on a machine and 2-3 ms on every later process, because the
 * OS caches it the way the NVIDIA driver caches PTX.
 *
 * <h2>This class owns a buffer pool, and the CUDA half does not</h2>
 *
 * {@code CudaGemm} allocates per call because the DRIVER has a stream-ordered pool behind
 * {@code cuMemAllocAsync}. Metal has no such thing: a fresh {@code MTLBuffer} is cheap to
 * create (1-8 us) but its pages fault in on the first write, which measured 60 us on a
 * small product and 4.3 ms on a 4 MB one. So the buffers here are size-classed and
 * reused.
 *
 * <h2>Residency on top of the pool: two modes, and a measurement between them</h2>
 *
 * Eagerly (the library's default), every result comes home before the call returns, and
 * this half keeps ONE kind of array resident: the matrix of an accepted {@link #gemvF}.
 * That is todo-477's measurement: on unified memory an upload is a memcpy into the slab's
 * {@code contents} -- 1.5 MB in ~75 us -- while a slab held out of the pool for a
 * resident copy costs the pool a FRESH slab for the next call of that size, and a fresh
 * slab pays its first-touch page faults (~1 us a page). With every result still coming
 * home, keeping every operand and result resident was 1-5% SLOWER than the pure pool at
 * every cap tried. Every other slab is SCRATCH: fully overwritten on the way in, fully
 * read on the way out, recycled the moment the call ends.
 *
 * <p>
 * Lazily ({@link #lazyResults}), a member's result is NOT copied home: its slab goes into
 * the same {@link DeviceResidency} as the host array's DIRTY copy, the next member finds
 * it there, and the bytes move only when something on the host reads the array
 * ({@link #materialize}) -- which is what makes a chain move nothing and what turns the
 * members a round trip had refused (the resident tier) into launches with no copy. Every
 * operand a call uploads is kept too, as a clean copy. The slabs the cache holds come
 * back to the pool at the two safe moments -- the start of a call, before any operand is
 * looked up, and the end of one, after the command buffer has completed -- and a dirty
 * copy the cache lets go of (the LRU, a release, a replacement) is downloaded first,
 * never dropped. The mode is built, bit-identical and pinned, and an embedder that asks
 * for it gets it -- but the INTERCEPTORS do not ask for it here ({@link #lazyResultsPay}
 * is {@code false}): measured on the training step it is a tie at the notebook's shapes
 * and a loss of a third to a half at the book's, because every call still waits for its
 * command buffer, the CPU's lane loop streams memory about as fast as the device does on
 * this machine, and a resident set of tens of gigabytes beside the host arrays it mirrors
 * puts the machine under memory pressure ({@code .kb/gpu.md}, "Lazy results and the
 * resident tier on Metal", which also says what would change the answer: asynchronous
 * command buffers, and {@code .todo/492}).
 *
 * @see Gpu
 * @see MetalDriver
 * @see DeviceResidency
 */
final class MetalGemm implements GpuDevice {

	/** The MSL source, beside this class in the resources. */
	static final String KERNEL_RESOURCE = "gemm.metal";

	/**
	 * The kernels {@link #KERNEL_RESOURCE} must export. The rank-2 product is absent on
	 * purpose: MPS serves it.
	 */
	static final String KERNEL_BATCHED_F32 = "gemm_batched_f32", KERNEL_MAP_F32 = "map_f32",
			KERNEL_BCAST_F32 = "bcast_f32", KERNEL_GATHER_F32 = "gather_f32", KERNEL_GEMV_F32 = "gemv_f32";

	/** The resident tier's kernels ({@code .todo/494}), in the order of {@link #tier}. */
	static final String[] KERNELS_RESIDENT = { "zip_f32", "scal_f32", "where_f32", "adam_f32", "copy_f32", "fold_f32" };

	private static final int ZIP = 0, SCAL = 1, WHERE = 2, ADAM = 3, COPY = 4, FOLD = 5;

	/**
	 * The kernels an EMBEDDER supplied, when this library's classes travel without its
	 * resources -- see {@link Gpu#useMetalKernels(String)}.
	 */
	private static volatile @Nullable String embeddedSource;

	/**
	 * The minimum {@code batch * n * m * p} a matrix product is accepted at:
	 * {@code 2^22}, a 166x166x166 product. Measured on an M4 Max against {@code --simd}
	 * on the JVM at f32, us per call: n=96 85 against ~135, n=128 178 against 144, n=192
	 * 571 against ~150, n=256 1288 against 166. So the crossover is just under n=128 and
	 * 2^22 is where the margin (2.6x) is past the noise -- the same "not where it first
	 * appears" rule the CUDA threshold follows, sitting 32x higher because the floor
	 * does.
	 */
	private static final long MIN_WORK = 1L << 22;

	/**
	 * The minimum element count an element-wise map is accepted at: {@code 2^17}.
	 * Measured on an M4 Max at f32, us per call, CPU against device: the cheapest member
	 * taken ({@code sin}) is 45 against ~110 at 16384 and ~380 against ~180 at 131072,
	 * {@code exp} 3x there and {@code erf} 20x. Eight times the CUDA threshold, for the
	 * same reason the product's is 32 times it.
	 */
	private static final long MIN_MAP_ELEMENTS = 1L << 17;

	/**
	 * The minimum OUTPUT element count a broadcast or a gather is accepted at:
	 * {@code 2^18}. Measured on an M4 Max at f32: a broadcast {@code sub} is 455 us
	 * against ~260 at 262144 and 727 against ~300 at the transformer's own
	 * {@code (4 256 384) - (4 256 1)}, and an axes transpose 357 against ~180 at
	 * {@code (4 256 192)}.
	 */
	private static final long MIN_STRIDED_ELEMENTS = 1L << 18;

	/**
	 * The minimum {@code rows * cols} a matrix-by-vector product is accepted at, once its
	 * matrix is resident: {@code 2^21}, just over a 1448x1448 matrix. Measured on an M4
	 * Max at f32 against the JIT-warm {@code --simd} lane kernel, us per call, with the
	 * matrix resident ({@code .todo/123-gpu-acceleration/MtlMatvecCrossover.java} against
	 * {@code matvec-baseline.lisp}): 1024x1024 is 100 CPU against 90 device -- a tie,
	 * because a resident call is the ~77 us command-buffer floor plus a memory-bound
	 * kernel that does not reach it until the matrix is several megabytes -- 1448x1448 is
	 * 233 against 93 (2.5x), 1536x1536 267 against 94 (2.8x; ~115 median in the shipped
	 * route, 2.3x), 2048x2048 500 against 105 (4.8x), and llama2's 32000x288 classifier
	 * head 800 against 185 (4.3x). Sixteen times the CUDA threshold, for the floor's
	 * sake; the cold trip -- the matrix copied in every call -- never pays here at any
	 * size (753 us at the head, against the CPU's 800), which is why the two-sight rule
	 * of {@link #gemvF} is not optional on this backend either. And one thing the
	 * threshold cannot see: this GPU lowers its clocks after ~1 ms idle, and the first
	 * command buffer after such a gap pays ~0.5 ms more, so a GEMV called once every few
	 * milliseconds (a decode loop) wins far less than a back-to-back one
	 * ({@code .kb/gpu.md}, "Residency and the GEMV on this backend").
	 */
	private static final long MIN_MATVEC_ELEMENTS = 1L << 21;

	/**
	 * The minimum element count a member is accepted at when it is offered for its
	 * RESIDENT operand rather than for its size -- the resident tier, and every
	 * size-thresholded member below its own threshold. A launch with no copy still pays
	 * this backend's per-command-buffer floor (~100 us through the shipped route,
	 * whatever the size, until 2^18 elements), and the CPU's alternative over a resident
	 * operand is a memcpy of it plus a lane loop, which crosses it between 2^18 and 2^19
	 * -- yet the training step measured fastest with the floor HERE, lower than that,
	 * because a declined member costs a materialize, the CPU loop and the re-upload of
	 * its result around it ({@code .kb/gpu.md}, "Lazy results and the resident tier on
	 * Metal").
	 */
	static final long MIN_RESIDENT_ELEMENTS = 1L << 14;

	/**
	 * Where {@code MPSMatrixMultiplication} takes over from {@link #KERNEL_BATCHED_F32}
	 * for ONE matrix of the product: {@code 2^27}, a 512x512x512 product. Below it the
	 * tiled kernel is ahead (166 us against 180 at n=256) because MPS costs ~35 us of
	 * object churn a call; above it MPS wins outright -- 202 against 308 at n=512, 523
	 * against 1545 at n=1024, 2264 against 10183 at n=2048.
	 */
	private static final long MPS_MIN_WORK = 1L << 27;

	/**
	 * Threads per threadgroup for the flat kernels: the map, the broadcast, the gather,
	 * the resident tier.
	 */
	private static final int FLAT_GROUP = 256;

	/**
	 * Threads per threadgroup for the GEMV: one SIMD-group per row, so this many divided
	 * by the pipeline's {@code threadExecutionWidth} rows per threadgroup.
	 */
	private static final int GEMV_GROUP = 256;

	/** The tile {@code gemm_batched_f32} is written around, and so its group shape. */
	private static final int TILE = 16;

	/**
	 * The smallest slab the pool hands out. A rank-8 layout buffer is 96 bytes and a
	 * one-element operand is 4, so without a floor the pool would fill with classes that
	 * exist only to be distinct.
	 */
	private static final int MIN_SLAB_BYTES = 4096;

	/** The largest slab class, {@code 2^36} = 64 GB -- past any Apple device today. */
	private static final int MAX_SLAB_CLASS = 36;

	/**
	 * What fraction of the device's recommended working set the pool may hold. The rest
	 * is for the program's own textures and buffers, and for the OS: a compute library
	 * that fills the working set is one that makes the machine unusable while it runs.
	 */
	private static final long POOL_BUDGET_DIVISOR = 4;

	/**
	 * Eagerly: what fraction of the POOL's budget the resident set -- the GEMV matrices
	 * -- may hold, and the cap on it: {@code min(pool / 4, 1 GB)}, the CUDA half's own
	 * cap. A resident slab is one the pool cannot recycle, so the bound is what keeps a
	 * program that offers many distinct matrices from turning the pool into fresh pages;
	 * a model whose weights exceed it keeps its most recently used ones.
	 */
	private static final long RESIDENT_SHARE = 4, RESIDENT_CAP = 1L << 30;

	/**
	 * Lazily: the POOL may hold the whole recommended working set less a headroom of an
	 * eighth (never less than {@link #LAZY_HEADROOM_FLOOR}), and the resident set the
	 * pool's budget less an eighth of THAT, which is what the scratch slabs of one call
	 * and the free lists live in. The CUDA half's lazy rule is the same shape over free
	 * device memory; here the pool and the resident set compete for the same slabs, so
	 * the headroom is taken off the pool. The quarter share of the eager pool is not
	 * enough once results live on the device: a training step at the book's shapes holds
	 * tens of gigabytes of activations reachable until its backward, and a budget below
	 * that flushed them as fast as they were made -- measured, 195 GB of flushes over 13
	 * steps and a step a third slower than the pure pool ({@code .kb/gpu.md}, "Lazy
	 * results and the resident tier on Metal"), the trap {@code .todo/491} hit at 1 GB on
	 * CUDA.
	 */
	private static final long LAZY_HEADROOM_SHARE = 8, LAZY_HEADROOM_FLOOR = 512L << 20;

	private final MetalDriver driver;

	private final MemorySegment device;

	private final MemorySegment queue;

	private final MemorySegment library;

	private final MemorySegment gemmBatched;

	private final MemorySegment map;

	private final MemorySegment bcast;

	private final MemorySegment gather;

	private final MemorySegment gemv;

	/** The resident tier's pipelines, indexed by {@link #ZIP} and its siblings. */
	private final MemorySegment[] tier;

	/** The GEMV pipeline's SIMD-group width, which is how many threads share one row. */
	private final int gemvWidth;

	private final String description;

	private final long workingSet;

	/**
	 * What the pool may hold in all, free or held: a quarter of the working set eagerly
	 * ({@link #POOL_BUDGET_DIVISOR}), the working set less a headroom of an eighth
	 * lazily, when the resident copies are the program's own live data and live in it
	 * ({@link #LAZY_HEADROOM_SHARE}). Re-derived by {@link #lazyResults}.
	 */
	private volatile long poolBudget;

	/**
	 * Free slabs by size class, {@code free[k]} holding buffers of {@code 1 << k} bytes.
	 */
	private final ArrayDeque<Slab>[] free;

	/**
	 * Every slab this pool has minted and not released, free or held, in bytes of
	 * capacity.
	 */
	private long pooledBytes;

	/**
	 * The resident copies: host array -> the address of the slab holding its elements
	 * ({@link DeviceResidency}), and {@link #held} is the slab behind each such address.
	 * Both are kept in step under {@code this}: a slab is put into {@link #held} before
	 * the cache learns its address, and removed from it only when the cache has handed
	 * the address back through {@link DeviceResidency#drain()}.
	 */
	private final DeviceResidency residency = new DeviceResidency();

	private final Map<Long, Slab> held = new HashMap<>();

	/** Whether results stay on the device until the host reads them. */
	private volatile boolean lazy;

	/** A test-imposed residency budget, or -1 for the derived one. */
	private volatile long residentBudgetOverride = -1;

	/**
	 * Whether a big enough matrix goes to MPS. Not final: the tiled kernel is otherwise
	 * UNREACHABLE for any shape above {@link #MPS_MIN_WORK} on a machine that has MPS,
	 * which is every machine that has Metal -- and it is a path that has to keep
	 * computing the same answers. Flipped only by {@code MetalGpuTest.withoutMps}, the
	 * exact counterpart of {@code GpuTest}'s allocator-route test.
	 */
	private volatile boolean mps = true;

	@SuppressWarnings("unchecked")
	private MetalGemm(MetalDriver driver, MemorySegment device, MemorySegment queue, MemorySegment library,
			MemorySegment[] kernels, int gemvWidth, String description, long workingSet) {
		this.driver = driver;
		this.device = device;
		this.queue = queue;
		this.library = library;
		this.gemmBatched = kernels[0];
		this.map = kernels[1];
		this.bcast = kernels[2];
		this.gather = kernels[3];
		this.gemv = kernels[4];
		this.tier = java.util.Arrays.copyOfRange(kernels, 5, kernels.length);
		this.gemvWidth = gemvWidth;
		this.description = description;
		this.workingSet = workingSet;
		this.free = new ArrayDeque[MAX_SLAB_CLASS + 1];
		this.poolBudget = derivedPoolBudget();
		this.residency.setBudget(derivedResidentBudget());
	}

	/**
	 * The outcome of a probe: the usable device, or {@code null} and the reason there is
	 * none. There is always a reason, and it is always printable.
	 *
	 * @param gemm the opened device, or {@code null} when this machine has none
	 * @param description what was found, or why nothing was
	 */
	record Probe(@Nullable MetalGemm gemm, String description) {
	}

	/**
	 * Asks this machine, once, whether it can run the kernels: the frameworks bind, a
	 * default device exists, a command queue opens, the MSL compiles and every kernel
	 * resolves. Any failure answers "no" with a reason and leaves nothing acquired.
	 * @return the device, or the reason there is none
	 */
	static Probe probe() {
		MetalDriver driver;
		try {
			driver = MetalDriver.open();
		}
		catch (Throwable ex) {
			return new Probe(null, "Metal could not be bound: " + describeThrowable(ex));
		}
		if (driver == null) {
			return new Probe(null, "Metal.framework is not present: this machine is not a Mac with a GPU");
		}
		MemorySegment pool = MemorySegment.NULL;
		// Everything acquired so far, so that EVERY exit below -- and there are eight --
		// unwinds it. A machine that declines at the last step must not leave a command
		// queue, an MSL library and eleven pipeline states behind, which is the same rule
		// CudaGemm.unwind follows for a retained primary context.
		MemorySegment queue = MemorySegment.NULL;
		MemorySegment library = MemorySegment.NULL;
		String[] names = { KERNEL_BATCHED_F32, KERNEL_MAP_F32, KERNEL_BCAST_F32, KERNEL_GATHER_F32, KERNEL_GEMV_F32,
				KERNELS_RESIDENT[ZIP], KERNELS_RESIDENT[SCAL], KERNELS_RESIDENT[WHERE], KERNELS_RESIDENT[ADAM],
				KERNELS_RESIDENT[COPY], KERNELS_RESIDENT[FOLD] };
		MemorySegment[] kernels = new MemorySegment[names.length];
		boolean keep = false;
		try {
			pool = driver.autoreleasePoolPush();
			MemorySegment device = driver.systemDefaultDevice();
			if (device.address() == 0) {
				return new Probe(null, "MTLCreateSystemDefaultDevice found no Metal device");
			}
			queue = driver.message(device, "newCommandQueue");
			if (queue.address() == 0) {
				return new Probe(null, "newCommandQueue failed on " + name(driver, device));
			}
			String source = readSource();
			if (source == null) {
				return new Probe(null, "the Metal kernels (" + KERNEL_RESOURCE + ") are not on the classpath");
			}
			MemorySegment options = compileOptions(driver);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment error = arena.allocate(MetalDriver.P);
				error.set(MetalDriver.P, 0, MemorySegment.NULL);
				library = driver.message(device, "newLibraryWithSource:options:error:", driver.nsString(source),
						options, error);
				String failure = library.address() != 0 ? null
						: driver.fromNsString(driver.message(error.get(MetalDriver.P, 0), "localizedDescription"));
				release(driver, options);
				if (failure != null) {
					return new Probe(null, "the Metal kernels did not compile: " + failure);
				}
			}
			for (int i = 0; i < names.length; i++) {
				MemorySegment state = pipeline(driver, device, library, names[i]);
				if (state == null) {
					return new Probe(null, "the Metal kernel " + names[i] + " could not be built");
				}
				kernels[i] = state;
			}
			if (driver.objcClass("MPSMatrixMultiplication").address() == 0) {
				return new Probe(null, "MetalPerformanceShaders is present but has no MPSMatrixMultiplication");
			}
			// The GEMV shares one SIMD-group per row, so the host has to know the width
			// the kernel was compiled to rather than assume the 32 of every Apple GPU.
			long width = driver.messageLong(kernels[4], "threadExecutionWidth");
			if (width <= 0 || width > GEMV_GROUP || (width & (width - 1)) != 0) {
				return new Probe(null, "the Metal GEMV kernel has an unusable SIMD-group width " + width);
			}
			long workingSet = driver.messageLong(device, "recommendedMaxWorkingSetSize");
			boolean unified = driver.respondsTo(device, "hasUnifiedMemory")
					&& driver.messageBool(device, "hasUnifiedMemory");
			String description = name(driver, device) + " (Metal, " + (unified ? "unified memory, " : "")
					+ (workingSet >> 30) + " GB working set)";
			keep = true;
			return new Probe(
					new MetalGemm(driver, device, queue, library, kernels, (int) width, description, workingSet),
					description);
		}
		catch (Throwable ex) {
			return new Probe(null, "the Metal device could not be probed: " + describeThrowable(ex));
		}
		finally {
			if (!keep) {
				for (MemorySegment kernel : kernels) {
					if (kernel != null) {
						release(driver, kernel);
					}
				}
				release(driver, library);
				release(driver, queue);
			}
			pop(driver, pool);
		}
	}

	/**
	 * {@code MTLMathModeSafe}, or {@code setFastMathEnabled:NO} on an OS whose
	 * {@code MTLCompileOptions} predates it. Not a preference: the relaxed default
	 * flushes denormals and reassociates, and the strided tier claims bit-identity with
	 * the scalar defun.
	 */
	private static MemorySegment compileOptions(MetalDriver driver) throws Throwable {
		MemorySegment options = driver.message(driver.message(driver.objcClass("MTLCompileOptions"), "alloc"), "init");
		if (options.address() == 0) {
			return MemorySegment.NULL;
		}
		if (driver.respondsTo(options, "setMathMode:")) {
			driver.messageVoid(options, "setMathMode:", MetalDriver.MATH_MODE_SAFE);
		}
		else if (driver.respondsTo(options, "setFastMathEnabled:")) {
			driver.messageVoid(options, "setFastMathEnabled:", false);
		}
		return options;
	}

	private static @Nullable MemorySegment pipeline(MetalDriver driver, MemorySegment device, MemorySegment library,
			String name) throws Throwable {
		MemorySegment function = driver.message(library, "newFunctionWithName:", driver.nsString(name));
		if (function.address() == 0) {
			return null;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment error = arena.allocate(MetalDriver.P);
			error.set(MetalDriver.P, 0, MemorySegment.NULL);
			MemorySegment state = driver.message(device, "newComputePipelineStateWithFunction:error:", function, error);
			release(driver, function);
			return state.address() == 0 ? null : state;
		}
	}

	private static String name(MetalDriver driver, MemorySegment device) throws Throwable {
		return driver.fromNsString(driver.message(device, "name"));
	}

	private static void release(MetalDriver driver, MemorySegment object) {
		try {
			if (object.address() != 0) {
				driver.messageVoid(object, "release");
			}
		}
		catch (Throwable ignored) {
			// An object that cannot be released is not a reason to fail a probe that was
			// already failing.
		}
	}

	private static void pop(MetalDriver driver, MemorySegment pool) {
		try {
			if (pool.address() != 0) {
				driver.autoreleasePoolPop(pool);
			}
		}
		catch (Throwable ignored) {
		}
	}

	private static String describeThrowable(Throwable ex) {
		try {
			return String.valueOf(ex);
		}
		catch (Throwable nested) {
			return ex.getClass().getName();
		}
	}

	/** See {@link Gpu#useMetalKernels(String)}. */
	static void embeddedSource(String source) {
		embeddedSource = source;
	}

	private static @Nullable String readSource() {
		String embedded = embeddedSource;
		if (embedded != null) {
			return embedded;
		}
		try (InputStream in = MetalGemm.class.getResourceAsStream(KERNEL_RESOURCE)) {
			return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	@Override
	public String description() {
		return this.description;
	}

	/**
	 * Always {@code true} once the probe has answered. There is no Metal counterpart of a
	 * sticky {@code CUresult}: a command buffer that ends in any status but
	 * {@code Completed} is an ordinary per-call decline, and nothing this class can
	 * observe leaves the device permanently unusable. A field that is never written would
	 * be worse than saying so.
	 * @return {@code true}
	 */
	@Override
	public boolean usable() {
		return true;
	}

	/** Always {@code false}: MSL has no {@code double}. */
	@Override
	public boolean supportsDouble() {
		return false;
	}

	/** Whether a matrix at or above {@link #MPS_MIN_WORK} goes to MPS. For the tests. */
	boolean mpsEnabled() {
		return this.mps;
	}

	/**
	 * Routes big products through the tiled kernel instead of MPS, or back. Answers what
	 * was in force before. Package-private and for the test that runs both routes over
	 * one shape; nothing in the feature ever calls it.
	 * @param wanted whether MPS should serve a big enough matrix
	 * @return the previous setting
	 */
	boolean setMps(boolean wanted) {
		boolean previous = this.mps;
		this.mps = wanted;
		return previous;
	}

	/** The per-matrix work at which MPS takes over. For the test that spans it. */
	static long mpsMinWork() {
		return MPS_MIN_WORK;
	}

	/**
	 * The axis fold's threshold is {@code Long.MAX_VALUE}: it is never offered for its
	 * size on this backend (the measured refusal, {@code .kb/gpu.md}), only over a
	 * resident operand. The generator fill is not a member at all.
	 */
	@Override
	public Thresholds thresholds() {
		return new Thresholds(MIN_WORK, MIN_MAP_ELEMENTS, MIN_STRIDED_ELEMENTS, Long.MAX_VALUE, Long.MAX_VALUE,
				MIN_MATVEC_ELEMENTS);
	}

	/**
	 * What the device's recommended working set has left. Metal reports the ALLOCATED
	 * size rather than the free size, so this is the recommendation less what this
	 * process holds -- which is what the leak tests need and all they need.
	 */
	@Override
	public long freeDeviceMemory() {
		try {
			return this.workingSet - this.driver.messageLong(this.device, "currentAllocatedSize");
		}
		catch (Throwable ex) {
			return 0;
		}
	}

	// --- the double half: a hard decline, one line each
	// ---------------------------------

	@Override
	public boolean gemm(double[] a, int oa, double[] b, int ob, double[] c, int oc, int n, int m, int p) {
		return false;
	}

	@Override
	public boolean gemm(double[] a, int oa, int sa, double[] b, int ob, int sb, double[] c, int oc, int batch, int n,
			int m, int p) {
		return false;
	}

	@Override
	public boolean map(int op, double[] a, int oa, double[] c, int oc, int n) {
		return false;
	}

	/**
	 * The generator fill is not a member here: it writes {@code double} draws, and this
	 * backend has no {@code double} (and its threshold is {@code Long.MAX_VALUE}, so the
	 * question is never asked). Both widths decline.
	 */
	@Override
	public boolean rngFill(double[] c, int oc, int n, int mode, double lo, double span, int s1, int s2, int s3) {
		return false;
	}

	@Override
	public boolean rngFillF(float[] c, int oc, int n, int mode, double lo, double span, int s1, int s2, int s3) {
		return false;
	}

	@Override
	public boolean bcast(int op, double[] a, int oa, int[] sa, double[] b, int ob, int[] sb, double[] c, int oc,
			int[] dims) {
		return false;
	}

	@Override
	public boolean gather(double[] a, int oa, int[] sa, double[] c, int oc, int[] dims) {
		return false;
	}

	@Override
	public boolean fold(int op, double[] a, int oa, double[] c, int oc, int outer, int len, int inner) {
		return false;
	}

	/** The matrix-by-vector product at {@code double} is a hard decline like the rest. */
	@Override
	public boolean gemv(double[] w, int ow, double[] x, int ox, double[] y, int oy, int rows, int cols) {
		return false;
	}

	@Override
	public boolean zip(int op, double[] a, int oa, double[] b, int ob, double[] c, int oc, int n) {
		return false;
	}

	@Override
	public boolean scale(int op, double[] a, int oa, double s, boolean swap, double[] c, int oc, int n) {
		return false;
	}

	@Override
	public boolean where(@Nullable Object m, int om, int[] sm, double ms, double @Nullable [] x, int ox, int[] sx,
			double xs, double @Nullable [] y, int oy, int[] sy, double ys, double[] c, int oc, int[] dims) {
		return false;
	}

	@Override
	public boolean adamStep(double[] x, int ox, double[] g, int og, double[] m, int om, double[] v, int ov, int n,
			double[] rule) {
		return false;
	}

	@Override
	public boolean copy(double[] a, int oa, int[] sa, int spanOa, int spanNa, double[] c, int oc, int[] sc, int spanOc,
			int spanNc, int[] dims) {
		return false;
	}

	/**
	 * The index tier and the clip norm are CUDA-only. Both halves exist to keep a lazy
	 * result from coming home, and this backend does not run lazily -- on unified memory
	 * the copy home is a memcpy, which measured a tie at the notebook's shapes and a loss
	 * at the book's ({@code .kb/gpu.md}, "Lazy results and the resident tier on Metal")
	 * -- so there is nothing here for them to save.
	 */
	@Override
	public boolean take(int mode, double[] a, int oa, int lenA, double[] c, int oc, int[] idx, int n, int slab) {
		return false;
	}

	@Override
	public boolean takeF(int mode, float[] a, int oa, int lenA, float[] c, int oc, int[] idx, int n, int slab) {
		return false;
	}

	@Override
	public boolean scatter(double[] z, int oz, double[] g, int og, int[] meta, int rows, int slab, int m) {
		return false;
	}

	@Override
	public boolean scatterF(float[] z, int oz, float[] g, int og, int[] meta, int rows, int slab, int m) {
		return false;
	}

	@Override
	public double @Nullable [] sumSquares(double[] a, int oa, int n) {
		return null;
	}

	@Override
	public double @Nullable [] sumSquaresF(float[] a, int oa, int n) {
		return null;
	}

	// --- the single-float half ---------------------------------------------------------

	@Override
	public boolean gemmF(float[] a, int oa, float[] b, int ob, float[] c, int oc, int n, int m, int p) {
		return gemmF(a, oa, 0, b, ob, 0, c, oc, 1, n, m, p);
	}

	/**
	 * {@code c = a x b} for a STACK of {@code batch} row-major {@code n x m} by
	 * {@code m x p} products, with per-batch ELEMENT strides on each operand -- 0 for a
	 * broadcast one, in which case only that operand's single slab is copied.
	 *
	 * <p>
	 * Which kernel runs is a size question and not a shape one: a matrix at or above
	 * {@link #MPS_MIN_WORK} goes to {@code MPSMatrixMultiplication}, one encode per slab
	 * into ONE command buffer, and anything smaller to the tiled kernel, one dispatch for
	 * the whole stack. The two agree bit for bit -- measured over a rectangular 37x23x19
	 * product, 0 of 703 cells differ -- so the choice is invisible in the results.
	 * @return {@code true} when {@code c} was filled (or, lazily, left resident),
	 * {@code false} when the product declined or the device failed -- in which case
	 * {@code c} is untouched
	 */
	@Override
	public boolean gemmF(float[] a, int oa, int sa, float[] b, int ob, int sb, float[] c, int oc, int batch, int n,
			int m, int p) {
		long aElements = span(batch, sa, (long) n * m), bElements = span(batch, sb, (long) m * p),
				cElements = (long) batch * n * p;
		if (!acceptable((long) batch * n * m * p, MIN_WORK, cElements, a, b)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(3);
			call.lookup(0, a, oa, aElements);
			call.lookup(1, b, ob, bElements);
			if (!call.ensure(0, aElements) || !call.ensure(1, bElements) || !call.ensure(2, cElements)) {
				return false;
			}
			call.stage(0, a, oa, aElements, false);
			call.stage(1, b, ob, bElements, false);
			boolean ran = this.mps && (long) n * m * p >= MPS_MIN_WORK
					? multiplyThroughMps(call.slabs, sa, sb, batch, n, m, p)
					: dispatchGemm(call.slabs, sa, sb, batch, n, m, p);
			if (!ran) {
				return false;
			}
			call.finish(2, c, oc, cElements);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/** One dispatch of the tiled kernel over the whole stack. */
	private boolean dispatchGemm(Slab[] slabs, int sa, int sb, int batch, int n, int m, int p) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment args = arena.allocate(I, 5);
			args.setAtIndex(I, 0, n);
			args.setAtIndex(I, 1, p);
			args.setAtIndex(I, 2, m);
			args.setAtIndex(I, 3, sa);
			args.setAtIndex(I, 4, sb);
			MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
			MemorySegment encoder = beginEncoder(commands, this.gemmBatched);
			this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[0].buffer, 0, 0);
			this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[1].buffer, 0, 1);
			this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[2].buffer, 0, 2);
			this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 5L * Integer.BYTES, 3);
			this.driver.dispatch(encoder, MetalDriver.size(arena, (p + TILE - 1) / TILE, (n + TILE - 1) / TILE, batch),
					MetalDriver.size(arena, TILE, TILE, 1));
			this.driver.messageVoid(encoder, "endEncoding");
			return commitAndWait(commands);
		}
	}

	/**
	 * One {@code MPSMatrixMultiplication} encoded per slab into ONE command buffer. The
	 * per-slab loop is what lets a broadcast operand keep its zero stride, which is the
	 * shape every {@code torch:linear} over a {@code (B T C)} activation has and which a
	 * batched {@code MPSMatrixDescriptor} cannot be handed. Metal's floor is per command
	 * buffer rather than per dispatch, so the encodes share the one wait.
	 */
	private boolean multiplyThroughMps(Slab[] slabs, int sa, int sb, int batch, int n, int m, int p) throws Throwable {
		MemorySegment left = this.driver.matrixDescriptor(n, m, (long) m * Float.BYTES);
		MemorySegment right = this.driver.matrixDescriptor(m, p, (long) p * Float.BYTES);
		MemorySegment result = this.driver.matrixDescriptor(n, p, (long) p * Float.BYTES);
		if (left.address() == 0 || right.address() == 0 || result.address() == 0) {
			return false;
		}
		MemorySegment multiplication = this.driver.matrixMultiplication(this.device, n, p, m);
		if (multiplication.address() == 0) {
			return false;
		}
		MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
		try {
			for (int z = 0; z < batch; z++) {
				MemorySegment ma = this.driver.matrix(slabs[0].buffer, (long) z * sa * Float.BYTES, left);
				MemorySegment mb = this.driver.matrix(slabs[1].buffer, (long) z * sb * Float.BYTES, right);
				MemorySegment mc = this.driver.matrix(slabs[2].buffer, (long) z * n * p * Float.BYTES, result);
				try {
					if (ma.address() == 0 || mb.address() == 0 || mc.address() == 0) {
						return false;
					}
					this.driver.messageVoid(multiplication,
							"encodeToCommandBuffer:leftMatrix:rightMatrix:resultMatrix:", commands, ma, mb, mc);
				}
				finally {
					release(this.driver, ma);
					release(this.driver, mb);
					release(this.driver, mc);
				}
			}
			return commitAndWait(commands);
		}
		finally {
			release(this.driver, multiplication);
		}
	}

	/**
	 * {@code c[i] = op(a[i])} over {@code n} elements -- the element-wise tier, where the
	 * member is the op code rather than the kernel. A libm member at or above the
	 * threshold, or ANY member (the resident tier's four included) over a resident
	 * operand.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean mapF(int op, float[] a, int oa, float[] c, int oc, int n) {
		if (!acceptable(op < Gpu.MAP_LIBM_OPS ? n : 0, MIN_MAP_ELEMENTS, n, a)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(2);
			call.lookup(0, a, oa, n);
			if (!call.ensure(0, n) || !call.ensure(1, n)) {
				return false;
			}
			call.stage(0, a, oa, n, false);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 2);
				args.setAtIndex(I, 0, n);
				args.setAtIndex(I, 1, op);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.map);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", call.slabs[0].buffer, 0, 0);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", call.slabs[1].buffer, 0, 1);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 2);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(1, c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * {@code out[i] = op(a[ia(i)], b[ib(i)])} over a BROADCAST binary element-wise op.
	 * Bit-identical to the scalar defun: see {@code gemm.metal}, which argues the case
	 * rather than inheriting the CUDA half's compute-in-double rule, because there is no
	 * double here to compute in.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean bcastF(int op, float[] a, int oa, int[] sa, float[] b, int ob, int[] sb, float[] c, int oc,
			int[] dims) {
		int rank = dims.length;
		int n = count(dims);
		long aElements = span(dims, sa) + 1, bElements = span(dims, sb) + 1;
		if (!acceptable(n, MIN_STRIDED_ELEMENTS, n, a, b)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(4);
			call.lookup(0, a, oa, aElements);
			call.lookup(1, b, ob, bElements);
			if (!call.ensure(0, aElements) || !call.ensure(1, bElements) || !call.ensure(2, n)
					|| !call.ensureBytes(3, 3L * rank * Integer.BYTES)) {
				return false;
			}
			call.stage(0, a, oa, aElements, false);
			call.stage(1, b, ob, bElements, false);
			uploadLayout(call.slabs[3], dims, sa, sb, null);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 3);
				args.setAtIndex(I, 0, op);
				args.setAtIndex(I, 1, n);
				args.setAtIndex(I, 2, rank);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.bcast);
				bind(encoder, call.slabs, 0, 1, 2, 3);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 3L * Integer.BYTES, 4);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(2, c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * {@code out[i] = a[ia(i)]}: the permuted COPY behind an axes transpose. A copy, so
	 * trivially bit-identical.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean gatherF(float[] a, int oa, int[] sa, float[] c, int oc, int[] dims) {
		int rank = dims.length;
		int n = count(dims);
		long aElements = span(dims, sa) + 1;
		if (!acceptable(n, MIN_STRIDED_ELEMENTS, n, a)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(3);
			call.lookup(0, a, oa, aElements);
			if (!call.ensure(0, aElements) || !call.ensure(1, n) || !call.ensureBytes(2, 2L * rank * Integer.BYTES)) {
				return false;
			}
			call.stage(0, a, oa, aElements, false);
			uploadLayout(call.slabs[2], dims, sa, null, null);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 2);
				args.setAtIndex(I, 0, n);
				args.setAtIndex(I, 1, rank);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.gather);
				bind(encoder, call.slabs, 0, 1, 2);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 3);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(1, c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * The axis fold, over a RESIDENT operand only: {@link #thresholds} says
	 * {@code Long.MAX_VALUE}, so {@link Gpu} never offers it for its size here -- the
	 * measured refusal stands for the round trip -- and offers it over an operand that is
	 * already on the device, where the trip the refusal measured is not paid and the
	 * alternative is bringing the operand home for the CPU fold. The sum accumulates in
	 * software binary64 ({@code gemm.metal}) and so lands on {@code %la-fold-axis}'s
	 * bits; amax / amin move bits.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean foldF(int op, float[] a, int oa, float[] c, int oc, int outer, int len, int inner) {
		long n = (long) outer * inner * len;
		int cells = outer * inner;
		if (!acceptable(0, Long.MAX_VALUE, n, a)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(2);
			call.lookup(0, a, oa, n);
			if (!call.ensure(0, n) || !call.ensure(1, cells)) {
				return false;
			}
			call.stage(0, a, oa, n, false);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 4);
				args.setAtIndex(I, 0, op);
				args.setAtIndex(I, 1, outer);
				args.setAtIndex(I, 2, len);
				args.setAtIndex(I, 3, inner);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.tier[FOLD]);
				bind(encoder, call.slabs, 0, 1);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 4L * Integer.BYTES, 2);
				flatDispatch(arena, encoder, cells);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(1, c, oc, cells);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * {@code y = W x}, the GEMV behind {@code vec:matvec} -- and the one member of this
	 * class whose accept-or-decline is a question of RESIDENCY rather than of size, for
	 * the reason {@code CudaGemm.gemv} gives, stronger here: a matrix-by-vector product
	 * is one pass over {@code W}, and on this backend copying {@code W} in is a memcpy of
	 * the very bytes the CPU kernel would have streamed, so the cold trip loses at EVERY
	 * size (753 us at llama2's 32000x288 head against the CPU's 800, and the floor below
	 * that). Over a resident matrix the call is the ~77 us command-buffer floor plus a
	 * read at the device's own bandwidth, and it is ahead from
	 * {@link #MIN_MATVEC_ELEMENTS}. So the rule is the CUDA half's: <b>a matrix is taken
	 * when it is resident, or when it has been offered once before and not written
	 * since</b> -- the second offer uploads it, every later one finds it there, and a
	 * matrix the program rewrites between calls is offered "for the first time" every
	 * time and never pays for a trip it would lose. The first sight is a decline that
	 * takes no slab, and the mark it leaves is a residency entry with no buffer
	 * ({@link DeviceResidency#offeredBefore}), so {@link #written} clears it exactly as
	 * it would clear a copy. The matrix is kept resident in BOTH modes (eagerly, it is
	 * the only array that is); {@code x} and {@code y} follow the mode like every other
	 * operand and result.
	 *
	 * <p>
	 * The accumulator is COMPENSATED rather than a double ({@code gemm.metal}): a
	 * float-float pair carrying ~48 bits, which lands on the scalar defun's
	 * double-accumulated bits on 1024 of 1024 rows measured -- the same contract the CUDA
	 * kernel's double earns, without the width MSL does not have.
	 * @return {@code true} when {@code y} was filled, {@code false} when the call
	 * declined or the device failed -- in which case {@code y} is untouched
	 */
	@Override
	public boolean gemvF(float[] w, int ow, float[] x, int ox, float[] y, int oy, int rows, int cols) {
		long wElements = (long) rows * cols;
		if (wElements < MIN_MATVEC_ELEMENTS) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(3);
			// The two-sight rule above: a matrix seen for the first time is declined and
			// marked, nothing taken; seen again unwritten, it is copied in below.
			if (call.lookup(0, w, ow, wElements) == null
					&& !this.residency.offeredBefore(w, (long) ow * Float.BYTES, wElements * Float.BYTES)) {
				return false;
			}
			call.lookup(1, x, ox, cols);
			if (!call.ensure(0, wElements) || !call.ensure(1, cols) || !call.ensure(2, rows)) {
				return false;
			}
			call.stage(0, w, ow, wElements, true);
			call.stage(1, x, ox, cols, false);
			if (!dispatchGemv(call.slabs, rows, cols)) {
				return false;
			}
			call.finish(2, y, oy, rows);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * One dispatch of the GEMV: one SIMD-group per row, {@link #GEMV_GROUP} threads a
	 * group.
	 */
	private boolean dispatchGemv(Slab[] slabs, int rows, int cols) throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment args = arena.allocate(I, 2);
			args.setAtIndex(I, 0, rows);
			args.setAtIndex(I, 1, cols);
			int rowsPerGroup = GEMV_GROUP / this.gemvWidth;
			MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
			MemorySegment encoder = beginEncoder(commands, this.gemv);
			bind(encoder, slabs, 0, 1, 2);
			this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 3);
			this.driver.dispatch(encoder, MetalDriver.size(arena, (rows + rowsPerGroup - 1) / rowsPerGroup, 1, 1),
					MetalDriver.size(arena, GEMV_GROUP, 1, 1));
			this.driver.messageVoid(encoder, "endEncoding");
			return commitAndWait(commands);
		}
	}

	// --- the resident tier (.todo/494) -------------------------------------------------
	// Offered by Gpu only once an operand is resident, and declined here below
	// MIN_RESIDENT_ELEMENTS, where the command-buffer floor loses to a memcpy and a lane
	// loop. Each is the ordinary shape -- look up, take what is missing, stage, launch,
	// finish -- and each lands on the CPU kernel's bits (gemm.metal says how).

	/**
	 * {@code c[i] = op(a[i], b[i])} over two operands of the same shape.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean zipF(int op, float[] a, int oa, float[] b, int ob, float[] c, int oc, int n) {
		if (!acceptable(0, Long.MAX_VALUE, n, a, b)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(3);
			call.lookup(0, a, oa, n);
			call.lookup(1, b, ob, n);
			if (!call.ensure(0, n) || !call.ensure(1, n) || !call.ensure(2, n)) {
				return false;
			}
			call.stage(0, a, oa, n, false);
			call.stage(1, b, ob, n, false);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 2);
				args.setAtIndex(I, 0, op);
				args.setAtIndex(I, 1, n);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.tier[ZIP]);
				bind(encoder, call.slabs, 0, 1, 2);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 3);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(2, c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * {@code c[i] = op(a[i], s)} -- or {@code op(s, a[i])} when {@code swap} -- over a
	 * DOUBLE scalar whatever the array's width, which is the CPU kernel's contract too. A
	 * scalar that is exactly a float takes the float path; any other takes the
	 * software-binary64 path ({@code gemm.metal}); both are the CPU's bits. In place (the
	 * destination IS the operand, as {@code %la-scale} writes it) over a resident operand
	 * the kernel reads and writes the one slab, which is then marked dirty.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean scaleF(int op, float[] a, int oa, double s, boolean swap, float[] c, int oc, int n) {
		if (!acceptable(0, Long.MAX_VALUE, n, a)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(2);
			call.lookup(0, a, oa, n);
			if (c == a && oc == oa && call.slabs[0] != null) {
				call.share(1, 0);
			}
			if (!call.ensure(0, n) || !call.ensure(1, n)) {
				return false;
			}
			call.stage(0, a, oa, n, false);
			try (Arena arena = Arena.ofConfined()) {
				boolean exact = (double) (float) s == s || Double.isNaN(s);
				MemorySegment args = arena.allocate(I, 4);
				args.setAtIndex(I, 0, op);
				args.setAtIndex(I, 1, n);
				args.setAtIndex(I, 2, swap ? 1 : 0);
				args.setAtIndex(I, 3, exact ? 1 : 0);
				MemorySegment scalar = arena.allocate(L, 2);
				scalar.setAtIndex(L, 0, Double.doubleToRawLongBits(s));
				scalar.setAtIndex(L, 1, Float.floatToRawIntBits((float) s) & 0xffffffffL);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.tier[SCAL]);
				bind(encoder, call.slabs, 0, 1);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 4L * Integer.BYTES, 2);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", scalar, 2L * Long.BYTES, 3);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(1, c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * {@code c = where(m, x, y)} over three operands broadcast to {@code dims}, any of
	 * which may be a scalar. The mask may be a {@code float[]} or a scalar; a
	 * {@code double[]} mask is a hard decline like every double operand here. The value
	 * scalars are narrowed on the host exactly as the CPU kernel narrows them.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean whereF(@Nullable Object m, int om, int[] sm, double ms, float @Nullable [] x, int ox, int[] sx,
			double xs, float @Nullable [] y, int oy, int[] sy, double ys, float[] c, int oc, int[] dims) {
		if (m instanceof double[]) {
			return false;
		}
		float[] mask = m instanceof float[] f ? f : null;
		int rank = dims.length;
		int n = count(dims);
		if (!acceptable(0, Long.MAX_VALUE, n, mask, x, y)) {
			return false;
		}
		long mElements = mask == null ? 0 : span(dims, sm) + 1, xElements = x == null ? 0 : span(dims, sx) + 1,
				yElements = y == null ? 0 : span(dims, sy) + 1;
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			// Slots: 0 mask, 1 x, 2 y, 3 result, 4 layout. A scalar operand's slot stays
			// null and the result slab is bound in its place, never read.
			call = new Call(5);
			if (mask != null) {
				call.lookup(0, mask, om, mElements);
			}
			if (x != null) {
				call.lookup(1, x, ox, xElements);
			}
			if (y != null) {
				call.lookup(2, y, oy, yElements);
			}
			if ((mask != null && !call.ensure(0, mElements)) || (x != null && !call.ensure(1, xElements))
					|| (y != null && !call.ensure(2, yElements)) || !call.ensure(3, n)
					|| !call.ensureBytes(4, 4L * rank * Integer.BYTES)) {
				return false;
			}
			if (mask != null) {
				call.stage(0, mask, om, mElements, false);
			}
			if (x != null) {
				call.stage(1, x, ox, xElements, false);
			}
			if (y != null) {
				call.stage(2, y, oy, yElements, false);
			}
			uploadLayout(call.slabs[4], dims, sm, sx, sy);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 6);
				args.setAtIndex(I, 0, n);
				args.setAtIndex(I, 1, rank);
				args.setAtIndex(I, 2, mask != null ? 1 : 0);
				args.setAtIndex(I, 3, x != null ? 1 : 0);
				args.setAtIndex(I, 4, y != null ? 1 : 0);
				args.setAtIndex(I, 5, ms != 0.0 ? 1 : 0);
				MemorySegment scalars = arena.allocate(F, 2);
				scalars.setAtIndex(F, 0, (float) xs);
				scalars.setAtIndex(F, 1, (float) ys);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.tier[WHERE]);
				Slab result = call.slabs[3];
				for (int i = 0; i < 5; i++) {
					Slab slab = call.slabs[i] != null ? call.slabs[i] : result;
					this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slab.buffer, 0, i);
				}
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 6L * Integer.BYTES, 5);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", scalars, 2L * Float.BYTES, 6);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(3, c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * Adam's fused update in place over the parameter, its gradient and the two moments:
	 * the parameter and the moments are WRITTEN on the device and (lazily) marked dirty,
	 * so a model's weights, once resident, never come home until the host reads them.
	 * Every operation is software binary64 ({@code gemm.metal}), so the three land on the
	 * CPU kernel's bits. The rule is {@code [lr, lr*wd, wd, b1, 1-b1, b2, 1-b2, eps,
	 * c1, c2, mode]}.
	 * @return {@code true} when the update ran
	 */
	@Override
	public boolean adamStepF(float[] x, int ox, float[] g, int og, float[] m, int om, float[] v, int ov, int n,
			double[] rule) {
		if (!acceptable(0, Long.MAX_VALUE, n, x, g, m, v)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(4);
			call.lookup(0, x, ox, n);
			call.lookup(1, g, og, n);
			call.lookup(2, m, om, n);
			call.lookup(3, v, ov, n);
			if (!call.ensure(0, n) || !call.ensure(1, n) || !call.ensure(2, n) || !call.ensure(3, n)) {
				return false;
			}
			call.stage(0, x, ox, n, false);
			call.stage(1, g, og, n, false);
			call.stage(2, m, om, n, false);
			call.stage(3, v, ov, n, false);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 2);
				args.setAtIndex(I, 0, n);
				args.setAtIndex(I, 1, (int) rule[10]);
				MemorySegment bits = arena.allocate(L, 10);
				for (int i = 0; i < 10; i++) {
					bits.setAtIndex(L, i, Double.doubleToRawLongBits(rule[i]));
				}
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.tier[ADAM]);
				bind(encoder, call.slabs, 0, 1, 2, 3);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 4);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", bits, 10L * Long.BYTES, 5);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			// Three arrays were written in place: each stays resident as the
			// authoritative copy (lazily) or comes home now (eagerly).
			call.finish(0, x, ox, n);
			call.finish(2, m, om, n);
			call.finish(3, v, ov, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * The strided copy: {@code c[oc + sc.i] = a[oa + sa.i]} over {@code dims}, either
	 * stride vector possibly negative -- reshape, the rank-2 transpose, a slice and each
	 * slab of a concatenation, over a resident operand only. Offsets are element offsets
	 * of the two WALKS' origins; the residency span of each array is still its whole data
	 * part, which the caller passes as {@code spanA} / {@code spanC} (an element offset
	 * and count), so that a slice finds the array it was cut from. A destination already
	 * resident (a concatenation's second slab onward) is written into in place.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean copyF(float[] a, int oa, int[] sa, int spanOa, int spanNa, float[] c, int oc, int[] sc, int spanOc,
			int spanNc, int[] dims) {
		int rank = dims.length;
		int n = count(dims);
		if (!acceptable(0, Long.MAX_VALUE, n, a)) {
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Call call = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			call = new Call(3);
			call.lookup(0, a, spanOa, spanNa);
			call.lookup(1, c, spanOc, spanNc);
			if (!call.ensure(0, spanNa) || !call.ensure(1, spanNc) || !call.ensureBytes(2, 3L * rank * Integer.BYTES)) {
				return false;
			}
			call.stage(0, a, spanOa, spanNa, false);
			uploadLayout(call.slabs[2], dims, sa, sc, null);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 4);
				args.setAtIndex(I, 0, n);
				args.setAtIndex(I, 1, rank);
				// The kernel walks from each side's origin: the walk's own offset within
				// the span.
				args.setAtIndex(I, 2, oa - spanOa);
				args.setAtIndex(I, 3, oc - spanOc);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.tier[COPY]);
				bind(encoder, call.slabs, 0, 1, 2);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 4L * Integer.BYTES, 3);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			call.finish(1, c, spanOc, spanNc);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			end(call);
			pop(this.driver, pool);
		}
	}

	/**
	 * The acceptance rule every member shares: big enough for its size threshold, or --
	 * offered for a resident operand -- at least {@link #MIN_RESIDENT_ELEMENTS} elements,
	 * where a launch with no copy beats a memcpy and a lane loop. {@code work} is what
	 * the size threshold counts (0 for a member that has none here).
	 */
	private boolean acceptable(long work, long threshold, long elements, @Nullable Object... operands) {
		if (work >= threshold) {
			return true;
		}
		if (elements < MIN_RESIDENT_ELEMENTS) {
			return false;
		}
		for (Object operand : operands) {
			if (operand != null && this.residency.resident(operand)) {
				return true;
			}
		}
		return false;
	}

	// --- encoding
	// -----------------------------------------------------------------------

	private MemorySegment beginEncoder(MemorySegment commands, MemorySegment state) throws Throwable {
		MemorySegment encoder = this.driver.message(commands, "computeCommandEncoder");
		this.driver.messageVoid(encoder, "setComputePipelineState:", state);
		return encoder;
	}

	/** Binds {@code slabs[slots[k]]} at buffer index {@code k}. */
	private void bind(MemorySegment encoder, Slab[] slabs, int... slots) throws Throwable {
		for (int k = 0; k < slots.length; k++) {
			this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[slots[k]].buffer, 0, k);
		}
	}

	private void flatDispatch(Arena arena, MemorySegment encoder, int n) throws Throwable {
		this.driver.dispatch(encoder, MetalDriver.size(arena, (n + FLAT_GROUP - 1) / FLAT_GROUP, 1, 1),
				MetalDriver.size(arena, FLAT_GROUP, 1, 1));
	}

	/**
	 * Commits and waits. A command buffer that ends in any status but {@code Completed}
	 * is an ordinary decline -- Metal has no sticky-context state for this to retire the
	 * feature over, unlike a CUDA {@code CUresult}.
	 */
	private boolean commitAndWait(MemorySegment commands) throws Throwable {
		this.driver.messageVoid(commands, "commit");
		this.driver.messageVoid(commands, "waitUntilCompleted");
		return this.driver.messageLong(commands, "status") == MetalDriver.STATUS_COMPLETED;
	}

	// --- one call's slabs
	// ---------------------------------------------------------------

	/**
	 * The slabs of one member call: for each slot, the slab and whether it is the CALL's
	 * (scratch taken from the pool this call, recycled at the end unless the cache
	 * adopted it) or the CACHE's (a resident copy the lookup found, or one the call
	 * adopted). The addresses of the resident ones are what an eviction under pool
	 * pressure must leave alone, because the coming dispatch reads them.
	 */
	private final class Call {

		final Slab[] slabs;

		final boolean[] owned;

		Call(int slots) {
			this.slabs = new Slab[slots];
			this.owned = new boolean[slots];
		}

		/**
		 * Looks the host span up in the cache; a hit fills the slot with the cache's
		 * slab. A hit whose slab is already gone (dropped, and a drain beat this call to
		 * it) is a miss like any other.
		 */
		@Nullable Slab lookup(int slot, Object host, long offsetElements, long elements) {
			long address = MetalGemm.this.residency.lookup(host, offsetElements * Float.BYTES, elements * Float.BYTES);
			if (address == 0) {
				return null;
			}
			synchronized (MetalGemm.this) {
				Slab slab = MetalGemm.this.held.get(address);
				if (slab != null) {
					this.slabs[slot] = slab;
				}
				return slab;
			}
		}

		/** The slot shares another's slab -- an in-place member's destination. */
		void share(int slot, int other) {
			this.slabs[slot] = this.slabs[other];
		}

		/** A scratch slab for the slot unless the lookup filled it. */
		boolean ensure(int slot, long elements) {
			return ensureBytes(slot, elements * Float.BYTES);
		}

		boolean ensureBytes(int slot, long bytes) {
			if (this.slabs[slot] != null) {
				return true;
			}
			Slab slab = take(bytes, keep());
			if (slab == null) {
				return false;
			}
			this.slabs[slot] = slab;
			this.owned[slot] = true;
			return true;
		}

		/** The addresses of every slab the call holds, for the pool's eviction. */
		private long[] keep() {
			long[] keep = new long[this.slabs.length];
			for (int i = 0; i < keep.length; i++) {
				keep[i] = this.slabs[i] != null ? this.slabs[i].buffer().address() : 0;
			}
			return keep;
		}

		/**
		 * Copies the operand in unless the lookup found it there, and -- lazily, or when
		 * {@code adopt} says so (the GEMV's matrix) -- records the slab as the array's
		 * clean resident copy, which the cache owns from then on.
		 */
		void stage(int slot, float[] host, int offset, long elements, boolean adopt) {
			if (!this.owned[slot]) {
				return;
			}
			Slab slab = this.slabs[slot];
			// A stub (DeviceResidency, the class comment) is uploaded from its backing.
			float[] source = host.length >= offset + elements ? host : (float[]) MetalGemm.this.residency.source(host,
					(long) offset * Float.BYTES, elements * Float.BYTES);
			upload(source, offset, slab, (int) elements);
			if (adopt || MetalGemm.this.lazy) {
				MetalGemm.this.adopt(host, (long) offset * Float.BYTES, elements * Float.BYTES, slab, false, keep());
				this.owned[slot] = false;
			}
		}

		/**
		 * The end of a successful member over the slot's array. Eagerly the result comes
		 * down and the slab stays scratch. Lazily nothing comes down: a slab the call
		 * took is recorded as the array's DIRTY copy, and one the cache already held (an
		 * in-place member) is marked dirty.
		 */
		void finish(int slot, float[] host, int offset, long elements) {
			Slab slab = this.slabs[slot];
			if (!MetalGemm.this.lazy) {
				// Eagerly a stub's backing is allocated and filled here, before the call
				// returns.
				float[] target = host.length >= offset + elements ? host : (float[]) MetalGemm.this.residency
					.storageFor(host, (long) offset * Float.BYTES, elements * Float.BYTES);
				download(slab, target, offset, (int) elements);
				return;
			}
			if (this.owned[slot]) {
				MetalGemm.this.adopt(host, (long) offset * Float.BYTES, elements * Float.BYTES, slab, true, keep());
				this.owned[slot] = false;
			}
			else {
				MetalGemm.this.residency.markDirty(host, (long) offset * Float.BYTES, elements * Float.BYTES);
			}
		}

	}

	/**
	 * The end of every call, successful or not: the scratch slabs go back to the pool,
	 * and then the other safe moment to give the cache's dropped slabs back -- the
	 * command buffer has completed, so nothing the kernel read is recycled underneath it.
	 */
	private void end(@Nullable Call call) {
		if (call != null) {
			synchronized (this) {
				for (int i = 0; i < call.slabs.length; i++) {
					if (call.owned[i] && call.slabs[i] != null) {
						push(call.slabs[i]);
					}
				}
			}
		}
		drainPending();
	}

	// --- residency
	// ----------------------------------------------------------------------

	/**
	 * The start of every call: gives the pool back every slab the cache has dropped since
	 * the last call. One of the two moments that is safe -- before any operand of THIS
	 * call has been looked up, so no slab the coming dispatch reads can be among them.
	 */
	private void enter() {
		drainPending();
	}

	/**
	 * Records {@code slab} as the resident copy of {@code host}'s span, clean or dirty.
	 * The slab goes into {@link #held} BEFORE the cache learns its address, so a lookup
	 * that finds the address always finds the slab; whatever the cache replaced or
	 * evicted for it comes back through the next drain, a dirty one downloaded first.
	 * {@code keep} is the call's own slabs, which an eviction must leave alone.
	 */
	private void adopt(Object host, long offsetBytes, long bytes, Slab slab, boolean dirty, long[] keep) {
		long address = slab.buffer().address();
		synchronized (this) {
			this.held.put(address, slab);
		}
		this.residency.put(host, offsetBytes, bytes, address, dirty);
		if (this.residency.collectionWanted()) {
			// Only dirty copies left to evict: wake the collector first, if it is due,
			// so that results the program has dropped -- stubs the young generation has
			// not got round to -- go back to the pool rather than being flushed into
			// fresh host arrays (DeviceResidency, the class comment); then evict what is
			// still over budget, keeping this call's slabs. The flushes run at the next
			// drain, as every flush here does.
			if (this.residency.collectionDue()) {
				System.gc();
				this.residency.collected();
			}
			drainPending();
			this.residency.evictOverBudget(keep);
		}
	}

	/**
	 * Performs every flush the cache has let go of -- a DIRTY copy it evicted, released
	 * or replaced, whose bytes the host array does not have: each is downloaded into its
	 * array -- and then returns every slab the cache has dropped, replaced, evicted or
	 * orphaned by a collected array to the pool's free lists.
	 */
	private void drainPending() {
		synchronized (this) {
			flushNow();
			long[] dropped = this.residency.drain();
			for (long address : dropped) {
				Slab slab = this.held.remove(address);
				if (slab != null) {
					push(slab);
				}
			}
		}
	}

	/** Downloads every pending flush and queues its slab for the drain. Under this. */
	private void flushNow() {
		for (DeviceResidency.Flush flush : this.residency.flushes()) {
			Slab slab = this.held.get(flush.pointer());
			if (slab != null && flush.target() instanceof float[] target) {
				download(slab, target, (int) (flush.offset() / Float.BYTES), (int) (flush.bytes() / Float.BYTES));
			}
			this.residency.release(flush.pointer());
		}
	}

	/**
	 * A host array is about to be written: its resident copy, if any, is stale and is
	 * dropped -- after being brought home first when it was the authoritative one
	 * ({@link #materialize}), so that the write lands on the array's real bytes. For a
	 * clean copy no device call happens here -- the slab goes back to the pool at the
	 * next call's safe moment -- so this is safe from any thread and costs a volatile
	 * read when nothing is resident.
	 * @param host the host array that is being written
	 * @return the array to write into: {@code host}, or a stub's backing
	 */
	@Override
	public Object written(Object host) {
		Object storage = materialize(host);
		this.residency.written(host);
		return storage;
	}

	/**
	 * Brings a DIRTY copy of {@code host} home -- the download every host read of
	 * packed-array storage performs first under {@link #lazyResults}: a memcpy out of the
	 * slab's {@code contents} -- and answers the array the host must read ({@code host},
	 * or its backing when it is a stub: {@link DeviceResidency}, the class comment).
	 * Cheap when it does not matter: a volatile read when nothing is dirty and no stub is
	 * backed, and a few identity compares for a loop that reads one array. When it does
	 * matter it is the one operation of this library that cannot decline: the host has no
	 * other copy of those bytes, so a slab that is not there is an error rather than a
	 * fallback.
	 * @param host the host array about to be read
	 * @return the array holding its bytes
	 */
	@Override
	public Object materialize(Object host) {
		Object recent = this.residency.recentClaim(host);
		if (recent != null) {
			return recent;
		}
		DeviceResidency.Claim claim = this.residency.claim(host);
		DeviceResidency.Flush flush = claim.flush();
		if (flush == null) {
			return claim.storage();
		}
		Slab slab;
		synchronized (this) {
			slab = this.held.get(flush.pointer());
		}
		if (slab == null || !(flush.target() instanceof float[] target)) {
			throw new IllegalStateException("a device result could not be brought home: its buffer is gone");
		}
		download(slab, target, (int) (flush.offset() / Float.BYTES), (int) (flush.bytes() / Float.BYTES));
		return claim.storage();
	}

	@Override
	public boolean resident(Object host) {
		return this.residency.resident(host);
	}

	@Override
	public long extent(Object host) {
		return this.residency.extent(host);
	}

	/**
	 * {@code false}: measured, lazy results do not pay on this backend -- a tie at the
	 * notebook's shapes and a loss of a third to a half at the book's, where every call
	 * still waits for its command buffer, the CPU's lane loop streams memory as fast as
	 * the device does, and a resident set of tens of gigabytes beside the host arrays it
	 * mirrors puts the machine under memory pressure ({@code .kb/gpu.md}, "Lazy results
	 * and the resident tier on Metal"). The mode itself works and is pinned by the tests;
	 * an embedder that asks for it gets it. The interceptors ask only where it pays.
	 */
	@Override
	public boolean lazyResultsPay() {
		return false;
	}

	@Override
	public boolean lazyResultsOn() {
		return this.lazy;
	}

	/**
	 * Switches lazy results on or off, and re-derives the residency budget for the mode.
	 * Switching OFF brings every dirty copy home first, so that the eager contract -- a
	 * result is in its array when the call returns -- holds for everything resident from
	 * then on.
	 * @param on whether results stay on the device until first read
	 */
	@Override
	public void lazyResults(boolean on) {
		this.lazy = on;
		this.poolBudget = derivedPoolBudget();
		this.residency
			.setBudget(this.residentBudgetOverride >= 0 ? this.residentBudgetOverride : derivedResidentBudget());
		if (!on) {
			synchronized (this) {
				for (DeviceResidency.Flush flush : this.residency.claimAllDirty()) {
					Slab slab = this.held.get(flush.pointer());
					if (slab != null && flush.target() instanceof float[] target) {
						download(slab, target, (int) (flush.offset() / Float.BYTES),
								(int) (flush.bytes() / Float.BYTES));
					}
				}
			}
		}
	}

	/**
	 * Bytes held by resident copies right now. For the tests and the description.
	 * @return the resident total, in bytes
	 */
	@Override
	public long residentBytes() {
		return this.residency.bytes();
	}

	/**
	 * Drops every resident copy -- downloading the dirty ones first -- and gives its slab
	 * back to the pool. For the tests, whose leak baselines are measured against an EMPTY
	 * resident set; the pool keeps the slabs, which is what the steady-state assertion
	 * wants.
	 */
	@Override
	public void releaseResident() {
		this.residency.evictAll();
		drainPending();
	}

	@Override
	public DeviceResidency residency() {
		return this.residency;
	}

	/**
	 * Imposes a residency budget in bytes, or {@code -1} to go back to the derived one.
	 * Package-private and for the tests, which need the LRU to evict at a size they can
	 * afford to fill. Takes effect at once; entries over the new budget go at the next
	 * insertion.
	 */
	void residentBudget(long bytes) {
		this.residentBudgetOverride = bytes;
		this.residency.setBudget(bytes >= 0 ? bytes : derivedResidentBudget());
	}

	/** The pool's budget for the mode in force: see {@link #LAZY_HEADROOM_SHARE}. */
	private long derivedPoolBudget() {
		if (this.lazy) {
			return Math.max(1L << 28,
					this.workingSet - Math.max(this.workingSet / LAZY_HEADROOM_SHARE, LAZY_HEADROOM_FLOOR));
		}
		return Math.max(1L << 28, this.workingSet / POOL_BUDGET_DIVISOR);
	}

	/**
	 * The resident set's budget for the mode in force: see {@link #RESIDENT_SHARE} and
	 * {@link #LAZY_HEADROOM_SHARE}.
	 */
	private long derivedResidentBudget() {
		if (this.lazy) {
			return Math.max(0, this.poolBudget - Math.max(this.poolBudget / LAZY_HEADROOM_SHARE, LAZY_HEADROOM_FLOOR));
		}
		return Math.min(RESIDENT_CAP, this.poolBudget / RESIDENT_SHARE);
	}

	// --- the buffer pool
	// -----------------------------------------------------------------

	/**
	 * One reusable device buffer and the host pointer into it. On unified memory
	 * {@code contents} IS host memory, so an upload is a {@code memcpy} and nothing more.
	 */
	private record Slab(MemorySegment buffer, MemorySegment contents, long capacity, int sizeClass) {
	}

	/**
	 * A slab of at least {@code bytes}, from the free lists or freshly minted. When the
	 * pool's budget is reached, first its own free classes are released; if that is not
	 * enough, every resident copy but the ones in {@code keep} -- the slabs the call in
	 * progress is holding -- is given back (a dirty one downloaded first) and the free
	 * lists are released again: the resident set must never be the reason a call
	 * declines. {@code null} only when the slab cannot fit even then.
	 */
	private @Nullable Slab take(long bytes, long[] keep) {
		if (bytes <= 0) {
			return null;
		}
		int sizeClass = sizeClass(bytes);
		if (sizeClass > MAX_SLAB_CLASS) {
			return null;
		}
		synchronized (this) {
			ArrayDeque<Slab> bucket = this.free[sizeClass];
			if (bucket != null && !bucket.isEmpty()) {
				return bucket.pop();
			}
			long capacity = 1L << sizeClass;
			if (this.pooledBytes + capacity > this.poolBudget && !evict(capacity)) {
				if (!this.residency.occupied()) {
					return null;
				}
				this.residency.evictAll(keep);
				flushNow();
				for (long address : this.residency.drain()) {
					Slab slab = this.held.remove(address);
					if (slab != null) {
						push(slab);
					}
				}
				if (!evict(capacity)) {
					return null;
				}
			}
			Slab slab = create(capacity, sizeClass);
			if (slab != null) {
				this.pooledBytes += capacity;
			}
			return slab;
		}
	}

	private @Nullable Slab create(long capacity, int sizeClass) {
		try {
			MemorySegment buffer = this.driver.message(this.device, "newBufferWithLength:options:", capacity,
					MetalDriver.STORAGE_SHARED);
			if (buffer.address() == 0) {
				return null;
			}
			return new Slab(buffer, this.driver.contents(buffer, capacity), capacity, sizeClass);
		}
		catch (Throwable ex) {
			return null;
		}
	}

	/**
	 * Frees whole classes of unused slabs until {@code wanted} bytes fit under the
	 * budget. Called only when the budget is reached, which a program with one steady
	 * working set never does.
	 */
	private boolean evict(long wanted) {
		for (int k = MAX_SLAB_CLASS; k >= 0 && this.pooledBytes + wanted > this.poolBudget; k--) {
			ArrayDeque<Slab> bucket = this.free[k];
			while (bucket != null && !bucket.isEmpty() && this.pooledBytes + wanted > this.poolBudget) {
				release(this.driver, bucket.pop().buffer());
				this.pooledBytes -= 1L << k;
			}
		}
		return this.pooledBytes + wanted <= this.poolBudget;
	}

	/** Onto its free list. Callers hold {@code this}. */
	private void push(Slab slab) {
		ArrayDeque<Slab> bucket = this.free[slab.sizeClass()];
		if (bucket == null) {
			bucket = new ArrayDeque<>();
			this.free[slab.sizeClass()] = bucket;
		}
		bucket.push(slab);
	}

	private static int sizeClass(long bytes) {
		return 64 - Long.numberOfLeadingZeros(Math.max(bytes, MIN_SLAB_BYTES) - 1);
	}

	// --- copies
	// -------------------------------------------------------------------------

	private static void upload(float[] source, int offset, Slab slab, int elements) {
		MemorySegment.copy(source, offset, slab.contents(), F, 0, elements);
	}

	private static void download(Slab slab, float[] destination, int offset, int elements) {
		MemorySegment.copy(slab.contents(), F, 0, destination, offset, elements);
	}

	/**
	 * The layout the strided kernels index out of: the output dims, then one source
	 * stride per output axis per operand, all in elements. Rank 8 is 96 bytes.
	 */
	private static void uploadLayout(Slab slab, int[] dims, int[] sa, int @Nullable [] sb, int @Nullable [] sc) {
		int rank = dims.length;
		MemorySegment contents = slab.contents();
		for (int k = 0; k < rank; k++) {
			contents.setAtIndex(I, k, dims[k]);
			contents.setAtIndex(I, rank + k, sa[k]);
			if (sb != null) {
				contents.setAtIndex(I, 2 * rank + k, sb[k]);
			}
			if (sc != null) {
				contents.setAtIndex(I, 3 * rank + k, sc[k]);
			}
		}
	}

	// --- shapes
	// -------------------------------------------------------------------------

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
	 * How many elements of an operand one launch spans: the last batch's slab plus
	 * everything the stride skipped over on the way to it. A broadcast operand (stride 0)
	 * spans ONE slab however long the batch is.
	 */
	private static long span(int batch, int stride, long matrix) {
		return (long) (batch - 1) * stride + matrix;
	}

}
