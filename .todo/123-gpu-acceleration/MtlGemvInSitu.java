import am.ik.gpu.Gpu;

import java.util.Arrays;

/**
 * The SHIPPED Metal GEMV route (`Gpu.matvec` over `am.ik.gpu`'s own classes, residency
 * and pool included), as a program calls it -- the question MtlMatvecCrossover's best-of
 * columns cannot answer. Two things: the per-call distribution back to back (min / median /
 * mean / p90, three rounds, the first shape in a process paying the clock ramp), and the
 * cost of the same resident call after a CPU GAP of 0 us to 10 ms -- which is what a decode
 * loop does, one GEMV per token with the attention and the sampler between them.
 *
 * <p>
 * Run from the repository root against the built classes:
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp target/classes .todo/123-gpu-acceleration/MtlGemvInSitu.java
 * </pre>
 */
public class MtlGemvInSitu {

	public static void main(String[] args) throws Exception {
		System.out.println("device: " + Gpu.description());
		int[][] shapes = { { 32000, 288 }, { 4096, 4096 }, { 1536, 1536 }, { 2048, 2048 }, { 1536, 1536 },
				{ 32000, 288 } };
		System.out.println("-- the shipped route back to back, us per call (the first shape pays the clock ramp) --");
		for (int[] s : shapes) {
			int rows = s[0], cols = s[1];
			float[] w = new float[rows * cols], x = new float[cols], y = new float[rows];
			for (int i = 0; i < w.length; i++) {
				w[i] = (float) Math.sin(i * 0.37);
			}
			for (int i = 0; i < cols; i++) {
				x[i] = (float) Math.cos(i * 0.11);
			}
			Gpu.matvec(w, 0, x, 0, y, 0, rows, cols); // the first sight declines
			if (!Gpu.matvec(w, 0, x, 0, y, 0, rows, cols)) {
				System.out.println(rows + "x" + cols + " declined");
				continue;
			}
			for (int i = 0; i < 100; i++) {
				Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
			}
			int reps = 300;
			double[] t = new double[reps];
			for (int round = 0; round < 3; round++) {
				for (int i = 0; i < reps; i++) {
					long t0 = System.nanoTime();
					Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
					t[i] = (System.nanoTime() - t0) / 1e3;
				}
				double[] sorted = t.clone();
				Arrays.sort(sorted);
				System.out.printf("    %-10s round %d: min %6.1f  median %6.1f  mean %6.1f  p90 %6.1f%n",
						rows + "x" + cols, round, sorted[0], sorted[reps / 2], Arrays.stream(t).sum() / reps,
						sorted[reps * 9 / 10]);
			}
		}
		System.out.println("-- the same resident call after a CPU gap, mean us per call --");
		int[][] gapped = { { 1536, 1536 }, { 32000, 288 } };
		for (int[] s : gapped) {
			int rows = s[0], cols = s[1];
			float[] w = new float[rows * cols], x = new float[cols], y = new float[rows];
			for (int i = 0; i < w.length; i++) {
				w[i] = (float) Math.sin(i * 0.37);
			}
			Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
			for (int i = 0; i < 100; i++) {
				Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
			}
			for (long gapUs : new long[] { 0, 100, 500, 1000, 2500, 5000, 10000 }) {
				double sink = 0;
				int reps = gapUs >= 5000 ? 60 : 150;
				long total = 0;
				for (int i = 0; i < reps; i++) {
					long g = System.nanoTime();
					while (System.nanoTime() - g < gapUs * 1000) {
						sink += Math.sin(sink);
					}
					long t0 = System.nanoTime();
					Gpu.matvec(w, 0, x, 0, y, 0, rows, cols);
					total += System.nanoTime() - t0;
				}
				System.out.printf("    %-10s gap %5d us -> call %6.1f us%s%n", rows + "x" + cols, gapUs,
						total / (reps * 1e3), sink == 12345 ? "!" : "");
			}
		}
	}

}
