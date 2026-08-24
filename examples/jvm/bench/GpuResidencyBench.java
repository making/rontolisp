import java.lang.reflect.Method;
import java.util.Random;

import am.ik.rontolisp.runtime.RontoFloatArray;

import com.example.CpuKernels;
import com.example.GpuKernels;

/**
 * Does the Java boundary handle defeat the {@code --gpu} device-resident tier? -- the
 * measurement behind {@code .kb/jvm-export.md}, "--gpu residency, and why the handle does
 * not materialize".
 *
 * <p>
 * A {@code :float-vector} result is wrapped WITHOUT materializing, so a Java-side chain
 * {@code h = Kernels.step(w, h)} should leave every intermediate on the device and bring
 * only the last one home, when the caller reads it. The alternative -- materializing at
 * the boundary -- is correct too and costs a download the next call only re-uploads, so
 * what is measured here is a cost, not a correctness question. Three things are checked:
 *
 * <ol>
 * <li><b>residency survives the crossing</b>: the chain uploads ONCE and the per-iteration
 * time equals the same chain run entirely inside Lisp, which crosses the boundary once for
 * all of it;
 * <li><b>a read brings it home exactly once</b>, and answers what the same library
 * compiled without {@code --gpu} answers;
 * <li><b>a write through the handle lands on the array the guard answers</b>, so the next
 * kernel call sees it -- on a lazy result the device still holds, and on the resident
 * matrix.
 * </ol>
 *
 * <p>
 * The residency counters are read reflectively out of the library the compiled class
 * carries ({@code am.ik.gpu}, renamed into the class's own package): a compiled class has
 * no test seam, and the counters are the only observable that says a result really stayed
 * -- the printed numbers cannot, since the device member is written to land on the host's
 * own bits.
 */
public class GpuResidencyBench {

	/** The GEMV's side. 2048x2048 f32 is 16 MB of weights and clears the GEMV threshold. */
	private static final int N = 2048;

	/** Iterations per timed chain. */
	private static final int ITERS = 200;

	/** Chain length for the oracle comparison -- short, because the iteration is chaotic. */
	private static final int ORACLE_STEPS = 8;

	private static final int REPEATS = 3;

	public static void main(String[] args) throws Exception {
		Random random = new Random(42);
		float[] weights = new float[N * N];
		float[] start = new float[N];
		// Variance 1/N per entry keeps the spectral radius near 1, so the chain neither
		// overflows nor decays over a few hundred GEMVs and the numbers stay comparable.
		double scale = Math.sqrt(3.0 / N);
		for (int i = 0; i < weights.length; i++) {
			weights[i] = (float) ((random.nextDouble() * 2.0 - 1.0) * scale);
		}
		for (int i = 0; i < N; i++) {
			start[i] = (float) (random.nextDouble() * 2.0 - 1.0);
		}
		RontoFloatArray w = RontoFloatArray.of(weights, N, N);

		// The matrix is uploaded on its SECOND sight, never its first (.kb/gpu.md), so the
		// steady state needs two calls to reach.
		RontoFloatArray warm = RontoFloatArray.of(start);
		for (int i = 0; i < 4; i++) {
			warm = GpuKernels.step(w, warm);
		}
		warm.get(0);

		Residency residency = Residency.of(GpuKernels.class);
		System.out.printf("n = %d, %d iterations, single-float%n", N, ITERS);
		System.out.println(residency.present() ? "device: present (the counters below are real)"
				: "device: NONE -- --gpu degraded to the portable kernels, so this run proves nothing");
		System.out.println();

		residencySurvivesTheCrossing(w, start, residency);
		aReadBringsItHomeExactlyOnce(w, start, residency);
		aWriteThroughTheHandleIsSeenByTheNextCall(w, start, weights);
	}

