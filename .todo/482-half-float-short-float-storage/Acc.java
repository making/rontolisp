import jdk.incubator.vector.*;

/** 4-accumulator rows (the todo-480 shape) for f32 and f16, so the width comparison is not chain-bound. */
public class Acc {
	static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final VectorSpecies<Short> SSH = VectorSpecies.of(short.class, VectorShape.forBitSize(IS.length() * 16));
	static final int SE = 0x7c00 << 13;
	static final FloatVector SCALE = FloatVector.broadcast(FS, 0x1.0p112f);
	static final IntVector INF_FIX = IntVector.broadcast(IS, 0x7f800000 - 0x47800000);
	static final int L = FS.length();

	static FloatVector dec(short[] h, int off) {
		IntVector hv = (IntVector) ShortVector.fromArray(SSH, h, off).convertShape(VectorOperators.S2I, IS, 0);
		IntVector u = hv.and(0x7fff).lanewise(VectorOperators.LSHL, 13);
		return u.reinterpretAsFloats().mul(SCALE).reinterpretAsInts()
				.add(INF_FIX, u.and(SE).compare(VectorOperators.EQ, SE))
				.or(hv.and(0x8000).lanewise(VectorOperators.LSHL, 16)).reinterpretAsFloats();
	}

	static void f32x4(float[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
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
			r[row] = s;
		}
	}

	static void f16x4(short[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
			int i = 0, b = cols - (cols % (4 * L));
			for (; i < b; i += 4 * L) {
				a0 = dec(w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
				a1 = dec(w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
				a2 = dec(w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
				a3 = dec(w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
			}
			float s = a0.add(a1).add(a2.add(a3)).reduceLanes(VectorOperators.ADD);
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
		java.util.Random rn = new java.util.Random(3);
		System.out.printf("%-14s %12s %12s %10s %10s%n", "shape", "f32x4 Gel/s", "f16x4 Gel/s", "f32 GB/s", "speedup");
		for (int[] sz : new int[][] { { 288, 288 }, { 1024, 1024 }, { 4096, 4096 }, { 8192, 8192 } }) {
			int rows = sz[0], cols = sz[1]; long e = (long) rows * cols;
			float[] wf = new float[(int) e]; short[] wh = new short[(int) e];
			for (int i = 0; i < e; i++) { float v = (float) (rn.nextGaussian() * 0.02); wf[i] = v; wh[i] = Float.floatToFloat16(v); }
			float[] x = new float[cols]; for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
			float[] r1 = new float[rows], r2 = new float[rows];
			int it = e > (1 << 22) ? 4 : 50;
			long t32 = t(() -> f32x4(wf, rows, cols, x, r1), it);
			long t16 = t(() -> f16x4(wh, rows, cols, x, r2), it);
			System.out.printf("%5dx%-8d %12.2f %12.2f %10.1f %9.2fx%n", rows, cols,
					e / (double) t32, e / (double) t16, e * 4 / 1e9 / (t32 / 1e9), t32 / (double) t16);
		}
	}
}
