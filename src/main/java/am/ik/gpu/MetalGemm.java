package am.ik.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import org.jspecify.annotations.Nullable;

import static am.ik.gpu.MetalDriver.F;
import static am.ik.gpu.MetalDriver.I;

/**
 * The Apple half of {@code --gpu}: one {@code MTLDevice}, one command queue, one library
 * compiled from {@code gemm.metal} at run time, and the three kernels it exports -- a
 * STACKED matrix product, an element-wise map and the strided broadcast/gather pair --
 * plus the rank-2 product, which goes through {@code MPSMatrixMultiplication} instead.
 * Everything that can fail fails into a decline; nothing here throws.
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
 * reused, which is sound with no invalidation rule of any kind -- they are SCRATCH, fully
 * overwritten on the way in and fully read on the way out, and no host array's device
 * copy outlives the call. Residency, which would need such a rule, is still not built
 * ({@code .kb/gpu.md}).
 *
 * @see Gpu
 * @see MetalDriver
 */
final class MetalGemm implements GpuDevice {

	/** The MSL source, beside this class in the resources. */
	static final String KERNEL_RESOURCE = "gemm.metal";

	/**
	 * The kernels {@link #KERNEL_RESOURCE} must export. The rank-2 product is absent on
	 * purpose: MPS serves it.
	 */
	static final String KERNEL_BATCHED_F32 = "gemm_batched_f32", KERNEL_MAP_F32 = "map_f32",
			KERNEL_BCAST_F32 = "bcast_f32", KERNEL_GATHER_F32 = "gather_f32";

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

	private final MetalDriver driver;

	private final MemorySegment device;

	private final MemorySegment queue;

	private final MemorySegment library;

	private final MemorySegment gemmBatched;

	private final MemorySegment map;

	private final MemorySegment bcast;

	private final MemorySegment gather;

	private final String description;

	private final long workingSet;

	private final long poolBudget;

	/**
	 * Free slabs by size class, {@code free[k]} holding buffers of {@code 1 << k} bytes.
	 */
	private final ArrayDeque<Slab>[] free;

	private long pooledBytes;

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
			MemorySegment gemmBatched, MemorySegment map, MemorySegment bcast, MemorySegment gather, String description,
			long workingSet) {
		this.driver = driver;
		this.device = device;
		this.queue = queue;
		this.library = library;
		this.gemmBatched = gemmBatched;
		this.map = map;
		this.bcast = bcast;
		this.gather = gather;
		this.description = description;
		this.workingSet = workingSet;
		this.poolBudget = Math.max(1L << 28, workingSet / POOL_BUDGET_DIVISOR);
		this.free = new ArrayDeque[MAX_SLAB_CLASS + 1];
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
		// queue, an MSL library and four pipeline states behind, which is the same rule
		// CudaGemm.unwind follows for a retained primary context.
		MemorySegment queue = MemorySegment.NULL;
		MemorySegment library = MemorySegment.NULL;
		MemorySegment[] kernels = new MemorySegment[4];
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
			String[] names = { KERNEL_BATCHED_F32, KERNEL_MAP_F32, KERNEL_BCAST_F32, KERNEL_GATHER_F32 };
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
			long workingSet = driver.messageLong(device, "recommendedMaxWorkingSetSize");
			boolean unified = driver.respondsTo(device, "hasUnifiedMemory")
					&& driver.messageBool(device, "hasUnifiedMemory");
			String description = name(driver, device) + " (Metal, " + (unified ? "unified memory, " : "")
					+ (workingSet >> 30) + " GB working set)";
			keep = true;
			return new Probe(new MetalGemm(driver, device, queue, library, kernels[0], kernels[1], kernels[2],
					kernels[3], description, workingSet), description);
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
		return new Thresholds(MIN_WORK, MIN_MAP_ELEMENTS, MIN_STRIDED_ELEMENTS, Long.MAX_VALUE, Long.MAX_VALUE);
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
		MemorySegment pool = MemorySegment.NULL;
		Slab @Nullable [] slabs = null;
		try {
			pool = this.driver.autoreleasePoolPush();
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

	// --- the buffer pool
	// -----------------------------------------------------------------

	/**
	 * One reusable device buffer and the host pointer into it. On unified memory
	 * {@code contents} IS host memory, so an upload is a {@code memcpy} and nothing more.
	 */
	private record Slab(MemorySegment buffer, MemorySegment contents, long capacity, int sizeClass) {
	}

	/**
	 * The slabs for one call, or {@code null} for a decline that costs the device
	 * nothing. Every slab taken before a failure goes straight back, so a call that
	 * cannot fit leaves the pool exactly as it found it.
	 */
	private Slab @Nullable [] acquire(long... sizes) {
		Slab[] slabs = new Slab[sizes.length];
		for (int i = 0; i < sizes.length; i++) {
			Slab slab = take(sizes[i]);
			if (slab == null) {
				recycle(java.util.Arrays.copyOf(slabs, i));
				return null;
			}
			slabs[i] = slab;
		}
		return slabs;
	}

	private @Nullable Slab take(long bytes) {
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
				return null;
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

	private void recycle(Slab @Nullable [] slabs) {
		if (slabs == null) {
			return;
		}
		synchronized (this) {
			for (Slab slab : slabs) {
				ArrayDeque<Slab> bucket = this.free[slab.sizeClass()];
				if (bucket == null) {
					bucket = new ArrayDeque<>();
					this.free[slab.sizeClass()] = bucket;
				}
				bucket.push(slab);
			}
		}
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
