// Round three: the CountedCompleter tree of round two, but with the caller's top-level
// splits handed to OUR pool (pool.execute) instead of fork()ed into the common pool,
// so a RONTOLISP_THREADS-sized pool gets the shape that won round two. Deeper splits
// happen on workers and fork() into the same pool. The caller computes the left-most
// leaf and then waits for the root -- parking (join) or spinning (isDone).
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import jdk.incubator.vector.*;

public class GemvPoolProbe3 {
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

	/** The caller's half of the tree: split, hand the right halves to POOL, compute the left-most leaf, wait. */
	static void ownTree(ForkJoinPool pool, boolean spin, float[] w, float[] x, float[] r, int d, int n, int grainRows) {
		Counted root = new Counted(null, w, x, r, 0, d, n, grainRows);
		int f = 0, t = d;
		while (t - f > grainRows) {
			int mid = (f + t) >>> 1;
			root.addToPendingCount(1);
			pool.execute(new Counted(root, w, x, r, mid, t, n, grainRows));
			t = mid;
		}
		rows(w, x, r, f, t, n);
		root.tryComplete();
		if (spin) {
			while (!root.isDone()) {
				Thread.onSpinWait();
			}
		}
		else {
			root.join();
		}
	}

	static double median(double[] a) {
		java.util.Arrays.sort(a);
		return a[a.length / 2];
	}

	public static void main(String[] args) throws Exception {
		int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
		ForkJoinPool pool = new ForkJoinPool(threads);
		ForkJoinPool poolM1 = new ForkJoinPool(Math.max(1, threads - 1));
		int[][] shapes = { { 288, 288 }, { 768, 288 }, { 288, 768 }, { 32000, 288 } };
		String[] what = { "wq/wk/wv/wo  288x288", "w1/w3        768x288", "w2           288x768", "classifier 32000x288" };
		System.out.printf("threads=%d, common pool parallelism=%d%n%n", threads, ForkJoinPool.getCommonPoolParallelism());
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
			System.out.printf("  %-26s %9.2f us%n", "serial", tser);
			float[] r = new float[d];
			for (int g : new int[] { 1 << 12, 1 << 13, 1 << 14 }) {
				int grainRows = Math.max(1, g / n);
				String gs = " 2^" + Integer.numberOfTrailingZeros(g);
				report("cc(common)" + gs, () -> new Counted(null, w, x, r, 0, d, n, grainRows).invoke(), reps, tser, ref, r);
				report("own(T) spin" + gs, () -> ownTree(pool, true, w, x, r, d, n, grainRows), reps, tser, ref, r);
				report("own(T) join" + gs, () -> ownTree(pool, false, w, x, r, d, n, grainRows), reps, tser, ref, r);
				report("own(T-1) spin" + gs, () -> ownTree(poolM1, true, w, x, r, d, n, grainRows), reps, tser, ref, r);
			}
			System.out.println();
		}
		pool.shutdown();
		poolM1.shutdown();
	}

	static void report(String name, Runnable body, int reps, double tser, float[] ref, float[] r) {
		java.util.Arrays.fill(r, Float.NaN);
		for (int i = 0; i < 200; i++) {
			body.run();
		}
		boolean same = java.util.Arrays.equals(ref, r);
		double t = time(body, reps);
		System.out.printf("  %-26s %9.2f us %6.2fx %s%n", name, t, tser / t, same ? "" : "  *** NOT IDENTICAL ***");
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
