// Round six, after the in-situ llama2 number came in at 1.06x instead of the probes'
// 1.8-3.8x: the probes ran GEMVs back to back, so the pool's workers never parked; a
// decode loop runs ~100 us of boxed Lisp between GEMVs, and a ForkJoinPool worker parks
// within microseconds of going idle, so EVERY in-situ dispatch pays the unpark chain.
// This probe puts a busy gap of G us between GEMVs and measures the GEMV alone, for the
// shipped adapt-tree on a ForkJoinPool against a spin-then-park pool of plain threads
// (workers spin on an epoch for SPIN us after their last job before parking; the work is
// claimed in grain-sized leaves off one counter, by caller and workers alike).
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import jdk.incubator.vector.*;

public class GemvColdProbe {
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

	// --- the shipped shape: adapt-tree on a ForkJoinPool of threads-1 ---
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

	// --- spin-then-park pool with leaf claiming ---
	static final class SpinPool {
		final Thread[] workers;
		final long spinNanos;
		volatile long epoch;
		// the current job
		volatile float[] w, x, r;
		volatile int d, n, grainRows;
		final AtomicInteger next = new AtomicInteger();
		final AtomicInteger pendingRows = new AtomicInteger();
		final boolean[] parked;

		SpinPool(int workers, long spinNanos) {
			this.spinNanos = spinNanos;
			this.workers = new Thread[workers];
			this.parked = new boolean[workers];
			for (int i = 0; i < workers; i++) {
				int id = i;
				Thread t = new Thread(() -> loop(id), "spinpool-" + i);
				t.setDaemon(true);
				this.workers[i] = t;
				t.start();
			}
		}

		void loop(int id) {
			long seen = 0;
			while (true) {
				long deadline = System.nanoTime() + spinNanos;
				long e;
				while ((e = epoch) == seen) {
					if (System.nanoTime() > deadline) {
						parked[id] = true;
						// re-check after publishing parked, then park
						if (epoch == seen) {
							LockSupport.park(this);
						}
						parked[id] = false;
						deadline = System.nanoTime() + spinNanos;
					}
					Thread.onSpinWait();
				}
				seen = e;
				work();
			}
		}

		void work() {
			float[] w = this.w, x = this.x, r = this.r;
			int d = this.d, n = this.n, g = this.grainRows;
			while (true) {
				int from = next.getAndAdd(g);
				if (from >= d) {
					return;
				}
				int to = Math.min(d, from + g);
				rows(w, x, r, from, to, n);
				pendingRows.addAndGet(-(to - from));
			}
		}

		void run(float[] w, float[] x, float[] r, int d, int n, int grainRows) {
			this.w = w; this.x = x; this.r = r; this.d = d; this.n = n; this.grainRows = grainRows;
			pendingRows.set(d);
			next.set(0);
			epoch++;
			for (int i = 0; i < workers.length; i++) {
				if (parked[i]) {
					LockSupport.unpark(workers[i]);
				}
			}
			work();
			while (pendingRows.get() != 0) {
				Thread.onSpinWait();
			}
		}
	}

	static volatile double sink;

	/** ~G us of unrelated serial work on the caller. */
	static void gap(long nanos) {
		long end = System.nanoTime() + nanos;
		double a = sink;
		while (System.nanoTime() < end) {
			for (int i = 0; i < 50; i++) {
				a = a * 1.0000001 + 1e-9;
			}
		}
		sink = a;
	}

	static double median(double[] a) {
		java.util.Arrays.sort(a);
		return a[a.length / 2];
	}

	interface Gemv {
		void run();
	}

	/** Median per-GEMV time (us) with a gap of gapNanos between calls; the gap is excluded. */
	static double time(Gemv body, long gapNanos, int reps) {
		double[] ts = new double[9];
		for (int k = 0; k < 9; k++) {
			long total = 0;
			for (int i = 0; i < reps; i++) {
				gap(gapNanos);
				long t0 = System.nanoTime();
				body.run();
				total += System.nanoTime() - t0;
			}
			ts[k] = total / 1e3 / reps;
		}
		return median(ts);
	}

	public static void main(String[] args) throws Exception {
		int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
		int d = 288, n = 288, mats = Integer.getInteger("mats", 200); // 200 x 331 KB = 66 MB: cold, like a token
		ForkJoinPool pool = new ForkJoinPool(Math.max(1, threads - 1));
		SpinPool spin = new SpinPool(Math.max(1, threads - 1), 1_000_000L);
		float[][] ws = new float[mats][d * n];
		for (float[] w : ws) for (int i = 0; i < w.length; i++) w[i] = (i % 97) * 0.01f - 0.5f;
		float[] x = new float[n]; for (int i = 0; i < n; i++) x[i] = (i % 13) * 0.1f - 0.6f;
		float[] r = new float[d];
		System.out.printf("288x288 GEMV over %d rotating matrices (%d MB), threads=%d, 50 us gap between calls%n", mats, mats * d * n * 4 / 1000000, threads);
		for (int g : new int[] { 4, 8, 16, 28, 72 }) {
			int[] idx = { 0 };
			Gemv serial = () -> rows(ws[idx[0]++ % mats], x, r, 0, d, n);
			Gemv par = () -> spin.run(ws[idx[0]++ % mats], x, r, d, n, g);
			for (int i = 0; i < 400; i++) { serial.run(); par.run(); }
			double ts = time(serial, 50_000, 200);
			double tp = time(par, 50_000, 200);
			System.out.printf("  grain %2d rows: serial %6.1f us  spinpool %6.1f us  %.1fx%n", g, ts, tp, ts / tp);
		}
		System.exit(0);
	}
}
