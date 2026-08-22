import jdk.incubator.vector.*;

/** Two questions: at what reuse does a narrow width stop costing, and is bf16 the better narrow width? */
public class Worth {
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SSH = VectorSpecies.of(short.class, VectorShape.forBitSize(IS.length() * 16));
	static final int SE = 0x7c00 << 13, L = FS.length();
	static final FloatVector SCALE = FloatVector.broadcast(FS, 0x1.0p112f);
	static final IntVector INF_FIX = IntVector.broadcast(IS, 0x7f800000 - 0x47800000);

	static IntVector widen(short[] h, int off) {
		return (IntVector) ShortVector.fromArray(SSH, h, off).convertShape(VectorOperators.S2I, IS, 0);
	}

	/** f16 -> f32, exact for every finite pattern. ~6 ops per lane. */
	static FloatVector decF16(short[] h, int off) {
		IntVector hv = widen(h, off);
		IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
		return u.reinterpretAsFloats().mul(SCALE).reinterpretAsInts()
				.add(INF_FIX, u.and(SE).compare(VectorOperators.EQ, SE))
				.or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats();
	}

	/** bf16 -> f32. One shift. Exact by construction: bf16 IS the top half of an f32. */
	static FloatVector decBf16(short[] b, int off) {
		return widen(b, off).lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats();
	}

	static short toBf16(float f) {   // round-to-nearest-even
		int b = Float.floatToRawIntBits(f);
		return (short) ((b + 0x7fff + ((b >>> 16) & 1)) >>> 16);
	}

	interface Row { float run(int base, int cols, float[] x); }

	static void gemv(int rows, int cols, float[] x, float[] r, Row row) {
		for (int i = 0; i < rows; i++) r[i] = row.run(i * cols, cols, x);
	}

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

	static float rowNarrow(short[] w, int base, int cols, float[] x, boolean bf) {
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		int i = 0, b = cols - (cols % (4 * L));
		for (; i < b; i += 4 * L) {
			a0 = (bf ? decBf16(w, base + i) : decF16(w, base + i)).fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = (bf ? decBf16(w, base + i + L) : decF16(w, base + i + L)).fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = (bf ? decBf16(w, base + i + 2 * L) : decF16(w, base + i + 2 * L)).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = (bf ? decBf16(w, base + i + 3 * L) : decF16(w, base + i + 3 * L)).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		float s = a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
		for (; i < cols; i++) s += (bf ? Float.intBitsToFloat(w[base + i] << 16) : Float.float16ToFloat(w[base + i])) * x[i];
		return s;
	}

	static void widenAll(short[] src, float[] dst, boolean bf) {
		int i = 0, bd = IS.loopBound(src.length);
		for (; i < bd; i += IS.length()) (bf ? decBf16(src, i) : decF16(src, i)).intoArray(dst, i);
		for (; i < src.length; i++) dst[i] = bf ? Float.intBitsToFloat(src[i] << 16) : Float.float16ToFloat(src[i]);
	}

	static long t(Runnable r, int it) {
		for (int i = 0; i < 6; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 5; k++) { long s = System.nanoTime(); for (int i = 0; i < it; i++) r.run(); best = Math.min(best, (System.nanoTime() - s) / it); }
		return best;
	}

	public static void main(String[] a) {
		java.util.Random rn = new java.util.Random(5);
		for (int[] sz : new int[][] { { 1024, 1024 }, { 4096, 4096 } }) {
			int rows = sz[0], cols = sz[1]; int e = rows * cols;
			float[] wf = new float[e]; short[] wh = new short[e], wb = new short[e];
			double maxF16 = 0, maxBf16 = 0;
			for (int i = 0; i < e; i++) {
				float v = (float) (rn.nextGaussian() * 0.02);
				wf[i] = v; wh[i] = Float.floatToFloat16(v); wb[i] = toBf16(v);
				if (v != 0) {
					maxF16 = Math.max(maxF16, Math.abs((Float.float16ToFloat(wh[i]) - v) / v));
					maxBf16 = Math.max(maxBf16, Math.abs((Float.intBitsToFloat(wb[i] << 16) - v) / v));
				}
			}
			float[] scratch = new float[e];
			float[][] xs = new float[64][cols];
			for (float[] x : xs) for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
			float[] r = new float[rows];
			int it = e > (1 << 22) ? 3 : 15;

			System.out.printf("%n=== %dx%d  (f32 %.0f MB, narrow %.0f MB)  weight round-trip rel.err: f16 %.2e  bf16 %.2e%n",
					rows, cols, e * 4 / 1e6, e * 2 / 1e6, maxF16, maxBf16);
			System.out.printf("%-6s %10s %10s %10s %12s %12s %12s%n",
					"reuse", "f32 ms", "f16fus ms", "bf16fus ms", "f16widen ms", "f16fus vs32", "bf16fus vs32");
			for (int k : new int[] { 1, 2, 4, 8, 16, 64 }) {
				long t32 = t(() -> { for (int j = 0; j < k; j++) { final int jj = j; gemv(rows, cols, xs[jj], r, (b2, c, x) -> rowF32(wf, b2, c, x)); } }, it);
				long t16 = t(() -> { for (int j = 0; j < k; j++) { final int jj = j; gemv(rows, cols, xs[jj], r, (b2, c, x) -> rowNarrow(wh, b2, c, x, false)); } }, it);
				long tb = t(() -> { for (int j = 0; j < k; j++) { final int jj = j; gemv(rows, cols, xs[jj], r, (b2, c, x) -> rowNarrow(wb, b2, c, x, true)); } }, it);
				long tw = t(() -> { widenAll(wh, scratch, false); for (int j = 0; j < k; j++) { final int jj = j; gemv(rows, cols, xs[jj], r, (b2, c, x) -> rowF32(scratch, b2, c, x)); } }, it);
				System.out.printf("%-6d %10.2f %10.2f %10.2f %12.2f %12.2fx %11.2fx%n", k,
						t32 / 1e6, t16 / 1e6, tb / 1e6, tw / 1e6, t32 / (double) t16, t32 / (double) tb);
			}
		}
	}
}
