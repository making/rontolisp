import jdk.incubator.vector.*;

/**
 * ONE shape, measured without any of the machinery that made `Gate.java` order-dependent.
 *
 * `Gate.java` returns three different ratios for the SAME shape (rows=256, cols=48) in one
 * process: 0.92x in its section A, 1.29x in section B, 1.21x in its closing table (GB10,
 * Graal, 2026-09-03). On x86-64 the same spread changes the SIGN -- 0.93x against 1.24x.
 * The spread reproduces on a quiet box and on a loaded one, so it is not noise.
 *
 * The confound is that `Gate.java` funnels both kernels through one generic timing method
 * (`time(Gemv, ...)`), so the two share a JIT profile and a compilation, and `gemv1` always
 * runs first. This file removes every part of that:
 *
 *   - **No interface, no lambda, no method reference.** Each kernel has its OWN timing
 *     method calling it by name, so each is monomorphic and compiled on its own -- which is
 *     also how the shipped kernel is called (`matvecRowsF` is a direct static call inside
 *     one method, never dispatched per row).
 *   - **One shape per process.** Nothing else has warmed the JIT or moved the caches.
 *   - **`kernel` mode runs ONE of the two and nothing else**, so the ratio can be taken
 *     across two processes that never saw the other kernel at all. That is the decisive
 *     instrument; `both` mode is the cross-check.
 *
 * Usage:
 *   java --add-modules jdk.incubator.vector Solo.java both  <rows> <cols> [reps]
 *   java --add-modules jdk.incubator.vector Solo.java one   <rows> <cols> <1|4>
 * Add -XX:-UseJVMCICompiler for C2.
 */
public class Solo {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_128;
	static final int L = 4;

	static float sumLanes(FloatVector v) {
		float s = 0.0f;
		for (int i = 0; i < v.length(); i++) s += v.lane(i);
		return s;
	}

	/** The shipped single-chain row. */
	static void gemv1(float[] r, float[] w, int rows, int cols, float[] x) {
		int bound = FS.loopBound(cols);
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			int i = 0;
			FloatVector vacc = FloatVector.zero(FS);
			for (; i < bound; i += L)
				vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			float acc = sumLanes(vacc);
			for (; i < cols; i++) acc += w[base + i] * x[i];
			r[row] = acc;
		}
	}

	/** The four-chain row, the element-based wide bound the shipped kernel uses. */
	static void gemv4(float[] r, float[] w, int rows, int cols, float[] x) {
		int wide = cols / (4 * L) * (4 * L);
		int bound = FS.loopBound(cols);
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			int i = 0;
			FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
			for (; i < wide; i += 4 * L) {
				a0 = a0.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
				a1 = a1.add(FloatVector.fromArray(FS, w, base + i + L).mul(FloatVector.fromArray(FS, x, i + L)));
				a2 = a2.add(FloatVector.fromArray(FS, w, base + i + 2 * L).mul(FloatVector.fromArray(FS, x, i + 2 * L)));
				a3 = a3.add(FloatVector.fromArray(FS, w, base + i + 3 * L).mul(FloatVector.fromArray(FS, x, i + 3 * L)));
			}
			FloatVector vacc = a0.add(a1).add(a2.add(a3));
			for (; i < bound; i += L)
				vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			float acc = sumLanes(vacc);
			for (; i < cols; i++) acc += w[base + i] * x[i];
			r[row] = acc;
		}
	}

	// Two timing methods, one per kernel, each calling its kernel by name: monomorphic,
	// separately compiled, and neither can inherit the other's profile.
	static double time1(float[] r, float[] w, int rows, int cols, float[] x, int it) {
		for (int k = 0; k < 3; k++) for (int i = 0; i < it; i++) gemv1(r, w, rows, cols, x);
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 9; k++) {
			long s = System.nanoTime();
			for (int i = 0; i < it; i++) gemv1(r, w, rows, cols, x);
			best = Math.min(best, System.nanoTime() - s);
		}
		return best / (double) it;
	}

	static double time4(float[] r, float[] w, int rows, int cols, float[] x, int it) {
		for (int k = 0; k < 3; k++) for (int i = 0; i < it; i++) gemv4(r, w, rows, cols, x);
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 9; k++) {
			long s = System.nanoTime();
			for (int i = 0; i < it; i++) gemv4(r, w, rows, cols, x);
			best = Math.min(best, System.nanoTime() - s);
		}
		return best / (double) it;
	}

	static String jit() {
		try {
			return "true".equals(java.lang.management.ManagementFactory
					.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
					.getVMOption("UseJVMCICompiler").getValue()) ? "graal" : "c2";
		} catch (RuntimeException e) { return "unknown"; }
	}

	public static void main(String[] a) {
		String mode = a[0];
		int rows = Integer.parseInt(a[1]), cols = Integer.parseInt(a[2]);
		int e = rows * cols;
		java.util.Random rn = new java.util.Random(11);
		float[] w = new float[e];
		for (int i = 0; i < e; i++) w[i] = (float) (rn.nextGaussian() * 0.02);
		float[] x = new float[cols];
		for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
		float[] r = new float[rows];
		int it = (int) Math.max(50, Math.min(200000, 4_000_000L / Math.max(1, (long) e)));

		if (mode.equals("one")) {
			int which = Integer.parseInt(a[3]);
			double ns = which == 1 ? time1(r, w, rows, cols, x, it) : time4(r, w, rows, cols, x, it);
			System.out.printf("jit=%s %dx%d acc=%d %.2f ns%n", jit(), rows, cols, which, ns);
			return;
		}
		int reps = a.length > 3 ? Integer.parseInt(a[3]) : 10;
		System.out.printf("jit=%s %dx%d, %d reps in one process%n", jit(), rows, cols, reps);
		for (int k = 0; k < reps; k++) {
			double n1 = time1(r, w, rows, cols, x, it);
			double n4 = time4(r, w, rows, cols, x, it);
			System.out.printf("  rep %2d: 1acc %8.1f ns   4acc %8.1f ns   %.2fx%n", k + 1, n1, n4, n1 / n4);
		}
	}
}
