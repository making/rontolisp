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

/**
 * The Apple half of {@code --gpu}: one {@code MTLDevice}, one command queue, one library
 * compiled from {@code gemm.metal} at run time, and the four kernels it exports -- a
 * STACKED matrix product, an element-wise map, the strided broadcast/gather pair and the
 * GEMV behind {@code vec:matvec} -- plus the rank-2 product, which goes through
 * {@code MPSMatrixMultiplication} instead. Everything that can fail fails into a decline;
 * nothing here throws.
 *
 * <h2>Single float, or nothing</h2>
 *
 * MSL rejects {@code double} outright, so {@link #supportsDouble()} is {@code false} and
 * every double-taking method here answers {@code false} without touching the device. That
 * is not a gap to fill later: there is no fp64 on this hardware to fill it with. It makes
 * the decline protocol load-bearing in a way it is not on CUDA -- {@code linalg}'s
 * default width reaches this backend only after {@code torch:}'s single-float default or
 * an explicit {@code #f} array.
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
 * <h2>And ONE resident array on top of it: the GEMV's matrix</h2>
 *
 * The CUDA half keeps a copy of every operand and result on the device
 * ({@link DeviceResidency}), and measured a fifth off its training step for it. This half
 * keeps the same cache, but puts only one thing in it: the matrix of an accepted
 * {@link #gemvF}. That is a measurement, not a shortcut. On unified memory an upload is a
 * memcpy into the slab's {@code contents} -- 1.5 MB in ~75 us -- while a slab held out of
 * the pool for a resident copy costs the pool a FRESH slab for the next call of that
 * size, and a fresh slab pays its first-touch page faults (~1 us a page; the measurement
 * that made the pool mandatory). On the training step {@code .kb/gpu.md} measures every
 * residency decision on, keeping every operand and result resident was 1-5% SLOWER than
 * the pure pool at every cap tried (1 GB, 256 MB, 64 MB, 16 MB, and 0, the bookkeeping
 * alone), and the chain hits it exists for bought nothing the clock could see. A GEMV's
 * matrix is the one array that is re-read hundreds of times and written never, and it is
 * the one array that cannot be copied in per call without losing (the copy IS the bytes
 * the CPU would have streamed), so it is kept, LRU-bounded by a quarter of the pool's
 * budget capped at {@link #RESIDENT_CAP}, dropped by {@link #written} like any resident
 * copy, and its slab returned to the pool at the two safe moments -- the start of a call,
 * before any operand is looked up, and the end of one, after the command buffer has
 * completed. Every other slab is SCRATCH as before: fully overwritten on the way in,
 * fully read on the way out, recycled the moment the call ends, sound with no
 * invalidation rule.
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
	 * Where {@code MPSMatrixMultiplication} takes over from {@link #KERNEL_BATCHED_F32}
	 * for ONE matrix of the product: {@code 2^27}, a 512x512x512 product. Below it the
	 * tiled kernel is ahead (166 us against 180 at n=256) because MPS costs ~35 us of
	 * object churn a call; above it MPS wins outright -- 202 against 308 at n=512, 523
	 * against 1545 at n=1024, 2264 against 10183 at n=2048.
	 */
	private static final long MPS_MIN_WORK = 1L << 27;

	/**
	 * Threads per threadgroup for the flat kernels: the map, the broadcast, the gather.
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
	 * What fraction of the POOL's budget the resident set -- the GEMV matrices -- may
	 * hold, and the cap on it: {@code min(pool / 4, 1 GB)}, the CUDA half's own cap. A
	 * resident slab is one the pool cannot recycle, so the bound is what keeps a program
	 * that offers many distinct matrices from turning the pool into fresh pages; a model
	 * whose weights exceed it keeps its most recently used ones.
	 */
	private static final long RESIDENT_SHARE = 4, RESIDENT_CAP = 1L << 30;

	private final MetalDriver driver;

	private final MemorySegment device;

	private final MemorySegment queue;

	private final MemorySegment library;

	private final MemorySegment gemmBatched;

	private final MemorySegment map;

	private final MemorySegment bcast;

	private final MemorySegment gather;

	private final MemorySegment gemv;

	/** The GEMV pipeline's SIMD-group width, which is how many threads share one row. */
	private final int gemvWidth;

	private final String description;

	private final long workingSet;

	private final long poolBudget;

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
	 * The resident copies -- the matrices of accepted GEMVs: host array -> the address of
	 * the slab holding its elements ({@link DeviceResidency}), and {@link #held} is the
	 * slab behind each such address. Both are kept in step under {@code this}: a slab is
	 * put into {@link #held} before the cache learns its address, and removed from it
	 * only when the cache has handed the address back through
	 * {@link DeviceResidency#drain()}.
	 */
	private final DeviceResidency residency = new DeviceResidency();

	private final Map<Long, Slab> held = new HashMap<>();

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
			MemorySegment gemmBatched, MemorySegment map, MemorySegment bcast, MemorySegment gather, MemorySegment gemv,
			int gemvWidth, String description, long workingSet) {
		this.driver = driver;
		this.device = device;
		this.queue = queue;
		this.library = library;
		this.gemmBatched = gemmBatched;
		this.map = map;
		this.bcast = bcast;
		this.gather = gather;
		this.gemv = gemv;
		this.gemvWidth = gemvWidth;
		this.description = description;
		this.workingSet = workingSet;
		this.poolBudget = Math.max(1L << 28, workingSet / POOL_BUDGET_DIVISOR);
		this.free = new ArrayDeque[MAX_SLAB_CLASS + 1];
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
		// queue, an MSL library and five pipeline states behind, which is the same rule
		// CudaGemm.unwind follows for a retained primary context.
		MemorySegment queue = MemorySegment.NULL;
		MemorySegment library = MemorySegment.NULL;
		MemorySegment[] kernels = new MemorySegment[5];
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
			String[] names = { KERNEL_BATCHED_F32, KERNEL_MAP_F32, KERNEL_BCAST_F32, KERNEL_GATHER_F32,
					KERNEL_GEMV_F32 };
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
			return new Probe(new MetalGemm(driver, device, queue, library, kernels[0], kernels[1], kernels[2],
					kernels[3], kernels[4], (int) width, description, workingSet), description);
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

	/**
	 * The axis fold is not a member on this backend at EITHER width, and the reason is
	 * measured twice over: {@code %la-fold-axis} accumulates in {@code double}, which no
	 * float kernel reproduces bit for bit, and the amax/amin half that needs no
	 * accumulator does not pay -- the CPU fold is 85 us over 262144 f32 elements against
	 * this backend's ~150. See {@code gemm.metal} and {@code .kb/gpu.md}.
	 */
	@Override
	public boolean foldF(int op, float[] a, int oa, float[] c, int oc, int outer, int len, int inner) {
		return false;
	}

	/** The matrix-by-vector product at {@code double} is a hard decline like the rest. */
	@Override
	public boolean gemv(double[] w, int ow, double[] x, int ox, double[] y, int oy, int rows, int cols) {
		return false;
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
	 * @return {@code true} when {@code c} was filled, {@code false} when the product
	 * declined or the device failed -- in which case {@code c} is untouched
	 */
	@Override
	public boolean gemmF(float[] a, int oa, int sa, float[] b, int ob, int sb, float[] c, int oc, int batch, int n,
			int m, int p) {
		long aElements = span(batch, sa, (long) n * m), bElements = span(batch, sb, (long) m * p),
				cElements = (long) batch * n * p;
		MemorySegment pool = MemorySegment.NULL;
		Slab @Nullable [] slabs = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			slabs = acquire(aElements * Float.BYTES, bElements * Float.BYTES, cElements * Float.BYTES);
			if (slabs == null) {
				return false;
			}
			upload(a, oa, slabs[0], (int) aElements);
			upload(b, ob, slabs[1], (int) bElements);
			boolean ran = this.mps && (long) n * m * p >= MPS_MIN_WORK
					? multiplyThroughMps(slabs, sa, sb, batch, n, m, p) : dispatchGemm(slabs, sa, sb, batch, n, m, p);
			if (!ran) {
				return false;
			}
			download(slabs[2], c, oc, (int) cElements);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			recycle(slabs);
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
	 * member is the op code rather than the kernel.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean mapF(int op, float[] a, int oa, float[] c, int oc, int n) {
		if (op >= Gpu.MAP_LIBM_OPS) {
			// The resident tier's four maps have no case in gemm.metal and no resident
			// operand to be offered over here.
			return false;
		}
		MemorySegment pool = MemorySegment.NULL;
		Slab @Nullable [] slabs = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			slabs = acquire((long) n * Float.BYTES, (long) n * Float.BYTES);
			if (slabs == null) {
				return false;
			}
			upload(a, oa, slabs[0], n);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 2);
				args.setAtIndex(I, 0, n);
				args.setAtIndex(I, 1, op);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.map);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[0].buffer, 0, 0);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[1].buffer, 0, 1);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 2);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			download(slabs[1], c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			recycle(slabs);
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
		MemorySegment pool = MemorySegment.NULL;
		Slab @Nullable [] slabs = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			slabs = acquire(aElements * Float.BYTES, bElements * Float.BYTES, (long) n * Float.BYTES,
					3L * rank * Integer.BYTES);
			if (slabs == null) {
				return false;
			}
			upload(a, oa, slabs[0], (int) aElements);
			upload(b, ob, slabs[1], (int) bElements);
			uploadLayout(slabs[3], dims, sa, sb);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 3);
				args.setAtIndex(I, 0, op);
				args.setAtIndex(I, 1, n);
				args.setAtIndex(I, 2, rank);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.bcast);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[0].buffer, 0, 0);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[1].buffer, 0, 1);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[2].buffer, 0, 2);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[3].buffer, 0, 3);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 3L * Integer.BYTES, 4);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			download(slabs[2], c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			recycle(slabs);
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
		MemorySegment pool = MemorySegment.NULL;
		Slab @Nullable [] slabs = null;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			slabs = acquire(aElements * Float.BYTES, (long) n * Float.BYTES, 2L * rank * Integer.BYTES);
			if (slabs == null) {
				return false;
			}
			upload(a, oa, slabs[0], (int) aElements);
			uploadLayout(slabs[2], dims, sa, null);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment args = arena.allocate(I, 2);
				args.setAtIndex(I, 0, n);
				args.setAtIndex(I, 1, rank);
				MemorySegment commands = this.driver.message(this.queue, "commandBuffer");
				MemorySegment encoder = beginEncoder(commands, this.gather);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[0].buffer, 0, 0);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[1].buffer, 0, 1);
				this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[2].buffer, 0, 2);
				this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 3);
				flatDispatch(arena, encoder, n);
				this.driver.messageVoid(encoder, "endEncoding");
				if (!commitAndWait(commands)) {
					return false;
				}
			}
			download(slabs[1], c, oc, n);
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			recycle(slabs);
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
	 * it would clear a copy. The matrix is the ONLY array this backend keeps resident
	 * (the class comment says why); {@code x} and {@code y} are scratch slabs, copied per
	 * call -- a 1 KB memcpy and a 128 KB one at llama2's head.
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
		long wElements = (long) rows * cols, offW = (long) ow * Float.BYTES, wBytes = wElements * Float.BYTES;
		MemorySegment pool = MemorySegment.NULL;
		Slab @Nullable [] slabs = null;
		// Whether slabs[0] is this call's to recycle (a scratch slab it copied the
		// matrix into) or the cache's.
		boolean ownMatrix = false;
		try {
			pool = this.driver.autoreleasePoolPush();
			enter();
			Slab matrix = resident(w, offW, wBytes);
			// The two-sight rule above: a matrix seen for the first time is declined and
			// marked, nothing taken; seen again unwritten, it is copied in below.
			if (matrix == null && !this.residency.offeredBefore(w, offW, wBytes)) {
				return false;
			}
			slabs = acquire(matrix, wBytes, (long) cols * Float.BYTES, (long) rows * Float.BYTES);
			if (slabs == null) {
				return false;
			}
			ownMatrix = matrix == null;
			if (ownMatrix) {
				upload(w, ow, slabs[0], (int) wElements);
			}
			upload(x, ox, slabs[1], cols);
			if (!dispatchGemv(slabs, rows, cols)) {
				return false;
			}
			download(slabs[2], y, oy, rows);
			if (ownMatrix) {
				// Device and host hold the same bytes: the slab becomes the matrix's
				// resident copy, and every later sight finds it.
				adopt(w, offW, wBytes, slabs[0]);
				ownMatrix = false;
			}
			drainPending();
			return true;
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			if (slabs != null) {
				recycle(ownMatrix ? slabs : java.util.Arrays.copyOfRange(slabs, 1, slabs.length));
			}
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
			this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[0].buffer, 0, 0);
			this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[1].buffer, 0, 1);
			this.driver.messageVoid(encoder, "setBuffer:offset:atIndex:", slabs[2].buffer, 0, 2);
			this.driver.messageVoid(encoder, "setBytes:length:atIndex:", args, 2L * Integer.BYTES, 3);
			this.driver.dispatch(encoder, MetalDriver.size(arena, (rows + rowsPerGroup - 1) / rowsPerGroup, 1, 1),
					MetalDriver.size(arena, GEMV_GROUP, 1, 1));
			this.driver.messageVoid(encoder, "endEncoding");
			return commitAndWait(commands);
		}
	}

	// --- encoding
	// -----------------------------------------------------------------------

	private MemorySegment beginEncoder(MemorySegment commands, MemorySegment state) throws Throwable {
		MemorySegment encoder = this.driver.message(commands, "computeCommandEncoder");
		this.driver.messageVoid(encoder, "setComputePipelineState:", state);
		return encoder;
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
	 * The slab holding a resident copy of {@code host}'s span, or {@code null}. A hit in
	 * the cache whose slab is already gone (dropped, and a drain beat this call to it) is
	 * a miss like any other.
	 */
	private @Nullable Slab resident(Object host, long offsetBytes, long bytes) {
		long address = this.residency.lookup(host, offsetBytes, bytes);
		if (address == 0) {
			return null;
		}
		synchronized (this) {
			return this.held.get(address);
		}
	}

	/**
	 * Records {@code slab} as the resident copy of {@code host}'s span. The slab goes
	 * into {@link #held} BEFORE the cache learns its address, so a lookup that finds the
	 * address always finds the slab; whatever the cache replaced or evicted for it comes
	 * back through the next drain.
	 */
	private void adopt(Object host, long offsetBytes, long bytes, Slab slab) {
		long address = slab.buffer().address();
		synchronized (this) {
			this.held.put(address, slab);
		}
		this.residency.put(host, offsetBytes, bytes, address, false);
	}

	/**
	 * Returns every slab the cache has dropped, replaced, evicted or orphaned by a
	 * collected array since the last drain to the pool's free lists.
	 */
	private void drainPending() {
		// This backend never marks a copy dirty, so a flush never carries bytes the host
		// lacks; its slab simply goes back with the rest.
		for (DeviceResidency.Flush flush : this.residency.flushes()) {
			this.residency.release(flush.pointer());
		}
		long[] dropped = this.residency.drain();
		if (dropped.length == 0) {
			return;
		}
		synchronized (this) {
			for (long address : dropped) {
				Slab slab = this.held.remove(address);
				if (slab != null) {
					push(slab);
				}
			}
		}
	}

	/**
	 * A host array was written: its resident copy, if any, is stale and is dropped. No
	 * device call happens here -- the slab goes back to the pool at the next call's safe
	 * moment -- so this is safe from any thread and costs a volatile read when nothing is
	 * resident.
	 * @param host the host array that was written
	 */
	@Override
	public void written(Object host) {
		this.residency.written(host);
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
	 * Drops every resident copy and gives its slab back to the pool. For the tests, whose
	 * leak baselines are measured against an EMPTY resident set; the pool keeps the
	 * slabs, which is what the steady-state assertion wants.
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
	 * A no-op: this backend keeps no lazy results, so no host array ever lacks bytes the
	 * device holds.
	 */
	@Override
	public void materialize(Object host) {
	}

	@Override
	public boolean resident(Object host) {
		return this.residency.resident(host);
	}

	/**
	 * Declined: on unified memory a result's copy home is a memcpy, and what the CUDA
	 * half measured as the cost of every result coming home ({@code .todo/491}) has not
	 * been measured here. Results keep coming home before the call returns, and
	 * {@link #materialize} stays a no-op. Measure before changing this
	 * ({@code .kb/gpu.md}, "Residency and the GEMV on this backend").
	 */
	@Override
	public void lazyResults(boolean on) {
	}

	// The resident tier is not a member set here: it exists to avoid a round trip that
	// on this backend is a memcpy, and the only resident arrays are the GEMV's matrices.
	// Each declines, and the CPU kernel runs.

	@Override
	public boolean zip(int op, double[] a, int oa, double[] b, int ob, double[] c, int oc, int n) {
		return false;
	}

	@Override
	public boolean zipF(int op, float[] a, int oa, float[] b, int ob, float[] c, int oc, int n) {
		return false;
	}

	@Override
	public boolean scale(int op, double[] a, int oa, double s, boolean swap, double[] c, int oc, int n) {
		return false;
	}

	@Override
	public boolean scaleF(int op, float[] a, int oa, double s, boolean swap, float[] c, int oc, int n) {
		return false;
	}

	@Override
	public boolean where(@Nullable Object m, int om, int[] sm, double ms, double @Nullable [] x, int ox, int[] sx,
			double xs, double @Nullable [] y, int oy, int[] sy, double ys, double[] c, int oc, int[] dims) {
		return false;
	}

	@Override
	public boolean whereF(@Nullable Object m, int om, int[] sm, double ms, float @Nullable [] x, int ox, int[] sx,
			double xs, float @Nullable [] y, int oy, int[] sy, double ys, float[] c, int oc, int[] dims) {
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

	@Override
	public boolean copyF(float[] a, int oa, int[] sa, int spanOa, int spanNa, float[] c, int oc, int[] sc, int spanOc,
			int spanNc, int[] dims) {
		return false;
	}

	@Override
	public boolean adamStepF(float[] x, int ox, float[] g, int og, float[] m, int om, float[] v, int ov, int n,
			double[] rule) {
		return false;
	}

	/**
	 * Imposes a residency budget in bytes, or {@code -1} to go back to the derived one.
	 * Package-private and for the tests, which need the LRU to evict at a size they can
	 * afford to fill. Takes effect at once; entries over the new budget go at the next
	 * insertion.
	 */
	void residentBudget(long bytes) {
		this.residency.setBudget(bytes >= 0 ? bytes : derivedResidentBudget());
	}

	private long derivedResidentBudget() {
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
	 * The scratch slabs for one call, or {@code null} for a decline that costs the device
	 * nothing. Every slab taken before a failure goes straight back, so a call that
	 * cannot fit leaves the pool exactly as it found it.
	 */
	private Slab @Nullable [] acquire(long... sizes) {
		return acquire(null, sizes);
	}

	/**
	 * The slabs for a GEMV: {@code resident} as {@code slabs[0]} when the matrix is
	 * already there (and then it is the cache's, not the call's, and is left out of the
	 * pre-flight's eviction), a scratch slab of {@code sizes[0]} otherwise; scratch slabs
	 * for the rest.
	 */
	private Slab @Nullable [] acquire(@Nullable Slab resident, long... sizes) {
		Slab[] slabs = new Slab[sizes.length];
		long keep = resident != null ? resident.buffer().address() : 0;
		for (int i = 0; i < sizes.length; i++) {
			Slab slab = i == 0 && resident != null ? resident : take(sizes[i], keep);
			if (slab == null) {
				recycle(java.util.Arrays.copyOfRange(slabs, resident != null ? 1 : 0, i));
				return null;
			}
			slabs[i] = slab;
		}
		return slabs;
	}

	/**
	 * A slab of at least {@code bytes}, from the free lists or freshly minted. When the
	 * pool's budget is reached, first its own free classes are released; if that is not
	 * enough, every resident copy but {@code keep} -- the matrix the call in progress is
	 * holding -- is given back and the free lists are released again: the resident set
	 * must never be the reason a call declines. {@code null} only when the slab cannot
	 * fit even then.
	 */
	private @Nullable Slab take(long bytes, long keep) {
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
				this.residency.evictAll(new long[] { keep });
				for (DeviceResidency.Flush flush : this.residency.flushes()) {
					this.residency.release(flush.pointer());
				}
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

	/** Gives the call's scratch slabs back to their free lists. */
	private void recycle(Slab @Nullable [] slabs) {
		if (slabs == null) {
			return;
		}
		synchronized (this) {
			for (Slab slab : slabs) {
				if (slab != null) {
					push(slab);
				}
			}
		}
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
	private static void uploadLayout(Slab slab, int[] dims, int[] sa, int @Nullable [] sb) {
		int rank = dims.length;
		MemorySegment contents = slab.contents();
		for (int k = 0; k < rank; k++) {
			contents.setAtIndex(I, k, dims[k]);
			contents.setAtIndex(I, rank + k, sa[k]);
			if (sb != null) {
				contents.setAtIndex(I, 2 * rank + k, sb[k]);
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
