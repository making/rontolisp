package am.ik.gpu;

import org.jspecify.annotations.Nullable;

/**
 * A matrix product -- or an element-wise map -- on the GPU, or a decline. That is the
 * whole public surface of this library, and it is deliberately the shape of a PARTIAL
 * function: a caller offers a computation and either the device took it or it did not,
 * said with {@code false} or {@code null} and never with an exception.
 *
 * <h2>What "declines" covers</h2>
 *
 * Everything. No NVIDIA driver and no Metal, no device, a card older than the PTX this
 * library carries, a JVM that forbids native access, a shape too big for the launch grid,
 * a product too small to be worth a round trip, one too big for free device memory, a
 * failed {@code CUresult} of any kind, a command buffer that did not complete, a
 * {@code double} operand on a backend that has no {@code double}, or a context that a
 * previous failure left unusable. All of them answer quietly, and the caller runs
 * whatever it would have run anyway. This is the same posture {@code --simd} has toward a
 * JDK without {@code jdk.incubator.vector} and {@code --blas} has toward a machine with
 * no tuned CBLAS ({@code .kb/linalg-simd.md}, {@code .kb/linalg-blas.md}).
 *
 * <p>
 * Two things are NOT declines, because neither is this library's to swallow. A
 * {@code null} operand array is a defect in the caller and throws
 * {@link NullPointerException}; and the array-returning overloads allocate the result, so
 * they can throw {@link OutOfMemoryError} exactly as {@code new double[n * p]} can. The
 * {@code out}-taking overloads allocate nothing.
 *
 * <h2>The probe runs once, and only when something needs it</h2>
 *
 * Binding the driver, retaining the primary context and JIT-compiling the kernels happens
 * on the first call that needs a device, and is cached for the life of the process,
 * failure included: a machine without a GPU pays one failed {@code dlopen} and never
 * touches the driver again. {@link #worth} deliberately does not need it, so an
 * interceptor can ask that on a path that then never touches the device.
 * {@link #description()} says what was found or why nothing was, and is what a caller
 * should print when it was asked for a GPU and cannot have one.
 *
 * <h2>Two kinds of device, one surface</h2>
 *
 * An NVIDIA card through the CUDA driver, or an Apple GPU through Metal, chosen by
 * whichever one this machine has. Nothing above this class needs to know which, but two
 * differences are visible through the decline protocol and are worth expecting: the Metal
 * backend has no {@code double} at all, so every {@code double}-taking method below
 * declines there whatever the size; and its per-call floor is five times CUDA's, so its
 * size thresholds are higher and it does not take the {@linkplain #fold axis fold} at
 * all. {@code .kb/gpu.md} has the measurements behind each of those.
 *
 * <h2>Offsets, because the arrays have headers</h2>
 *
 * Every operand -- the result included -- takes an element offset. rontolisp's compiled
 * backends carry a {@code [rank, dim..., data...]} header inside the same array as the
 * data, so a caller must be able to say where the elements start AND to have the product
 * written straight into the array it has already shaped; an interpreter whose arrays hold
 * data alone passes 0. The array-returning overloads are the convenience form for a
 * caller that wants a bare {@code n * p} result, and they delegate.
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
	static final long POOLED_MIN_WORK = 1L << 17;

	/**
	 * The threshold {@link #multiply} applies instead on a machine whose driver has no
	 * usable stream-ordered allocator. Allocating the three buffers per call raises the
	 * floor from 15 us to 170, which moves the crossover against the same CPU column to
	 * between n=96 (131 us) and n=128 (384 us). {@link #worth} does NOT apply it, and
	 * says why.
	 */
	static final long UNPOOLED_MIN_WORK = 1L << 21;

	/**
	 * Below this many elements an {@linkplain #map element-wise map} declines and the
	 * caller's own kernel wins.
	 *
	 * <p>
	 * It is four orders of magnitude below the product's threshold because it counts
	 * ELEMENTS and not multiply-adds: a map is one libm call per element, so 2^14 of them
	 * is 2^14 calls where a 2^17-multiply-add product is a 51-cubed matrix. Measured
	 * against the fastest CPU path rontolisp has ({@code --simd}, JIT-warm) on the
	 * machine this was tuned on, the crossover for the cheapest member offered here is
	 * between 2000 and 4000 elements and for the dearest it is below 1000; the threshold
	 * sits at 16384, where the cheapest of them is already 2.6x ahead and the rest 5-9x.
	 * Below it the CPU wins outright and declining costs nothing. See {@code .kb/gpu.md}.
	 */
	static final long MAP_POOLED_MIN_ELEMENTS = 1L << 14;

	/**
	 * The element-wise threshold on a machine whose driver has no usable stream-ordered
	 * allocator, where the per-call floor is ~170 us rather than ~15. That floor is above
	 * the CPU's cost for 16384 elements of any member here, so the threshold moves up to
	 * where the CPU column passes it: 65536 elements, where the cheapest member measures
	 * 360 us on the CPU.
	 */
	static final long MAP_UNPOOLED_MIN_ELEMENTS = 1L << 16;

	/**
	 * Below this many OUTPUT elements a {@linkplain #bcast broadcast binary op} or a
	 * {@linkplain #gather strided gather} declines.
	 *
	 * <p>
	 * It is one power of two above the element-wise map's, and the reason is the CPU
	 * column rather than this side: a map's CPU twin is a libm call per element, while
	 * these two are one arithmetic op or one load per element -- but walked by an
	 * ODOMETER rather than by a lane loop, which is what makes them worth offering at
	 * all. Measured against a JIT-warm {@code --simd} CPU on the machine this was tuned
	 * on, the crossover for both is between 4096 and 16384 elements; at 16384 the margin
	 * is 1.2x at f64 (26.5 us against 21.8), which is inside the measurement, and at
	 * 32768 it is 2.1x (53.0 against 25.7) at f64 and 2.3x at f32. The threshold sits at
	 * the second, for the reason the product's does: where the win is unambiguous, not
	 * where it first appears. See {@code .kb/gpu.md}.
	 */
	static final long STRIDED_POOLED_MIN_ELEMENTS = 1L << 15;

	/**
	 * The strided threshold on a machine whose driver has no usable stream-ordered
	 * allocator, where the per-call floor is ~170 us rather than ~15 -- which is above
	 * the CPU's cost for 32768 elements of either member, so it moves up to where the CPU
	 * column passes it (2^17, ~200 us).
	 */
	static final long STRIDED_UNPOOLED_MIN_ELEMENTS = 1L << 17;

	/**
	 * Below this many INPUT elements an {@linkplain #fold axis fold} declines. It counts
	 * the input rather than the output because that is what the fold reads and what the
	 * copy up costs; the way back is {@code outer * inner} elements and is usually
	 * nothing.
	 *
	 * <p>
	 * Four times the strided threshold, and measured the same way: a fold moves ONE array
	 * over the link where a broadcast binary moves three, so its device side is cheaper
	 * -- but its CPU twin is cheaper by more, because a fold's odometer is one counter
	 * rather than three. The two columns are level at 65536 (29.5 us against 30.6 at f64)
	 * and the device is 1.7x/2.1x ahead at 131072. See {@code .kb/gpu.md}.
	 */
	static final long FOLD_POOLED_MIN_ELEMENTS = 1L << 17;

	/**
	 * The fold threshold with no stream-ordered allocator; see the two siblings above.
	 */
	static final long FOLD_UNPOOLED_MIN_ELEMENTS = 1L << 19;

	/**
	 * How many OUTPUT cells an axis fold needs before it is offered at all, whatever its
	 * input size. Each output cell is one thread walking the axis sequentially -- which
	 * is what keeps the fold in the defun's own order and therefore bit-identical -- so a
	 * fold with one output cell is a single-threaded device loop and loses to any CPU.
	 * One block's worth is the floor.
	 */
	private static final long FOLD_MIN_CELLS = 256;

	/**
	 * Below this many elements a {@linkplain #rngFill generator fill} declines and the
	 * CPU's sequential walk wins. A fill has no operand to copy up -- only the result
	 * comes back -- so its floor is the lowest of the set, and the CPU side costs ~3 ns
	 * per uniform draw (~36 per Irwin-Hall normal, which is twelve of them). Measured on
	 * the GB10 ({@code .todo/123-gpu-acceleration/RngCrossover.java}): one uniform draw
	 * per element is 0.7-0.8x at 2^12 and 1.6-1.8x at 2^13, the normal 4x at 2^12
	 * already; 2^13 is where every rule wins, and the one threshold serves all three
	 * ({@code .kb/gpu.md}).
	 */
	static final long RNG_POOLED_MIN_ELEMENTS = 1L << 13;

	/**
	 * The same on a machine without the stream-ordered allocator (one buffer, ~180 us).
	 */
	static final long RNG_UNPOOLED_MIN_ELEMENTS = 1L << 15;

	/**
	 * Below this many elements ({@code rows * cols}) a {@linkplain #matvec
	 * matrix-by-vector product} declines. It is measured with the matrix RESIDENT, which
	 * is the only case the member is offered in (below): a GEMV reads every element of
	 * its matrix once, so over the link it loses to the CPU until ~2^19 elements, while
	 * over a matrix that is already on the device its whole cost is the ~9 us floor --
	 * {@code x} up, a launch, {@code y} down -- and the crossover against the JIT-warm
	 * {@code --simd} lane kernel is between 256x256 (10.0 us CPU against 9.7, a tie at
	 * {@code #f}) and 384x384 (23.0 against 10.7, 2.1x; 45.5 against 9.9 at {@code #d}).
	 * 2^17 sits at the second, where the win is unambiguous ({@code .kb/gpu.md}).
	 */
	static final long MATVEC_POOLED_MIN_ELEMENTS = 1L << 17;

	/**
	 * The same on a machine without the stream-ordered allocator, where the floor is ~180
	 * us: the CPU column passes that at about a million elements (200 us at 1024x1024
	 * {@code #f}).
	 */
	static final long MATVEC_UNPOOLED_MIN_ELEMENTS = 1L << 20;

	/** The deepest rank {@link #bcast} and {@link #gather} will walk; deeper declines. */
	private static final int MAX_STRIDED_RANK = 16;

	/**
	 * The op codes {@link #map} takes, mirrored by the {@code switch} in {@code gemm.cu}
	 * -- the two are changed together and nothing links them, so a new member is one case
	 * there, one constant here and one regeneration of the PTX.
	 *
	 * <p>
	 * Every one of them is a member whose scalar cost is a libm CALL, and that is the
	 * whole selection rule: measured on this project's machine the device beats a
	 * JIT-warm SIMD CPU loop by 9-124x at f64 and by up to 394x at f32 on these, while
	 * {@code sqrt} (1.4-2x), {@code abs}, {@code negative} and {@code sign} are one
	 * machine instruction over one stream and the binary {@code add} / {@code sub} /
	 * {@code mul} / {@code div} are one over three. Those are declines, and they are
	 * declines by measurement rather than by assumption.
	 */
	public static final int MAP_EXP = 0, MAP_LOG = 1, MAP_TANH = 2, MAP_SIN = 3, MAP_COS = 4, MAP_TAN = 5, MAP_ASIN = 6,
			MAP_ACOS = 7, MAP_ATAN = 8, MAP_SINH = 9, MAP_COSH = 10, MAP_ERF = 11;

	/**
	 * How many op codes {@link #map} knows; an op outside {@code [0, MAP_OPS)} declines.
	 */
	public static final int MAP_OPS = 12;

	/**
	 * The op codes {@link #bcast} takes, mirrored by the {@code bin_op} switch in
	 * {@code gemm.cu} -- the same arrangement {@link #MAP_EXP} has, and the same rule: a
	 * new member is one case there, one constant here and one regeneration of the PTX.
	 *
	 * <p>
	 * These are the four arithmetic ops and the two strict selects, and offering them is
	 * NOT a reversal of the element-wise tier's refusal of the same names. That refusal
	 * was measured at EQUAL shapes, where the caller's own kernel is a lane loop and a
	 * round trip cannot win (and still cannot: 65 us against 112 at f32, measured). This
	 * entry point is for the BROADCAST shape, where the caller walks an odometer element
	 * by element instead and the same round trip is 5.5-8.5x faster. {@code .kb/gpu.md}
	 * has both halves.
	 *
	 * <p>
	 * {@link #BIN_MAX} and {@link #BIN_MIN} are the STRICT selects {@code x > y ? x : y}
	 * and {@code x < y ? x : y}, so the second operand wins a tie and a NaN.
	 */
	public static final int BIN_ADD = 0, BIN_SUB = 1, BIN_MUL = 2, BIN_DIV = 3, BIN_MAX = 4, BIN_MIN = 5;

	/**
	 * How many op codes {@link #bcast} knows; an op outside {@code [0, BIN_OPS)}
	 * declines.
	 */
	public static final int BIN_OPS = 6;

	/**
	 * The op codes {@link #fold} takes, mirrored by the {@code fold} switch in
	 * {@code gemm.cu}. {@link #FOLD_SUM} seeds with 0 and adds; {@link #FOLD_AMAX} and
	 * {@link #FOLD_AMIN} seed with the FIRST element along the axis and compare strictly,
	 * so the accumulator wins a tie and a NaN.
	 */
	public static final int FOLD_SUM = 0, FOLD_AMAX = 1, FOLD_AMIN = 2;

	/** How many op codes {@link #fold} knows; anything else declines. */
	public static final int FOLD_OPS = 3;

	private Gpu() {
	}

	/**
	 * The probe, and everything that depends on having run it, behind a holder so that it
	 * happens on the first question that NEEDS a device rather than on any mention of
	 * {@link Gpu}. It is not a cheap static initializer: a {@code dlopen}, a
	 * {@code cuInit}, a retained primary context (which reserves device memory of its
	 * own) and a PTX JIT.
	 */
	private static final class Probe {

		private static final @Nullable GpuDevice DEVICE;

		private static final String DESCRIPTION;

		private static final long MIN_WORK;

		private static final long MAP_MIN_ELEMENTS;

		private static final long STRIDED_MIN_ELEMENTS;

		private static final long FOLD_MIN_ELEMENTS;

		private static final long RNG_MIN_ELEMENTS;

		private static final long MATVEC_MIN_ELEMENTS;

		static {
			GpuDevice device;
			String description;
			try {
				// CUDA first and Metal second, which is not a preference: the two are
				// mutually exclusive in practice (no machine has both libcuda.so.1 and
				// Metal.framework) and each declines in a failed library lookup on the
				// other's platform, so the order costs a dlopen that was going to fail
				// anyway. What it does decide is which SENTENCE a machine with neither
				// gets, and that is the platform's own -- see #describe.
				CudaGemm.Probe cuda = CudaGemm.probe();
				if (cuda.gemm() != null) {
					device = cuda.gemm();
					description = cuda.description();
				}
				else {
					MetalGemm.Probe metal = MetalGemm.probe();
					device = metal.gemm();
					description = device != null ? metal.description() : describe(cuda, metal);
				}
			}
			catch (Throwable ex) {
				// The probes are written not to throw; if one ever does, the answer is
				// still no. The nested try is not paranoia for its own sake: an exception
				// escaping HERE would fail this class's initialization, and every later
				// call would get a NoClassDefFoundError instead of a decline, for the
				// life of the process.
				device = null;
				description = "no GPU could be probed";
				try {
					description = description + ": " + ex;
				}
				catch (Throwable nested) {
					description = description + ": " + ex.getClass().getName();
				}
			}
			DEVICE = device;
			DESCRIPTION = description;
			Gpu.probed = device;
			GpuDevice.Thresholds thresholds = device == null
					? new GpuDevice.Thresholds(POOLED_MIN_WORK, MAP_POOLED_MIN_ELEMENTS, STRIDED_POOLED_MIN_ELEMENTS,
							FOLD_POOLED_MIN_ELEMENTS, RNG_POOLED_MIN_ELEMENTS, MATVEC_POOLED_MIN_ELEMENTS)
					: device.thresholds();
			MIN_WORK = thresholds.work();
			MAP_MIN_ELEMENTS = thresholds.map();
			STRIDED_MIN_ELEMENTS = thresholds.strided();
			FOLD_MIN_ELEMENTS = thresholds.fold();
			RNG_MIN_ELEMENTS = thresholds.rng();
			MATVEC_MIN_ELEMENTS = thresholds.matvec();
		}

		/**
		 * What to say when NEITHER backend found a device. Both have a reason and only
		 * one of them is about this machine -- "libcuda.so.1 is not present" is noise on
		 * a Mac and "this is not a Mac" is noise on a Linux box -- so the platform picks.
		 */
		private static String describe(CudaGemm.Probe cuda, MetalGemm.Probe metal) {
			String os;
			try {
				os = String.valueOf(System.getProperty("os.name"));
			}
			catch (Throwable ex) {
				os = "";
			}
			return os.startsWith("Mac") ? metal.description() : cuda.description();
		}

		private Probe() {
		}

	}

	/**
	 * Whether this machine has a GPU these kernels can run on. {@code false} is an
	 * ordinary answer, not an error, and it is the answer on most machines. The first
	 * call runs the probe.
	 * @return {@code true} when a product may be offered to {@link #multiply}
	 */
	public static boolean available() {
		GpuDevice device = Probe.DEVICE;
		return device != null && device.usable();
	}

	/**
	 * What was found -- device model, architecture, driver version -- or why nothing was.
	 * Always a printable one-liner, on every machine. The first call runs the probe.
	 * @return a one-line description of the probe's outcome
	 */
	public static String description() {
		return Probe.DESCRIPTION;
	}

	/**
	 * Supplies the PTX kernel text, for an embedder that carries this library's CLASSES
	 * but not its resources.
	 *
	 * <p>
	 * Normally the kernels are read from {@code am/ik/gpu/gemm.ptx} beside
	 * {@link CudaGemm}, and nothing needs this. rontolisp's JVM backend injects the
	 * library's class files into the standalone {@code .class} it emits, renamed into
	 * that program's own package, where a classpath resource of ours cannot follow -- so
	 * it hands the text in instead ({@code .kb/gpu.md}). The text supplied wins over the
	 * resource when both are present.
	 *
	 * <p>
	 * Must be called BEFORE the first {@link #available()} / {@link #description()} /
	 * {@link #multiply} on this process, which is when the module is loaded; afterwards
	 * it changes nothing and is not an error. It never throws and it never probes.
	 * @param ptx the PTX text the driver is to JIT-compile
	 */
	public static void useKernels(String ptx) {
		CudaGemm.embeddedPtx(ptx);
	}

	/**
	 * Supplies the Metal Shading Language kernel text, for an embedder that carries this
	 * library's CLASSES but not its resources -- {@link #useKernels(String)}'s Apple
	 * sibling, and needed for the same reason and by the same one caller.
	 *
	 * <p>
	 * Unlike the PTX this is the SOURCE rather than a compiled artifact: Metal's compiler
	 * is in the OS, so there is nothing generated to carry. Must be called BEFORE the
	 * first {@link #available()} / {@link #description()} / {@link #multiply} on this
	 * process; afterwards it changes nothing and is not an error. It never throws and it
	 * never probes.
	 * @param msl the MSL text the OS is to compile
	 */
	public static void useMetalKernels(String msl) {
		MetalGemm.embeddedSource(msl);
	}

	/**
	 * The one device this process has probed, or {@code null}. Package-private and for
	 * the tests: probing again would retain a second reference to the primary context and
	 * JIT the module a second time, so nothing may call {@code CudaGemm.probe()} twice.
	 * @return the probed device, or {@code null} when there is none
	 */
	static @Nullable GpuDevice device() {
		return Probe.DEVICE;
	}

	/**
	 * The threshold {@link #multiply} is applying on this machine, which is
	 * {@link #worth}'s only when the driver's pooled allocator is in use. Package-private
	 * and for the tests, which must size their shapes off the threshold actually in force
	 * rather than off the one that was true when they were written.
	 * @return the minimum {@code n * m * p} a product is accepted at
	 */
	static long minWork() {
		return Probe.MIN_WORK;
	}

	/**
	 * The element-wise threshold in force on this machine, which is
	 * {@link #worthMap(long)}'s only when the driver's pooled allocator is in use.
	 * Package-private and for the tests.
	 * @return the minimum element count a map is accepted at
	 */
	static long mapMinElements() {
		return Probe.MAP_MIN_ELEMENTS;
	}

	/**
	 * The strided-tier threshold actually in force on this machine. Package-private and
	 * for the tests, exactly as {@link #mapMinElements} is.
	 * @return the minimum output element count a broadcast or gather is offered at
	 */
	static long stridedMinElements() {
		return Probe.STRIDED_MIN_ELEMENTS;
	}

	/**
	 * The axis-fold threshold actually in force on this machine, in INPUT elements.
	 * @return the minimum input element count a fold is offered at
	 */
	static long foldMinElements() {
		return Probe.FOLD_MIN_ELEMENTS;
	}

	/**
	 * The minimum number of output cells a fold needs. Package-private and for the tests.
	 * @return the fold's parallelism floor
	 */
	static long foldMinCells() {
		return FOLD_MIN_CELLS;
	}

	static long rngMinElements() {
		return Probe.RNG_MIN_ELEMENTS;
	}

	/**
	 * The matrix-by-vector threshold in force on this machine, in {@code rows * cols};
	 * {@link Long#MAX_VALUE} on a backend that is not a member of it. Package-private and
	 * for the tests.
	 * @return the minimum element count a GEMV is offered at
	 */
	static long matvecMinElements() {
		return Probe.MATVEC_MIN_ELEMENTS;
	}

	/**
	 * The probed device, copied out of {@link Probe} the moment it exists, so that
	 * {@link #written} can answer "nothing is resident" on a process that has not probed
	 * without running the probe -- a {@code dlopen}, a context and a PTX JIT -- from an
	 * {@code aset} loop that merely stored a double.
	 */
	private static volatile @Nullable GpuDevice probed;

	/**
	 * Tells the library that a host array it may hold a RESIDENT copy of has been written
	 * in place, so that copy is stale and must not be read again. The device members keep
	 * a cache from a host array, by identity, to a device buffer holding a copy of it --
	 * which is what lets a chain of members pay for one upload rather than one per call
	 * -- and the host array stays authoritative, so every in-place write to a packed
	 * float array has to come through here. The interceptors call it from every element
	 * setter and every in-place kernel; the enumeration is in {@code .kb/gpu.md}.
	 *
	 * <p>
	 * Cheap when it does not matter: a volatile read on a process with no device or
	 * nothing resident, and an identity lookup under an uncontended monitor otherwise. It
	 * never runs the probe, never touches the driver, never throws, and may be called
	 * from any thread. An array that was never an operand is simply not found.
	 * @param hostArray the {@code double[]} or {@code float[]} that was written
	 */
	public static void written(Object hostArray) {
		GpuDevice device = probed;
		if (device != null) {
			device.written(hostArray);
		}
	}

	/**
	 * Bytes held by resident copies right now, or {@code 0}. Package-private and for the
	 * tests, which assert the cache is bounded and that a release empties it.
	 * @return the resident total, in bytes
	 */
	static long residentBytes() {
		GpuDevice device = Probe.DEVICE;
		return device != null ? device.residentBytes() : 0;
	}

	/**
	 * Drops and frees every resident copy. Package-private and for the tests: the leak
	 * assertions measure against an EMPTY device.
	 */
	static void releaseResident() {
		GpuDevice device = Probe.DEVICE;
		if (device != null) {
			device.releaseResident();
		}
	}

	/**
	 * Imposes a residency budget in bytes on the CUDA device, or {@code -1} to restore
	 * the derived one. Package-private and for the tests; a no-op on any other device.
	 * @param bytes the budget, or -1
	 */
	static void residentBudget(long bytes) {
		if (Probe.DEVICE instanceof CudaGemm cuda) {
			cuda.residentBudget(bytes);
		}
	}

	/**
	 * The CUDA device's residency cache, for the tests' hit and miss counts, or
	 * {@code null} on any other device.
	 * @return the cache, or null
	 */
	static @Nullable CudaResidency residency() {
		return Probe.DEVICE instanceof CudaGemm cuda ? cuda.residency() : null;
	}

	/**
	 * Whether an {@code n x m} by {@code m x p} product is big enough to be worth a round
	 * trip to the device at all -- a pure size predicate over the three dimensions, so a
	 * caller can ask it BEFORE it goes to the trouble of unwrapping its operands, and
	 * before anything has touched the driver.
	 *
	 * <p>
	 * It is deliberately the LOWER of the two thresholds this library uses: a machine
	 * whose driver has no pooled allocator has a floor eleven times higher and declines
	 * up to 16x further up, but discovering that costs a probe, and this predicate's
	 * whole value is that it costs nothing. So {@code true} means "worth unwrapping for",
	 * not "will be accepted"; {@link #multiply} asks the real question.
	 * @param n rows of the left operand and of the result
	 * @param m the inner dimension
	 * @param p columns of the right operand and of the result
	 * @return {@code true} when the product is above the size threshold
	 */
	public static boolean worth(long n, long m, long p) {
		return worth(1, n, m, p);
	}

	/**
	 * The same predicate for a STACK of {@code batch} such products, which is what a
	 * batched matrix product ({@code torch.bmm}, and hence every attention layer) offers.
	 *
	 * <p>
	 * The measure is the TOTAL multiply-adds, {@code batch * n * m * p}, against the same
	 * threshold -- a batch is one round trip and one launch, so the floor this threshold
	 * exists to clear is paid once for the whole stack, not once per matrix. That is also
	 * why the batched shape is the one this feature pays on: the CPU's cost grows with
	 * the total work while the device's fixed cost does not, and a batch of 16 small
	 * matrices is 16x the work behind one 15 us floor. Measured, a batch of 64x64
	 * products crosses over at batch 1 and stays ahead from there ({@code .kb/gpu.md}).
	 * @param batch how many products are stacked
	 * @param n rows of each left operand and of each result
	 * @param m the inner dimension
	 * @param p columns of each right operand and of each result
	 * @return {@code true} when the stack is above the size threshold
	 */
	public static boolean worth(long batch, long n, long m, long p) {
		return batch > 0 && n > 0 && m > 0 && p > 0 && batch * n * m * p >= POOLED_MIN_WORK;
	}

	/**
	 * {@code out = a x b} for a row-major {@code n x m} by {@code m x p} pair of
	 * double-float arrays, written straight into the caller's array at its own offset.
	 * This is the form an interceptor wants: rontolisp's compiled arrays carry a
	 * dimension header in front of their data, and a fresh array would have to be copied
	 * into one.
	 *
	 * <p>
	 * Double is the width this device class is WORST at -- a tenth to a fortieth of its
	 * single-float throughput, depending on the card -- so this product wins by less than
	 * its single-float sibling does, and on some machines a threaded CPU BLAS draws level
	 * with it. It is still the width {@code linalg} defaults to, so it is here.
	 *
	 * <p>
	 * The result is NOT bit-identical to a scalar row-by-column product: the kernel folds
	 * {@code k} in the same ascending order, but every one of its multiply-adds is FUSED
	 * ({@code fma.rn.f64}), so each term is rounded once where a scalar loop rounds
	 * twice. Callers that need identity must not offer the product.
	 * @param a the left operand, row-major, elements starting at {@code offsetA}
	 * @param offsetA the index of {@code a}'s first element
	 * @param b the right operand, row-major, elements starting at {@code offsetB}
	 * @param offsetB the index of {@code b}'s first element
	 * @param out the array the {@code n * p} result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param n rows of {@code a} and of the result
	 * @param m columns of {@code a} and rows of {@code b}
	 * @param p columns of {@code b} and of the result
	 * @return {@code true} when {@code out} was filled; {@code false} when this call
	 * declined, in which case {@code out} is untouched
	 */
	public static boolean multiply(double[] a, int offsetA, double[] b, int offsetB, double[] out, int offsetOut, int n,
			int m, int p) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offered(a.length, offsetA, b.length, offsetB, out.length, offsetOut, n, m, p)
				&& device.gemm(a, offsetA, b, offsetB, out, offsetOut, n, m, p);
	}

	/**
	 * The single-float sibling of
	 * {@link #multiply(double[], int, double[], int, double[], int, int, int, int)}, and
	 * the one the hardware is for.
	 * @param a the left operand, row-major, elements starting at {@code offsetA}
	 * @param offsetA the index of {@code a}'s first element
	 * @param b the right operand, row-major, elements starting at {@code offsetB}
	 * @param offsetB the index of {@code b}'s first element
	 * @param out the array the {@code n * p} result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param n rows of {@code a} and of the result
	 * @param m columns of {@code a} and rows of {@code b}
	 * @param p columns of {@code b} and of the result
	 * @return {@code true} when {@code out} was filled
	 */
	public static boolean multiply(float[] a, int offsetA, float[] b, int offsetB, float[] out, int offsetOut, int n,
			int m, int p) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offered(a.length, offsetA, b.length, offsetB, out.length, offsetOut, n, m, p)
				&& device.gemmF(a, offsetA, b, offsetB, out, offsetOut, n, m, p);
	}

	/**
	 * {@code out = a x b} for a STACK of {@code batch} row-major {@code n x m} by
	 * {@code m x p} double-float products, written straight into the caller's array at
	 * its own offset -- one round trip and one launch for the whole stack, because the
	 * device carries the batch on {@code blockIdx.z}.
	 *
	 * <p>
	 * {@code strideA} and {@code strideB} are per-batch ELEMENT strides, and either may
	 * be 0: that is what a BROADCAST operand passes (a rank-2 right operand under a
	 * rank-3 left one, which is every {@code torch:linear} over a {@code (B T C)}
	 * activation), and the whole batch then reads the same slab -- so only that slab is
	 * copied to the device. The result is contiguous, {@code n * p} per batch.
	 *
	 * <p>
	 * The precision contract is the unbatched one's, and it is exactly the same code: a
	 * batched cell is a per-batch
	 * {@link #multiply(double[], int, double[], int, double[], int, int, int, int)
	 * multiply} of the same slab, folding {@code k} in ascending order with a FUSED
	 * multiply-add per term.
	 * @param a the left operands, row-major, the first starting at {@code offsetA}
	 * @param offsetA the index of {@code a}'s first element
	 * @param strideA elements from one left operand to the next, or 0 to broadcast
	 * @param b the right operands, row-major, the first starting at {@code offsetB}
	 * @param offsetB the index of {@code b}'s first element
	 * @param strideB elements from one right operand to the next, or 0 to broadcast
	 * @param out the array the {@code batch * n * p} result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param batch how many products are stacked
	 * @param n rows of each left operand and of each result
	 * @param m columns of each {@code a} and rows of each {@code b}
	 * @param p columns of each {@code b} and of each result
	 * @return {@code true} when {@code out} was filled; {@code false} when this call
	 * declined, in which case {@code out} is untouched
	 */
	public static boolean multiply(double[] a, int offsetA, int strideA, double[] b, int offsetB, int strideB,
			double[] out, int offsetOut, int batch, int n, int m, int p) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offered(a.length, offsetA, strideA, b.length, offsetB, strideB, out.length, offsetOut,
				batch, n, m, p)
				&& device.gemm(a, offsetA, strideA, b, offsetB, strideB, out, offsetOut, batch, n, m, p);
	}

	/**
	 * The single-float sibling of
	 * {@link #multiply(double[], int, int, double[], int, int, double[], int, int, int, int, int)},
	 * and the one the hardware is for.
	 * @param a the left operands, row-major, the first starting at {@code offsetA}
	 * @param offsetA the index of {@code a}'s first element
	 * @param strideA elements from one left operand to the next, or 0 to broadcast
	 * @param b the right operands, row-major, the first starting at {@code offsetB}
	 * @param offsetB the index of {@code b}'s first element
	 * @param strideB elements from one right operand to the next, or 0 to broadcast
	 * @param out the array the {@code batch * n * p} result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param batch how many products are stacked
	 * @param n rows of each left operand and of each result
	 * @param m columns of each {@code a} and rows of each {@code b}
	 * @param p columns of each {@code b} and of each result
	 * @return {@code true} when {@code out} was filled
	 */
	public static boolean multiply(float[] a, int offsetA, int strideA, float[] b, int offsetB, int strideB,
			float[] out, int offsetOut, int batch, int n, int m, int p) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offered(a.length, offsetA, strideA, b.length, offsetB, strideB, out.length, offsetOut,
				batch, n, m, p)
				&& device.gemmF(a, offsetA, strideA, b, offsetB, strideB, out, offsetOut, batch, n, m, p);
	}

	/**
	 * {@code a x b} into a fresh array -- the convenience form, for a caller that wants a
	 * bare {@code n * p} result with no header of its own.
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
		if (Probe.DEVICE == null || !offered(a.length, offsetA, b.length, offsetB, (long) n * p, 0, n, m, p)) {
			return null;
		}
		double[] out = new double[n * p];
		return multiply(a, offsetA, b, offsetB, out, 0, n, m, p) ? out : null;
	}

	/**
	 * The single-float sibling of
	 * {@link #multiply(double[], int, double[], int, int, int, int)}.
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
		if (Probe.DEVICE == null || !offered(a.length, offsetA, b.length, offsetB, (long) n * p, 0, n, m, p)) {
			return null;
		}
		float[] out = new float[n * p];
		return multiply(a, offsetA, b, offsetB, out, 0, n, m, p) ? out : null;
	}

	/**
	 * Whether an element-wise map over {@code n} elements is big enough to be worth a
	 * round trip at all -- the {@link #worth(long, long, long) worth} of the element-wise
	 * tier, and the same kind of predicate: a pure size test that touches no driver, so a
	 * caller can ask it before it unwraps its operand, and {@link #map} re-asks the real
	 * question.
	 *
	 * <p>
	 * It does not take the op code. Every member {@link #map} accepts costs the CPU a
	 * libm call per element, and the spread between the cheapest of them and the dearest
	 * (measured, 5x) is far smaller than the margin at the threshold, so one number
	 * serves them all and a per-op table would be precision this measurement does not
	 * support.
	 * @param n how many elements the map covers
	 * @return {@code true} when the map is above the size threshold
	 */
	public static boolean worthMap(long n) {
		return n >= MAP_POOLED_MIN_ELEMENTS;
	}

	/**
	 * {@code out[i] = op(a[i])} for {@code n} elements of a double-float array, written
	 * straight into the caller's array at its own offset -- the ELEMENT-WISE tier.
	 *
	 * <p>
	 * {@code op} is one of the {@link #MAP_EXP} constants; anything else declines, and so
	 * does a map below the size threshold, one whose elements are not inside the arrays
	 * it was handed, and everything the {@linkplain Gpu class contract} already covers.
	 *
	 * <p>
	 * The result is NOT bit-identical to {@code java.lang.Math}'s answer for the same
	 * member, and the break is far bigger than the fused multiply-add that separates an
	 * accelerated product from a scalar one: two correctly-implemented libms simply
	 * differ in their last ulps, and the device has its own. A caller that needs identity
	 * must not offer the map. {@code .kb/gpu.md} carries the measured divergence per
	 * member and per width.
	 * @param op which member to apply, one of the {@code MAP_*} constants
	 * @param a the operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param out the array the {@code n} results are written into
	 * @param offsetOut the index in {@code out} the results start at
	 * @param n how many elements to map
	 * @return {@code true} when {@code out} was filled; {@code false} when this call
	 * declined, in which case {@code out} is untouched
	 */
	public static boolean map(int op, double[] a, int offsetA, double[] out, int offsetOut, int n) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredMap(op, a.length, offsetA, out.length, offsetOut, n)
				&& device.map(op, a, offsetA, out, offsetOut, n);
	}

	/**
	 * The single-float sibling of {@link #map(int, double[], int, double[], int, int)},
	 * and the one the hardware is for -- measured at 15-18x this width's kernel time on
	 * the machine this was tuned on.
	 * @param op which member to apply, one of the {@code MAP_*} constants
	 * @param a the operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param out the array the {@code n} results are written into
	 * @param offsetOut the index in {@code out} the results start at
	 * @param n how many elements to map
	 * @return {@code true} when {@code out} was filled
	 */
	public static boolean map(int op, float[] a, int offsetA, float[] out, int offsetOut, int n) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredMap(op, a.length, offsetA, out.length, offsetOut, n)
				&& device.mapF(op, a, offsetA, out, offsetOut, n);
	}

	/**
	 * Whether a {@linkplain #bcast broadcast binary op} or a {@linkplain #gather strided
	 * gather} over {@code n} OUTPUT elements is worth a round trip at all -- the same
	 * kind of driver-free size predicate {@link #worthMap} is, and re-asked by the calls
	 * themselves.
	 * @param n how many elements the result holds
	 * @return {@code true} when the call is above the size threshold
	 */
	public static boolean worthStrided(long n) {
		return n >= STRIDED_POOLED_MIN_ELEMENTS;
	}

	/**
	 * Whether an {@linkplain #fold axis fold} over {@code n} INPUT elements is worth a
	 * round trip at all. It counts the input, which is what the fold reads and what the
	 * copy up costs.
	 * @param n how many elements the operand holds
	 * @return {@code true} when the fold is above the size threshold
	 */
	public static boolean worthFold(long n) {
		return n >= FOLD_POOLED_MIN_ELEMENTS;
	}

	/**
	 * {@code out[i] = op(a[ia(i)], b[ib(i)])} over the whole of a BROADCAST binary
	 * element-wise op: the output is {@code dims}, row-major, and each operand's flat
	 * index follows its own per-axis stride, 0 on an axis it is stretched across. That is
	 * numpy's broadcast rule expressed as two stride vectors, and it is what an
	 * {@code (n m 1)} operand against an {@code (n m p)} one needs.
	 *
	 * <p>
	 * Every element is read WIDENED to double, the op runs in double, and only the store
	 * narrows -- so this is BIT-IDENTICAL to a scalar widen-compute-narrow walk at both
	 * widths, unlike {@link #map}. The four arithmetic ops are correctly rounded in IEEE
	 * 754 and the two selects are comparisons, so there is nothing left for a libm to
	 * disagree about.
	 * @param op which op to apply, one of the {@link #BIN_ADD} constants
	 * @param a the left operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param strideA {@code a}'s stride along each output axis, in elements
	 * @param b the right operand
	 * @param offsetB the index of {@code b}'s first element
	 * @param strideB {@code b}'s stride along each output axis, in elements
	 * @param out the array the result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param dims the output shape, outermost first
	 * @return {@code true} when {@code out} was filled; {@code false} when this call
	 * declined, in which case {@code out} is untouched
	 */
	public static boolean bcast(int op, double[] a, int offsetA, int[] strideA, double[] b, int offsetB, int[] strideB,
			double[] out, int offsetOut, int[] dims) {
		GpuDevice device = Probe.DEVICE;
		return device != null
				&& offeredBcast(op, a.length, offsetA, strideA, b.length, offsetB, strideB, out.length, offsetOut, dims)
				&& device.bcast(op, a, offsetA, strideA, b, offsetB, strideB, out, offsetOut, dims);
	}

	/**
	 * The single-float sibling of
	 * {@link #bcast(int, double[], int, int[], double[], int, int[], double[], int, int[])},
	 * and the width the hardware is for. It widens to double, computes in double and
	 * narrows on the store exactly as the double form does, which is what keeps it
	 * bit-identical to the caller's own scalar walk.
	 * @param op which op to apply, one of the {@link #BIN_ADD} constants
	 * @param a the left operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param strideA {@code a}'s stride along each output axis, in elements
	 * @param b the right operand
	 * @param offsetB the index of {@code b}'s first element
	 * @param strideB {@code b}'s stride along each output axis, in elements
	 * @param out the array the result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param dims the output shape, outermost first
	 * @return {@code true} when {@code out} was filled
	 */
	public static boolean bcast(int op, float[] a, int offsetA, int[] strideA, float[] b, int offsetB, int[] strideB,
			float[] out, int offsetOut, int[] dims) {
		GpuDevice device = Probe.DEVICE;
		return device != null
				&& offeredBcast(op, a.length, offsetA, strideA, b.length, offsetB, strideB, out.length, offsetOut, dims)
				&& device.bcastF(op, a, offsetA, strideA, b, offsetB, strideB, out, offsetOut, dims);
	}

	/**
	 * {@code out[i] = a[ia(i)]}: a pure permuted COPY, where the output is {@code dims},
	 * row-major, and the source index follows one stride per output axis. An axes
	 * transpose is exactly this with the source's own strides permuted; so is any other
	 * strided view materialization. Being a copy it is trivially bit-identical.
	 * @param a the operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param strideA {@code a}'s stride along each output axis, in elements
	 * @param out the array the result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param dims the output shape, outermost first
	 * @return {@code true} when {@code out} was filled; {@code false} when this call
	 * declined, in which case {@code out} is untouched
	 */
	public static boolean gather(double[] a, int offsetA, int[] strideA, double[] out, int offsetOut, int[] dims) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredGather(a.length, offsetA, strideA, out.length, offsetOut, dims)
				&& device.gather(a, offsetA, strideA, out, offsetOut, dims);
	}

	/**
	 * The single-float sibling of
	 * {@link #gather(double[], int, int[], double[], int, int[])}.
	 * @param a the operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param strideA {@code a}'s stride along each output axis, in elements
	 * @param out the array the result is written into
	 * @param offsetOut the index in {@code out} the result starts at
	 * @param dims the output shape, outermost first
	 * @return {@code true} when {@code out} was filled
	 */
	public static boolean gather(float[] a, int offsetA, int[] strideA, float[] out, int offsetOut, int[] dims) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredGather(a.length, offsetA, strideA, out.length, offsetOut, dims)
				&& device.gatherF(a, offsetA, strideA, out, offsetOut, dims);
	}

	/**
	 * The fold of ONE axis of a row-major array: for each of the {@code outer * inner}
	 * output cells, {@code out[o * inner + j]} folds {@code a[(o * len + k) * inner + j]}
	 * over {@code k} ASCENDING. {@code outer} is the product of the axes before the
	 * folded one and {@code inner} the product of those after it, so any rank reduces to
	 * those three numbers.
	 *
	 * <p>
	 * The accumulator is a {@code double} at BOTH widths and only the store narrows,
	 * which is the same widen-compute-narrow rule {@link #bcast} follows -- and it is
	 * deliberately NOT a parallel tree reduction: one thread walks each output cell's
	 * whole axis in order, so the sum is the caller's own sequential sum and the fold is
	 * bit-identical. That is also why a fold with too few output cells declines: it would
	 * be a single-threaded device loop.
	 * @param op which fold, one of the {@link #FOLD_SUM} constants
	 * @param a the operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param out the array the {@code outer * inner} results are written into
	 * @param offsetOut the index in {@code out} the results start at
	 * @param outer the product of the axes before the folded one
	 * @param len the folded axis's own extent
	 * @param inner the product of the axes after the folded one
	 * @return {@code true} when {@code out} was filled; {@code false} when this call
	 * declined, in which case {@code out} is untouched
	 */
	public static boolean fold(int op, double[] a, int offsetA, double[] out, int offsetOut, int outer, int len,
			int inner) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredFold(op, a.length, offsetA, out.length, offsetOut, outer, len, inner)
				&& device.fold(op, a, offsetA, out, offsetOut, outer, len, inner);
	}

	/**
	 * The single-float sibling of
	 * {@link #fold(int, double[], int, double[], int, int, int, int)}. The accumulator is
	 * still a {@code double} -- narrowing it per term would be a different sum from the
	 * caller's.
	 * @param op which fold, one of the {@link #FOLD_SUM} constants
	 * @param a the operand
	 * @param offsetA the index of {@code a}'s first element
	 * @param out the array the {@code outer * inner} results are written into
	 * @param offsetOut the index in {@code out} the results start at
	 * @param outer the product of the axes before the folded one
	 * @param len the folded axis's own extent
	 * @param inner the product of the axes after the folded one
	 * @return {@code true} when {@code out} was filled
	 */
	public static boolean fold(int op, float[] a, int offsetA, float[] out, int offsetOut, int outer, int len,
			int inner) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredFold(op, a.length, offsetA, out.length, offsetOut, outer, len, inner)
				&& device.foldF(op, a, offsetA, out, offsetOut, outer, len, inner);
	}

	/**
	 * The output element count of a strided shape, or -1 when the shape is one this
	 * library will not walk -- an empty, over-deep or over-large one.
	 */
	/**
	 * Whether a {@linkplain #rngFill generator fill} of {@code n} elements is worth a
	 * round trip at all -- the driver-free size predicate, re-asked by the call itself.
	 * @param n how many elements the fill writes
	 * @return {@code true} when the fill is above the size threshold
	 */
	public static boolean worthRng(long n) {
		return n >= Probe.RNG_MIN_ELEMENTS;
	}

	/**
	 * Fills {@code n} elements of {@code out} from the Wichmann-Hill generator in state
	 * {@code (s1, s2, s3)} -- the seeded generator behind {@code linalg:rand} /
	 * {@code randn} / {@code uniform}: {@code mode} 0 is one uniform {@code [0, 1)} draw
	 * per element, 1 the sum of twelve draws minus 6 (the Irwin-Hall normal), 2
	 * {@code lo + span * draw}. The kernel reproduces the scalar generator operation for
	 * operation and is BIT-IDENTICAL to a sequential fill at both widths: every element
	 * starts from the state the closed form {@code a^k s mod m} puts it at, then draws as
	 * the sequential walk would, and only the store into a single-float result narrows.
	 * The state the generator ENDS on is the caller's to compute, through
	 * {@link #rngAdvance}, from the same closed form.
	 *
	 * <p>
	 * Declines a mode outside 0..2, a state word outside {@code [0, 2^23)} (the range the
	 * generator keeps, and the range in which the integer arithmetic is exact), a fill
	 * below the size threshold, and one that does not fit inside {@code out}.
	 * @param out the array the draws are written into
	 * @param offsetOut the index in {@code out} the fill starts at
	 * @param n how many elements to fill
	 * @param mode which element rule, 0..2
	 * @param lo the lower bound of rule 2
	 * @param span the range of rule 2
	 * @param s1 the first state word
	 * @param s2 the second state word
	 * @param s3 the third state word
	 * @return {@code true} when {@code out} was filled; {@code false} when this call
	 * declined, in which case {@code out} is untouched
	 */
	public static boolean rngFill(double[] out, int offsetOut, int n, int mode, double lo, double span, int s1, int s2,
			int s3) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredRng(out.length, offsetOut, n, mode, s1, s2, s3)
				&& device.rngFill(out, offsetOut, n, mode, lo, span, s1, s2, s3);
	}

	/**
	 * The single-float sibling of
	 * {@link #rngFill(double[], int, int, int, double, double, int, int, int)}: the draws
	 * are computed in double and narrowed on the store, as the scalar fill does.
	 * @param out the array the draws are written into
	 * @param offsetOut the index in {@code out} the fill starts at
	 * @param n how many elements to fill
	 * @param mode which element rule, 0..2
	 * @param lo the lower bound of rule 2
	 * @param span the range of rule 2
	 * @param s1 the first state word
	 * @param s2 the second state word
	 * @param s3 the third state word
	 * @return {@code true} when {@code out} was filled
	 */
	public static boolean rngFill(float[] out, int offsetOut, int n, int mode, double lo, double span, int s1, int s2,
			int s3) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredRng(out.length, offsetOut, n, mode, s1, s2, s3)
				&& device.rngFillF(out, offsetOut, n, mode, lo, span, s1, s2, s3);
	}

	/**
	 * The state the generator is in after {@code steps} draws from {@code (s1, s2, s3)}
	 * -- the host half of {@link #rngFill}, by the same closed form the kernel uses
	 * ({@code a^k s mod m}, square-and-multiply, exact integer arithmetic), so a fill's
	 * end state is what a sequential walk would have reached. Pure arithmetic: it touches
	 * no device and is available on every machine.
	 * @param s1 the first state word
	 * @param s2 the second state word
	 * @param s3 the third state word
	 * @param steps how many draws to advance by
	 * @return the three state words after {@code steps} draws
	 */
	public static int[] rngAdvance(int s1, int s2, int s3, long steps) {
		return new int[] { (int) ((long) s1 * modPow(171, steps, 30269) % 30269),
				(int) ((long) s2 * modPow(172, steps, 30307) % 30307),
				(int) ((long) s3 * modPow(170, steps, 30323) % 30323) };
	}

	private static long modPow(long a, long e, long m) {
		long r = 1, b = a % m;
		while (e > 0) {
			if ((e & 1) != 0) {
				r = r * b % m;
			}
			b = b * b % m;
			e >>= 1;
		}
		return r;
	}

	/**
	 * Whether a {@code rows x cols} matrix-by-vector product is big enough to be worth
	 * offering at all -- the driver-free size predicate of the GEMV, re-asked by
	 * {@link #matvec} with the threshold actually in force. {@code true} is "worth
	 * unwrapping for", not "will be accepted": the call itself also asks whether the
	 * matrix is RESIDENT, which no size can tell.
	 * @param rows rows of the matrix and length of the result
	 * @param cols columns of the matrix and length of the vector
	 * @return {@code true} when the product is above the size threshold
	 */
	public static boolean worthMatvec(long rows, long cols) {
		return rows > 0 && cols > 0 && rows * cols >= MATVEC_POOLED_MIN_ELEMENTS;
	}

	/**
	 * {@code y = W x} for a row-major {@code rows x cols} double-float matrix and a
	 * {@code cols}-long vector, written straight into the caller's array at its own
	 * offset -- the GEMV behind {@code vec:matvec}, the one member of this library
	 * outside {@code linalg:} and the one whose worth is decided by RESIDENCY rather than
	 * by size.
	 *
	 * <p>
	 * A matrix-by-vector product is memory-bound: its whole cost is one pass over
	 * {@code W}, and copying {@code W} to the device costs more than the pass, so over
	 * the link the device loses to the CPU up to ~2^19 elements. Over a matrix that is
	 * ALREADY on the device -- a model's weight, which is read every step and never
	 * written -- the call is the ~9 us floor plus a read at the device's own bandwidth,
	 * and the CPU loses from 2^17 elements up. So this member is accepted only when its
	 * matrix is resident, or when the same matrix has been offered before and not written
	 * since -- in which case this call uploads it and every later call finds it there.
	 * The first offer of a matrix declines and costs nothing; a matrix the program
	 * rewrites between calls is never uploaded and never pays for the trip it would lose.
	 * Every other operand and result follows the ordinary residency rule. On a device
	 * that keeps no resident copies (Metal, today) the member declines outright.
	 *
	 * <p>
	 * The accumulator is a {@code double} at both widths and only the store narrows,
	 * which is the scalar defun's rule: at {@code #f} the product of two elements is
	 * exact in it, so the result differs from the defun only where the ORDER of a double
	 * sum crosses a single-float rounding boundary (measured: never, over 1024 rows of
	 * 768); at {@code #d} the fused multiply-add and the warp's tree are the product's
	 * own few-ulp story. Neither is asserted as byte-identity.
	 * @param w the matrix, row-major, elements starting at {@code offsetW}
	 * @param offsetW the index of {@code w}'s first element
	 * @param x the vector, elements starting at {@code offsetX}
	 * @param offsetX the index of {@code x}'s first element
	 * @param y the array the {@code rows} results are written into
	 * @param offsetY the index in {@code y} the results start at
	 * @param rows rows of {@code w} and length of the result
	 * @param cols columns of {@code w} and length of {@code x}
	 * @return {@code true} when {@code y} was filled; {@code false} when this call
	 * declined, in which case {@code y} is untouched
	 */
	public static boolean matvec(double[] w, int offsetW, double[] x, int offsetX, double[] y, int offsetY, int rows,
			int cols) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredMatvec(w.length, offsetW, x.length, offsetX, y.length, offsetY, rows, cols)
				&& device.gemv(w, offsetW, x, offsetX, y, offsetY, rows, cols);
	}

	/**
	 * The single-float sibling of
	 * {@link #matvec(double[], int, double[], int, double[], int, int, int)}, and the
	 * width a decode loop runs at. The accumulator is still a {@code double}.
	 * @param w the matrix, row-major, elements starting at {@code offsetW}
	 * @param offsetW the index of {@code w}'s first element
	 * @param x the vector, elements starting at {@code offsetX}
	 * @param offsetX the index of {@code x}'s first element
	 * @param y the array the {@code rows} results are written into
	 * @param offsetY the index in {@code y} the results start at
	 * @param rows rows of {@code w} and length of the result
	 * @param cols columns of {@code w} and length of {@code x}
	 * @return {@code true} when {@code y} was filled
	 */
	public static boolean matvec(float[] w, int offsetW, float[] x, int offsetX, float[] y, int offsetY, int rows,
			int cols) {
		GpuDevice device = Probe.DEVICE;
		return device != null && offeredMatvec(w.length, offsetW, x.length, offsetX, y.length, offsetY, rows, cols)
				&& device.gemvF(w, offsetW, x, offsetX, y, offsetY, rows, cols);
	}

	/**
	 * Whether a GEMV is one this library will attempt: above the threshold in force, a
	 * matrix that is one Java array, and all three operands inside the arrays they were
	 * handed. Whether the matrix is resident is the device's question, not this one's.
	 */
	private static boolean offeredMatvec(long lengthW, int offsetW, long lengthX, int offsetX, long lengthY,
			int offsetY, int rows, int cols) {
		return rows > 0 && cols > 0 && (long) rows * cols >= Probe.MATVEC_MIN_ELEMENTS
				&& (long) rows * cols <= Integer.MAX_VALUE && offsetW >= 0 && offsetX >= 0 && offsetY >= 0
				&& offsetW + (long) rows * cols <= lengthW && offsetX + (long) cols <= lengthX
				&& offsetY + (long) rows <= lengthY;
	}

	/**
	 * The range the generator keeps its words in, and in which {@code a * s} is exact.
	 */
	private static final int RNG_STATE_LIMIT = 1 << 23;

	private static boolean offeredRng(long lengthOut, int offsetOut, int n, int mode, int s1, int s2, int s3) {
		return mode >= 0 && mode <= 2 && n > 0 && n >= Probe.RNG_MIN_ELEMENTS && offsetOut >= 0
				&& (long) offsetOut + n <= lengthOut && s1 >= 0 && s1 < RNG_STATE_LIMIT && s2 >= 0
				&& s2 < RNG_STATE_LIMIT && s3 >= 0 && s3 < RNG_STATE_LIMIT;
	}

	private static long stridedCount(int[] dims) {
		if (dims.length < 1 || dims.length > MAX_STRIDED_RANK) {
			return -1;
		}
		long total = 1;
		for (int d : dims) {
			if (d < 1) {
				return -1;
			}
			total *= d;
			if (total > Integer.MAX_VALUE) {
				return -1;
			}
		}
		return total;
	}

	/**
	 * The highest element index a stride vector can reach over {@code dims}, or -1 when
	 * one of the strides is negative. The kernel indexes freely, so this is what bounds
	 * it inside the caller's array.
	 */
	private static long stridedSpan(int[] dims, int[] stride) {
		if (stride.length != dims.length) {
			return -1;
		}
		long span = 0;
		for (int k = 0; k < dims.length; k++) {
			if (stride[k] < 0) {
				return -1;
			}
			span += (long) (dims[k] - 1) * stride[k];
		}
		return span;
	}

	/**
	 * Whether a broadcast binary op is one this library will attempt: an op it names, a
	 * shape it will walk, above the threshold in force on this machine, and with both
	 * operands' whole reachable span inside the arrays it was handed.
	 */
	private static boolean offeredBcast(int op, long lengthA, int offsetA, int[] strideA, long lengthB, int offsetB,
			int[] strideB, long lengthOut, int offsetOut, int[] dims) {
		long total = stridedCount(dims);
		if (op < 0 || op >= BIN_OPS || total < 0 || total < Probe.STRIDED_MIN_ELEMENTS) {
			return false;
		}
		long spanA = stridedSpan(dims, strideA);
		long spanB = stridedSpan(dims, strideB);
		return spanA >= 0 && spanB >= 0 && offsetA >= 0 && offsetB >= 0 && offsetOut >= 0 && offsetA + spanA < lengthA
				&& offsetB + spanB < lengthB && offsetOut + total <= lengthOut;
	}

	/** {@link #offeredBcast} for the one-operand form. */
	private static boolean offeredGather(long lengthA, int offsetA, int[] strideA, long lengthOut, int offsetOut,
			int[] dims) {
		long total = stridedCount(dims);
		if (total < 0 || total < Probe.STRIDED_MIN_ELEMENTS) {
			return false;
		}
		long spanA = stridedSpan(dims, strideA);
		return spanA >= 0 && offsetA >= 0 && offsetOut >= 0 && offsetA + spanA < lengthA
				&& offsetOut + total <= lengthOut;
	}

	/**
	 * Whether an axis fold is one this library will attempt: an op it names, enough
	 * output cells to be worth a grid, above the threshold in force on this machine, and
	 * inside both arrays.
	 */
	private static boolean offeredFold(int op, long lengthA, int offsetA, long lengthOut, int offsetOut, int outer,
			int len, int inner) {
		if (op < 0 || op >= FOLD_OPS || outer < 1 || len < 1 || inner < 1) {
			return false;
		}
		long cells = (long) outer * inner;
		long total = cells * len;
		return cells >= FOLD_MIN_CELLS && cells <= Integer.MAX_VALUE && total <= Integer.MAX_VALUE
				&& total >= Probe.FOLD_MIN_ELEMENTS && offsetA >= 0 && offsetOut >= 0 && offsetA + total <= lengthA
				&& offsetOut + cells <= lengthOut;
	}

	/**
	 * Whether an element-wise map is one this library will attempt: a member it names, at
	 * or above the threshold actually in force on this machine, and actually present in
	 * the two arrays it was handed.
	 */
	private static boolean offeredMap(int op, long lengthA, int offsetA, long lengthOut, int offsetOut, int n) {
		return op >= 0 && op < MAP_OPS && n > 0 && n >= Probe.MAP_MIN_ELEMENTS && offsetA >= 0 && offsetOut >= 0
				&& (long) offsetA + n <= lengthA && (long) offsetOut + n <= lengthOut;
	}

	/**
	 * Whether a product is one this library will attempt: big enough for the threshold
	 * actually in force on this machine, launchable in one grid, and actually present in
	 * the three arrays it was handed. A caller that gets its own offsets wrong is
	 * declined rather than signalled -- the point of the whole mechanism is that it never
	 * changes what a program computes.
	 */
	private static boolean offered(long lengthA, int offsetA, long lengthB, int offsetB, long lengthOut, int offsetOut,
			int n, int m, int p) {
		return offered(lengthA, offsetA, 0, lengthB, offsetB, 0, lengthOut, offsetOut, 1, n, m, p);
	}

	/**
	 * The same for a STACK: every matrix has to be launchable, the whole stack has to
	 * clear the threshold, and each operand's SPAN -- the last slab plus everything its
	 * stride skipped on the way there, so a 0 stride spans one slab however long the
	 * batch -- has to be inside the array it was handed.
	 *
	 * <p>
	 * The launch bound asked here is the CUDA one on BOTH backends. Metal's grid axes are
	 * 32-bit where CUDA's row axis is 16, so the CUDA bound is the stricter of the two
	 * and a shape it refuses is one no backend has ever been offered -- one predicate is
	 * worth more than the handful of enormous stacks the looser bound would add on one
	 * platform.
	 */
	private static boolean offered(long lengthA, int offsetA, int strideA, long lengthB, int offsetB, int strideB,
			long lengthOut, int offsetOut, int batch, int n, int m, int p) {
		return batch > 0 && n > 0 && m > 0 && p > 0 && (long) batch * n * m * p >= Probe.MIN_WORK
				&& CudaGemm.launchable(batch, n, m, p) && offsetA >= 0 && offsetB >= 0 && offsetOut >= 0 && strideA >= 0
				&& strideB >= 0 && (long) offsetA + (long) (batch - 1) * strideA + (long) n * m <= lengthA
				&& (long) offsetB + (long) (batch - 1) * strideB + (long) m * p <= lengthB
				&& (long) offsetOut + (long) batch * n * p <= lengthOut;
	}

}
