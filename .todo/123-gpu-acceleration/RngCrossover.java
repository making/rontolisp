import am.ik.gpu.Gpu;

/**
 * Where does the seeded generator's fill (linalg::%la-rng-fill, the member behind
 * linalg:rand / randn / uniform) start to pay for a round trip? The device side is the
 * SHIPPED route -- Gpu.rngFill over the checked-in PTX, pooled allocation included --
 * and the CPU side is the sequential walk the --simd kernels run (copied here so the
 * probe has no dependency on rontolisp classes). Run from the repository root after a
 * build, with the library's classes on the classpath:
 *
 *   java --enable-native-access=ALL-UNNAMED -cp target/classes \
 *        .todo/123-gpu-acceleration/RngCrossover.java
 *
 * Both sides are bit-identical (asserted below on every size), so the only question is
 * the time. The threshold the library ships (Gpu.RNG_POOLED_MIN_ELEMENTS) is read off
 * this table: the crossover for one uniform draw per element sits between 1024 and 4096,
 * and for the twelve-draw normal it is below the smallest size here.
 */
public class RngCrossover {

	public static void main(String[] args) throws Exception {
		System.out.println(Gpu.description());
		System.out.printf("%n%-8s %-6s %-5s %12s %12s %8s%n", "n", "mode", "width", "cpu us", "gpu us", "ratio");
		for (int mode : new int[] { 0, 1, 2 }) {
			for (int n : new int[] { 512, 1024, 2048, 4096, 8192, 16384, 65536, 262144, 1048576 }) {
				row(n, mode, false);
				row(n, mode, true);
			}
		}
	}

	static void row(int n, int mode, boolean single) {
		int s1 = 4321, s2 = 8765, s3 = 2468;
		double lo = -1.0, span = 3.0;
		int reps = n <= 16384 ? 400 : 40;
		double cpu = Double.MAX_VALUE, gpu = Double.MAX_VALUE;
		double[] d = new double[n], dr = new double[n];
		float[] f = new float[n], fr = new float[n];
		for (int r = 0; r < reps; r++) {
			long t0 = System.nanoTime();
			if (single) fill(f, mode, lo, span, s1, s2, s3); else fill(d, mode, lo, span, s1, s2, s3);
			long t1 = System.nanoTime();
			boolean ok = single ? Gpu.rngFill(fr, 0, n, mode, lo, span, s1, s2, s3)
					: Gpu.rngFill(dr, 0, n, mode, lo, span, s1, s2, s3);
			long t2 = System.nanoTime();
			if (!ok) {
				System.out.printf("%-8d %-6d %-5s %12s%n", n, mode, single ? "f32" : "f64", "declined");
				return;
			}
			if (r > reps / 5) {
				cpu = Math.min(cpu, (t1 - t0) / 1e3);
				gpu = Math.min(gpu, (t2 - t1) / 1e3);
			}
		}
		for (int i = 0; i < n; i++) {
			if (single ? Float.floatToRawIntBits(f[i]) != Float.floatToRawIntBits(fr[i])
					: Double.doubleToRawLongBits(d[i]) != Double.doubleToRawLongBits(dr[i])) {
				throw new AssertionError("mismatch at " + i + " n=" + n + " mode=" + mode);
			}
		}
		System.out.printf("%-8d %-6d %-5s %12.1f %12.1f %8.2f%n", n, mode, single ? "f32" : "f64", cpu, gpu, cpu / gpu);
	}

	// The sequential walk, as LinalgSimdKernels.rngFill spells it.
	static void fill(double[] out, int mode, double lo, double span, int s1, int s2, int s3) {
		int[] st = { s1, s2, s3 };
		for (int k = 0; k < out.length; k++) out[k] = element(mode, lo, span, st);
	}

	static void fill(float[] out, int mode, double lo, double span, int s1, int s2, int s3) {
		int[] st = { s1, s2, s3 };
		for (int k = 0; k < out.length; k++) out[k] = (float) element(mode, lo, span, st);
	}

	static double element(int mode, double lo, double span, int[] st) {
		if (mode == 1) {
			double acc = 0.0;
			for (int j = 0; j < 12; j++) acc = acc + next(st);
			return acc - 6.0;
		}
		if (mode == 0) return next(st);
		return lo + span * next(st);
	}

	static double next(int[] st) {
		int a = 171 * st[0] % 30269, b = 172 * st[1] % 30307, c = 170 * st[2] % 30323;
		st[0] = a; st[1] = b; st[2] = c;
		double u = a / 30269.0 + b / 30307.0 + c / 30323.0;
		return u >= 2.0 ? u - 2.0 : (u >= 1.0 ? u - 1.0 : u);
	}
}
