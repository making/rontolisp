package am.ik.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GpuTest}'s Apple counterpart: the half of {@code am.ik.gpu} that needs a METAL
 * device on the machine. It answers the same claims that file answers for CUDA -- the
 * kernels compile and compute, the products agree with a scalar oracle, every operand is
 * read from its own offset, every decline condition still declines with hardware present,
 * and a run of calls does not grow device memory without bound -- at {@code #f}, because
 * that is the only width this backend has.
 *
 * <h2>Why this is a second file and not a widened first one</h2>
 *
 * The two backends do not have the same member set, the same thresholds or the same
 * precision story: MSL has no {@code double}, the floor is five times CUDA's so every
 * threshold moves, the axis fold is not a member at all, and the rank-2 product goes
 * through MPS rather than through a kernel of ours. A single width-generic suite would
 * have had to branch on the backend in nearly every test, which is a worse way to say the
 * same thing.
 *
 * <h2>What is pinned here and NOT in {@link GpuTest}</h2>
 *
 * Four claims that are this backend's own. (1) A {@code double} operand is a HARD decline
 * at every member and every size -- the one thing a user of this flag on a Mac has to
 * know. (2) The strided tier is BIT-IDENTICAL to the scalar oracle even though it
 * computes in {@code float} where the CUDA kernels compute in {@code double};
 * {@code gemm.metal} argues why, and this asserts it. (3) BOTH product routes compute,
 * because the tiled kernel is otherwise unreachable above the MPS threshold on any
 * machine that has MPS, which is every machine that has Metal. (4) The axis fold declines
 * at every size, which is the guard on a measured refusal.
 */
@EnabledIf("am.ik.gpu.MetalGpuTest#aMetalGpuIsAvailable")
class MetalGpuTest {

	static boolean aMetalGpuIsAvailable() {
		return Gpu.available() && Gpu.device() instanceof MetalGemm;
	}

	private static MetalGemm device() {
		return (MetalGemm) java.util.Objects.requireNonNull(Gpu.device());
	}

	/**
	 * The smallest square product this machine will actually accept, times a safety
	 * factor so that a shape derived from it is not sitting on the threshold. Sized off
	 * {@link Gpu#minWork()} for the reason {@link GpuTest} is: the threshold is a
	 * property of the backend, and hard-coding one reads as a kernel regression when it
	 * is nothing of the sort.
	 */
	private static int square() {
		int n = (int) Math.ceil(Math.cbrt((double) Gpu.minWork()));
		return Math.max(64, (n + n / 4 + 15) / 16 * 16);
	}

	@Test
	void theCheckedInMetalKernelsCompileAndTheProductComputes() {
		assertThat(Gpu.description()).contains("Metal");
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
		// Small integers through the reduction are exact at this width, so however the
		// kernel walks k the answer is the oracle's and this is an EQUALITY.
		double[] expected = reference(ad, 0, bd, 0, n, n, n);
		for (int i = 0; i < c.length; i++) {
			assertThat((double) c[i]).isEqualTo(expected[i]);
		}
	}

	@Test
	void aDoubleOperandIsAHardDeclineAtEveryMemberAndSize() {
		assertThat(device().supportsDouble()).isFalse();
		int n = square();
		double[] a = new double[n * n], b = new double[n * n], out = new double[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = i % 7;
			b[i] = i % 5;
		}
		// Every shape here is well ABOVE the threshold, so a decline can only be the
		// width: MSL has no double, and there is no slower path to fall back to.
		assertThat(Gpu.multiply(a, 0, b, 0, out, 0, n, n, n)).isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, n, n, n)).isNull();
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, 0, out, 0, 1, n, n, n)).isFalse();
		int elements = (int) Gpu.mapMinElements() * 4;
		double[] x = new double[elements], y = new double[elements];
		assertThat(Gpu.map(Gpu.MAP_EXP, x, 0, y, 0, elements)).isFalse();
		int cols = 64, rows = (int) Math.max(Gpu.stridedMinElements() / cols * 4, 512);
		int[] dims = { rows, cols };
		double[] big = new double[rows * cols], column = new double[rows], strided = new double[rows * cols];
		assertThat(
				Gpu.bcast(Gpu.BIN_SUB, big, 0, new int[] { cols, 1 }, column, 0, new int[] { 1, 0 }, strided, 0, dims))
			.isFalse();
		assertThat(Gpu.gather(big, 0, new int[] { 1, rows }, strided, 0, new int[] { cols, rows })).isFalse();
		// Nothing was written anywhere.
		assertThat(out).containsOnly(0.0);
		assertThat(y).containsOnly(0.0);
		assertThat(strided).containsOnly(0.0);
	}

	@Test
	void theAxisFoldIsDeclinedAtEveryWidthAndSize() {
		// A refusal with two measurements behind it (gemm.metal): %la-fold-axis
		// accumulates in double, which no float kernel reproduces, and the amax/amin half
		// that needs no accumulator loses to the CPU anyway. The guard is that widening
		// the member set without re-measuring fails here.
		int inner = 1, outer = 4096, len = 256;
		float[] a = new float[outer * len * inner], out = new float[outer * inner];
		double[] ad = new double[a.length], outd = new double[out.length];
		for (int i = 0; i < a.length; i++) {
			a[i] = i % 13;
			ad[i] = a[i];
		}
		for (int op : new int[] { Gpu.FOLD_SUM, Gpu.FOLD_AMAX, Gpu.FOLD_AMIN }) {
			assertThat(Gpu.fold(op, ad, 0, outd, 0, outer, len, inner)).isFalse();
			assertThat(Gpu.fold(op, a, 0, out, 0, outer, len, inner)).isFalse();
		}
		assertThat(out).containsOnly(0.0f);
		assertThat(outd).containsOnly(0.0);
	}

	@Test
	void bothProductRoutesComputeTheSameProduct() {
		// MPS is 1.5-4.5x the tiled kernel from n=512 up and ~35 us of object churn
		// behind it below that, which is why the split exists -- but it makes the tiled
		// kernel unreachable for a big matrix on any machine that has MPS, and that is a
		// path which has to keep computing the same answers.
		MetalGemm gemm = device();
		int n = (int) Math.ceil(Math.cbrt((double) MetalGemm.mpsMinWork() * 2));
		n = (n + 15) / 16 * 16;
		Random random = new Random(11);
		float[] a = new float[n * n], b = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (float) random.nextGaussian();
			b[i] = (float) random.nextGaussian();
		}
		float[] throughMps = new float[n * n], throughKernel = new float[n * n];
		assertThat(gemm.mpsEnabled()).isTrue();
		assertThat(gemm.gemmF(a, 0, b, 0, throughMps, 0, n, n, n)).isTrue();
		boolean previous = gemm.setMps(false);
		try {
			assertThat(previous).isTrue();
			assertThat(gemm.gemmF(a, 0, b, 0, throughKernel, 0, n, n, n)).isTrue();
		}
		finally {
			gemm.setMps(previous);
		}
		assertThat(gemm.mpsEnabled()).isTrue();
		// Both fold k ascending over the same f32 values, and measured they agree bit for
		// bit -- so which route ran is invisible in the result, which is what lets the
		// choice be a pure size decision.
		assertBitIdentical(throughKernel, throughMps, "the tiled kernel against MPS");
	}

	@Test
	void anInexactProductAgreesWithTheScalarOracleToTheWidthsOwnTolerance() {
		// f32 is f32: a CPU accumulation of the same product at this width lands the same
		// distance from the f64 oracle, so the tolerance is the WIDTH's and not the
		// device's. Random zero-mean data, because dyadic test data round-trips exactly
		// and hides the whole question.
		int n = square();
		Random random = new Random(5);
		double[] a = new double[n * n], b = new double[n * n];
		float[] af = new float[n * n], bf = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = random.nextGaussian();
			b[i] = random.nextGaussian();
			af[i] = (float) a[i];
			bf[i] = (float) b[i];
		}
		double[] expected = reference(a, 0, b, 0, n, n, n);
		double scale = 0;
		for (double value : expected) {
			scale = Math.max(scale, Math.abs(value));
		}
		float[] c = Gpu.multiply(af, 0, bf, 0, n, n, n);
		assertThat(c).isNotNull();
		for (int i = 0; i < expected.length; i++) {
			assertThat(Math.abs(c[i] - expected[i]) / scale).isLessThan(1e-5);
		}
	}

	@Test
	void everyOperandIncludingTheResultIsReadFromItsOwnOffset() {
		// The compiled backends keep a [rank, dim..., data...] header inside the same
		// array as the data, so an interceptor has to be able to say where the elements
		// start AND to have the product written into the array it already shaped.
		int n = square();
		int headerA = 3, headerB = 5, headerOut = 7;
		float[] a = new float[headerA + n * n], b = new float[headerB + n * n];
		float[] out = new float[headerOut + n * n];
		double[] ad = new double[headerA + n * n], bd = new double[headerB + n * n];
		for (int i = 0; i < n * n; i++) {
			ad[headerA + i] = (i % 7) - 3;
			bd[headerB + i] = (i % 5) - 2;
			a[headerA + i] = (float) ad[headerA + i];
			b[headerB + i] = (float) bd[headerB + i];
		}
		for (int i = 0; i < headerA; i++) {
			a[i] = Float.NaN;
		}
		for (int i = 0; i < headerOut; i++) {
			out[i] = i + 1;
		}
		assertThat(Gpu.multiply(a, headerA, b, headerB, out, headerOut, n, n, n)).isTrue();
		double[] expected = reference(ad, headerA, bd, headerB, n, n, n);
		for (int i = 0; i < expected.length; i++) {
			assertThat((double) out[headerOut + i]).isEqualTo(expected[i]);
		}
		for (int i = 0; i < headerOut; i++) {
			assertThat(out[i]).as("the destination's header survives").isEqualTo(i + 1);
		}
	}

	@Test
	void aRectangularProductUsesAllThreeDimensions() {
		// A square shape hides a transposed index; a non-multiple of the tile also
		// exercises the kernel's bounds guard.
		int base = square();
		int n = base + 3, m = base + 7, p = base + 11;
		float[] a = new float[n * m], b = new float[m * p];
		double[] ad = new double[n * m], bd = new double[m * p];
		for (int i = 0; i < a.length; i++) {
			ad[i] = (i % 7) - 3;
			a[i] = (float) ad[i];
		}
		for (int i = 0; i < b.length; i++) {
			bd[i] = (i % 5) - 2;
			b[i] = (float) bd[i];
		}
		float[] c = Gpu.multiply(a, 0, b, 0, n, m, p);
		assertThat(c).isNotNull().hasSize(n * p);
		double[] expected = reference(ad, 0, bd, 0, n, m, p);
		for (int i = 0; i < expected.length; i++) {
			assertThat((double) c[i]).isEqualTo(expected[i]);
		}
	}

	@Test
	void everyElementWiseMemberComputesItsOwnFunction() {
		// The op code is a kernel PARAMETER, so a swapped constant computes a different
		// member and every value still looks plausible. Nothing but this catches it.
		int n = (int) Gpu.mapMinElements() * 2;
		float[] a = new float[n], out = new float[n];
		for (int i = 0; i < n; i++) {
			// (0, 1): inside every member's domain, asin and acos included.
			a[i] = (float) ((i + 1) / (double) (n + 1));
		}
		for (int op = 0; op < Gpu.MAP_OPS; op++) {
			assertThat(Gpu.map(op, a, 0, out, 0, n)).as("op %d", op).isTrue();
			for (int i = 0; i < n; i += 97) {
				double expected = expected(op, a[i]);
				double tolerance = Math.max(1e-5 * Math.abs(expected), 1e-6);
				assertThat((double) out[i]).as("op %d at %f", op, a[i])
					.isCloseTo(expected, org.assertj.core.api.Assertions.within(tolerance));
			}
		}
	}

	@Test
	void anElementWiseMapReadsAndWritesFromItsOwnOffset() {
		int n = (int) Gpu.mapMinElements() * 2;
		float[] a = new float[n + 3], out = new float[n + 3];
		for (int i = 0; i < n; i++) {
			a[3 + i] = (float) ((i + 1) / (double) (n + 1));
		}
		for (int i = 0; i < 3; i++) {
			a[i] = Float.NaN;
			out[i] = i + 1;
		}
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 3, out, 3, n)).isTrue();
		for (int i = 0; i < n; i += 97) {
			assertThat((double) out[3 + i]).isCloseTo(Math.exp(a[3 + i]), org.assertj.core.api.Assertions.within(1e-5));
		}
		for (int i = 0; i < 3; i++) {
			assertThat(out[i]).isEqualTo(i + 1);
		}
	}

	@Test
	void theStridedTierIsBitIdenticalToTheScalarOracle() {
		// The claim gemm.metal argues rather than inherits: the CUDA kernels compute in
		// double and narrow on the store, which is %la-bcast-loop's rule, and MSL has no
		// double to do that with. +, - and * over two floats are exact in binary64 so one
		// float rounding is the same rounding; / is innocuous double rounding at these
		// widths; the selects and the gather move values. Asserted over INEXACT data,
		// which is the only kind that can tell the difference.
		int cols = 64;
		int rows = (int) Math.max(Gpu.stridedMinElements() / cols * 2, 512);
		int n = rows * cols;
		Random random = new Random(17);
		float[] x = new float[n], y = new float[rows], out = new float[n];
		for (int i = 0; i < n; i++) {
			x[i] = (float) random.nextGaussian();
		}
		for (int i = 0; i < rows; i++) {
			y[i] = (float) (random.nextGaussian() + 1.5);
		}
		int[] dims = { rows, cols };
		int[] sx = { cols, 1 }, sy = { 1, 0 };
		float[] expected = new float[n];
		for (int op = 0; op < Gpu.BIN_OPS; op++) {
			assertThat(Gpu.bcast(op, x, 0, sx, y, 0, sy, out, 0, dims)).as("op %d", op).isTrue();
			for (int r = 0; r < rows; r++) {
				for (int c = 0; c < cols; c++) {
					expected[r * cols + c] = binary(op, x[r * cols + c], y[r]);
				}
			}
			assertBitIdentical(out, expected, "op " + op);
		}
		// The axes transpose, as a permuted copy: dims (cols, rows) over source strides
		// (1, cols).
		float[] moved = new float[n];
		assertThat(Gpu.gather(x, 0, new int[] { 1, cols }, moved, 0, new int[] { cols, rows })).isTrue();
		for (int c = 0; c < cols; c++) {
			for (int r = 0; r < rows; r++) {
				expected[c * rows + r] = x[r * cols + c];
			}
		}
		assertBitIdentical(moved, expected, "the axes transpose");
	}

	@Test
	void aBatchIsTheSameSlabsRunOneAtATime() {
		int n = square();
		int batch = 3;
		Random random = new Random(23);
		float[] a = new float[batch * n * n], b = new float[batch * n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (float) random.nextGaussian();
			b[i] = (float) random.nextGaussian();
		}
		float[] batched = new float[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, batched, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			float[] alone = new float[n * n];
			assertThat(Gpu.multiply(a, z * n * n, b, z * n * n, alone, 0, n, n, n)).isTrue();
			assertBitIdentical(java.util.Arrays.copyOfRange(batched, z * n * n, (z + 1) * n * n), alone, "batch " + z);
		}
	}

	@Test
	void aBatchAboveTheMpsThresholdAddressesEachSlabByItsOwnOffset() {
		// The other route through the stack, and the only place a nonzero MPSMatrix
		// offset is used at all: above the MPS threshold a stacked product is one encode
		// PER SLAB into one command buffer, each matrix addressing itself with a byte
		// offset into the shared buffer. Getting that offset wrong computes the first
		// slab twice, which every other test in this file would miss.
		int n = (int) Math.ceil(Math.cbrt((double) MetalGemm.mpsMinWork()));
		n = (n + 15) / 16 * 16;
		int batch = 2;
		Random random = new Random(29);
		float[] a = new float[batch * n * n], b = new float[batch * n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = (float) random.nextGaussian();
			b[i] = (float) random.nextGaussian();
		}
		float[] batched = new float[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, n * n, batched, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			float[] alone = new float[n * n];
			assertThat(Gpu.multiply(a, z * n * n, b, z * n * n, alone, 0, n, n, n)).isTrue();
			assertBitIdentical(java.util.Arrays.copyOfRange(batched, z * n * n, (z + 1) * n * n), alone, "batch " + z);
		}
	}

	@Test
	void aBroadcastOperandIsAZeroStrideAndReadsTheSameSlabEveryBatch() {
		// The shape every torch:linear over a (B T C) activation has, and the reason only
		// ONE slab of that operand is copied to the device.
		int n = square();
		int batch = 3;
		float[] a = new float[batch * n * n], b = new float[n * n];
		double[] ad = new double[a.length], bd = new double[b.length];
		for (int i = 0; i < a.length; i++) {
			ad[i] = (i % 7) - 3;
			a[i] = (float) ad[i];
		}
		for (int i = 0; i < b.length; i++) {
			bd[i] = (i % 5) - 2;
			b[i] = (float) bd[i];
		}
		float[] c = new float[batch * n * n];
		assertThat(Gpu.multiply(a, 0, n * n, b, 0, 0, c, 0, batch, n, n, n)).isTrue();
		for (int z = 0; z < batch; z++) {
			double[] expected = reference(ad, z * n * n, bd, 0, n, n, n);
			for (int i = 0; i < expected.length; i++) {
				assertThat((double) c[z * n * n + i]).as("batch %d cell %d", z, i).isEqualTo(expected[i]);
			}
		}
	}

	@Test
	void everyDeclineConditionStillDeclinesWithADevicePresent() {
		// Having hardware must not bypass the size threshold or the bounds checks.
		int n = square();
		float[] a = new float[n * n], b = new float[n * n], out = new float[n * n];
		assertThat(Gpu.multiply(a, 0, b, 0, out, 0, 4, 4, 4)).as("below the threshold").isFalse();
		assertThat(Gpu.multiply(a, 1, b, 0, out, 0, n, n, n)).as("the left operand runs off the end").isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, out, 1, n, n, n)).as("the result runs off the end").isFalse();
		assertThat(Gpu.multiply(a, -1, b, 0, out, 0, n, n, n)).as("a negative offset").isFalse();
		assertThat(Gpu.multiply(a, 0, b, 0, out, 0, n, n, 0)).as("an empty dimension").isFalse();
		int elements = (int) Gpu.mapMinElements() * 2;
		float[] x = new float[elements], y = new float[elements];
		assertThat(Gpu.map(Gpu.MAP_OPS, x, 0, y, 0, elements)).as("an op code the library does not name").isFalse();
		assertThat(Gpu.map(Gpu.MAP_EXP, x, 0, y, 0, elements / 64)).as("below the element threshold").isFalse();
		assertThat(out).containsOnly(0.0f);
		assertThat(y).containsOnly(0.0f);
	}

	@Test
	void aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt() {
		// The Metal half owns a POOL where the CUDA half borrows the driver's, so the
		// leak question is a different one: not "is every buffer freed" but "does the
		// pool reach a steady state". A run of 400 products over three shapes reuses the
		// same size classes, so after a warm-up nothing new is allocated at all.
		MetalGemm gemm = device();
		int n = square();
		float[] a = new float[n * n], b = new float[n * n], c = new float[n * n];
		for (int i = 0; i < a.length; i++) {
			a[i] = i % 7;
			b[i] = i % 5;
		}
		for (int i = 0; i < 20; i++) {
			assertThat(Gpu.multiply(a, 0, b, 0, c, 0, n, n, n)).isTrue();
		}
		long before = gemm.freeDeviceMemory();
		for (int i = 0; i < 400; i++) {
			assertThat(Gpu.multiply(a, 0, b, 0, c, 0, n, n, n)).isTrue();
		}
		long after = gemm.freeDeviceMemory();
		// Two-sided: free memory that GREW would mean this is measuring the rest of the
		// machine rather than the pool. A per-call leak of three n x n buffers over 400
		// products is orders of magnitude past this bound.
		assertThat(Math.abs(before - after)).isLessThan(64L << 20);
	}

	@Test
	void theCheckedInMetalSourceIsTheArtifactTheLoaderExpects() throws IOException {
		String msl = resource(MetalGemm.KERNEL_RESOURCE);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_BATCHED_F32);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_MAP_F32);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_BCAST_F32);
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_GATHER_F32);
		// The op-code mirrors, whose other halves are Gpu.MAP_* / Gpu.BIN_* and gemm.cu.
		// Nothing links the three, so this is the only thing that notices a slip.
		assertThat(msl).contains("case " + Gpu.MAP_ERF + ": return erf1(x);");
		assertThat(msl).contains("case " + Gpu.BIN_DIV + ": return x / y;");
		// There is no fold entry point, and that is a measured refusal rather than an
		// omission: it must not reappear without the measurement being redone.
		assertThat(msl).doesNotContain("kernel void fold_f32");
		assertThat(device().thresholds().fold()).isEqualTo(Long.MAX_VALUE);
	}

	private String resource(String name) throws IOException {
		try (InputStream in = MetalGemm.class.getResourceAsStream(name)) {
			assertThat(in).as("resource %s", name).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * Bit equality over two arrays of half a million cells, by hand. Neither an AssertJ
	 * per-cell assertion nor {@code containsExactly} can be used at this size: the first
	 * builds a description object per cell and the second boxes the whole array, and
	 * between them they took 475 of this class's 522 seconds before it was written.
	 * @param actual what the device produced
	 * @param expected what the scalar oracle produced
	 * @param context what to name in the failure message
	 */
	private static void assertBitIdentical(float[] actual, float[] expected, String context) {
		assertThat(actual).as("%s: length", context).hasSameSizeAs(expected);
		for (int i = 0; i < expected.length; i++) {
			if (Float.floatToRawIntBits(actual[i]) != Float.floatToRawIntBits(expected[i])) {
				org.assertj.core.api.Assertions
					.fail("%s: cell %d is %s where the oracle is %s".formatted(context, i, actual[i], expected[i]));
			}
		}
	}

	/** The scalar row-by-column product, in double: the oracle both routes answer to. */
	private static double[] reference(double[] a, int offsetA, double[] b, int offsetB, int n, int m, int p) {
		double[] c = new double[n * p];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < p; j++) {
				double sum = 0;
				for (int k = 0; k < m; k++) {
					sum += a[offsetA + i * m + k] * b[offsetB + k * p + j];
				}
				c[i * p + j] = sum;
			}
		}
		return c;
	}

	/** {@link Gpu}'s element-wise op codes, as {@code java.lang.Math} spells them. */
	private static double expected(int op, double x) {
		return switch (op) {
			case Gpu.MAP_EXP -> Math.exp(x);
			case Gpu.MAP_LOG -> Math.log(x);
			case Gpu.MAP_TANH -> Math.tanh(x);
			case Gpu.MAP_SIN -> Math.sin(x);
			case Gpu.MAP_COS -> Math.cos(x);
			case Gpu.MAP_TAN -> Math.tan(x);
			case Gpu.MAP_ASIN -> Math.asin(x);
			case Gpu.MAP_ACOS -> Math.acos(x);
			case Gpu.MAP_ATAN -> Math.atan(x);
			case Gpu.MAP_SINH -> Math.sinh(x);
			case Gpu.MAP_COSH -> Math.cosh(x);
			case Gpu.MAP_ERF -> erf(x);
			default -> throw new IllegalArgumentException("op " + op);
		};
	}

	/** {@code linalg::%la-erf-1}'s own series, which is what {@code gemm.metal} runs. */
	private static double erf(double x) {
		double ax = Math.abs(x);
		if (ax >= 6.0) {
			return x < 0 ? -1.0 : 1.0;
		}
		double term = 1.0, total = 1.0, xx = 2.0 * ax * ax;
		for (int i = 1; i <= 200; i++) {
			term = term * xx / (2.0 * i + 1.0);
			total += term;
			if (term < 1.0e-17 * total) {
				break;
			}
		}
		double v = 1.1283791670955126 * ax * Math.exp(-(ax * ax)) * total;
		return x < 0 ? -v : v;
	}

	/** {@link Gpu}'s binary op codes, as the scalar oracle spells them. */
	private static float binary(int op, float x, float y) {
		return switch (op) {
			case Gpu.BIN_ADD -> x + y;
			case Gpu.BIN_SUB -> x - y;
			case Gpu.BIN_MUL -> x * y;
			case Gpu.BIN_DIV -> x / y;
			case Gpu.BIN_MAX -> x > y ? x : y;
			case Gpu.BIN_MIN -> x < y ? x : y;
			default -> throw new IllegalArgumentException("op " + op);
		};
	}

}