	/**
	 * 1. The chain stays on the device across every crossing: one upload for the whole
	 * run, and the same per-iteration cost as the loop that never crosses.
	 */
	private static void residencySurvivesTheCrossing(RontoFloatArray w, float[] start, Residency residency)
			throws Exception {
		System.out.println("1. residency across the boundary");
		for (int repeat = 0; repeat < REPEATS; repeat++) {
			Measurement chain = measure(residency, () -> {
				RontoFloatArray h = RontoFloatArray.of(start);
				for (int i = 0; i < ITERS; i++) {
					h = GpuKernels.step(w, h);
				}
				return h;
			});
			Measurement inside = measure(residency, () -> GpuKernels.steps(w, RontoFloatArray.of(start), ITERS));
			Measurement copying = measure(residency, () -> {
				RontoFloatArray h = RontoFloatArray.of(start);
				for (int i = 0; i < ITERS; i++) {
					// What materializing at the boundary would cost: home, and back up.
					h = RontoFloatArray.of(GpuKernels.step(w, h).toFloatArray());
				}
				return h;
			});
			report("Java chain through the handle", chain);
			report("the same chain inside Lisp (the floor)", inside);
			report("a chain that materializes per call", copying);
			if (Math.abs(chain.first - inside.first) > 1e-6 * Math.abs(inside.first)) {
				throw new AssertionError("the two chains must compute the same number: " + chain.first + " vs "
						+ inside.first);
			}
			System.out.println();
		}
	}

	/**
	 * 2. A read brings the result home exactly once -- one stub gains a backing, one dirty
	 * copy is cleared, and a second read moves nothing -- and answers what the same
	 * library without {@code --gpu} answers.
	 */
	private static void aReadBringsItHomeExactlyOnce(RontoFloatArray w, float[] start, Residency residency)
			throws Exception {
		System.out.println("2. toArray() on a device result");
		RontoFloatArray h = RontoFloatArray.of(start);
		for (int i = 0; i < ORACLE_STEPS; i++) {
			h = GpuKernels.step(w, h);
		}
		int dirty = residency.dirty();
		int backed = residency.backed();
		System.out.printf("  before the read: %d elements held, host array of %d (the header alone)%n", h.size(),
				java.lang.reflect.Array.getLength(h.packed()));
		double[] out = h.toArray();
		System.out.printf("  the read:        dirty %d -> %d, stubs holding a backing %d -> %d%n", dirty,
				residency.dirty(), backed, residency.backed());
		h.toArray();
		System.out.printf("  a second read:   dirty %d, stubs holding a backing %d (nothing moved)%n",
				residency.dirty(), residency.backed());

		double[] oracle = CpuKernels.steps(w, RontoFloatArray.of(start), ORACLE_STEPS).toArray();
		double worst = 0.0;
		double norm = 0.0;
		for (int i = 0; i < oracle.length; i++) {
			worst = Math.max(worst, Math.abs(out[i] - oracle[i]));
			norm = Math.max(norm, Math.abs(oracle[i]));
		}
		System.out.printf("  vs the same library without --gpu: worst element %.3e of %.3e (%.1e relative)%n%n", worst,
				norm, worst / norm);
		if (worst > 1e-3 * norm) {
			throw new AssertionError("the device chain must answer the oracle's numbers");
		}
	}

	/**
	 * 3. A write through the handle lands on the array the residency guard ANSWERS, so the
	 * next kernel call sees it -- both on a lazy result the device still holds and on the
	 * resident matrix, whose device copy the write has to invalidate.
	 */
	private static void aWriteThroughTheHandleIsSeenByTheNextCall(RontoFloatArray w, float[] start, float[] weights) {
		System.out.println("3. set() through a handle");
		RontoFloatArray x = RontoFloatArray.of(start);
		for (int i = 0; i < 3; i++) {
			x = GpuKernels.step(w, x);
		}
		double before = GpuKernels.step(w, x).get(0);
		x.set(3, x.get(3) + 1.0);
		double after = GpuKernels.step(w, x).get(0);
		// Row 0 of the answer moves by exactly the weight that multiplies element 3.
		check("into a lazy device result", after - before, weights[3]);

		RontoFloatArray u = RontoFloatArray.of(start);
		double base = GpuKernels.step(w, u).get(0);
		w.set(0, 5, w.get(0, 5) + 1.0);
		double moved = GpuKernels.step(w, u).get(0);
		check("into the resident matrix", moved - base, start[5]);
		w.set(0, 5, w.get(0, 5) - 1.0);
	}

