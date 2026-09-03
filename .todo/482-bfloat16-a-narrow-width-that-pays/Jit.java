import jdk.incubator.vector.*;

/** The same bf16/f16 GEMV in several JIT-facing shapes, so the two JITs (Graal, C2) can be compared per shape. */
public class Jit {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_128;
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_128;
	static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;
	static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;
	static final int L = 4, SE = 0x7c00 << 13;
	static final FloatVector SCALE = FloatVector.broadcast(FS, 0x1.0p112f);
	static final IntVector INF_FIX = IntVector.broadcast(IS, 0x7f800000 - 0x47800000);

	static short toBf16(float f) { int b = Float.floatToRawIntBits(f); return (short) ((b + 0x7fff + ((b >>> 16) & 1)) >>> 16); }

	static float rowF32(float[] w, int base, int cols, float[] x) {
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		int i = 0;
		for (; i < cols; i += 4 * L) {
			a0 = FloatVector.fromArray(FS, w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = FloatVector.fromArray(FS, w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = FloatVector.fromArray(FS, w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = FloatVector.fromArray(FS, w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		return a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
	}
	// 1. the spike's shape: S64 -> convertShape(S2I, IS, 0)
	static FloatVector bfShape(short[] w, int off) {
		return ((IntVector) ShortVector.fromArray(S64, w, off).convertShape(VectorOperators.S2I, IS, 0)).lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats();
	}
	// 2. S64 -> convert(S2I, 0): lane count preserved (4 -> 4), shape grows 64 -> 128
	static FloatVector bfConvert(short[] w, int off) {
		return ((IntVector) ShortVector.fromArray(S64, w, off).convert(VectorOperators.S2I, 0)).lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats();
	}
	static float rowBf(short[] w, int base, int cols, float[] x, boolean conv) {
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		for (int i = 0; i < cols; i += 4 * L) {
			a0 = (conv ? bfConvert(w, base + i) : bfShape(w, base + i)).fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = (conv ? bfConvert(w, base + i + L) : bfShape(w, base + i + L)).fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = (conv ? bfConvert(w, base + i + 2 * L) : bfShape(w, base + i + 2 * L)).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = (conv ? bfConvert(w, base + i + 3 * L) : bfShape(w, base + i + 3 * L)).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		return a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
	}
	// 3. S128 (8 shorts) -> two int vectors by convertShape part 0 / part 1
	static float rowBf128(short[] w, int base, int cols, float[] x) {
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		for (int i = 0; i < cols; i += 4 * L) {
			ShortVector s0 = ShortVector.fromArray(S128, w, base + i), s1 = ShortVector.fromArray(S128, w, base + i + 2 * L);
			a0 = ((IntVector) s0.convertShape(VectorOperators.S2I, IS, 0)).lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats().fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = ((IntVector) s0.convertShape(VectorOperators.S2I, IS, 1)).lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats().fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = ((IntVector) s1.convertShape(VectorOperators.S2I, IS, 0)).lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats().fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = ((IntVector) s1.convertShape(VectorOperators.S2I, IS, 1)).lanewise(VectorOperators.LSHL, 16).reinterpretAsFloats().fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		return a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
	}
	// 4. plain scalar loops, 4 accumulators (what the JIT's auto-vectorizer sees)
	static float rowBfScalar(short[] w, int base, int cols, float[] x) {
		float a0 = 0, a1 = 0, a2 = 0, a3 = 0;
		for (int i = 0; i < cols; i += 4) {
			a0 += Float.intBitsToFloat(w[base + i] << 16) * x[i];
			a1 += Float.intBitsToFloat(w[base + i + 1] << 16) * x[i + 1];
			a2 += Float.intBitsToFloat(w[base + i + 2] << 16) * x[i + 2];
			a3 += Float.intBitsToFloat(w[base + i + 3] << 16) * x[i + 3];
		}
		return (a0 + a1) + (a2 + a3);
	}
	static float rowF16Scalar(short[] w, int base, int cols, float[] x) {
		float a0 = 0, a1 = 0, a2 = 0, a3 = 0;
		for (int i = 0; i < cols; i += 4) {
			a0 += Float.float16ToFloat(w[base + i]) * x[i];
			a1 += Float.float16ToFloat(w[base + i + 1]) * x[i + 1];
			a2 += Float.float16ToFloat(w[base + i + 2]) * x[i + 2];
			a3 += Float.float16ToFloat(w[base + i + 3]) * x[i + 3];
		}
		return (a0 + a1) + (a2 + a3);
	}
	// 5. widen the row into an L1-resident f32 scratch with a plain loop (auto-vectorizable: no reduction), then the f32 lane kernel
	static float rowBfScratch(short[] w, int base, int cols, float[] x, float[] scratch) {
		for (int i = 0; i < cols; i++) scratch[i] = Float.intBitsToFloat(w[base + i] << 16);
		return rowF32(scratch, 0, cols, x);
	}
	static float rowF16Scratch(short[] w, int base, int cols, float[] x, float[] scratch) {
		for (int i = 0; i < cols; i++) scratch[i] = Float.float16ToFloat(w[base + i]);
		return rowF32(scratch, 0, cols, x);
	}
	// 6. f16 with the vector magic-multiply decode, via convert()
	static FloatVector f16Convert(short[] h, int off) {
		IntVector hv = (IntVector) ShortVector.fromArray(S64, h, off).convertShape(VectorOperators.S2I, IS, 0);
		IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
		return u.reinterpretAsFloats().mul(SCALE).reinterpretAsInts().add(INF_FIX, u.and(SE).compare(VectorOperators.EQ, SE))
				.or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats();
	}
	static float rowF16Vec(short[] w, int base, int cols, float[] x) {
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		for (int i = 0; i < cols; i += 4 * L) {
			a0 = f16Convert(w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = f16Convert(w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = f16Convert(w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = f16Convert(w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		return a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
	}

	interface Row { float run(int base, int cols); }
	static void gemv(int rows, int cols, float[] r, Row row) { for (int i = 0; i < rows; i++) r[i] = row.run(i * cols, cols); }
	static long t(Runnable r, int it) {
		for (int i = 0; i < 8; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 5; k++) { long s = System.nanoTime(); for (int i = 0; i < it; i++) r.run(); best = Math.min(best, (System.nanoTime() - s) / it); }
		return best;
	}
	public static void main(String[] a) {
		java.util.Random rn = new java.util.Random(11);
		for (int[] sz : new int[][] { { 1024, 1024 }, { 4096, 4096 } }) {
			int rows = sz[0], cols = sz[1], e = rows * cols;
			float[] wf = new float[e]; short[] wb = new short[e], wh = new short[e];
			for (int i = 0; i < e; i++) { wf[i] = (float) (rn.nextGaussian() * 0.02); wb[i] = toBf16(wf[i]); wh[i] = Float.floatToFloat16(wf[i]); }
			float[] x = new float[cols]; for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
			float[] r = new float[rows], scratch = new float[cols];
			int it = e > (1 << 22) ? 10 : 40;
			record V(String n, Row r) {}
			V[] vs = { new V("f32 lanes (baseline)", (b, c) -> rowF32(wf, b, c, x)),
					new V("bf16 S64 convertShape (spike)", (b, c) -> rowBf(wb, b, c, x, false)),
					new V("bf16 S128 convertShape parts", (b, c) -> rowBf128(wb, b, c, x)),
					new V("bf16 scalar loop 4 acc", (b, c) -> rowBfScalar(wb, b, c, x)),
					new V("bf16 scratch + f32 lanes", (b, c) -> rowBfScratch(wb, b, c, x, scratch)),
					new V("f16 vector magic-multiply", (b, c) -> rowF16Vec(wh, b, c, x)),
					new V("f16 scalar loop 4 acc", (b, c) -> rowF16Scalar(wh, b, c, x)),
					new V("f16 scratch + f32 lanes", (b, c) -> rowF16Scratch(wh, b, c, x, scratch)) };
			System.out.printf("%n=== %dx%d%n%-34s %9s %9s %8s%n", rows, cols, "variant", "ms", "Gelem/s", "vs f32");
			long base = 0;
			for (V v : vs) {
				long ns = t(() -> gemv(rows, cols, r, v.r()), it);
				if (base == 0) base = ns;
				System.out.printf("%-34s %9.3f %9.2f %7.2fx%n", v.n(), ns / 1e6, e / (double) ns, base / (double) ns);
			}
		}
	}
}
