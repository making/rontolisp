import jdk.incubator.vector.*;

public class Gem {
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SSH = VectorSpecies.of(short.class, VectorShape.forBitSize(IS.length() * 16));
	static final int SE = 0x7c00 << 13;
	static final FloatVector SCALE = FloatVector.broadcast(FS, 0x1.0p112f);
	static final IntVector INF_FIX = IntVector.broadcast(IS, 0x7f800000 - 0x47800000);

	/** Exact f16 -> f32 for all 65536 patterns: magic multiply + one masked inf/nan fixup. */
	static FloatVector decode(short[] h, int off) {
		IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, off).convertShape(VectorOperators.S2I, IS, 0);
		IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
		IntVector r = u.reinterpretAsFloats().mul(SCALE).reinterpretAsInts()
				.add(INF_FIX, u.and(SE).compare(VectorOperators.EQ, SE));
		return r.or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats();
	}

	static void decodeAll(short[] h, float[] o) {
		int i = 0, bd = IS.loopBound(h.length);
		for (; i < bd; i += IS.length()) decode(h, i).intoArray(o, i);
		for (; i < h.length; i++) o[i] = Float.float16ToFloat(h[i]);
	}

	static void gemvF32(float[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols; FloatVector acc = FloatVector.zero(FS);
			int i = 0, b = FS.loopBound(cols);
			for (; i < b; i += FS.length())
				acc = acc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			float s = acc.reduceLanes(VectorOperators.ADD);
			for (; i < cols; i++) s += w[base + i] * x[i];
			r[row] = s;
		}
	}

	static void gemvF16(short[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols; FloatVector acc = FloatVector.zero(FS);
			int i = 0, b = IS.loopBound(cols);
			for (; i < b; i += IS.length())
				acc = acc.add(decode(w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			float s = acc.reduceLanes(VectorOperators.ADD);
			for (; i < cols; i++) s += Float.float16ToFloat(w[base + i]) * x[i];
			r[row] = s;
		}
	}

	static long t(Runnable r, int it) {
		for (int i = 0; i < 8; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 7; k++) { long s = System.nanoTime(); for (int i = 0; i < it; i++) r.run(); best = Math.min(best, (System.nanoTime() - s) / it); }
		return best;
	}

	public static void main(String[] a) {
		short[] all = new short[65536]; for (int i = 0; i < 65536; i++) all[i] = (short) i;
		float[] ref = new float[65536], got = new float[65536];
		for (int i = 0; i < 65536; i++) ref[i] = Float.float16ToFloat(all[i]);
		decodeAll(all, got);
		int bad = 0;
		for (int i = 0; i < 65536; i++)
			if (Float.floatToRawIntBits(ref[i]) != Float.floatToRawIntBits(got[i])) bad++;
		System.out.println("exact-decode mismatches over all 65536 patterns (raw bits, NaN payload included): " + bad);

		java.util.Random rn = new java.util.Random(7);
		int N = 1 << 22; short[] hh = new short[N]; float[] oo = new float[N];
		for (int i = 0; i < N; i++) hh[i] = Float.floatToFloat16((float) (rn.nextGaussian() * 0.02));
		long td = t(() -> decodeAll(hh, oo), 30);
		System.out.printf("exact-decode throughput: %.2f Gelem/s%n%n", N / (double) td);

		for (int[] sz : new int[][] { { 288, 288 }, { 1024, 1024 }, { 4096, 4096 }, { 8192, 8192 } }) {
			int rows = sz[0], cols = sz[1]; long e = (long) rows * cols;
			float[] wf = new float[(int) e]; short[] wh = new short[(int) e];
			for (int i = 0; i < e; i++) { float v = (float) (rn.nextGaussian() * 0.02); wf[i] = v; wh[i] = Float.floatToFloat16(v); }
			float[] x = new float[cols]; for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
			float[] r1 = new float[rows], r2 = new float[rows];
			int it = e > (1 << 22) ? 4 : 50;
			long t32 = t(() -> gemvF32(wf, rows, cols, x, r1), it);
			long t16 = t(() -> gemvF16(wh, rows, cols, x, r2), it);
			double err = 0, mx = 0;
			for (int i = 0; i < rows; i++) { err = Math.max(err, Math.abs(r2[i] - r1[i])); mx = Math.max(mx, Math.abs(r1[i])); }
			System.out.printf("gemv %5dx%-5d  f32 %8.3f ms (%5.1f GB/s)   f16 %8.3f ms (%5.1f GB/s)   speedup %.2fx   mem %.0f->%.0f MB   relerr %.2f%%%n",
					rows, cols, t32 / 1e6, e * 4 / 1e9 / (t32 / 1e9), t16 / 1e6, e * 2 / 1e9 / (t16 / 1e9),
					t32 / (double) t16, e * 4 / 1e6, e * 2 / 1e6, 100 * err / mx);
		}
	}
}
