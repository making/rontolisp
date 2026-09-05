import jdk.incubator.vector.*;

/**
 * Where the GEMV column gate belongs, measured at the shapes real models actually use.
 *
 * The gate has to be a pure function of the COLUMN COUNT: the `(vec:matvec vth att)` of
 * examples/llm/llm.lisp is a GEMV whose rows are the head dimension and whose COLUMNS are
 * the sequence length, so the same call site crosses the gate as generation proceeds --
 * and it may cross it only because the column count changed, never because of a row
 * count, a call count or any other state. Its sibling `K . q` is the other way round:
 * columns fixed at the head dimension, rows growing with the sequence.
 *
 * (This measurement is from 2026-08-22 and is not restated. Only the POINTER above was
 * rewritten, on 2026-09-05, when examples/llama2 became examples/llm: it cited "line 633"
 * and "line 621", and the file had grown enough that 633 was config reading and the GEMV
 * had moved to 1312. A line number into a living file decays silently and nothing checks
 * it, so it is named by the call it points at instead.)
 *
 * So the two tables below:
 *
 *   A. columns fixed at a real head dimension, rows 1 .. 1024 (the 621 shape). Four
 *      accumulators cost four zeroed vectors and a three-add fold PER ROW, so the worst
 *      case is the FEWEST rows -- the first tokens of a generation, not the middle.
 *   B. rows fixed at a real head dimension, columns swept 1 .. 256 (the 633 shape), which
 *      is the gate crossing itself.
 *   C. 128x128, the Gated DeltaNet GEMV of Qwen3.5-0.8B: square, fixed, one point.
 *
 * The real head dimensions, checked 2026-09-03: 48 (stories15M), 64 (SmolLM2-135M,
 * TinyLlama-1.1B, LFM2.5-1.2B), 128 (Qwen3-0.6B, and the DeltaNet GEMV), 256
 * (Qwen3.5-0.8B). They fall into {48, 64} and {128, 256} with nothing between, so a gate
 * must sit in that gap and not ON a real value -- a threshold a model's head dimension
 * lands exactly on puts that model's every GEMV on whichever side measurement noise
 * falls. 96 is the middle of the gap and the only candidate measured here.
 *
 *   java --add-modules jdk.incubator.vector Gate.java
 *   java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector Gate.java
 */
public class Gate {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_128;
	static final int L = 4;

	static float sumLanes(FloatVector v) {
		float s = 0.0f;
		for (int i = 0; i < v.length(); i++) s += v.lane(i);
		return s;
	}

	/** The shipped single-chain row: one accumulator, two-rounding mul-then-add. */
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

	/** The candidate: four independent chains, the element-based wide bound the kernels use. */
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

	interface Gemv { void run(float[] r, float[] w, int rows, int cols, float[] x); }

	/** Nanoseconds per GEMV: best of 9 rounds, iterations scaled so a round is ~1 ms of work. */
	static double time(Gemv g, float[] r, float[] w, int rows, int cols, float[] x) {
		int it = (int) Math.max(50, Math.min(200000, 4_000_000L / Math.max(1, (long) rows * cols)));
		for (int k = 0; k < 3; k++) for (int i = 0; i < it; i++) g.run(r, w, rows, cols, x);
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 9; k++) {
			long s = System.nanoTime();
			for (int i = 0; i < it; i++) g.run(r, w, rows, cols, x);
			best = Math.min(best, System.nanoTime() - s);
		}
		return best / (double) it;
	}

	static final java.util.Random RN = new java.util.Random(11);

	static double[] one(int rows, int cols) {
		int e = rows * cols;
		float[] w = new float[e];
		for (int i = 0; i < e; i++) w[i] = (float) (RN.nextGaussian() * 0.02);
		float[] x = new float[cols];
		for (int i = 0; i < cols; i++) x[i] = (float) RN.nextGaussian();
		float[] r = new float[rows];
		double n1 = time(Gate::gemv1, r, w, rows, cols, x);
		double n4 = time(Gate::gemv4, r, w, rows, cols, x);
		return new double[] { n1, n4 };
	}

	static String jit() {
		try {
			return "true".equals(java.lang.management.ManagementFactory
					.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
					.getVMOption("UseJVMCICompiler").getValue()) ? "graal" : "c2";
		} catch (RuntimeException e) { return "unknown"; }
	}

	public static void main(String[] a) {
		System.out.printf("jit=%s java=%s   (4-acc vs the shipped 1-acc row; >1.00x means 4 accumulators win)%n",
				jit(), System.getProperty("java.version"));

		int[] headDims = { 48, 64, 96, 128, 256 };
		int[] rowCounts = { 1, 4, 16, 64, 256, 1024 };
		System.out.printf("%n=== A. line 621: columns FIXED at a head dimension, rows growing with the sequence%n");
		System.out.printf("%8s", "rows");
		for (int c : headDims) System.out.printf(" %11s", "cols=" + c);
		System.out.println();
		for (int rows : rowCounts) {
			System.out.printf("%8d", rows);
			for (int cols : headDims) {
				double[] t = one(rows, cols);
				System.out.printf(" %10.2fx", t[0] / t[1]);
			}
			System.out.println();
		}

		int[] sweep = { 1, 2, 3, 4, 8, 12, 16, 24, 32, 48, 64, 80, 88, 96, 104, 112, 128, 160, 192, 224, 256 };
		System.out.printf("%n=== B. line 633: rows FIXED at a head dimension, columns = sequence length (the gate crossing)%n");
		System.out.printf("%8s", "cols");
		for (int r : new int[] { 48, 64, 128, 256 }) System.out.printf(" %11s", "rows=" + r);
		System.out.println();
		for (int cols : sweep) {
			System.out.printf("%8d", cols);
			for (int rows : new int[] { 48, 64, 128, 256 }) {
				double[] t = one(rows, cols);
				System.out.printf(" %10.2fx", t[0] / t[1]);
			}
			System.out.println();
		}

		System.out.printf("%n=== C. Gated DeltaNet, Qwen3.5-0.8B: 128x128, square and fixed%n");
		double[] t = one(128, 128);
		System.out.printf("128x128   1acc %.1f ns   4acc %.1f ns   %.2fx%n", t[0], t[1], t[0] / t[1]);

		System.out.printf("%n=== absolute times at the five candidate columns, rows = 256 (mid-generation 621)%n");
		System.out.printf("%8s %12s %12s %9s%n", "cols", "1acc ns", "4acc ns", "4 vs 1");
		for (int cols : headDims) {
			double[] u = one(256, cols);
			System.out.printf("%8d %12.1f %12.1f %8.2fx%n", cols, u[0], u[1], u[0] / u[1]);
		}
	}
}
