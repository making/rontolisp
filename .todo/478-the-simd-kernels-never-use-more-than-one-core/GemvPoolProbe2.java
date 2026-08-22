// Round two of GemvPoolProbe: the first round said `pool.invoke(task)` from an outside
// thread loses to the gist's parallel stream at every shape, and the reason is WHO
// splits. A stream's ForEachTask is invoke()d on the CALLING thread: the caller forks
// the right halves into the common pool's submission queue as it splits and computes
// the left-most leaf itself, so every core gets work at once and the caller never
// idles. `pool.invoke` hands ONE task to ONE worker, which then splits it alone while
// the caller parks. The shapes here keep the caller working and make the chunks
// visible at once:
//
//   stream(common)   the reference: IntStream.range(0, d).parallel() on the common pool
//   cc(common)       a CountedCompleter tree invoke()d on the caller, forks to the
//                    common pool (the stream's shape with our own work grain)
//   chunks(own)      k equal row chunks, k = pool threads: the caller submits chunks
//                    1..k-1 to OUR pool up front, computes chunk 0, then joins
//   chunks-spin(own) the same, but the final wait spins on isDone() instead of parking
//   fine(own)        4k chunks submitted up front, caller computes chunk 0, then joins
//   fine-spin(own)   the same with a spinning wait
//   stream(own)      the stream run INSIDE our pool (pool.submit(() -> stream).join())
//
//   javac --add-modules jdk.incubator.vector GemvPoolProbe2.java
//   java  --add-modules jdk.incubator.vector [-Dthreads=N] GemvPoolProbe2
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.IntStream;
import jdk.incubator.vector.*;

public class GemvPoolProbe2 {
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

	static final class Counted extends CountedCompleter<Void> {
		final float[] w, x, r; final int from, to, n, grainRows;
		Counted(Counted parent, float[] w, float[] x, float[] r, int from, int to, int n, int grainRows) {
			super(parent);
			this.w = w; this.x = x; this.r = r; this.from = from; this.to = to; this.n = n; this.grainRows = grainRows;
		}
		public void compute() {
			int f = from, t = to;
			while (t - f > grainRows) {
				int mid = (f + t) >>> 1;
				addToPendingCount(1);
				new Counted(this, w, x, r, mid, t, n, grainRows).fork();
				t = mid;
			}
			rows(w, x, r, f, t, n);
			tryComplete();
		}
	}

	static void chunks(ForkJoinPool pool, int k, boolean spin, float[] w, float[] x, float[] r, int d, int n) {
		int chunk = (d + k - 1) / k;
		if (chunk >= d) {
			rows(w, x, r, 0, d, n);
			return;
		}
		ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[k];
		int count = 0;
		for (int c = 1; c < k; c++) {
			int from = c * chunk, to = Math.min(d, from + chunk);
			if (from >= to) {
				break;
			}
			tasks[count++] = pool.submit(() -> rows(w, x, r, from, to, n));
		}
		rows(w, x, r, 0, chunk, n);
		for (int c = 0; c < count; c++) {
			if (spin) {
				while (!tasks[c].isDone()) {
					Thread.onSpinWait();
				}
			}
			else {
				tasks[c].join();
			}
		}
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
		System.out.printf("threads=%d (own pool parallelism), common pool parallelism=%d%n%n", threads,
				ForkJoinPool.getCommonPoolParallelism());
		for (int s = 0; s < shapes.length; s++) {
			int d = shapes[s][0], n = shapes[s][1];
			float[] w = new float[d * n], x = new float[n];
			for (int i = 0; i < w.length; i++) {
				w[i] = (i % 97) * 0.01f - 0.5f;
			}
			for (int i = 0; i < n; i++) {
				x[i] = (i % 13) * 0.1f - 0.6f;
			}
			float[] ref = new float[d];
			rows(w, x, ref, 0, d, n);
			int reps = Math.max(20, (int) (20_000_000L / ((long) d * n)));
			System.out.printf("%s  (reps %d)%n", what[s], reps);
			double tser = time(() -> rows(w, x, ref, 0, d, n), reps);
			System.out.printf("  %-22s %9.2f us%n", "serial", tser);
			float[] r = new float[d];
			report("stream(common)", () -> IntStream.range(0, d).parallel().forEach(row -> rows(w, x, r, row, row + 1, n)),
					reps, tser, ref, r);
			for (int g : new int[] { 1 << 13, 1 << 15 }) {
				int grainRows = Math.max(1, g / n);
				report("cc(common) 2^" + Integer.numberOfTrailingZeros(g),
						() -> new Counted(null, w, x, r, 0, d, n, grainRows).invoke(), reps, tser, ref, r);
			}
			report("chunks(own)", () -> chunks(pool, threads, false, w, x, r, d, n), reps, tser, ref, r);
			report("chunks-spin(own)", () -> chunks(pool, threads, true, w, x, r, d, n), reps, tser, ref, r);
			report("fine(own)", () -> chunks(pool, 4 * threads, false, w, x, r, d, n), reps, tser, ref, r);
			report("fine-spin(own)", () -> chunks(pool, 4 * threads, true, w, x, r, d, n), reps, tser, ref, r);
			report("stream(own)", () -> pool
				.submit(() -> IntStream.range(0, d).parallel().forEach(row -> rows(w, x, r, row, row + 1, n)))
				.join(), reps, tser, ref, r);
			System.out.println();
		}
		pool.shutdown();
	}

	static void report(String name, Runnable body, int reps, double tser, float[] ref, float[] r) {
		java.util.Arrays.fill(r, Float.NaN);
		for (int i = 0; i < 200; i++) {
			body.run();
		}
		boolean same = java.util.Arrays.equals(ref, r);
		double t = time(body, reps);
		System.out.printf("  %-22s %9.2f us %6.2fx %s%n", name, t, tser / t, same ? "" : "  *** NOT IDENTICAL ***");
	}

	static double time(Runnable body, int reps) {
		double[] ts = new double[11];
		for (int k = 0; k < 11; k++) {
			long t0 = System.nanoTime();
			for (int i = 0; i < reps; i++) {
				body.run();
			}
			ts[k] = (System.nanoTime() - t0) / 1e3 / reps;
		}
		return median(ts);
	}
}
