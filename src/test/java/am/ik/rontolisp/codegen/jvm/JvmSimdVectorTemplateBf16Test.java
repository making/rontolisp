package am.ik.rontolisp.codegen.jvm;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fused {@code bfloat16} kernels of {@link JvmSimdVectorTemplate}, the embedded
 * {@code --simd} bridge's mirror of {@code eval.VecSimdKernels}' -- the GEMV / dot / sum
 * lane loops that decode a bf16 weight lane group inside the loop instead of widening the
 * whole matrix into an f32 scratch first.
 *
 * <p>
 * Their contract is an EQUIVALENCE, not a tolerance: bf16 -> f32 is exact
 * ({@code bits << 16}), so a kernel that decodes lane by lane and accumulates in f32 must
 * produce, bit for bit, what the f32 kernel produces over the widened array. Every case
 * asserts <b>fused == widen-then-f32-kernel</b>, at several shapes and ranks, on both
 * sides of the {@code THRESHOLD = 128} and {@code MATVEC_ROW_THRESHOLD = 16} lane gates,
 * serially and split across the {@code --parallel} threads. The f32 side is reached
 * through the real bridge entries ({@code simdSum} / {@code simdDot} /
 * {@code simdMatvec}) over header-carrying packed arrays, so the oracle is the code a
 * compiled {@code --simd} class actually runs.
 *
 * <p>
 * These kernels take BARE {@code short[]} arrays with no dimension header -- the packed
 * bf16 array type does not exist yet, so they are standalone until the interception layer
 * grows header-aware entries in front of them. The interpreter twin is pinned by
 * {@code eval.VecSimdBf16KernelsTest}; the two files may not reference each other (the
 * package dependency rule), so each is pinned against its own f32 kernel, which the
 * existing {@code --simd} tests already pin to the other's.
 */
class JvmSimdVectorTemplateBf16Test {

	/**
	 * The private lane gate of {@link JvmSimdVectorTemplate}, repeated so cases can
	 * straddle it.
	 */
	private static final int THRESHOLD = 128;

	/** The private GEMV row gate of {@link JvmSimdVectorTemplate}. */
	private static final int MATVEC_ROW_THRESHOLD = 16;

	private static short[] weights(int n, long seed) {
		Random random = new Random(seed);
		short[] w = new short[n];
		for (int i = 0; i < n; i++) {
			w[i] = JvmSimdVectorTemplate.floatToBf16((float) (random.nextGaussian() * 0.02));
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

	/** A rank-1 packed vector {@code [1, n, e...]} of the widened patterns. */
	private static float[] packedWidened(short[] w) {
		float[] v = new float[2 + w.length];
		v[0] = 1.0f;
		v[1] = w.length;
		for (int i = 0; i < w.length; i++) {
			v[2 + i] = Float.intBitsToFloat(w[i] << 16);
		}
		return v;
	}

	/** A rank-2 packed matrix {@code [2, rows, cols, e...]} of the widened patterns. */
	private static float[] packedWidenedMatrix(short[] w, int rows, int cols) {
		float[] m = new float[3 + rows * cols];
		m[0] = 2.0f;
		m[1] = rows;
		m[2] = cols;
		for (int i = 0; i < rows * cols; i++) {
			m[3 + i] = Float.intBitsToFloat(w[i] << 16);
		}
		return m;
	}

	private static float[] packed(float[] x) {
		float[] v = new float[2 + x.length];
		v[0] = 1.0f;
		v[1] = x.length;
		System.arraycopy(x, 0, v, 2, x.length);
		return v;
	}

	/** The elements of a rank-1 packed vector, header stripped. */
	private static float[] elements(@org.jspecify.annotations.Nullable Object packed) {
		float[] v = (float[]) java.util.Objects.requireNonNull(packed);
		return Arrays.copyOfRange(v, 1 + (int) v[0], v.length);
	}

	// --- the widening is exact ----------------------------------------------------

	@Test
	void everyBf16PatternWidensToTheF32ValueItsBitsDenote() {
		for (int p = 0; p < 1 << 16; p++) {
			short bits = (short) p;
			assertThat(Float.floatToRawIntBits(JvmSimdVectorTemplate.bf16ToFloat(bits))).as("pattern 0x%04x", p)
				.isEqualTo(bits << 16);
		}
	}

	@Test
	void everyNonNanBf16PatternRoundTripsThroughF32Unchanged() {
		for (int p = 0; p < 1 << 16; p++) {
			short bits = (short) p;
			float widened = JvmSimdVectorTemplate.bf16ToFloat(bits);
			if (Float.isNaN(widened)) {
				continue;
			}
			assertThat(JvmSimdVectorTemplate.floatToBf16(widened)).as("pattern 0x%04x", p).isEqualTo(bits);
		}
	}

	@Test
	void theBulkWidenMatchesTheScalarWidenOnBothSidesOfTheLaneGate() {
		for (int n : new int[] { 0, 1, 7, MATVEC_ROW_THRESHOLD, THRESHOLD - 1, THRESHOLD, THRESHOLD + 3, 1024 }) {
			short[] w = weights(n, 5150 + n);
			float[] actual = new float[n];
			JvmSimdVectorTemplate.widenBf16Into(actual, w);
			assertThat(actual).as("n = %d", n).isEqualTo(elements(packedWidened(w)));
		}
	}

	// --- the narrowing rounds to nearest, ties to even ------------------------------

	@Test
	void theNarrowingRoundsToNearestWithTiesToEven() {
		assertThat(narrowOf(0x3f80_8000)).isEqualTo((short) 0x3f80);
		assertThat(narrowOf(0x3f81_8000)).isEqualTo((short) 0x3f82);
		assertThat(narrowOf(0x3f80_7fff)).isEqualTo((short) 0x3f80);
		assertThat(narrowOf(0x3f80_8001)).isEqualTo((short) 0x3f81);
		assertThat(narrowOf(0xbf81_8000)).isEqualTo((short) 0xbf82);
	}

	@Test
	void theNarrowingNeverTurnsANanIntoAnInfinity() {
		for (int bits : new int[] { 0x7f80_0001, 0xff80_0001, 0x7fc0_0000, Float.floatToRawIntBits(Float.NaN) }) {
			short narrowed = narrowOf(bits);
			assertThat(Float.isNaN(JvmSimdVectorTemplate.bf16ToFloat(narrowed)))
				.as("0x%08x narrowed to 0x%04x", bits, narrowed & 0xffff)
				.isTrue();
		}
		assertThat(narrowOf(Float.floatToRawIntBits(Float.POSITIVE_INFINITY))).isEqualTo((short) 0x7f80);
		assertThat(narrowOf(Float.floatToRawIntBits(Float.NEGATIVE_INFINITY))).isEqualTo((short) 0xff80);
	}

	private static short narrowOf(int bits) {
		return JvmSimdVectorTemplate.floatToBf16(Float.intBitsToFloat(bits));
	}

	// --- fused == widen-then-f32-kernel ---------------------------------------------

	@Test
	void theFusedSumIsBitIdenticalToTheF32SumOverTheWidenedArray() {
		for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024, 4096 }) {
			short[] w = weights(n, 31 + n);
			assertThat(JvmSimdVectorTemplate.sumBf16(w)).as("n = %d", n)
				.isEqualTo(JvmSimdVectorTemplate.simdSum(packedWidened(w)));
		}
	}

