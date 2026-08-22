// Round four: the shape that won round three (own pool of threads-1 workers, the caller
// splits the row range, hands the right halves to the pool, computes the left-most leaf
// and SPINS for the rest) written WITHOUT a task class -- ForkJoinTask.adapt over a
// lambda plus an AtomicInteger of outstanding leaves -- because the compiled --simd
// bridge is a single embedded class and can carry lambdas but not nested classes.
// Measured against the CountedCompleter tree it replaces.
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.incubator.vector.*;

public class GemvPoolProbe4 {
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

	static void ccTree(ForkJoinPool pool, float[] w, float[] x, float[] r, int d, int n, int grainRows) {
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
		while (!root.isDone()) {
			Thread.onSpinWait();
		}
	}

	// --- the class-free shape ---
	static void sub(float[] w, float[] x, float[] r, int from, int to, int n, int grainRows, AtomicInteger pending) {
		int f = from, t = to;
		while (t - f > grainRows) {
			int mid = (f + t) >>> 1;
			int m = mid, tt = t;
			pending.incrementAndGet();
			ForkJoinTask.adapt(() -> sub(w, x, r, m, tt, n, grainRows, pending)).fork();
			t = mid;
		}
		rows(w, x, r, f, t, n);
		pending.decrementAndGet();
	}

	static void adaptTree(ForkJoinPool pool, float[] w, float[] x, float[] r, int d, int n, int grainRows) {
		AtomicInteger pending = new AtomicInteger();
		int f = 0, t = d;
		while (t - f > grainRows) {
			int mid = (f + t) >>> 1;
			int m = mid, tt = t;
			pending.incrementAndGet();
			pool.execute(ForkJoinTask.adapt(() -> sub(w, x, r, m, tt, n, grainRows, pending)));
			t = mid;
		}
		rows(w, x, r, f, t, n);
		while (pending.get() != 0) {
			Thread.onSpinWait();
		}
	}

	// the same, but flat: the caller cuts ALL leaves itself and executes each one
	static void adaptFlat(ForkJoinPool pool, float[] w, float[] x, float[] r, int d, int n, int grainRows) {
		AtomicInteger pending = new AtomicInteger();
		int leaves = (d + grainRows - 1) / grainRows;
		for (int l = 1; l < leaves; l++) {
			int f = l * grainRows, t = Math.min(d, f + grainRows);
			pending.incrementAndGet();
			pool.execute(ForkJoinTask.adapt(() -> { rows(w, x, r, f, t, n); pending.decrementAndGet(); }));
		}
		rows(w, x, r, 0, Math.min(d, grainRows), n);
		while (pending.get() != 0) {
			Thread.onSpinWait();
		}
	}

	static double median(double[] a) {
		java.util.Arrays.sort(a);
		return a[a.length / 2];
	}

	public static void main(String[] args) throws Exception {
		int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
		ForkJoinPool pool = new ForkJoinPool(Math.max(1, threads - 1));
		int[][] shapes = { { 288, 288 }, { 768, 288 }, { 288, 768 }, { 32000, 288 } };
		String[] what = { "wq/wk/wv/wo  288x288", "w1/w3        768x288", "w2           288x768", "classifier 32000x288" };
		System.out.printf("threads=%d (pool = threads-1 workers + the caller)%n%n", threads);
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
			for (int g : new int[] { 1 << 12, 1 << 13, 1 << 14 }) {
				int grainRows = Math.max(1, g / n);
				String gs = " 2^" + Integer.numberOfTrailingZeros(g);
				report("cc-tree" + gs, () -> ccTree(pool, w, x, r, d, n, grainRows), reps, tser, ref, r);
				report("adapt-tree" + gs, () -> adaptTree(pool, w, x, r, d, n, grainRows), reps, tser, ref, r);
				report("adapt-flat" + gs, () -> adaptFlat(pool, w, x, r, d, n, grainRows), reps, tser, ref, r);
			}
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
