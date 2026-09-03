import jdk.incubator.vector.*;

/**
 * How many accumulators the --simd GEMV row wants, and whether FMA is needed for the win.
 *
 * The second question is the one that decides the item: wasm SIMD has NO fused
 * multiply-add. The `relaxed-simd` proposal has one and it is explicitly allowed to differ
 * between engines, so it can never carry a cross-backend bit-identity contract. If the win
 * needs FMA, it cannot land in `WasmVecSimdRuntimeBuilder` and the four backends cannot
 * agree; if it needs only more accumulators, it can. Every kernel here is therefore
 * measured as mul-then-add as well as fma.
 *
 * Shapes: 288x288 hot (llama2 stories15M, .todo/480's acceptance is < 8 us), 1024x1024,
 * 4096x4096 (DRAM). Run under both JITs:
 *   java --add-modules jdk.incubator.vector Acc.java
 *   java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector Acc.java
 */
public class Acc {
	static final VectorSpecies<Float> FS = FloatVector.SPECIES_128;
	static final int L = 4;

	static float sumLanes(FloatVector v) {
		float s = 0.0f;
		for (int i = 0; i < v.length(); i++) s += v.lane(i);
		return s;
	}

	// --- 1 accumulator, mul-then-add: exactly what ships today --------------------
	static float row1(float[] w, int base, int cols, float[] x) {
		int i = 0;
		float acc = 0.0f;
		FloatVector vacc = FloatVector.zero(FS);
		int bound = FS.loopBound(cols);
		for (; i < bound; i += L) {
			vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		}
		acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	// --- 2 accumulators, mul-then-add --------------------------------------------
	static float row2(float[] w, int base, int cols, float[] x) {
		int i = 0;
		FloatVector a0 = FloatVector.zero(FS), a1 = a0;
		int bound = (cols / (2 * L)) * (2 * L);
		for (; i < bound; i += 2 * L) {
			a0 = a0.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			a1 = a1.add(FloatVector.fromArray(FS, w, base + i + L).mul(FloatVector.fromArray(FS, x, i + L)));
		}
		FloatVector vacc = a0.add(a1);
		int b4 = FS.loopBound(cols);
		for (; i < b4; i += L) vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		float acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	// --- 4 accumulators, mul-then-add (the wasm-expressible shape) ----------------
	static float row4(float[] w, int base, int cols, float[] x) {
		int i = 0;
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		int bound = (cols / (4 * L)) * (4 * L);
		for (; i < bound; i += 4 * L) {
			a0 = a0.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			a1 = a1.add(FloatVector.fromArray(FS, w, base + i + L).mul(FloatVector.fromArray(FS, x, i + L)));
			a2 = a2.add(FloatVector.fromArray(FS, w, base + i + 2 * L).mul(FloatVector.fromArray(FS, x, i + 2 * L)));
			a3 = a3.add(FloatVector.fromArray(FS, w, base + i + 3 * L).mul(FloatVector.fromArray(FS, x, i + 3 * L)));
		}
		FloatVector vacc = a0.add(a1).add(a2.add(a3));
		int b4 = FS.loopBound(cols);
		for (; i < b4; i += L) vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		float acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	// --- 8 accumulators, mul-then-add --------------------------------------------
	static float row8(float[] w, int base, int cols, float[] x) {
		int i = 0;
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0, a4 = a0, a5 = a0, a6 = a0, a7 = a0;
		int bound = (cols / (8 * L)) * (8 * L);
		for (; i < bound; i += 8 * L) {
			a0 = a0.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
			a1 = a1.add(FloatVector.fromArray(FS, w, base + i + L).mul(FloatVector.fromArray(FS, x, i + L)));
			a2 = a2.add(FloatVector.fromArray(FS, w, base + i + 2 * L).mul(FloatVector.fromArray(FS, x, i + 2 * L)));
			a3 = a3.add(FloatVector.fromArray(FS, w, base + i + 3 * L).mul(FloatVector.fromArray(FS, x, i + 3 * L)));
			a4 = a4.add(FloatVector.fromArray(FS, w, base + i + 4 * L).mul(FloatVector.fromArray(FS, x, i + 4 * L)));
			a5 = a5.add(FloatVector.fromArray(FS, w, base + i + 5 * L).mul(FloatVector.fromArray(FS, x, i + 5 * L)));
			a6 = a6.add(FloatVector.fromArray(FS, w, base + i + 6 * L).mul(FloatVector.fromArray(FS, x, i + 6 * L)));
			a7 = a7.add(FloatVector.fromArray(FS, w, base + i + 7 * L).mul(FloatVector.fromArray(FS, x, i + 7 * L)));
		}
		FloatVector vacc = a0.add(a1).add(a2.add(a3)).add(a4.add(a5).add(a6.add(a7)));
		int b4 = FS.loopBound(cols);
		for (; i < b4; i += L) vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		float acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	// --- 4 accumulators, FMA: NOT expressible on wasm, measured as the ceiling -----
	static float row4fma(float[] w, int base, int cols, float[] x) {
		int i = 0;
		FloatVector a0 = FloatVector.zero(FS), a1 = a0, a2 = a0, a3 = a0;
		int bound = (cols / (4 * L)) * (4 * L);
		for (; i < bound; i += 4 * L) {
			a0 = FloatVector.fromArray(FS, w, base + i).fma(FloatVector.fromArray(FS, x, i), a0);
			a1 = FloatVector.fromArray(FS, w, base + i + L).fma(FloatVector.fromArray(FS, x, i + L), a1);
			a2 = FloatVector.fromArray(FS, w, base + i + 2 * L).fma(FloatVector.fromArray(FS, x, i + 2 * L), a2);
			a3 = FloatVector.fromArray(FS, w, base + i + 3 * L).fma(FloatVector.fromArray(FS, x, i + 3 * L), a3);
		}
		FloatVector vacc = a0.add(a1).add(a2.add(a3));
		int b4 = FS.loopBound(cols);
		for (; i < b4; i += L) vacc = vacc.add(FloatVector.fromArray(FS, w, base + i).mul(FloatVector.fromArray(FS, x, i)));
		float acc = sumLanes(vacc);
		for (; i < cols; i++) acc += w[base + i] * x[i];
		return acc;
	}

	interface Row { float run(float[] w, int base, int cols, float[] x); }

	static void gemv(float[] r, float[] w, int rows, int cols, float[] x, Row row) {
		for (int i = 0; i < rows; i++) r[i] = row.run(w, i * cols, cols, x);
	}

	static long time(Runnable r, int it) {
		for (int i = 0; i < 8; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int k = 0; k < 7; k++) {
			long s = System.nanoTime();
			for (int i = 0; i < it; i++) r.run();
			best = Math.min(best, (System.nanoTime() - s) / it);
		}
		return best;
	}

	static String jit() {
		try {
			return "true".equals(java.lang.management.ManagementFactory
					.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
					.getVMOption("UseJVMCICompiler").getValue()) ? "graal" : "c2";
		} catch (RuntimeException e) { return "unknown"; }
	}

	public static void main(String[] a) {
		System.out.printf("jit=%s java=%s%n", jit(), System.getProperty("java.version"));
		java.util.Random rn = new java.util.Random(11);
		record V(String n, Row r) {}
		V[] vs = { new V("1 acc  mul+add (shipped)", Acc::row1), new V("2 acc  mul+add", Acc::row2),
				new V("4 acc  mul+add", Acc::row4), new V("8 acc  mul+add", Acc::row8),
				new V("4 acc  fma (not on wasm)", Acc::row4fma) };
		for (int[] sz : new int[][] { { 288, 288 }, { 256, 48 }, { 1024, 1024 }, { 4096, 4096 } }) {
			int rows = sz[0], cols = sz[1], e = rows * cols;
			float[] w = new float[e];
			for (int i = 0; i < e; i++) w[i] = (float) (rn.nextGaussian() * 0.02);
			float[] x = new float[cols];
			for (int i = 0; i < cols; i++) x[i] = (float) rn.nextGaussian();
			float[] r = new float[rows];
			int it = e > (1 << 22) ? 10 : 200;
			System.out.printf("%n=== %dx%d%n%-26s %10s %9s %8s   result%n", rows, cols, "variant", "us", "Gelem/s", "vs 1 acc");
			long base = 0;
			for (V v : vs) {
				long ns = time(() -> gemv(r, w, rows, cols, x, v.r()), it);
				if (base == 0) base = ns;
				gemv(r, w, rows, cols, x, v.r());
				double sum = 0;
				for (float f : r) sum += f;
				System.out.printf("%-26s %10.2f %9.2f %7.2fx   %a%n", v.n(), ns / 1e3, e / (double) ns, base / (double) ns, sum);
			}
		}
	}
}
