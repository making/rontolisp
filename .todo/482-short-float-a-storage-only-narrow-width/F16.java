import jdk.incubator.vector.*;
import static jdk.incubator.vector.Float16.*;

/** Does JEP 508's jdk.incubator.vector.Float16 auto-vectorize a GEMV on this host? */
public class F16 {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
	static final int L = FS.length();

	// f32 reference, 4 accumulators + FMA (the todo-480 shape)
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

	// JEP 508 Float16: f16 weights AND f16 activations, f16 accumulator -- the shape the
	// JEP says is auto-vectorized. 4 accumulators so a chain does not hide the win.
	static void f16Jep(short[] w, int rows, int cols, short[] x, short[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			Float16 a0 = valueOf(0), a1 = a0, a2 = a0, a3 = a0;
			int i = 0, b = cols - (cols % 4);
			for (; i < b; i += 4) {
				a0 = fma(shortBitsToFloat16(w[base + i]), shortBitsToFloat16(x[i]), a0);
				a1 = fma(shortBitsToFloat16(w[base + i + 1]), shortBitsToFloat16(x[i + 1]), a1);
				a2 = fma(shortBitsToFloat16(w[base + i + 2]), shortBitsToFloat16(x[i + 2]), a2);
				a3 = fma(shortBitsToFloat16(w[base + i + 3]), shortBitsToFloat16(x[i + 3]), a3);
			}
			Float16 s = add(add(a0, a1), add(a2, a3));
			for (; i < cols; i++) s = fma(shortBitsToFloat16(w[base + i]), shortBitsToFloat16(x[i]), s);
			r[row] = float16ToRawShortBits(s);
		}
	}

	// Float16 weights widened to float, f32 accumulate (mixed precision -- the useful shape)
	static void f16Mixed(short[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			float a0 = 0, a1 = 0, a2 = 0, a3 = 0;
			int i = 0, b = cols - (cols % 4);
			for (; i < b; i += 4) {
				a0 = Math.fma(shortBitsToFloat16(w[base + i]).floatValue(), x[i], a0);
				a1 = Math.fma(shortBitsToFloat16(w[base + i + 1]).floatValue(), x[i + 1], a1);
				a2 = Math.fma(shortBitsToFloat16(w[base + i + 2]).floatValue(), x[i + 2], a2);
				a3 = Math.fma(shortBitsToFloat16(w[base + i + 3]).floatValue(), x[i + 3], a3);
			}
			float s = (a0 + a1) + (a2 + a3);
			for (; i < cols; i++) s += shortBitsToFloat16(w[base + i]).floatValue() * x[i];
			r[row] = s;
		}
	}

	// The same, but through the plain Float.float16ToFloat intrinsic (no Float16 object)
	static void f16Intrinsic(short[] w, int rows, int cols, float[] x, float[] r) {
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			float a0 = 0, a1 = 0, a2 = 0, a3 = 0;
			int i = 0, b = cols - (cols % 4);
			for (; i < b; i += 4) {
				a0 = Math.fma(Float.float16ToFloat(w[base + i]), x[i], a0);
				a1 = Math.fma(Float.float16ToFloat(w[base + i + 1]), x[i + 1], a1);
				a2 = Math.fma(Float.float16ToFloat(w[base + i + 2]), x[i + 2], a2);
				a3 = Math.fma(Float.float16ToFloat(w[base + i + 3]), x[i + 3], a3);
			}
			float s = (a0 + a1) + (a2 + a3);
			for (; i < cols; i++) s += Float.float16ToFloat(w[base + i]) * x[i];
			r[row] = s;
		}
	}

	static long t(Runnable r, int it) {
		for (int i = 0; i < 10; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 7; k++) { long s = System.nanoTime(); for (int i = 0; i < it; i++) r.run(); best = Math.min(best, (System.nanoTime() - s) / it); }
		return best;
	}

	public static void main(String[] a) {
		System.out.println("Float16 is " + Float16.class.getModule().getName() + "/" + Float16.class.getName());
		System.out.printf("%-12s %11s %11s %11s %11s%n", "shape", "f32x4", "F16 f16acc", "F16 mixed", "intrinsic");
		System.out.printf("%-12s %11s %11s %11s %11s%n", "", "Gelem/s", "Gelem/s", "Gelem/s", "Gelem/s");
		java.util.Random rn = new java.util.Random(11);
		for (int[] sz : new int[][] { { 288, 288 }, { 1024, 1024 }, { 4096, 4096 } }) {
			int rows = sz[0], cols = sz[1]; long e = (long) rows * cols;
			float[] wf = new float[(int) e]; short[] wh = new short[(int) e];
			for (int i = 0; i < e; i++) { float v = (float) (rn.nextGaussian() * 0.02); wf[i] = v; wh[i] = Float.floatToFloat16(v); }
			float[] xf = new float[cols]; short[] xh = new short[cols];
			for (int i = 0; i < cols; i++) { float v = (float) rn.nextGaussian(); xf[i] = v; xh[i] = Float.floatToFloat16(v); }
			float[] r1 = new float[rows], r3 = new float[rows], r4 = new float[rows];
			short[] r2 = new short[rows];
			int it = e > (1 << 22) ? 4 : 30;
			long t1 = t(() -> f32x4(wf, rows, cols, xf, r1), it);
			long t2 = t(() -> f16Jep(wh, rows, cols, xh, r2), it);
			long t3 = t(() -> f16Mixed(wh, rows, cols, xf, r3), it);
			long t4 = t(() -> f16Intrinsic(wh, rows, cols, xf, r4), it);
			System.out.printf("%5dx%-6d %11.2f %11.2f %11.2f %11.2f%n", rows, cols,
					e / (double) t1, e / (double) t2, e / (double) t3, e / (double) t4);
		}
	}
}