	@Test
	void theFusedDotIsBitIdenticalToTheF32DotOverTheWidenedArray() {
		for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024, 4096 }) {
			short[] w = weights(n, 101 + n);
			float[] x = activations(n, 202 + n);
			assertThat(JvmSimdVectorTemplate.dotBf16(w, x)).as("n = %d", n)
				.isEqualTo(JvmSimdVectorTemplate.simdDot(packedWidened(w), packed(x)));
		}
	}

	@Test
	void theFusedGemvIsBitIdenticalToTheF32GemvOverTheWidenedMatrix() {
		for (int[] shape : shapes()) {
			int rows = shape[0], cols = shape[1];
			short[] w = weights(rows * cols, 7L * rows + cols);
			float[] x = activations(cols, 9L * rows + cols);
			assertThat(JvmSimdVectorTemplate.matvecBf16(w, rows, cols, x, false)).as("%dx%d", rows, cols)
				.isEqualTo(elements(JvmSimdVectorTemplate.simdMatvec(packedWidenedMatrix(w, rows, cols), packed(x))));
		}
	}

	@Test
	void theParallelFusedGemvIsBitIdenticalToTheSerialFusedGemv() {
		for (int[] shape : shapes()) {
			int rows = shape[0], cols = shape[1];
			short[] w = weights(rows * cols, 7L * rows + cols);
			float[] x = activations(cols, 9L * rows + cols);
			assertThat(JvmSimdVectorTemplate.matvecBf16(w, rows, cols, x, true)).as("%dx%d", rows, cols)
				.isEqualTo(JvmSimdVectorTemplate.matvecBf16(w, rows, cols, x, false));
		}
	}

	@Test
	void theFusedGemvIntoWritesWhatTheAllocatingFusedGemvReturns() {
		short[] w = weights(64 * 1024, 4242);
		float[] x = activations(1024, 2424);
		float[] out = new float[64];
		JvmSimdVectorTemplate.matvecIntoBf16(out, w, 64, 1024, x, true);
		assertThat(out).isEqualTo(JvmSimdVectorTemplate.matvecBf16(w, 64, 1024, x, false));
	}

	private static int[][] shapes() {
		return new int[][] { { 1, 1 }, { 3, 8 }, { 3, MATVEC_ROW_THRESHOLD }, { 5, 33 }, { 17, THRESHOLD - 1 },
				{ 17, THRESHOLD }, { 8, 288 }, { 64, 1024 }, { 2, 4096 } };
	}

}
