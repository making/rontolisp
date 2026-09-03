import jdk.incubator.vector.*;

/**
 * Where the multi-accumulator GEMV row starts to pay: a column sweep at a fixed total
 * work, matrix hot in cache.
 *
 * `Acc.java` found 4 accumulators worth 1.5-2.6x at 288 columns and wider -- and a
 * REGRESSION at 48 columns (0.89x under Graal, 0.48x under C2), which is llama2's
 * attention-score shape (256x48) and a shape the row threshold already lets into the lane
 * path. Four accumulators cost four zero vectors and a three-add tree per row; on a short
 * row that setup is most of the row. So the kernel needs a second gate, on the COLUMN
 * count, and this finds it.
 *
 *   java --add-modules jdk.incubator.vector Sweep.java
 *   java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector Sweep.java
 */
public class Sweep {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_128;
	static final int L = 4;

	static float sumLanes(FloatVector v) {
		float s = 0.0f;
		for (int i = 0; i < v.length(); i++) s += v.lane(i);
		return s;
	}

	static float row1(float[] w, int base, int cols, float[] x) {
		int i = 0;
		FloatVector vacc = FloatVector.zero(FS);
		int bound = FS.loopBound(cols);
		for (; i < bound; i += L) vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		float acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	static float row2(float[] w, int base, int cols, float[] x) {
		int i = 0;
		FloatVector a0 = FloatVector.zero(FS), a1 = a0;
		int bound = (cols / (2 * L)) * (2 * L);
		for (; i < bound; i += 2 * L) {
			a0 = a0.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			a1 = a1.add(FloatVector.fromArray(FS, w, base + i + L).mul(FloatVector.fromArray(FS, x, i + L)));
		}
		FloatVector vacc = a0.add(a1);
		int b4 = FS.loopBound(cols);
		for (; i < b4; i += L) vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		float acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	static float row4(float[] w, int base, int cols, float[] x) {
		int i = 0;
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		int bound = (cols / (4 * L)) * (4 * L);
		for (; i < bound; i += 4 * L) {
			a0 = a0.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			a1 = a1.add(FloatVector.fromArray(FS, w, base + i + L).mul(FloatVector.fromArray(FS, x, i + L)));
			a2 = a2.add(FloatVector.fromArray(FS, w, base + i + 2 * L).mul(FloatVector.fromArray(FS, x, i + 2 * L)));
			a3 = a3.add(FloatVector.fromArray(FS, w, base + i + 3 * L).mul(FloatVector.fromArray(FS, x, i + 3 * L)));
		}
		FloatVector vacc = a0.add(a1).add(a2.add(a3));
		int b4 = FS.loopBound(cols);
		for (; i < b4; i += L) vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		float acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	interface Row { float run(float[] w, int base, int cols, float[] x); }

	static void gemv(float[] r, float[] w, int rows, int cols, float[] x, Row row) {
		for (int i = 0; i < rows; i++) r[i] = row.run(w, i * cols, cols, x);
	}

	static long time(Runnable r, int it) {
		for (int i = 0; i < 10; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 9; k++) {
			long s = System.nanoTime();
			for (int i = 0; i < it; i++) r.run();
			best = Math.min(best, (System.nanoTime() - s) / it);
		}
		return best;
	}

	static String jit() {
		try {
			return "true".equals(java.lang.management.ManagementFactory
					.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
					.getVMOption("UseJVMCICompiler").getValue()) ? "graal" : "c2";
		} catch (RuntimeException e) { return "unknown"; }
	}

	public static void main(String[] a) {
		System.out.printf("jit=%s  (hot matrix, ~1M elements of work per call)%n%n", jit());
		System.out.printf("%6s %6s %10s %10s %8s %10s %8s%n", "cols", "rows", "1acc us", "2acc us", "2 vs 1", "4acc us", "4 vs 1");
		java.util.Random rn = new java.util.Random(11);
		for (int cols : new int[] { 16, 20, 24, 32, 40, 48, 64, 80, 96, 128, 160, 192, 256, 288, 512 }) {
			int rows = Math.max(2, (1 << 20) / cols);
			int e = rows * cols;
			float[] w = new float[e];
			for (int i = 0; i < e; i++) w[i] = (float) (rn.nextGaussian() * 0.02);
			float[] x = new float[cols];
			for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
			float[] r = new float[rows];
			long n1 = time(() -> gemv(r, w, rows, cols, x, Sweep::row1), 40);
			long n2 = time(() -> gemv(r, w, rows, cols, x, Sweep::row2), 40);
			long n4 = time(() -> gemv(r, w, rows, cols, x, Sweep::row4), 40);
			System.out.printf("%6d %6d %10.2f %10.2f %7.2fx %10.2f %7.2fx%n", cols, rows, n1 / 1e3, n2 / 1e3,
					n1 / (double) n2, n4 / 1e3, n1 / (double) n4);
		}
	}
}
