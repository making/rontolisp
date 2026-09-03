package am.ik.rontolisp.eval;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fused {@code bfloat16} kernels of {@link VecSimdKernels}: the GEMV / dot / sum lane
 * loops that decode a bf16 weight lane group inside the loop instead of widening the
 * whole array into an f32 scratch first.
 *
 * <p>
 * Their contract is an EQUIVALENCE, not a tolerance. A bf16 value IS the top half of an
 * f32 value, so widening it ({@code bits << 16}) is exact -- no rounding, no range clamp,
 * NaN payloads carried through -- and a kernel that decodes lane by lane and accumulates
 * in f32 must therefore produce, bit for bit, what the existing f32 kernel produces over
 * the widened array. That is what every case below asserts: <b>fused ==
 * widen-then-f32-kernel</b>, at several shapes and ranks, on both sides of the
 * {@code THRESHOLD = 128} and {@code MATVEC_ROW_THRESHOLD = 16} lane gates, serially and
 * with the rows split across {@code --parallel} threads. Because the equivalence is exact
 * there is nothing to relax on a wider host: the bf16 decode is pinned to four lanes for
 * exactly the reason {@code FSPECIES_REDUCE} is (see {@code .kb/vec.md}, "The lane-count
 * pin").
 *
 * <p>
 * {@code codegen.jvm.JvmSimdVectorTemplateBf16Test} asserts the same equivalence for the
 * compiled bridge's mirror of these kernels; the two files may not reference each other
 * (the package dependency rule), so each is pinned against its OWN f32 kernel, which the
 * existing {@code --simd} tests already pin to the other's.
 *
 * <p>
 * Requires {@code --add-modules jdk.incubator.vector} (the surefire {@code argLine}
 * supplies it).
 */
class VecSimdBf16KernelsTest {

	/**
	 * The private lane gate of {@link VecSimdKernels}, repeated so cases can straddle it.
	 */
	private static final int THRESHOLD = 128;

	/** The private GEMV row gate of {@link VecSimdKernels}. */
	private static final int MATVEC_ROW_THRESHOLD = 16;

	/** {@code n} bf16 patterns of finite gaussian weights, the realistic operand. */
	private static short[] weights(int n, long seed) {
		Random random = new Random(seed);
		short[] w = new short[n];
		for (int i = 0; i < n; i++) {
			w[i] = VecSimdKernels.floatToBf16((float) (random.nextGaussian() * 0.02));
		}
		return w;
	}

	private static float[] activations(int n, long seed) {
		Random random = new Random(seed);
		float[] x = new float[n];
		for (int i = 0; i < n; i++) {
			x[i] = (float) random.nextGaussian();
		}
		return x;
	}

	/**
	 * The oracle side: the whole operand widened up front, exactly as the scalar does.
	 */
	private static float[] widened(short[] w) {
		float[] f = new float[w.length];
		for (int i = 0; i < w.length; i++) {
			f[i] = Float.intBitsToFloat(w[i] << 16);
		}
		return f;
	}

	// --- the widening is exact ----------------------------------------------------

	@Test
	void everyBf16PatternWidensToTheF32ValueItsBitsDenote() {
		for (int p = 0; p < 1 << 16; p++) {
			short bits = (short) p;
			assertThat(Float.floatToRawIntBits(VecSimdKernels.bf16ToFloat(bits))).as("pattern 0x%04x", p)
				.isEqualTo(bits << 16);
		}
	}

	@Test
	void everyNonNanBf16PatternRoundTripsThroughF32Unchanged() {
		for (int p = 0; p < 1 << 16; p++) {
			short bits = (short) p;
			float widened = VecSimdKernels.bf16ToFloat(bits);
			if (Float.isNaN(widened)) {
				continue;
			}
			assertThat(VecSimdKernels.floatToBf16(widened)).as("pattern 0x%04x", p).isEqualTo(bits);
		}
	}

	@Test
	void theBulkWidenMatchesTheScalarWidenOnBothSidesOfTheLaneGate() {
		for (int n : new int[] { 0, 1, 7, MATVEC_ROW_THRESHOLD, THRESHOLD - 1, THRESHOLD, THRESHOLD + 3, 1024 }) {
			short[] w = weights(n, 5150 + n);
			float[] actual = new float[n];
			VecSimdKernels.widenBf16Into(actual, w);
			assertThat(actual).as("n = %d", n).isEqualTo(widened(w));
		}
	}

	// --- the narrowing rounds to nearest, ties to even ------------------------------

	@Test
	void theNarrowingRoundsToNearestWithTiesToEven() {
		// low 16 bits exactly 0x8000 is the tie: it goes to the neighbour whose retained
		// low bit is 0. A plain >>> 16 would truncate every one of these downwards.
		assertThat(narrowOf(0x3f80_8000)).isEqualTo((short) 0x3f80); // tie, 0x3f80 even
		assertThat(narrowOf(0x3f81_8000)).isEqualTo((short) 0x3f82); // tie, 0x3f81 odd
		assertThat(narrowOf(0x3f80_7fff)).isEqualTo((short) 0x3f80); // below half
		assertThat(narrowOf(0x3f80_8001)).isEqualTo((short) 0x3f81); // above half
		assertThat(narrowOf(0xbf81_8000)).isEqualTo((short) 0xbf82); // sign is carried
	}

	@Test
	void theNarrowingNeverTurnsANanIntoAnInfinity() {
		// 0x7f800001's surviving mantissa bits are all zero, so rounding it carries into
		// the exponent and answers an infinity unless the NaN arm catches it first.
		for (int bits : new int[] { 0x7f80_0001, 0xff80_0001, 0x7fc0_0000, Float.floatToRawIntBits(Float.NaN) }) {
			short narrowed = narrowOf(bits);
			assertThat(Float.isNaN(VecSimdKernels.bf16ToFloat(narrowed)))
				.as("0x%08x narrowed to 0x%04x", bits, narrowed & 0xffff)
				.isTrue();
		}
		assertThat(narrowOf(Float.floatToRawIntBits(Float.POSITIVE_INFINITY))).isEqualTo((short) 0x7f80);
		assertThat(narrowOf(Float.floatToRawIntBits(Float.NEGATIVE_INFINITY))).isEqualTo((short) 0xff80);
	}

	@Test
	void theBulkNarrowMatchesTheScalarNarrow() {
		float[] x = activations(1024, 77);
		short[] actual = new short[x.length];
		VecSimdKernels.narrowBf16Into(actual, x);
		for (int i = 0; i < x.length; i++) {
			assertThat(actual[i]).isEqualTo(VecSimdKernels.floatToBf16(x[i]));
		}
	}

	private static short narrowOf(int bits) {
		return VecSimdKernels.floatToBf16(Float.intBitsToFloat(bits));
	}

	// --- fused == widen-then-f32-kernel ---------------------------------------------

	@Test
	void theFusedSumIsBitIdenticalToTheF32SumOverTheWidenedArray() {
		for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024, 4096 }) {
			short[] w = weights(n, 31 + n);
			assertThat(VecSimdKernels.sumBf16(w)).as("n = %d", n).isEqualTo(VecSimdKernels.sumF(widened(w)));
		}
	}

	@Test
	void theFusedDotIsBitIdenticalToTheF32DotOverTheWidenedArray() {
		for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024, 4096 }) {
			short[] w = weights(n, 101 + n);
			float[] x = activations(n, 202 + n);
			assertThat(VecSimdKernels.dotBf16(w, x)).as("n = %d", n).isEqualTo(VecSimdKernels.dotF(widened(w), x));
		}
	}

	@Test
	void theFusedGemvIsBitIdenticalToTheF32GemvOverTheWidenedMatrix() {
		for (int[] shape : shapes()) {
			int rows = shape[0], cols = shape[1];
			short[] w = weights(rows * cols, 7L * rows + cols);
			float[] x = activations(cols, 9L * rows + cols);
			assertThat(VecSimdKernels.matvecBf16(w, rows, cols, x, false)).as("%dx%d", rows, cols)
				.isEqualTo(VecSimdKernels.matvecF(widened(w), rows, cols, x, false));
		}
	}

	@Test
	void theParallelFusedGemvIsBitIdenticalToTheSerialFusedGemv() {
		for (int[] shape : shapes()) {
			int rows = shape[0], cols = shape[1];
			short[] w = weights(rows * cols, 7L * rows + cols);
			float[] x = activations(cols, 9L * rows + cols);
			assertThat(VecSimdKernels.matvecBf16(w, rows, cols, x, true)).as("%dx%d", rows, cols)
				.isEqualTo(VecSimdKernels.matvecBf16(w, rows, cols, x, false));
		}
	}

	@Test
	void theFusedGemvIntoWritesWhatTheAllocatingFusedGemvReturns() {
		short[] w = weights(64 * 1024, 4242);
		float[] x = activations(1024, 2424);
		float[] out = new float[64];
		VecSimdKernels.matvecIntoBf16(out, w, 64, 1024, x, true);
		assertThat(out).isEqualTo(VecSimdKernels.matvecBf16(w, 64, 1024, x, false));
	}

	/**
	 * Ranks and shapes that straddle both gates, and one (64x1024 = 2^16 multiply-adds)
	 * above {@code SimdParallel.MIN_WORK} so the parallel case really splits.
	 */
	private static int[][] shapes() {
		return new int[][] { { 1, 1 }, { 3, 8 }, { 3, MATVEC_ROW_THRESHOLD }, { 5, 33 }, { 17, THRESHOLD - 1 },
				{ 17, THRESHOLD }, { 8, 288 }, { 64, 1024 }, { 2, 4096 } };
	}

}
