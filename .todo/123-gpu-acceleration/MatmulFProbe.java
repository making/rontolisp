import jdk.incubator.vector.*;

/**
 * Would the wasm backend's trick -- lanes at #f width with an f64 accumulator, reached by
 * widening the b-row -- pay on the JVM too? The Java kernels avoid convert(F2D) on the
 * grounds that a JIT is least likely to intrinsify it; wasm does it anyway and stays
 * bit-identical. Three variants of the same ikj product, f32 in, f32 out.
 */
public class MatmulFProbe {

	static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;

	static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;

	/** (a) today's kernel: plain scalar loop into a double[] accumulator row. */
	static float[] scalarAcc(float[] a, float[] b, int n, int m, int p) {
		float[] r = new float[n * p];
		double[] acc = new double[p];
		for (int i = 0; i < n; i++) {
			java.util.Arrays.fill(acc, 0.0);
			int ao = i * m;
			for (int k = 0; k < m; k++) {
				double s = a[ao + k];
				int bo = k * p;
				for (int j = 0; j < p; j++) {
					acc[j] += b[bo + j] * s;
				}
			}
			int ro = i * p;
			for (int j = 0; j < p; j++) {
				r[ro + j] = (float) acc[j];
			}
		}
		return r;
	}

	/** (b) the wasm approach on the JVM: widen the b row to f64 lanes, accumulate in f64. */
	static float[] laneAccF2D(float[] a, float[] b, int n, int m, int p) {
		float[] r = new float[n * p];
		double[] acc = new double[p];
		int bound = FS.loopBound(p);
		int half = DS.length();
		for (int i = 0; i < n; i++) {
			java.util.Arrays.fill(acc, 0.0);
			int ao = i * m;
			for (int k = 0; k < m; k++) {
				double s = a[ao + k];
				int bo = k * p;
				int j = 0;
				for (; j < bound; j += FS.length()) {
					FloatVector fv = FloatVector.fromArray(FS, b, bo + j);
					for (int part = 0; part * half < FS.length(); part++) {
						DoubleVector bd = (DoubleVector) fv.convertShape(VectorOperators.F2D, DS, part);
						int at = j + part * half;
						DoubleVector.fromArray(DS, acc, at).add(bd.mul(s)).intoArray(acc, at);
					}
				}
				for (; j < p; j++) {
					acc[j] += b[bo + j] * s;
				}
			}
			int ro = i * p;
			for (int j = 0; j < p; j++) {
				r[ro + j] = (float) acc[j];
			}
		}
		return r;
	}

	/** (c) the ceiling: pure f32 lanes, f32 accumulator. NOT bit-identical to the oracle. */
	static float[] laneAccF32(float[] a, float[] b, int n, int m, int p) {
		float[] r = new float[n * p];
		int bound = FS.loopBound(p);
		for (int i = 0; i < n; i++) {
			int ro = i * p;
			int ao = i * m;
			for (int k = 0; k < m; k++) {
				float s = a[ao + k];
				int bo = k * p;
				int j = 0;
				for (; j < bound; j += FS.length()) {
					FloatVector.fromArray(FS, r, ro + j)
						.add(FloatVector.fromArray(FS, b, bo + j).mul(s))
						.intoArray(r, ro + j);
				}
				for (; j < p; j++) {
					r[ro + j] += b[bo + j] * s;
				}
			}
		}
		return r;
	}

	/** the f64 kernel, for the reference this whole question is about. */
	static double[] f64(double[] a, double[] b, int n, int m, int p) {
		double[] r = new double[n * p];
		int bound = DS.loopBound(p);
		for (int i = 0; i < n; i++) {
			int ro = i * p, ao = i * m;
			for (int k = 0; k < m; k++) {
				double s = a[ao + k];
				int bo = k * p;
				int j = 0;
				for (; j < bound; j += DS.length()) {
					DoubleVector.fromArray(DS, r, ro + j)
						.add(DoubleVector.fromArray(DS, b, bo + j).mul(s))
						.intoArray(r, ro + j);
				}
				for (; j < p; j++) {
					r[ro + j] += b[bo + j] * s;
				}
			}
		}
		return r;
	}

	public static void main(String[] x) {
		System.out.println("f32 lanes=" + FS.length() + ", f64 lanes=" + DS.length());
		for (int n : new int[] { 256, 512 }) {
			java.util.Random rnd = new java.util.Random(42);
			float[] a = new float[n * n], b = new float[n * n];
			double[] ad = new double[n * n], bd = new double[n * n];
			for (int i = 0; i < a.length; i++) {
				a[i] = (float) (rnd.nextDouble() - 0.5);
				b[i] = (float) (rnd.nextDouble() - 0.5);
				ad[i] = a[i];
				bd[i] = b[i];
			}
			float[] ref = scalarAcc(a, b, n, n, n);
			float[] w = laneAccF2D(a, b, n, n, n);
			float[] f = laneAccF32(a, b, n, n, n);
			boolean exactW = java.util.Arrays.equals(ref, w), exactF = java.util.Arrays.equals(ref, f);
			int ulps = 0;
			double relmax = 0;
			for (int q = 0; q < ref.length; q++) {
				ulps = Math.max(ulps, Math.abs(Float.floatToIntBits(ref[q]) - Float.floatToIntBits(f[q])));
				if (ref[q] != 0) relmax = Math.max(relmax, Math.abs((ref[q] - f[q]) / ref[q]));
			}
			System.out.printf("      laneF32 vs oracle: max %d ulp, max rel %.3g%n", ulps, relmax);
			System.out.printf("n=%d  scalarAcc %6.2f ms | laneF2D %6.2f ms (%s) | laneF32 %6.2f ms (%s) | f64 %6.2f ms%n",
					n, time(() -> scalarAcc(a, b, n, n, n)), time(() -> laneAccF2D(a, b, n, n, n)),
					exactW ? "bit-identical" : "DIFFERS", time(() -> laneAccF32(a, b, n, n, n)),
					exactF ? "bit-identical" : "DIFFERS", time(() -> f64(ad, bd, n, n, n)));
		}
	}

	static double time(Runnable r) {
		for (int i = 0; i < 5; i++) {
			r.run();
		}
		double best = Double.MAX_VALUE;
		for (int i = 0; i < 15; i++) {
			long t = System.nanoTime();
			r.run();
			best = Math.min(best, (System.nanoTime() - t) / 1e6);
		}
		return best;
	}
}
