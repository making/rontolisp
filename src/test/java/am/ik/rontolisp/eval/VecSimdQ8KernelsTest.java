package am.ik.rontolisp.eval;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integer-dot GEMV kernels of {@link VecSimdKernels} over a Q8_0 quantized matrix
 * ({@code .kb/quantized-matrix.md}), against a scalar transcription of the
 * {@code vec.lisp} defun's arithmetic: the activation quantized to int8 per block of 32
 * ({@code sx = amax / 127} in double, {@code round} half even), an exact integer dot per
 * block, ONE double multiply-add per block, the store narrowed to the result width.
 *
 * <p>
 * The contract is BIT identity, not a tolerance -- integer sums are exact in any order
 * and the two floating-point steps per block happen in the same order on both sides -- so
 * every case compares raw bits, serially and with the rows split across
 * {@code --parallel} threads, at both activation widths.
 *
 * <p>
 * {@code codegen.jvm.JvmSimdVectorTemplateQ8Test} asserts the same for the compiled
 * bridge's mirror over the headered representation. Requires
 * {@code --add-modules jdk.incubator.vector} (the surefire {@code argLine} supplies it).
 */
class VecSimdQ8KernelsTest {

	/** {@code rows x cols} ggml Q8_0 blocks of gaussian weights at the 0.02 scale. */
	static byte[] blocks(int rows, int cols, long seed) {
		Random random = new Random(seed);
		float[] w = new float[rows * cols];
		for (int i = 0; i < w.length; i++) {
			w[i] = (float) (random.nextGaussian() * 0.02);
		}
		byte[] blocks = new byte[w.length / 32 * 34];
		for (int b = 0; b < w.length / 32; b++) {
			QuantizedMatrices.quantizeRowQ8_0(w, b * 32, blocks, b * 34);
		}
		return blocks;
	}

	static float[] activationsF(int n, long seed) {
		Random random = new Random(seed);
		float[] x = new float[n];
		for (int i = 0; i < n; i++) {
			x[i] = (float) random.nextGaussian();
		}
		return x;
	}

	static double[] activationsD(int n, long seed) {
		Random random = new Random(seed);
		double[] x = new double[n];
		for (int i = 0; i < n; i++) {
			x[i] = random.nextGaussian();
		}
		return x;
	}

	/**
	 * The defun, in Java: {@code vec::%matvec-quantized} step for step over doubles (an
	 * f32 activation is read widened, as {@code aref} widens it).
	 */
	static double[] oracle(byte[] blocks, int rows, int cols, double[] x) {
		int nb = cols / 32;
		int[] xq = new int[cols];
		double[] xs = new double[nb];
		for (int b = 0; b < nb; b++) {
			double amax = 0.0;
			for (int k = 0; k < 32; k++) {
				double v = Math.abs(x[b * 32 + k]);
				if (v > amax) {
					amax = v;
				}
			}
			double sx = amax / 127.0;
			xs[b] = sx;
			for (int k = 0; k < 32; k++) {
				xq[b * 32 + k] = sx == 0.0 ? 0 : (int) Math.rint(x[b * 32 + k] / sx);
			}
		}
		double[] r = new double[rows];
		for (int i = 0; i < rows; i++) {
			double acc = 0.0;
			for (int b = 0; b < nb; b++) {
				int bo = (i * nb + b) * 34;
				long isum = 0;
				for (int k = 0; k < 32; k++) {
					isum += blocks[bo + 2 + k] * xq[b * 32 + k];
				}
				double sw = Float.float16ToFloat((short) ((blocks[bo] & 0xff) | (blocks[bo + 1] << 8)));
				acc = acc + isum * (sw * xs[b]);
			}
			r[i] = acc;
		}
		return r;
	}

