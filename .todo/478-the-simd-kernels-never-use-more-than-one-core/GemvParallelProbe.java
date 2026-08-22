// Row-parallel GEMV against the serial lane kernel rontolisp emits today, at the
// shapes examples/llama2 (stories15M) asks for. Rows are independent chains, so the
// parallel result is BIT-IDENTICAL to the serial one -- the probe asserts that.
//
//   javac --add-modules jdk.incubator.vector GemvParallelProbe.java
//   java  --add-modules jdk.incubator.vector GemvParallelProbe
import java.util.concurrent.*;
import java.util.stream.IntStream;
import jdk.incubator.vector.*;

public class GemvParallelProbe {
	// what JvmSimdVectorTemplate.matvecF pins: one 128-bit chain per row.
	static final VectorSpecies<Float> S = FloatVector.SPECIES_128;

	static void rows(float[] w, float[] x, float[] r, int from, int to, int n) {
		for (int row = from; row < to; row++) {
			int base = row * n;
			FloatVector vacc = FloatVector.zero(S);
			int i = 0, bound = S.loopBound(n);
			for (; i < bound; i += S.length()) {
				vacc = vacc.add(FloatVector.fromArray(S, w, base + i).mul(FloatVector.fromArray(S, x, i)));
			}
			float acc = vacc.reduceLanes(VectorOperators.ADD);
			for (; i < n; i++) {
				acc += w[base + i] * x[i];
			}
			r[row] = acc;
		}
	}

	static void serial(float[] w, float[] x, float[] r, int d, int n) {
		rows(w, x, r, 0, d, n);
	}

	static void parallel(float[] w, float[] x, float[] r, int d, int n, ForkJoinPool pool, int threads) {
		int chunk = (d + threads - 1) / threads;
		pool.invoke(new java.util.concurrent.RecursiveAction() {
			protected void compute() {
				invokeAll(IntStream.range(0, threads)
					.mapToObj(t -> java.util.concurrent.ForkJoinTask.adapt(() -> {
						rows(w, x, r, t * chunk, Math.min(d, t * chunk + chunk), n);
					}))
					.toArray(java.util.concurrent.ForkJoinTask[]::new));
			}
		});
	}

	/** what the gist does: one parallel stream over rows on the COMMON pool. */
	static void parallelStream(float[] w, float[] x, float[] r, int d, int n) {
		IntStream.range(0, d).parallel().forEach(row -> rows(w, x, r, row, row + 1, n));
	}

	static double median(double[] a) {
		java.util.Arrays.sort(a);
		return a[a.length / 2];
	}

	public static void main(String[] args) throws Exception {
		int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
		ForkJoinPool pool = new ForkJoinPool(threads);
		int[][] shapes = { { 288, 288 }, { 768, 288 }, { 288, 768 }, { 32000, 288 } };
		String[] what = { "wq/wk/wv/wo  288x288", "w1/w3        768x288", "w2           288x768", "classifier 32000x288" };
		System.out.printf("threads=%d, lane kernel = SPECIES_128, one chain per row%n%n", threads);
		System.out.printf("common pool parallelism=%d%n%n", ForkJoinPool.getCommonPoolParallelism());
		System.out.printf("%-22s %10s %10s %8s %10s %8s %12s%n", "shape", "serial us", "chunk us", "speedup", "stream us", "speedup", "bit-identical");
		for (int s = 0; s < shapes.length; s++) {
			int d = shapes[s][0], n = shapes[s][1];
			float[] w = new float[d * n], x = new float[n];
			for (int i = 0; i < w.length; i++) {
				w[i] = (i % 97) * 0.01f - 0.5f;
			}
			for (int i = 0; i < n; i++) {
				x[i] = (i % 13) * 0.1f - 0.6f;
			}
			float[] r1 = new float[d], r2 = new float[d];
			for (int i = 0; i < 200; i++) {
				serial(w, x, r1, d, n);
				parallel(w, x, r2, d, n, pool, threads);
			}
			boolean same = java.util.Arrays.equals(r1, r2);
			int reps = Math.max(20, (int) (20_000_000L / ((long) d * n)));
			float[] r3 = new float[d];
			for (int i = 0; i < 200; i++) {
				parallelStream(w, x, r3, d, n);
			}
			same = same && java.util.Arrays.equals(r1, r3);
			double[] ts = new double[11], tp = new double[11], tq = new double[11];
			for (int k = 0; k < 11; k++) {
				long t0 = System.nanoTime();
				for (int i = 0; i < reps; i++) {
					serial(w, x, r1, d, n);
				}
				long t1 = System.nanoTime();
				for (int i = 0; i < reps; i++) {
					parallel(w, x, r2, d, n, pool, threads);
				}
				long t2 = System.nanoTime();
				for (int i = 0; i < reps; i++) {
					parallelStream(w, x, r3, d, n);
				}
				long t3 = System.nanoTime();
				ts[k] = (t1 - t0) / 1e3 / reps;
				tp[k] = (t2 - t1) / 1e3 / reps;
				tq[k] = (t3 - t2) / 1e3 / reps;
			}
			double a = median(ts), b = median(tp), c = median(tq);
			System.out.printf("%-22s %10.2f %10.2f %7.2fx %10.2f %7.2fx %12s%n", what[s], a, b, a / b, c, a / c, same);
		}
		pool.shutdown();
	}
}
