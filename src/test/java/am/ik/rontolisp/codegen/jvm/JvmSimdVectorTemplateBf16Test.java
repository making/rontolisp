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
 * Both arms are driven through the BRIDGE ENTRIES, over the compiled packed
 * representation each width carries -- {@code [2, rows, cols, e...]} at f32 and
 * {@code [2, rows_hi, rows_lo, cols_hi, cols_lo, e...]} at bfloat16, where a dimension
 * takes two slots because a {@code short} cannot hold one ({@code .kb/bfloat16.md}) -- so
 * the equivalence is asserted over exactly the code a compiled {@code --simd} class runs,
 * header arithmetic included. {@code widenBf16Into} is the one header-free member: it is
 * a bulk conversion between two raw buffers. The interpreter twin is pinned by
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

	/** A rank-1 packed bfloat16 vector: {@code [1, n_hi, n_lo, e...]}. */
	private static short[] packedBf16(short[] w) {
		short[] v = new short[3 + w.length];
		v[0] = 1;
		v[1] = (short) (w.length >>> 16);
		v[2] = (short) w.length;
		System.arraycopy(w, 0, v, 3, w.length);
		return v;
	}

	/** A rank-2 packed bfloat16 matrix: {@code [2, r_hi, r_lo, c_hi, c_lo, e...]}. */
	private static short[] packedBf16Matrix(short[] w, int rows, int cols) {
		short[] m = new short[5 + w.length];
		m[0] = 2;
		m[1] = (short) (rows >>> 16);
		m[2] = (short) rows;
		m[3] = (short) (cols >>> 16);
		m[4] = (short) cols;
		System.arraycopy(w, 0, m, 5, w.length);
		return m;
	}

	/** A fresh rank-1 packed f32 destination {@code [1, n, 0...]}. */
	private static float[] destination(int n) {
		float[] r = new float[2 + n];
		r[0] = 1.0f;
		r[1] = n;
		return r;
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

	/** A fresh rank-1 packed bfloat16 destination {@code [1, n_hi, n_lo, 0...]}. */
	private static short[] destinationBf16(int n) {
		short[] r = new short[3 + n];
		r[0] = 1;
		r[1] = (short) (n >>> 16);
		r[2] = (short) n;
		return r;
	}

	/** The elements of a rank-1 packed bfloat16 vector, header stripped. */
	private static short[] elementsBf16(@org.jspecify.annotations.Nullable Object packed) {
		short[] v = (short[]) java.util.Objects.requireNonNull(packed);
		return Arrays.copyOfRange(v, 1 + 2 * v[0], v.length);
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

	/**
	 * The conversion census check ({@code .kb/bfloat16.md}): the narrowing must agree
	 * with {@code am.ik.rontolisp.BFloat16}, the single authority, on EVERY pattern, NaN
	 * payloads included -- not just "stays a NaN", which is all the test above and
	 * {@link #theNarrowingNeverTurnsANanIntoAnInfinity} check.
	 */
	@Test
	void theNarrowingAgreesWithTheAuthorityOnEveryBf16WidenedPattern() {
		for (int p = 0; p < 1 << 16; p++) {
			short bits = (short) p;
			float widened = JvmSimdVectorTemplate.bf16ToFloat(bits);
			assertThat(JvmSimdVectorTemplate.floatToBf16(widened) & 0xffff).as("pattern 0x%04x", p)
				.isEqualTo(am.ik.rontolisp.BFloat16.bits(widened));
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
			assertThat(JvmSimdVectorTemplate.simdSum(packedBf16(w))).as("n = %d", n)
				.isEqualTo(JvmSimdVectorTemplate.simdSum(packedWidened(w)));
		}
	}

	@Test
	void theFusedDotIsBitIdenticalToTheF32DotOverTheWidenedArray() {
		for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024, 4096 }) {
			short[] w = weights(n, 101 + n);
			float[] x = activations(n, 202 + n);
			assertThat(JvmSimdVectorTemplate.simdDot(packedBf16(w), packed(x))).as("n = %d", n)
				.isEqualTo(JvmSimdVectorTemplate.simdDot(packedWidened(w), packed(x)));
		}
	}

	@Test
	void theFusedGemvIsBitIdenticalToTheF32GemvOverTheWidenedMatrix() {
		for (int[] shape : shapes()) {
			int rows = shape[0], cols = shape[1];
			short[] w = weights(rows * cols, 7L * rows + cols);
			float[] x = activations(cols, 9L * rows + cols);
			assertThat(elements(JvmSimdVectorTemplate.simdMatvec(packedBf16Matrix(w, rows, cols), packed(x))))
				.as("%dx%d", rows, cols)
				.isEqualTo(elements(JvmSimdVectorTemplate.simdMatvec(packedWidenedMatrix(w, rows, cols), packed(x))));
		}
	}

	@Test
	void theParallelFusedGemvIsBitIdenticalToTheSerialFusedGemv() {
		for (int[] shape : shapes()) {
			int rows = shape[0], cols = shape[1];
			short[] w = weights(rows * cols, 7L * rows + cols);
			float[] x = activations(cols, 9L * rows + cols);
			short[] m = packedBf16Matrix(w, rows, cols);
			assertThat(elements(JvmSimdVectorTemplate.simdMatvecParallel(m, packed(x)))).as("%dx%d", rows, cols)
				.isEqualTo(elements(JvmSimdVectorTemplate.simdMatvec(m, packed(x))));
		}
	}

	@Test
	void theFusedGemvIntoWritesWhatTheAllocatingFusedGemvReturns() {
		short[] m = packedBf16Matrix(weights(64 * 1024, 4242), 64, 1024);
		float[] x = packed(activations(1024, 2424));
		float[] out = destination(64);
		JvmSimdVectorTemplate.simdMatvecIntoParallel(out, m, x);
		assertThat(elements(out)).isEqualTo(elements(JvmSimdVectorTemplate.simdMatvec(m, x)));
	}

	// --- element-wise bf16 x bf16 -> bf16 ----------------------------------------------
	// Driven through the BRIDGE ENTRIES over the compiled packed representation, so
	// the header arithmetic is asserted too -- including the length-1 vector, where a
	// hard-coded one-slot offset would read the rank word as an element. The oracle
	// is the scalar composite (widen, compute in f32, narrow through the scalar
	// `floatToBf16`), which is pinned against the defun (.kb/bfloat16.md); the
	// interpreter twin is pinned by `eval.VecSimdBf16KernelsTest`.

	/** The scalar composite for one pair, the oracle the lane loop must match. */
	private static short composite(int op, short a, short b) {
		float fa = JvmSimdVectorTemplate.bf16ToFloat(a);
		float fb = JvmSimdVectorTemplate.bf16ToFloat(b);
		return switch (op) {
			case 0 -> JvmSimdVectorTemplate.floatToBf16(fa + fb);
			case 1 -> JvmSimdVectorTemplate.floatToBf16(fa - fb);
			case 2 -> JvmSimdVectorTemplate.floatToBf16(fa * fb);
			default -> JvmSimdVectorTemplate.floatToBf16(fa / fb);
		};
	}

	private static short[] runBridgeBinary(int op, short[] x, short[] y) {
		return switch (op) {
			case 0 -> elementsBf16(JvmSimdVectorTemplate.simdAdd(packedBf16(x), packedBf16(y)));
			case 1 -> elementsBf16(JvmSimdVectorTemplate.simdSub(packedBf16(x), packedBf16(y)));
			case 2 -> elementsBf16(JvmSimdVectorTemplate.simdMul(packedBf16(x), packedBf16(y)));
			default -> elementsBf16(JvmSimdVectorTemplate.simdDiv(packedBf16(x), packedBf16(y)));
		};
	}

	private static void runBridgeBinaryInto(int op, short[] r, short[] x, short[] y) {
		switch (op) {
			case 0 -> JvmSimdVectorTemplate.simdAddInto(r, packedBf16(x), packedBf16(y));
			case 1 -> JvmSimdVectorTemplate.simdSubInto(r, packedBf16(x), packedBf16(y));
			case 2 -> JvmSimdVectorTemplate.simdMulInto(r, packedBf16(x), packedBf16(y));
			default -> JvmSimdVectorTemplate.simdDivInto(r, packedBf16(x), packedBf16(y));
		}
	}

	/**
	 * Patterns that exercise the lane narrow's arms: ties, quiet NaN payloads,
	 * infinities, zeros of both signs, subnormals and the overflow edge. Signalling NaNs
	 * are NOT here: on an sNaN input the packed lanes quiet the source payload while the
	 * scalar instructions answer the indefinite, so they are pinned separately at
	 * {@code isNaN} level by {@link #signallingNanInputsAnswerNanOnBothRoutes}.
	 */
	private static short[] trickyPatterns() {
		int[] bits = { 0x3f808000, 0x3f818000, 0x3f807fff, 0x3f808001, 0xbf818000, 0x7fc12345, 0xffc00000, 0x7f800000,
				0xff800000, 0x00000000, 0x80000000, 0x007fffff, 0x00000001, 0x7f7fffff, 0x4f800000, 0xcf800000 };
		short[] w = new short[1024];
		for (int i = 0; i < w.length; i++) {
			w[i] = JvmSimdVectorTemplate.floatToBf16(Float.intBitsToFloat(bits[i % bits.length]));
		}
		return w;
	}

	/**
	 * Warms the kernel loops so the assertions below meet the JIT-compiled lane path
	 * rather than whichever tier happens to be warm: a lane-vs-scalar comparison only
	 * pins the lanes once they actually run as lanes.
	 */
	private static void warm(int op, short[] x, short[] y) {
		short[] rx = packedBf16(x);
		short[] ry = packedBf16(y);
		for (int k = 0; k < 100; k++) {
			switch (op) {
				case 0 -> JvmSimdVectorTemplate.simdAdd(rx, ry);
				case 1 -> JvmSimdVectorTemplate.simdSub(rx, ry);
				case 2 -> JvmSimdVectorTemplate.simdMul(rx, ry);
				default -> JvmSimdVectorTemplate.simdDiv(rx, ry);
			}
		}
	}

	@Test
	void theBridgeElementWiseKernelsMatchTheScalarCompositeOnBothSidesOfTheLaneGate() {
		for (int op = 0; op < 4; op++) {
			for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024 }) {
				short[] x = weights(n, 1001 + 7L * n + op);
				short[] y = weights(n, 2002 + 7L * n + op);
				short[] actual = runBridgeBinary(op, x, y);
				for (int i = 0; i < n; i++) {
					assertThat(actual[i]).as("op %d n = %d i = %d", op, n, i).isEqualTo(composite(op, x[i], y[i]));
				}
			}
		}
	}

	@Test
	void theBridgeElementWiseKernelsMatchTheScalarCompositeOnTrickyPatterns() {
		short[] x = trickyPatterns();
		short[] y = trickyPatterns();
		for (int i = 0; i < y.length; i++) {
			y[i] = (short) (y[(i * 7 + 3) % y.length] ^ 0x1234);
		}
		for (int op = 0; op < 4; op++) {
			warm(op, x, y);
			short[] actual = runBridgeBinary(op, x, y);
			for (int i = 0; i < x.length; i++) {
				assertThat(actual[i]).as("op %d i = %d", op, i).isEqualTo(composite(op, x[i], y[i]));
			}
		}
	}

	@Test
	void signallingNanInputsAnswerNanOnBothRoutes() {
		// An sNaN input raises the invalid-operation flag, and the hardware answers
		// differently per instruction shape: the packed lanes quiet the source payload
		// while the scalar instructions answer the indefinite. Both are quiet NaNs,
		// so the pin is the CLASS, not the payload -- see the interpreter twin's
		// `eval.VecSimdBf16KernelsTest#signallingNanInputsAnswerNanOnBothRoutes`.
		short[] x = { (short) 0x7f81, (short) 0xff81, (short) 0x7fbf, JvmSimdVectorTemplate.floatToBf16(1.5f) };
		short[] bigX = new short[1024];
		short[] bigY = new short[1024];
		for (int i = 0; i < bigX.length; i++) {
			bigX[i] = x[i % x.length];
			bigY[i] = x[(i + 1) % x.length];
		}
		for (int op = 0; op < 4; op++) {
			warm(op, bigX, bigY);
			short[] actual = runBridgeBinary(op, bigX, bigY);
			for (int i = 0; i < 4; i++) {
				assertThat(Float.isNaN(JvmSimdVectorTemplate.bf16ToFloat(actual[i]))).as("kernel op %d i = %d", op, i)
					.isTrue();
				assertThat(Float.isNaN(JvmSimdVectorTemplate.bf16ToFloat(composite(op, bigX[i], bigY[i]))))
					.as("composite op %d i = %d", op, i)
					.isTrue();
			}
		}
	}

	@Test
	void theBridgeElementWiseIntoWritesWhatTheAllocatingBridgeReturns() {
		for (int op = 0; op < 4; op++) {
			short[] x = weights(1024, 6161 + op);
			short[] y = weights(1024, 6162 + op);
			short[] out = destinationBf16(1024);
			runBridgeBinaryInto(op, out, x, y);
			assertThat(elementsBf16(out)).as("op %d", op).isEqualTo(runBridgeBinary(op, x, y));
		}
	}

	@Test
	void theBridgeUnaryElementWiseKernelsMatchTheScalarComposite() {
		for (int op = 0; op < 4; op++) {
			for (int n : new int[] { 1, 7, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 1024 }) {
				short[] x = weights(n, 7007 + 13L * n + op);
				short[] packed = packedBf16(x);
				short[] actual = switch (op) {
					case 0 -> elementsBf16(JvmSimdVectorTemplate.simdSqrt(packed));
					case 1 -> elementsBf16(JvmSimdVectorTemplate.simdAbs(packed));
					case 2 -> elementsBf16(JvmSimdVectorTemplate.simdNegative(packed));
					default -> elementsBf16(JvmSimdVectorTemplate.simdReciprocal(packed));
				};
				for (int i = 0; i < n; i++) {
					float fa = JvmSimdVectorTemplate.bf16ToFloat(x[i]);
					short expected = switch (op) {
						case 0 -> JvmSimdVectorTemplate.floatToBf16((float) Math.sqrt(fa));
						case 1 -> JvmSimdVectorTemplate.floatToBf16(Math.abs(fa));
						case 2 -> JvmSimdVectorTemplate.floatToBf16(-fa);
						default -> JvmSimdVectorTemplate.floatToBf16(1.0f / fa);
					};
					assertThat(actual[i]).as("op %d n = %d i = %d", op, n, i).isEqualTo(expected);
				}
				short[] out = destinationBf16(n);
				switch (op) {
					case 0 -> JvmSimdVectorTemplate.simdSqrtInto(out, packed);
					case 1 -> JvmSimdVectorTemplate.simdAbsInto(out, packed);
					case 2 -> JvmSimdVectorTemplate.simdNegativeInto(out, packed);
					default -> JvmSimdVectorTemplate.simdReciprocalInto(out, packed);
				}
				assertThat(elementsBf16(out)).as("into op %d n = %d", op, n).isEqualTo(actual);
			}
		}
	}

	private static int[][] shapes() {
		return new int[][] { { 1, 1 }, { 3, 8 }, { 3, MATVEC_ROW_THRESHOLD }, { 5, 33 }, { 17, THRESHOLD - 1 },
				{ 17, THRESHOLD }, { 8, 288 }, { 64, 1024 }, { 2, 4096 } };
	}

}
