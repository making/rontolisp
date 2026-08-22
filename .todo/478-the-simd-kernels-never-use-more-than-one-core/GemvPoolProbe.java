// Which pool shape to build into the --simd bridge: the row-parallel GEMV of
// GemvParallelProbe, at llama2's four shapes, over the candidate designs --
//
//   stream   IntStream.range(0, d).parallel() on the COMMON pool (what the gist does;
//            the per-leaf work is whatever the stream splitter decides)
//   bisect   a RecursiveAction that halves the row range down to a WORK grain
//            (rows * cols >= GRAIN multiply-adds per leaf), pool.invoke from the caller
//   counted  the same split as a CountedCompleter tree (no join chain)
//   helps    bisect, but the caller computes the first 1/threads of the rows itself and
//            joins the rest afterwards (it never idles while the pool works)
//
// each on a dedicated ForkJoinPool of RONTOLISP_THREADS-like parallelism. Every
// variant's result is asserted bit-identical to the serial kernel.
//
//   javac --add-modules jdk.incubator.vector GemvPoolProbe.java
//   java  --add-modules jdk.incubator.vector [-Dthreads=N] GemvPoolProbe
import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.stream.IntStream;
import jdk.incubator.vector.*;

public class GemvPoolProbe {
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

	static final class Bisect extends RecursiveAction {
		final float[] w, x, r; final int from, to, n, grainRows;
		Bisect(float[] w, float[] x, float[] r, int from, int to, int n, int grainRows) {
			this.w = w; this.x = x; this.r = r; this.from = from; this.to = to; this.n = n; this.grainRows = grainRows;
		}
		protected void compute() {
			if (to - from <= grainRows) {
				rows(w, x, r, from, to, n);
				return;
			}
			int mid = (from + to) >>> 1;
			invokeAll(new Bisect(w, x, r, from, mid, n, grainRows), new Bisect(w, x, r, mid, to, n, grainRows));
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

	static double median(double[] a) {
		java.util.Arrays.sort(a);
		return a[a.length / 2];
	}

	public static void main(String[] args) throws Exception {
		int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
		ForkJoinPool pool = new ForkJoinPool(threads);
		int[] grains = { 1 << 13, 1 << 14, 1 << 15, 1 << 16 };
		int[][] shapes = { { 288, 288 }, { 768, 288 }, { 288, 768 }, { 32000, 288 } };
		String[] what = { "wq/wk/wv/wo  288x288", "w1/w3        768x288", "w2           288x768", "classifier 32000x288" };
		System.out.printf("threads=%d (pool parallelism), common pool parallelism=%d%n%n", threads,
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
			// serial
			Runnable serial = () -> rows(w, x, ref, 0, d, n);
			double tser = time(serial, reps);
			System.out.printf("  %-28s %9.2f us%n", "serial", tser);
			float[] r = new float[d];
			Runnable stream = () -> IntStream.range(0, d).parallel().forEach(row -> rows(w, x, r, row, row + 1, n));
			report("stream(common)", stream, reps, tser, ref, r);
			for (int g : grains) {
				int grainRows = Math.max(1, g / n);
				Runnable bisect = () -> pool.invoke(new Bisect(w, x, r, 0, d, n, grainRows));
				report("bisect grain=2^" + Integer.numberOfTrailingZeros(g), bisect, reps, tser, ref, r);
				Runnable counted = () -> pool.invoke(new Counted(null, w, x, r, 0, d, n, grainRows));
				report("counted grain=2^" + Integer.numberOfTrailingZeros(g), counted, reps, tser, ref, r);
				Runnable helps = () -> {
					int mine = Math.max(grainRows, d / threads);
					if (mine >= d) {
						rows(w, x, r, 0, d, n);
						return;
					}
					ForkJoinTask<?> rest = pool.submit(new Bisect(w, x, r, mine, d, n, grainRows));
					rows(w, x, r, 0, mine, n);
					rest.join();
				};
				report("helps grain=2^" + Integer.numberOfTrailingZeros(g), helps, reps, tser, ref, r);
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
		System.out.printf("  %-28s %9.2f us %6.2fx %s%n", name, t, tser / t, same ? "" : "  *** NOT IDENTICAL ***");
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
