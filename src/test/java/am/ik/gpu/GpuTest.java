package am.ik.gpu;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
	 * Every test below reads a result straight out of its array, which is the library's
	 * EAGER contract; an interceptor test that ran earlier in this fork may have switched
	 * lazy results on for the process, so each test starts from the default.
	 */
	@org.junit.jupiter.api.BeforeEach
	void eagerResults() {
		Gpu.lazyResults(false);
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
	 * How far free device memory may drift across a leak test before it is called a leak,
	 * on a driver with no memory pool -- the one case {@link #driftSample} still has to
	 * ask {@code cuMemGetInfo} for (see {@link #driftBound}). It is deliberately LOOSE,
	 * and it had to be widened twice: {@code cuMemGetInfo} reports the whole DEVICE, and
	 * the rest of the suite runs beside this one -- the JVM backend's fork loads a
	 * separate copy of this binding, with its own primary context and its own PTX module,
	 * for every compiled {@code --gpu} class it defines, and there are now enough of
	 * those to move free memory by ~800 MB on their own. Every leak test below is sized
	 * so that a real leak is 2-8x this, so widening the bound costs the assertion
	 * nothing.
	 */
	private static final long DRIFT_BOUND = 1536L << 20;

	/**
	 * The same bound where {@link #driftSample} answers from the pool instead --
	 * {@code CU_MEMPOOL_ATTR_USED_MEM_CURRENT} is scoped to the pool HANDLE it is asked
	 * of, not the device, so a sibling process's allocations -- another surefire fork, or
	 * anything else running on the machine at the same time -- do not move it at all
	 * (.todo/481, seen only in a full {@code ./mvnw test} on a unified-memory machine,
	 * where {@code cuMemGetInfo}'s free figure is the HOST's free memory too). Measured
	 * on the GB10 with an unrelated process actively touching 8 GB of host memory
	 * throughout a 1000-call run: {@code cuMemGetInfo} drifted 1.3 GB and the pool's own
	 * count did not move at all. So this can be tight -- every leak test below is still
	 * sized so that a real leak is orders of magnitude past it.
	 */
	private static final long POOL_DRIFT_BOUND = 64L << 20;

	/**
	 * Bytes outstanding right now: the pool's own count when this driver has one, which
	 * is what every leak test below should be asking, or the device-wide reading as the
	 * fallback for a driver with no pool, where there is no other number to ask.
	 */
	private static long driftSample(GpuDevice gemm) {
		long pool = ((CudaGemm) gemm).poolBytesInUse();
		return pool >= 0 ? pool : gemm.freeDeviceMemory();
	}

	/** The bound that matches what {@link #driftSample} just answered. */
	private static long driftBound(GpuDevice gemm) {
		return ((CudaGemm) gemm).poolBytesInUse() >= 0 ? POOL_DRIFT_BOUND : DRIFT_BOUND;
	}

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
				Gpu.MAP_ACOS, Gpu.MAP_ATAN, Gpu.MAP_SINH, Gpu.MAP_COSH, Gpu.MAP_ERF, Gpu.MAP_SQRT, Gpu.MAP_ABS,
				Gpu.MAP_NEGATIVE, Gpu.MAP_SIGN };
		assertThat(ops).hasSize(Gpu.MAP_OPS);
		for (int op : ops) {
			double[] a = new double[n], out = new double[n];
			float[] af = new float[n], outF = new float[n];
			for (int i = 0; i < n; i++) {
				a[i] = domain(op, i, n);
				af[i] = (float) a[i];
			}
			if (op >= Gpu.MAP_LIBM_OPS) {
				// The resident tier's four are offered over a RESIDENT operand only: a
				// libm member over the same array first is what makes it one.
				assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new double[n], 0, n)).isTrue();
				assertThat(Gpu.map(Gpu.MAP_EXP, af, 0, new float[n], 0, n)).isTrue();
			}
			assertThat(Gpu.map(op, a, 0, out, 0, n)).as("op %d", op).isTrue();
			assertThat(Gpu.map(op, af, 0, outF, 0, n)).as("op %d f32", op).isTrue();
			for (int i = 0; i < n; i += 97) {
				double expected = scalar(op, a[i]);
				if (op >= Gpu.MAP_LIBM_OPS) {
					// Computed in double and narrowed, so BIT-identical to Math's answer
					// at both widths -- including sign's NaN and signed zero.
					assertThat(out[i]).as("op %d at %f", op, a[i]).isEqualTo(expected);
					assertThat(outF[i]).as("op %d at %f f32", op, a[i]).isEqualTo((float) expected);
					continue;
				}
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
		if (op == Gpu.MAP_SQRT) {
			return t * 100;
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
		if (op == Gpu.MAP_SQRT) {
			return Math.sqrt(x);
		}
		if (op == Gpu.MAP_ABS) {
			return Math.abs(x);
		}
		if (op == Gpu.MAP_NEGATIVE) {
			return -x;
		}
		if (op == Gpu.MAP_SIGN) {
			return Math.signum(x);
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
		long before = driftSample(gemm);
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		}
		// 1000 maps of two 4 MB buffers leak 8 GB if they leak at all. See the bound's
		// reasoning under aRunOfSuccessfulProductsFreesEveryBufferItAllocates.
		assertThat(Math.abs(before - driftSample(gemm))).isLessThan(driftBound(gemm));
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
		return apply(op, a, b);
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
		long before = driftSample(gemm);
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 800; i++) {
			assertThat(gemm.bcast(Gpu.BIN_ADD, x, 0, new int[] { cols, 1 }, y, 0, new int[] { 1, 0 }, out, 0, dims))
				.isTrue();
			assertThat(gemm.fold(Gpu.FOLD_SUM, x, 0, y, 0, rows, cols, 1)).isTrue();
			assertThat(gemm.gather(x, 0, new int[] { 1, cols }, out, 0, new int[] { cols, rows })).isTrue();
		}
		assertThat(Math.abs(before - driftSample(gemm))).isLessThan(driftBound(gemm));
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
		long before = driftSample(gemm);
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.rngFill(out, 0, n, i % 3, 0.0, 1.0, 1, 2, 3)).isTrue();
		}
		assertThat(Math.abs(before - driftSample(gemm))).isLessThan(driftBound(gemm));
	}

	// --- the matrix-by-vector product (vec:matvec, 2026-08-22) -----------------------
	// The one member whose accept-or-decline is RESIDENCY rather than size: the first
	// sight of a matrix declines and leaves a mark, the second uploads it, every later
	// one
	// finds it, and a write in between starts the count again.

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aMatrixByVectorProductIsTakenOnlyOnceItsMatrixHasBeenOfferedTwiceUnwritten() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "residency is the CUDA backend's");
		Gpu.releaseResident();
		int rows = 512, cols = 512;
		double[] w = new double[rows * cols], x = new double[cols], y = new double[rows], oracle = new double[rows];
		for (int i = 0; i < w.length; i++) {
			w[i] = (i % 7) - 3; // exact small integers: a reordered sum of them is exact
								// too
		}
		for (int j = 0; j < cols; j++) {
			x[j] = (j % 5) - 2;
		}
		for (int r = 0; r < rows; r++) {
			double acc = 0;
			for (int j = 0; j < cols; j++) {
				acc += w[r * cols + j] * x[j];
			}
			oracle[r] = acc;
		}
		// First sight: declined, nothing moved, nothing resident.
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(y).containsOnly(0.0);
		assertThat(Gpu.residentBytes()).isZero();
		// Second sight, unwritten: uploaded, computed exactly, and the matrix stays.
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		assertThat(y).isEqualTo(oracle);
		assertThat(Gpu.residentBytes()).isGreaterThanOrEqualTo((long) rows * cols * Double.BYTES);
		// Third: the matrix is a hit.
		long hits = residency.hits();
		java.util.Arrays.fill(y, 0.0);
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		assertThat(residency.hits()).isGreaterThan(hits);
		assertThat(y).isEqualTo(oracle);
		// Written: the copy is dropped, the next sight is a first sight again and
		// declines, and the one after that uploads the NEW bytes.
		w[0] = 100;
		Gpu.written(w);
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		oracle[0] += (100 - (-3)) * x[0];
		assertThat(y).isEqualTo(oracle);
	}

	@Test
	void aSingleFloatMatrixByVectorProductLandsOnTheDoubleAccumulatedOracle() {
		// The kernel accumulates in double at f32 too, so every product of two floats is
		// exact in it and only the ORDER of a double sum separates it from the scalar
		// defun's widen-accumulate-narrow rule -- which moves the narrowed float only
		// when
		// the sum lies within ~1e-16 of a rounding boundary: measured, never over 1024
		// rows. A float accumulator (the lane kernel's width) lands 2.6e-7 away and on
		// about a quarter of the rows, so the near-identity below is the contract's pin.
		int rows = 512, cols = 768;
		float[] w = new float[rows * cols], x = new float[cols], y = new float[rows], oracle = new float[rows];
		for (int i = 0; i < w.length; i++) {
			w[i] = (float) Math.sin(i * 0.37);
		}
		for (int j = 0; j < cols; j++) {
			x[j] = (float) Math.cos(j * 0.11);
		}
		double scale = 0;
		for (int r = 0; r < rows; r++) {
			double acc = 0;
			for (int j = 0; j < cols; j++) {
				acc += (double) w[r * cols + j] * x[j];
			}
			oracle[r] = (float) acc;
			scale = Math.max(scale, Math.abs(acc));
		}
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		int identical = 0;
		for (int r = 0; r < rows; r++) {
			assertThat(y[r]).as("row %d", r).isCloseTo(oracle[r], within((float) (scale * 1e-6)));
			if (y[r] == oracle[r]) {
				identical++;
			}
		}
		assertThat(identical).as("rows bit-identical to the double-accumulated oracle").isGreaterThan(rows * 99 / 100);
	}

	@Test
	void aDoubleMatrixByVectorProductAgreesWithTheOracleToAFewUlps() {
		// At f64 the fused multiply-add and the warp's tree are the product's own story.
		int rows = 512, cols = 768;
		double[] w = new double[rows * cols], x = new double[cols], y = new double[rows], oracle = new double[rows];
		for (int i = 0; i < w.length; i++) {
			w[i] = Math.sin(i * 0.37);
		}
		for (int j = 0; j < cols; j++) {
			x[j] = Math.cos(j * 0.11);
		}
		double scale = 0;
		for (int r = 0; r < rows; r++) {
			double acc = 0;
			for (int j = 0; j < cols; j++) {
				acc += w[r * cols + j] * x[j];
			}
			oracle[r] = acc;
			scale = Math.max(scale, Math.abs(acc));
		}
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		for (int r = 0; r < rows; r++) {
			assertThat(y[r]).as("row %d", r).isCloseTo(oracle[r], within(scale * 1e-13));
		}
	}

	@Test
	void everyMatrixByVectorOperandIncludingTheResultIsReadFromItsOwnOffset() {
		// The compiled representation keeps a header inside each array: the matrix's
		// elements start at 3, a vector's at 2, and the result's header must survive.
		int rows = 400, cols = 400;
		double[] w = new double[3 + rows * cols], x = new double[2 + cols], y = new double[2 + rows];
		w[0] = 2;
		w[1] = rows;
		w[2] = cols;
		x[0] = 1;
		x[1] = cols;
		y[0] = 1;
		y[1] = rows;
		for (int i = 0; i < rows * cols; i++) {
			w[3 + i] = (i % 11) - 5;
		}
		for (int j = 0; j < cols; j++) {
			x[2 + j] = (j % 3) - 1;
		}
		assertThat(Gpu.matvec(w, 3, x, 2, y, 2, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 3, x, 2, y, 2, rows, cols)).isTrue();
		assertThat(y[0]).isEqualTo(1.0);
		assertThat(y[1]).isEqualTo(rows);
		for (int r = 0; r < rows; r++) {
			double acc = 0;
			for (int j = 0; j < cols; j++) {
				acc += w[3 + r * cols + j] * x[2 + j];
			}
			assertThat(y[2 + r]).as("row %d", r).isEqualTo(acc);
		}
	}

	@Test
	void everyMatrixByVectorDeclineConditionStillDeclinesWithADevicePresent() {
		int rows = 512, cols = 256;
		double[] w = new double[rows * cols], x = new double[cols], y = new double[rows];
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, 64, 64)).isFalse();
		assertThat(Gpu.matvec(w, 0, new double[cols - 1], 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, new double[rows - 1], 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 1, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 1, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 1, rows, cols)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, 0, cols)).isFalse();
		assertThat(y).containsOnly(0.0);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aRunOfMatrixByVectorProductsFreesEveryBufferItAllocates() {
		// A resident matrix, a resident vector and a result replaced every call: the
		// steady state holds three buffers, and the drift over a thousand calls is the
		// same loose bound every leak test here uses.
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		Gpu.releaseResident();
		int rows = 512, cols = 512;
		double[] w = new double[rows * cols], x = new double[cols], y = new double[rows];
		assertThat(gemm.gemv(w, 0, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(gemm.gemv(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		long before = driftSample(gemm);
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.gemv(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		}
		assertThat(Math.abs(before - driftSample(gemm))).isLessThan(driftBound(gemm));
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
		long before = driftSample(gemm);
		assertThat(before).isGreaterThan(0);
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.gemm(a, 0, b, 0, c, 0, n, n, n)).isTrue();
		}
		long after = driftSample(gemm);
		// 1000 products of three 1.2 MB buffers leak 3.5 GB if they leak at all, and
		// leaking ONE of the three still costs 1.2 GB -- so the bound separates the two
		// outcomes by a wide margin rather than measuring precisely. The assertion stays
		// two-sided on purpose: free memory that GREW would mean this is measuring the
		// rest of the machine rather than the buffers.
		assertThat(Math.abs(before - after)).isLessThan(driftBound(gemm));
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
	void everySingleFloatProductKernelLandsOnTheSameFusedFold() {
		// Three single-float kernels since 2026-08-22 -- the 16x16 one and the 64x64 and
		// 128x128 register-tiled ones, chosen per shape by SM count -- and ONE contract:
		// every cell is k ascending, one fused multiply-add per term, from +0. That fold
		// is
		// Math.fma over floats on the CPU, so this is an EQUALITY over inexact operands
		// at
		// shapes that reach each tile on this machine (a 1000-square product takes the
		// 128 tile on anything with <= 128 SMs, a stack of 256x192 slabs the 64 tile on
		// <= 48) and whose edges are ragged on every axis -- M, N and K all off the tile
		// and off 16. Which kernel ran is not observable; that is the assertion.
		int[][] shapes = { { 1, 1000, 1000, 1000 }, { 4, 256, 384, 192 }, { 3, 130, 70, 200 }, { 2, 64, 2048, 37 } };
		Random random = new Random(20260822L);
		for (int[] shape : shapes) {
			int batch = shape[0], n = shape[1], m = shape[2], p = shape[3];
			float[] a = new float[batch * n * m], b = new float[m * p], c = new float[batch * n * p];
			for (int i = 0; i < a.length; i++) {
				a[i] = random.nextFloat() - 0.5f;
			}
			for (int i = 0; i < b.length; i++) {
				b[i] = random.nextFloat() - 0.5f;
			}
			// b broadcasts over the batch (stride 0), as a weight under a (B T C)
			// activation
			// does; a stack of one is the plain product.
			assertThat(Gpu.multiply(a, 0, n * m, b, 0, 0, c, 0, batch, n, m, p)).as("%s", Arrays.toString(shape))
				.isTrue();
			for (int z = 0; z < batch; z++) {
				for (int i = 0; i < n; i++) {
					for (int j = 0; j < p; j++) {
						float acc = 0f;
						for (int k = 0; k < m; k++) {
							acc = Math.fma(a[z * n * m + i * m + k], b[k * p + j], acc);
						}
						assertThat(c[z * n * p + i * p + j]).as("%s cell %d,%d,%d", Arrays.toString(shape), z, i, j)
							.isEqualTo(acc);
					}
				}
			}
		}
	}

	@Test
	void aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProductAtBothWidths() {
		// The transposed product (2026-09-02): the operand is STORED with its last two
		// axes exchanged and the kernel indexes it there rather than being handed a
		// gather's copy of it. The tile the fold reads is the same tile, so the claim is
		// EQUALITY -- not a tolerance -- against the plain product of the transposed
		// copy, at every kernel the shape can reach and at both widths. The shapes are
		// the fused-fold test's, so the 16x16, 64x64 and 128x128 tiles are all covered.
		int[][] shapes = { { 1, 1000, 1000, 1000 }, { 4, 256, 384, 192 }, { 3, 130, 70, 200 }, { 2, 64, 2048, 37 } };
		Random random = new Random(20260902L);
		for (int[] shape : shapes) {
			int batch = shape[0], n = shape[1], m = shape[2], p = shape[3];
			float[] a = new float[batch * n * m], b = new float[m * p];
			for (int i = 0; i < a.length; i++) {
				a[i] = random.nextFloat() - 0.5f;
			}
			for (int i = 0; i < b.length; i++) {
				b[i] = random.nextFloat() - 0.5f;
			}
			// The same operands laid out the other way round, per slab: this is exactly
			// what the copy pass used to produce, and what the kernel now reads through.
			float[] at = new float[a.length], bt = new float[b.length];
			for (int z = 0; z < batch; z++) {
				for (int i = 0; i < n; i++) {
					for (int k = 0; k < m; k++) {
						at[z * n * m + k * n + i] = a[z * n * m + i * m + k];
					}
				}
			}
			for (int k = 0; k < m; k++) {
				for (int j = 0; j < p; j++) {
					bt[j * m + k] = b[k * p + j];
				}
			}
			float[] plain = new float[batch * n * p], left = new float[batch * n * p], right = new float[batch * n * p];
			String at1 = Arrays.toString(shape);
			assertThat(Gpu.multiply(a, 0, n * m, b, 0, 0, plain, 0, batch, n, m, p)).as("%s", at1).isTrue();
			assertThat(Gpu.multiply(at, 0, n * m, true, b, 0, 0, false, left, 0, batch, n, m, p)).as("%s ta", at1)
				.isTrue();
			assertThat(Gpu.multiply(a, 0, n * m, false, bt, 0, 0, true, right, 0, batch, n, m, p)).as("%s tb", at1)
				.isTrue();
			assertThat(left).as("%s ta", at1).isEqualTo(plain);
			assertThat(right).as("%s tb", at1).isEqualTo(plain);
			// The double sibling, over the same numbers: the orientation is a width-free
			// property of the kernel, so both widths have to answer it.
			double[] ad = new double[a.length], bd = new double[b.length], adt = new double[a.length],
					bdt = new double[b.length];
			for (int i = 0; i < a.length; i++) {
				ad[i] = a[i];
				adt[i] = at[i];
			}
			for (int i = 0; i < b.length; i++) {
				bd[i] = b[i];
				bdt[i] = bt[i];
			}
			double[] plainD = new double[batch * n * p], leftD = new double[batch * n * p],
					rightD = new double[batch * n * p];
			assertThat(Gpu.multiply(ad, 0, n * m, bd, 0, 0, plainD, 0, batch, n, m, p)).as("%s #d", at1).isTrue();
			assertThat(Gpu.multiply(adt, 0, n * m, true, bd, 0, 0, false, leftD, 0, batch, n, m, p)).as("%s #d ta", at1)
				.isTrue();
			assertThat(Gpu.multiply(ad, 0, n * m, false, bdt, 0, 0, true, rightD, 0, batch, n, m, p))
				.as("%s #d tb", at1)
				.isTrue();
			assertThat(leftD).as("%s #d ta", at1).isEqualTo(plainD);
			assertThat(rightD).as("%s #d tb", at1).isEqualTo(plainD);
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

	// --- device residency (2026-08-22) ------------------------------------------------
	// The CUDA backend keeps a copy of every operand and result on the device, keyed by
	// the identity of the host array and held weakly (DeviceResidency). These pin the
	// four
	// properties .kb/gpu.md sells it on: a recent operand or result is not uploaded
	// again; a write to the host array -- reported through Gpu.written, which every
	// setter on both backends calls -- makes the next call upload it again and answer
	// for the NEW bytes; the resident set is bounded by its budget and a release empties
	// it, with the device memory coming back; and an array the collector has reclaimed
	// takes its device copy with it.

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void anOperandUploadedOrProducedByARecentCallIsNotUploadedAgain() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "residency is the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] a = new double[n], c = new double[n], d = new double[n], e = new double[n];
		for (int i = 0; i < n; i++) {
			a[i] = (i % 97) / 100.0;
		}
		long hits = residency.hits(), misses = residency.misses();
		// First sight of a: uploaded and recorded; c is recorded after its download.
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		assertThat(residency.misses()).isEqualTo(misses + 1);
		assertThat(residency.hits()).isEqualTo(hits);
		// The same operand again: found, not moved.
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, d, 0, n)).isTrue();
		assertThat(residency.hits()).isEqualTo(hits + 1);
		assertThat(d).isEqualTo(c);
		// The CHAIN, which is what residency is for: the result of one call is the
		// operand of the next, and that is a hit too -- it was recorded on the way down.
		assertThat(Gpu.map(Gpu.MAP_LOG, c, 0, e, 0, n)).isTrue();
		assertThat(residency.hits()).isEqualTo(hits + 2);
		for (int i = 0; i < n; i += 997) {
			assertThat(e[i]).as("log(exp(a[%d]))", i).isCloseTo(a[i], within(1e-9));
		}
		assertThat(Gpu.residentBytes()).isGreaterThan(0);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aWrittenHostArrayIsUploadedAgainAndTheAnswerFollowsTheWrite() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "residency is the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] a = new double[n], c = new double[n];
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		assertThat(c[12345]).isEqualTo(1.0);
		// The host array stays authoritative: a write to it, reported the way every
		// enumerated setter reports it, must reach the next call's answer.
		a[12345] = 2.0;
		Gpu.written(a);
		long misses = residency.misses();
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		assertThat(residency.misses()).as("the written operand is uploaded again").isEqualTo(misses + 1);
		assertThat(c[12345]).isCloseTo(Math.exp(2.0), within(1e-12));
		assertThat(c[12344]).isEqualTo(1.0);
		// An array the cache has never seen is simply not found, and the hook never
		// throws, runs the probe or touches the driver.
		Gpu.written(new double[1]);
		Gpu.written("not an array at all");
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "residency is the CUDA backend's");
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		Gpu.releaseResident();
		long before = gemm.freeDeviceMemory();
		assertThat(before).isGreaterThan(0);
		int n = 1 << 18; // 2 MB a buffer, two buffers a call
		long budget = 8L << 20;
		Gpu.residentBudget(budget);
		List<double[]> reachable = new ArrayList<>();
		try {
			for (int i = 0; i < 32; i++) {
				double[] a = new double[n], c = new double[n];
				reachable.add(a);
				reachable.add(c);
				assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
				assertThat(Gpu.residentBytes()).as("after call %d", i).isLessThanOrEqualTo(budget);
			}
			assertThat(Gpu.residentBytes()).isGreaterThan(0);
			assertThat(residency.budget()).isEqualTo(budget);
			Gpu.releaseResident();
			assertThat(Gpu.residentBytes()).isZero();
			// 32 calls of two 2 MB buffers held resident would be 128 MB; the bound is
			// the
			// leak tests' loose one, two-sided for the reason theirs is.
			assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(DRIFT_BOUND);
		}
		finally {
			Gpu.residentBudget(-1);
			Gpu.releaseResident();
		}
		assertThat(reachable).hasSize(64);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aCollectedHostArrayTakesItsResidentCopyWithIt() throws InterruptedException {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "residency is the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] keep = new double[n], kc = new double[n];
		assertThat(Gpu.map(Gpu.MAP_EXP, keep, 0, kc, 0, n)).isTrue();
		long held = Gpu.residentBytes();
		assertThat(held).isEqualTo(2L * n * Double.BYTES);
		for (int i = 0; i < 8; i++) {
			assertThat(Gpu.map(Gpu.MAP_EXP, new double[n], 0, new double[n], 0, n)).isTrue();
		}
		assertThat(Gpu.residentBytes()).isGreaterThan(held);
		// Sixteen arrays nobody can reach any more: once the collector has them, the
		// next call's drain frees their buffers, and only the reachable pair is left.
		long after = -1;
		for (int attempt = 0; attempt < 20 && after != held; attempt++) {
			System.gc();
			Thread.sleep(20);
			assertThat(Gpu.map(Gpu.MAP_EXP, keep, 0, kc, 0, n)).isTrue();
			after = Gpu.residentBytes();
		}
		assertThat(after).isEqualTo(held);
	}

	// --- lazy results and the resident tier (.todo/491) -------------------------------

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aLazyResultStaysOnTheDeviceUntilTheHostFirstReadsIt() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		// And the mode PAYS here -- measured, a fifth off the training step -- which is
		// what makes it the interceptors' mode on this backend (and, since todo-495, on
		// Metal).
		assertThat(java.util.Objects.requireNonNull(Gpu.device()).lazyResultsPay()).isTrue();
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] a = new double[n], c = new double[n], e = new double[n];
		for (int i = 0; i < n; i++) {
			a[i] = (i % 97) / 100.0;
		}
		Gpu.lazyResults(true);
		try {
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
			// Nothing came home: the host array is untouched and the copy is DIRTY.
			assertThat(c[12345]).isZero();
			assertThat(residency.dirtyCount()).isEqualTo(1);
			assertThat(Gpu.resident(c)).isTrue();
			// The chain reads the device's bytes, not the host's zeros, and downloads
			// nothing on the way.
			long hits = residency.hits();
			assertThat(Gpu.map(Gpu.MAP_LOG, c, 0, e, 0, n)).isTrue();
			assertThat(residency.hits()).isEqualTo(hits + 1);
			assertThat(residency.dirtyCount()).isEqualTo(2);
			assertThat(e[12345]).isZero();
			// The first host touch brings each home, once; a second touch is free.
			Gpu.materialize(e);
			assertThat(residency.dirtyCount()).isEqualTo(1);
			for (int i = 0; i < n; i += 997) {
				assertThat(e[i]).as("log(exp(a[%d]))", i).isCloseTo(a[i], within(1e-9));
			}
			Gpu.materialize(c);
			assertThat(residency.dirtyCount()).isZero();
			assertThat(c[12345]).isCloseTo(Math.exp(a[12345]), within(1e-12));
			Gpu.materialize(c);
			// Both stay resident, clean, for the next member.
			assertThat(Gpu.resident(c)).isTrue();
			assertThat(Gpu.resident(e)).isTrue();
			// An array the device never saw, or no array at all, is simply nothing to do.
			Gpu.materialize(new double[4]);
			Gpu.materialize("not an array");
		}
		finally {
			Gpu.lazyResults(false);
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aWriteToALazyResultBringsItHomeFirst() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] a = new double[n], c = new double[n], d = new double[n];
		Gpu.lazyResults(true);
		try {
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
			assertThat(c[100]).isZero();
			// The write hook, called BEFORE the store as every enumerated setter calls
			// it: the result is downloaded, then forgotten, and the store lands on the
			// real bytes.
			Gpu.written(c);
			assertThat(c[100]).isEqualTo(1.0);
			assertThat(Gpu.resident(c)).isFalse();
			c[100] = 2.0;
			long misses = residency.misses();
			assertThat(Gpu.map(Gpu.MAP_LOG, c, 0, d, 0, n)).isTrue();
			assertThat(residency.misses()).as("the written array is uploaded again").isEqualTo(misses + 1);
			Gpu.materialize(d);
			assertThat(d[100]).isCloseTo(Math.log(2.0), within(1e-12));
			assertThat(d[99]).isZero();
		}
		finally {
			Gpu.lazyResults(false);
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void anEvictedOrReleasedLazyResultIsDownloadedNotDropped() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		GpuDevice gemm = Gpu.device();
		assertThat(gemm).isNotNull();
		Gpu.releaseResident();
		int n = 1 << 18; // 2 MB a buffer
		long budget = 8L << 20;
		Gpu.residentBudget(budget);
		Gpu.lazyResults(true);
		List<double[]> results = new ArrayList<>();
		try {
			double[] a = new double[n];
			for (int i = 0; i < n; i++) {
				a[i] = (i % 89) / 100.0;
			}
			// Thirty-two lazy results against a budget that holds three: the cap
			// evicts by DOWNLOADING, and every evicted array holds its answer without
			// anyone having read it.
			for (int i = 0; i < 32; i++) {
				double[] c = new double[n];
				results.add(c);
				assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
				assertThat(Gpu.residentBytes()).as("after call %d", i).isLessThanOrEqualTo(budget);
			}
			assertThat(residency.dirtyCount()).isGreaterThan(0);
			int stillResident = 0;
			for (double[] c : results) {
				if (Gpu.resident(c)) {
					stillResident++;
					assertThat(c[777]).as("an unread lazy result is still on the device").isZero();
				}
				else {
					assertThat(c[777]).as("an evicted lazy result came home")
						.isCloseTo(Math.exp(a[777]), within(1e-12));
				}
			}
			// The LRU evicts CLEAN copies first -- the operand a, whose eviction costs
			// one upload -- and dirty ones, whose eviction costs a download, only when
			// no clean one is left: so what the cap holds is the last few results.
			assertThat(stillResident).isBetween(1, 4);
			// A release (and switching lazy results off) flushes what is left the same
			// way: nothing is lost, and device memory comes back.
			long before = gemm.freeDeviceMemory();
			Gpu.lazyResults(false);
			assertThat(residency.dirtyCount()).isZero();
			for (double[] c : results) {
				assertThat(c[777]).isCloseTo(Math.exp(a[777]), within(1e-12));
			}
			Gpu.releaseResident();
			assertThat(Gpu.residentBytes()).isZero();
			assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(DRIFT_BOUND);
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.residentBudget(-1);
			Gpu.releaseResident();
		}
	}

	// --- a lazy result allocates no host array (.todo/492)
	// ------------------------------

	/**
	 * A result STUB: the three-slot prefix a rank-2 caller keeps ahead of its elements.
	 */
	private static double[] stub(int rows, int cols) {
		return new double[] { 2, rows, cols };
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aStubResultAllocatesNoHostArrayUntilTheHostFirstReadsIt() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] a = new double[n];
		for (int i = 0; i < n; i++) {
			a[i] = (i % 97) / 100.0;
		}
		Gpu.lazyResults(true);
		try {
			// The result array handed over is the header alone; the library takes it,
			// allocates nothing on the host, and keeps the elements on the device.
			double[] c = stub(512, 512);
			int backed = residency.backingCount();
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 3, n)).isTrue();
			assertThat(c).hasSize(3);
			assertThat(residency.backingCount()).isEqualTo(backed);
			assertThat(residency.dirtyCount()).isEqualTo(1);
			assertThat(Gpu.resident(c)).isTrue();
			// A stub is a full operand to the next member -- its extent is the span it
			// was created with, not its Java length -- and the chain moves nothing.
			double[] e = stub(512, 512);
			long hits = residency.hits();
			assertThat(Gpu.map(Gpu.MAP_LOG, c, 3, e, 3, n)).isTrue();
			assertThat(residency.hits()).isEqualTo(hits + 1);
			assertThat(residency.dirtyCount()).isEqualTo(2);
			assertThat(residency.backingCount()).isEqualTo(backed);
			// The first host read allocates the BACKING -- the full span, the stub's
			// own prefix copied in -- and downloads into it; the stub itself stays what
			// it was, and is what the program keeps holding.
			Object storage = Gpu.materialize(e);
			assertThat(storage).isInstanceOf(double[].class).isNotSameAs(e);
			double[] backing = (double[]) storage;
			assertThat(backing).hasSize(3 + n);
			assertThat(backing[0]).isEqualTo(2.0);
			assertThat(backing[1]).isEqualTo(512.0);
			assertThat(backing[2]).isEqualTo(512.0);
			for (int i = 0; i < n; i += 997) {
				assertThat(backing[3 + i]).as("log(exp(a[%d]))", i).isCloseTo(a[i], within(1e-9));
			}
			assertThat(e).hasSize(3);
			assertThat(residency.dirtyCount()).isEqualTo(1);
			assertThat(residency.backingCount()).isEqualTo(backed + 1);
			// Every later read answers the same backing, and an ordinary array answers
			// itself.
			assertThat(Gpu.materialize(e)).isSameAs(backing);
			assertThat(Gpu.materialize(a)).isSameAs(a);
			assertThat(Gpu.materialize(new double[4])).isInstanceOf(double[].class);
			// The stub stays resident, clean, for the next member.
			assertThat(Gpu.resident(e)).isTrue();
			double[] f = stub(512, 512);
			hits = residency.hits();
			assertThat(Gpu.map(Gpu.MAP_EXP, e, 3, f, 3, n)).isTrue();
			assertThat(residency.hits()).isEqualTo(hits + 1);
			assertThat(((double[]) Gpu.materialize(f))[3 + 100]).isCloseTo(Math.exp(a[100]), within(1e-9));
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aWriteThroughAStubLandsInItsBackingAndTheStubIsUploadedFromIt() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] a = new double[n];
		Gpu.lazyResults(true);
		try {
			double[] c = stub(512, 512);
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 3, n)).isTrue();
			// The write hook answers the array the store must land in: the backing,
			// allocated and filled now, with the device copy then forgotten.
			double[] backing = (double[]) Gpu.written(c);
			assertThat(backing).hasSize(3 + n);
			assertThat(backing[3 + 100]).isEqualTo(1.0);
			assertThat(Gpu.resident(c)).isFalse();
			backing[3 + 100] = 2.0;
			assertThat(Gpu.written(c)).isSameAs(backing);
			// Offered again, the stub is uploaded from its backing -- the written value
			// is what the device reads -- and the answer follows the write.
			double[] d = stub(512, 512);
			long misses = residency.misses();
			assertThat(Gpu.map(Gpu.MAP_LOG, c, 3, d, 3, n)).isTrue();
			assertThat(residency.misses()).as("the written stub is uploaded again").isEqualTo(misses + 1);
			double[] out = (double[]) Gpu.materialize(d);
			assertThat(out[3 + 100]).isCloseTo(Math.log(2.0), within(1e-12));
			assertThat(out[3 + 99]).isZero();
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void anEvictedReleasedOrEagerStubIsDownloadedIntoABackingNotLost() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18; // 2 MB a buffer
		long budget = 8L << 20;
		Gpu.residentBudget(budget);
		Gpu.lazyResults(true);
		List<double[]> results = new ArrayList<>();
		try {
			double[] a = new double[n];
			for (int i = 0; i < n; i++) {
				a[i] = (i % 89) / 100.0;
			}
			// Thirty-two stub results against a budget that holds three: the cap evicts
			// by downloading, and what it evicts lands in a backing nobody asked for yet.
			for (int i = 0; i < 32; i++) {
				double[] c = stub(512, 512);
				results.add(c);
				assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 3, n)).isTrue();
				assertThat(Gpu.residentBytes()).as("after call %d", i).isLessThanOrEqualTo(budget);
			}
			assertThat(residency.dirtyCount()).isGreaterThan(0);
			assertThat(residency.backingCount()).isGreaterThan(20);
			int stillResident = 0;
			for (double[] c : results) {
				assertThat(c).hasSize(3);
				if (Gpu.resident(c)) {
					stillResident++;
				}
				assertThat(((double[]) Gpu.materialize(c))[3 + 777]).isCloseTo(Math.exp(a[777]), within(1e-12));
			}
			assertThat(stillResident).isBetween(1, 4);
			// Switching lazy results off flushes the rest into backings; a release keeps
			// them readable; and a stub handed over EAGERLY is filled into a backing
			// before the call returns.
			Gpu.lazyResults(false);
			assertThat(residency.dirtyCount()).isZero();
			Gpu.releaseResident();
			assertThat(Gpu.residentBytes()).isZero();
			for (double[] c : results) {
				assertThat(((double[]) Gpu.materialize(c))[3 + 777]).isCloseTo(Math.exp(a[777]), within(1e-12));
			}
			double[] eager = stub(512, 512);
			int backed = residency.backingCount();
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, eager, 3, n)).isTrue();
			assertThat(residency.backingCount()).isEqualTo(backed + 1);
			assertThat(residency.dirtyCount()).isZero();
			assertThat(((double[]) Gpu.materialize(eager))[3 + 777]).isCloseTo(Math.exp(a[777]), within(1e-12));
			// A result array that is neither full nor exactly the prefix is a caller's
			// mistake and declines.
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new double[10], 3, n)).isFalse();
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.residentBudget(-1);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aCollectedStubTakesItsBackingWithIt() throws InterruptedException {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 18;
		double[] a = new double[n];
		Gpu.lazyResults(true);
		try {
			double[] keep = stub(512, 512);
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, keep, 3, n)).isTrue();
			double[] keepBacking = (double[]) Gpu.materialize(keep);
			// Settle what earlier tests left unreachable before counting.
			for (int attempt = 0; attempt < 5; attempt++) {
				System.gc();
				Thread.sleep(20);
			}
			int backed = residency.backingCount();
			for (int i = 0; i < 8; i++) {
				double[] c = stub(512, 512);
				assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 3, n)).isTrue();
				Gpu.materialize(c);
			}
			assertThat(residency.backingCount()).isEqualTo(backed + 8);
			// The read-side ring remembers the last few arrays it answered for (strongly,
			// as it always has); push the stubs out of it so nothing but this frame holds
			// them.
			for (int i = 0; i < 4; i++) {
				Gpu.materialize(new double[8]);
			}
			// Eight stubs nobody can reach: once the collector has them, their backings
			// go with them, and the one still held keeps its backing.
			int after = -1;
			for (int attempt = 0; attempt < 20 && after != backed; attempt++) {
				System.gc();
				Thread.sleep(20);
				after = residency.backingCount();
			}
			assertThat(after).isEqualTo(backed);
			assertThat(Gpu.materialize(keep)).isSameAs(keepBacking);
			assertThat(keepBacking[3 + 5]).isEqualTo(1.0);
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aDeviceMemberUpdatingAnArrayInPlaceLeavesItResidentAndAuthoritative() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "lazy results are the CUDA backend's");
		Gpu.releaseResident();
		int n = (int) Gpu.rngMinElements() * 4;
		double[] out = new double[n];
		Gpu.lazyResults(true);
		try {
			// A fill over a fresh array: lazily it is a dirty resident copy ...
			assertThat(Gpu.rngFill(out, 0, n, 0, 0.0, 1.0, 11, 22, 33)).isTrue();
			assertThat(out[5]).isZero();
			assertThat(residency.dirtyCount()).isEqualTo(1);
			// ... a second fill over the SAME array reuses the buffer in place ...
			long resident = Gpu.residentBytes();
			assertThat(Gpu.rngFill(out, 0, n, 0, 0.0, 1.0, 44, 55, 66)).isTrue();
			assertThat(Gpu.residentBytes()).isEqualTo(resident);
			assertThat(residency.dirtyCount()).isEqualTo(1);
			// ... and the host sees the SECOND fill when it reads.
			double[] expected = new double[n];
			Gpu.lazyResults(false);
			assertThat(Gpu.rngFill(expected, 0, n, 0, 0.0, 1.0, 44, 55, 66)).isTrue();
			Gpu.materialize(out);
			assertThat(out).isEqualTo(expected);
		}
		finally {
			Gpu.lazyResults(false);
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "the resident tier is the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 15;
		float[] a = new float[n], b = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = (float) Math.sin(i * 0.37) * 3;
			b[i] = (float) Math.cos(i * 0.11) + 0.01f;
		}
		float[] out = new float[n];
		// Nothing resident: every member of the tier declines, at any size -- a round
		// trip cannot beat the caller's lane loop, and the library does not try.
		assertThat(Gpu.zip(Gpu.BIN_ADD, a, 0, b, 0, out, 0, n)).isFalse();
		assertThat(Gpu.scale(Gpu.BIN_DIV, a, 0, 8.0, false, out, 0, n)).isFalse();
		assertThat(Gpu.map(Gpu.MAP_SQRT, b, 0, out, 0, n)).isFalse();
		assertThat(Gpu.where(a, 0, new int[] { 1 }, 0.0, b, 0, new int[] { 1 }, 0.0, null, 0, new int[] { 0 }, -1.0,
				out, 0, new int[] { n }))
			.isFalse();
		float[] x = a.clone(), m = new float[n], v = new float[n];
		double[] rule = { 0.01, 0.001, 0.1, 0.9, 0.1, 0.999, 0.001, 1e-8, 0.19, 0.001999, 2.0 };
		assertThat(Gpu.adamStep(x, 0, b, 0, m, 0, v, 0, n, rule)).isFalse();
		assertThat(x).isEqualTo(a);
		// Make a resident (a libm member over it), and every member is a launch that
		// reads the device's copy. Each computes in double and narrows on the store,
		// which is the CPU kernels' rule, so the comparison is EQUALITY.
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new float[n], 0, n)).isTrue();
		assertThat(Gpu.resident(a)).isTrue();
		int[] binary = { Gpu.BIN_ADD, Gpu.BIN_SUB, Gpu.BIN_MUL, Gpu.BIN_DIV, Gpu.BIN_MAX, Gpu.BIN_MIN, Gpu.BIN_GT,
				Gpu.BIN_GE, Gpu.BIN_LT, Gpu.BIN_LE, Gpu.BIN_EQ };
		assertThat(binary).hasSize(Gpu.BIN_OPS);
		for (int op : binary) {
			assertThat(Gpu.zip(op, a, 0, b, 0, out, 0, n)).as("zip %d", op).isTrue();
			for (int i = 0; i < n; i += 13) {
				assertThat(out[i]).as("zip %d at %d", op, i).isEqualTo((float) apply(op, a[i], b[i]));
			}
			// The other operand resident is enough too (b became one as an operand).
			assertThat(Gpu.zip(op, b, 0, a, 0, out, 0, n)).as("zip %d swapped", op).isTrue();
			for (int i = 0; i < n; i += 13) {
				assertThat(out[i]).as("zip %d swapped at %d", op, i).isEqualTo((float) apply(op, b[i], a[i]));
			}
			assertThat(Gpu.scale(op, a, 0, 0.3, false, out, 0, n)).as("scale %d", op).isTrue();
			for (int i = 0; i < n; i += 13) {
				assertThat(out[i]).as("scale %d at %d", op, i).isEqualTo((float) apply(op, a[i], 0.3));
			}
			assertThat(Gpu.scale(op, a, 0, 0.3, true, out, 0, n)).as("scale %d swapped", op).isTrue();
			for (int i = 0; i < n; i += 13) {
				assertThat(out[i]).as("scale %d swapped at %d", op, i).isEqualTo((float) apply(op, 0.3, a[i]));
			}
		}
		// where over a broadcast mask of the OTHER width and a scalar y.
		int rows = 64, cols = n / rows;
		double[] mask = new double[cols];
		for (int j = 0; j < cols; j++) {
			mask[j] = j % 3 == 0 ? 0.0 : 1.0;
		}
		assertThat(Gpu.where(mask, 0, new int[] { 0, 1 }, 0.0, a, 0, new int[] { cols, 1 }, 0.0, null, 0,
				new int[] { 0, 0 }, -9.5, out, 0, new int[] { rows, cols }))
			.isTrue();
		for (int i = 0; i < n; i += 7) {
			assertThat(out[i]).isEqualTo(mask[i % cols] == 0.0 ? -9.5f : a[i]);
		}
		// A scalar mask and a scalar x.
		assertThat(Gpu.where(null, 0, new int[] { 0 }, 1.0, null, 0, new int[] { 0 }, 2.5, a, 0, new int[] { 1 }, 0.0,
				out, 0, new int[] { n }))
			.isTrue();
		assertThat(out[17]).isEqualTo(2.5f);
		// The Adam update over a resident gradient, against the CPU kernel's arithmetic.
		float[] xr = a.clone(), mr = new float[n], vr = new float[n];
		for (int i = 0; i < n; i++) {
			mr[i] = b[i] * 0.5f;
			vr[i] = Math.abs(b[i]);
		}
		float[] xe = xr.clone(), me = mr.clone(), ve = vr.clone();
		for (int mode = 0; mode <= 2; mode++) {
			rule[10] = mode;
			assertThat(Gpu.adamStep(xr, 0, b, 0, mr, 0, vr, 0, n, rule)).as("mode %d", mode).isTrue();
			adamReference(xe, b, me, ve, rule);
			assertThat(xr).as("x, mode %d", mode).isEqualTo(xe);
			assertThat(mr).as("m, mode %d", mode).isEqualTo(me);
			assertThat(vr).as("v, mode %d", mode).isEqualTo(ve);
		}
		// At double width the same, over a resident double operand.
		double[] ad = new double[n], bd = new double[n], od = new double[n];
		for (int i = 0; i < n; i++) {
			ad[i] = a[i];
			bd[i] = b[i];
		}
		assertThat(Gpu.map(Gpu.MAP_EXP, ad, 0, new double[n], 0, n)).isTrue();
		assertThat(Gpu.zip(Gpu.BIN_DIV, ad, 0, bd, 0, od, 0, n)).isTrue();
		for (int i = 0; i < n; i += 13) {
			assertThat(od[i]).isEqualTo(ad[i] / bd[i]);
		}
		assertThat(Gpu.scale(Gpu.BIN_SUB, ad, 0, 0.125, true, od, 0, n)).isTrue();
		assertThat(od[9]).isEqualTo(0.125 - ad[9]);
		assertThat(Gpu.map(Gpu.MAP_SQRT, ad, 0, od, 0, n)).isTrue();
		for (int i = 0; i < n; i += 13) {
			// Bit for bit, NaN (a negative operand) included.
			assertThat(Double.doubleToRawLongBits(od[i])).isEqualTo(Double.doubleToRawLongBits(Math.sqrt(ad[i])));
		}
	}

	/** {@code laApply}: the CPU kernels' binary op table, in double. */
	private static double apply(int op, double x, double y) {
		return switch (op) {
			case Gpu.BIN_ADD -> x + y;
			case Gpu.BIN_SUB -> x - y;
			case Gpu.BIN_MUL -> x * y;
			case Gpu.BIN_DIV -> x / y;
			case Gpu.BIN_MAX -> x > y ? x : y;
			case Gpu.BIN_MIN -> x < y ? x : y;
			case Gpu.BIN_GT -> x > y ? 1.0 : 0.0;
			case Gpu.BIN_GE -> x >= y ? 1.0 : 0.0;
			case Gpu.BIN_LT -> x < y ? 1.0 : 0.0;
			case Gpu.BIN_LE -> x <= y ? 1.0 : 0.0;
			default -> x == y ? 1.0 : 0.0;
		};
	}

	/** {@code laAdamStep}'s single-float arithmetic, verbatim. */
	private static void adamReference(float[] xa, float[] ga, float[] ma, float[] va, double[] ps) {
		double lr = ps[0], lrwd = ps[1], wd = ps[2], beta1 = ps[3], omb1 = ps[4], beta2 = ps[5], omb2 = ps[6],
				eps = ps[7], corr1 = ps[8], corr2 = ps[9];
		int mode = (int) ps[10];
		for (int k = 0; k < xa.length; k++) {
			double x0 = xa[k];
			double xv = mode == 2 ? x0 - lrwd * x0 : x0;
			double gv = mode == 1 ? ga[k] + wd * x0 : ga[k];
			double mk = beta1 * ma[k] + omb1 * gv;
			double vk = beta2 * va[k] + omb2 * gv * gv;
			ma[k] = (float) mk;
			va[k] = (float) vk;
			xa[k] = (float) (xv - lr * (mk / corr1) / (Math.sqrt(vk / corr2) + eps));
		}
	}

	@Test
	void everyResidentTierDeclineConditionStillDeclinesWithADevicePresent() {
		Gpu.releaseResident();
		int n = 1 << 14;
		double[] a = new double[n], b = new double[n], out = new double[n];
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new double[n], 0, n)).isTrue();
		assertThat(Gpu.resident(a)).isTrue();
		// An op the library does not name, and elements outside their arrays.
		assertThat(Gpu.zip(Gpu.BIN_OPS, a, 0, b, 0, out, 0, n)).isFalse();
		assertThat(Gpu.zip(-1, a, 0, b, 0, out, 0, n)).isFalse();
		assertThat(Gpu.zip(Gpu.BIN_ADD, a, 1, b, 0, out, 0, n)).isFalse();
		assertThat(Gpu.zip(Gpu.BIN_ADD, a, 0, b, 0, new double[8], 0, n)).isFalse();
		assertThat(Gpu.scale(Gpu.BIN_OPS, a, 0, 1.0, false, out, 0, n)).isFalse();
		assertThat(Gpu.scale(Gpu.BIN_ADD, a, 0, 1.0, false, out, 1, n)).isFalse();
		// where: no array at all, a span outside an array, a scalar-only call.
		assertThat(Gpu.where(null, 0, new int[] { 0 }, 1.0, null, 0, new int[] { 0 }, 1.0, null, 0, new int[] { 0 },
				2.0, out, 0, new int[] { n }))
			.isFalse();
		assertThat(Gpu.where(a, 1, new int[] { 1 }, 0.0, b, 0, new int[] { 1 }, 0.0, null, 0, new int[] { 0 }, 0.0, out,
				0, new int[] { n }))
			.isFalse();
		// adam: a malformed rule, a mode it does not name, a short array.
		double[] rule = { 0.01, 0.001, 0.1, 0.9, 0.1, 0.999, 0.001, 1e-8, 0.19, 0.001999, 0.0 };
		assertThat(Gpu.adamStep(a, 0, b, 0, out, 0, new double[n], 0, n, new double[10])).isFalse();
		double[] badMode = rule.clone();
		badMode[10] = 3.0;
		assertThat(Gpu.adamStep(a, 0, b, 0, out, 0, new double[n], 0, n, badMode)).isFalse();
		assertThat(Gpu.adamStep(a, 0, b, 0, out, 0, new double[8], 0, n, rule)).isFalse();
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace() {
		DeviceResidency residency = Gpu.residency();
		assumeTrue(residency != null, "the resident tier is the CUDA backend's");
		Gpu.releaseResident();
		// Over the map threshold, so the exp below really makes a resident.
		int rows = 128, cols = 192, n = rows * cols;
		float[] a = new float[3 + n];
		a[0] = 2;
		a[1] = rows;
		a[2] = cols;
		for (int i = 0; i < n; i++) {
			a[3 + i] = (float) Math.sin(i * 0.13) * 7;
		}
		int[] spanA = { 3, n };
		// Not resident: declined, whatever the walk.
		float[] out = new float[3 + n];
		assertThat(Gpu.copy(a, 3, new int[] { 1 }, spanA, out, 3, new int[] { 1 }, new int[] { 3, n }, new int[] { n }))
			.isFalse();
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 3, new float[3 + n], 3, n)).isTrue();
		// reshape: one contiguous walk.
		assertThat(Gpu.copy(a, 3, new int[] { 1 }, spanA, out, 3, new int[] { 1 }, new int[] { 3, n }, new int[] { n }))
			.isTrue();
		assertThat(java.util.Arrays.copyOfRange(out, 3, 3 + n)).isEqualTo(java.util.Arrays.copyOfRange(a, 3, 3 + n));
		// transpose: out[j][i] = a[i][j].
		float[] t = new float[3 + n];
		assertThat(Gpu.copy(a, 3, new int[] { 1, cols }, spanA, t, 3, new int[] { rows, 1 }, new int[] { 3, n },
				new int[] { cols, rows }))
			.isTrue();
		for (int i = 0; i < rows; i += 7) {
			for (int j = 0; j < cols; j += 5) {
				assertThat(t[3 + j * rows + i]).isEqualTo(a[3 + i * cols + j]);
			}
		}
		// a slice with a NEGATIVE step: rows 80..20 step -3, columns 10..60 step 2.
		int sr = (80 - 20 + 2) / 3, sc = (60 - 10 + 1) / 2;
		float[] sl = new float[3 + sr * sc];
		assertThat(Gpu.copy(a, 3 + 80 * cols + 10, new int[] { -3 * cols, 2 }, spanA, sl, 3, new int[] { sc, 1 },
				new int[] { 3, sr * sc }, new int[] { sr, sc }))
			.isTrue();
		for (int i = 0; i < sr; i++) {
			for (int j = 0; j < sc; j++) {
				assertThat(sl[3 + i * sc + j]).isEqualTo(a[3 + (80 - 3 * i) * cols + 10 + 2 * j]);
			}
		}
		// a walk that leaves the span declines, on either side.
		assertThat(Gpu.copy(a, 3 + 80 * cols + 10, new int[] { -3 * cols, 2 }, spanA, sl, 3, new int[] { sc, 1 },
				new int[] { 3, sr * sc - 1 }, new int[] { sr, sc }))
			.isFalse();
		assertThat(
				Gpu.copy(a, 3, new int[] { -1 }, spanA, out, 3, new int[] { 1 }, new int[] { 3, n }, new int[] { 2 }))
			.isFalse();
		// concatenate: two halves of a into out's two slabs -- the second copy finds the
		// output resident and writes into it in place.
		float[] cat = new float[3 + n];
		int half = rows / 2;
		long resident = Gpu.residentBytes();
		assertThat(Gpu.copy(a, 3, new int[] { cols, 1 }, spanA, cat, 3, new int[] { cols, 1 }, new int[] { 3, n },
				new int[] { half, cols }))
			.isTrue();
		assertThat(Gpu.copy(a, 3 + half * cols, new int[] { cols, 1 }, spanA, cat, 3 + half * cols,
				new int[] { cols, 1 }, new int[] { 3, n }, new int[] { half, cols }))
			.isTrue();
		assertThat(Gpu.residentBytes()).isEqualTo(resident + (long) n * Float.BYTES);
		assertThat(java.util.Arrays.copyOfRange(cat, 3, 3 + n)).isEqualTo(java.util.Arrays.copyOfRange(a, 3, 3 + n));
		// The in-place scale over the resident a: the same buffer, marked, and the host
		// sees the product.
		resident = Gpu.residentBytes();
		float[] expected = a.clone();
		for (int i = 0; i < n; i++) {
			expected[3 + i] = (float) ((double) a[3 + i] * 0.25);
		}
		assertThat(Gpu.scale(Gpu.BIN_MUL, a, 3, 0.25, false, a, 3, n)).isTrue();
		assertThat(Gpu.residentBytes()).isEqualTo(resident);
		assertThat(a).isEqualTo(expected);
	}

	/**
	 * The INDEX tier: an embedding lookup, a per-row pick and the scatter-add adjoint,
	 * each offered only over a resident operand and each BIT-IDENTICAL to the CPU kernel
	 * it replaces -- so the comparison is equality, and the scatter's indices REPEAT,
	 * which is where the order it keeps can be seen.
	 */
	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theIndexTierIsOfferedOnlyOverAResidentOperandAndCopiesTheCpuKernelsBits() {
		assumeTrue(Gpu.residency() != null, "the index tier is the CUDA backend's");
		Gpu.releaseResident();
		int rows = 97, slab = 384, m = 1024;
		float[] table = new float[rows * slab];
		for (int i = 0; i < table.length; i++) {
			table[i] = (float) Math.sin(i * 0.013) * 4;
		}
		int[] idx = new int[m];
		for (int i = 0; i < m; i++) {
			idx[i] = (i * 37 + i / 7) % rows;
		}
		float[] out = new float[m * slab];
		// Nothing resident: the whole tier declines, at any size.
		assertThat(Gpu.takeRows(table, 0, table.length, out, 0, idx, slab)).isFalse();
		assertThat(Gpu.pick(table, 0, new float[rows], 0, new int[rows], slab)).isFalse();
		assertThat(Gpu.scatterRows(new float[rows * slab], 0, out, 0, idx, rows, slab)).isFalse();
		assertThat(Gpu.sumSquares(table, 0, table.length, 0.0)).isNull();
		// Resident, and every member is a launch with no copy up.
		assertThat(Gpu.map(Gpu.MAP_EXP, table, 0, new float[table.length], 0, table.length)).isTrue();
		assertThat(Gpu.resident(table)).isTrue();
		assertThat(Gpu.takeRows(table, 0, table.length, out, 0, idx, slab)).isTrue();
		for (int i = 0; i < m; i++) {
			for (int k = 0; k < slab; k += 61) {
				assertThat(out[i * slab + k]).as("take %d,%d", i, k).isEqualTo(table[idx[i] * slab + k]);
			}
		}
		// The per-row pick, over the same table read as an m x slab matrix.
		int[] cols = new int[rows];
		for (int i = 0; i < rows; i++) {
			cols[i] = (i * 53) % slab;
		}
		float[] picked = new float[rows];
		assertThat(Gpu.pick(table, 0, picked, 0, cols, slab)).isTrue();
		for (int i = 0; i < rows; i++) {
			assertThat(picked[i]).as("pick %d", i).isEqualTo(table[i * slab + cols[i]]);
		}
		// The scatter-add: the CPU kernel's own loop is the oracle, and every one of the
		// 97 destinations is hit ten times or so, so the order is what is being asserted.
		float[] z = new float[rows * slab], oracle = new float[rows * slab];
		for (int i = 0; i < z.length; i++) {
			z[i] = (float) Math.cos(i * 0.021);
			oracle[i] = z[i];
		}
		for (int i = 0; i < m; i++) {
			for (int k = 0; k < slab; k++) {
				int d = idx[i] * slab + k;
				oracle[d] = (float) ((double) oracle[d] + (double) out[i * slab + k]);
			}
		}
		assertThat(Gpu.resident(out)).isTrue();
		assertThat(Gpu.scatterRows(z, 0, out, 0, idx, rows, slab)).isTrue();
		assertThat(z).isEqualTo(oracle);
		// An index outside the table declines rather than reading off the end.
		int[] bad = idx.clone();
		bad[3] = rows;
		assertThat(Gpu.takeRows(table, 0, table.length, out, 0, bad, slab)).isFalse();
		assertThat(Gpu.scatterRows(z, 0, out, 0, bad, rows, slab)).isFalse();
		assertThat(Gpu.pick(table, 0, picked, 0, new int[] { slab }, slab)).isFalse();
	}

	@Test
	void aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiplyAtBothWidths() {
		// The scale kernel computes in double and narrows on the store, and a double
		// DIVIDE is the one arithmetic operation this card is slow at (measured: 28% of
		// a scale pass at the attention score's shape, .kb/gpu.md). Dividing by a power
		// of two is exactly multiplying by its reciprocal -- two correct roundings of the
		// same real number -- so Gpu.scale launches the multiply instead. Bit-identity is
		// the whole licence for that, and it is what this asserts: every divisor below,
		// power of two or not, element by element against the CPU's own divide, over
		// operands that include a subnormal, an infinity and a negative zero.
		int n = 1 << 14;
		double[] a = new double[n];
		float[] af = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = switch (i) {
				case 0 -> Double.MIN_VALUE;
				case 1 -> Double.POSITIVE_INFINITY;
				case 2 -> -0.0;
				default -> (i - n / 2) * 0.37;
			};
			af[i] = (float) a[i];
		}
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new double[n], 0, n)).isTrue();
		assertThat(Gpu.map(Gpu.MAP_EXP, af, 0, new float[n], 0, n)).isTrue();
		double[] out = new double[n];
		float[] outf = new float[n];
		for (double s : new double[] { 8.0, 0.125, 1.0, -4.0, 1024.0, 3.0, 1.4142135623730951, 0.1 }) {
			assertThat(Gpu.scale(Gpu.BIN_DIV, a, 0, s, false, out, 0, n)).as("div %s", s).isTrue();
			assertThat(Gpu.scale(Gpu.BIN_DIV, af, 0, s, false, outf, 0, n)).as("div %s at #f", s).isTrue();
			for (int i = 0; i < n; i++) {
				assertThat(out[i]).as("div %s at %d", s, i).isEqualTo(a[i] / s);
				assertThat(outf[i]).as("div %s at %d at #f", s, i).isEqualTo((float) (af[i] / s));
			}
			// The SWAPPED form is s / a[i], which no reciprocal rewrites.
			assertThat(Gpu.scale(Gpu.BIN_DIV, a, 0, s, true, out, 0, n)).as("div swapped %s", s).isTrue();
			for (int i = 0; i < n; i++) {
				assertThat(out[i]).as("div swapped %s at %d", s, i).isEqualTo(s / a[i]);
			}
		}
	}

	// --- the fused tier (.todo/499) --------------------------------------------------

	/**
	 * The chain of device members a fused kernel replaces, run member by member at one
	 * width, over arrays the earlier members left resident (the resident tier's zip and
	 * scale need that). Every step asserts it was taken.
	 */
	private static final class Chain {

		final boolean single;

		Chain(boolean single) {
			this.single = single;
		}

		Object fresh(int n) {
			return this.single ? new float[n] : new double[n];
		}

		int n(Object a) {
			return this.single ? ((float[]) a).length : ((double[]) a).length;
		}

		Object map(int op, Object a) {
			Object c = fresh(n(a));
			assertThat(this.single ? Gpu.map(op, (float[]) a, 0, (float[]) c, 0, n(a))
					: Gpu.map(op, (double[]) a, 0, (double[]) c, 0, n(a)))
				.as("map %d", op)
				.isTrue();
			return c;
		}

		Object zip(int op, Object a, Object b) {
			Object c = fresh(n(a));
			assertThat(this.single ? Gpu.zip(op, (float[]) a, 0, (float[]) b, 0, (float[]) c, 0, n(a))
					: Gpu.zip(op, (double[]) a, 0, (double[]) b, 0, (double[]) c, 0, n(a)))
				.as("zip %d", op)
				.isTrue();
			return c;
		}

		Object scale(int op, Object a, double s, boolean swap) {
			Object c = fresh(n(a));
			assertThat(this.single ? Gpu.scale(op, (float[]) a, 0, s, swap, (float[]) c, 0, n(a))
					: Gpu.scale(op, (double[]) a, 0, s, swap, (double[]) c, 0, n(a)))
				.as("scale %d", op)
				.isTrue();
			return c;
		}

		/** {@code a}, {@code rows x len}, folded over its last axis. */
		Object fold(int op, Object a, int rows, int len) {
			Object c = fresh(rows);
			assertThat(this.single ? Gpu.fold(op, (float[]) a, 0, (float[]) c, 0, rows, len, 1)
					: Gpu.fold(op, (double[]) a, 0, (double[]) c, 0, rows, len, 1))
				.as("fold %d", op)
				.isTrue();
			return c;
		}

		/** {@code a}, {@code rows x len}, against the per-row {@code b}, broadcast. */
		Object bcast(int op, Object a, Object b, int rows, int len) {
			Object c = fresh(rows * len);
			int[] dims = { rows, len }, sa = { len, 1 }, sb = { 1, 0 };
			assertThat(this.single ? Gpu.bcast(op, (float[]) a, 0, sa, (float[]) b, 0, sb, (float[]) c, 0, dims)
					: Gpu.bcast(op, (double[]) a, 0, sa, (double[]) b, 0, sb, (double[]) c, 0, dims))
				.as("bcast %d", op)
				.isTrue();
			return c;
		}

		/** {@code a}, {@code rows x len}, against the per-COLUMN {@code b}, broadcast. */
		Object bcastCols(int op, Object a, Object b, int rows, int len) {
			Object c = fresh(rows * len);
			int[] dims = { rows, len }, sa = { len, 1 }, sb = { 0, 1 };
			assertThat(this.single ? Gpu.bcast(op, (float[]) a, 0, sa, (float[]) b, 0, sb, (float[]) c, 0, dims)
					: Gpu.bcast(op, (double[]) a, 0, sa, (double[]) b, 0, sb, (double[]) c, 0, dims))
				.as("bcast cols %d", op)
				.isTrue();
			return c;
		}

		double[] doubles(Object a) {
			if (!this.single) {
				return (double[]) a;
			}
			return widen((float[]) a);
		}

	}

	/**
	 * Bit equality of two arrays, reported at the first differing index. AssertJ's
	 * {@code containsExactly} takes seventeen seconds over 262144 elements (measured),
	 * which is what this loop is for.
	 */
	private static void assertSameBits(String what, double[] actual, double[] expected) {
		assertThat(actual.length).as(what + " length").isEqualTo(expected.length);
		for (int i = 0; i < actual.length; i++) {
			if (Double.doubleToRawLongBits(actual[i]) != Double.doubleToRawLongBits(expected[i])) {
				assertThat(actual[i]).as(what + " at " + i).isEqualTo(expected[i]);
			}
		}
	}

	/**
	 * The width's member boundary: a chain at {@code #f} stores every member as float.
	 */
	private static double nr(double v, boolean single) {
		return single ? (float) v : v;
	}

	private static double[] widen(float[] f) {
		double[] d = new double[f.length];
		for (int i = 0; i < f.length; i++) {
			d[i] = f[i];
		}
		return d;
	}

	@Test
	void theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths() {
		// The whole claim of the tier: a fused kernel IS the chain of device members it
		// replaces, rounding for rounding -- so its result is that chain's bit for bit,
		// libm members included (kernel and chain call the same erf and exp at the same
		// width). Each composition runs twice, member by member and fused.
		int rows = 1024, len = 256, n = rows * len;
		double eps = 1.0e-5;
		for (boolean single : new boolean[] { false, true }) {
			Chain ch = new Chain(single);
			Random random = new Random(11);
			Object x = ch.fresh(n), g = ch.fresh(n), old = ch.fresh(n), zeros = ch.fresh(n);
			for (int i = 0; i < n; i++) {
				double xv = random.nextDouble() * 6 - 3, gv = random.nextDouble() * 2 - 1, ov = random.nextDouble();
				if (single) {
					((float[]) x)[i] = (float) xv;
					((float[]) g)[i] = (float) gv;
					((float[]) old)[i] = (float) ov;
				}
				else {
					((double[]) x)[i] = xv;
					((double[]) g)[i] = gv;
					((double[]) old)[i] = ov;
				}
			}
			// softmax: amax, sub, exp, sum, div.
			Object m = ch.fold(Gpu.FOLD_AMAX, x, rows, len);
			Object e = ch.map(Gpu.MAP_EXP, ch.bcast(Gpu.BIN_SUB, x, m, rows, len));
			Object out = ch.bcast(Gpu.BIN_DIV, e, ch.fold(Gpu.FOLD_SUM, e, rows, len), rows, len);
			Object fused = ch.fresh(n);
			assertThat(single ? Gpu.softmax((float[]) x, 0, (float[]) fused, 0, rows, len)
					: Gpu.softmax((double[]) x, 0, (double[]) fused, 0, rows, len))
				.isTrue();
			assertThat(ch.doubles(fused)).as("softmax single=%s", single).containsExactly(ch.doubles(out));
			// its adjoint: mul, sum, sub, mul.
			Object tot = ch.fold(Gpu.FOLD_SUM, ch.zip(Gpu.BIN_MUL, g, out), rows, len);
			Object dx = ch.zip(Gpu.BIN_MUL, out, ch.bcast(Gpu.BIN_SUB, g, tot, rows, len));
			fused = ch.fresh(n);
			assertThat(single ? Gpu.softmaxGrad((float[]) g, 0, (float[]) out, 0, (float[]) fused, 0, rows, len)
					: Gpu.softmaxGrad((double[]) g, 0, (double[]) out, 0, (double[]) fused, 0, rows, len))
				.isTrue();
			assertThat(ch.doubles(fused)).as("softmax grad single=%s", single).containsExactly(ch.doubles(dx));
			// The attention head's scaled and masked softmax (2026-09-02): the scale
			// (a divide, and a divide by a power of two, which is the multiply), the
			// mask's select on the host (a select is exact) and the softmax pair, against
			// the fused pair with the two folded in -- with the mask at either width and
			// a
			// -infinity fill, which is what the causal mask fills with.
			for (double sc : new double[] { 3.0, 8.0 }) {
				for (int mk = 0; mk < 2; mk++) {
					int maskLen = len * 4;
					double[] maskD = new double[maskLen];
					float[] maskF = new float[maskLen];
					for (int i = 0; i < maskLen; i++) {
						maskD[i] = (i * 7) % 5 == 0 ? 1.0 : 0.0;
						maskF[i] = (float) maskD[i];
					}
					Object mask = mk == 0 ? maskD : maskF;
					double fill = mk == 0 ? Double.NEGATIVE_INFINITY : -5.0;
					Object sc1 = ch.scale(Gpu.BIN_DIV, x, sc, false);
					Object masked = ch.fresh(n);
					for (int i = 0; i < n; i++) {
						boolean hit = maskD[i % maskLen] != 0.0;
						if (single) {
							((float[]) masked)[i] = hit ? (float) fill : ((float[]) sc1)[i];
						}
						else {
							((double[]) masked)[i] = hit ? fill : ((double[]) sc1)[i];
						}
					}
					Object mout = ch.fresh(n);
					assertThat(single ? Gpu.softmax((float[]) masked, 0, (float[]) mout, 0, rows, len)
							: Gpu.softmax((double[]) masked, 0, (double[]) mout, 0, rows, len))
						.isTrue();
					fused = ch.fresh(n);
					assertThat(single
							? Gpu.softmax((float[]) x, 0, mask, 0, maskLen, (float[]) fused, 0, rows, len, Gpu.BIN_DIV,
									sc, fill)
							: Gpu.softmax((double[]) x, 0, mask, 0, maskLen, (double[]) fused, 0, rows, len,
									Gpu.BIN_DIV, sc, fill))
						.isTrue();
					assertSameBits("scaled masked softmax single=" + single + " scale=" + sc + " mask=" + mk,
							ch.doubles(fused), ch.doubles(mout));
					// its adjoint: the softmax adjoint, zero under the mask, the divide
					// -- the
					// divide taken on the device BEFORE the host zeroes (a zero divided
					// by a
					// positive scale is the same zero, and a host write to a resident
					// array
					// would have to be reported and would lose the residency the scalar
					// tier is offered over).
					Object mdx = ch.fresh(n);
					assertThat(single ? Gpu.softmaxGrad((float[]) g, 0, (float[]) mout, 0, (float[]) mdx, 0, rows, len)
							: Gpu.softmaxGrad((double[]) g, 0, (double[]) mout, 0, (double[]) mdx, 0, rows, len))
						.isTrue();
					Object mdx2 = ch.scale(Gpu.BIN_DIV, mdx, sc, false);
					for (int i = 0; i < n; i++) {
						if (maskD[i % maskLen] != 0.0) {
							if (single) {
								((float[]) mdx2)[i] = 0.0f;
							}
							else {
								((double[]) mdx2)[i] = 0.0;
							}
						}
					}
					fused = ch.fresh(n);
					assertThat(single
							? Gpu.softmaxGrad((float[]) g, 0, (float[]) mout, 0, mask, 0, maskLen, (float[]) fused, 0,
									rows, len, Gpu.BIN_DIV, sc)
							: Gpu.softmaxGrad((double[]) g, 0, (double[]) mout, 0, mask, 0, maskLen, (double[]) fused,
									0, rows, len, Gpu.BIN_DIV, sc))
						.isTrue();
					assertSameBits("scaled masked softmax grad single=" + single + " scale=" + sc + " mask=" + mk,
							ch.doubles(fused), ch.doubles(mdx2));
				}
			}
			// log-softmax: amax, sub, exp, sum, log, sub -- the deviation recomputed in
			// the kernel's third pass rather than stored, which is the same (T) value.
			Object s = ch.bcast(Gpu.BIN_SUB, x, m, rows, len);
			Object lg = ch.map(Gpu.MAP_LOG, ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_EXP, s), rows, len));
			Object lout = ch.bcast(Gpu.BIN_SUB, s, lg, rows, len);
			fused = ch.fresh(n);
			assertThat(single ? Gpu.logSoftmax((float[]) x, 0, (float[]) fused, 0, rows, len)
					: Gpu.logSoftmax((double[]) x, 0, (double[]) fused, 0, rows, len))
				.isTrue();
			assertThat(ch.doubles(fused)).as("log-softmax single=%s", single).containsExactly(ch.doubles(lout));
			// its adjoint: sum, exp, mul, sub.
			Object ldx = ch.zip(Gpu.BIN_SUB, g,
					ch.bcast(Gpu.BIN_MUL, ch.map(Gpu.MAP_EXP, lout), ch.fold(Gpu.FOLD_SUM, g, rows, len), rows, len));
			fused = ch.fresh(n);
			assertThat(single ? Gpu.logSoftmaxGrad((float[]) g, 0, (float[]) lout, 0, (float[]) fused, 0, rows, len)
					: Gpu.logSoftmaxGrad((double[]) g, 0, (double[]) lout, 0, (double[]) fused, 0, rows, len))
				.isTrue();
			assertThat(ch.doubles(fused)).as("log-softmax grad single=%s", single).containsExactly(ch.doubles(ldx));
			// gelu: mul 0.5, div sqrt 2, erf, 1 + , mul.
			Object t1 = ch.scale(Gpu.BIN_MUL, x, 0.5, false);
			Object t2 = ch.scale(Gpu.BIN_DIV, x, 1.4142135623730951, false);
			Object t4 = ch.scale(Gpu.BIN_ADD, ch.map(Gpu.MAP_ERF, t2), 1.0, true);
			Object gelu = ch.zip(Gpu.BIN_MUL, t1, t4);
			fused = ch.fresh(n);
			assertThat(single ? Gpu.gelu((float[]) x, 0, (float[]) fused, 0, n)
					: Gpu.gelu((double[]) x, 0, (double[]) fused, 0, n))
				.isTrue();
			assertThat(ch.doubles(fused)).as("gelu single=%s", single).containsExactly(ch.doubles(gelu));
			// its adjoint, with and without an accumulated gradient to fold onto.
			Object g1 = ch.zip(Gpu.BIN_MUL, g, t4), g4 = ch.zip(Gpu.BIN_MUL, g, t1);
			Object ex = ch.map(Gpu.MAP_EXP, ch.map(Gpu.MAP_NEGATIVE, ch.zip(Gpu.BIN_MUL, t2, t2)));
			Object g2 = ch.zip(Gpu.BIN_MUL, g4, ch.scale(Gpu.BIN_MUL, ex, 1.1283791670955126, true));
			Object b = ch.scale(Gpu.BIN_DIV, g2, 1.4142135623730951, false);
			Object a = ch.scale(Gpu.BIN_MUL, g1, 0.5, false);
			Object dxNew = ch.zip(Gpu.BIN_ADD, b, a);
			Object dxOld = ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, old, b), a);
			fused = ch.fresh(n);
			Object fusedOld = ch.fresh(n);
			if (single) {
				assertThat(Gpu.geluGrad((float[]) g, 0, (float[]) x, 0, null, 0, (float[]) fused, 0, n)).isTrue();
				assertThat(Gpu.geluGrad((float[]) g, 0, (float[]) x, 0, (float[]) old, 0, (float[]) fusedOld, 0, n))
					.isTrue();
			}
			else {
				assertThat(Gpu.geluGrad((double[]) g, 0, (double[]) x, 0, null, 0, (double[]) fused, 0, n)).isTrue();
				assertThat(Gpu.geluGrad((double[]) g, 0, (double[]) x, 0, (double[]) old, 0, (double[]) fusedOld, 0, n))
					.isTrue();
			}
			assertThat(ch.doubles(fused)).as("gelu grad single=%s", single).containsExactly(ch.doubles(dxNew));
			assertThat(ch.doubles(fusedOld)).as("gelu grad onto old single=%s", single)
				.containsExactly(ch.doubles(dxOld));
			// layer-norm: mean, sub, the variance's square / sum / divisor, eps, sqrt,
			// div.
			Object mu = ch.scale(Gpu.BIN_DIV, ch.fold(Gpu.FOLD_SUM, x, rows, len), len, false);
			Object dev = ch.bcast(Gpu.BIN_SUB, x, mu, rows, len);
			Object v = ch.scale(Gpu.BIN_DIV, ch.fold(Gpu.FOLD_SUM, ch.zip(Gpu.BIN_MUL, dev, dev), rows, len), len,
					false);
			Object sd = ch.map(Gpu.MAP_SQRT, ch.scale(Gpu.BIN_ADD, v, eps, false));
			Object norm = ch.bcast(Gpu.BIN_DIV, dev, sd, rows, len);
			fused = ch.fresh(n);
			assertThat(single ? Gpu.layerNorm((float[]) x, 0, (float[]) fused, 0, rows, len, eps)
					: Gpu.layerNorm((double[]) x, 0, (double[]) fused, 0, rows, len, eps))
				.isTrue();
			assertThat(ch.doubles(fused)).as("layer-norm single=%s", single).containsExactly(ch.doubles(norm));
			// its adjoint, the tape's own walk: the division's two adjoints, sqrt's, the
			// divisor's, the broadcast onto zeros, the squared deviations' two, the two
			// means' -- and the four contributions folded onto x's gradient in order.
			Object gDev = ch.bcast(Gpu.BIN_DIV, g, sd, rows, len);
			Object r = ch.bcast(Gpu.BIN_DIV, ch.zip(Gpu.BIN_MUL, g, dev), ch.zip(Gpu.BIN_MUL, sd, sd), rows, len);
			Object gSd = ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_NEGATIVE, r), rows, len);
			Object gVe = ch.zip(Gpu.BIN_DIV, gSd, ch.scale(Gpu.BIN_MUL, sd, 2.0, true));
			Object gSq = ch.bcast(Gpu.BIN_ADD, zeros, ch.scale(Gpu.BIN_DIV, gVe, len, false), rows, len);
			Object gd2 = ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_MUL, gSq, dev), ch.zip(Gpu.BIN_MUL, gSq, dev));
			Object gM2 = ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_NEGATIVE, gd2), rows, len);
			Object a2 = ch.scale(Gpu.BIN_DIV, ch.bcast(Gpu.BIN_ADD, zeros, gM2, rows, len), len, false);
			Object gMu = ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_NEGATIVE, gDev), rows, len);
			Object a4 = ch.scale(Gpu.BIN_DIV, ch.bcast(Gpu.BIN_ADD, zeros, gMu, rows, len), len, false);
			Object lnNew = ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, gd2, a2), gDev), a4);
			Object lnOld = ch.zip(Gpu.BIN_ADD,
					ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, old, gd2), a2), gDev), a4);
			fused = ch.fresh(n);
			fusedOld = ch.fresh(n);
			if (single) {
				assertThat(
						Gpu.layerNormGrad((float[]) g, 0, (float[]) x, 0, null, 0, (float[]) fused, 0, rows, len, eps))
					.isTrue();
				assertThat(Gpu.layerNormGrad((float[]) g, 0, (float[]) x, 0, (float[]) old, 0, (float[]) fusedOld, 0,
						rows, len, eps))
					.isTrue();
			}
			else {
				assertThat(Gpu.layerNormGrad((double[]) g, 0, (double[]) x, 0, null, 0, (double[]) fused, 0, rows, len,
						eps))
					.isTrue();
				assertThat(Gpu.layerNormGrad((double[]) g, 0, (double[]) x, 0, (double[]) old, 0, (double[]) fusedOld,
						0, rows, len, eps))
					.isTrue();
			}
			assertThat(ch.doubles(fused)).as("layer-norm grad single=%s", single).containsExactly(ch.doubles(lnNew));
			assertThat(ch.doubles(fusedOld)).as("layer-norm grad onto old single=%s", single)
				.containsExactly(ch.doubles(lnOld));
			// layer-norm's AFFINE (todo-634): the normalization above, then the two
			// broadcast passes over the (len) weight and bias -- and its adjoint, whose
			// two results are the plain adjoint over the broadcast g * weight and the zip
			// g * norm. The plain pair is pinned to its own chain just above, so it is
			// the reference the affine pair's first result is held to.
			Object w = ch.fresh(len), bias = ch.fresh(len);
			for (int i = 0; i < len; i++) {
				double wv = 0.5 + random.nextDouble(), bv = random.nextDouble() - 0.5;
				if (single) {
					((float[]) w)[i] = (float) wv;
					((float[]) bias)[i] = (float) bv;
				}
				else {
					((double[]) w)[i] = wv;
					((double[]) bias)[i] = bv;
				}
			}
			Object affine = ch.bcastCols(Gpu.BIN_ADD, ch.bcastCols(Gpu.BIN_MUL, norm, w, rows, len), bias, rows, len);
			fused = ch.fresh(n);
			assertThat(single
					? Gpu.layerNormAffine((float[]) x, 0, (float[]) w, 0, (float[]) bias, 0, (float[]) fused, 0, rows,
							len, eps)
					: Gpu.layerNormAffine((double[]) x, 0, (double[]) w, 0, (double[]) bias, 0, (double[]) fused, 0,
							rows, len, eps))
				.isTrue();
			assertSameBits("layer-norm affine single=" + single, ch.doubles(fused), ch.doubles(affine));
			Object gw = ch.bcastCols(Gpu.BIN_MUL, g, w, rows, len);
			Object gn = ch.zip(Gpu.BIN_MUL, g, norm);
			Object affNew = ch.fresh(n), affOld = ch.fresh(n);
			if (single) {
				assertThat(Gpu.layerNormGrad((float[]) gw, 0, (float[]) x, 0, null, 0, (float[]) affNew, 0, rows, len,
						eps))
					.isTrue();
				assertThat(Gpu.layerNormGrad((float[]) gw, 0, (float[]) x, 0, (float[]) old, 0, (float[]) affOld, 0,
						rows, len, eps))
					.isTrue();
			}
			else {
				assertThat(Gpu.layerNormGrad((double[]) gw, 0, (double[]) x, 0, null, 0, (double[]) affNew, 0, rows,
						len, eps))
					.isTrue();
				assertThat(Gpu.layerNormGrad((double[]) gw, 0, (double[]) x, 0, (double[]) old, 0, (double[]) affOld, 0,
						rows, len, eps))
					.isTrue();
			}
			for (Object accumulated : new Object[] { null, old }) {
				Object dxOut = ch.fresh(n), gnOut = ch.fresh(n);
				assertThat(single
						? Gpu.layerNormAffineGrad((float[]) g, 0, (float[]) x, 0, (float[]) w, 0, (float[]) accumulated,
								0, (float[]) dxOut, 0, (float[]) gnOut, 0, rows, len, eps)
						: Gpu.layerNormAffineGrad((double[]) g, 0, (double[]) x, 0, (double[]) w, 0,
								(double[]) accumulated, 0, (double[]) dxOut, 0, (double[]) gnOut, 0, rows, len, eps))
					.isTrue();
				String what = "layer-norm affine grad single=" + single + " old=" + (accumulated != null);
				assertSameBits(what + " dx", ch.doubles(dxOut), ch.doubles(accumulated == null ? affNew : affOld));
				assertSameBits(what + " g*norm", ch.doubles(gnOut), ch.doubles(gn));
			}
		}
	}

	@Test
	void theLibmFreeFusedMembersAreTheSequentialReferencesBits() {
		// The three fused members with no library function in them are BIT-IDENTICAL to
		// the CPU chain, not merely to the device's: the row folds are sequential in a
		// double and every member boundary narrows to the width. Checked against a
		// sequential Java replay of the chain, and the dropout mask against the
		// generator's own walk.
		int rows = 512, len = 384, n = rows * len;
		double eps = 1.0e-5, p = 0.1, span = 1.0 - p;
		for (boolean single : new boolean[] { false, true }) {
			Random random = new Random(5);
			double[] x = new double[n], g = new double[n], o = new double[n];
			for (int i = 0; i < n; i++) {
				x[i] = nr(random.nextDouble() * 6 - 3, single);
				g[i] = nr(random.nextDouble() * 2 - 1, single);
				o[i] = nr(random.nextDouble(), single);
			}
			double[] norm = new double[n];
			for (int r = 0; r < rows; r++) {
				int base = r * len;
				double acc = 0;
				for (int k = 0; k < len; k++) {
					acc += x[base + k];
				}
				double mu = nr(nr(acc, single) / len, single);
				acc = 0;
				for (int k = 0; k < len; k++) {
					double dev = nr(x[base + k] - mu, single);
					acc += nr(dev * dev, single);
				}
				double sd = nr(Math.sqrt(nr(nr(nr(acc, single) / len, single) + eps, single)), single);
				for (int k = 0; k < len; k++) {
					norm[base + k] = nr(nr(x[base + k] - mu, single) / sd, single);
				}
			}
			double[] grad = layerNormGradReference(g, x, null, rows, len, eps, single);
			double[] gradOld = layerNormGradReference(g, x, o, rows, len, eps, single);
			// Layer-norm's AFFINE and its adjoint (todo-634) over a weight and bias of
			// the row length: the forward's two broadcast passes, and backward the
			// broadcast g * weight the normalization's own adjoint then walks, plus the
			// zip g * norm the weight's gradient is folded from.
			double[] w = new double[len], bias = new double[len];
			for (int k = 0; k < len; k++) {
				w[k] = nr(0.5 + random.nextDouble(), single);
				bias[k] = nr(random.nextDouble() - 0.5, single);
			}
			double[] affine = new double[n], gw = new double[n], gn = new double[n];
			for (int r = 0; r < rows; r++) {
				for (int k = 0; k < len; k++) {
					int i = r * len + k;
					affine[i] = nr(nr(norm[i] * w[k], single) + bias[k], single);
					gw[i] = nr(g[i] * w[k], single);
					gn[i] = nr(g[i] * norm[i], single);
				}
			}
			double[] affineGrad = layerNormGradReference(gw, x, null, rows, len, eps, single);
			double[] affineGradOld = layerNormGradReference(gw, x, o, rows, len, eps, single);
			// The softmax adjoint, with o standing in for the softmax output.
			double[] sgrad = new double[n];
			for (int r = 0; r < rows; r++) {
				int base = r * len;
				double acc = 0;
				for (int k = 0; k < len; k++) {
					acc += nr(g[base + k] * o[base + k], single);
				}
				double tot = nr(acc, single);
				for (int k = 0; k < len; k++) {
					sgrad[base + k] = nr(o[base + k] * nr(g[base + k] - tot, single), single);
				}
			}
			int s1 = 4321, s2 = 8765, s3 = 2468;
			int[] st = { s1, s2, s3 };
			double[] mask = new double[n];
			for (int k = 0; k < n; k++) {
				double u = nr(next(st), single);
				mask[k] = nr((u > p ? 1.0 : 0.0) / span, single);
			}
			if (single) {
				float[] xf = new float[n], gf = new float[n], of = new float[n];
				for (int i = 0; i < n; i++) {
					xf[i] = (float) x[i];
					gf[i] = (float) g[i];
					of[i] = (float) o[i];
				}
				float[] c = new float[n];
				assertThat(Gpu.layerNorm(xf, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(widen(c)).as("layer-norm f32").containsExactly(norm);
				assertThat(Gpu.layerNormGrad(gf, 0, xf, 0, null, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(widen(c)).as("layer-norm grad f32").containsExactly(grad);
				assertThat(Gpu.layerNormGrad(gf, 0, xf, 0, of, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(widen(c)).as("layer-norm grad onto old f32").containsExactly(gradOld);
				float[] wf = new float[len], bf = new float[len];
				for (int k = 0; k < len; k++) {
					wf[k] = (float) w[k];
					bf[k] = (float) bias[k];
				}
				assertThat(Gpu.layerNormAffine(xf, 0, wf, 0, bf, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(widen(c)).as("layer-norm affine f32").containsExactly(affine);
				float[] gnc = new float[n];
				assertThat(Gpu.layerNormAffineGrad(gf, 0, xf, 0, wf, 0, null, 0, c, 0, gnc, 0, rows, len, eps))
					.isTrue();
				assertThat(widen(c)).as("layer-norm affine grad f32").containsExactly(affineGrad);
				assertThat(widen(gnc)).as("layer-norm affine grad g*norm f32").containsExactly(gn);
				assertThat(Gpu.layerNormAffineGrad(gf, 0, xf, 0, wf, 0, of, 0, c, 0, gnc, 0, rows, len, eps)).isTrue();
				assertThat(widen(c)).as("layer-norm affine grad onto old f32").containsExactly(affineGradOld);
				assertThat(widen(gnc)).as("layer-norm affine grad onto old g*norm f32").containsExactly(gn);
				assertThat(Gpu.softmaxGrad(gf, 0, of, 0, c, 0, rows, len)).isTrue();
				assertThat(widen(c)).as("softmax grad f32").containsExactly(sgrad);
				assertThat(Gpu.dropoutMask(c, 0, n, p, span, s1, s2, s3)).isTrue();
				assertThat(widen(c)).as("dropout mask f32").containsExactly(mask);
			}
			else {
				double[] c = new double[n];
				assertThat(Gpu.layerNorm(x, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(c).as("layer-norm f64").containsExactly(norm);
				assertThat(Gpu.layerNormGrad(g, 0, x, 0, null, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(c).as("layer-norm grad f64").containsExactly(grad);
				assertThat(Gpu.layerNormGrad(g, 0, x, 0, o, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(c).as("layer-norm grad onto old f64").containsExactly(gradOld);
				assertThat(Gpu.layerNormAffine(x, 0, w, 0, bias, 0, c, 0, rows, len, eps)).isTrue();
				assertThat(c).as("layer-norm affine f64").containsExactly(affine);
				double[] gnc = new double[n];
				assertThat(Gpu.layerNormAffineGrad(g, 0, x, 0, w, 0, null, 0, c, 0, gnc, 0, rows, len, eps)).isTrue();
				assertThat(c).as("layer-norm affine grad f64").containsExactly(affineGrad);
				assertThat(gnc).as("layer-norm affine grad g*norm f64").containsExactly(gn);
				assertThat(Gpu.layerNormAffineGrad(g, 0, x, 0, w, 0, o, 0, c, 0, gnc, 0, rows, len, eps)).isTrue();
				assertThat(c).as("layer-norm affine grad onto old f64").containsExactly(affineGradOld);
				assertThat(gnc).as("layer-norm affine grad onto old g*norm f64").containsExactly(gn);
				assertThat(Gpu.softmaxGrad(g, 0, o, 0, c, 0, rows, len)).isTrue();
				assertThat(c).as("softmax grad f64").containsExactly(sgrad);
				assertThat(Gpu.dropoutMask(c, 0, n, p, span, s1, s2, s3)).isTrue();
				assertThat(c).as("dropout mask f64").containsExactly(mask);
			}
			assertThat(Gpu.rngAdvance(s1, s2, s3, n)).isEqualTo(st);
		}
	}

	/**
	 * The tape's backward through layer-norm's normalization, replayed sequentially in
	 * Java at the given width: the reference both the plain adjoint and the affine one
	 * (whose {@code g} is the broadcast {@code g * weight}) are held to.
	 */
	private static double[] layerNormGradReference(double[] g, double[] x,
			double @org.jspecify.annotations.Nullable [] old, int rows, int len, double eps, boolean single) {
		double[] out = new double[rows * len];
		for (int r = 0; r < rows; r++) {
			int base = r * len;
			double acc = 0;
			for (int k = 0; k < len; k++) {
				acc += x[base + k];
			}
			double mu = nr(nr(acc, single) / len, single);
			acc = 0;
			for (int k = 0; k < len; k++) {
				double dev = nr(x[base + k] - mu, single);
				acc += nr(dev * dev, single);
			}
			double sd = nr(Math.sqrt(nr(nr(nr(acc, single) / len, single) + eps, single)), single);
			double q = nr(sd * sd, single), accSd = 0, accMu = 0;
			for (int k = 0; k < len; k++) {
				double dev = nr(x[base + k] - mu, single);
				accSd += nr(-nr(nr(g[base + k] * dev, single) / q, single), single);
				accMu += nr(-nr(g[base + k] / sd, single), single);
			}
			double gS = nr(nr(nr(accSd, single) / nr(2.0 * sd, single), single) / len, single);
			double gSq = nr(0.0 + gS, single), accM2 = 0;
			for (int k = 0; k < len; k++) {
				double pp = nr(gSq * nr(x[base + k] - mu, single), single);
				accM2 += nr(-nr(pp + pp, single), single);
			}
			double a2 = nr(nr(0.0 + nr(accM2, single), single) / len, single);
			double a4 = nr(nr(0.0 + nr(accMu, single), single) / len, single);
			for (int k = 0; k < len; k++) {
				double dev = nr(x[base + k] - mu, single);
				double pp = nr(gSq * dev, single), gd2 = nr(pp + pp, single);
				double gDev = nr(g[base + k] / sd, single);
				double a = old == null ? gd2 : nr(old[base + k] + gd2, single);
				out[base + k] = nr(nr(nr(a + a2, single) + gDev, single) + a4, single);
			}
		}
		return out;
	}

	@Test
	void everyFusedDeclineConditionStillDeclinesWithADevicePresent() {
		// Too few rows for a fresh operand, an empty row, a result or an accumulated
		// gradient too short, a state word out of range: each declines, none throws.
		int rows = 4, len = 64, n = rows * len;
		double[] x = new double[n], c = new double[n];
		assertThat(Gpu.softmax(x, 0, c, 0, rows, len)).isFalse();
		assertThat(Gpu.softmaxGrad(x, 0, x, 0, c, 0, rows, len)).isFalse();
		assertThat(Gpu.layerNorm(x, 0, c, 0, rows, len, 1e-5)).isFalse();
		assertThat(Gpu.layerNormGrad(x, 0, x, 0, null, 0, c, 0, rows, len, 1e-5)).isFalse();
		int big = (int) Math.max(Gpu.mapMinElements() * 2, Gpu.foldMinElements() * 2);
		double[] a = new double[big], out = new double[big], shortArray = new double[big - 1];
		assertThat(Gpu.gelu(a, 0, shortArray, 0, big)).isFalse();
		assertThat(Gpu.gelu(a, 0, out, 0, 0)).isFalse();
		assertThat(Gpu.geluGrad(a, 0, a, 0, shortArray, 0, out, 0, big)).isFalse();
		assertThat(Gpu.softmax(a, 0, out, 0, 0, big)).isFalse();
		assertThat(Gpu.softmax(a, 0, shortArray, 0, 256, big / 256)).isFalse();
		// The scaled-masked forms (2026-09-02): a mask the operand is not a multiple of,
		// one
		// that is neither width, one short of its length, and a scale op that is neither
		// the multiply nor the divide.
		assertThat(Gpu.softmax(a, 0, new double[7], 0, 7, out, 0, 256, big / 256, Gpu.BIN_DIV, 8.0, 0.0)).isFalse();
		assertThat(Gpu.softmax(a, 0, new int[256], 0, 256, out, 0, 256, big / 256, 0, 0.0, 0.0)).isFalse();
		assertThat(Gpu.softmax(a, 0, new double[255], 0, 256, out, 0, 256, big / 256, 0, 0.0, 0.0)).isFalse();
		assertThat(Gpu.softmax(a, 0, null, 0, 0, out, 0, 256, big / 256, Gpu.BIN_SUB, 8.0, 0.0)).isFalse();
		assertThat(Gpu.softmaxGrad(a, 0, a, 0, new double[7], 0, 7, out, 0, 256, big / 256, Gpu.BIN_DIV, 8.0))
			.isFalse();
		assertThat(Gpu.softmaxGrad(a, 0, a, 0, null, 0, 0, out, 0, 256, big / 256, Gpu.BIN_SUB, 8.0)).isFalse();
		assertThat(Gpu.layerNormGrad(a, 0, a, 0, shortArray, 0, out, 0, 256, big / 256, 1e-5)).isFalse();
		// The affine pair (todo-634): a weight or bias shorter than the row, and a second
		// result too short for the count.
		int affLen = big / 256;
		double[] par = new double[affLen], shortPar = new double[affLen - 1];
		assertThat(Gpu.layerNormAffine(a, 0, shortPar, 0, par, 0, out, 0, 256, affLen, 1e-5)).isFalse();
		assertThat(Gpu.layerNormAffine(a, 0, par, 0, shortPar, 0, out, 0, 256, affLen, 1e-5)).isFalse();
		assertThat(Gpu.layerNormAffineGrad(a, 0, a, 0, shortPar, 0, null, 0, out, 0, out, 0, 256, affLen, 1e-5))
			.isFalse();
		assertThat(Gpu.layerNormAffineGrad(a, 0, a, 0, par, 0, null, 0, out, 0, shortArray, 0, 256, affLen, 1e-5))
			.isFalse();
		assertThat(Gpu.layerNormAffine(x, 0, new double[len], 0, new double[len], 0, c, 0, rows, len, 1e-5)).isFalse();
		assertThat(Gpu.layerNormAffineGrad(x, 0, x, 0, new double[len], 0, null, 0, c, 0, c, 0, rows, len, 1e-5))
			.isFalse();
		assertThat(Gpu.dropoutMask(out, 0, big, 0.1, 0.9, 1 << 23, 1, 1)).isFalse();
		assertThat(Gpu.dropoutMask(out, 0, 0, 0.1, 0.9, 1, 1, 1)).isFalse();
		assertThat(Gpu.dropoutMask(shortArray, 0, big, 0.1, 0.9, 1, 1, 1)).isFalse();
	}

	/**
	 * The clip norm's sum of squares: the ONE member of this library whose result is not
	 * the caller's own fold. It is offered over a resident operand only, it is
	 * REPRODUCIBLE (the block count is a function of the length), and it is within a few
	 * ulps of the sequential fold -- on the correct side of it, since a blocked sum is
	 * the better approximation.
	 */
	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theSumOfSquaresFoldsInBlocksAndIsReproducibleWithinAFewUlpsOfTheSequentialSum() {
		assumeTrue(Gpu.residency() != null, "the sum of squares is the CUDA backend's");
		Gpu.releaseResident();
		int n = 1 << 20;
		float[] a = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = (float) (Math.sin(i * 0.0007) * 0.5 + 0.5);
		}
		assertThat(Gpu.sumSquares(a, 0, n, 1.5)).isNull();
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new float[n], 0, n)).isTrue();
		double sequential = 1.5;
		for (float v : a) {
			double d = v;
			sequential = sequential + d * d;
		}
		Double first = Gpu.sumSquares(a, 0, n, 1.5);
		assertThat(first).isNotNull();
		assertThat(first).isCloseTo(sequential, within(Math.abs(sequential) * 1e-12));
		// Same length, same answer, every time: the association is fixed, not scheduled.
		for (int r = 0; r < 4; r++) {
			assertThat(Gpu.sumSquares(a, 0, n, 1.5)).isEqualTo(first);
		}
		// The seed is added to the total, and a slice folds only its own elements.
		assertThat(Gpu.sumSquares(a, 0, n, 0.0)).isCloseTo(first - 1.5, within(Math.abs(sequential) * 1e-12));
		double tail = 0;
		for (int i = n / 2; i < n; i++) {
			double d = a[i];
			tail = tail + d * d;
		}
		Double half = Gpu.sumSquares(a, n / 2, n / 2, 0.0);
		assertThat(half).isNotNull();
		assertThat(half).isCloseTo(tail, within(Math.abs(tail) * 1e-12));
		// An empty or out-of-bounds slice declines.
		assertThat(Gpu.sumSquares(a, 0, 0, 0.0)).isNull();
		assertThat(Gpu.sumSquares(a, 1, n, 0.0)).isNull();
	}

}
