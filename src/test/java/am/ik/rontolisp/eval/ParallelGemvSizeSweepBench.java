package am.ik.rontolisp.eval;

import java.util.Random;

/**
 * A single measurement: whether the parallel f32 GEMV's ~41-42 Gelem/s plateau
 * ({@code .kb/simd-parallel.md}) is memory bandwidth or the parallel machinery itself. A
 * bandwidth ceiling has no reason to bind a 0.26 MB matrix and a 67 MB one identically,
 * so this sweeps square shapes from a size unambiguously cache-resident (256x256) up past
 * the shape that plateaus, and reports the parallel arm's Gelem/s at every point in
 * between -- no bf16, no model, so nothing here can be misread as a comparison of widths.
 *
 * <p>
 * Only the shipped f32 kernel ({@link VecSimdKernels#matvecIntoF}) is timed, serial and
 * parallel, at each shape; the interpretation is entirely in the shape of the resulting
 * curve, which is why this is a {@code main} beside {@code Bf16GemvBench} rather than an
 * addition to it.
 *
 * <pre>{@code
 * ./mvnw -o test-compile
 * CP=target/classes:target/test-classes
 * java --add-modules jdk.incubator.vector -cp $CP am.ik.rontolisp.eval.ParallelGemvSizeSweepBench
 * }</pre>
 *
 * Results and conditions (base commit, JIT, machine, load average, thread count) are
 * recorded in
 * {@code .todo/artefacts/702-the-parallel-cap-is-the-machinery-or-memory-one-run-decides/README.md},
 * not here -- this file does not change with them.
 */
public final class ParallelGemvSizeSweepBench {

	private ParallelGemvSizeSweepBench() {
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

	public static void main(String[] args) {
		System.out.printf("jit=%s java=%s threads=%s%n", jit(), System.getProperty("java.version"),
				System.getenv().getOrDefault("RONTOLISP_THREADS", "default"));
		int[] sizes = { 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096 };
		System.out.printf("%n%-12s %10s %10s %10s %10s %10s %8s%n", "shape", "MB(f32)", "serial ms", "par ms",
				"serial G", "par Gelem", "speedup");
		for (int n : sizes) {
			int rows = n;
			int cols = n;
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
			// Small shapes need more calls per round to get a stable nanoTime reading;
			// large ones cost more per call, so fewer are needed.
			int iterations = elements <= (1 << 18) ? 500
					: elements <= (1 << 20) ? 200 : elements <= (1 << 22) ? 50 : 10;

			long serialNs = time(() -> VecSimdKernels.matvecIntoF(r, w, rows, cols, x, false), iterations);
			long parNs = time(() -> VecSimdKernels.matvecIntoF(r, w, rows, cols, x, true), iterations);

			System.out.printf("%-12s %10.2f %10.4f %10.4f %10.2f %10.2f %7.2fx%n", rows + "x" + cols,
					elements * 4 / 1e6, serialNs / 1e6, parNs / 1e6, elements / (double) serialNs,
					elements / (double) parNs, serialNs / (double) parNs);
		}
	}

}
