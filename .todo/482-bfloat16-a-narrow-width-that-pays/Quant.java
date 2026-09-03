import jdk.incubator.vector.*;
import java.util.concurrent.*;

/**
 * Is a block-quantized integer width (Q8_0 / Q4_0, the GGUF shapes) faster than bf16 on the JVM's
 * Vector API, at 1 thread and at 20? f32 is the baseline, bf16 the width .todo/482 chose.
 * GEMV, f32 activations, 4 accumulator chains where the shape allows, 128-bit lanes.
 */
public class Quant {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SSH = VectorSpecies.of(short.class, VectorShape.forBitSize(IS.length() * 16));
	static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;
	static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;
	static final int L = FS.length();      // 4 on this box
	static final int GS = 32;              // ggml block size for Q8_0 / Q4_0

	// ---- f32 -----------------------------------------------------------------------------------
	static float rowF32(float[] w, int base, int cols, float[] x) {
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		int i = 0, b = cols - (cols % (4 * L));
		for (; i < b; i += 4 * L) {
			a0 = FloatVector.fromArray(FS, w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = FloatVector.fromArray(FS, w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = FloatVector.fromArray(FS, w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = FloatVector.fromArray(FS, w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		float s = a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
		for (; i < cols; i++) s += w[base + i] * x[i];
		return s;
	}

	// ---- bf16 ----------------------------------------------------------------------------------
	static FloatVector decBf16(short[] b, int off) {
		return ((IntVector) ShortVector.fromArray(SSH, b, off).convertShape(VectorOperators.S2I, IS, 0))
				.lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats();
	}
	static short toBf16(float f) {
		int b = Float.floatToRawIntBits(f);
		return (short) ((b + 0x7fff + ((b >>> 16) & 1)) >>> 16);
	}
	static float rowBf16(short[] w, int base, int cols, float[] x) {
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		int i = 0, b = cols - (cols % (4 * L));
		for (; i < b; i += 4 * L) {
			a0 = decBf16(w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = decBf16(w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = decBf16(w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = decBf16(w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		float s = a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
		for (; i < cols; i++) s += Float.intBitsToFloat(w[base + i] << 16) * x[i];
		return s;
	}

	// ---- Q8_0: 32 int8 + one f32 scale per block (ggml keeps the scale as f16; same bytes ±) ----
	static FloatVector decI8(ByteVector bv, int part) {
		return (FloatVector) ((IntVector) bv.convertShape(VectorOperators.B2I, IS, part)).convert(VectorOperators.I2F, 0);
	}
	/** dequantize each lane to f32 and FMA -- the "narrow storage, f32 arithmetic" shape. */
	static float rowQ8Deq(byte[] q, float[] s, int base, int cols, float[] x) {
		FloatVector acc0 = FloatVector.zero(FS), acc1 = acc0;
		int nb = cols / GS, sb = base / GS;
		for (int blk = 0; blk < nb; blk++) {
			int off = base + blk * GS, xo = blk * GS;
			ByteVector v0 = ByteVector.fromArray(B128, q, off), v1 = ByteVector.fromArray(B128, q, off + 16);
			FloatVector b0 = decI8(v0, 0).mul(FloatVector.fromArray(FS, x, xo));
			FloatVector b1 = decI8(v0, 1).mul(FloatVector.fromArray(FS, x, xo + 4));
			b0 = decI8(v0, 2).fma(FloatVector.fromArray(FS, x, xo + 8), b0);
			b1 = decI8(v0, 3).fma(FloatVector.fromArray(FS, x, xo + 12), b1);
			b0 = decI8(v1, 0).fma(FloatVector.fromArray(FS, x, xo + 16), b0);
			b1 = decI8(v1, 1).fma(FloatVector.fromArray(FS, x, xo + 20), b1);
			b0 = decI8(v1, 2).fma(FloatVector.fromArray(FS, x, xo + 24), b0);
			b1 = decI8(v1, 3).fma(FloatVector.fromArray(FS, x, xo + 28), b1);
			FloatVector sc = FloatVector.broadcast(FS, s[sb + blk]);
			acc0 = b0.fma(sc, acc0);
			acc1 = b1.fma(sc, acc1);
		}
		return acc0.add(acc1).reduceLanes(VectorOperators.ADD);
	}
	/** runq.c / ggml shape: the activation is quantized to int8 per block too, the dot is integer. */
	static float rowQ8Int(byte[] q, float[] s, int base, int cols, byte[] xq, float[] xs) {
		FloatVector acc = FloatVector.zero(FS);
		int nb = cols / GS, sb = base / GS;
		for (int blk = 0; blk < nb; blk++) {
			int off = base + blk * GS, xo = blk * GS;
			ByteVector w0 = ByteVector.fromArray(B128, q, off), w1 = ByteVector.fromArray(B128, q, off + 16);
			ByteVector x0 = ByteVector.fromArray(B128, xq, xo), x1 = ByteVector.fromArray(B128, xq, xo + 16);
			ShortVector p = ((ShortVector) w0.convertShape(VectorOperators.B2S, S128, 0)).mul((ShortVector) x0.convertShape(VectorOperators.B2S, S128, 0))
					.add(((ShortVector) w0.convertShape(VectorOperators.B2S, S128, 1)).mul((ShortVector) x0.convertShape(VectorOperators.B2S, S128, 1)));
			ShortVector r = ((ShortVector) w1.convertShape(VectorOperators.B2S, S128, 0)).mul((ShortVector) x1.convertShape(VectorOperators.B2S, S128, 0))
					.add(((ShortVector) w1.convertShape(VectorOperators.B2S, S128, 1)).mul((ShortVector) x1.convertShape(VectorOperators.B2S, S128, 1)));
			IntVector isum = ((IntVector) p.convertShape(VectorOperators.S2I, IS, 0)).add((IntVector) p.convertShape(VectorOperators.S2I, IS, 1))
					.add((IntVector) r.convertShape(VectorOperators.S2I, IS, 0)).add((IntVector) r.convertShape(VectorOperators.S2I, IS, 1));
			acc = ((FloatVector) isum.convert(VectorOperators.I2F, 0)).fma(FloatVector.broadcast(FS, s[sb + blk] * xs[blk]), acc);
		}
		return acc.reduceLanes(VectorOperators.ADD);
	}
	static void quantizeX(float[] x, byte[] xq, float[] xs) {
		for (int blk = 0; blk < x.length / GS; blk++) {
			float amax = 0;
			for (int k = 0; k < GS; k++) amax = Math.max(amax, Math.abs(x[blk * GS + k]));
			float sc = amax / 127f, inv = sc == 0 ? 0 : 1 / sc;
			xs[blk] = sc;
			for (int k = 0; k < GS; k++) xq[blk * GS + k] = (byte) Math.round(x[blk * GS + k] * inv);
		}
	}

	// ---- Q4_0: 32 nibbles (16 bytes, low nibbles = elements 0..15, high = 16..31), value = (n - 8) * scale ----
	/** the -8 is folded out: dot((n-8), x) = dot(n, x) - 8 * sum(x) over the block, with the block sums of x taken once. */
	static float rowQ4Deq(byte[] q, float[] s, int base, int cols, float[] x, float[] xBlockSum) {
		FloatVector acc0 = FloatVector.zero(FS), acc1 = acc0;
		int nb = cols / GS, sb = base / GS;
		float corr = 0;
		for (int blk = 0; blk < nb; blk++) {
			int off = (base + blk * GS) / 2, xo = blk * GS;
			ByteVector v = ByteVector.fromArray(B128, q, off);
			FloatVector b0 = FloatVector.zero(FS), b1 = b0;
			for (int part = 0; part < 4; part++) {
				IntVector iv = (IntVector) v.convertShape(VectorOperators.B2I, IS, part);
				FloatVector lo = (FloatVector) iv.and(0xF).convert(VectorOperators.I2F, 0);
				FloatVector hi = (FloatVector) iv.lanewise(VectorOperators.LSHR, 4).and(0xF).convert(VectorOperators.I2F, 0);
				b0 = lo.fma(FloatVector.fromArray(FS, x, xo + part * 4), b0);
				b1 = hi.fma(FloatVector.fromArray(FS, x, xo + 16 + part * 4), b1);
			}
			float sc = s[sb + blk];
			FloatVector scv = FloatVector.broadcast(FS, sc);
			acc0 = b0.fma(scv, acc0);
			acc1 = b1.fma(scv, acc1);
			corr += 8f * xBlockSum[blk] * sc;
		}
		return acc0.add(acc1).reduceLanes(VectorOperators.ADD) - corr;
	}

	// ---- driver ----------------------------------------------------------------------------------
	interface Row { float run(int base, int cols); }
	static final int THREADS = 20;
	static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS);
	static void gemv(int rows, int cols, float[] r, Row row, boolean par) {
		if (!par) { for (int i = 0; i < rows; i++) r[i] = row.run(i * cols, cols); return; }
		int chunk = (rows + THREADS - 1) / THREADS;
		java.util.List<Future<?>> fs = new java.util.ArrayList<>();
		for (int t = 0; t < THREADS; t++) {
			int from = t * chunk, to = Math.min(rows, from + chunk);
			if (from >= to) break;
			fs.add(POOL.submit(() -> { for (int i = from; i < to; i++) r[i] = row.run(i * cols, cols); }));
		}
		try { for (Future<?> f : fs) f.get(); } catch (Exception e) { throw new RuntimeException(e); }
	}
	static long t(Runnable r, int it) {
		for (int i = 0; i < 8; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 5; k++) { long s = System.nanoTime(); for (int i = 0; i < it; i++) r.run(); best = Math.min(best, (System.nanoTime() - s) / it); }
		return best;
	}
	static double relErr(float[] got, double[] ref) {
		double num = 0, den = 0;
		for (int i = 0; i < got.length; i++) { num += (got[i] - ref[i]) * (got[i] - ref[i]); den += ref[i] * ref[i]; }
		return Math.sqrt(num / den);
	}

	public static void main(String[] a) {
		boolean par = a.length > 0 && a[0].equals("par");
		System.out.printf("lanes: FS=%d IS=%d threads=%s%n", FS.length(), IS.length(), par ? THREADS : 1);
		java.util.Random rn = new java.util.Random(7);
		int[][] shapes = { { 1024, 1024 }, { 4096, 4096 }, { 5632, 2048 }, { 2048, 5632 }, { 8192, 8192 } };
		System.out.printf("%-11s %8s | %8s %8s %8s %8s %8s | %7s %7s %7s %7s | %8s %8s %8s %8s%n", "shape", "f32 MB",
				"f32 ms", "bf16 ms", "q8deq ms", "q8int ms", "q4 ms", "bf16/32", "q8d/32", "q8i/32", "q4/32", "bf16 GB/s", "q8 GB/s", "q4 GB/s", "f32 GB/s");
		for (int[] sz : shapes) {
			int rows = sz[0], cols = sz[1], e = rows * cols, nb = e / GS;
			float[] wf = new float[e]; short[] wb = new short[e];
			byte[] q8 = new byte[e]; float[] s8 = new float[nb];
			byte[] q4 = new byte[e / 2]; float[] s4 = new float[nb];
			for (int i = 0; i < e; i++) { wf[i] = (float) (rn.nextGaussian() * 0.02); wb[i] = toBf16(wf[i]); }
			for (int blk = 0; blk < nb; blk++) {
				float amax = 0, maxv = 0;
				for (int k = 0; k < GS; k++) { float v = wf[blk * GS + k]; if (Math.abs(v) > amax) { amax = Math.abs(v); maxv = v; } }
				float sc8 = amax / 127f, inv8 = sc8 == 0 ? 0 : 1 / sc8;
				s8[blk] = sc8;
				for (int k = 0; k < GS; k++) q8[blk * GS + k] = (byte) Math.round(wf[blk * GS + k] * inv8);
				float sc4 = maxv / -8f, inv4 = sc4 == 0 ? 0 : 1 / sc4;   // ggml's Q4_0 convention
				s4[blk] = sc4;
				for (int k = 0; k < GS; k++) {
					int n = Math.min(15, (int) (wf[blk * GS + k] * inv4 + 8.5f));
					int j = k % 16; if (k < 16) q4[blk * 16 + j] |= (byte) n; else q4[blk * 16 + j] |= (byte) (n << 4);
				}
			}
			float[] x = new float[cols]; for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
			byte[] xq = new byte[cols]; float[] xs = new float[cols / GS]; quantizeX(x, xq, xs);
			float[] xbs = new float[cols / GS]; for (int blk = 0; blk < cols / GS; blk++) { float t = 0; for (int k = 0; k < GS; k++) t += x[blk * GS + k]; xbs[blk] = t; }
			double[] ref = new double[rows];
			for (int i = 0; i < rows; i++) { double t = 0; for (int j = 0; j < cols; j++) t += (double) wf[i * cols + j] * x[j]; ref[i] = t; }
			float[] r = new float[rows];
			int it = e >= (1 << 24) ? 4 : e >= (1 << 22) ? 10 : 40;
			long t32 = t(() -> gemv(rows, cols, r, (b, c) -> rowF32(wf, b, c, x), par), it); double e32 = relErr(r, ref);
			long tb = t(() -> gemv(rows, cols, r, (b, c) -> rowBf16(wb, b, c, x), par), it); double eb = relErr(r, ref);
			long t8d = t(() -> gemv(rows, cols, r, (b, c) -> rowQ8Deq(q8, s8, b, c, x), par), it); double e8d = relErr(r, ref);
			long t8i = t(() -> gemv(rows, cols, r, (b, c) -> rowQ8Int(q8, s8, b, c, xq, xs), par), it); double e8i = relErr(r, ref);
			long t4 = t(() -> gemv(rows, cols, r, (b, c) -> rowQ4Deq(q4, s4, b, c, x, xbs), par), it); double e4 = relErr(r, ref);
			System.out.printf("%-11s %8.0f | %8.3f %8.3f %8.3f %8.3f %8.3f | %6.2fx %6.2fx %6.2fx %6.2fx | %8.1f %8.1f %8.1f %8.1f%n",
					rows + "x" + cols, e * 4 / 1e6, t32 / 1e6, tb / 1e6, t8d / 1e6, t8i / 1e6, t4 / 1e6,
					t32 / (double) tb, t32 / (double) t8d, t32 / (double) t8i, t32 / (double) t4,
					e * 2.0 / tb, (e + nb * 4.0) / t8d, (e / 2.0 + nb * 4.0) / t4, e * 4.0 / t32);
			System.out.printf("%-11s rel.err vs f64: f32 %.1e  bf16 %.1e  q8deq %.1e  q8int %.1e  q4 %.1e%n", "", e32, eb, e8d, e8i, e4);
		}
		POOL.shutdown();
	}
}
