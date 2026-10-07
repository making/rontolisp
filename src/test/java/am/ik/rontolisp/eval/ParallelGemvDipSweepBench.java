package am.ik.rontolisp.eval;

import java.util.Random;

/**
 * The follow-up to the {@code ParallelGemvSizeSweepBench} size sweep: that sweep found a
 * reproducible dip to 25-28 Gelem/s at 3072x3072, well below both 2048x2048 (~54) and
 * 4096x4096 (~42), and left the cause unestablished. This bench separates the two live
 * hypotheses from {@code SimdParallel.rows}'s grain formula
 * ({@code max(GRAIN/workPerRow, rows/(LEAVES_PER_THREAD*threads))}):
 *
 * <ul>
 * <li>Section A -- a fine square sweep (every 128 columns, 2048x2048 to 4096x4096) to see
 * how wide the dip is and whether it is centered exactly on 3072.</li>
 * <li>Section B -- rows fixed at 3072, columns varied, so the row count is held constant
 * while both {@code workPerRow} and the total byte size change. If the dip is a ROW COUNT
 * (leaf quantization) effect it should persist across this section regardless of column
 * count.</li>
 * <li>Section C -- columns fixed at 3072 (so the row-major stride between rows, and
 * {@code workPerRow}, are held at the 3072x3072 shape's value), rows varied. If the dip
 * is a STRIDE effect (cache/TLB associativity keyed on the 3072*4 = 12288-byte row
 * stride, which -- unlike 2048*4 = 8192 and 4096*4 = 16384 -- is not a power of two) it
 * should persist here regardless of row count.</li>
 * <li>Section D -- total element count fixed at 3072*3072 = 9 437 184 (the same 37.75 MB
 * of matrix, so DRAM/cache traffic per call is identical), shape varied. If the dip is a
 * BYTE SIZE effect it should persist across every shape in this section; if it is a shape
 * (row count or stride) effect it should appear only at the square shape.</li>
 * </ul>
 *
 * <pre>{@code
 * ./mvnw -o spring-javaformat:apply test-compile
 * CP=target/classes:target/test-classes
 * java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.ParallelGemvDipSweepBench
 * }</pre>
 *
 * Results and conditions are recorded in {@code .todo/artefacts/713-.../README.md}, not
 * here -- this file does not change with them.
 */
public final class ParallelGemvDipSweepBench {

	private ParallelGemvDipSweepBench() {
	}

	private static String jit() {
		try {
			return "true".equals(java.lang.management.ManagementFactory
				.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
				.getVMOption("UseJVMCICompiler")
				.getValue()) ? "graal" : "c2";
		}
		catch (RuntimeException ex) {
			return "unknown";
		}
	}

	@FunctionalInterface
	private interface Variant {

		void run();

	}

	/**
	 * Best of five rounds of {@code iterations} calls each, after eight warm-up calls.
	 * @return nanoseconds per call
	 */
	private static long time(Variant v, int iterations) {
		for (int i = 0; i < 8; i++) {
			v.run();
		}
		long best = Long.MAX_VALUE;
		for (int round = 0; round < 5; round++) {
			long start = System.nanoTime();
			for (int i = 0; i < iterations; i++) {
				v.run();
			}
			best = Math.min(best, (System.nanoTime() - start) / iterations);
		}
		return best;
	}

	private static int iterationsFor(long elements) {
		return elements <= (1 << 18) ? 500 : elements <= (1 << 20) ? 200 : elements <= (1 << 22) ? 50 : 10;
	}

	private static void row(int rows, int cols) {
		long elements = (long) rows * cols;
		Random random = new Random(11);
		float[] w = new float[(int) elements];
		for (int i = 0; i < elements; i++) {
			w[i] = (float) (random.nextGaussian() * 0.02);
		}
		float[] x = new float[cols];
		for (int i = 0; i < cols; i++) {
			x[i] = (float) random.nextGaussian();
		}
		float[] r = new float[rows];
		int iterations = iterationsFor(elements);

		long serialNs = time(() -> VecSimdKernels.matvecIntoF(r, w, rows, cols, x, false), iterations);
		long parNs = time(() -> VecSimdKernels.matvecIntoF(r, w, rows, cols, x, true), iterations);

		System.out.printf("%-14s %10.2f %10.4f %10.4f %10.2f %10.2f %7.2fx%n", rows + "x" + cols, elements * 4 / 1e6,
				serialNs / 1e6, parNs / 1e6, elements / (double) serialNs, elements / (double) parNs,
				serialNs / (double) parNs);
	}

	private static void header(String title) {
		System.out.printf("%n== %s ==%n", title);
		System.out.printf("%-14s %10s %10s %10s %10s %10s %8s%n", "shape", "MB(f32)", "serial ms", "par ms", "serial G",
				"par Gelem", "speedup");
	}

	public static void main(String[] args) {
		System.out.printf("jit=%s java=%s threads=%s%n", jit(), System.getProperty("java.version"),
				System.getenv().getOrDefault("RONTOLISP_THREADS", "default"));

		header("A: fine square sweep, 2048x2048 to 4096x4096 step 128 (row-count granularity)");
		for (int n = 2048; n <= 4096; n += 128) {
			row(n, n);
		}

		header("B: rows fixed at 3072, cols varied (row-count hypothesis)");
		for (int cols : new int[] { 1024, 1536, 2048, 2560, 3072, 3584, 4096, 6144 }) {
			row(3072, cols);
		}

		header("C: cols fixed at 3072, rows varied (row-stride/workPerRow hypothesis)");
		for (int rows : new int[] { 1024, 1536, 2048, 2560, 3072, 3584, 4096, 6144 }) {
			row(rows, 3072);
		}

		header("D: elements fixed at 3072x3072 = 9 437 184, shape varied (byte-size hypothesis)");
		int[][] shapes = { { 3072, 3072 }, { 1536, 6144 }, { 6144, 1536 }, { 2304, 4096 }, { 4096, 2304 },
				{ 1024, 9216 }, { 9216, 1024 }, { 2048, 4608 }, { 4608, 2048 } };
		for (int[] shape : shapes) {
			row(shape[0], shape[1]);
		}
	}

}
