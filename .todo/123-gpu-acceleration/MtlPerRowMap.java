import am.ik.gpu.Gpu;

/**
 * todo-642's question: {@code MetalGemm.MIN_MAP_ELEMENTS} is 2^17 and was measured against
 * {@code sin} over a WHOLE array; the shape that straddles it is a CHAIN's per-row intermediate --
 * {@code log-softmax}'s {@code (linalg:log (linalg:sum ... :keepdims t))} over a {@code rows x 1}
 * array, 16384 elements at the book's shapes. Is a device {@code log} over that shape ahead of
 * {@code VecSimdKernels.logIntoF}'s {@code (float) Math.log} loop over it?
 *
 * <p>Three columns, because the chain's context is not the back-to-back one a threshold table is
 * usually taken in. On this backend the axis FOLD is refused at every size (the fold threshold is
 * {@code Long.MAX_VALUE}), so the {@code sum} that produces this operand runs on the CPU -- and it
 * runs for milliseconds at the book's shapes, which is longer than the ~1 ms idle this GPU lowers
 * its clocks after ({@code .kb/gpu.md}, "Residency and the GEMV on this backend"). So the device
 * call the chain would actually make is the FIRST one after a gap, not a back-to-back one:
 *
 * <ul>
 * <li>{@code device b2b} -- the map called back to back, the number a threshold table would hold;
 * <li>{@code device gap} -- the same call with the CPU busy for {@code GAP_MS} before each one,
 * which is the chain's own shape;
 * <li>{@code cpu} -- {@code (float) Math.log} over the same freshly written operand.
 * </ul>
 *
 * <p>The operand is rewritten before every call (the previous member just wrote it) and each call
 * is timed on its own; a round is the median of {@code REPS} calls and the figure printed is the
 * best of {@code ROUNDS} rounds, the rounds interleaved so a drifting machine moves both columns.
 *
 * <p>Below the threshold the shipped library declines the map, so the device column needs the
 * constant opened up -- in a COPY, because the point of the run is to leave the shipped one alone.
 * From the repository root:
 *
 * <pre>
 * ./mvnw -o clean compile
 * mkdir -p /tmp/probe-src/am/ik/gpu /tmp/probe-classes
 * cp src/main/java/am/ik/gpu/*.java /tmp/probe-src/am/ik/gpu/
 * sed -i '' 's|MIN_MAP_ELEMENTS = 1L &lt;&lt; 17;|MIN_MAP_ELEMENTS = Long.getLong("probe.mapMin", 1L &lt;&lt; 17);|' \
 *   /tmp/probe-src/am/ik/gpu/MetalGemm.java
 * javac -cp "$(find ~/.m2 -name 'jspecify-*.jar' | head -1)" -d /tmp/probe-classes /tmp/probe-src/am/ik/gpu/*.java
 * java -cp /tmp/probe-classes:target/classes -Dprobe.mapMin=1024 --enable-native-access=ALL-UNNAMED \
 *   .todo/123-gpu-acceleration/MtlPerRowMap.java
 * </pre>
 *
 * <p>{@code target/classes} stays on the path behind the copy: it is where {@code gemm.metal} is
 * read from, so the run compiles the kernels this library actually ships.
 */
public class MtlPerRowMap {

	private static final int REPS = 200, ROUNDS = 5, GAP_MS = 8;

	public static void main(String[] args) {
		System.out.println(Gpu.description());
		System.out.println("probe.mapMin=" + System.getProperty("probe.mapMin") + "; gap " + GAP_MS + " ms, " + REPS
				+ " calls a round, best of " + ROUNDS);
		chainGap();
		System.out.printf("%8s %10s %12s %12s %12s %10s%n", "rows", "elements", "device b2b", "device gap", "cpu",
				"verdict");
		for (int p = 12; p <= 18; p++) {
			int n = 1 << p;
			float[] a = new float[n], out = new float[n];
			fill(a, n);
			// Warm the pipeline, the pool and C2 on both sides.
			for (int r = 0; r < 100; r++) {
				if (!Gpu.map(Gpu.MAP_LOG, a, 0, out, 0, n)) {
					System.out.printf("%8d %10d  declined by the device%n", n, n);
					break;
				}
				cpuLog(a, out, n);
			}
			double b2b = Double.MAX_VALUE, gap = Double.MAX_VALUE, cpu = Double.MAX_VALUE;
			for (int round = 0; round < ROUNDS; round++) {
				b2b = Math.min(b2b, median(time(a, out, n, true, 0)));
				gap = Math.min(gap, median(time(a, out, n, true, GAP_MS)));
				cpu = Math.min(cpu, median(time(a, out, n, false, 0)));
			}
			String verdict = cpu <= b2b ? "cpu" : (cpu <= gap ? "cpu (gap)" : "device");
			System.out.printf("%8d %10d %12.1f %12.1f %12.1f %10s%n", n, n, b2b, gap, cpu, verdict);
		}
	}

