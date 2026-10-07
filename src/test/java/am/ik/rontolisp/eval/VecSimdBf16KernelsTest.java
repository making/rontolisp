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
 * exactly the reason {@code FSPECIES_REDUCE} is (see {@code .kb/vec.md}, "The
 * f32-reduction precision contract").
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
			float widened = VecSimdKernels.bf16ToFloat(bits);
			assertThat(VecSimdKernels.floatToBf16(widened) & 0xffff).as("pattern 0x%04x", p)
				.isEqualTo(am.ik.rontolisp.BFloat16.bits(widened));
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

	// --- element-wise bf16 x bf16 -> bf16 ------------------------------------------
	// The oracle here is the SCALAR composite -- widen each operand, compute in f32,
	// narrow on store through the scalar `floatToBf16` -- which an exhaustive harness
	// already swept against the `vec.lisp` DEFUN's f64 route over all 65536x65536
	// operand pairs per operation with 0 mismatches. So kernel == composite pins the
	// lane loop (and the lane narrow) against the defun transitively, and
	// `eval.VecSimdTest` pins kernel == defun directly through the evaluator.

	/** The scalar composite for one pair, the oracle the lane loop must match. */
	private static short composite(int op, short a, short b) {
		float fa = VecSimdKernels.bf16ToFloat(a);
		float fb = VecSimdKernels.bf16ToFloat(b);
		return switch (op) {
			case 0 -> VecSimdKernels.floatToBf16(fa + fb);
			case 1 -> VecSimdKernels.floatToBf16(fa - fb);
			case 2 -> VecSimdKernels.floatToBf16(fa * fb);
			default -> VecSimdKernels.floatToBf16(fa / fb);
		};
	}

	private static short[] runBinary(int op, short[] x, short[] y) {
		return runBinary(op, new short[Math.min(x.length, y.length)], x, y);
	}

	/** The scalar composite for one element, the unary oracle. */
	private static short compositeUnary(int op, short a) {
		float fa = VecSimdKernels.bf16ToFloat(a);
		return switch (op) {
			case 0 -> VecSimdKernels.floatToBf16((float) Math.sqrt(fa));
			case 1 -> VecSimdKernels.floatToBf16(Math.abs(fa));
			case 2 -> VecSimdKernels.floatToBf16(-fa);
			default -> VecSimdKernels.floatToBf16(1.0f / fa);
		};
	}

	private static void runUnaryInto(int op, short[] r, short[] x) {
		switch (op) {
			case 0 -> VecSimdKernels.sqrtIntoBf16(r, x);
			case 1 -> VecSimdKernels.absIntoBf16(r, x);
			case 2 -> VecSimdKernels.negIntoBf16(r, x);
			default -> VecSimdKernels.reciprocalIntoBf16(r, x);
		}
	}

	/**
	 * Patterns that exercise the lane narrow's arms: ties (low 16 bits exactly
	 * {@code 0x8000}, where truncation would go down and the carry must pick even), quiet
	 * NaNs with payloads, infinities, zeros of both signs, subnormals and the overflow
	 * edge. Signalling NaNs are NOT here: on an sNaN input the lane arithmetic (quieted
	 * source payload) and the scalar arithmetic (indefinite) disagree the way the
	 * hardware scalar and packed instructions do, so they are pinned separately at
	 * {@code isNaN} level by {@link #signallingNanInputsAnswerNanOnBothRoutes}.
	 */
	private static short[] trickyPatterns() {
		int[] bits = { 0x3f808000, 0x3f818000, 0x3f807fff, 0x3f808001, 0xbf818000, 0x7fc12345, 0xffc00000, 0x7f800000,
				0xff800000, 0x00000000, 0x80000000, 0x007fffff, 0x00000001, 0x7f7fffff, 0x4f800000, 0xcf800000 };
		short[] w = new short[1024];
		for (int i = 0; i < w.length; i++) {
			w[i] = VecSimdKernels.floatToBf16(Float.intBitsToFloat(bits[i % bits.length]));
		}
		return w;
	}

	/**
	 * Warms the kernel loops so the assertions below meet the JIT-compiled lane path
	 * rather than whichever tier happens to be warm: a lane-vs-scalar comparison only
	 * pins the lanes once they actually run as lanes.
	 */
	private static void warm(int op, short[] x, short[] y) {
		short[] r = new short[x.length];
		for (int k = 0; k < 100; k++) {
			runBinary(op, r, x, y);
		}
	}

	private static short[] runBinary(int op, short[] r, short[] x, short[] y) {
		switch (op) {
			case 0 -> VecSimdKernels.addIntoBf16(r, x, y);
			case 1 -> VecSimdKernels.subIntoBf16(r, x, y);
			case 2 -> VecSimdKernels.mulIntoBf16(r, x, y);
			default -> VecSimdKernels.divIntoBf16(r, x, y);
		}
		return r;
	}

	@Test
	void theElementWiseKernelsMatchTheScalarCompositeOnBothSidesOfTheLaneGate() {
		for (int op = 0; op < 4; op++) {
			for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024, 4096 }) {
				short[] x = weights(n, 1001 + 7L * n + op);
				short[] y = weights(n, 2002 + 7L * n + op);
				short[] actual = runBinary(op, x, y);
				for (int i = 0; i < n; i++) {
					assertThat(actual[i]).as("op %d n = %d i = %d", op, n, i).isEqualTo(composite(op, x[i], y[i]));
				}
			}
		}
	}

	@Test
	void theElementWiseKernelsMatchTheScalarCompositeOnTrickyPatterns() {
		// 1024 elements, so the lane loop -- including the lane narrow's NaN arm and
		// tie carry -- runs, not just the scalar tail.
		short[] x = trickyPatterns();
		short[] y = trickyPatterns();
		for (int i = 0; i < y.length; i++) {
			y[i] = (short) (y[(i * 7 + 3) % y.length] ^ 0x1234);
		}
		for (int op = 0; op < 4; op++) {
			warm(op, x, y);
			short[] actual = runBinary(op, x, y);
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
		// so the pin is the CLASS, not the payload -- and the defun agrees with the
		// scalar route's class (the exhaustive pair sweep covers sNaN patterns with 0
		// mismatches against it at the payload level, which the lanes cannot keep).
		short[] x = { (short) 0x7f81, (short) 0xff81, (short) 0x7fbf, VecSimdKernels.floatToBf16(1.5f) };
		short[] bigX = new short[1024];
		short[] bigY = new short[1024];
		for (int i = 0; i < bigX.length; i++) {
			bigX[i] = x[i % x.length];
			bigY[i] = x[(i + 1) % x.length];
		}
		for (int op = 0; op < 4; op++) {
			warm(op, bigX, bigY);
			short[] actual = runBinary(op, bigX, bigY);
			for (int i = 0; i < 4; i++) {
				assertThat(Float.isNaN(VecSimdKernels.bf16ToFloat(actual[i]))).as("kernel op %d i = %d", op, i)
					.isTrue();
				assertThat(Float.isNaN(VecSimdKernels.bf16ToFloat(composite(op, bigX[i], bigY[i]))))
					.as("composite op %d i = %d", op, i)
					.isTrue();
			}
		}
		short[] sq = new short[1024];
		for (int i = 0; i < sq.length; i++) {
			sq[i] = x[i % x.length];
		}
		short[] sqOut = new short[1024];
		for (int k = 0; k < 100; k++) {
			VecSimdKernels.sqrtIntoBf16(sqOut, sq);
		}
		VecSimdKernels.sqrtIntoBf16(sqOut, sq);
		for (int i = 0; i < 3; i++) {
			assertThat(Float.isNaN(VecSimdKernels.bf16ToFloat(sqOut[i]))).as("sqrt i = %d", i).isTrue();
		}
		assertThat(sqOut[3]).as("sqrt of 1.5").isEqualTo(compositeUnary(0, sq[3]));
	}

	@Test
	void everyBf16PatternRoundTripsThroughTheElementWiseScalarTail() {
		// Single-element vectors take the scalar tail unconditionally: all 65536
		// patterns through each operation against a fixed operand, pinning the tail
		// the lane loop must agree with.
		short[] one = new short[1];
		short[] two = new short[1];
		two[0] = VecSimdKernels.floatToBf16(1.5f);
		for (int op = 0; op < 4; op++) {
			for (int p = 0; p < 1 << 16; p++) {
				one[0] = (short) p;
				assertThat(runBinary(op, one, two)[0]).as("op %d pattern 0x%04x", op, p)
					.isEqualTo(composite(op, one[0], two[0]));
			}
		}
	}

	@Test
	void theAllocatingElementWiseKernelsWriteWhatTheIntoKernelsWrite() {
		short[] x = weights(1024, 31337);
		short[] y = weights(1024, 31338);
		assertThat(VecSimdKernels.addBf16(x, y)).isEqualTo(runBinary(0, x, y));
		assertThat(VecSimdKernels.subBf16(x, y)).isEqualTo(runBinary(1, x, y));
		assertThat(VecSimdKernels.mulBf16(x, y)).isEqualTo(runBinary(2, x, y));
		assertThat(VecSimdKernels.divBf16(x, y)).isEqualTo(runBinary(3, x, y));
	}

	@Test
	void theElementWiseIntoKernelsTolerateAliasing() {
		// out[i] depends only on x[i] and y[i], so in-place accumulation is
		// well-defined (the add-into rule): aliasing must answer what a fresh
		// destination does.
		short[] x = weights(1024, 41414);
		short[] y = weights(1024, 41415);
		short[] acc = x.clone();
		VecSimdKernels.addIntoBf16(acc, acc, y);
		assertThat(acc).isEqualTo(VecSimdKernels.addBf16(x, y));
		short[] sq = x.clone();
		VecSimdKernels.mulIntoBf16(sq, sq, sq);
		assertThat(sq).isEqualTo(VecSimdKernels.mulBf16(x, x));
	}

	@Test
	void theUnaryElementWiseKernelsMatchTheScalarCompositeOnBothSidesOfTheLaneGate() {
		for (int op = 0; op < 4; op++) {
			for (int n : new int[] { 1, 7, 63, THRESHOLD - 1, THRESHOLD, THRESHOLD + 1, 291, 1024, 4096 }) {
				short[] x = weights(n, 5005 + 11L * n + op);
				short[] actual = new short[n];
				runUnaryInto(op, actual, x);
				for (int i = 0; i < n; i++) {
					assertThat(actual[i]).as("op %d n = %d i = %d", op, n, i).isEqualTo(compositeUnary(op, x[i]));
				}
			}
		}
	}

	@Test
	void theUnaryElementWiseKernelsMatchTheScalarCompositeOnTrickyPatterns() {
		short[] x = trickyPatterns();
		for (int op = 0; op < 4; op++) {
			short[] actual = new short[x.length];
			runUnaryInto(op, actual, x);
			for (int i = 0; i < x.length; i++) {
				assertThat(actual[i]).as("op %d i = %d", op, i).isEqualTo(compositeUnary(op, x[i]));
			}
		}
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