	private static void check(String label, double actual, double expected) {
		System.out.printf("  %-28s the next call moved by %+.6f, expected %+.6f%n", label, actual, expected);
		if (Math.abs(actual - expected) > 1e-4 * Math.max(1.0, Math.abs(expected))) {
			throw new AssertionError(label + ": the next kernel call did not see the write");
		}
	}

	private static Measurement measure(Residency residency, java.util.function.Supplier<RontoFloatArray> body)
			throws Exception {
		long hits = residency.hits();
		long misses = residency.misses();
		long started = System.nanoTime();
		// The read is inside the timing on purpose: launches are asynchronous under lazy
		// results, so a chain that is never read has not necessarily finished.
		double first = body.get().get(0);
		double ms = (System.nanoTime() - started) / 1e6;
		return new Measurement(ms, residency.hits() - hits, residency.misses() - misses, first);
	}

	private static void report(String label, Measurement m) {
		// Every upload here is one vector: the matrix was made resident before the timing.
		double uploadedKb = m.misses * N * 4 / 1024.0;
		System.out.printf("  %-38s %8.3f ms  %6.3f ms/iter   %4d resident hits, %4d uploads (%.0f KB)%n", label, m.ms,
				m.ms / ITERS, m.hits, m.misses, uploadedKb);
	}

	/** One timed chain: what it cost, and what the device did while it ran. */
	private record Measurement(double ms, long hits, long misses, double first) {
	}

	/**
	 * The compiled class's own copy of the residency cache, reached by reflection. The
	 * {@code --gpu} bridge travels as {@code am.ik.gpu}'s class files renamed into the
	 * generated class's package ({@code .kb/gpu.md}), so the cache is
	 * {@code <package>.RontoLispGpuDeviceResidency} and the counters are the library's own
	 * package-private ones. A consumer never does this; a measurement has to.
	 */
	private static final class Residency {

		private final Object cache;

		private final Method hits;

		private final Method misses;

		private final Method dirty;

		private final Method backed;

		private Residency(Object cache, Method hits, Method misses, Method dirty, Method backed) {
			this.cache = cache;
			this.hits = hits;
			this.misses = misses;
			this.dirty = dirty;
			this.backed = backed;
		}

		static Residency of(Class<?> library) throws Exception {
			String pkg = library.getPackageName().isEmpty() ? "" : library.getPackageName() + ".";
			Method entry = Class.forName(pkg + "RontoLispGpuGpu").getDeclaredMethod("residency");
			entry.setAccessible(true);
			Object cache = entry.invoke(null);
			if (cache == null) {
				return new Residency(null, null, null, null, null);
			}
			return new Residency(cache, method(cache, "hits"), method(cache, "misses"), method(cache, "dirtyCount"),
					method(cache, "backingCount"));
		}

		private static Method method(Object cache, String name) throws Exception {
			Method m = cache.getClass().getDeclaredMethod(name);
			m.setAccessible(true);
			return m;
		}

		boolean present() {
			return this.cache != null;
		}

		long hits() throws Exception {
			return this.cache == null ? 0 : ((Number) this.hits.invoke(this.cache)).longValue();
		}

		long misses() throws Exception {
			return this.cache == null ? 0 : ((Number) this.misses.invoke(this.cache)).longValue();
		}

		int dirty() throws Exception {
			return this.cache == null ? -1 : ((Number) this.dirty.invoke(this.cache)).intValue();
		}

		int backed() throws Exception {
			return this.cache == null ? -1 : ((Number) this.backed.invoke(this.cache)).intValue();
		}

	}

}