	/**
	 * How long the GPU is actually idle before the chain reaches this map, and how the device call
	 * grows with that idle. The gap is the {@code sum} this backend refuses at every size, over the
	 * book's own {@code (16384 3038)}: the fold's lane loop, timed here, is what sits between the
	 * previous device member and this one.
	 */
	private static void chainGap() {
		int rows = 16384, cols = 3038;
		float[] wide = new float[rows * cols];
		for (int i = 0; i < wide.length; i++) {
			wide[i] = 1.0f + (i % 4096) * 0.001f;
		}
		float[] sums = new float[rows];
		double best = Double.MAX_VALUE;
		for (int round = 0; round < 3; round++) {
			long t0 = System.nanoTime();
			rowSum(wide, sums, rows, cols);
			best = Math.min(best, (System.nanoTime() - t0) / 1e6);
		}
		System.out.printf("the chain's own gap: a CPU row sum over (%d %d) is %.1f ms%n", rows, cols, best);
		float[] a = new float[rows], out = new float[rows];
		fill(a, rows);
		for (int r = 0; r < 100; r++) {
			Gpu.map(Gpu.MAP_LOG, a, 0, out, 0, rows);
		}
		StringBuilder line = new StringBuilder("device log over 16384, per call us by idle before it:");
		for (int gap : new int[] { 0, 1, 2, 4, 8, 16, 32 }) {
			double us = Double.MAX_VALUE;
			for (int round = 0; round < 3; round++) {
				us = Math.min(us, median(time(a, out, rows, true, gap)));
			}
			line.append(String.format("  %dms %.0f", gap, us));
		}
		System.out.println(line);
	}

	private static void rowSum(float[] a, float[] out, int rows, int cols) {
		for (int r = 0; r < rows; r++) {
			double acc = 0;
			int base = r * cols;
			for (int c = 0; c < cols; c++) {
				acc += a[base + c];
			}
			out[r] = (float) acc;
		}
	}

	/** Per-call microseconds, one sample a call, the operand rewritten before each. */
	private static double[] time(float[] a, float[] out, int n, boolean device, int gapMs) {
		double[] samples = new double[REPS];
		for (int r = 0; r < REPS; r++) {
			fill(a, n);
			if (gapMs > 0) {
				burn(gapMs);
			}
			long t0 = System.nanoTime();
			if (device) {
				if (!Gpu.map(Gpu.MAP_LOG, a, 0, out, 0, n)) {
					return new double[] { Double.NaN };
				}
			}
			else {
				cpuLog(a, out, n);
			}
			samples[r] = (System.nanoTime() - t0) / 1e3;
		}
		return samples;
	}

	/** {@code VecSimdKernels.logIntoF}, which is what a declined map falls back to. */
	private static void cpuLog(float[] a, float[] out, int n) {
		for (int i = 0; i < n; i++) {
			out[i] = (float) Math.log(a[i]);
		}
	}

	/**
	 * The CPU busy for {@code ms} milliseconds -- the {@code sum} this backend refuses, whose
	 * length is what leaves the GPU idle long enough to lower its clocks.
	 */
	private static double sink;

	private static void burn(int ms) {
		long until = System.nanoTime() + ms * 1_000_000L;
		double acc = 0;
		while (System.nanoTime() < until) {
			for (int i = 1; i <= 4096; i++) {
				acc += Math.log(i);
			}
		}
		sink += acc;
	}

	/** A row of {@code exp} sums: positive, spread over the magnitudes log-softmax produces. */
	private static void fill(float[] a, int n) {
		for (int i = 0; i < n; i++) {
			a[i] = 1.0f + (i % 4096) * 1.25f;
		}
	}

	private static double median(double[] samples) {
		double[] copy = samples.clone();
		java.util.Arrays.sort(copy);
		return copy[copy.length / 2];
	}

}
