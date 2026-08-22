package am.ik.gpu;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The half of {@code am.ik.gpu} that needs a GPU on the machine, and therefore runs only
 * where there is one -- the same answer the library itself gives. {@link GpuDeclineTest}
 * covers what every machine must do; nothing here is allowed to be a requirement.
 *
 * <p>
 * Five things are pinned. (1) The CHECKED-IN PTX loads on this device and computes the
 * reference values: it is a generated artifact with no other test of its validity, and a
 * regeneration that silently produced the wrong kernel would pass every decline test. (2)
 * The products agree with a scalar Java reference -- exactly, on inputs that are exact at
 * the operand width, and to a tight relative tolerance on inputs that are not, which is
 * the precision contract. (3) BOTH allocator routes compute, because the fallback one is
 * unreachable on a machine whose driver has a memory pool and is otherwise never executed
 * anywhere. (4) Every decline condition still declines with a device present, so the size
 * threshold and the bounds checks are not accidentally bypassed by having hardware. (5)
 * Neither a run of successful products NOR a run of failing ones moves free device
 * memory, which is the leak assertion -- and the failing half is the one that was wrong
 * first, so it is asserted rather than assumed.
 *
 * <h2>Every shape here is sized off {@link Gpu#minWork()}</h2>
 *
 * The size threshold is a property of the MACHINE, not of the build: a driver without a
 * usable stream-ordered allocator has an eleven-times-higher floor and declines up to 16x
 * further up. A test that hard-codes a shape chosen for one of those thresholds turns the
 * whole class red on a machine with the other, and reads as a kernel regression when it
 * is nothing of the sort.
 */
@EnabledIf("am.ik.gpu.GpuTest#aDoubleCapableGpuIsAvailable")
class GpuTest {

	/**
	 * A device that has a {@code double}, which means the CUDA backend and not merely "a
	 * GPU". Every assertion below is written at {@code #d} -- the oracle, the offsets,
	 * the leak runs, the batch shapes -- because that was the only backend there was; the
	 * Metal one has no {@code double} at all and answers the same claims at {@code #f} in
	 * {@link MetalGpuTest}. Splitting them beat widening this file: the two devices do
	 * not have the same member set, the same thresholds or the same precision story, so a
	 * single width-generic suite would have had to branch on the backend in nearly every
	 * test.
	 * @return {@code true} when a double-capable device is present
	 */
	static boolean aDoubleCapableGpuIsAvailable() {
		GpuDevice device = Gpu.device();
		return Gpu.available() && device != null && device.supportsDouble();
	}

	/**
	 * The four tests that ASSERT ON FREE DEVICE MEMORY hold this, so they never overlap
	 * each other. {@code cuMemGetInfo} is a property of the device rather than of the
	 * thread: two leak tests running at once each see the other's pool churn as their own
	 * drift, and with the strided tier's four-buffer path in the set that drift reached
	 * 800 MB against a 256 MB bound. Serializing them is the fix; widening the bound
	 * would throw away what the assertion is for. (The bound is still loose, because this
	 * suite also runs beside a SECOND surefire fork whose compiled classes each define
	 * their own copy of the binding -- see {@code .kb/gpu.md}.)
	 */
	private static final String DEVICE_MEMORY = "am.ik.gpu.device-memory";

	/**
	 * How far free device memory may drift across a leak test before it is called a leak.
	 * It is deliberately LOOSE, and it had to be widened twice: {@code cuMemGetInfo}
	 * reports the whole DEVICE, and the rest of the suite runs beside this one -- the JVM
	 * backend's fork loads a separate copy of this binding, with its own primary context
	 * and its own PTX module, for every compiled {@code --gpu} class it defines, and
	 * there are now enough of those to move free memory by ~800 MB on their own. Every
	 * leak test below is sized so that a real leak is 2-8x this, so widening the bound
	 * costs the assertion nothing.
	 */
	private static final long DRIFT_BOUND = 1536L << 20;

	/**
	 * The smallest square product this machine will actually accept, times a safety
	 * factor so that a shape derived from it is not sitting on the threshold.
	 */
	private static int square() {
		int n = (int) Math.ceil(Math.cbrt((double) Gpu.minWork()));
		return Math.max(64, roundUpToTile(n + n / 4));
	}

	private static int roundUpToTile(int n) {
		return (n + 15) / 16 * 16;
	}

	@Test
	void theCheckedInPtxLoadsAndTheKernelComputes() {
		assertThat(Gpu.description()).contains("sm_");
		int n = square();
		double[] a = new double[n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
			b[i] = (i % 5) - 2;
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		// Small integers through the reduction are exact at both widths, so the tiled
		// kernel's reordering is invisible and this is an EQUALITY.
		assertThat(c).containsExactly(reference(a, 0, b, 0, n, n, n));
	}

	@Test
	void theSingleFloatKernelComputesTheSameExactValues() {
		int n = square();
		float[] a = new float[n * n], b = new float[n * n];
		double[] ad = new double[n * n], bd = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			ad[i] = (i % 7) - 3;
			bd[i] = (i % 5) - 2;
			a[i] = (float) ad[i];
			b[i] = (float) bd[i];
		}
		float[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		double[] expected = reference(ad, 0, bd, 0, n, n, n);
		for (int i = 0; i < c.length; i++) {
			assertThat((double) c[i]).isEqualTo(expected[i]);
		}
	}

	@Test
	void bothAllocatorRoutesComputeTheSameProduct() {
		// cuMemAllocAsync is 0.7 us a pair and cuMemAlloc is 126, which is why the pooled
		// route exists -- but a driver older than CUDA 11.2, or a device without memory
		// pools, takes the other one, and on this machine it would otherwise never run at
		// all. Same shapes, same answers, and the threshold in force is the pooled one
		// either way because the switch is below Gpu. The cast is the one place this file
		// names the backend: the allocator route is the CUDA driver's, and there is no
		// second route on Metal to compare against.
		CudaGemm gemm = (CudaGemm) Gpu.device();
		assertThat(gemm).isNotNull();
		int n = square();
		double[] a = new double[n * n], b = new double[n * n];
		float[] af = new float[n * n], bf = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
			b[i] = (i % 5) - 2;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		double[] asProbed = new double[n * n], flipped = new double[n * n];
		float[] asProbedF = new float[n * n], flippedF = new float[n * n];
		boolean probed = gemm.pooled();
		assertThat(gemm.gemm(a, 0, b, 0, asProbed, 0, n, n, n)).isTrue();
		assertThat(gemm.gemmF(af, 0, bf, 0, asProbedF, 0, n, n, n)).isTrue();
		boolean previous = gemm.setPooledAllocation(!probed);
		try {
			assertThat(previous).isEqualTo(probed);
			// False either way, and for two different reasons: a machine whose driver HAS
			// a pool has just been switched off it, and one whose driver has none cannot
			// be switched ONTO it. The second runs the same route twice, which is still
			// the right assertion for that machine.
			assertThat(gemm.pooled()).isFalse();
			assertThat(gemm.gemm(a, 0, b, 0, flipped, 0, n, n, n)).isTrue();
			assertThat(gemm.gemmF(af, 0, bf, 0, flippedF, 0, n, n, n)).isTrue();
		}
		finally {
			gemm.setPooledAllocation(previous);
		}
		assertThat(gemm.pooled()).isEqualTo(probed);
		assertThat(flipped).containsExactly(asProbed);
		assertThat(flippedF).containsExactly(asProbedF);
		assertThat(asProbed).containsExactly(reference(a, 0, b, 0, n, n, n));
	}

	@Test
	void anInexactProductAgreesWithTheScalarOracleToTheWidthsOwnTolerance() {
		// The tiled walk reorders the reduction, so this is a tolerance and not an
		// equality -- and the tolerance is the WIDTH's, not the device's.
		int n = 2 * square();
		Random random = new Random(20260820L);
		double[] a = new double[n * n], b = new double[n * n];
		float[] af = new float[n * n], bf = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = random.nextDouble() - 0.5;
			b[i] = random.nextDouble() - 0.5;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		double[] expected = reference(a, 0, b, 0, n, n, n);
		double scale = 0;
		for (double value : expected) {
			scale = Math.max(scale, Math.abs(value));
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		float[] cf = Gpu.multiply(af, 0, bf, 0, n, n, n);
		assertThat(c).isNotNull();
		assertThat(cf).isNotNull();
		double worst64 = 0, worst32 = 0;
		for (int i = 0; i < expected.length; i++) {
			worst64 = Math.max(worst64, Math.abs(c[i] - expected[i]) / scale);
			worst32 = Math.max(worst32, Math.abs(cf[i] - expected[i]) / scale);
		}
		assertThat(worst64).isLessThan(1e-14);
		assertThat(worst32).isLessThan(1e-6);
	}

	@Test
	void everyOperandIncludingTheResultIsReadFromItsOwnOffset() {
		// The compiled backends keep a [rank, dim..., data...] header inside the same
		// array as the data, so an intercepted product hands over an offset per operand
		// AND writes into the array the caller has already shaped.
		int n = square();
		int headerA = 3, headerB = 7, headerOut = 4;
		double[] a = new double[headerA + n * n], b = new double[headerB + n * n];
		double[] out = new double[headerOut + n * n];
		for (int i = 0; i < headerA; i++) {
			a[i] = Double.NaN;
		}
		for (int i = 0; i < headerB; i++) {
			b[i] = Double.NaN;
		}
		for (int i = 0; i < headerOut; i++) {
			out[i] = i + 0.5;
		}
		for (int i = 0; i < n * n; i++) {
			a[headerA + i] = (i % 7) - 3;
			b[headerB + i] = (i % 5) - 2;
		}
		assertThat(Gpu.multiply(a, headerA, b, headerB, out, headerOut, n, n, n)).isTrue();
		double[] expected = reference(a, headerA, b, headerB, n, n, n);
		// A NaN anywhere in the result would mean an operand's header was read as data.
		for (int i = 0; i < n * n; i++) {
			assertThat(out[headerOut + i]).isEqualTo(expected[i]);
		}
		// And the result's own header is where the caller left it.
		for (int i = 0; i < headerOut; i++) {
			assertThat(out[i]).isEqualTo(i + 0.5);
		}
	}

	@Test
	void aRectangularProductUsesAllThreeDimensions() {
		// n, m and p distinct, none a multiple of the 16x16 tile so the kernel's bounds
		// guards are exercised rather than its happy path, and all three above whatever
		// threshold is in force on this machine.
		int base = square();
		int n = base + 6, m = base - 11, p = base + 37;
		double[] a = new double[n * m], b = new double[m * p];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 9) - 4;
		}
		for (int i = 0; i < b.length; i++) {
			b[i] = (i % 6) - 2;
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, m, p);
		assertThat(c).isNotNull().hasSize(n * p);
		assertThat(c).containsExactly(reference(a, 0, b, 0, n, m, p));
	}

	@Test
	void anOperandTooBigForOneCriticalCopyIsSplitAndStillAgrees() {
		// Past the critical chunk size a copy is issued in several pieces, and the kernel
		// is awaited outside them; the split must not move a byte. 3072x3072 doubles is
		// 72 MB an operand, over the 64 MB chunk.
		int n = 3072;
		double[] a = new double[n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = i % 3;
			b[i] = i % 2;
		}
		double[] c = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(c).isNotNull().hasSize(n * n);
		// Spot-check one row rather than the whole 9 M cells: the reference is a triple
		// loop and the point here is the transfer route, not the arithmetic. Every value
		// is a small integer, so the reordered reduction is still exact.
		for (int j = 0; j < 8; j++) {
			double expected = 0;
			for (int k = 0; k < n; k++) {
				expected += a[k] * b[k * n + j];
			}
			assertThat(c[j]).isEqualTo(expected);
		}
	}

	@Test
	void everyDeclineConditionStillDeclinesWithADevicePresent() {
		int n = square();
		double[] a = new double[n * n], b = new double[n * n], out = new double[n * n];
		assertThat(Gpu.worth(8, 8, 8)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, 8, 8, 8)).isNull();
		assertThat(Gpu.multiply(a, 0, b, 0, 4 * n, 4 * n, 4 * n)).isNull();
		assertThat(Gpu.multiply(a, 1, b, 0, n, n, n)).isNull();
		assertThat(Gpu.multiply(a, 0, b, 0, n, n, 0)).isNull();
		// The out-taking form declines on the same conditions, plus its own.
		assertThat(Gpu.multiply(a, 0, b, 0, out, 1, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, out, -1, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, new double[n], 0, n, n, n)).isFalse();
	}

	@Test
	void everyElementWiseMemberComputesItsOwnFunction() {
		// The op code is a PARAMETER of one kernel, so a mis-numbered constant would
		// silently compute a different member -- and every value would still look
		// plausible. Each op is checked against java.lang.Math over a domain the member
		// is defined on, which is the only assertion that catches that.
		int n = (int) Gpu.mapMinElements() * 2;
		int[] ops = { Gpu.MAP_EXP, Gpu.MAP_LOG, Gpu.MAP_TANH, Gpu.MAP_SIN, Gpu.MAP_COS, Gpu.MAP_TAN, Gpu.MAP_ASIN,
				Gpu.MAP_ACOS, Gpu.MAP_ATAN, Gpu.MAP_SINH, Gpu.MAP_COSH, Gpu.MAP_ERF };
		assertThat(ops).hasSize(Gpu.MAP_OPS);
		for (int op : ops) {
			double[] a = new double[n], out = new double[n];
			float[] af = new float[n], outF = new float[n];
			for (int i = 0; i < n; i++) {
				a[i] = domain(op, i, n);
				af[i] = (float) a[i];
			}
			assertThat(Gpu.map(op, a, 0, out, 0, n)).as("op %d", op).isTrue();
			assertThat(Gpu.map(op, af, 0, outF, 0, n)).as("op %d f32", op).isTrue();
			for (int i = 0; i < n; i += 97) {
				double expected = scalar(op, a[i]);
				// Two libms differ in their last ulps and the device has its own; the
				// contract is a relative tolerance, not equality, and this is where the
				// number in .kb/gpu.md's precision table is defended. Measured worst
				// case on the machine this was written on: 1.0e-15 at f64 (erf, whose
				// rontolisp oracle is a series rather than a correctly-rounded erf) and
				// 1.7e-7 at f32, where the device evaluates AT the operand width.
				assertThat(Math.abs(out[i] - expected) / Math.abs(expected)).as("op %d at %f", op, a[i])
					.isLessThan(1e-12);
				assertThat(Math.abs(outF[i] - expected) / Math.abs(expected)).as("op %d at %f f32", op, a[i])
					.isLessThan(1e-5);
			}
		}
	}

	/** An input the given member is defined on, spread over its interesting range. */
	private static double domain(int op, int i, int n) {
		double t = (i + 0.5) / n;
		if (op == Gpu.MAP_LOG) {
			return 0.01 + t * 100;
		}
		if (op == Gpu.MAP_ASIN || op == Gpu.MAP_ACOS) {
			return -0.9 + 1.8 * t;
		}
		if (op == Gpu.MAP_TAN) {
			return -1.4 + 2.8 * t;
		}
		return -3.0 + 6.0 * t;
	}

	private static double scalar(int op, double x) {
		if (op == Gpu.MAP_EXP) {
			return Math.exp(x);
		}
		if (op == Gpu.MAP_LOG) {
			return Math.log(x);
		}
		if (op == Gpu.MAP_TANH) {
			return Math.tanh(x);
		}
		if (op == Gpu.MAP_SIN) {
			return Math.sin(x);
		}
		if (op == Gpu.MAP_COS) {
			return Math.cos(x);
		}
		if (op == Gpu.MAP_TAN) {
			return Math.tan(x);
		}
		if (op == Gpu.MAP_ASIN) {
			return Math.asin(x);
		}
		if (op == Gpu.MAP_ACOS) {
			return Math.acos(x);
		}
		if (op == Gpu.MAP_ATAN) {
			return Math.atan(x);
		}
		if (op == Gpu.MAP_SINH) {
			return Math.sinh(x);
		}
		if (op == Gpu.MAP_COSH) {
			return Math.cosh(x);
		}
		return erf(x);
	}

	/**
	 * {@code linalg.lisp}'s own A&S 7.1.6 series, which is the oracle {@code erf} has.
	 */
	private static double erf(double x) {
		double ax = Math.abs(x);
		if (ax >= 6) {
			return Math.signum(x);
		}
		double term = 1, total = 1;
		for (int k = 1; k <= 200; k++) {
			term = term * 2 * ax * ax / (2 * k + 1);
			total += term;
			if (term < 1e-17 * total) {
				break;
			}
		}
		return Math.signum(x) * 1.1283791670955126 * ax * Math.exp(-ax * ax) * total;
	}

	@Test
	void anElementWiseMapReadsAndWritesFromItsOwnOffset() {
		// The compiled backend's arrays carry a [rank, dim..., data...] header, so a map
		// is handed an offset on the operand AND on the destination -- and the
		// destination's header must survive, since the result keeps the operand's shape.
		int n = (int) Gpu.mapMinElements() * 2;
		double[] a = new double[n + 3], out = new double[n + 3];
		for (int i = 0; i < n; i++) {
			a[3 + i] = 0.5 + i * 0.001;
		}
		out[0] = 2;
		out[1] = 7;
		out[2] = 11;
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 3, out, 3, n)).isTrue();
		assertThat(out[0]).isEqualTo(2);
		assertThat(out[1]).isEqualTo(7);
		assertThat(out[2]).isEqualTo(11);
		for (int i = 0; i < n; i += 89) {
			assertThat(out[3 + i]).isCloseTo(Math.exp(a[3 + i]), within(1e-12 * Math.exp(a[3 + i])));
		}
	}

	@Test
	void everyElementWiseDeclineConditionStillDeclinesWithADevicePresent() {
		int n = (int) Gpu.mapMinElements() * 2;
		double[] a = new double[n], out = new double[n];
		assertThat(Gpu.worthMap(8)).isFalse();
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, out, 0, 8)).isFalse();
		assertThat(Gpu.map(Gpu.MAP_OPS, a, 0, out, 0, n)).isFalse();
		assertThat(Gpu.map(-1, a, 0, out, 0, n)).isFalse();
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 1, out, 0, n)).isFalse();
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, out, 1, n)).isFalse();
		assertThat(out).containsOnly(0.0);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aRunOfElementWiseMapsFreesEveryBufferItAllocates() {
		// The map path allocates TWO buffers rather than three and frees them in its own
		// finally; the product's leak test cannot cover it.
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		int n = 1 << 19;
		double[] a = new double[n], c = new double[n];
		assertThat(gemm.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		}
		// 1000 maps of two 4 MB buffers leak 8 GB if they leak at all. See the bound's
		// reasoning under aRunOfSuccessfulProductsFreesEveryBufferItAllocates.
		assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(DRIFT_BOUND);
	}

	@Test
	void aBroadcastBinaryOpMatchesTheScalarOdometerWalk() {
		// Every op, both widths, over a (rows x cols) output against a (rows x 1) operand
		// -- the shape torch:softmax and torch:layer-norm produce, and the one the CPU
		// walks with an odometer. INEXACT data, because the whole claim is that the
		// device
		// is bit-identical here rather than merely close: it widens to double, computes
		// in
		// double and narrows on the store, which is %la-bcast-loop's own rule.
		int cols = 64;
		int rows = (int) Math.max(Gpu.stridedMinElements() / cols * 2, 512);
		int n = rows * cols;
		Random random = new Random(4242);
		double[] x = new double[n], y = new double[rows], out = new double[n];
		float[] xf = new float[n], yf = new float[n == 0 ? 0 : rows], outf = new float[n];
		for (int i = 0; i < n; i++) {
			x[i] = random.nextDouble() * 4 - 2;
			xf[i] = (float) x[i];
		}
		for (int i = 0; i < rows; i++) {
			y[i] = random.nextDouble() * 4 - 2 + 3;
			yf[i] = (float) y[i];
		}
		int[] dims = { rows, cols };
		int[] sx = { cols, 1 };
		int[] sy = { 1, 0 };
		for (int op = 0; op < Gpu.BIN_OPS; op++) {
			assertThat(Gpu.bcast(op, x, 0, sx, y, 0, sy, out, 0, dims)).isTrue();
			assertThat(Gpu.bcast(op, xf, 0, sx, yf, 0, sy, outf, 0, dims)).isTrue();
			for (int r = 0; r < rows; r++) {
				for (int c = 0; c < cols; c++) {
					double expected = binary(op, x[r * cols + c], y[r]);
					assertThat(out[r * cols + c]).isEqualTo(expected);
					assertThat(outf[r * cols + c]).isEqualTo((float) binary(op, xf[r * cols + c], yf[r]));
				}
			}
		}
	}

	/**
	 * The oracle {@code bin_op} mirrors: the strict selects put the SECOND operand first.
	 */
	private static double binary(int op, double a, double b) {
		return switch (op) {
			case Gpu.BIN_ADD -> a + b;
			case Gpu.BIN_SUB -> a - b;
			case Gpu.BIN_MUL -> a * b;
			case Gpu.BIN_DIV -> a / b;
			case Gpu.BIN_MAX -> a > b ? a : b;
			default -> a < b ? a : b;
		};
	}

	@Test
	void aStridedGatherIsThePermutedCopy() {
		// A rank-3 axes transpose (0 2 1), which is what every attention head asks for.
		int d0 = 4, d1 = 64;
		int d2 = (int) Math.max(Gpu.stridedMinElements() / (d0 * d1) * 2, 64);
		int n = d0 * d1 * d2;
		Random random = new Random(99);
		double[] a = new double[n], out = new double[n];
		float[] af = new float[n], outf = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = random.nextDouble();
			af[i] = (float) a[i];
		}
		int[] od = { d0, d2, d1 };
		int[] strides = { d1 * d2, 1, d2 };
		assertThat(Gpu.gather(a, 0, strides, out, 0, od)).isTrue();
		assertThat(Gpu.gather(af, 0, strides, outf, 0, od)).isTrue();
		for (int i = 0; i < d0; i++) {
			for (int j = 0; j < d2; j++) {
				for (int k = 0; k < d1; k++) {
					double expected = a[i * d1 * d2 + k * d2 + j];
					assertThat(out[(i * d2 + j) * d1 + k]).isEqualTo(expected);
					assertThat(outf[(i * d2 + j) * d1 + k]).isEqualTo((float) expected);
				}
			}
		}
	}

	@Test
	void anAxisFoldIsTheDefunsOwnSequentialFold() {
		// outer x len x inner, folded over the middle axis. The accumulator is a double
		// at
		// BOTH widths and the walk is ascending, so this is bit-identical to a scalar
		// fold
		// -- asserted over inexact data, and asserted for the two seeds separately: sum
		// starts from 0, amax/amin from the first element with a STRICT comparison, so
		// the
		// accumulator wins a tie.
		int outer = 8, inner = 32;
		int len = (int) Math.max(Gpu.foldMinElements() / (outer * inner) * 2, 16);
		int n = outer * len * inner, cells = outer * inner;
		Random random = new Random(7);
		double[] a = new double[n], out = new double[cells];
		float[] af = new float[n], outf = new float[cells];
		for (int i = 0; i < n; i++) {
			a[i] = random.nextDouble() * 6 - 3;
			af[i] = (float) a[i];
		}
		for (int op = 0; op < Gpu.FOLD_OPS; op++) {
			assertThat(Gpu.fold(op, a, 0, out, 0, outer, len, inner)).isTrue();
			assertThat(Gpu.fold(op, af, 0, outf, 0, outer, len, inner)).isTrue();
			for (int o = 0; o < outer; o++) {
				for (int i = 0; i < inner; i++) {
					int base = o * len * inner + i;
					double acc = op == Gpu.FOLD_SUM ? 0.0 : a[base];
					double accf = op == Gpu.FOLD_SUM ? 0.0 : af[base];
					for (int k = op == Gpu.FOLD_SUM ? 0 : 1; k < len; k++) {
						double v = a[base + k * inner], vf = af[base + k * inner];
						if (op == Gpu.FOLD_SUM) {
							acc += v;
							accf += vf;
						}
						else if (op == Gpu.FOLD_AMAX ? v > acc : v < acc) {
							acc = v;
						}
						if (op != Gpu.FOLD_SUM && (op == Gpu.FOLD_AMAX ? vf > accf : vf < accf)) {
							accf = vf;
						}
					}
					assertThat(out[o * inner + i]).isEqualTo(acc);
					assertThat(outf[o * inner + i]).isEqualTo((float) accf);
				}
			}
		}
	}

	@Test
	void everyStridedOperandIncludingTheResultIsReadFromItsOwnOffset() {
		// The compiled backends keep a [rank, dim..., data] header inside the same array,
		// so all three calls have to honour an element offset -- and must not touch the
		// header they are offset past.
		int cols = 64;
		// Sized off the FOLD threshold, which is the higher of the two: one shape has to
		// clear both.
		int rows = (int) Math.max(Gpu.foldMinElements() / cols * 2, 512);
		int n = rows * cols, off = 3;
		double[] x = new double[off + n], y = new double[off + rows], out = new double[off + n];
		for (int i = 0; i < n; i++) {
			x[off + i] = i % 17;
		}
		for (int i = 0; i < rows; i++) {
			y[off + i] = i % 5 + 1;
		}
		int[] dims = { rows, cols };
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, off, new int[] { cols, 1 }, y, off, new int[] { 1, 0 }, out, off, dims))
			.isTrue();
		assertThat(out[0]).isEqualTo(0.0);
		assertThat(out[off]).isEqualTo(x[off] + y[off]);
		assertThat(out[off + n - 1]).isEqualTo(x[off + n - 1] + y[off + rows - 1]);
		double[] folded = new double[off + rows];
		assertThat(Gpu.fold(Gpu.FOLD_SUM, x, off, folded, off, rows, cols, 1)).isTrue();
		assertThat(folded[0]).isEqualTo(0.0);
		double first = 0;
		for (int c = 0; c < cols; c++) {
			first += x[off + c];
		}
		assertThat(folded[off]).isEqualTo(first);
		double[] moved = new double[off + n];
		assertThat(Gpu.gather(x, off, new int[] { 1, cols }, moved, off, new int[] { cols, rows })).isTrue();
		assertThat(moved[0]).isEqualTo(0.0);
		assertThat(moved[off + 1]).isEqualTo(x[off + cols]);
	}

	@Test
	void everyStridedDeclineConditionStillDeclinesWithADevicePresent() {
		int cols = 64;
		int rows = (int) Math.max(Gpu.stridedMinElements() / cols * 2, 512);
		int n = rows * cols;
		double[] x = new double[n], y = new double[rows], out = new double[n];
		int[] dims = { rows, cols };
		int[] sx = { cols, 1 };
		int[] sy = { 1, 0 };
		assertThat(Gpu.bcast(Gpu.BIN_OPS, x, 0, sx, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(-1, x, 0, sx, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, sx, y, 0, sy, out, 1, dims)).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, new int[] { cols }, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, new int[] { cols, -1 }, y, 0, sy, out, 0, dims)).isFalse();
		assertThat(Gpu.bcast(Gpu.BIN_ADD, x, 0, sx, y, 0, sy, out, 0, new int[] { 2, 2 })).isFalse();
		assertThat(Gpu.gather(x, 0, sx, out, 0, new int[] { 2, 2 })).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_OPS, x, 0, out, 0, rows, cols, 1)).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_SUM, x, 0, out, 0, 1, n, 1)).isFalse();
		assertThat(Gpu.fold(Gpu.FOLD_SUM, x, 0, out, 0, rows, cols, 0)).isFalse();
		assertThat(out).containsOnly(0.0);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aRunOfStridedCallsFreesEveryBufferItAllocates() {
		// The broadcast path allocates FOUR buffers -- the fourth is the layout -- and
		// the
		// fold two; neither is reached by the product's or the map's leak test.
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		int cols = 64, rows = 1 << 12, n = rows * cols;
		double[] x = new double[n], y = new double[rows], out = new double[n];
		int[] dims = { rows, cols };
		assertThat(gemm.bcast(Gpu.BIN_ADD, x, 0, new int[] { cols, 1 }, y, 0, new int[] { 1, 0 }, out, 0, dims))
			.isTrue();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 800; i++) {
			assertThat(gemm.bcast(Gpu.BIN_ADD, x, 0, new int[] { cols, 1 }, y, 0, new int[] { 1, 0 }, out, 0, dims))
				.isTrue();
			assertThat(gemm.fold(Gpu.FOLD_SUM, x, 0, y, 0, rows, cols, 1)).isTrue();
			assertThat(gemm.gather(x, 0, new int[] { 1, cols }, out, 0, new int[] { cols, rows })).isTrue();
		}
		assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(DRIFT_BOUND);
	}

	@Test
	void theGeneratorFillIsBitIdenticalToTheSequentialWalkAtBothWidthsAndEveryRule() {
		// The whole claim of the member: the closed-form jump puts every element at the
		// state the sequential walk would have reached, and the draws from there are the
		// walk's own operations -- so the device fill is byte-for-byte the CPU's at both
		// widths, for every rule, from an offset, and its reported end state is the
		// walk's. The size spans a wrap of every modulus many times over.
		int n = (int) Math.max(Gpu.rngMinElements() * 2, 1 << 16);
		int s1 = 4321, s2 = 8765, s3 = 2468;
		for (int mode = 0; mode <= 2; mode++) {
			double[] expected = new double[n];
			int[] st = { s1, s2, s3 };
			for (int k = 0; k < n; k++) {
				expected[k] = element(mode, -1.0, 3.0, st);
			}
			double[] out = new double[n + 3];
			float[] outF = new float[n + 5];
			assertThat(Gpu.rngFill(out, 3, n, mode, -1.0, 3.0, s1, s2, s3)).as("mode %d f64", mode).isTrue();
			assertThat(Gpu.rngFill(outF, 5, n, mode, -1.0, 3.0, s1, s2, s3)).as("mode %d f32", mode).isTrue();
			for (int k = 0; k < n; k++) {
				assertThat(out[3 + k]).as("mode %d element %d", mode, k).isEqualTo(expected[k]);
				assertThat(outF[5 + k]).as("mode %d f32 element %d", mode, k).isEqualTo((float) expected[k]);
			}
			assertThat(out[0]).isEqualTo(0.0);
			assertThat(outF[4]).isEqualTo(0.0f);
			assertThat(Gpu.rngAdvance(s1, s2, s3, (long) n * (mode == 1 ? 12 : 1))).isEqualTo(st);
		}
	}

	/** The sequential rule, as the CPU kernels spell it. */
	private static double element(int mode, double lo, double span, int[] st) {
		if (mode == 1) {
			double acc = 0.0;
			for (int j = 0; j < 12; j++) {
				acc = acc + next(st);
			}
			return acc - 6.0;
		}
		if (mode == 0) {
			return next(st);
		}
		return lo + span * next(st);
	}

	private static double next(int[] st) {
		int a = 171 * st[0] % 30269, b = 172 * st[1] % 30307, c = 170 * st[2] % 30323;
		st[0] = a;
		st[1] = b;
		st[2] = c;
		double u = a / 30269.0 + b / 30307.0 + c / 30323.0;
		return u >= 2.0 ? u - 2.0 : (u >= 1.0 ? u - 1.0 : u);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aRunOfGeneratorFillsFreesEveryBufferItAllocates() {
		// One buffer a call, on a path none of the other leak tests reach.
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		int n = 1 << 18;
		double[] out = new double[n];
		assertThat(gemm.rngFill(out, 0, n, 1, 0.0, 1.0, 1, 2, 3)).isTrue();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.rngFill(out, 0, n, i % 3, 0.0, 1.0, 1, 2, 3)).isTrue();
		}
		assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(DRIFT_BOUND);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aRunOfSuccessfulProductsFreesEveryBufferItAllocates() {
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		int n = 384;
		double[] a = new double[n * n], b = new double[n * n], c = new double[n * n];
		// One call first, so the driver's pool has reached its working size and the
		// baseline is the steady state rather than the cold one.
		assertThat(gemm.gemm(a, 0, b, 0, c, 0, n, n, n)).isTrue();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.gemm(a, 0, b, 0, c, 0, n, n, n)).isTrue();
		}
		long after = gemm.freeDeviceMemory();
		// 1000 products of three 1.2 MB buffers leak 3.5 GB if they leak at all, and
		// leaking ONE of the three still costs 1.2 GB -- so the bound separates the two
		// outcomes by a wide margin rather than measuring precisely. It cannot be tight:
		// cuMemGetInfo is a property of the DEVICE, not of this thread, and the JVM
		// backend's tests run in a second surefire fork where every compiled class
		// defines its own copy of this binding and loads its own module. The assertion
		// stays two-sided on purpose: free memory that GREW would mean this is measuring
		// the rest of the machine rather than the buffers.
		assertThat(Math.abs(before - after)).isLessThan(DRIFT_BOUND);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aDeclinedProductCostsTheDeviceNothing() {
		// The failure path, which the successful run above never enters, and the one that
		// was wrong first: a pooled allocation that FAILS grows the driver's pool as far
		// as it can on the way to failing and hands back no pointer to free. Measured
		// before this was handled, ONE declined product took this 128 GB device from
		// 69 GB free to 1 GB free and never gave it back -- to this process or to any
		// other one on the card. An 80 GB operand is refused by the pre-flight; the pool
		// trim covers the same case when the pre-flight cannot see it coming.
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		int huge = 100_000;
		double[] tiny = new double[1];
		for (int i = 0; i < 12; i++) {
			assertThat(gemm.gemm(tiny, 0, tiny, 0, tiny, 0, huge, huge, huge))
				.as("an %d x %d product must decline", huge, huge)
				.isFalse();
			assertThat(gemm.usable()).as("running out of device memory is not a sticky error").isTrue();
		}
		long after = gemm.freeDeviceMemory();
		assertThat(before - after).as("free device memory after twelve declined products").isLessThan(64L << 20);
		// And the device still works afterwards.
		int n = square();
		double[] a = new double[n * n], b = new double[n * n], c = new double[n * n];
		assertThat(gemm.gemm(a, 0, b, 0, c, 0, n, n, n)).isTrue();
	}

	@Test
	void theSameProductRepeatedIsTheSameAnswer() {
		int n = square();
		Random random = new Random(7L);
		double[] a = new double[n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = random.nextDouble();
			b[i] = random.nextDouble();
		}
		double[] first = Gpu.multiply(a, 0, b, 0, n, n, n);
		assertThat(first).isNotNull();
		for (int i = 0; i < 20; i++) {
			assertThat(Gpu.multiply(a, 0, b, 0, n, n, n)).containsExactly(first);
		}
	}

	@Test
	void aBatchedProductIsThePerBatchProductOfEachSlab() {
		// The stacked shape (torch.bmm): every slab is its own product, and small
		// integers through the fold are exact at both widths, so this is an EQUALITY.
		int n = square();
		int batch = 3;
		double[] a = new double[batch * n * n], b = new double[batch * n * n];
		float[] af = new float[a.length], bf = new float[b.length];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
			b[i] = (i % 5) - 2;
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		double[] c = new double[batch * n * n];
		float[] cf = new float[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, c, 0, batch, n, n, n)).isTrue();
		assertThat(Gpu.multiply(af, 0, n * n, bf, 0, n * n, cf, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(a, z * n * n, b, z * n * n, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(c[z * n * n + i]).isEqualTo(expected[i]);
				assertThat((double) cf[z * n * n + i]).isEqualTo(expected[i]);
			}
		}
	}

	@Test
	void aBatchIsBitIdenticalToTheSameSlabsRunOneAtATime() {
		// The precision contract of the stacked member, stated as an assertion: a batched
		// cell is a per-batch device product, not something the batch axis rounds
		// differently. INEXACT operands, so a difference would show.
		int n = square();
		int batch = 4;
		Random random = new Random(4242L);
		double[] a = new double[batch * n * n], b = new double[batch * n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = random.nextDouble() - 0.5;
			b[i] = random.nextDouble() - 0.5;
		}
		double[] batched = new double[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, batched, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] alone = new double[n * n];
			assertThat(Gpu.multiply(a, z * n * n, b, z * n * n, alone, 0, n, n, n)).isTrue();
			for (int i = 0; i < n * n; i++) {
				assertThat(batched[z * n * n + i]).isEqualTo(alone[i]);
			}
		}
	}

	@Test
	void aBroadcastOperandIsAZeroStrideAndReadsTheSameSlabEveryBatch() {
		// What linalg::%la-matmul-nd's broadcast leading axis passes -- and every
		// torch:linear over a (B T C) activation, whose right operand is one matrix. The
		// operand array holds exactly ONE slab however long the batch is, which is also
		// the assertion that only that slab is copied to the device.
		int n = square();
		int batch = 5;
		double[] a = new double[batch * n * n], b = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (i % 7) - 3;
		}
		for (int i = 0; i < b.length; i++) {
			b[i] = (i % 5) - 2;
		}
		double[] c = new double[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, 0, c, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(a, z * n * n, b, 0, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(c[z * n * n + i]).isEqualTo(expected[i]);
			}
		}
		// And the other way round: one left operand against a stack of right ones.
		double[] left = new double[n * n], stack = new double[batch * n * n], out = new double[batch * n * n];
		for (int i = 0; i < left.length; i++) {
			left[i] = (i % 3) - 1;
		}
		for (int i = 0; i < stack.length; i++) {
			stack[i] = (i % 4) - 2;
		}
		assertThat(Gpu.multiply(left, 0, 0, stack, 0, n * n, out, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(left, 0, stack, z * n * n, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(out[z * n * n + i]).isEqualTo(expected[i]);
			}
		}
	}

	@Test
	void aBatchedProductReadsEveryOperandFromItsOwnOffset() {
		int n = square();
		int batch = 3, headerA = 4, headerB = 3, headerOut = 5;
		double[] a = new double[headerA + batch * n * n], b = new double[headerB + batch * n * n];
		double[] out = new double[headerOut + batch * n * n];
		for (int i = 0; i < headerA; i++) {
			a[i] = Double.NaN;
		}
		for (int i = 0; i < headerB; i++) {
			b[i] = Double.NaN;
		}
		for (int i = 0; i < headerOut; i++) {
			out[i] = i + 0.25;
		}
		for (int i = 0; i < batch * n * n; i++) {
			a[headerA + i] = (i % 7) - 3;
			b[headerB + i] = (i % 5) - 2;
		}
		assertThat(Gpu.multiply(a, headerA, n * n, b, headerB, n * n, out, headerOut, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(a, headerA + z * n * n, b, headerB + z * n * n, n, n, n);
			for (int i = 0; i < n * n; i++) {
				assertThat(out[headerOut + z * n * n + i]).isEqualTo(expected[i]);
			}
		}
		for (int i = 0; i < headerOut; i++) {
			assertThat(out[i]).isEqualTo(i + 0.25);
		}
	}

	@Test
	void everyBatchedDeclineConditionStillDeclinesWithADevicePresent() {
		int n = square();
		int batch = 3;
		double[] a = new double[batch * n * n], b = new double[batch * n * n], out = new double[batch * n * n];
		// Below the threshold, which for a stack is the TOTAL work: one 8x8x8 product
		// stays below it however the batch is spelled, and 64 of them still do.
		assertThat(Gpu.worth(64, 8, 8, 8)).isFalse();
		assertThat(Gpu.multiply(a, 0, 512, b, 0, 512, out, 0, 64, 8, 8, 8)).isFalse();
		// An empty batch, and one past the 16-bit gridDim.z.
		assertThat(Gpu.worth(0, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, out, 0, 0, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, out, 0, 70_000, n, n, n)).isFalse();
		// A stride that walks off the end of an operand, and a negative one.
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, out, 0, batch + 1, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, -(n * n), b, 0, n * n, out, 0, batch, n, n, n)).isFalse();
		// A result array that cannot hold the whole stack.
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, new double[n * n], 0, batch, n, n, n)).isFalse();
		// And a batch above the threshold still costs the device nothing when it is too
		// big for device memory.
		assertThat(Gpu.multiply(a, 0, 0, b, 0, 0, out, 0, 60_000, 20_000, 20_000, 20_000)).isFalse();
	}

	/** The scalar row-by-column product the accelerated one has to agree with. */
	private static double[] reference(double[] a, int offsetA, double[] b, int offsetB, int n, int m, int p) {
		double[] c = new double[n * p];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < m; k++) {
				double left = a[offsetA + i * m + k];
				for (int j = 0; j < p; j++) {
					c[i * p + j] += left * b[offsetB + k * p + j];
				}
			}
		}
		return c;
	}

}