	static double[] widened(float[] x) {
		double[] d = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			d[i] = x[i];
		}
		return d;
	}

	static int[] bitsF(float[] r) {
		int[] bits = new int[r.length];
		for (int i = 0; i < r.length; i++) {
			bits[i] = Float.floatToRawIntBits(r[i]);
		}
		return bits;
	}

	static int[] narrowedBits(double[] r) {
		int[] bits = new int[r.length];
		for (int i = 0; i < r.length; i++) {
			bits[i] = Float.floatToRawIntBits((float) r[i]);
		}
		return bits;
	}

	static long[] bitsD(double[] r) {
		long[] bits = new long[r.length];
		for (int i = 0; i < r.length; i++) {
			bits[i] = Double.doubleToRawLongBits(r[i]);
		}
		return bits;
	}

	private static final int[][] SHAPES = { { 1, 32 }, { 2, 64 }, { 5, 96 }, { 17, 256 }, { 40, 1024 }, { 300, 512 } };

	@Test
	void theF32GemvIsTheDefunBitForBitSeriallyAndInParallel() {
		for (int[] shape : SHAPES) {
			int rows = shape[0], cols = shape[1];
			byte[] w = blocks(rows, cols, 100 + rows);
			float[] x = activationsF(cols, 200 + cols);
			int[] expected = narrowedBits(oracle(w, rows, cols, widened(x)));
			assertThat(bitsF(VecSimdKernels.matvecQ8F(w, rows, cols, x, false))).as("%dx%d serial", rows, cols)
				.isEqualTo(expected);
			assertThat(bitsF(VecSimdKernels.matvecQ8F(w, rows, cols, x, true))).as("%dx%d parallel", rows, cols)
				.isEqualTo(expected);
			float[] into = new float[rows];
			VecSimdKernels.matvecIntoQ8F(into, w, rows, cols, x, true);
			assertThat(bitsF(into)).as("%dx%d -into", rows, cols).isEqualTo(expected);
		}
	}

	@Test
	void theF64GemvIsTheDefunBitForBitSeriallyAndInParallel() {
		for (int[] shape : SHAPES) {
			int rows = shape[0], cols = shape[1];
			byte[] w = blocks(rows, cols, 300 + rows);
			double[] x = activationsD(cols, 400 + cols);
			long[] expected = bitsD(oracle(w, rows, cols, x));
			assertThat(bitsD(VecSimdKernels.matvecQ8D(w, rows, cols, x, false))).as("%dx%d serial", rows, cols)
				.isEqualTo(expected);
			assertThat(bitsD(VecSimdKernels.matvecQ8D(w, rows, cols, x, true))).as("%dx%d parallel", rows, cols)
				.isEqualTo(expected);
		}
	}

	@Test
	void anActivationTieRoundsToEvenAndAnAllZeroBlockContributesNothing() {
		// Block 0: amax 127 makes sx exactly 1, so 2.5 and -2.5 are their own
		// quotients and round to 2 and -2 (CL's round, Math.rint); block 1 is all zero.
		int cols = 64;
		float[] x = new float[cols];
		x[0] = 127.0f;
		x[1] = 2.5f;
		x[2] = -2.5f;
		x[3] = 0.5f;
		byte[] xq = new byte[cols];
		double[] xs = new double[2];
		VecSimdKernels.quantizeActivationF(x, 0, cols, xq, xs);
		assertThat(xq[0]).isEqualTo((byte) 127);
		assertThat(xq[1]).isEqualTo((byte) 2);
		assertThat(xq[2]).isEqualTo((byte) -2);
		assertThat(xq[3]).isEqualTo((byte) 0);
		assertThat(xs[0]).isEqualTo(1.0);
		assertThat(xs[1]).isEqualTo(0.0);
		byte[] w = blocks(3, cols, 9);
		assertThat(bitsF(VecSimdKernels.matvecQ8F(w, 3, cols, x, false)))
			.isEqualTo(narrowedBits(oracle(w, 3, cols, widened(x))));
	}

	@Test
	void theWeightQuantsMayReachMinus128WithoutOverflowingTheShortProducts() {
		// A hand-written block whose quants are all -128 against an activation that
		// quantizes to +-127: |2 x 128 x 127| = 32512 < 32767, the bound the short
		// pairwise add relies on.
		int cols = 32;
		byte[] w = new byte[34];
		short d = Float.floatToFloat16(0.5f);
		w[0] = (byte) d;
		w[1] = (byte) (d >>> 8);
		for (int k = 0; k < 32; k++) {
			w[2 + k] = (byte) -128;
		}
		float[] x = new float[cols];
		for (int k = 0; k < cols; k++) {
			x[k] = (k % 2 == 0) ? 3.0f : -3.0f;
		}
		assertThat(bitsF(VecSimdKernels.matvecQ8F(w, 1, cols, x, false)))
			.isEqualTo(narrowedBits(oracle(w, 1, cols, widened(x))));
	}

}
