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
 * Everything. No NVIDIA driver, no device, a card older than the PTX this library
 * carries, a JVM that forbids native access, a platform with no {@code libcuda.so.1} (so
 * far, anything but Linux), a shape too big for the launch grid, a product too small to
 * be worth a round trip, one too big for free device memory, a failed {@code CUresult} of
 * any kind, or a context that a previous failure left unusable. All of them answer
 * quietly, and the caller runs whatever it would have run anyway. This is the same
 * posture {@code --simd} has toward a JDK without {@code jdk.incubator.vector} and
 * {@code --blas} has toward a machine with no tuned CBLAS ({@code .kb/linalg-simd.md},
 * {@code .kb/linalg-blas.md}).
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
	private static final long POOLED_MIN_WORK = 1L << 17;

	/**
	 * The threshold {@link #multiply} applies instead on a machine whose driver has no
	 * usable stream-ordered allocator. Allocating the three buffers per call raises the
	 * floor from 15 us to 170, which moves the crossover against the same CPU column to
	 * between n=96 (131 us) and n=128 (384 us). {@link #worth} does NOT apply it, and
	 * says why.
	 */
	private static final long UNPOOLED_MIN_WORK = 1L << 21;

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
	private static final long MAP_POOLED_MIN_ELEMENTS = 1L << 14;

	/**
	 * The element-wise threshold on a machine whose driver has no usable stream-ordered
	 * allocator, where the per-call floor is ~170 us rather than ~15. That floor is above
	 * the CPU's cost for 16384 elements of any member here, so the threshold moves up to
	 * where the CPU column passes it: 65536 elements, where the cheapest member measures
	 * 360 us on the CPU.
	 */
	private static final long MAP_UNPOOLED_MIN_ELEMENTS = 1L << 16;

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

		private static final @Nullable CudaGemm DEVICE;

		private static final String DESCRIPTION;

		private static final long MIN_WORK;

		private static final long MAP_MIN_ELEMENTS;

		static {
			CudaGemm device;
			String description;
			try {
				CudaGemm.Probe probe = CudaGemm.probe();
				device = probe.gemm();
				description = probe.description();
			}
			catch (Throwable ex) {
				// The probe is written not to throw; if it ever does, the answer is still
				// no. The nested try is not paranoia for its own sake: an exception
				// escaping HERE would fail this class's initialization, and every later
				// call would get a NoClassDefFoundError instead of a decline, for the
				// life of the process.
				device = null;
				description = "the CUDA driver could not be probed";
				try {
					description = description + ": " + ex;
				}
				catch (Throwable nested) {
					description = description + ": " + ex.getClass().getName();
				}
			}
			DEVICE = device;
			DESCRIPTION = description;
			MIN_WORK = device == null || device.pooled() ? POOLED_MIN_WORK : UNPOOLED_MIN_WORK;
			MAP_MIN_ELEMENTS = device == null || device.pooled() ? MAP_POOLED_MIN_ELEMENTS : MAP_UNPOOLED_MIN_ELEMENTS;
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
		CudaGemm device = Probe.DEVICE;
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
	 * The one device this process has probed, or {@code null}. Package-private and for
	 * the tests: probing again would retain a second reference to the primary context and
	 * JIT the module a second time, so nothing may call {@code CudaGemm.probe()} twice.
	 * @return the probed device, or {@code null} when there is none
	 */
	static @Nullable CudaGemm device() {
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
		CudaGemm device = Probe.DEVICE;
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
		CudaGemm device = Probe.DEVICE;
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
		CudaGemm device = Probe.DEVICE;
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
		CudaGemm device = Probe.DEVICE;
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
		CudaGemm device = Probe.DEVICE;
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
		CudaGemm device = Probe.DEVICE;
		return device != null && offeredMap(op, a.length, offsetA, out.length, offsetOut, n)
				&& device.mapF(op, a, offsetA, out, offsetOut, n);
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
