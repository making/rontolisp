package am.ik.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

import static am.ik.gpu.CudaDriver.D;
import static am.ik.gpu.CudaDriver.I;
import static am.ik.gpu.CudaDriver.L;
import static am.ik.gpu.CudaDriver.P;

/**
 * The device side of {@code --gpu}: one primary context, one module JIT-compiled from the
 * checked-in PTX, and the kernels it exports -- a tiled matrix product, a STACK of them,
 * an element-wise map, the strided tier, the generator fill and a matrix-by-vector
 * product, at each width. Everything that can fail fails into a decline; nothing here
 * throws.
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
final class CudaGemm implements GpuDevice {

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
	 * The register-tiled single-float products: the same fold over a 64x64 and a 128x128
	 * block tile, chosen per shape by {@link #tileF32}. Both take the batched parameter
	 * block at every batch size.
	 */
	static final String KERNEL_BATCHED_F32_T4 = "gemm_batched_f32_t4", KERNEL_BATCHED_F32_T8 = "gemm_batched_f32_t8";

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
			"fold_f32", "rng_fill_f64", "rng_fill_f32" };

	private static final int BCAST_F64 = 0, BCAST_F32 = 1, GATHER_F64 = 2, GATHER_F32 = 3, FOLD_F64 = 4, FOLD_F32 = 5,
			RNG_F64 = 6, RNG_F32 = 7;

	/**
	 * The RESIDENT tier's kernels ({@code .todo/491}), in the order {@link #resident}
	 * holds them: the equal-shape binary op, the array-with-scalar form, the three-way
	 * select and the fused Adam update, at each width. None of them is offered over a
	 * round trip -- their CPU twins are lane loops, which the element-wise tier measured
	 * a round trip cannot beat -- but every one is a launch with no copy over an operand
	 * that is ALREADY on the device, and that is the only way {@link Gpu} offers them.
	 */
	static final String[] KERNELS_RESIDENT = { "zip_f64", "zip_f32", "scal_f64", "scal_f32", "where_f64", "where_f32",
			"adam_f64", "adam_f32", "copy_f64", "copy_f32", "take_f64", "take_f32", "scatter_f64", "scatter_f32",
			"sumsq_f64", "sumsq_f32" };

	private static final int ZIP_F64 = 0, ZIP_F32 = 1, SCAL_F64 = 2, SCAL_F32 = 3, WHERE_F64 = 4, WHERE_F32 = 5,
			ADAM_F64 = 6, ADAM_F32 = 7, COPY_F64 = 8, COPY_F32 = 9, TAKE_F64 = 10, TAKE_F32 = 11, SCATTER_F64 = 12,
			SCATTER_F32 = 13, SUMSQ_F64 = 14, SUMSQ_F32 = 15;

	/**
	 * The FUSED tier's kernels ({@code .todo/499}), in the order {@link #fused} holds
	 * them: the exact GELU and its adjoint, the last-axis softmax and its adjoint,
	 * layer-norm's normalization and its adjoint, and the inverted-dropout mask, at each
	 * width. Each is one pass where the {@code torch.lisp} composition ran a chain of
	 * members, and each reproduces that chain's arithmetic rounding for rounding
	 * ({@code gemm.cu}, "The FUSED tier").
	 */
	static final String[] KERNELS_FUSED = { "gelu_f64", "gelu_f32", "gelu_grad_f64", "gelu_grad_f32", "softmax_f64",
			"softmax_f32", "softmax_grad_f64", "softmax_grad_f32", "layer_norm_f64", "layer_norm_f32",
			"layer_norm_grad_f64", "layer_norm_grad_f32", "dropout_mask_f64", "dropout_mask_f32" };

	private static final int GELU_F64 = 0, GELU_F32 = 1, GELU_GRAD_F64 = 2, GELU_GRAD_F32 = 3, SOFTMAX_F64 = 4,
			SOFTMAX_F32 = 5, SOFTMAX_GRAD_F64 = 6, SOFTMAX_GRAD_F32 = 7, LAYER_NORM_F64 = 8, LAYER_NORM_F32 = 9,
			LAYER_NORM_GRAD_F64 = 10, LAYER_NORM_GRAD_F32 = 11, DROPOUT_F64 = 12, DROPOUT_F32 = 13;

	/**
	 * What one element of a row kernel (softmax, layer-norm and their adjoints) is
	 * charged as for the safepoint threshold: a few double operations per pass over three
	 * to five passes, with a libm call in the softmax's.
	 */
	private static final long FUSED_ROW_FLOPS_PER_ELEMENT = 64;

	/**
	 * Threads per block of the row kernels: {@code ROW_WARPS} warps of thirty-two rows
	 * each, one thread per row, the rows streamed through a transposed shared-memory tile
	 * per warp ({@code gemm.cu}, "THE ROW KERNELS' LAYOUT"). The two are one number in
	 * two files: the kernel derives its rows from {@code ROW_WARPS}, so a launch at any
	 * other block size would compute the wrong rows.
	 */
	private static final int ROW_BLOCK = 64;

	/**
	 * Threads per block of the sum-of-squares reduction, and the most blocks it is ever
	 * launched with. The block size is {@link #STRIDED_BLOCK} so that one launch helper
	 * serves both, and the block count is capped so the partials that come home are at
	 * most 8 KB whatever the operand -- and so that the number of them, hence the order
	 * the host adds them in, is a pure function of the length.
	 */
	private static final int SUMSQ_MAX_BLOCKS = 1024;

	/** The stand-in for a scalar operand of the three-way select; never resident. */
	private static final Object NO_ARRAY = new Object();

	/**
	 * The GEMV behind {@code vec:matvec} ({@code .todo/475}): one warp per row over a
	 * row-major matrix, accumulating in double at both widths. The one member whose worth
	 * is decided by residency rather than size -- see {@link #gemv}.
	 */
	static final String KERNEL_GEMV_F64 = "gemv_f64", KERNEL_GEMV_F32 = "gemv_f32";

	/**
	 * Threads per block for the GEMV: eight warps, so eight rows per block. The kernel is
	 * memory-bound and one warp reads one row with coalesced loads, so the block shape
	 * decides nothing but the grid size.
	 */
	private static final int GEMV_BLOCK = 256;

	/**
	 * The work one generator element is counted as for the sync decision: three
	 * square-and-multiply jumps (~31 iterations each) plus the draws themselves -- twelve
	 * for the Irwin-Hall rule, one otherwise.
	 */
	private static final long RNG_FLOPS_PER_ELEMENT = 256, RNG_FLOPS_PER_NORMAL = 1024;

	/**
	 * How many allocations share one {@code cuMemGetInfo} answer before it is asked
	 * again. The pre-flight against free memory costs 6-13 us a call on the GB10 (every
	 * intercepted call pays it, so it was 1.4% of a training run); the answer is re-asked
	 * after this many allocations, and sooner whenever a request is large against the
	 * remembered figure -- and the guard the figure serves is unchanged, because a
	 * request the stale estimate lets through that the device then refuses still lands on
	 * the failed-allocation trim below.
	 */
	private static final int FREE_MEMORY_REFRESH_INTERVAL = 64;

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
	 * The tile the 16x16 kernel is written around, and the BLOCK shape of every product
	 * kernel: 16x16 threads. The 16x16 kernel gives each thread one output element; the
	 * register-tiled single-float kernels give it a 4x4 or an 8x8 patch of a
	 * {@link #REGISTER_TILE_4 64x64} or {@link #REGISTER_TILE_8 128x128} block tile.
	 */
	private static final int TILE = 16;

	/** The block tile of {@code gemm_batched_f32_t4}: 64x64 outputs, 4x4 per thread. */
	private static final int REGISTER_TILE_4 = 4 * TILE;

	/** The block tile of {@code gemm_batched_f32_t8}: 128x128 outputs, 8x8 per thread. */
	private static final int REGISTER_TILE_8 = 8 * TILE;

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
	 * The page-locked bounce buffer every download is staged through, 16 MB: the device
	 * copies a result into it by DMA and the Java side copies it on into the result
	 * array. A download bigger than this goes in chunks of it.
	 *
	 * <p>
	 * Why stage at all, when "The library never stages" was measured and written down
	 * ({@code .kb/gpu.md}, "Linker.Option.critical takes heap segments here too"): that
	 * measurement copied to and from the SAME host array every iteration, and a training
	 * step does not -- every result is a fresh Java array, and on the machine this was
	 * built on a device copy into a page the GPU has never touched costs ~9 us per 4 KB
	 * page through the address-translation service, a hundred times the warm copy. The
	 * baseline build was hiding that: its uploads touched most of the eden, so the result
	 * arrays it later downloaded into were usually pages the GPU had seen. Device
	 * residency halves the uploads and the downloads went cold -- 619 of 7904 over a
	 * millisecond, 93% of the download time -- and a step got SLOWER with half the
	 * copies. The bounce buffer is never cold, the Java copy into the fresh array costs
	 * what a memcpy costs, and the array is then warm for any direct upload that follows.
	 */
	private static final long BOUNCE_BYTES = 16L << 20;

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
	 * The size, in ints, of the by-value layout struct every strided kernel with a layout
	 * takes ({@code strided_meta} in {@code gemm.cu}): the output dims plus up to three
	 * per-operand stride vectors at {@link Gpu#MAX_STRIDED_RANK}, which is what holds a
	 * layout to this size.
	 */
	private static final int LAYOUT_INTS = 4 * Gpu.MAX_STRIDED_RANK;

	/**
	 * How much free device memory a product leaves untouched. The device is shared --
	 * with the display, with other CUDA processes, and with whatever the driver itself
	 * wants -- so a product that would fit only by taking the last byte declines instead.
	 */
	private static final long ALLOCATION_HEADROOM = 64L << 20;

	/**
	 * The residency budget is the smaller of a share of free device memory -- a quarter,
	 * re-derived whenever the pre-flight re-reads {@code cuMemGetInfo} -- and
	 * {@link #RESIDENT_CAP}. It is a CEILING the LRU trims to, not a reservation: the
	 * pre-flight evicts everything a call is not holding before it would ever refuse one
	 * ({@link #allocate}), so residency can slow a call down by a copy but never turn it
	 * into a decline.
	 */
	private static final int RESIDENT_SHARE = 4;

	/**
	 * With LAZY results on the budget is not a share but a HEADROOM: everything the
	 * device has, less an eighth of it (and never less than half a gigabyte), because an
	 * eviction then costs a download AND a later upload rather than one upload, and what
	 * a training step keeps reachable -- every activation until its backward -- is what
	 * it is: at the book's shapes a quarter and then a half of this machine's free memory
	 * both evicted the graph by the tens of gigabytes per step ({@code .kb/gpu.md}). The
	 * pre-flight still evicts everything a call is not holding before it would refuse the
	 * call, so the headroom is for the rest of the process, not for the next launch.
	 */
	private static final int LAZY_HEADROOM_SHARE = 8;

	private static final long LAZY_HEADROOM_FLOOR = 512L << 20;

	/**
	 * 1 GB, and the cap is not a safety margin but the measurement that decides whether
	 * residency pays at all. The buffers the cache holds are buffers the driver's pool
	 * cannot hand out again, and what makes a per-call intercept affordable is that pool
	 * recycling its few warm blocks: with the budget left at a quarter of this machine's
	 * free memory (~30 GB) nothing was ever evicted, every allocation grew the pool (5 us
	 * a call instead of 1), and a 200-step training run was SLOWER with half the uploads
	 * than with none. Capped at 64 MB, 256 MB or 1 GB the same run was 5-10% faster than
	 * with no residency -- the three were within noise of each other -- so the cap is the
	 * largest of them, for a model whose chain is longer than this one's.
	 */
	private static final long RESIDENT_CAP = 1L << 30;

	private final CudaDriver driver;

	private final int device;

	private final MemorySegment context;

	private final MemorySegment module;

	private final MemorySegment gemmF64;

	private final MemorySegment gemmF32;

	private final MemorySegment gemmBatchedF64;

	private final MemorySegment gemmBatchedF32;

	private final MemorySegment gemmBatchedF32T4;

	private final MemorySegment gemmBatchedF32T8;

	/**
	 * The device's SM count, which is what decides between the three single-float product
	 * kernels ({@link #tileF32}): a register-tiled grid that does not fill about half the
	 * SMs loses to the 16x16 kernel's finer grid.
	 */
	private final int multiprocessors;

	private final MemorySegment mapF64;

	private final MemorySegment mapF32;

	/** The strided tier's six kernels, indexed by {@link #BCAST_F64} and its siblings. */
	private final MemorySegment[] strided;

	/**
	 * The resident tier's eight kernels, indexed by {@link #ZIP_F64} and its siblings.
	 */
	private final MemorySegment[] resident;

	/** The fused tier's kernels, indexed by {@link #GELU_F64} and its siblings. */
	private final MemorySegment[] fused;

	private final MemorySegment gemvF64;

	private final MemorySegment gemvF32;

	private final String description;

	/**
	 * The device's default memory pool, or {@link MemorySegment#NULL} when this driver
	 * has none. Held only so that an out-of-memory decline can hand back what the failed
	 * allocation grew the pool by.
	 */
	private final MemorySegment memoryPool;

	/**
	 * The download bounce buffer ({@link #BOUNCE_BYTES}), or {@link MemorySegment#NULL}
	 * on a driver without {@code cuMemHostAlloc}, in which case downloads go straight
	 * into the heap through the critical handle as they did before.
	 */
	private final MemorySegment bounce;

	/** Serializes the downloads, which share the one bounce buffer. */
	private final Object bounceLock = new Object();

	private final long syncFlopCeiling;

	/**
	 * Whether a kernel has been enqueued on the null stream since the last explicit wait.
	 * Only ever set under {@link #lazy} results, where the post-launch
	 * {@code cuCtxSynchronize} is skipped ({@link #awaitLaunched}); a CRITICAL copy asks
	 * {@link #awaitQueued} first so the queue's runtime never sits inside its
	 * safepoint-free window. Volatile because the interceptors may run from more than one
	 * thread; a race costs at worst one extra (or one late) synchronize, never a wrong
	 * answer -- the null stream orders the work itself.
	 */
	private volatile boolean queued;

	/**
	 * Whether per-call memory comes from the driver's pool. Not final: the fallback path
	 * is otherwise unreachable on a machine whose driver has a pool, and it is a path
	 * that has to keep computing the same answers. Flipped only by
	 * {@code GpuTest.withPooledAllocation}.
	 */
	private volatile boolean pooled;

	private volatile boolean usable = true;

	/**
	 * Whether a member's RESULT stays on the device until the host first reads it
	 * ({@link #lazyResults}), or is downloaded before the call returns as the library's
	 * default contract says. Off by default; the interceptors, which enumerate every host
	 * read of packed-array storage and materialize first, switch it on.
	 */
	private volatile boolean lazy;

	/**
	 * The last {@code cuMemGetInfo} answer less what has been allocated since, or
	 * {@code -1} when it must be asked again. A plain field on purpose: two threads
	 * racing on it can only make the estimate staler, and a stale estimate costs nothing
	 * but the trim a refused allocation already pays for.
	 */
	private long freeEstimate = -1;

	private int allocationsSinceRefresh;

	/**
	 * The resident copies: host array -> device buffer ({@link DeviceResidency}). Every
	 * member looks its operands up here before it allocates, records what it uploaded and
	 * what it downloaded, and frees what the cache dropped at the two safe moments.
	 */
	private final DeviceResidency residency = new DeviceResidency();

	/**
	 * A byte budget imposed from outside, or {@code -1} for the derived one. For the
	 * tests, which need the LRU to evict at a size they can afford to fill.
	 */
	private volatile long residentBudgetOverride = -1;

	private CudaGemm(CudaDriver driver, int device, MemorySegment context, MemorySegment module, MemorySegment gemmF64,
			MemorySegment gemmF32, MemorySegment gemmBatchedF64, MemorySegment gemmBatchedF32,
			MemorySegment gemmBatchedF32T4, MemorySegment gemmBatchedF32T8, int multiprocessors, MemorySegment mapF64,
			MemorySegment mapF32, MemorySegment[] strided, MemorySegment[] resident, MemorySegment[] fused,
			MemorySegment gemvF64, MemorySegment gemvF32, boolean pooled, MemorySegment memoryPool,
			MemorySegment bounce, long syncFlopCeiling, String description) {
		this.driver = driver;
		this.device = device;
		this.context = context;
		this.module = module;
		this.gemmF64 = gemmF64;
		this.gemmF32 = gemmF32;
		this.gemmBatchedF64 = gemmBatchedF64;
		this.gemmBatchedF32 = gemmBatchedF32;
		this.gemmBatchedF32T4 = gemmBatchedF32T4;
		this.gemmBatchedF32T8 = gemmBatchedF32T8;
		this.multiprocessors = multiprocessors;
		this.mapF64 = mapF64;
		this.mapF32 = mapF32;
		this.strided = strided;
		this.resident = resident;
		this.fused = fused;
		this.gemvF64 = gemvF64;
		this.gemvF32 = gemvF32;
		this.pooled = pooled;
		this.memoryPool = memoryPool;
		this.bounce = bounce;
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
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_BATCHED_F32_T4));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_BATCHED_F32_T4 + ": " + driver.errorString(status));
			}
			MemorySegment batchedF32T4 = functionOut.get(P, 0);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_BATCHED_F32_T8));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_BATCHED_F32_T8 + ": " + driver.errorString(status));
			}
			MemorySegment batchedF32T8 = functionOut.get(P, 0);
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
			MemorySegment[] resident = new MemorySegment[KERNELS_RESIDENT.length];
			for (int i = 0; i < KERNELS_RESIDENT.length; i++) {
				status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNELS_RESIDENT[i]));
				if (status != CuResult.SUCCESS) {
					return unwind(driver, device, true, module,
							"cuModuleGetFunction " + KERNELS_RESIDENT[i] + ": " + driver.errorString(status));
				}
				resident[i] = functionOut.get(P, 0);
			}
			MemorySegment[] fused = new MemorySegment[KERNELS_FUSED.length];
			for (int i = 0; i < KERNELS_FUSED.length; i++) {
				status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNELS_FUSED[i]));
				if (status != CuResult.SUCCESS) {
					return unwind(driver, device, true, module,
							"cuModuleGetFunction " + KERNELS_FUSED[i] + ": " + driver.errorString(status));
				}
				fused[i] = functionOut.get(P, 0);
			}
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_GEMV_F64));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_GEMV_F64 + ": " + driver.errorString(status));
			}
			MemorySegment gemvF64 = functionOut.get(P, 0);
			status = driver.moduleGetFunction(functionOut, module, arena.allocateFrom(KERNEL_GEMV_F32));
			if (status != CuResult.SUCCESS) {
				return unwind(driver, device, true, module,
						"cuModuleGetFunction " + KERNEL_GEMV_F32 + ": " + driver.errorString(status));
			}
			MemorySegment gemvF32 = functionOut.get(P, 0);
			MemorySegment pool = MemorySegment.NULL;
			boolean pooled = pooledAllocationWorks(driver, arena);
			if (pooled) {
				MemorySegment poolOut = arena.allocate(P);
				pooled = driver.deviceGetDefaultMemPool(poolOut, device) == CuResult.SUCCESS;
				if (pooled) {
					pool = poolOut.get(P, 0);
					// Keep the pool's reserve across synchronizations. The driver's
					// default
					// threshold of 0 hands every unused reserved byte back to the OS at
					// each cuCtxSynchronize and each synchronous copy, and with resident
					// copies in play (DeviceResidency) the reserve is no longer three
					// buffers but a training step's worth of them: measured, releasing
					// and re-mapping it around every call made the device-to-host copies
					// four times as expensive and the step slower than without residency.
					// A failed allocation still trims the pool explicitly
					// (trimMemoryPool), so a decline still costs the device nothing.
					MemorySegment threshold = arena.allocate(L);
					threshold.set(L, 0, -1L);
					driver.memPoolSetAttribute(pool, CudaDriver.MEMPOOL_ATTR_RELEASE_THRESHOLD, threshold);
				}
			}
			// The download bounce buffer (BOUNCE_BYTES). Allocated here rather than on
			// the
			// first download so that every leak test's baseline already includes it.
			MemorySegment bounce = MemorySegment.NULL;
			MemorySegment bounceOut = arena.allocate(P);
			if (driver.memHostAlloc(bounceOut, BOUNCE_BYTES, 0) == CuResult.SUCCESS) {
				bounce = bounceOut.get(P, 0).reinterpret(BOUNCE_BYTES);
			}
			int multiprocessors = attribute(driver, arena, CudaDriver.ATTRIBUTE_MULTIPROCESSOR_COUNT, device);
			long ceiling = SYNC_FLOPS_PER_MULTIPROCESSOR * Math.max(1, multiprocessors);
			String description = describe(driver, arena, device) + (pooled ? "" : ", unpooled allocation");
			return new Probe(new CudaGemm(driver, device, context, module, f64, f32, batchedF64, batchedF32,
					batchedF32T4, batchedF32T8, multiprocessors, mapF64, mapF32, strided, resident, fused, gemvF64,
					gemvF32, pooled, pool, bounce, ceiling, description), description);
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
	@Override
	public String description() {
		return this.description;
	}

	/** Whether the context is still usable -- see the sticky-error rule on the class. */
	@Override
	public boolean usable() {
		return this.usable;
	}

	/**
	 * Whether per-call device memory comes from the driver's pool. It decides the size
	 * threshold, because it decides the floor: 15 us a call pooled, 170 us unpooled.
	 */
	/**
	 * Always {@code true}: fp64 is a tenth to a fortieth of this hardware's single-float
	 * throughput, which is a reason to prefer {@code #f} and not a reason to decline.
	 * Metal is the backend where the answer is {@code false}.
	 * @return {@code true}
	 */
	@Override
	public boolean supportsDouble() {
		return true;
	}

	/**
	 * {@link Gpu}'s own constants, in whichever of the two sets this driver's allocator
	 * put in force -- the unpooled floor is ~180 us against ~16, so every threshold moves
	 * with it. Read once, by the probe: {@link #setPooledAllocation} flips the path a
	 * later call takes but not the size the feature is documented to accept.
	 * @return the thresholds in force on this device
	 */
	@Override
	public Thresholds thresholds() {
		return this.pooled
				? new Thresholds(Gpu.POOLED_MIN_WORK, Gpu.MAP_POOLED_MIN_ELEMENTS, Gpu.STRIDED_POOLED_MIN_ELEMENTS,
						Gpu.FOLD_POOLED_MIN_ELEMENTS, Gpu.RNG_POOLED_MIN_ELEMENTS, Gpu.MATVEC_POOLED_MIN_ELEMENTS)
				: new Thresholds(Gpu.UNPOOLED_MIN_WORK, Gpu.MAP_UNPOOLED_MIN_ELEMENTS,
						Gpu.STRIDED_UNPOOLED_MIN_ELEMENTS, Gpu.FOLD_UNPOOLED_MIN_ELEMENTS,
						Gpu.RNG_UNPOOLED_MIN_ELEMENTS, Gpu.MATVEC_UNPOOLED_MIN_ELEMENTS);
	}

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
		// A resident copy was allocated by the allocator in force when it was made and
		// must be freed by the same one, so nothing may stay resident across the switch.
		releaseResident();
		this.pooled = wanted && !this.memoryPool.equals(MemorySegment.NULL);
		return previous;
	}

	/**
	 * Free device memory in bytes, or {@code -1} when the driver would not say. Exists
	 * for the leak test: a run of products must not move it, because every buffer a
	 * product allocates it also frees.
	 */
	@Override
	public long freeDeviceMemory() {
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
	 * Bytes currently outstanding from THIS process's stream-ordered pool, or {@code -1}
	 * when allocation is unpooled or the driver will not say. {@link #freeDeviceMemory()}
	 * asks {@code cuMemGetInfo}, which answers for the whole DEVICE -- on a
	 * unified-memory machine that is the whole MACHINE's free memory, so a sibling
	 * process (another surefire fork, or anything else running at the same time) moving
	 * it looks exactly like a leak. {@code CU_MEMPOOL_ATTR_USED_MEM_CURRENT} is scoped to
	 * the pool HANDLE it is asked of, which this process created and no other process can
	 * allocate from, so it answers only for what this device object itself has
	 * outstanding -- the leak test's actual question, asked directly instead of inferred
	 * from a shared counter.
	 */
	long poolBytesInUse() {
		if (this.memoryPool.equals(MemorySegment.NULL)) {
			return -1;
		}
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
				return -1;
			}
			MemorySegment value = arena.allocate(L);
			return this.driver.memPoolGetAttribute(this.memoryPool, CudaDriver.MEMPOOL_ATTR_USED_MEM_CURRENT,
					value) == CuResult.SUCCESS ? value.get(L, 0) : -1;
		}
		catch (Throwable ex) {
			return -1;
		}
	}

	/**
	 * {@code c = a x b} for a row-major {@code n x m} by {@code m x p} pair of packed
	 * double-float arrays, each read from its own element offset.
	 * @return {@code true} when {@code c} was filled, {@code false} when the product
	 * declined or the device failed -- in which case {@code c} is untouched
	 */
	@Override
	public boolean gemm(double[] a, int oa, double[] b, int ob, double[] c, int oc, int n, int m, int p) {
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
	@Override
	public boolean gemm(double[] a, int oa, int sa, double[] b, int ob, int sb, double[] c, int oc, int batch, int n,
			int m, int p) {
		return gemmT(a, oa, sa, false, b, ob, sb, false, c, oc, batch, n, m, p);
	}

	/**
	 * The stacked product with either operand read TRANSPOSED -- its {@code n x m} (or
	 * {@code m x p}) matrix stored with the last two axes exchanged, which is what the
	 * linear backward's {@code g . b^T} and {@code a^T . g} hand it (2026-09-02).
	 *
	 * <p>
	 * The kernel indexes the operand where it already is instead of reading a gather's
	 * copy of it, so the pass the transpose used to cost disappears. The fold is
	 * untouched -- k ascending, one {@code fma} per term -- so the result is the
	 * untransposed product's bit for bit and the orientation is invisible above this
	 * seam.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean gemmT(double[] a, int oa, int sa, boolean ta, double[] b, int ob, int sb, boolean tb, double[] c,
			int oc, int batch, int n, int m, int p) {
		return gemm(MemorySegment.ofArray(a), a, oa, sa, MemorySegment.ofArray(b), b, ob, sb, MemorySegment.ofArray(c),
				c, oc, batch, n, m, p, Double.BYTES,
				new Tile(batch == 1 && !(ta || tb) ? this.gemmF64 : this.gemmBatchedF64, TILE, TILE,
						batch > 1 || ta || tb),
				ta, tb);
	}

	/**
	 * The single-float sibling of {@link #gemm} -- the width the device is actually good
	 * at, by a factor of 44 on the hardware this was measured on.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean gemmF(float[] a, int oa, float[] b, int ob, float[] c, int oc, int n, int m, int p) {
		return gemmF(a, oa, 0, b, ob, 0, c, oc, 1, n, m, p);
	}

	/**
	 * The single-float sibling of
	 * {@link #gemm(double[], int, int, double[], int, int, double[], int, int, int, int, int)}
	 * -- the stacked product at the width the device is actually good at.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean gemmF(float[] a, int oa, int sa, float[] b, int ob, int sb, float[] c, int oc, int batch, int n,
			int m, int p) {
		return gemmFT(a, oa, sa, false, b, ob, sb, false, c, oc, batch, n, m, p);
	}

	/**
	 * The single-float sibling of
	 * {@link #gemmT(double[], int, int, boolean, double[], int, int, boolean, double[], int, int, int, int, int)}
	 * -- the width the linear backward actually runs at.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean gemmFT(float[] a, int oa, int sa, boolean ta, float[] b, int ob, int sb, boolean tb, float[] c,
			int oc, int batch, int n, int m, int p) {
		return gemm(MemorySegment.ofArray(a), a, oa, sa, MemorySegment.ofArray(b), b, ob, sb, MemorySegment.ofArray(c),
				c, oc, batch, n, m, p, Float.BYTES, tileF32(batch, n, p, ta || tb), ta, tb);
	}

	/**
	 * One product kernel and its block tile: the entry point, the outputs one block
	 * covers on each axis (16x16 threads either way), and whether the kernel takes the
	 * batched parameter block -- the 16x16 kernel only when the batch is more than one,
	 * the register-tiled ones always.
	 */
	private record Tile(MemorySegment function, int rows, int columns, boolean batchedParameters) {
	}

	/**
	 * Which single-float kernel runs this shape. All three fold every cell identically (k
	 * ascending, one fused multiply-add per term, K padded to 16 with zeros), so the
	 * choice is invisible in the result and is made on speed alone. Measured on a GB10
	 * (48 SMs; {@code .todo/123-gpu-acceleration/gemm-tile-probe.cu}): the 128x128 tile
	 * is 2.2-4x the 16x16 kernel once its grid holds about half the SMs -- a
	 * transformer's feed-forward products, n >= 768 square -- and LOSES below that, where
	 * the grid is too small to fill the card (0.3x at 256x256); the 64x64 tile is the
	 * middle rung, 1.2-2.3x from a full wave of blocks up and a tie below, and the one
	 * that takes a batch of SHORT rows (the book's batch-64 of 64-row slabs: 2.3x, where
	 * the 128-row tile wastes half of itself). An operand narrower than the tile on
	 * either output axis stays on the finer kernel: padding would waste the tile. The
	 * thresholds are in SMs so that a smaller card moves them down with it.
	 */
	private Tile tileF32(int batch, int n, int p, boolean transposed) {
		long blocks8 = (long) ceilDiv(n, REGISTER_TILE_8) * ceilDiv(p, REGISTER_TILE_8) * batch;
		if (n >= REGISTER_TILE_8 && p >= REGISTER_TILE_8 && blocks8 >= Math.max(1, this.multiprocessors / 2)) {
			return new Tile(this.gemmBatchedF32T8, REGISTER_TILE_8, REGISTER_TILE_8, true);
		}
		long blocks4 = (long) ceilDiv(n, REGISTER_TILE_4) * ceilDiv(p, REGISTER_TILE_4) * batch;
		if (n >= REGISTER_TILE_4 && p >= REGISTER_TILE_4 && blocks4 >= Math.max(1, this.multiprocessors)) {
			return new Tile(this.gemmBatchedF32T4, REGISTER_TILE_4, REGISTER_TILE_4, true);
		}
		// The plain entry point has no orientation parameter, so a transposed product of
		// one slab takes the batched one with a stride of 0 rather than a kernel of its
		// own.
		return new Tile(batch == 1 && !transposed ? this.gemmF32 : this.gemmBatchedF32, TILE, TILE,
				batch > 1 || transposed);
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}

	/**
	 * One width-independent product round trip; the two public forms differ only in it.
	 * The host arrays ride along beside their segments because they are the residency
	 * KEYS: an operand a recent call uploaded or produced is found by identity and not
	 * copied up again, and the result is recorded after its download
	 * ({@link DeviceResidency}). Every member below has the same shape: look the operands
	 * up, allocate only what is not resident, stage what was missed, launch, and record
	 * the result on the way out.
	 */
	private boolean gemm(MemorySegment a, Object ah, int oa, int sa, MemorySegment b, Object bh, int ob, int sb,
			MemorySegment c, Object ch, int oc, int batch, int n, int m, int p, int width, Tile tile, boolean ta,
			boolean tb) {
		if (!this.usable) {
			return false;
		}
		long aBytes = span(batch, sa, (long) n * m) * width, bBytes = span(batch, sb, (long) m * p) * width,
				cBytes = (long) batch * n * p * width;
		long offA = (long) oa * width, offB = (long) ob * width, offC = (long) oc * width;
		long[] buffers = { 0, 0, 0 }, owned = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, aBytes);
			buffers[1] = this.residency.lookup(bh, offB, bBytes);
			if (!allocate(arena, buffers, owned, aBytes, bBytes, cBytes)) {
				return false;
			}
			boolean sync = 2L * batch * n * m * p >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, aBytes) || !stage(buffers, owned, 1, bh, b, offB, bBytes)
					|| !launch(arena, tile, buffers, batch, sa, sb, n, m, p, ta, tb, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 2, ch, cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
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
	@Override
	public boolean map(int op, double[] a, int oa, double[] c, int oc, int n) {
		return map(this.mapF64, MemorySegment.ofArray(a), a, oa, MemorySegment.ofArray(c), c, oc, n, op, Double.BYTES);
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
	@Override
	public boolean mapF(int op, float[] a, int oa, float[] c, int oc, int n) {
		return map(this.mapF32, MemorySegment.ofArray(a), a, oa, MemorySegment.ofArray(c), c, oc, n, op, Float.BYTES);
	}

	/** One width-independent map round trip; the two public forms differ only in it. */
	private boolean map(MemorySegment kernel, MemorySegment a, Object ah, int oa, MemorySegment c, Object ch, int oc,
			int n, int op, int width) {
		if (!this.usable) {
			return false;
		}
		long bytes = (long) n * width, offA = (long) oa * width, offC = (long) oc * width;
		long[] buffers = { 0, 0 }, owned = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, bytes);
			if (!allocate(arena, buffers, owned, bytes, bytes)) {
				return false;
			}
			boolean sync = (long) n * MAP_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, bytes) || !launchMap(arena, kernel, buffers, n, op, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 1, ch, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	// --- the strided tier ------------------------------------------------------------
	// Three buffers for a broadcast binary op, two for a gather or a fold: the operands
	// and the result. The LAYOUT -- the output dims followed by one source stride per
	// output axis per operand -- rides BY VALUE in the kernel parameter block
	// ({@link #layout}): it used to be a fourth device buffer, which cost one pooled
	// allocation and one synchronous 192-byte copy per call, and that copy is what a
	// profile of the book's-shape training step showed DRAINING the null-stream queue --
	// a synchronous cuMemcpyHtoD orders behind every queued kernel, so each strided call
	// was a hidden cuCtxSynchronize and the pipeline could never run ahead (about a
	// thousand of them per step).

	/**
	 * {@code out[i] = op(a[ia(i)], b[ib(i)])} over a BROADCAST binary element-wise op:
	 * the output is {@code dims} row-major and each operand follows its own per-axis
	 * stride, 0 where it is stretched. Bit-identical to a scalar widen-compute-narrow
	 * walk at both widths -- the kernel computes in double and narrows only on the store.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean bcast(int op, double[] a, int oa, int[] sa, double[] b, int ob, int[] sb, double[] c, int oc,
			int[] dims) {
		return bcast(op, MemorySegment.ofArray(a), a, oa, sa, MemorySegment.ofArray(b), b, ob, sb,
				MemorySegment.ofArray(c), c, oc, dims, Double.BYTES, this.strided[BCAST_F64]);
	}

	/**
	 * The single-float sibling of
	 * {@link #bcast(int, double[], int, int[], double[], int, int[], double[], int, int[])}.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean bcastF(int op, float[] a, int oa, int[] sa, float[] b, int ob, int[] sb, float[] c, int oc,
			int[] dims) {
		return bcast(op, MemorySegment.ofArray(a), a, oa, sa, MemorySegment.ofArray(b), b, ob, sb,
				MemorySegment.ofArray(c), c, oc, dims, Float.BYTES, this.strided[BCAST_F32]);
	}

	/**
	 * One width-independent broadcast round trip; the two public forms differ only in it.
	 */
	private boolean bcast(int op, MemorySegment a, Object ah, int oa, int[] sa, MemorySegment b, Object bh, int ob,
			int[] sb, MemorySegment c, Object ch, int oc, int[] dims, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int rank = dims.length;
		int n = count(dims);
		long aBytes = (span(dims, sa) + 1L) * width, bBytes = (span(dims, sb) + 1L) * width, cBytes = (long) n * width;
		long offA = (long) oa * width, offB = (long) ob * width, offC = (long) oc * width;
		long[] buffers = { 0, 0, 0 }, owned = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, aBytes);
			buffers[1] = this.residency.lookup(bh, offB, bBytes);
			if (!allocate(arena, buffers, owned, aBytes, bBytes, cBytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, aBytes) || !stage(buffers, owned, 1, bh, b, offB, bBytes)
					|| !launchStrided(arena, kernel, n, new long[] { op, buffers[0], buffers[1], buffers[2], n, rank },
							new boolean[] { false, true, true, true, false, false }, layout(arena, dims, sa, sb, null),
							sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 2, ch, cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * {@code out[i] = a[ia(i)]}: the permuted COPY behind an axes transpose, one source
	 * stride per output axis. A copy, so trivially bit-identical.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean gather(double[] a, int oa, int[] sa, double[] c, int oc, int[] dims) {
		return gather(MemorySegment.ofArray(a), a, oa, sa, MemorySegment.ofArray(c), c, oc, dims, Double.BYTES,
				this.strided[GATHER_F64]);
	}

	/**
	 * The single-float sibling of
	 * {@link #gather(double[], int, int[], double[], int, int[])}.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean gatherF(float[] a, int oa, int[] sa, float[] c, int oc, int[] dims) {
		return gather(MemorySegment.ofArray(a), a, oa, sa, MemorySegment.ofArray(c), c, oc, dims, Float.BYTES,
				this.strided[GATHER_F32]);
	}

	private boolean gather(MemorySegment a, Object ah, int oa, int[] sa, MemorySegment c, Object ch, int oc, int[] dims,
			int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int rank = dims.length;
		int n = count(dims);
		long aBytes = (span(dims, sa) + 1L) * width, cBytes = (long) n * width;
		long offA = (long) oa * width, offC = (long) oc * width;
		long[] buffers = { 0, 0 }, owned = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, aBytes);
			if (!allocate(arena, buffers, owned, aBytes, cBytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, aBytes)
					|| !launchStrided(arena, kernel, n, new long[] { buffers[0], buffers[1], n, rank },
							new boolean[] { true, true, false, false }, layout(arena, dims, sa, null, null), sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 1, ch, cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * The fold of one axis: {@code outer * inner} output cells, each walking its own
	 * {@code len} elements in ASCENDING order in a double accumulator. Sequential per
	 * cell on purpose -- a tree reduction would be faster and would not be the caller's
	 * sum.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean fold(int op, double[] a, int oa, double[] c, int oc, int outer, int len, int inner) {
		return fold(op, MemorySegment.ofArray(a), a, oa, MemorySegment.ofArray(c), c, oc, outer, len, inner,
				Double.BYTES, this.strided[FOLD_F64]);
	}

	/**
	 * The single-float sibling of
	 * {@link #fold(int, double[], int, double[], int, int, int, int)}.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean foldF(int op, float[] a, int oa, float[] c, int oc, int outer, int len, int inner) {
		return fold(op, MemorySegment.ofArray(a), a, oa, MemorySegment.ofArray(c), c, oc, outer, len, inner,
				Float.BYTES, this.strided[FOLD_F32]);
	}

	private boolean fold(int op, MemorySegment a, Object ah, int oa, MemorySegment c, Object ch, int oc, int outer,
			int len, int inner, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int cells = outer * inner;
		long aBytes = (long) cells * len * width, cBytes = (long) cells * width;
		long offA = (long) oa * width, offC = (long) oc * width;
		long[] buffers = { 0, 0 }, owned = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, aBytes);
			if (!allocate(arena, buffers, owned, aBytes, cBytes)) {
				return false;
			}
			boolean sync = (long) cells * len * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, aBytes) || !launchStrided(arena, kernel, cells,
					new long[] { op, buffers[0], buffers[1], outer, len, inner },
					new boolean[] { false, true, true, false, false, false }, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 1, ch, cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
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
	 * Packs the layout -- the output dims, then one source stride per output axis per
	 * operand -- into the fixed-size struct the strided kernels take BY VALUE
	 * ({@code strided_meta} in {@code gemm.cu}): always {@link #LAYOUT_INTS} ints, the
	 * unused tail zero, because {@code cuLaunchKernel} copies the declared parameter
	 * size. The driver hands it to the kernel with the rest of the parameter block, so
	 * unlike the device buffer this replaces it costs no allocation and -- the reason it
	 * moved -- no synchronous copy that would drain the null-stream queue.
	 */
	private MemorySegment layout(Arena arena, int[] dims, int[] sa, int @Nullable [] sb, int @Nullable [] sc) {
		int rank = dims.length;
		MemorySegment meta = arena.allocate(I, LAYOUT_INTS);
		for (int k = 0; k < rank; k++) {
			meta.setAtIndex(I, k, dims[k]);
			meta.setAtIndex(I, rank + k, sa[k]);
			if (sb != null) {
				meta.setAtIndex(I, 2 * rank + k, sb[k]);
			}
			if (sc != null) {
				meta.setAtIndex(I, 3 * rank + k, sc[k]);
			}
		}
		return meta;
	}

	/**
	 * One flat launch over {@code n} output cells, one thread each. The parameter block
	 * is described by two parallel arrays -- the values, and whether each is a device
	 * POINTER (8 bytes) or an {@code int} (4) -- because the strided kernels take
	 * different mixtures of the two, plus, where the kernel has one, the by-value
	 * {@link #layout} struct as the final parameter.
	 */
	private boolean launchStrided(Arena arena, MemorySegment function, int n, long[] values, boolean[] pointer,
			boolean sync) throws Throwable {
		return launchStrided(arena, function, n, values, pointer, null, sync);
	}

	private boolean launchStrided(Arena arena, MemorySegment function, int n, long[] values, boolean[] pointer,
			@Nullable MemorySegment meta, boolean sync) throws Throwable {
		MemorySegment parameters = arena.allocate(P, values.length + (meta == null ? 0 : 1));
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
		if (meta != null) {
			parameters.setAtIndex(P, values.length, meta);
		}
		int status = this.driver.launchKernel(function, (n + STRIDED_BLOCK - 1) / STRIDED_BLOCK, 1, 1, STRIDED_BLOCK, 1,
				1, 0, MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		return awaitLaunched(sync);
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
	private boolean allocate(Arena arena, long[] buffers, long[] owned, long... sizes) throws Throwable {
		MemorySegment out = arena.allocate(L);
		// Only the slots the residency lookup left empty are allocated; a resident
		// operand
		// is already where the kernel wants it.
		long total = 0;
		for (int i = 0; i < sizes.length; i++) {
			if (buffers[i] == 0) {
				total += sizes[i];
			}
		}
		// The pre-flight, amortized: the driver is asked every
		// FREE_MEMORY_REFRESH_INTERVAL allocations, and sooner when a request is large
		// against the remembered figure; in between, the figure is decremented by what
		// was handed out, so the estimate only ever errs on the side of refusing.
		long free = this.freeEstimate;
		if (free < 0 || ++this.allocationsSinceRefresh >= FREE_MEMORY_REFRESH_INTERVAL || total > free / 4) {
			free = refreshFreeMemory(arena);
		}
		if (free >= 0 && total > free - ALLOCATION_HEADROOM && this.residency.occupied()) {
			// The resident copies must never be the reason a call declines. First the
			// ones the program has dropped and the collector has not yet noticed (the
			// stubs' case, DeviceResidency's class comment): collect, drain, trim the
			// pool's reserve back to the device, ask again ...
			collect();
			trimMemoryPool();
			free = refreshFreeMemory(arena);
		}
		if (free >= 0 && total > free - ALLOCATION_HEADROOM && this.residency.occupied()) {
			// ... and only then give back every copy this call is not holding, live
			// ones included, hand the pool's reserve back again, and ask once more.
			this.residency.evictAll(buffers);
			settle();
			drainPending();
			trimMemoryPool();
			free = refreshFreeMemory(arena);
		}
		if (free >= 0 && total > free - ALLOCATION_HEADROOM) {
			return false;
		}
		for (int i = 0; i < sizes.length; i++) {
			// A slot the lookup filled, or an operand the call does not have (a fused
			// adjoint's absent accumulated gradient, size 0), allocates nothing.
			if (buffers[i] != 0 || sizes[i] == 0) {
				continue;
			}
			int status = this.pooled ? this.driver.memAllocAsync(out, sizes[i]) : this.driver.memAlloc(out, sizes[i]);
			if (status != CuResult.SUCCESS) {
				// Order matters, and getting it wrong is silent: the buffers that DID
				// allocate have to go back to the pool before the pool is trimmed, or the
				// trim finds them still in use and keeps their memory. Measured with the
				// two swapped, a declined product held 78 GB of a 128 GB device. The
				// resident copies go back too, for the same reason the pre-flight above
				// gives them back.
				release(owned);
				this.residency.evictAll(buffers);
				settle();
				drainPending();
				trimMemoryPool();
				this.freeEstimate = -1;
				return fail(status);
			}
			buffers[i] = out.get(L, 0);
			owned[i] = buffers[i];
		}
		if (free >= 0) {
			this.freeEstimate = free - total;
		}
		return true;
	}

	/**
	 * Re-asks the driver how much device memory is free, resets the amortization, and
	 * re-derives the residency budget from the answer -- a quarter of what this process
	 * could hold, the resident bytes themselves counted back in, capped at
	 * {@link #RESIDENT_CAP}.
	 */
	private long refreshFreeMemory(Arena arena) throws Throwable {
		long free = freeDeviceMemory(arena);
		this.freeEstimate = free;
		this.allocationsSinceRefresh = 0;
		long override = this.residentBudgetOverride;
		// Lazily, neither the cap nor the share applies: an eviction is then a download
		// AND
		// a later upload, and the pool's recycling, which the cap exists for, is worth
		// less
		// than either; the budget is what the device has less a headroom (.kb/gpu.md, "A
		// result comes home on first host touch").
		long held = free < 0 ? 0 : free + this.residency.bytes();
		this.residency.setBudget(override >= 0 ? override
				: this.lazy ? Math.max(0, held - Math.max(held / LAZY_HEADROOM_SHARE, LAZY_HEADROOM_FLOOR))
						: Math.min(RESIDENT_CAP, held / RESIDENT_SHARE));
		return free;
	}

	/**
	 * The start of every call: makes the context current and frees what the cache has
	 * dropped since the last call. This is one of the two moments a pending free is safe
	 * to enqueue -- before any operand of THIS call has been looked up, so no buffer the
	 * coming launch reads can be among them (see {@link DeviceResidency}).
	 */
	private boolean enter() throws Throwable {
		if (this.driver.ctxSetCurrent(this.context) != CuResult.SUCCESS) {
			return false;
		}
		drainPending();
		return true;
	}

	/**
	 * Moves operand {@code i} up unless the residency lookup already found it there, and
	 * records the copy so the next call over the same array finds it. The buffer then
	 * belongs to the cache: it is struck from {@code owned} so {@link #release} leaves
	 * it.
	 */
	private boolean stage(long[] buffers, long[] owned, int i, Object host, MemorySegment heap, long offset, long bytes)
			throws Throwable {
		if (owned[i] == 0) {
			return true;
		}
		// A stub (DeviceResidency, the class comment) is uploaded from its backing; its
		// own segment is too short to hold the span.
		MemorySegment source = heap.byteSize() >= offset + bytes ? heap
				: heap(this.residency.source(host, offset, bytes));
		if (!upload(buffers[i], source, offset, bytes)) {
			return false;
		}
		this.residency.put(host, offset, bytes, buffers[i], false);
		owned[i] = 0;
		return trim(buffers) && settle();
	}

	/**
	 * After a {@code put} that left the cache over budget with only DIRTY entries to
	 * evict: wake the collector first, if it is due, so that the results the program has
	 * already dropped -- stubs the young generation never filled up enough to collect --
	 * go back to the pool instead of being flushed into fresh host arrays; drain what it
	 * released; and only then evict what is still over budget, as flushes
	 * ({@link DeviceResidency}, the class comment). The call's own buffers are kept.
	 */
	private boolean trim(long[] buffers) throws Throwable {
		if (!this.residency.collectionWanted()) {
			return true;
		}
		collect();
		this.residency.evictOverBudget(buffers);
		return settle();
	}

	/**
	 * Runs the collector when the cache says enough has been produced since the last
	 * time, and frees what it released. {@code System.gc()} is a request the JVM may
	 * ignore ({@code -XX:+DisableExplicitGC}); then the eviction that follows is what it
	 * was before, a flush.
	 */
	private void collect() {
		if (this.residency.collectionDue()) {
			System.gc();
			this.residency.collected();
		}
		drainPending();
	}

	/**
	 * The end of every successful call. Eagerly (the library's default), the result comes
	 * down and -- device and host now holding the same bytes -- the buffer is recorded as
	 * the host array's CLEAN resident copy rather than freed, which is what makes a chain
	 * of members pay for one upload. Lazily ({@link #lazyResults}), nothing comes down:
	 * the buffer is recorded as the array's DIRTY copy -- the device holds the bytes and
	 * the host does not -- and the download happens on the host's first read of the array
	 * ({@link #materialize}) or never. A buffer the call did not allocate (an in-place
	 * member over a resident array) is marked rather than recorded. Then the other safe
	 * moment to free what the cache dropped: the launch has been enqueued (and, eagerly,
	 * the synchronous download has returned), so nothing the kernel read is freed
	 * underneath it.
	 */
	private boolean finish(MemorySegment heap, long offset, long[] buffers, long[] owned, int i, Object host,
			long bytes) throws Throwable {
		if (this.lazy) {
			if (owned[i] != 0) {
				this.residency.put(host, offset, bytes, buffers[i], true);
				owned[i] = 0;
				if (!trim(buffers)) {
					return false;
				}
			}
			else {
				this.residency.markDirty(host, offset, bytes);
			}
		}
		else {
			// Eagerly a stub's backing is allocated here and filled before the call
			// returns, which is what keeps the library's own contract for an embedder
			// that hands over stubs without lazy results.
			MemorySegment target = heap.byteSize() >= offset + bytes ? heap
					: heap(this.residency.storageFor(host, offset, bytes));
			if (!download(target, offset, buffers[i], bytes)) {
				return false;
			}
			if (owned[i] != 0) {
				this.residency.put(host, offset, bytes, buffers[i], false);
				owned[i] = 0;
				if (!trim(buffers)) {
					return false;
				}
			}
		}
		if (!settle()) {
			return false;
		}
		drainPending();
		return true;
	}

	/**
	 * Performs every flush the cache has let go of since the last call here -- a DIRTY
	 * copy it evicted, released or replaced, whose bytes the host array does not have:
	 * each is downloaded into its array and its buffer then queued for the next drain.
	 * Called immediately after every cache operation that can produce one, so that no
	 * array is ever without an entry while the device still holds its only bytes. The
	 * buffers are queued rather than freed here because a flush can run BEFORE a launch
	 * that reads the same buffer (an eviction inside {@link #stage}), and a free must
	 * stay behind that launch.
	 */
	private boolean settle() throws Throwable {
		for (DeviceResidency.Flush flush : this.residency.flushes()) {
			if (!download(heap(flush.target()), flush.offset(), flush.pointer(), flush.bytes())) {
				this.residency.release(flush.pointer());
				return false;
			}
			this.residency.release(flush.pointer());
		}
		return true;
	}

	/**
	 * The heap segment over a host array or a backing, which is always one of the two.
	 */
	private static MemorySegment heap(Object host) {
		return host instanceof float[] f ? MemorySegment.ofArray(f) : MemorySegment.ofArray((double[]) host);
	}

	/**
	 * Brings a DIRTY copy of {@code host} home -- the download every host read of
	 * packed-array storage performs first under {@link #lazyResults} -- and answers the
	 * array the host must read: {@code host} itself, or its backing when {@code host} is
	 * a stub ({@link DeviceResidency}, the class comment). Cheap when it does not matter:
	 * a volatile read when nothing is dirty and no stub is backed, and a few identity
	 * compares for a loop that reads one array. When it does matter it is the one
	 * operation of this library that cannot decline: the host has no other copy of those
	 * bytes, so a download the driver refuses is an error rather than a fallback.
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
		boolean ok;
		try {
			ok = this.driver.ctxSetCurrent(this.context) == CuResult.SUCCESS
					&& download(heap(flush.target()), flush.offset(), flush.pointer(), flush.bytes());
		}
		catch (Throwable ex) {
			throw new IllegalStateException("a device result could not be brought home: " + ex, ex);
		}
		if (!ok) {
			throw new IllegalStateException("a device result could not be brought home: the driver refused the copy");
		}
		return claim.storage();
	}

	/**
	 * Whether a copy of {@code host} is resident -- the question the resident tier asks
	 * before it offers a member. No context, no driver call.
	 * @param host the host array
	 * @return {@code true} when a device buffer holds a copy of it
	 */
	@Override
	public boolean resident(Object host) {
		return this.residency.resident(host);
	}

	@Override
	public long extent(Object host) {
		return this.residency.extent(host);
	}

	/** {@code true}: measured on a GB10, a fifth off the training step and then half. */
	@Override
	public boolean lazyResultsPay() {
		return true;
	}

	@Override
	public boolean lazyResultsOn() {
		return this.lazy;
	}

	/**
	 * Switches lazy results on or off. Switching OFF brings every dirty copy home first,
	 * so that the eager contract -- a result is in its array when the call returns --
	 * holds for everything resident from then on.
	 * @param on whether results stay on the device until first read
	 */
	@Override
	public void lazyResults(boolean on) {
		this.lazy = on;
		if (!on) {
			try {
				if (this.driver.ctxSetCurrent(this.context) == CuResult.SUCCESS) {
					for (DeviceResidency.Flush flush : this.residency.claimAllDirty()) {
						download(heap(flush.target()), flush.offset(), flush.pointer(), flush.bytes());
					}
				}
			}
			catch (Throwable ex) {
				// The context is gone, and with it the copies; the sticky rule has
				// retired
				// the feature.
			}
		}
	}

	/**
	 * Frees every buffer the cache has dropped, replaced or evicted since the last drain.
	 */
	private void drainPending() {
		for (long pointer : this.residency.drain()) {
			free(pointer);
		}
	}

	/**
	 * A host array is about to be written: its resident copy, if any, is stale and is
	 * dropped -- after being brought home first when it was the authoritative one
	 * ({@link #materialize}), so that the write lands on the array's real bytes, which
	 * are the array answered (a stub's backing, or the array itself). For a clean copy no
	 * driver call happens here -- the buffer is freed by the next call, on a thread that
	 * has the context -- so this is safe from any thread and costs a volatile read when
	 * nothing is resident.
	 * @param host the host array that is being written
	 * @return the array to write into
	 */
	// --- the fused tier (.todo/499) --------------------------------------------------

	@Override
	public boolean gelu(double[] a, int oa, double[] c, int oc, int n) {
		return elementwise(this.fused[GELU_F64], MemorySegment.ofArray(a), a, oa, null, null, 0, null, null, 0,
				MemorySegment.ofArray(c), c, oc, n, Double.BYTES);
	}

	@Override
	public boolean geluF(float[] a, int oa, float[] c, int oc, int n) {
		return elementwise(this.fused[GELU_F32], MemorySegment.ofArray(a), a, oa, null, null, 0, null, null, 0,
				MemorySegment.ofArray(c), c, oc, n, Float.BYTES);
	}

	@Override
	public boolean geluGrad(double[] g, int og, double[] x, int ox, double @Nullable [] old, int oOld, double[] c,
			int oc, int n) {
		return elementwise(this.fused[GELU_GRAD_F64], MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(x), x, ox,
				old == null ? null : MemorySegment.ofArray(old), old, oOld, MemorySegment.ofArray(c), c, oc, n,
				Double.BYTES);
	}

	@Override
	public boolean geluGradF(float[] g, int og, float[] x, int ox, float @Nullable [] old, int oOld, float[] c, int oc,
			int n) {
		return elementwise(this.fused[GELU_GRAD_F32], MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(x), x, ox,
				old == null ? null : MemorySegment.ofArray(old), old, oOld, MemorySegment.ofArray(c), c, oc, n,
				Float.BYTES);
	}

	@Override
	public boolean softmax(double[] a, int oa, double[] c, int oc, int rows, int len) {
		return rowKernel(this.fused[SOFTMAX_F64], MemorySegment.ofArray(a), a, oa, null, null, 0, null, null, 0,
				MemorySegment.ofArray(c), c, oc, rows, len, null, false, Double.BYTES);
	}

	@Override
	public boolean softmaxF(float[] a, int oa, float[] c, int oc, int rows, int len) {
		return rowKernel(this.fused[SOFTMAX_F32], MemorySegment.ofArray(a), a, oa, null, null, 0, null, null, 0,
				MemorySegment.ofArray(c), c, oc, rows, len, null, false, Float.BYTES);
	}

	@Override
	public boolean softmaxGrad(double[] g, int og, double[] s, int os, double[] c, int oc, int rows, int len) {
		return rowKernel(this.fused[SOFTMAX_GRAD_F64], MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(s), s, os,
				null, null, 0, MemorySegment.ofArray(c), c, oc, rows, len, null, false, Double.BYTES);
	}

	@Override
	public boolean softmaxGradF(float[] g, int og, float[] s, int os, float[] c, int oc, int rows, int len) {
		return rowKernel(this.fused[SOFTMAX_GRAD_F32], MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(s), s, os,
				null, null, 0, MemorySegment.ofArray(c), c, oc, rows, len, null, false, Float.BYTES);
	}

	@Override
	public boolean layerNorm(double[] x, int ox, double[] c, int oc, int rows, int len, double eps) {
		return rowKernel(this.fused[LAYER_NORM_F64], MemorySegment.ofArray(x), x, ox, null, null, 0, null, null, 0,
				MemorySegment.ofArray(c), c, oc, rows, len, eps, false, Double.BYTES);
	}

	@Override
	public boolean layerNormF(float[] x, int ox, float[] c, int oc, int rows, int len, double eps) {
		return rowKernel(this.fused[LAYER_NORM_F32], MemorySegment.ofArray(x), x, ox, null, null, 0, null, null, 0,
				MemorySegment.ofArray(c), c, oc, rows, len, eps, false, Float.BYTES);
	}

	@Override
	public boolean layerNormGrad(double[] g, int og, double[] x, int ox, double @Nullable [] old, int oOld, double[] c,
			int oc, int rows, int len, double eps) {
		return rowKernel(this.fused[LAYER_NORM_GRAD_F64], MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(x), x,
				ox, old == null ? null : MemorySegment.ofArray(old), old, oOld, MemorySegment.ofArray(c), c, oc, rows,
				len, eps, true, Double.BYTES);
	}

	@Override
	public boolean layerNormGradF(float[] g, int og, float[] x, int ox, float @Nullable [] old, int oOld, float[] c,
			int oc, int rows, int len, double eps) {
		return rowKernel(this.fused[LAYER_NORM_GRAD_F32], MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(x), x,
				ox, old == null ? null : MemorySegment.ofArray(old), old, oOld, MemorySegment.ofArray(c), c, oc, rows,
				len, eps, true, Float.BYTES);
	}

	@Override
	public boolean dropoutMask(double[] c, int oc, int n, double p, double span, int s1, int s2, int s3) {
		return dropoutMask(this.fused[DROPOUT_F64], MemorySegment.ofArray(c), c, (long) oc * Double.BYTES,
				(long) n * Double.BYTES, n, p, span, s1, s2, s3);
	}

	@Override
	public boolean dropoutMaskF(float[] c, int oc, int n, double p, double span, int s1, int s2, int s3) {
		return dropoutMask(this.fused[DROPOUT_F32], MemorySegment.ofArray(c), c, (long) oc * Float.BYTES,
				(long) n * Float.BYTES, n, p, span, s1, s2, s3);
	}

	/**
	 * One fused pass over {@code n} elements with one to three operands -- the first
	 * always present, the second and third optional (a {@code null} segment): the GELU
	 * ({@code X}) and its adjoint ({@code G, X, OLD}). The parameter block is the
	 * operands present (an absent one rides as a null pointer, which the kernel tests),
	 * the result and the count. Each operand is looked up, staged and recorded like any
	 * other member's, so the activation a forward left resident is what its backward
	 * reads.
	 */
	private boolean elementwise(MemorySegment kernel, MemorySegment a, Object ah, int oa, @Nullable MemorySegment b,
			@Nullable Object bh, int ob, @Nullable MemorySegment d, @Nullable Object dh, int od, MemorySegment c,
			Object ch, int oc, int n, int width) {
		if (!this.usable) {
			return false;
		}
		long bytes = (long) n * width;
		long offA = (long) oa * width, offB = (long) ob * width, offD = (long) od * width, offC = (long) oc * width;
		long[] buffers = { 0, 0, 0, 0 }, owned = { 0, 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, bytes);
			if (b != null) {
				buffers[1] = this.residency.lookup(java.util.Objects.requireNonNull(bh), offB, bytes);
			}
			if (d != null) {
				buffers[2] = this.residency.lookup(java.util.Objects.requireNonNull(dh), offD, bytes);
			}
			if (!allocate(arena, buffers, owned, bytes, b == null ? 0 : bytes, d == null ? 0 : bytes, bytes)) {
				return false;
			}
			boolean sync = (long) n * MAP_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, bytes)
					|| (b != null && !stage(buffers, owned, 1, java.util.Objects.requireNonNull(bh), b, offB, bytes))
					|| (d != null && !stage(buffers, owned, 2, java.util.Objects.requireNonNull(dh), d, offD, bytes))) {
				return false;
			}
			// A kernel with a third operand takes it always, absent as a null pointer.
			Object[] values = b == null ? new Object[] { buffers[0], buffers[3], n }
					: new Object[] { buffers[0], buffers[1], buffers[2], buffers[3], n };
			if (!launchFused(arena, kernel, n, STRIDED_BLOCK, values, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 3, ch, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * One fused pass per ROW over a {@code rows x len} operand -- one thread walks one
	 * row, the fold kernel's own pattern -- with one to three operands as in
	 * {@link #elementwise}: the softmax ({@code A}), its adjoint ({@code G, S}),
	 * layer-norm ({@code X}) and its adjoint ({@code G, X, OLD}). {@code eps}, when
	 * present, is the trailing double parameter.
	 */
	private boolean rowKernel(MemorySegment kernel, MemorySegment a, Object ah, int oa, @Nullable MemorySegment b,
			@Nullable Object bh, int ob, @Nullable MemorySegment d, @Nullable Object dh, int od, MemorySegment c,
			Object ch, int oc, int rows, int len, @Nullable Double eps, boolean oldSlot, int width) {
		if (!this.usable) {
			return false;
		}
		long n = (long) rows * len;
		long bytes = n * width;
		long offA = (long) oa * width, offB = (long) ob * width, offD = (long) od * width, offC = (long) oc * width;
		long[] buffers = { 0, 0, 0, 0 }, owned = { 0, 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, bytes);
			if (b != null) {
				buffers[1] = this.residency.lookup(java.util.Objects.requireNonNull(bh), offB, bytes);
			}
			if (d != null) {
				buffers[2] = this.residency.lookup(java.util.Objects.requireNonNull(dh), offD, bytes);
			}
			if (!allocate(arena, buffers, owned, bytes, b == null ? 0 : bytes, d == null ? 0 : bytes, bytes)) {
				return false;
			}
			boolean sync = n * FUSED_ROW_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, bytes)
					|| (b != null && !stage(buffers, owned, 1, java.util.Objects.requireNonNull(bh), b, offB, bytes))
					|| (d != null && !stage(buffers, owned, 2, java.util.Objects.requireNonNull(dh), d, offD, bytes))) {
				return false;
			}
			java.util.List<Object> values = new java.util.ArrayList<>();
			values.add(buffers[0]);
			if (b != null) {
				values.add(buffers[1]);
			}
			if (oldSlot) {
				// The adjoint kernels take the accumulated gradient always, absent as a
				// null pointer.
				values.add(buffers[2]);
			}
			values.add(buffers[3]);
			values.add(rows);
			values.add(len);
			if (eps != null) {
				values.add(eps);
			}
			if (!launchFused(arena, kernel, rows, ROW_BLOCK, values.toArray(), sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 3, ch, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * The dropout mask: {@link #rngFill}'s shape -- no operand, a destination that may
	 * already be resident and is then filled in place -- with the threshold and the
	 * survival span as the two doubles.
	 */
	private boolean dropoutMask(MemorySegment function, MemorySegment heap, Object host, long offset, long bytes, int n,
			double p, double span, int s1, int s2, int s3) {
		if (!this.usable) {
			return false;
		}
		long[] buffers = { 0 }, owned = { 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(host, offset, bytes);
			if (!allocate(arena, buffers, owned, bytes)) {
				return false;
			}
			boolean sync = (long) n * RNG_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!launchFused(arena, function, n, STRIDED_BLOCK, new Object[] { buffers[0], n, p, span, s1, s2, s3 },
					sync)) {
				return false;
			}
			return finish(heap, offset, buffers, owned, 0, host, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * A flat launch over {@code n} threads (one per element, or per row) in blocks of
	 * {@code block}, whose parameter block is given as VALUES: a {@code Long} is a device
	 * pointer, an {@code Integer} an {@code int}, a {@code Double} a {@code double}, each
	 * in a slot of its own width. Since the fused kernels mix all three, the two parallel
	 * arrays the strided launcher takes would not describe them.
	 */
	private boolean launchFused(Arena arena, MemorySegment function, int n, int block, Object[] values, boolean sync)
			throws Throwable {
		MemorySegment parameters = arena.allocate(P, values.length);
		for (int i = 0; i < values.length; i++) {
			MemorySegment slot;
			switch (values[i]) {
				case Long pointer -> {
					slot = arena.allocate(L);
					slot.set(L, 0, pointer);
				}
				case Integer count -> {
					slot = arena.allocate(I);
					slot.set(I, 0, count);
				}
				case Double d -> {
					slot = arena.allocate(D);
					slot.set(D, 0, d);
				}
				default -> throw new IllegalArgumentException("unsupported kernel parameter " + values[i]);
			}
			parameters.setAtIndex(P, i, slot);
		}
		int status = this.driver.launchKernel(function, (n + block - 1) / block, 1, 1, block, 1, 1, 0,
				MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		return awaitLaunched(sync);
	}

	@Override
	public Object written(Object host) {
		Object storage = materialize(host);
		this.residency.written(host);
		return storage;
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
	 * Drops and frees every resident copy. For the leak tests, which need the baseline
	 * they measure against to be an EMPTY device, and for {@link #setPooledAllocation},
	 * which must not leave a pooled buffer to be freed by the other allocator.
	 */
	@Override
	public void releaseResident() {
		this.residency.evictAll();
		try (Arena arena = Arena.ofConfined()) {
			if (this.driver.ctxSetCurrent(this.context) == CuResult.SUCCESS) {
				settle();
				drainPending();
			}
		}
		catch (Throwable ex) {
			// The context is gone; so are the buffers.
		}
	}

	/**
	 * Imposes a residency budget in bytes, or {@code -1} to go back to the derived one.
	 * Package-private and for the tests, which need the LRU to evict at a size they can
	 * afford to fill. Takes effect at the next pre-flight refresh, so the tests also
	 * release what is resident to make the switch immediate.
	 */
	void residentBudget(long bytes) {
		this.residentBudgetOverride = bytes;
		this.freeEstimate = -1;
	}

	/** The cache itself, for the tests' hit and miss counts. */
	@Override
	public DeviceResidency residency() {
		return this.residency;
	}

	/**
	 * The generator fill: one buffer, no copy up, one launch and the result back -- the
	 * one member of this library with no operand, which is why its threshold is the
	 * lowest. The closed-form jump and the draws are {@code gemm.cu}'s; see there.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean rngFill(double[] c, int oc, int n, int mode, double lo, double span, int s1, int s2, int s3) {
		return rngFill(this.strided[RNG_F64], MemorySegment.ofArray(c), c, (long) oc * Double.BYTES,
				(long) n * Double.BYTES, n, mode, lo, span, s1, s2, s3);
	}

	@Override
	public boolean rngFillF(float[] c, int oc, int n, int mode, double lo, double span, int s1, int s2, int s3) {
		return rngFill(this.strided[RNG_F32], MemorySegment.ofArray(c), c, (long) oc * Float.BYTES,
				(long) n * Float.BYTES, n, mode, lo, span, s1, s2, s3);
	}

	private boolean rngFill(MemorySegment function, MemorySegment heap, Object host, long offset, long bytes, int n,
			int mode, double lo, double span, int s1, int s2, int s3) {
		if (!this.usable) {
			return false;
		}
		long[] buffers = { 0 }, owned = { 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			// A destination that is already resident is filled in place: the fill covers
			// its whole span, so whatever it held is superseded.
			buffers[0] = this.residency.lookup(host, offset, bytes);
			if (!allocate(arena, buffers, owned, bytes)) {
				return false;
			}
			boolean sync = (long) n
					* (mode == 1 ? RNG_FLOPS_PER_NORMAL : RNG_FLOPS_PER_ELEMENT) >= this.syncFlopCeiling;
			// The parameter block: the pointer, three ints, two doubles and three ints,
			// each in its own slot of its own width.
			MemorySegment out = arena.allocate(L), count = arena.allocate(I), which = arena.allocate(I),
					low = arena.allocate(D), range = arena.allocate(D), w1 = arena.allocate(I), w2 = arena.allocate(I),
					w3 = arena.allocate(I);
			out.set(L, 0, buffers[0]);
			count.set(I, 0, n);
			which.set(I, 0, mode);
			low.set(D, 0, lo);
			range.set(D, 0, span);
			w1.set(I, 0, s1);
			w2.set(I, 0, s2);
			w3.set(I, 0, s3);
			MemorySegment parameters = arena.allocate(P, 8);
			parameters.setAtIndex(P, 0, out);
			parameters.setAtIndex(P, 1, count);
			parameters.setAtIndex(P, 2, which);
			parameters.setAtIndex(P, 3, low);
			parameters.setAtIndex(P, 4, range);
			parameters.setAtIndex(P, 5, w1);
			parameters.setAtIndex(P, 6, w2);
			parameters.setAtIndex(P, 7, w3);
			int status = this.driver.launchKernel(function, (n + STRIDED_BLOCK - 1) / STRIDED_BLOCK, 1, 1,
					STRIDED_BLOCK, 1, 1, 0, MemorySegment.NULL, parameters, MemorySegment.NULL);
			if (status != CuResult.SUCCESS) {
				return fail(status);
			}
			if (!awaitLaunched(sync)) {
				return false;
			}
			return finish(heap, offset, buffers, owned, 0, host, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * {@code y = W x}, the GEMV behind {@code vec:matvec} -- and the one member of this
	 * class whose accept-or-decline is a question of RESIDENCY rather than of size.
	 *
	 * <p>
	 * A matrix-by-vector product reads every element of {@code W} exactly once, so its
	 * cost IS the pass over {@code W}, and copying {@code W} to the device first costs
	 * more than the pass: measured over the link the device loses to the {@code --simd}
	 * lane kernel up to ~2^19 elements and wins by less than 2x below a million. Over a
	 * matrix that is ALREADY resident the same call is the ~9 us floor -- {@code x} up, a
	 * launch, {@code y} down -- and the crossover is 2^17
	 * ({@code Gpu.MATVEC_POOLED_MIN_ELEMENTS}). So the rule is: <b>a matrix is taken when
	 * it is resident, or when it has been offered once before and not written since</b>
	 * -- the second offer uploads it and every later one finds it there, while a matrix
	 * the program rewrites between calls is offered "for the first time" every time and
	 * never pays for a trip it would lose. The first sight of any matrix is a decline
	 * that allocates nothing, and the mark it leaves is a residency entry with no buffer
	 * ({@link DeviceResidency#offeredBefore}), so {@link #written} clears it exactly as
	 * it would clear a copy. A model's weights -- read every step, written never -- are
	 * resident from their second step on, which is what {@code .todo/475} measured the
	 * whole item on.
	 * @return {@code true} when {@code y} was filled, {@code false} when the call
	 * declined or the device failed -- in which case {@code y} is untouched
	 */
	@Override
	public boolean gemv(double[] w, int ow, double[] x, int ox, double[] y, int oy, int rows, int cols) {
		return gemv(this.gemvF64, MemorySegment.ofArray(w), w, ow, MemorySegment.ofArray(x), x, ox,
				MemorySegment.ofArray(y), y, oy, rows, cols, Double.BYTES);
	}

	/**
	 * The single-float sibling of {@link #gemv}. The kernel still accumulates in double,
	 * which is what keeps it on the scalar defun's bits in practice ({@code gemm.cu}).
	 * @return {@code true} when {@code y} was filled
	 */
	@Override
	public boolean gemvF(float[] w, int ow, float[] x, int ox, float[] y, int oy, int rows, int cols) {
		return gemv(this.gemvF32, MemorySegment.ofArray(w), w, ow, MemorySegment.ofArray(x), x, ox,
				MemorySegment.ofArray(y), y, oy, rows, cols, Float.BYTES);
	}

	private boolean gemv(MemorySegment kernel, MemorySegment w, Object wh, int ow, MemorySegment x, Object xh, int ox,
			MemorySegment y, Object yh, int oy, int rows, int cols, int width) {
		if (!this.usable) {
			return false;
		}
		long wBytes = (long) rows * cols * width, xBytes = (long) cols * width, yBytes = (long) rows * width;
		long offW = (long) ow * width, offX = (long) ox * width, offY = (long) oy * width;
		long[] buffers = { 0, 0, 0 }, owned = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(wh, offW, wBytes);
			// The residency rule above: a matrix seen for the first time is declined and
			// marked, nothing allocated; seen again unwritten, it is uploaded below.
			if (buffers[0] == 0 && !this.residency.offeredBefore(wh, offW, wBytes)) {
				settle();
				return false;
			}
			if (!settle()) {
				return false;
			}
			buffers[1] = this.residency.lookup(xh, offX, xBytes);
			if (!allocate(arena, buffers, owned, wBytes, xBytes, yBytes)) {
				return false;
			}
			boolean sync = 2L * rows * cols >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, wh, w, offW, wBytes) || !stage(buffers, owned, 1, xh, x, offX, xBytes)
					|| !launchGemv(arena, kernel, buffers, rows, cols, sync)) {
				return false;
			}
			return finish(y, offY, buffers, owned, 2, yh, yBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/** One launch of eight rows per block, one warp per row. */
	private boolean launchGemv(Arena arena, MemorySegment function, long[] buffers, int rows, int cols, boolean sync)
			throws Throwable {
		MemorySegment w = arena.allocate(L), x = arena.allocate(L), y = arena.allocate(L);
		w.set(L, 0, buffers[0]);
		x.set(L, 0, buffers[1]);
		y.set(L, 0, buffers[2]);
		MemorySegment r = arena.allocate(I), c = arena.allocate(I);
		r.set(I, 0, rows);
		c.set(I, 0, cols);
		MemorySegment parameters = arena.allocate(P, 5);
		parameters.setAtIndex(P, 0, w);
		parameters.setAtIndex(P, 1, x);
		parameters.setAtIndex(P, 2, y);
		parameters.setAtIndex(P, 3, r);
		parameters.setAtIndex(P, 4, c);
		int warps = GEMV_BLOCK / 32;
		int status = this.driver.launchKernel(function, (rows + warps - 1) / warps, 1, 1, GEMV_BLOCK, 1, 1, 0,
				MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		return awaitLaunched(sync);
	}

	// --- the resident tier (.todo/491) -------------------------------------------------
	// Four members whose CPU twin is a lane loop, which a round trip could never win and
	// which Gpu therefore offers only over an operand that is ALREADY resident: the
	// equal-shape binary op, the array-with-scalar form, linalg:where and the fused Adam
	// update. Each is the ordinary shape -- look up, allocate what is missing, stage,
	// launch, finish -- and each computes in double and narrows on the store, so all four
	// land on the CPU kernels' bits (gemm.cu).

	/**
	 * {@code c[i] = op(a[i], b[i])} over two operands of the same shape -- the shape the
	 * element-wise tier refused as a round trip, taken here because {@link Gpu} has
	 * already found an operand resident.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean zip(int op, double[] a, int oa, double[] b, int ob, double[] c, int oc, int n) {
		return zip(op, MemorySegment.ofArray(a), a, oa, MemorySegment.ofArray(b), b, ob, MemorySegment.ofArray(c), c,
				oc, n, Double.BYTES, this.resident[ZIP_F64]);
	}

	@Override
	public boolean zipF(int op, float[] a, int oa, float[] b, int ob, float[] c, int oc, int n) {
		return zip(op, MemorySegment.ofArray(a), a, oa, MemorySegment.ofArray(b), b, ob, MemorySegment.ofArray(c), c,
				oc, n, Float.BYTES, this.resident[ZIP_F32]);
	}

	private boolean zip(int op, MemorySegment a, Object ah, int oa, MemorySegment b, Object bh, int ob, MemorySegment c,
			Object ch, int oc, int n, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		long bytes = (long) n * width, offA = (long) oa * width, offB = (long) ob * width, offC = (long) oc * width;
		long[] buffers = { 0, 0, 0 }, owned = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, bytes);
			buffers[1] = this.residency.lookup(bh, offB, bytes);
			if (!allocate(arena, buffers, owned, bytes, bytes, bytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, bytes) || !stage(buffers, owned, 1, bh, b, offB, bytes)
					|| !launchStrided(arena, kernel, n, new long[] { op, buffers[0], buffers[1], buffers[2], n },
							new boolean[] { false, true, true, true, false }, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 2, ch, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * {@code c[i] = op(a[i], s)} -- or {@code op(s, a[i])} when {@code swap} -- over a
	 * DOUBLE scalar whatever the array's width, which is the CPU kernel's contract too.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean scale(int op, double[] a, int oa, double s, boolean swap, double[] c, int oc, int n) {
		return scale(op, MemorySegment.ofArray(a), a, oa, s, swap, MemorySegment.ofArray(c), c, oc, n, Double.BYTES,
				this.resident[SCAL_F64]);
	}

	@Override
	public boolean scaleF(int op, float[] a, int oa, double s, boolean swap, float[] c, int oc, int n) {
		return scale(op, MemorySegment.ofArray(a), a, oa, s, swap, MemorySegment.ofArray(c), c, oc, n, Float.BYTES,
				this.resident[SCAL_F32]);
	}

	private boolean scale(int op, MemorySegment a, Object ah, int oa, double s, boolean swap, MemorySegment c,
			Object ch, int oc, int n, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		long bytes = (long) n * width, offA = (long) oa * width, offC = (long) oc * width;
		long[] buffers = { 0, 0 }, owned = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, bytes);
			// In place (the destination IS the operand, as %la-scale writes it): the
			// kernel
			// reads and writes the same element of the one resident buffer, and finish
			// marks it dirty rather than recording a second one.
			if (ch == ah && oc == oa && buffers[0] != 0) {
				buffers[1] = buffers[0];
			}
			if (!allocate(arena, buffers, owned, bytes, bytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, bytes)) {
				return false;
			}
			// The parameter block: op, A, s (a double), C, n, swap.
			MemorySegment opv = arena.allocate(I), av = arena.allocate(L), sv = arena.allocate(D),
					cv = arena.allocate(L), nv = arena.allocate(I), swv = arena.allocate(I);
			opv.set(I, 0, op);
			av.set(L, 0, buffers[0]);
			sv.set(D, 0, s);
			cv.set(L, 0, buffers[1]);
			nv.set(I, 0, n);
			swv.set(I, 0, swap ? 1 : 0);
			MemorySegment parameters = arena.allocate(P, 6);
			parameters.setAtIndex(P, 0, opv);
			parameters.setAtIndex(P, 1, av);
			parameters.setAtIndex(P, 2, sv);
			parameters.setAtIndex(P, 3, cv);
			parameters.setAtIndex(P, 4, nv);
			parameters.setAtIndex(P, 5, swv);
			if (!launchFlat(kernel, n, parameters, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 1, ch, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * The strided copy: {@code c[oc + so.i] = a[oa + sa.i]} over {@code dims}, either
	 * stride vector possibly negative -- reshape, the rank-2 transpose, a slice and each
	 * slab of a concatenation, over a resident operand only. Offsets are element offsets
	 * of the two WALKS' origins; the residency span of each array is still its whole data
	 * part, which the caller passes as {@code spanA} / {@code spanC} (an element offset
	 * and count), so that a slice finds the array it was cut from.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean copy(double[] a, int oa, int[] sa, int spanOa, int spanNa, double[] c, int oc, int[] sc, int spanOc,
			int spanNc, int[] dims) {
		return copy(MemorySegment.ofArray(a), a, oa, sa, spanOa, spanNa, MemorySegment.ofArray(c), c, oc, sc, spanOc,
				spanNc, dims, Double.BYTES, this.resident[COPY_F64]);
	}

	@Override
	public boolean copyF(float[] a, int oa, int[] sa, int spanOa, int spanNa, float[] c, int oc, int[] sc, int spanOc,
			int spanNc, int[] dims) {
		return copy(MemorySegment.ofArray(a), a, oa, sa, spanOa, spanNa, MemorySegment.ofArray(c), c, oc, sc, spanOc,
				spanNc, dims, Float.BYTES, this.resident[COPY_F32]);
	}

	private boolean copy(MemorySegment a, Object ah, int oa, int[] sa, int spanOa, int spanNa, MemorySegment c,
			Object ch, int oc, int[] sc, int spanOc, int spanNc, int[] dims, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int rank = dims.length;
		int n = count(dims);
		long aBytes = (long) spanNa * width, cBytes = (long) spanNc * width;
		long offA = (long) spanOa * width, offC = (long) spanOc * width;
		long[] buffers = { 0, 0 }, owned = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, aBytes);
			// A destination already resident (a concatenation's second slab onward) is
			// written into in place; a fresh one is allocated and recorded.
			buffers[1] = this.residency.lookup(ch, offC, cBytes);
			if (!allocate(arena, buffers, owned, aBytes, cBytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, aBytes)) {
				return false;
			}
			// The kernel walks from each side's origin: the buffer plus the walk's own
			// offset within the span.
			long originA = buffers[0] + ((long) oa - spanOa) * width,
					originC = buffers[1] + ((long) oc - spanOc) * width;
			if (!launchStrided(arena, kernel, n, new long[] { originA, originC, n, rank },
					new boolean[] { true, true, false, false }, layout(arena, dims, sa, sc, null), sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 1, ch, cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * {@code c[i] = m[im(i)] != 0 ? x[ix(i)] : y[iy(i)]} over three operands broadcast
	 * together, any of which may be a scalar ({@code null} array, its value in the
	 * double) -- {@code linalg:where}. The mask may be either width; {@code x} /
	 * {@code y} share the result's.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean where(@Nullable Object m, int om, int[] sm, double ms, double @Nullable [] x, int ox, int[] sx,
			double xs, double @Nullable [] y, int oy, int[] sy, double ys, double[] c, int oc, int[] dims) {
		return where(m, om, sm, ms, x == null ? null : MemorySegment.ofArray(x), x, ox, sx, xs,
				y == null ? null : MemorySegment.ofArray(y), y, oy, sy, ys, MemorySegment.ofArray(c), c, oc, dims,
				Double.BYTES, this.resident[WHERE_F64]);
	}

	@Override
	public boolean whereF(@Nullable Object m, int om, int[] sm, double ms, float @Nullable [] x, int ox, int[] sx,
			double xs, float @Nullable [] y, int oy, int[] sy, double ys, float[] c, int oc, int[] dims) {
		return where(m, om, sm, ms, x == null ? null : MemorySegment.ofArray(x), x, ox, sx, xs,
				y == null ? null : MemorySegment.ofArray(y), y, oy, sy, ys, MemorySegment.ofArray(c), c, oc, dims,
				Float.BYTES, this.resident[WHERE_F32]);
	}

	private boolean where(@Nullable Object mh, int om, int[] sm, double ms, @Nullable MemorySegment x,
			@Nullable Object xh, int ox, int[] sx, double xs, @Nullable MemorySegment y, @Nullable Object yh, int oy,
			int[] sy, double ys, MemorySegment c, Object ch, int oc, int[] dims, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		int rank = dims.length;
		int n = count(dims);
		int mkind = mh instanceof float[] ? 1 : mh instanceof double[] ? 2 : 0;
		int mwidth = mkind == 1 ? Float.BYTES : Double.BYTES;
		// NullAway cannot see that a non-zero kind implies a non-null array, so the three
		// array operands are re-bound non-null here, a scalar's to a placeholder that is
		// never looked up, staged or read.
		Object mArray = mh == null ? NO_ARRAY : mh, xArray = xh == null ? NO_ARRAY : xh,
				yArray = yh == null ? NO_ARRAY : yh;
		MemorySegment m = mkind == 0 ? null : heap(mArray);
		long mBytes = mkind == 0 ? 0 : (span(dims, sm) + 1L) * mwidth,
				xBytes = x == null ? 0 : (span(dims, sx) + 1L) * width,
				yBytes = y == null ? 0 : (span(dims, sy) + 1L) * width, cBytes = (long) n * width;
		long offM = (long) om * mwidth, offX = (long) ox * width, offY = (long) oy * width, offC = (long) oc * width;
		// Slots: 0 mask, 1 x, 2 y, 3 result. A scalar operand's slot is a zero byte
		// count and stays a null pointer; the layout rides in the parameter block.
		long[] buffers = { 0, 0, 0, 0 }, owned = { 0, 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			if (m != null) {
				buffers[0] = this.residency.lookup(mArray, offM, mBytes);
			}
			if (x != null) {
				buffers[1] = this.residency.lookup(xArray, offX, xBytes);
			}
			if (y != null) {
				buffers[2] = this.residency.lookup(yArray, offY, yBytes);
			}
			if (!allocate(arena, buffers, owned, mBytes, xBytes, yBytes, cBytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if ((m != null && !stage(buffers, owned, 0, mArray, m, offM, mBytes))
					|| (x != null && !stage(buffers, owned, 1, xArray, x, offX, xBytes))
					|| (y != null && !stage(buffers, owned, 2, yArray, y, offY, yBytes))) {
				return false;
			}
			// The parameter block: M, mkind, ms, X, xs, Y, ys, C, n, rank, meta.
			MemorySegment mv = arena.allocate(L), mk = arena.allocate(I), msv = arena.allocate(D),
					xv = arena.allocate(L), xsv = arena.allocate(D), yv = arena.allocate(L), ysv = arena.allocate(D),
					cv = arena.allocate(L), nv = arena.allocate(I), rv = arena.allocate(I);
			mv.set(L, 0, buffers[0]);
			mk.set(I, 0, mkind);
			msv.set(D, 0, ms);
			xv.set(L, 0, buffers[1]);
			xsv.set(D, 0, xs);
			yv.set(L, 0, buffers[2]);
			ysv.set(D, 0, ys);
			cv.set(L, 0, buffers[3]);
			nv.set(I, 0, n);
			rv.set(I, 0, rank);
			MemorySegment metav = layout(arena, dims, sm, sx, sy);
			MemorySegment parameters = arena.allocate(P, 11);
			MemorySegment[] slots = { mv, mk, msv, xv, xsv, yv, ysv, cv, nv, rv, metav };
			for (int i = 0; i < slots.length; i++) {
				parameters.setAtIndex(P, i, slots[i]);
			}
			if (!launchFlat(kernel, n, parameters, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 3, ch, cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * The embedding lookup and the cross-entropy pick -- {@code linalg:take-rows} at
	 * {@code mode} 0 and {@code linalg:gather} at 1 -- as an index-driven gather. A pure
	 * copy, so bit-identical to the CPU kernels.
	 * @return {@code true} when {@code c} was filled
	 */
	@Override
	public boolean take(int mode, double[] a, int oa, int lenA, double[] c, int oc, int[] idx, int n, int slab) {
		return take(mode, MemorySegment.ofArray(a), a, oa, lenA, MemorySegment.ofArray(c), c, oc, idx, n, slab,
				Double.BYTES, this.resident[TAKE_F64]);
	}

	@Override
	public boolean takeF(int mode, float[] a, int oa, int lenA, float[] c, int oc, int[] idx, int n, int slab) {
		return take(mode, MemorySegment.ofArray(a), a, oa, lenA, MemorySegment.ofArray(c), c, oc, idx, n, slab,
				Float.BYTES, this.resident[TAKE_F32]);
	}

	private boolean take(int mode, MemorySegment a, Object ah, int oa, int lenA, MemorySegment c, Object ch, int oc,
			int[] idx, int n, int slab, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		long aBytes = (long) lenA * width, cBytes = (long) n * width, idxBytes = (long) idx.length * Integer.BYTES;
		long offA = (long) oa * width, offC = (long) oc * width;
		long[] buffers = { 0, 0, 0 }, owned = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(ah, offA, aBytes);
			if (!allocate(arena, buffers, owned, aBytes, cBytes, idxBytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, ah, a, offA, aBytes) || !uploadInts(arena, buffers[2], idx)
					|| !launchStrided(arena, kernel, n,
							new long[] { mode, buffers[0], buffers[1], buffers[2], n, slab },
							new boolean[] { false, true, true, true, false, false }, sync)) {
				return false;
			}
			return finish(c, offC, buffers, owned, 1, ch, cBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * {@code linalg::%la-scatter-rows}, take-rows' adjoint, IN PLACE: {@code z} is
	 * written on the device and stays there. {@code meta} is the indices GROUPED by
	 * destination -- {@code rows + 1} group starts followed by the source slab numbers,
	 * ascending inside each group -- which is what lets one thread per destination cell
	 * keep the defun's index order without atomics ({@code scatter} in {@code gemm.cu}).
	 * Bit-identical to the CPU kernel.
	 * @return {@code true} when the scatter ran
	 */
	@Override
	public boolean scatter(double[] z, int oz, double[] g, int og, int[] meta, int rows, int slab, int m) {
		return scatter(MemorySegment.ofArray(z), z, oz, MemorySegment.ofArray(g), g, og, meta, rows, slab, m,
				Double.BYTES, this.resident[SCATTER_F64]);
	}

	@Override
	public boolean scatterF(float[] z, int oz, float[] g, int og, int[] meta, int rows, int slab, int m) {
		return scatter(MemorySegment.ofArray(z), z, oz, MemorySegment.ofArray(g), g, og, meta, rows, slab, m,
				Float.BYTES, this.resident[SCATTER_F32]);
	}

	private boolean scatter(MemorySegment z, Object zh, int oz, MemorySegment g, Object gh, int og, int[] meta,
			int rows, int slab, int m, int width, MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		long zBytes = (long) rows * slab * width, gBytes = (long) m * slab * width,
				metaBytes = (long) meta.length * Integer.BYTES;
		long offZ = (long) oz * width, offG = (long) og * width;
		long[] buffers = { 0, 0, 0 }, owned = { 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(zh, offZ, zBytes);
			buffers[1] = this.residency.lookup(gh, offG, gBytes);
			if (!allocate(arena, buffers, owned, zBytes, gBytes, metaBytes)) {
				return false;
			}
			int cells = rows * slab;
			boolean sync = (long) m * slab * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, zh, z, offZ, zBytes) || !stage(buffers, owned, 1, gh, g, offG, gBytes)
					|| !uploadInts(arena, buffers[2], meta)
					|| !launchStrided(arena, kernel, cells,
							new long[] { buffers[0], buffers[1], buffers[2], rows, slab },
							new boolean[] { true, true, true, false, false }, sync)) {
				return false;
			}
			return finish(z, offZ, buffers, owned, 0, zh, zBytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * The block partials of {@code sum(a[i] * a[i])} in double, or {@code null} when this
	 * call declined -- the device half of {@code linalg::%la-sum-squares}, and the ONE
	 * member here that does not compute the caller's fold order. The caller adds the
	 * partials up in block order; how many there are is a pure function of {@code n}, so
	 * the value is reproducible.
	 * @return the per-block partials, or {@code null} on a decline
	 */
	@Override
	public double @Nullable [] sumSquares(double[] a, int oa, int n) {
		return sumSquares(MemorySegment.ofArray(a), a, oa, n, Double.BYTES, this.resident[SUMSQ_F64]);
	}

	@Override
	public double @Nullable [] sumSquaresF(float[] a, int oa, int n) {
		return sumSquares(MemorySegment.ofArray(a), a, oa, n, Float.BYTES, this.resident[SUMSQ_F32]);
	}

	private double @Nullable [] sumSquares(MemorySegment a, Object ah, int oa, int n, int width, MemorySegment kernel) {
		if (!this.usable) {
			return null;
		}
		int blocks = (int) Math.min(SUMSQ_MAX_BLOCKS, ((long) n + STRIDED_BLOCK - 1) / STRIDED_BLOCK);
		long aBytes = (long) n * width, pBytes = (long) blocks * Double.BYTES, offA = (long) oa * width;
		long[] buffers = { 0, 0 }, owned = { 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return null;
			}
			buffers[0] = this.residency.lookup(ah, offA, aBytes);
			if (!allocate(arena, buffers, owned, aBytes, pBytes)) {
				return null;
			}
			if (!stage(buffers, owned, 0, ah, a, offA, aBytes) || !launchStrided(arena, kernel, blocks * STRIDED_BLOCK,
					new long[] { buffers[0], buffers[1], n }, new boolean[] { true, true, false }, false)) {
				return null;
			}
			// The partials are the ONLY thing that comes home, and the synchronous
			// download orders itself behind the launch that made them.
			double[] partials = new double[blocks];
			if (!download(MemorySegment.ofArray(partials), 0, buffers[1], pBytes)) {
				return null;
			}
			drainPending();
			return partials;
		}
		catch (Throwable ex) {
			return null;
		}
		finally {
			release(owned);
		}
	}

	/**
	 * Copies a small {@code int} vector -- an index list, or a scatter's grouped indices
	 * -- into the device buffer the index-tier kernels read it out of. Unlike the strided
	 * tier's layout, an index list has no fixed size, so it cannot ride in the parameter
	 * block and stays a buffer plus a critical copy (behind {@link #awaitQueued}).
	 */
	private boolean uploadInts(Arena arena, long destination, int[] values) throws Throwable {
		if (!awaitQueued()) {
			return false;
		}
		MemorySegment host = arena.allocate(I, values.length);
		for (int k = 0; k < values.length; k++) {
			host.setAtIndex(I, k, values[k]);
		}
		int status = this.driver.memcpyHtoD(destination, host, (long) values.length * Integer.BYTES);
		return status == CuResult.SUCCESS || fail(status);
	}

	/**
	 * Adam's fused update in place over the parameter, its gradient and the two moments:
	 * the parameter and the moments are WRITTEN on the device and marked dirty, so a
	 * model's weights, once resident, never come home until the host reads them. The rule
	 * is {@code [lr, lr*wd, wd, b1, 1-b1, b2, 1-b2, eps, c1, c2, mode]}.
	 * @return {@code true} when the update ran
	 */
	@Override
	public boolean adamStep(double[] x, int ox, double[] g, int og, double[] m, int om, double[] v, int ov, int n,
			double[] rule) {
		return adamStep(MemorySegment.ofArray(x), x, ox, MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(m), m,
				om, MemorySegment.ofArray(v), v, ov, n, rule, Double.BYTES, this.resident[ADAM_F64]);
	}

	@Override
	public boolean adamStepF(float[] x, int ox, float[] g, int og, float[] m, int om, float[] v, int ov, int n,
			double[] rule) {
		return adamStep(MemorySegment.ofArray(x), x, ox, MemorySegment.ofArray(g), g, og, MemorySegment.ofArray(m), m,
				om, MemorySegment.ofArray(v), v, ov, n, rule, Float.BYTES, this.resident[ADAM_F32]);
	}

	private boolean adamStep(MemorySegment x, Object xh, int ox, MemorySegment g, Object gh, int og, MemorySegment m,
			Object mh, int om, MemorySegment v, Object vh, int ov, int n, double[] rule, int width,
			MemorySegment kernel) {
		if (!this.usable) {
			return false;
		}
		long bytes = (long) n * width, offX = (long) ox * width, offG = (long) og * width, offM = (long) om * width,
				offV = (long) ov * width;
		long[] buffers = { 0, 0, 0, 0 }, owned = { 0, 0, 0, 0 };
		try (Arena arena = Arena.ofConfined()) {
			if (!enter()) {
				return false;
			}
			buffers[0] = this.residency.lookup(xh, offX, bytes);
			buffers[1] = this.residency.lookup(gh, offG, bytes);
			buffers[2] = this.residency.lookup(mh, offM, bytes);
			buffers[3] = this.residency.lookup(vh, offV, bytes);
			if (!allocate(arena, buffers, owned, bytes, bytes, bytes, bytes)) {
				return false;
			}
			boolean sync = (long) n * STRIDED_FLOPS_PER_ELEMENT >= this.syncFlopCeiling;
			if (!stage(buffers, owned, 0, xh, x, offX, bytes) || !stage(buffers, owned, 1, gh, g, offG, bytes)
					|| !stage(buffers, owned, 2, mh, m, offM, bytes) || !stage(buffers, owned, 3, vh, v, offV, bytes)) {
				return false;
			}
			// The parameter block: X, G, M, V, n, the ten doubles of the rule, mode.
			MemorySegment parameters = arena.allocate(P, 16);
			for (int i = 0; i < 4; i++) {
				MemorySegment slot = arena.allocate(L);
				slot.set(L, 0, buffers[i]);
				parameters.setAtIndex(P, i, slot);
			}
			MemorySegment nv = arena.allocate(I);
			nv.set(I, 0, n);
			parameters.setAtIndex(P, 4, nv);
			for (int i = 0; i < 10; i++) {
				MemorySegment slot = arena.allocate(D);
				slot.set(D, 0, rule[i]);
				parameters.setAtIndex(P, 5 + i, slot);
			}
			MemorySegment mode = arena.allocate(I);
			mode.set(I, 0, (int) rule[10]);
			parameters.setAtIndex(P, 15, mode);
			if (!launchFlat(kernel, n, parameters, sync)) {
				return false;
			}
			// Three arrays were written in place: each stays resident as the
			// authoritative
			// copy (lazily) or comes home now (eagerly).
			return finish(x, offX, buffers, owned, 0, xh, bytes) && finish(m, offM, buffers, owned, 2, mh, bytes)
					&& finish(v, offV, buffers, owned, 3, vh, bytes);
		}
		catch (Throwable ex) {
			return false;
		}
		finally {
			release(owned);
		}
	}

	/** One flat launch over {@code n} elements with a ready parameter block. */
	private boolean launchFlat(MemorySegment function, int n, MemorySegment parameters, boolean sync) throws Throwable {
		int status = this.driver.launchKernel(function, (n + STRIDED_BLOCK - 1) / STRIDED_BLOCK, 1, 1, STRIDED_BLOCK, 1,
				1, 0, MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		return awaitLaunched(sync);
	}

	/**
	 * The wait that follows a launch. EAGERLY it is the explicit {@code cuCtxSynchronize}
	 * the {@code sync} flag asks for ({@link #SYNC_FLOPS_PER_MULTIPROCESSOR}): the result
	 * is about to come down over a CRITICAL copy, and without the wait the kernel's whole
	 * runtime would sit inside that copy's safepoint-free window. LAZILY nothing comes
	 * down at the end of a call, so the same wait only idled the host while the device
	 * ran -- a profile of the book's-shape training step measured 817 of them a step,
	 * half the step's wall time -- and it is SKIPPED: the launch is recorded in
	 * {@link #queued} instead, and the critical copies keep the safepoint argument by
	 * asking {@link #awaitQueued} first.
	 */
	private boolean awaitLaunched(boolean sync) throws Throwable {
		if (this.lazy) {
			this.queued = true;
			return true;
		}
		if (!sync) {
			return true;
		}
		int status = this.driver.ctxSynchronize();
		return status == CuResult.SUCCESS || fail(status);
	}

	/**
	 * The explicit wait before a CRITICAL copy: drains the null-stream queue on a plain,
	 * safepoint-friendly downcall so that the copy's critical window holds the copy
	 * alone. A no-op whenever nothing has been enqueued since the last wait -- eager
	 * calls, and the pinned-bounce download path, never need it.
	 */
	private boolean awaitQueued() throws Throwable {
		if (!this.queued) {
			return true;
		}
		int status = this.driver.ctxSynchronize();
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		this.queued = false;
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
			this.queued = false;
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
		if (!awaitQueued()) {
			return false;
		}
		for (long done = 0; done < bytes; done += CRITICAL_CHUNK_BYTES) {
			long chunk = Math.min(CRITICAL_CHUNK_BYTES, bytes - done);
			int status = this.driver.memcpyHtoD(destination + done, heap.asSlice(offset + done, chunk), chunk);
			if (status != CuResult.SUCCESS) {
				return fail(status);
			}
		}
		return true;
	}

	/**
	 * The mirror of {@link #upload}, for the result -- STAGED through the pinned bounce
	 * buffer (see {@link #BOUNCE_BYTES} for why): the device DMAs a chunk into pinned
	 * memory on a plain, safepoint-friendly downcall, and the Java side copies it into
	 * the fresh result array, which is a memcpy and never a cold-page walk. Without a
	 * bounce buffer it is the direct critical copy it used to be.
	 */
	private boolean download(MemorySegment heap, long offset, long source, long bytes) throws Throwable {
		if (this.bounce.equals(MemorySegment.NULL)) {
			if (!awaitQueued()) {
				return false;
			}
			for (long done = 0; done < bytes; done += CRITICAL_CHUNK_BYTES) {
				long chunk = Math.min(CRITICAL_CHUNK_BYTES, bytes - done);
				int status = this.driver.memcpyDtoH(heap.asSlice(offset + done, chunk), source + done, chunk);
				if (status != CuResult.SUCCESS) {
					return fail(status);
				}
			}
			return true;
		}
		synchronized (this.bounceLock) {
			for (long done = 0; done < bytes; done += BOUNCE_BYTES) {
				long chunk = Math.min(BOUNCE_BYTES, bytes - done);
				int status = this.driver.memcpyDtoHPinned(this.bounce, source + done, chunk);
				if (status != CuResult.SUCCESS) {
					return fail(status);
				}
				MemorySegment.copy(this.bounce, 0, heap, offset + done, chunk);
			}
		}
		return true;
	}

	/**
	 * One launch of the chosen product kernel over the whole output, 16x16 threads per
	 * block and one block per {@link Tile} -- {@code gridDim.z} is the batch, so a stack
	 * of products is still ONE launch -- plus, for a product long enough that it would
	 * matter, the explicit wait that keeps the kernel's runtime out of the following
	 * critical copy.
	 */
	private boolean launch(Arena arena, Tile tile, long[] buffers, int batch, long sa, long sb, int n, int m, int p,
			boolean ta, boolean tb, boolean sync) throws Throwable {
		MemorySegment a = arena.allocate(L), b = arena.allocate(L), c = arena.allocate(L);
		a.set(L, 0, buffers[0]);
		b.set(L, 0, buffers[1]);
		c.set(L, 0, buffers[2]);
		MemorySegment rows = arena.allocate(I), columns = arena.allocate(I), inner = arena.allocate(I);
		rows.set(I, 0, n);
		columns.set(I, 0, p);
		inner.set(I, 0, m);
		// A single product on the 16x16 kernel is the plain entry point with the
		// parameter block it has always had; a stack, and every register-tiled product,
		// carries the two stride parameters.
		boolean batched = tile.batchedParameters();
		MemorySegment parameters = arena.allocate(P, batched ? 10 : 6);
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
			MemorySegment transposeA = arena.allocate(I), transposeB = arena.allocate(I);
			transposeA.set(I, 0, ta ? 1 : 0);
			transposeB.set(I, 0, tb ? 1 : 0);
			parameters.setAtIndex(P, 6, strideA);
			parameters.setAtIndex(P, 7, strideB);
			parameters.setAtIndex(P, 8, transposeA);
			parameters.setAtIndex(P, 9, transposeB);
		}
		int status = this.driver.launchKernel(tile.function(), ceilDiv(p, tile.columns()), ceilDiv(n, tile.rows()),
				batch, TILE, TILE, 1, 0, MemorySegment.NULL, parameters, MemorySegment.NULL);
		if (status != CuResult.SUCCESS) {
			return fail(status);
		}
		return awaitLaunched(sync);
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
		return awaitLaunched(sync);
	}

	/**
	 * Frees every device buffer this call allocated and the cache did not take, on the
	 * failure path too. A buffer {@link #stage} or {@link #finish} handed to the cache is
	 * struck from {@code owned} first and outlives the call.
	 */
	private void release(long[] owned) {
		for (int i = 0; i < owned.length; i++) {
			if (owned[i] != 0) {
				free(owned[i]);
				owned[i] = 0;
			}
		}
	}

	/** Frees one device buffer through whichever allocator is in force. */
	private void free(long pointer) {
		try {
			if (this.pooled) {
				this.driver.memFreeAsync(pointer);
			}
			else {
				this.driver.memFree(pointer);
			}
		}
		catch (Throwable ex) {
			// A free that fails means the context is already gone; the sticky rule has
			// retired the feature, and there is nothing to undo.
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
