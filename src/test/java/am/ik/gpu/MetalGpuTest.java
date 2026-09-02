package am.ik.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

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
 * at every size, which is the guard on a measured refusal. (5) The GEMV lands on the
 * double-accumulated oracle's bits WITHOUT a double -- the compensated accumulator's
 * claim -- and is taken only on the second unwritten sight of its matrix, and the
 * resident set EAGERLY holds that matrix and nothing else -- a measured decision -- with
 * the budget bounding it, a write invalidating it and a collected array freeing it. (6)
 * Lazily ({@code .todo/494}) a result stays in its slab until the host reads it, the
 * resident tier runs over it, and the members whose CPU twin computes in double land on
 * its bits through software binary64 -- the one claim no other backend has to make -- and
 * -- since todo-495 -- a call under the mode commits its command buffer and returns, the
 * wait moving to the first host touch, which is what made the mode the interceptors' own
 * here too.
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
	 * Every test that does not switch lazy results on itself runs under the library's
	 * EAGER contract; an interceptor test that ran earlier in this fork may have switched
	 * lazy results on for the process, so each test starts from the default.
	 */
	@org.junit.jupiter.api.BeforeEach
	void eagerResults() {
		Gpu.lazyResults(false);
	}

	/**
	 * The tests that assert on free device memory or on the resident set hold this, so
	 * they never overlap each other: {@code currentAllocatedSize} and the cache are
	 * properties of the device, not of the thread.
	 */
	private static final String DEVICE_MEMORY = "am.ik.gpu.device-memory";

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
	void theAxisFoldIsDeclinedForItsSizeAtEveryWidth() {
		// A refusal with two measurements behind it (gemm.metal): as a ROUND TRIP the
		// amax/amin half loses to the CPU and the sum half could not be bit-identical in
		// float. Since .todo/494 the fold is a member over a RESIDENT operand only (the
		// sum accumulated in software binary64), which
		// theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits
		// pins; for its size alone it declines at every width however big, and the guard
		// is that widening that without re-measuring fails here.
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
	void aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProduct() {
		// The transposed product (todo-631): the operand is STORED with its last two axes
		// exchanged and the kernel indexes it there rather than being handed a strided
		// copy of it. The tile the fold reads is the same tile, so the claim is EQUALITY
		// -- not a tolerance -- against the plain product of the transposed copy. Only
		// #f, because that is the only width this backend has; the shapes straddle the
		// MPS threshold, so BOTH routes answer it, and the largest one answers it twice,
		// once through each.
		MetalGemm gemm = device();
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
			assertTransposedProductsAgree(a, at, b, bt, batch, n, m, p,
					"%s".formatted(java.util.Arrays.toString(shape)));
			if ((long) n * m * p >= MetalGemm.mpsMinWork()) {
				// The one shape MPS takes, again on the tiled kernel: above the threshold
				// the kernel is otherwise unreachable, and both routes carry the
				// orientation.
				boolean previous = gemm.setMps(false);
				try {
					assertTransposedProductsAgree(a, at, b, bt, batch, n, m, p,
							"%s through the tiled kernel".formatted(java.util.Arrays.toString(shape)));
				}
				finally {
					gemm.setMps(previous);
				}
			}
		}
	}

	/**
	 * The plain product of the transposed copies, and the same product with each operand
	 * read transposed in place: three calls, one answer, bit for bit.
	 */
	private static void assertTransposedProductsAgree(float[] a, float[] at, float[] b, float[] bt, int batch, int n,
			int m, int p, String at1) {
		float[] plain = new float[batch * n * p], left = new float[batch * n * p], right = new float[batch * n * p];
		assertThat(Gpu.multiply(a, 0, n * m, b, 0, 0, plain, 0, batch, n, m, p)).as("%s", at1).isTrue();
		assertThat(Gpu.multiply(at, 0, n * m, true, b, 0, 0, false, left, 0, batch, n, m, p)).as("%s ta", at1).isTrue();
		assertThat(Gpu.multiply(a, 0, n * m, false, bt, 0, 0, true, right, 0, batch, n, m, p)).as("%s tb", at1)
			.isTrue();
		assertBitIdentical(left, plain, "%s ta".formatted(at1));
		assertBitIdentical(right, plain, "%s tb".formatted(at1));
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
		// The twelve libm members; the resident tier's four past them are offered over
		// a resident operand only (the tier test below) and decline here, where nothing
		// is.
		for (int op = 0; op < Gpu.MAP_LIBM_OPS; op++) {
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
		assertThat(Gpu.map(Gpu.MAP_SQRT, x, 0, y, 0, elements)).as("a resident-only member, nothing resident")
			.isFalse();
		assertThat(Gpu.map(Gpu.MAP_EXP, x, 0, y, 0, elements / 64)).as("below the element threshold").isFalse();
		assertThat(out).containsOnly(0.0f);
		assertThat(y).containsOnly(0.0f);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
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
		assertThat(msl).contains("kernel void " + MetalGemm.KERNEL_GEMV_F32);
		for (String kernel : MetalGemm.KERNELS_FUSED) {
			assertThat(msl).contains("kernel void " + kernel);
		}
		for (String kernel : MetalGemm.KERNELS_RESIDENT) {
			assertThat(msl).contains("kernel void " + kernel);
		}
		// The op-code mirrors, whose other halves are Gpu.MAP_* / Gpu.BIN_* and gemm.cu.
		// Nothing links the three, so this is the only thing that notices a slip.
		assertThat(msl).contains("case " + Gpu.MAP_ERF + ": return erf1(x);");
		assertThat(msl).contains("case " + Gpu.BIN_DIV + ": return x / y;");
		assertThat(msl).contains("case " + Gpu.MAP_SQRT + ": {");
		// The fold has an entry point since .todo/494 but NO size threshold: it is a
		// resident-operand member only, and the measured refusal of the round trip
		// stands.
		assertThat(device().thresholds().fold()).isEqualTo(Long.MAX_VALUE);
		// And the GEMV IS a member here now, from a threshold this machine measured.
		assertThat(device().thresholds().matvec()).isLessThan(Long.MAX_VALUE);
		assertThat(Gpu.matvecMinElements()).isEqualTo(device().thresholds().matvec());
	}

	// --- the matrix-by-vector product (vec:matvec, 2026-08-22, on Metal) -------------
	// The one member whose accept-or-decline is RESIDENCY rather than size, exactly as on
	// CUDA: the first sight of a matrix declines and leaves a mark, the second uploads
	// it, every later one finds it, and a write in between starts the count again. The
	// shapes are sized off the threshold IN FORCE, which on this backend is sixteen
	// times CUDA's.

	/** The side of a square matrix comfortably over the GEMV threshold in force. */
	private static int matvecSide() {
		return 16 * (int) Math.ceil(Math.sqrt(2.0 * Gpu.matvecMinElements()) / 16);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aMatrixByVectorProductIsTakenOnlyOnceItsMatrixHasBeenOfferedTwiceUnwritten() {
		DeviceResidency residency = device().residency();
		Gpu.releaseResident();
		int side = matvecSide();
		float[] w = new float[side * side], x = new float[side], y = new float[side], oracle = new float[side];
		// Exact small integers: a reordered sum of them is exact too.
		for (int i = 0; i < w.length; i++) {
			w[i] = (i % 7) - 3;
		}
		for (int j = 0; j < side; j++) {
			x[j] = (j % 5) - 2;
		}
		for (int r = 0; r < side; r++) {
			float acc = 0;
			for (int j = 0; j < side; j++) {
				acc += w[r * side + j] * x[j];
			}
			oracle[r] = acc;
		}
		// First sight: declined, nothing moved, nothing resident.
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, side, side)).isFalse();
		assertThat(y).containsOnly(0.0f);
		assertThat(Gpu.residentBytes()).isZero();
		// Second sight, unwritten: uploaded, computed exactly, and the matrix stays.
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, side, side)).isTrue();
		assertThat(y).isEqualTo(oracle);
		assertThat(Gpu.residentBytes()).isGreaterThanOrEqualTo((long) side * side * Float.BYTES);
		// Third: the matrix is a hit.
		long hits = residency.hits();
		java.util.Arrays.fill(y, 0.0f);
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, side, side)).isTrue();
		assertThat(residency.hits()).isGreaterThan(hits);
		assertThat(y).isEqualTo(oracle);
		// Written: the copy is dropped, the next sight is a first sight again and
		// declines, and the one after that uploads the NEW bytes.
		w[0] = 100;
		Gpu.written(w);
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, side, side)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, side, side)).isTrue();
		oracle[0] += (100 - (-3)) * x[0];
		assertThat(y).isEqualTo(oracle);
	}

	@Test
	void aSingleFloatMatrixByVectorProductLandsOnTheDoubleAccumulatedOracleWithoutADouble() {
		// gemm.metal's claim: a float-float compensated accumulator carries ~48 bits, so
		// against the scalar defun's rule (a double sum, narrowed on the store) only the
		// rare row whose exact sum sits within ~2^-48 of a float rounding boundary can
		// differ -- measured, none of 1024 -- where a plain float sum differs on three
		// rows in four. The same pin as the CUDA kernel's double earns.
		int rows = 1024, cols = (int) ((Gpu.matvecMinElements() + rows - 1) / rows) + 256;
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
	void everyMatrixByVectorOperandIncludingTheResultIsReadFromItsOwnOffset() {
		// The compiled representation keeps a header inside each array: the matrix's
		// elements start at 3, a vector's at 2, and the result's header must survive.
		int side = matvecSide();
		float[] w = new float[3 + side * side], x = new float[2 + side], y = new float[2 + side];
		w[0] = 2;
		w[1] = side;
		w[2] = side;
		x[0] = 1;
		x[1] = side;
		y[0] = 1;
		y[1] = side;
		for (int i = 0; i < side * side; i++) {
			w[3 + i] = (i % 11) - 5;
		}
		for (int j = 0; j < side; j++) {
			x[2 + j] = (j % 3) - 1;
		}
		assertThat(Gpu.matvec(w, 3, x, 2, y, 2, side, side)).isFalse();
		assertThat(Gpu.matvec(w, 3, x, 2, y, 2, side, side)).isTrue();
		assertThat(y[0]).isEqualTo(1.0f);
		assertThat(y[1]).isEqualTo((float) side);
		for (int r = 0; r < side; r++) {
			float acc = 0;
			for (int j = 0; j < side; j++) {
				acc += w[3 + r * side + j] * x[2 + j];
			}
			assertThat(y[2 + r]).as("row %d", r).isEqualTo(acc);
		}
	}

	@Test
	void everyMatrixByVectorDeclineConditionStillDeclinesWithADevicePresent() {
		int side = matvecSide();
		float[] w = new float[side * side], x = new float[side], y = new float[side];
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, 64, 64)).as("below the threshold").isFalse();
		assertThat(Gpu.matvec(w, 0, new float[side - 1], 0, y, 0, side, side)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, new float[side - 1], 0, side, side)).isFalse();
		assertThat(Gpu.matvec(w, 1, x, 0, y, 0, side, side)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 1, y, 0, side, side)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 1, side, side)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, 0, side)).isFalse();
		// And the double half is a hard decline however often the matrix is offered.
		double[] wd = new double[side * side], xd = new double[side], yd = new double[side];
		assertThat(Gpu.matvec(wd, 0, xd, 0, yd, 0, side, side)).isFalse();
		assertThat(Gpu.matvec(wd, 0, xd, 0, yd, 0, side, side)).isFalse();
		assertThat(Gpu.matvec(wd, 0, xd, 0, yd, 0, side, side)).isFalse();
		assertThat(y).containsOnly(0.0f);
		assertThat(yd).containsOnly(0.0);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aRunOfMatrixByVectorProductsSettlesThePoolRatherThanGrowingIt() {
		// A resident matrix, a resident vector and a result slab that is replaced every
		// call and recycled at the next: the steady state is three slabs, and the pool
		// does not grow over a thousand calls.
		MetalGemm gemm = device();
		Gpu.releaseResident();
		int side = matvecSide();
		float[] w = new float[side * side], x = new float[side], y = new float[side];
		assertThat(gemm.gemvF(w, 0, x, 0, y, 0, side, side)).isFalse();
		assertThat(gemm.gemvF(w, 0, x, 0, y, 0, side, side)).isTrue();
		for (int i = 0; i < 20; i++) {
			assertThat(gemm.gemvF(w, 0, x, 0, y, 0, side, side)).isTrue();
		}
		long before = gemm.freeDeviceMemory();
		for (int i = 0; i < 1000; i++) {
			assertThat(gemm.gemvF(w, 0, x, 0, y, 0, side, side)).isTrue();
		}
		assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(64L << 20);
	}

	// --- device residency, on Metal (2026-08-22) -------------------------------------
	// The same DeviceResidency the CUDA half keeps, over this backend's own pool -- and
	// EAGERLY (the library's default mode, the one these tests run in) holding ONE kind
	// of array, the matrix of an accepted GEMV. MetalGemm's class comment has the
	// measurement: with every result coming home, a slab held out of the pool costs the
	// pool a fresh one, so keeping every operand and result resident was slower than the
	// pure pool at every cap. Lazily the set holds every operand and result, which the
	// lazy-results tests above pin. These pin the eager rule and the three properties
	// of the set that is kept: the budget bounds it and a release gives the slabs back to
	// the pool, a collected array frees its copy, and nothing but a GEMV matrix ever
	// enters it.

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void eagerlyOnlyTheMatrixOfAnAcceptedGemvIsKeptResident() {
		DeviceResidency residency = device().residency();
		Gpu.releaseResident();
		int n = (int) Gpu.mapMinElements() * 2;
		float[] a = new float[n], c = new float[n], d = new float[n];
		long hits = residency.hits();
		// An element-wise member's operand and result are scratch: nothing is recorded,
		// and the same operand again is copied in again rather than found.
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		assertThat(Gpu.residentBytes()).isZero();
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, d, 0, n)).isTrue();
		assertThat(Gpu.map(Gpu.MAP_LOG, c, 0, d, 0, n)).isTrue();
		assertThat(residency.hits()).isEqualTo(hits);
		assertThat(Gpu.residentBytes()).isZero();
		int side = square();
		float[] l = new float[side * side], r = new float[side * side], out = new float[side * side];
		assertThat(Gpu.multiply(l, 0, r, 0, out, 0, side, side, side)).isTrue();
		assertThat(Gpu.multiply(l, 0, r, 0, out, 0, side, side, side)).isTrue();
		assertThat(residency.hits()).isEqualTo(hits);
		assertThat(Gpu.residentBytes()).isZero();
		// A GEMV's matrix, on its second sight, is.
		int m = matvecSide();
		float[] w = new float[m * m], x = new float[m], y = new float[m];
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, m, m)).isFalse();
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, m, m)).isTrue();
		assertThat(Gpu.residentBytes()).isEqualTo((long) m * m * Float.BYTES);
		// And its vector and result are not: a third call finds exactly the matrix.
		assertThat(Gpu.matvec(w, 0, x, 0, y, 0, m, m)).isTrue();
		assertThat(residency.hits()).isEqualTo(hits + 1);
		assertThat(Gpu.residentBytes()).isEqualTo((long) m * m * Float.BYTES);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheSlabsBack() {
		DeviceResidency residency = device().residency();
		MetalGemm gemm = device();
		Gpu.releaseResident();
		// 8 MB a matrix, at this backend's threshold.
		int rows = 1024, cols = (int) ((Gpu.matvecMinElements() + rows - 1) / rows);
		long matrix = (long) rows * cols * Float.BYTES;
		long budget = 4 * matrix;
		Gpu.residentBudget(budget);
		List<float[]> reachable = new ArrayList<>();
		float[] x = new float[cols], y = new float[rows];
		try {
			for (int i = 0; i < 16; i++) {
				float[] w = new float[rows * cols];
				reachable.add(w);
				assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isFalse();
				assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isTrue();
				assertThat(Gpu.residentBytes()).as("after matrix %d", i).isLessThanOrEqualTo(budget);
			}
			assertThat(Gpu.residentBytes()).isEqualTo(budget);
			assertThat(residency.budget()).isEqualTo(budget);
			long before = gemm.freeDeviceMemory();
			Gpu.releaseResident();
			assertThat(Gpu.residentBytes()).isZero();
			// The slabs go back to the POOL rather than to the device, so free memory
			// does not move: what residency held out is now on the free lists, and a
			// later call takes it from there.
			assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(64L << 20);
			// Released, so the next sight is a first sight again.
			assertThat(Gpu.matvec(reachable.get(0), 0, x, 0, y, 0, rows, cols)).isFalse();
		}
		finally {
			Gpu.residentBudget(-1);
			Gpu.releaseResident();
		}
		assertThat(reachable).hasSize(16);
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aCollectedHostArrayTakesItsResidentCopyWithIt() throws InterruptedException {
		Gpu.releaseResident();
		int rows = 1024, cols = (int) ((Gpu.matvecMinElements() + rows - 1) / rows);
		float[] keep = new float[rows * cols], x = new float[cols], y = new float[rows];
		assertThat(Gpu.matvec(keep, 0, x, 0, y, 0, rows, cols)).isFalse();
		assertThat(Gpu.matvec(keep, 0, x, 0, y, 0, rows, cols)).isTrue();
		long held = Gpu.residentBytes();
		assertThat(held).isEqualTo((long) rows * cols * Float.BYTES);
		for (int i = 0; i < 8; i++) {
			float[] w = new float[rows * cols];
			assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isFalse();
			assertThat(Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)).isTrue();
		}
		assertThat(Gpu.residentBytes()).isGreaterThan(held);
		// Eight matrices nobody can reach any more: once the collector has them, the
		// next call's drain gives their slabs back, and only the reachable one is left.
		long after = -1;
		for (int attempt = 0; attempt < 20 && after != held; attempt++) {
			System.gc();
			Thread.sleep(20);
			assertThat(Gpu.matvec(keep, 0, x, 0, y, 0, rows, cols)).isTrue();
			after = Gpu.residentBytes();
		}
		assertThat(after).isEqualTo(held);
	}

	// --- lazy results and the resident tier, on Metal (2026-08-23, todo-494) ----------
	// The Apple half of .todo/491, built, measured, NOT switched on for the interceptors
	// until todo-495 made the command buffers asynchronous under it (.kb/gpu.md, "Lazy
	// results and the resident tier on Metal", then "Asynchronous command buffers on
	// Metal"): asked for,
	// a member's result stays in its slab as the host array's DIRTY copy until the host
	// first reads it, every operand a call uploads is kept as a clean one, and the
	// members
	// a round trip had refused run over a resident operand as launches with no copy. The
	// slabs are the pool's, so the claims below are
	// also claims about the pool: a released copy goes back to the free lists, not to the
	// device. And one claim that is this backend's alone: the members whose CPU twin
	// computes in double land on its bits WITHOUT a double -- gemm.metal runs binary64 in
	// software where float arithmetic cannot be the CPU's bits -- which the soft-f64 test
	// pins over the bit patterns a float kernel gets wrong (subnormals first).

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theInterceptorsRequestSwitchesLazyResultsOnHereAndTheDefaultStaysEager() {
		// The decision of todo-494, reversed by todo-495 and pinned: lazy results pay on
		// this backend now that a call under the mode does not wait for its command
		// buffer (.kb/gpu.md, "Asynchronous command buffers on Metal"), so the request
		// the interceptors make switches the mode on -- while the library's default,
		// and the contract every method's javadoc states, is still eager.
		MetalGemm gemm = device();
		Gpu.releaseResident();
		assertThat(gemm.lazyResultsPay()).isTrue();
		assertThat(gemm.lazyResultsOn()).isFalse();
		int n = (int) Gpu.mapMinElements() * 2;
		float[] a = new float[n], c = new float[n];
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
		assertThat(c[7]).as("eager: the result is in its array when the call returns").isEqualTo(1.0f);
		assertThat(Gpu.resident(c)).isFalse();
		Gpu.lazyResultsIfWorthwhile();
		try {
			assertThat(gemm.lazyResultsOn()).as("the interceptors' request is honoured here").isTrue();
			float[] d = new float[n];
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, d, 0, n)).isTrue();
			assertThat(d[7]).as("lazy: the result stays on the device").isZero();
			assertThat(Gpu.resident(d)).isTrue();
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
		assertThat(gemm.lazyResultsOn()).isFalse();
	}

	/** An array the device holds a copy of: a libm map over it under lazy results. */
	private static void makeResident(float[] a) {
		assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, new float[a.length], 0, a.length)).isTrue();
		assertThat(Gpu.resident(a)).isTrue();
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aLazyResultStaysOnTheDeviceUntilTheHostFirstReadsIt() {
		DeviceResidency residency = device().residency();
		Gpu.releaseResident();
		int n = (int) Gpu.mapMinElements() * 2;
		float[] a = new float[n], c = new float[n], e = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = (i % 97) / 100.0f;
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
				assertThat(e[i]).as("log(exp(a[%d]))", i).isCloseTo(a[i], within(1e-5f));
			}
			Gpu.materialize(c);
			assertThat(residency.dirtyCount()).isZero();
			assertThat(c[12345]).isCloseTo((float) Math.exp(a[12345]), within(1e-6f));
			Gpu.materialize(c);
			// Both stay resident, clean, for the next member.
			assertThat(Gpu.resident(c)).isTrue();
			assertThat(Gpu.resident(e)).isTrue();
			// An array the device never saw, or no array at all, is simply nothing to do.
			Gpu.materialize(new float[4]);
			Gpu.materialize("not an array");
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aWriteToALazyResultBringsItHomeFirst() {
		DeviceResidency residency = device().residency();
		Gpu.releaseResident();
		int n = (int) Gpu.mapMinElements() * 2;
		float[] a = new float[n], c = new float[n], d = new float[n];
		Gpu.lazyResults(true);
		try {
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
			assertThat(c[100]).isZero();
			// The write hook, called BEFORE the store as every enumerated setter calls
			// it: the result is downloaded, then forgotten, and the store lands on the
			// real bytes.
			Gpu.written(c);
			assertThat(c[100]).isEqualTo(1.0f);
			assertThat(Gpu.resident(c)).isFalse();
			c[100] = 2.0f;
			long misses = residency.misses();
			assertThat(Gpu.map(Gpu.MAP_LOG, c, 0, d, 0, n)).isTrue();
			assertThat(residency.misses()).as("the written array is uploaded again").isEqualTo(misses + 1);
			Gpu.materialize(d);
			assertThat(d[100]).isCloseTo((float) Math.log(2.0), within(1e-6f));
			assertThat(d[99]).isZero();
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void anEvictedOrReleasedLazyResultIsDownloadedNotDropped() {
		DeviceResidency residency = device().residency();
		MetalGemm gemm = device();
		Gpu.releaseResident();
		int n = (int) Gpu.mapMinElements() * 2; // 1 MB a slab
		long budget = 4L * n * Float.BYTES;
		Gpu.residentBudget(budget);
		Gpu.lazyResults(true);
		List<float[]> results = new ArrayList<>();
		try {
			float[] a = new float[n];
			for (int i = 0; i < n; i++) {
				a[i] = (i % 89) / 100.0f;
			}
			// Thirty-two lazy results against a budget that holds four: the cap evicts
			// by DOWNLOADING, and every evicted array holds its answer without anyone
			// having read it.
			for (int i = 0; i < 32; i++) {
				float[] c = new float[n];
				results.add(c);
				assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, c, 0, n)).isTrue();
				assertThat(Gpu.residentBytes()).as("after call %d", i).isLessThanOrEqualTo(budget);
			}
			assertThat(residency.dirtyCount()).isGreaterThan(0);
			int stillResident = 0;
			for (float[] c : results) {
				if (Gpu.resident(c)) {
					stillResident++;
					assertThat(c[777]).as("an unread lazy result is still on the device").isZero();
				}
				else {
					assertThat(c[777]).as("an evicted lazy result came home")
						.isCloseTo((float) Math.exp(a[777]), within(1e-6f));
				}
			}
			// The LRU evicts CLEAN copies first -- the operand a, whose eviction costs
			// one upload -- and dirty ones, whose eviction costs a download, only when
			// no clean one is left: so what the cap holds is the last few results.
			assertThat(stillResident).isBetween(1, 4);
			// A release (and switching lazy results off) flushes what is left the same
			// way: nothing is lost, and the slabs go back to the POOL -- free memory
			// does not move, because the pool is the whole point here.
			long before = gemm.freeDeviceMemory();
			Gpu.lazyResults(false);
			assertThat(residency.dirtyCount()).isZero();
			for (float[] c : results) {
				assertThat(c[777]).isCloseTo((float) Math.exp(a[777]), within(1e-6f));
			}
			Gpu.releaseResident();
			assertThat(Gpu.residentBytes()).isZero();
			assertThat(Math.abs(before - gemm.freeDeviceMemory())).isLessThan(64L << 20);
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.residentBudget(-1);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits() {
		Gpu.releaseResident();
		// Over this backend's resident floor, so the members are accepted for the
		// operand's residency and not declined for their size.
		int n = (int) Math.max(MetalGemm.MIN_RESIDENT_ELEMENTS, Gpu.mapMinElements()) * 2;
		float[] a = new float[n], b = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = (float) Math.sin(i * 0.37) * 3;
			b[i] = (float) Math.cos(i * 0.11) + 0.01f;
		}
		float[] out = new float[n];
		Gpu.lazyResults(true);
		try {
			// Nothing resident: every member of the tier declines, at any size -- a
			// round trip cannot beat the caller's lane loop, and the library does not
			// try.
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
			assertThat(Gpu.fold(Gpu.FOLD_SUM, a, 0, new float[n / 256], 0, n / 256, 256, 1)).isFalse();
			// Make a resident (a libm member over it), and every member is a launch that
			// reads the device's copy. Each lands on the CPU kernel's bits -- float
			// arithmetic where that is exact, software binary64 where it is not -- so
			// the comparison is EQUALITY.
			makeResident(a);
			int[] binary = { Gpu.BIN_ADD, Gpu.BIN_SUB, Gpu.BIN_MUL, Gpu.BIN_DIV, Gpu.BIN_MAX, Gpu.BIN_MIN, Gpu.BIN_GT,
					Gpu.BIN_GE, Gpu.BIN_LT, Gpu.BIN_LE, Gpu.BIN_EQ };
			assertThat(binary).hasSize(Gpu.BIN_OPS);
			for (int op : binary) {
				assertThat(Gpu.zip(op, a, 0, b, 0, out, 0, n)).as("zip %d", op).isTrue();
				Gpu.materialize(out);
				for (int i = 0; i < n; i += 13) {
					assertThat(out[i]).as("zip %d at %d", op, i).isEqualTo((float) apply(op, a[i], b[i]));
				}
				// The other operand resident is enough too (b became one as an operand).
				assertThat(Gpu.zip(op, b, 0, a, 0, out, 0, n)).as("zip %d swapped", op).isTrue();
				Gpu.materialize(out);
				for (int i = 0; i < n; i += 13) {
					assertThat(out[i]).as("zip %d swapped at %d", op, i).isEqualTo((float) apply(op, b[i], a[i]));
				}
				// A scalar that is not a float takes the software route; 0.25 the float
				// one.
				for (double s : new double[] { 0.3, 0.25 }) {
					assertThat(Gpu.scale(op, a, 0, s, false, out, 0, n)).as("scale %d", op).isTrue();
					Gpu.materialize(out);
					for (int i = 0; i < n; i += 13) {
						assertThat(out[i]).as("scale %d by %s at %d", op, s, i).isEqualTo((float) apply(op, a[i], s));
					}
					assertThat(Gpu.scale(op, a, 0, s, true, out, 0, n)).as("scale %d swapped", op).isTrue();
					Gpu.materialize(out);
					for (int i = 0; i < n; i += 13) {
						assertThat(out[i]).as("scale %d by %s swapped at %d", op, s, i)
							.isEqualTo((float) apply(op, s, a[i]));
					}
				}
			}
			// The four maps the round trip refused.
			for (int op : new int[] { Gpu.MAP_SQRT, Gpu.MAP_ABS, Gpu.MAP_NEGATIVE, Gpu.MAP_SIGN }) {
				assertThat(Gpu.map(op, a, 0, out, 0, n)).as("map %d", op).isTrue();
				Gpu.materialize(out);
				for (int i = 0; i < n; i += 13) {
					double d = a[i];
					float expected = (float) switch (op) {
						case Gpu.MAP_SQRT -> Math.sqrt(d);
						case Gpu.MAP_ABS -> Math.abs(d);
						case Gpu.MAP_NEGATIVE -> -d;
						default -> Math.signum(d);
					};
					// Bit for bit, sqrt's NaN (a negative operand) included.
					assertThat(Float.floatToRawIntBits(out[i])).as("map %d at %d", op, i)
						.isEqualTo(Float.floatToRawIntBits(expected));
				}
			}
			// where over a broadcast mask and a scalar y, at BOTH mask widths (todo-645):
			// a double mask is not the hard decline every other double operand here is,
			// because the mask is a predicate read as raw words -- the same bits, the
			// same select, whichever width carried it. The cells cover every case the
			// integer test has to separate: zero, a negative zero (which is FALSE, like
			// the CPU's (/= m 0)), an ordinary value, and a NaN (which is TRUE).
			int rows = 64, cols = n / rows;
			float[] mask = new float[cols];
			double[] maskD = new double[cols];
			for (int j = 0; j < cols; j++) {
				maskD[j] = switch (j % 4) {
					case 0 -> 0.0;
					case 1 -> -0.0;
					case 2 -> Double.NaN;
					default -> 1.0;
				};
				mask[j] = (float) maskD[j];
			}
			for (Object mk : new Object[] { mask, maskD }) {
				String what = mk == mask ? "f32 mask" : "f64 mask";
				// A result of its own: `out` may be a lazy device copy by now, and a
				// host write into one is the hazard the residency design forbids.
				float[] selected = new float[n];
				assertThat(Gpu.where(mk, 0, new int[] { 0, 1 }, 0.0, a, 0, new int[] { cols, 1 }, 0.0, null, 0,
						new int[] { 0, 0 }, -9.5, selected, 0, new int[] { rows, cols }))
					.as(what)
					.isTrue();
				Gpu.materialize(selected);
				for (int i = 0; i < n; i += 7) {
					float expected = maskD[i % cols] != 0.0 ? a[i] : -9.5f;
					assertThat(Float.floatToRawIntBits(selected[i])).as("%s at %d", what, i)
						.isEqualTo(Float.floatToRawIntBits(expected));
				}
			}
			// A scalar mask and a scalar x.
			assertThat(Gpu.where(null, 0, new int[] { 0 }, 1.0, null, 0, new int[] { 0 }, 2.5, a, 0, new int[] { 1 },
					0.0, out, 0, new int[] { n }))
				.isTrue();
			Gpu.materialize(out);
			assertThat(out[17]).isEqualTo(2.5f);
			// The Adam update over a resident gradient, against the CPU kernel's
			// arithmetic -- every step of it software binary64 here, and every step on
			// the CPU's bits.
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
				Gpu.materialize(xr);
				Gpu.materialize(mr);
				Gpu.materialize(vr);
				assertThat(xr).as("x, mode %d", mode).isEqualTo(xe);
				assertThat(mr).as("m, mode %d", mode).isEqualTo(me);
				assertThat(vr).as("v, mode %d", mode).isEqualTo(ve);
			}
			// The axis fold over a resident operand: the sum accumulated in software
			// binary64 lands on %la-fold-axis's double-accumulated bits; amax and amin
			// move bits.
			int len = 256, cells = n / len;
			float[] folded = new float[cells];
			for (int op : new int[] { Gpu.FOLD_SUM, Gpu.FOLD_AMAX, Gpu.FOLD_AMIN }) {
				assertThat(Gpu.fold(op, a, 0, folded, 0, cells, len, 1)).as("fold %d", op).isTrue();
				Gpu.materialize(folded);
				for (int cell = 0; cell < cells; cell += 5) {
					double acc = op == Gpu.FOLD_SUM ? 0.0 : a[cell * len];
					for (int k = op == Gpu.FOLD_SUM ? 0 : 1; k < len; k++) {
						double value = a[cell * len + k];
						if (op == Gpu.FOLD_SUM) {
							acc += value;
						}
						else if (op == Gpu.FOLD_AMAX ? value > acc : value < acc) {
							acc = value;
						}
					}
					assertThat(folded[cell]).as("fold %d cell %d", op, cell).isEqualTo((float) acc);
				}
			}
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace() {
		Gpu.releaseResident();
		// Over the resident floor and the map threshold, so the exp below really makes
		// a resident and the copies are accepted.
		int rows = 256, cols = (int) Math.max(MetalGemm.MIN_RESIDENT_ELEMENTS, Gpu.mapMinElements()) * 2 / rows,
				n = rows * cols;
		float[] a = new float[3 + n];
		a[0] = 2;
		a[1] = rows;
		a[2] = cols;
		for (int i = 0; i < n; i++) {
			a[3 + i] = (float) Math.sin(i * 0.13) * 7;
		}
		int[] spanA = { 3, n };
		Gpu.lazyResults(true);
		try {
			// Not resident: declined, whatever the walk.
			float[] out = new float[3 + n];
			assertThat(Gpu.copy(a, 3, new int[] { 1 }, spanA, out, 3, new int[] { 1 }, new int[] { 3, n },
					new int[] { n }))
				.isFalse();
			// Bound to a local rather than passed anonymously: it becomes a resident
			// copy the byte totals below count, and an anonymous one is unreachable from
			// the moment it is made.
			float[] exp = new float[3 + n];
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 3, exp, 3, n)).isTrue();
			// reshape: one contiguous walk.
			assertThat(Gpu.copy(a, 3, new int[] { 1 }, spanA, out, 3, new int[] { 1 }, new int[] { 3, n },
					new int[] { n }))
				.isTrue();
			Gpu.materialize(out);
			assertThat(java.util.Arrays.copyOfRange(out, 3, 3 + n))
				.isEqualTo(java.util.Arrays.copyOfRange(a, 3, 3 + n));
			// transpose: out[j][i] = a[i][j].
			float[] t = new float[3 + n];
			assertThat(Gpu.copy(a, 3, new int[] { 1, cols }, spanA, t, 3, new int[] { rows, 1 }, new int[] { 3, n },
					new int[] { cols, rows }))
				.isTrue();
			Gpu.materialize(t);
			for (int i = 0; i < rows; i += 7) {
				for (int j = 0; j < cols; j += 5) {
					assertThat(t[3 + j * rows + i]).isEqualTo(a[3 + i * cols + j]);
				}
			}
			// a slice with a NEGATIVE step: rows 200..20 step -3, columns 10..cols-4
			// step 2.
			int sr = (200 - 20 + 2) / 3, sc = (cols - 4 - 10 + 1) / 2;
			float[] sl = new float[3 + sr * sc];
			assertThat(Gpu.copy(a, 3 + 200 * cols + 10, new int[] { -3 * cols, 2 }, spanA, sl, 3, new int[] { sc, 1 },
					new int[] { 3, sr * sc }, new int[] { sr, sc }))
				.isTrue();
			Gpu.materialize(sl);
			for (int i = 0; i < sr; i++) {
				for (int j = 0; j < sc; j++) {
					assertThat(sl[3 + i * sc + j]).isEqualTo(a[3 + (200 - 3 * i) * cols + 10 + 2 * j]);
				}
			}
			// a walk that leaves the span declines, on either side.
			assertThat(Gpu.copy(a, 3 + 200 * cols + 10, new int[] { -3 * cols, 2 }, spanA, sl, 3, new int[] { sc, 1 },
					new int[] { 3, sr * sc - 1 }, new int[] { sr, sc }))
				.isFalse();
			assertThat(Gpu.copy(a, 3, new int[] { -1 }, spanA, out, 3, new int[] { 1 }, new int[] { 3, n },
					new int[] { 2 }))
				.isFalse();
			// concatenate: two halves of a into out's two slabs -- the second copy finds
			// the output resident and writes into it in place.
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
			Gpu.materialize(cat);
			assertThat(java.util.Arrays.copyOfRange(cat, 3, 3 + n))
				.isEqualTo(java.util.Arrays.copyOfRange(a, 3, 3 + n));
			// The in-place scale over the resident a: the same slab, marked dirty, and
			// the host sees the product on its first read.
			resident = Gpu.residentBytes();
			float[] expected = a.clone();
			for (int i = 0; i < n; i++) {
				expected[3 + i] = (float) ((double) a[3 + i] * 0.25);
			}
			assertThat(Gpu.scale(Gpu.BIN_MUL, a, 3, 0.25, false, a, 3, n)).isTrue();
			assertThat(Gpu.residentBytes()).isEqualTo(resident);
			Gpu.materialize(a);
			assertThat(a).isEqualTo(expected);
			// The resident totals above count SIX arrays, and four of them (the exp
			// result, out, t, sl) are dead to the JIT long before the last one is read.
			// A resident copy goes when its host array does -- that is this backend's
			// design and `aCollectedHostArrayTakesItsResidentCopyWithIt` pins it -- so a
			// collection between the capture of `resident` and the assertion drops them
			// and the count falls to the two still referenced. Seen: `expected 5364080
			// but was 2097152`, 2 MB being exactly `a` and `cat`, once every three runs,
			// with `a.clone()` just above as the allocation that triggers it. These
			// fences are what make the totals mean what they say.
			java.lang.ref.Reference.reachabilityFence(exp);
			java.lang.ref.Reference.reachabilityFence(out);
			java.lang.ref.Reference.reachabilityFence(t);
			java.lang.ref.Reference.reachabilityFence(sl);
			java.lang.ref.Reference.reachabilityFence(cat);
			java.lang.ref.Reference.reachabilityFence(a);
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theSoftwareBinary64RouteLandsOnJavasDoubleArithmeticBitForBit() {
		// What makes the resident tier possible on a backend without a double: the
		// scalar forms over a scalar that is not a float, the Adam update and the sum
		// fold run IEEE binary64 in software (gemm.metal), and this GPU flushes subnormal
		// floats to zero in every float operation, so the float route is guarded by it
		// too. Asserted over the bit patterns a float kernel gets wrong -- subnormals,
		// the specials, the tiny and the huge -- as equality with Java's arithmetic,
		// which is the CPU kernels'.
		Gpu.releaseResident();
		int n = (int) Math.max(MetalGemm.MIN_RESIDENT_ELEMENTS, Gpu.mapMinElements()) * 2;
		Random random = new Random(31);
		float[] a = new float[n], out = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = switch (i % 8) {
				case 0 -> (float) random.nextGaussian();
				case 1 -> (float) (random.nextGaussian() * 1e30);
				case 2 -> (float) (random.nextGaussian() * 1e-30);
				case 3 -> Float.intBitsToFloat(random.nextInt());
				case 4 -> Float.intBitsToFloat(random.nextInt(0x00800000));
				case 5 -> random.nextInt(1000) - 500;
				case 6 -> (float) (random.nextGaussian() * 1e-40);
				default -> random.nextFloat() * 2 - 1;
			};
		}
		double[] scalars = { 0.3, 7, 2.5, 1e300, 1e-300, 1e-40, -0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, 3.0e-4,
				1.0 / 3, 1e38, 1e-45, 123456789.123456789, -1e-310, Double.MIN_VALUE, Double.MAX_VALUE, 0.999,
				Double.longBitsToDouble(random.nextLong()), random.nextGaussian() * 1e-20,
				(float) random.nextGaussian() };
		Gpu.lazyResults(true);
		try {
			makeResident(a);
			for (double s : scalars) {
				for (int op = 0; op < Gpu.BIN_OPS; op++) {
					for (boolean swap : new boolean[] { false, true }) {
						assertThat(Gpu.scale(op, a, 0, s, swap, out, 0, n)).as("scale %d by %s", op, s).isTrue();
						Gpu.materialize(out);
						for (int i = 0; i < n; i++) {
							float expected = (float) (swap ? apply(op, s, a[i]) : apply(op, a[i], s));
							if (Float.floatToRawIntBits(out[i]) != Float.floatToRawIntBits(expected)
									&& !(Float.isNaN(out[i]) && Float.isNaN(expected))) {
								org.assertj.core.api.Assertions
									.fail("scale op %d by %s swap %s at %d (a = %s): %s where Java says %s"
										.formatted(op, s, swap, i, a[i], out[i], expected));
							}
						}
					}
				}
			}
			// The Adam update over the same bit patterns (the gradient is a, resident).
			float[] x = new float[n], m = new float[n], v = new float[n];
			for (int i = 0; i < n; i++) {
				x[i] = (float) random.nextGaussian();
				m[i] = (float) random.nextGaussian() * 0.01f;
				v[i] = Math.abs((float) random.nextGaussian()) * 0.001f;
			}
			float[] xe = x.clone(), me = m.clone(), ve = v.clone();
			for (int step = 1; step <= 3; step++) {
				double lr = 3e-4 * step;
				double[] rule = { lr, lr * 0.1, 0.1, 0.9, 1 - 0.9, 0.999, 1 - 0.999, 1e-8, 1 - Math.pow(0.9, step),
						1 - Math.pow(0.999, step), step % 3 };
				assertThat(Gpu.adamStep(x, 0, a, 0, m, 0, v, 0, n, rule)).as("step %d", step).isTrue();
				adamReference(xe, a, me, ve, rule);
				Gpu.materialize(x);
				Gpu.materialize(m);
				Gpu.materialize(v);
				for (int i = 0; i < n; i++) {
					assertThat(Float.floatToRawIntBits(x[i])).as("x at %d, step %d", i, step)
						.isEqualTo(Float.floatToRawIntBits(xe[i]));
					assertThat(Float.floatToRawIntBits(m[i])).as("m at %d", i)
						.isEqualTo(Float.floatToRawIntBits(me[i]));
					assertThat(Float.floatToRawIntBits(v[i])).as("v at %d", i)
						.isEqualTo(Float.floatToRawIntBits(ve[i]));
				}
			}
			// And the equal-shape ops over two such arrays: the float route with its
			// flush guard, against the same arithmetic.
			float[] b = new float[n];
			for (int i = 0; i < n; i++) {
				b[i] = a[(i * 7919) % n];
			}
			for (int op = 0; op < Gpu.BIN_OPS; op++) {
				assertThat(Gpu.zip(op, a, 0, b, 0, out, 0, n)).as("zip %d", op).isTrue();
				Gpu.materialize(out);
				for (int i = 0; i < n; i++) {
					float expected = (float) apply(op, a[i], b[i]);
					if (Float.floatToRawIntBits(out[i]) != Float.floatToRawIntBits(expected)
							&& !(Float.isNaN(out[i]) && Float.isNaN(expected))) {
						org.assertj.core.api.Assertions.fail("zip op %d at %d (%s, %s): %s where Java says %s"
							.formatted(op, i, a[i], b[i], out[i], expected));
					}
				}
			}
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	/** {@code laApply}: the CPU kernels' binary op table, in double. */
	/**
	 * One chain member, run on the DEVICE and asserted to have run there. Every member of
	 * a fused composition is offered on this backend over a RESIDENT operand -- the folds
	 * and the per-row members are below every size threshold here -- so the chain is
	 * built under lazy results, which is what keeps each result on the device for the
	 * next member to be offered over.
	 */
	private static final class Chain {

		float[] fresh(int n) {
			return new float[n];
		}

		float[] map(int op, float[] a) {
			float[] c = fresh(a.length);
			assertThat(Gpu.map(op, a, 0, c, 0, a.length)).as("map %d", op).isTrue();
			return c;
		}

		float[] zip(int op, float[] a, float[] b) {
			float[] c = fresh(a.length);
			assertThat(Gpu.zip(op, a, 0, b, 0, c, 0, a.length)).as("zip %d", op).isTrue();
			return c;
		}

		float[] scale(int op, float[] a, double s, boolean swap) {
			float[] c = fresh(a.length);
			assertThat(Gpu.scale(op, a, 0, s, swap, c, 0, a.length)).as("scale %d", op).isTrue();
			return c;
		}

		/** {@code a}, {@code rows x len}, folded over its last axis. */
		float[] fold(int op, float[] a, int rows, int len) {
			float[] c = fresh(rows);
			assertThat(Gpu.fold(op, a, 0, c, 0, rows, len, 1)).as("fold %d", op).isTrue();
			return c;
		}

		/** {@code a}, {@code rows x len}, against the per-row {@code b}, broadcast. */
		float[] bcast(int op, float[] a, float[] b, int rows, int len) {
			float[] c = fresh(rows * len);
			int[] dims = { rows, len }, sa = { len, 1 }, sb = { 1, 0 };
			assertThat(Gpu.bcast(op, a, 0, sa, b, 0, sb, c, 0, dims)).as("bcast %d", op).isTrue();
			return c;
		}

		float[] home(float[] a) {
			Gpu.materialize(a);
			return a;
		}

	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theFusedTierLandsOnTheComposedDeviceChainsBits() {
		// The whole claim of the tier (todo-636): a fused kernel IS the chain of device
		// members it replaces, rounding for rounding -- so its result is that chain's bit
		// for bit, libm members included (kernel and chain call the same exp and the same
		// erf1 at the same width, and the same software binary64 where the chain's
		// arithmetic is genuinely f64). Each composition runs twice, member by member and
		// fused. Only #f: it is the only width here.
		//
		// The shape is 16384 rows of 64, because on THIS backend a chain member over the
		// per-row intermediate (rows elements) has to clear MIN_RESIDENT_ELEMENTS to be
		// offered at all -- the row count is what that bounds, not the element count.
		Gpu.releaseResident();
		int rows = (int) Math.max(MetalGemm.MIN_RESIDENT_ELEMENTS, 1024), len = 64, n = rows * len;
		double eps = 1.0e-5;
		Chain ch = new Chain();
		Random random = new Random(11);
		float[] x = new float[n], g = new float[n], old = new float[n], zeros = new float[n];
		for (int i = 0; i < n; i++) {
			x[i] = (float) (random.nextDouble() * 6 - 3);
			g[i] = (float) (random.nextDouble() * 2 - 1);
			old[i] = (float) random.nextDouble();
		}
		Gpu.lazyResults(true);
		try {
			makeResident(x);
			makeResident(g);
			makeResident(zeros);
			// softmax: amax, sub, exp, sum, div.
			float[] m = ch.fold(Gpu.FOLD_AMAX, x, rows, len);
			float[] e = ch.map(Gpu.MAP_EXP, ch.bcast(Gpu.BIN_SUB, x, m, rows, len));
			float[] out = ch.bcast(Gpu.BIN_DIV, e, ch.fold(Gpu.FOLD_SUM, e, rows, len), rows, len);
			float[] fused = new float[n];
			assertThat(Gpu.softmax(x, 0, fused, 0, rows, len)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(out), "softmax");
			// its adjoint: mul, sum, sub, mul.
			float[] tot = ch.fold(Gpu.FOLD_SUM, ch.zip(Gpu.BIN_MUL, g, out), rows, len);
			float[] dx = ch.zip(Gpu.BIN_MUL, out, ch.bcast(Gpu.BIN_SUB, g, tot, rows, len));
			fused = new float[n];
			assertThat(Gpu.softmaxGrad(g, 0, out, 0, fused, 0, rows, len)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(dx), "softmax grad");
			// log-softmax: amax, sub, exp, sum, log, sub -- the deviation recomputed in
			// the kernel's third pass rather than stored, which is the same value.
			float[] s = ch.bcast(Gpu.BIN_SUB, x, m, rows, len);
			float[] lg = ch.map(Gpu.MAP_LOG, ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_EXP, s), rows, len));
			float[] lout = ch.bcast(Gpu.BIN_SUB, s, lg, rows, len);
			fused = new float[n];
			assertThat(Gpu.logSoftmax(x, 0, fused, 0, rows, len)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(lout), "log-softmax");
			// its adjoint: sum, exp, mul, sub.
			float[] ldx = ch.zip(Gpu.BIN_SUB, g,
					ch.bcast(Gpu.BIN_MUL, ch.map(Gpu.MAP_EXP, lout), ch.fold(Gpu.FOLD_SUM, g, rows, len), rows, len));
			fused = new float[n];
			assertThat(Gpu.logSoftmaxGrad(g, 0, lout, 0, fused, 0, rows, len)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(ldx), "log-softmax grad");
			// gelu: mul 0.5, div sqrt 2, erf, 1 + , mul.
			float[] t1 = ch.scale(Gpu.BIN_MUL, x, 0.5, false);
			float[] t2 = ch.scale(Gpu.BIN_DIV, x, 1.4142135623730951, false);
			float[] t4 = ch.scale(Gpu.BIN_ADD, ch.map(Gpu.MAP_ERF, t2), 1.0, true);
			float[] gelu = ch.zip(Gpu.BIN_MUL, t1, t4);
			fused = new float[n];
			assertThat(Gpu.gelu(x, 0, fused, 0, n)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(gelu), "gelu");
			// its adjoint, with and without an accumulated gradient to fold onto.
			float[] g1 = ch.zip(Gpu.BIN_MUL, g, t4), g4 = ch.zip(Gpu.BIN_MUL, g, t1);
			float[] ex = ch.map(Gpu.MAP_EXP, ch.map(Gpu.MAP_NEGATIVE, ch.zip(Gpu.BIN_MUL, t2, t2)));
			float[] g2 = ch.zip(Gpu.BIN_MUL, g4, ch.scale(Gpu.BIN_MUL, ex, 1.1283791670955126, true));
			float[] b = ch.scale(Gpu.BIN_DIV, g2, 1.4142135623730951, false);
			float[] a = ch.scale(Gpu.BIN_MUL, g1, 0.5, false);
			float[] dxNew = ch.zip(Gpu.BIN_ADD, b, a);
			makeResident(old);
			float[] dxOld = ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, old, b), a);
			fused = new float[n];
			float[] fusedOld = new float[n];
			assertThat(Gpu.geluGrad(g, 0, x, 0, null, 0, fused, 0, n)).isTrue();
			assertThat(Gpu.geluGrad(g, 0, x, 0, old, 0, fusedOld, 0, n)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(dxNew), "gelu grad");
			assertBitIdentical(ch.home(fusedOld), ch.home(dxOld), "gelu grad onto old");
			// layer-norm: mean, sub, the variance's square / sum / divisor, eps, sqrt,
			// div.
			float[] mu = ch.scale(Gpu.BIN_DIV, ch.fold(Gpu.FOLD_SUM, x, rows, len), len, false);
			float[] dev = ch.bcast(Gpu.BIN_SUB, x, mu, rows, len);
			float[] v = ch.scale(Gpu.BIN_DIV, ch.fold(Gpu.FOLD_SUM, ch.zip(Gpu.BIN_MUL, dev, dev), rows, len), len,
					false);
			float[] sd = ch.map(Gpu.MAP_SQRT, ch.scale(Gpu.BIN_ADD, v, eps, false));
			float[] norm = ch.bcast(Gpu.BIN_DIV, dev, sd, rows, len);
			fused = new float[n];
			assertThat(Gpu.layerNorm(x, 0, fused, 0, rows, len, eps)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(norm), "layer-norm");
			// its adjoint, the tape's own walk: the division's two adjoints, sqrt's, the
			// divisor's, the broadcast onto zeros, the squared deviations' two, the two
			// means' -- and the four contributions folded onto x's gradient in order.
			float[] gDev = ch.bcast(Gpu.BIN_DIV, g, sd, rows, len);
			float[] r = ch.bcast(Gpu.BIN_DIV, ch.zip(Gpu.BIN_MUL, g, dev), ch.zip(Gpu.BIN_MUL, sd, sd), rows, len);
			float[] gSd = ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_NEGATIVE, r), rows, len);
			float[] gVe = ch.zip(Gpu.BIN_DIV, gSd, ch.scale(Gpu.BIN_MUL, sd, 2.0, true));
			float[] gSq = ch.bcast(Gpu.BIN_ADD, zeros, ch.scale(Gpu.BIN_DIV, gVe, len, false), rows, len);
			float[] gd2 = ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_MUL, gSq, dev), ch.zip(Gpu.BIN_MUL, gSq, dev));
			float[] gM2 = ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_NEGATIVE, gd2), rows, len);
			float[] a2 = ch.scale(Gpu.BIN_DIV, ch.bcast(Gpu.BIN_ADD, zeros, gM2, rows, len), len, false);
			float[] gMu = ch.fold(Gpu.FOLD_SUM, ch.map(Gpu.MAP_NEGATIVE, gDev), rows, len);
			float[] a4 = ch.scale(Gpu.BIN_DIV, ch.bcast(Gpu.BIN_ADD, zeros, gMu, rows, len), len, false);
			float[] lnNew = ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, gd2, a2), gDev), a4);
			float[] lnOld = ch.zip(Gpu.BIN_ADD,
					ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, ch.zip(Gpu.BIN_ADD, old, gd2), a2), gDev), a4);
			fused = new float[n];
			float[] fusedOldLn = new float[n];
			assertThat(Gpu.layerNormGrad(g, 0, x, 0, null, 0, fused, 0, rows, len, eps)).isTrue();
			assertThat(Gpu.layerNormGrad(g, 0, x, 0, old, 0, fusedOldLn, 0, rows, len, eps)).isTrue();
			assertBitIdentical(ch.home(fused), ch.home(lnNew), "layer-norm grad");
			assertBitIdentical(ch.home(fusedOldLn), ch.home(lnOld), "layer-norm grad onto old");
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	@ResourceLock(DEVICE_MEMORY)
	void theScaledAndMaskedSoftmaxLandsOnTheComposedDeviceChainsBits() {
		// The attention head's idiom (todo-643): torch:div then torch:masked-fill then
		// torch:softmax, folded into the softmax pair. The claim is the tier's -- the
		// fused kernel IS the chain, rounding for rounding -- so the oracle is the chain
		// run on the DEVICE: the scale through scal_f32, the mask's select on the host
		// (a select is exact, whichever side runs it, and spelling it here keeps the
		// oracle independent of whether whereF took the mask) and then the plain fused
		// softmax.
		//
		// Three scales cover both of scal_f32's routes: 8 is a power of two and reaches
		// the kernel already rewritten to the multiply by its exact reciprocal, 3 is an
		// exact divide, and sqrt 2 is not a float at all and takes the software binary64
		// route both in the chain and in the kernel. Both mask widths, both fills, and
		// both of the packed mask's reads -- the shuffled one where the mask is a whole
		// number of 32-aligned rows, the cell-by-cell one where it is not.
		Gpu.releaseResident();
		int rows = (int) Math.max(MetalGemm.MIN_RESIDENT_ELEMENTS, 1024), len = 64, n = rows * len;
		Chain ch = new Chain();
		Random random = new Random(23);
		float[] x = new float[n], g = new float[n];
		for (int i = 0; i < n; i++) {
			x[i] = (float) (random.nextDouble() * 6 - 3);
			g[i] = (float) (random.nextDouble() * 2 - 1);
		}
		Gpu.lazyResults(true);
		try {
			makeResident(x);
			makeResident(g);
			for (double sc : new double[] { 8.0, 3.0, 1.4142135623730951 }) {
				// maskLen 4 rows: a whole number of rows of a 32-aligned length, so the
				// row kernel takes one word a row and shuffles. maskLen 32: not a whole
				// number of rows, so every lane looks its cells up one by one.
				for (int maskLen : new int[] { len * 4, 32 }) {
					for (int mk = 0; mk < 2; mk++) {
						double[] maskD = new double[maskLen];
						float[] maskF = new float[maskLen];
						for (int i = 0; i < maskLen; i++) {
							maskD[i] = (i * 7) % 5 == 0 ? 1.0 : 0.0;
							maskF[i] = (float) maskD[i];
						}
						Object mask = mk == 0 ? maskD : maskF;
						double fill = mk == 0 ? Double.NEGATIVE_INFINITY : -5.0;
						String what = "scale=%s maskLen=%d mask=%s".formatted(sc, maskLen, mk == 0 ? "f64" : "f32");
						float[] scaled = ch.home(ch.scale(Gpu.BIN_DIV, x, sc, false));
						float[] masked = new float[n];
						for (int i = 0; i < n; i++) {
							masked[i] = maskD[i % maskLen] != 0.0 ? (float) fill : scaled[i];
						}
						makeResident(masked);
						float[] out = new float[n];
						assertThat(Gpu.softmax(masked, 0, out, 0, rows, len)).isTrue();
						float[] fused = new float[n];
						assertThat(Gpu.softmax(x, 0, mask, 0, maskLen, fused, 0, rows, len, Gpu.BIN_DIV, sc, fill))
							.isTrue();
						assertBitIdentical(ch.home(fused), ch.home(out), "scaled masked softmax " + what);
						// Its adjoint: the plain adjoint, then the scale, then the mask's
						// zeroing -- the divide taken before the host zeroes, because a
						// zero divided by the scale is the same zero and a host write to
						// a resident array would lose the residency the scale is offered
						// over.
						float[] dx = new float[n];
						assertThat(Gpu.softmaxGrad(g, 0, out, 0, dx, 0, rows, len)).isTrue();
						float[] expected = ch.home(ch.scale(Gpu.BIN_DIV, dx, sc, false));
						for (int i = 0; i < n; i++) {
							if (maskD[i % maskLen] != 0.0) {
								expected[i] = 0.0f;
							}
						}
						float[] fusedGrad = new float[n];
						assertThat(Gpu.softmaxGrad(g, 0, out, 0, mask, 0, maskLen, fusedGrad, 0, rows, len, Gpu.BIN_DIV,
								sc))
							.isTrue();
						assertBitIdentical(ch.home(fusedGrad), expected, "scaled masked softmax grad " + what);
					}
				}
			}
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	@Test
	void theDropoutMaskStaysDeclinedHere() {
		// The one member of the nine that is NOT fused on this backend, and on the
		// arithmetic rather than for want of a kernel: the Wichmann-Hill uniform is three
		// binary64 divisions an element, which here are software restoring divides. The
		// generator fill is already not a member for the same reason.
		int n = (int) Gpu.mapMinElements() * 4;
		assertThat(Gpu.dropoutMask(new float[n], 0, n, 0.1, 0.9, 11, 22, 33)).isFalse();
		assertThat(Gpu.rngFill(new float[n], 0, n, 0, 0.0, 1.0, 11, 22, 33)).isFalse();
	}

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
			case Gpu.BIN_GT -> x > y ? 1.0f : 0.0f;
			case Gpu.BIN_GE -> x >= y ? 1.0f : 0.0f;
			case Gpu.BIN_LT -> x < y ? 1.0f : 0.0f;
			case Gpu.BIN_LE -> x <= y ? 1.0f : 0.0f;
			case Gpu.BIN_EQ -> x == y ? 1.0f : 0.0f;
			default -> throw new IllegalArgumentException("op " + op);
		};
	}

	// --- asynchronous command buffers (todo-495) ---------------------------------------

	/**
	 * A chain over resident operands, lazily: every call returns with its command buffer
	 * still in flight, the host reads the last result and gets the device's bytes, and
	 * that read is what retires the line.
	 */
	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aLazyChainRunsWithItsCommandBuffersInFlightAndTheFirstReadRetiresThem() {
		MetalGemm gemm = device();
		Gpu.releaseResident();
		int n = 1 << 22;
		float[] a = new float[n], b = new float[n];
		for (int i = 0; i < n; i++) {
			// Multiples of a quarter, so that every sum and difference below is EXACT and
			// the chain lands back on a.
			a[i] = (i % 97) / 4.0f;
			b[i] = 1.0f + (i % 13) / 2.0f;
		}
		Gpu.lazyResults(true);
		try {
			makeResident(a);
			makeResident(b);
			float[] previous = a;
			float[] result = a;
			int links = 24;
			for (int k = 0; k < links; k++) {
				result = new float[n];
				assertThat(Gpu.zip(k % 2 == 0 ? Gpu.BIN_ADD : Gpu.BIN_SUB, previous, 0, b, 0, result, 0, n)).isTrue();
				previous = result;
			}
			// The device is at least a memory pass behind the host's encoding: some of
			// the chain is still running when the last call returns.
			assertThat(gemm.inflightCount()).as("command buffers in flight after the chain").isPositive();
			Gpu.materialize(result);
			assertThat(gemm.inflightCount()).as("the read waits for the writer, and one queue is in order").isZero();
			for (int i = 0; i < n; i += 4099) {
				// links even: ((a + b) - b) ... = a exactly, every step exact at these
				// values
				assertThat(result[i]).as("chain at %d", i).isEqualTo(a[i]);
			}
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	/**
	 * The ordering the residency design exists to forbid: a slab recycled under a launch
	 * that still reads it. Lazily a launch's operand slab goes back to the pool the
	 * moment the host writes the array ({@code written}) and the next call's upload would
	 * take it -- so that upload waits for the launch, and the result of the launch is
	 * computed from the bytes it was given.
	 */
	@Test
	@ResourceLock(DEVICE_MEMORY)
	void anOperandSlabRecycledUnderALaunchIsNotUploadedIntoUntilTheLaunchIsDone() {
		Gpu.releaseResident();
		int n = 1 << 23;
		float[] a = new float[n], b = new float[n], r = new float[n], s = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = (i % 101) / 100.0f;
			b[i] = -(i % 7) / 10.0f;
		}
		Gpu.lazyResults(true);
		try {
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, r, 0, n)).isTrue();
			assertThat(Gpu.resident(a)).isTrue();
			// The host writes a: its clean copy is dropped and its slab is the next one
			// the pool hands out, while the launch that reads it is still in flight.
			Gpu.written(a);
			assertThat(Gpu.resident(a)).isFalse();
			assertThat(Gpu.map(Gpu.MAP_EXP, b, 0, s, 0, n)).isTrue();
			Gpu.materialize(r);
			Gpu.materialize(s);
			for (int i = 0; i < n; i += 4099) {
				// The device's exp is its own libm: a last-ulp neighbour of Java's, never
				// exp of the OTHER array's element.
				assertThat(r[i]).as("exp(a[%d])", i).isCloseTo((float) Math.exp(a[i]), within(1e-6f));
				assertThat(s[i]).as("exp(b[%d])", i).isCloseTo((float) Math.exp(b[i]), within(1e-6f));
			}
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

	/**
	 * A command buffer that fails AFTER its call answered {@code true}: the result it
	 * wrote, and every result of a later launch that read it, is lost and the first host
	 * read of either throws; a result the failed buffer only READ is intact, and so is
	 * everything computed after it from intact operands. Switching the mode off lets the
	 * lost results go without throwing -- they surface at their read, if ever.
	 */
	@Test
	@ResourceLock(DEVICE_MEMORY)
	void aFailedCommandBufferSurfacesAtTheFirstHostReadOfWhatItWrote() {
		MetalGemm gemm = device();
		Gpu.releaseResident();
		int n = (int) Gpu.mapMinElements() * 2;
		float[] a = new float[n], r1 = new float[n], r2 = new float[n], r3 = new float[n], r4 = new float[n];
		for (int i = 0; i < n; i++) {
			a[i] = 1.0f + (i % 31) / 100.0f;
		}
		Gpu.lazyResults(true);
		try {
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, r1, 0, n)).isTrue();
			gemm.failNextCommandBuffer();
			assertThat(Gpu.map(Gpu.MAP_LOG, r1, 0, r2, 0, n)).as("answered before the failure is known").isTrue();
			assertThat(Gpu.map(Gpu.MAP_SQRT, r2, 0, r3, 0, n)).as("a chain over the lost result").isTrue();
			assertThat(Gpu.map(Gpu.MAP_LOG, a, 0, r4, 0, n)).as("an unrelated launch after it").isTrue();
			Gpu.materialize(r1);
			assertThat(r1[17]).isCloseTo((float) Math.exp(a[17]), within(1e-5f));
			assertThatThrownBy(() -> Gpu.materialize(r2)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("failed");
			assertThatThrownBy(() -> Gpu.materialize(r3)).isInstanceOf(IllegalStateException.class);
			Gpu.materialize(r4);
			assertThat(r4[17]).isCloseTo((float) Math.log(a[17]), within(1e-6f));
			Gpu.lazyResults(false);
			assertThatThrownBy(() -> Gpu.materialize(r2)).as("lost for good, mode or no mode")
				.isInstanceOf(IllegalStateException.class);
			// The line is empty and the device is as usable as before.
			assertThat(gemm.inflightCount()).isZero();
			float[] r5 = new float[n];
			assertThat(Gpu.map(Gpu.MAP_EXP, a, 0, r5, 0, n)).isTrue();
			assertThat(r5[17]).isCloseTo((float) Math.exp(a[17]), within(1e-5f));
		}
		finally {
			Gpu.lazyResults(false);
			Gpu.releaseResident();
		}
	}

}
