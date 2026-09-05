package am.ik.rontolisp.codegen.jvm;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compiled bridge's integer-dot GEMV over a Q8_0 quantized matrix
 * ({@link JvmSimdVectorTemplate#matvecQ8F} and its siblings), against the same scalar
 * transcription of the {@code vec.lisp} defun {@code eval.VecSimdQ8KernelsTest} uses --
 * repeated here because the two packages may not reference each other -- over the
 * compiled representation: a {@code byte[]} whose little-endian int header
 * ({@link JvmQuantizedMatrixRuntimeBuilder}) is what the kernel reads its shape from. Bit
 * identity, serial and {@code --parallel}, both activation widths, and the {@code -into}
 * form writing past a destination's header.
 */
class JvmSimdVectorTemplateQ8Test {

	/** ggml's {@code quantize_row_q8_0_ref} over one block, for the fixture. */
	private static void quantizeBlock(float[] src, int srcOff, byte[] dst, int dstOff) {
		float amax = 0.0f;
		for (int k = 0; k < 32; k++) {
			amax = Math.max(amax, Math.abs(src[srcOff + k]));
		}
		float d = amax / 127.0f;
		float id = d != 0.0f ? 1.0f / d : 0.0f;
		short dh = Float.floatToFloat16(d);
		dst[dstOff] = (byte) dh;
		dst[dstOff + 1] = (byte) (dh >>> 8);
		for (int k = 0; k < 32; k++) {
			float x0 = src[srcOff + k] * id;
			dst[dstOff + 2 + k] = (byte) (x0 < 0 ? -Math.round(-x0) : Math.round(x0));
		}
	}

	private static void putInt(byte[] a, int off, int v) {
		for (int k = 0; k < 4; k++) {
			a[off + k] = (byte) (v >>> (8 * k));
		}
	}

	/** A compiled rank-2 quantized matrix: {@code [1, 2, rows, cols]} then the blocks. */
	static byte[] packedQ8(int rows, int cols, long seed) {
		Random random = new Random(seed);
		float[] w = new float[rows * cols];
		for (int i = 0; i < w.length; i++) {
			w[i] = (float) (random.nextGaussian() * 0.02);
		}
		byte[] m = new byte[16 + w.length / 32 * 34];
		putInt(m, 0, 1);
		putInt(m, 4, 2);
		putInt(m, 8, rows);
		putInt(m, 12, cols);
		for (int b = 0; b < w.length / 32; b++) {
			quantizeBlock(w, b * 32, m, 16 + b * 34);
		}
		return m;
	}

	static float[] packedF(int n, long seed) {
		Random random = new Random(seed);
		float[] v = new float[2 + n];
		v[0] = 1.0f;
		v[1] = n;
		for (int i = 0; i < n; i++) {
			v[2 + i] = (float) random.nextGaussian();
		}
		return v;
	}

	static double[] packedD(int n, long seed) {
		Random random = new Random(seed);
		double[] v = new double[2 + n];
		v[0] = 1.0;
		v[1] = n;
		for (int i = 0; i < n; i++) {
			v[2 + i] = random.nextGaussian();
		}
		return v;
	}

	/** The defun in Java, over header-free operands. */
	static double[] oracle(byte[] m, int rows, int cols, double[] x) {
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
				int bo = 16 + (i * nb + b) * 34;
				long isum = 0;
				for (int k = 0; k < 32; k++) {
					isum += m[bo + 2 + k] * xq[b * 32 + k];
				}
				double sw = Float.float16ToFloat((short) ((m[bo] & 0xff) | (m[bo + 1] << 8)));
				acc = acc + isum * (sw * xs[b]);
			}
			r[i] = acc;
		}
		return r;
	}

	private static double[] elementsWidened(float[] packed) {
		double[] d = new double[packed.length - 2];
		for (int i = 0; i < d.length; i++) {
			d[i] = packed[2 + i];
		}
		return d;
	}

	private static int[] narrowedBits(double[] r) {
		int[] bits = new int[r.length];
		for (int i = 0; i < r.length; i++) {
			bits[i] = Float.floatToRawIntBits((float) r[i]);
		}
		return bits;
	}

	private static int[] bitsF(float[] packed, int off) {
		int[] bits = new int[packed.length - off];
		for (int i = 0; i < bits.length; i++) {
			bits[i] = Float.floatToRawIntBits(packed[off + i]);
		}
		return bits;
	}

	private static long[] bitsD(double[] r, int from, int to) {
		long[] bits = new long[to - from];
		for (int i = 0; i < bits.length; i++) {
			bits[i] = Double.doubleToRawLongBits(r[from + i]);
		}
		return bits;
	}

	private static final int[][] SHAPES = { { 1, 32 }, { 2, 64 }, { 5, 96 }, { 17, 256 }, { 40, 1024 }, { 300, 512 } };

	@Test
	void theF32GemvIsTheDefunBitForBitOverTheHeaderedRepresentation() {
		for (int[] shape : SHAPES) {
			int rows = shape[0], cols = shape[1];
			byte[] w = packedQ8(rows, cols, 11 + rows);
			float[] x = packedF(cols, 13 + cols);
			int[] expected = narrowedBits(oracle(w, rows, cols, elementsWidened(x)));
			float[] serial = JvmSimdVectorTemplate.matvecQ8F(w, x, false);
			assertThat(serial[0]).isEqualTo(1.0f);
			assertThat((int) serial[1]).isEqualTo(rows);
			assertThat(bitsF(serial, 2)).as("%dx%d serial", rows, cols).isEqualTo(expected);
			assertThat(bitsF(JvmSimdVectorTemplate.matvecQ8F(w, x, true), 2)).as("%dx%d parallel", rows, cols)
				.isEqualTo(expected);
			// -into a destination whose elements start past a rank-1 header.
			float[] into = new float[2 + rows];
			into[0] = 1.0f;
			into[1] = rows;
			JvmSimdVectorTemplate.matvecIntoQ8F(into, 2, w, x, true);
			assertThat(bitsF(into, 2)).as("%dx%d -into", rows, cols).isEqualTo(expected);
		}
	}

	@Test
	void theF64GemvIsTheDefunBitForBitOverTheHeaderedRepresentation() {
		for (int[] shape : SHAPES) {
			int rows = shape[0], cols = shape[1];
			byte[] w = packedQ8(rows, cols, 17 + rows);
			double[] x = packedD(cols, 19 + cols);
			double[] expected = oracle(w, rows, cols, Arrays.copyOfRange(x, 2, x.length));
			double[] serial = JvmSimdVectorTemplate.matvecQ8D(w, x, false);
			assertThat(bitsD(serial, 2, serial.length)).as("%dx%d serial", rows, cols)
				.isEqualTo(bitsD(expected, 0, rows));
			double[] parallel = JvmSimdVectorTemplate.matvecQ8D(w, x, true);
			assertThat(bitsD(parallel, 2, parallel.length)).as("%dx%d parallel", rows, cols)
				.isEqualTo(bitsD(expected, 0, rows));
			double[] into = new double[2 + rows];
			into[0] = 1.0;
			into[1] = rows;
			JvmSimdVectorTemplate.matvecIntoQ8D(into, 2, w, x, false);
			assertThat(bitsD(into, 2, into.length)).as("%dx%d -into", rows, cols).isEqualTo(bitsD(expected, 0, rows));
		}
	}

	@Test
	void theBridgeEntriesDispatchAByteArrayWeightMatrixToTheIntegerDotKernel() {
		int rows = 17, cols = 256;
		byte[] w = packedQ8(rows, cols, 23);
		float[] x = packedF(cols, 29);
		int[] expected = narrowedBits(oracle(w, rows, cols, elementsWidened(x)));
		assertThat(bitsF((float[]) java.util.Objects.requireNonNull(JvmSimdVectorTemplate.simdMatvec(w, x)), 2))
			.isEqualTo(expected);
		assertThat(bitsF((float[]) java.util.Objects.requireNonNull(JvmSimdVectorTemplate.simdMatvecParallel(w, x)), 2))
			.isEqualTo(expected);
		float[] out = new float[2 + rows];
		out[0] = 1.0f;
		out[1] = rows;
		assertThat(JvmSimdVectorTemplate.simdMatvecInto(out, w, x)).isSameAs(out);
		assertThat(bitsF(out, 2)).isEqualTo(expected);
		double[] xd = packedD(cols, 31);
		double[] expectedD = oracle(w, rows, cols, Arrays.copyOfRange(xd, 2, xd.length));
		double[] rd = (double[]) java.util.Objects.requireNonNull(JvmSimdVectorTemplate.simdMatvec(w, xd));
		assertThat(bitsD(rd, 2, rd.length)).isEqualTo(bitsD(expectedD, 0, rows));
	}

}
