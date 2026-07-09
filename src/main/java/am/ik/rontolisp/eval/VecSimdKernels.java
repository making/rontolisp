package am.ik.rontolisp.eval;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The {@code jdk.incubator.vector} lane loops behind the interpreter's opt-in
 * {@code --simd} acceleration of the {@code vec:} kernels. Every method takes and returns
 * a bare primitive array -- the interpreter's packed float arrays
 * ({@link am.ik.rontolisp.LispDoubleFloatArray} /
 * {@link am.ik.rontolisp.LispSingleFloatArray}) store their elements without the
 * dimension header the compiled JVM representation carries, so the marshalling in
 * {@link VecSimd} passes {@code data()} straight through (zero copy in, a fresh backing
 * out).
 *
 * <p>
 * The lane logic mirrors the compiled {@code --simd} bridge
 * ({@code codegen.jvm.JvmSimdVectorTemplate}) operation for operation -- same
 * {@code SPECIES_PREFERRED}, same {@code THRESHOLD}, same two-rounding mul-then-add in
 * the reductions (deliberately not {@code fma}), same per-lane {@code F2D} widening for
 * the single-float reductions, same f64-then-narrow {@code scale}. Interpreter
 * {@code --simd} therefore produces bit-identical results to a compiled {@code .class
 * --simd}, and both differ from the scalar {@code vec.lisp} oracle only by reduction
 * associativity. The duplication is deliberate: {@code eval} may not depend on
 * {@code codegen.jvm} (the package dependency rule), and the compiled template's kernels
 * are written against the header-in-array representation anyway.
 *
 * <p>
 * This class is the ONLY part of the interpreter that references the incubator Vector
 * API, and {@link VecSimd} is its only caller. Loading it fails with a
 * {@link NoClassDefFoundError} on a JVM without {@code --add-modules
 * jdk.incubator.vector} (the {@link #laneCount()} probe turns that into a graceful scalar
 * fallback), and the browser Web Image build cuts it out by substituting
 * {@link VecSimd}'s two methods. Keep it free of any other rontolisp reference so those
 * two boundaries stay simple.
 */
final class VecSimdKernels {

	private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

	private static final VectorSpecies<Float> FSPECIES = FloatVector.SPECIES_PREFERRED;

	/**
	 * Below this element count the Vector API setup cost outweighs the lane parallelism,
	 * so a plain scalar loop runs instead. Purely a performance gate -- the same value
	 * the compiled bridge uses, so the two agree even on the reduction rounding.
	 */
	private static final int THRESHOLD = 128;

	private VecSimdKernels() {
	}

	/**
	 * Returns the preferred double lane count. Used only as an availability probe:
	 * touching this class links the Vector API, so a JVM without the incubator module
	 * raises {@link NoClassDefFoundError} here rather than deep inside a kernel.
	 * @return the number of {@code double} lanes in the preferred species ({@code >= 1})
	 */
	static int laneCount() {
		return SPECIES.length();
	}

	// --- double-float (f64) kernels ----------------------------------------------

	static double[] add(double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).add(DoubleVector.fromArray(SPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] + y[i];
		}
		return r;
	}

	static double[] sub(double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).sub(DoubleVector.fromArray(SPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] - y[i];
		}
		return r;
	}

	static double[] mul(double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).mul(DoubleVector.fromArray(SPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] * y[i];
		}
		return r;
	}

	static double[] scale(double[] x, double s) {
		int n = x.length;
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).mul(s).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] * s;
		}
		return r;
	}

	static double sum(double[] x) {
		int n = x.length;
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				vacc = vacc.add(DoubleVector.fromArray(SPECIES, x, i));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[i];
		}
		return acc;
	}

	static double dot(double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				// Two-rounding mul then add, matching the scalar reference; deliberately
				// not a single-rounding fma, so the only scalar-vs-vector divergence is
				// the reduction associativity.
				vacc = vacc.add(DoubleVector.fromArray(SPECIES, x, i).mul(DoubleVector.fromArray(SPECIES, y, i)));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[i] * y[i];
		}
		return acc;
	}

	/**
	 * GEMV over a row-major rank-2 matrix: {@code r[row] = dot(w row, x)}.
	 * @param w the flat row-major matrix elements ({@code rows * cols} of them)
	 * @param rows the row count (the length of the result)
	 * @param cols the column count (the length of {@code x})
	 * @param x the vector
	 * @return a fresh vector of length {@code rows}
	 */
	static double[] matvec(double[] w, int rows, int cols, double[] x) {
		double[] r = new double[rows];
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			int i = 0;
			double acc = 0.0;
			if (cols >= THRESHOLD) {
				DoubleVector vacc = DoubleVector.zero(SPECIES);
				int bound = SPECIES.loopBound(cols);
				for (; i < bound; i += SPECIES.length()) {
					vacc = vacc
						.add(DoubleVector.fromArray(SPECIES, w, base + i).mul(DoubleVector.fromArray(SPECIES, x, i)));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < cols; i++) {
				acc += w[base + i] * x[i];
			}
			r[row] = acc;
		}
		return r;
	}

	// --- single-float (f32) kernels ----------------------------------------------
	// Element-wise ops run natively in f32 (a single +/-/* of two floats has no
	// double-rounding error, so they stay bit-identical to the widen-compute-narrow
	// scalar reference); the reductions widen each lane to f64 and fold to an f64
	// scalar, and scale multiplies by the genuine f64 scalar before narrowing.

	static float[] addF(float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		float[] r = new float[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).add(FloatVector.fromArray(FSPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] + y[i];
		}
		return r;
	}

	static float[] subF(float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		float[] r = new float[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).sub(FloatVector.fromArray(FSPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] - y[i];
		}
		return r;
	}

	static float[] mulF(float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		float[] r = new float[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).mul(FloatVector.fromArray(FSPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] * y[i];
		}
		return r;
	}

	/**
	 * The f64 intermediate defeats a clean native-f32 lane multiply (a scalar not exactly
	 * representable in f32 would diverge from the scalar reference), so this stays a
	 * scalar loop -- as in the compiled bridge.
	 */
	static float[] scaleF(float[] x, double s) {
		int n = x.length;
		float[] r = new float[n];
		for (int i = 0; i < n; i++) {
			r[i] = (float) (x[i] * s);
		}
		return r;
	}

	static double sumF(float[] x) {
		int n = x.length;
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector fv = FloatVector.fromArray(FSPECIES, x, i);
				vacc = vacc.add((DoubleVector) fv.convert(VectorOperators.F2D, 0))
					.add((DoubleVector) fv.convert(VectorOperators.F2D, 1));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[i];
		}
		return acc;
	}

	static double dotF(float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector fx = FloatVector.fromArray(FSPECIES, x, i);
				FloatVector fy = FloatVector.fromArray(FSPECIES, y, i);
				DoubleVector x0 = (DoubleVector) fx.convert(VectorOperators.F2D, 0);
				DoubleVector x1 = (DoubleVector) fx.convert(VectorOperators.F2D, 1);
				DoubleVector y0 = (DoubleVector) fy.convert(VectorOperators.F2D, 0);
				DoubleVector y1 = (DoubleVector) fy.convert(VectorOperators.F2D, 1);
				vacc = vacc.add(x0.mul(y0)).add(x1.mul(y1));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += (double) x[i] * (double) y[i];
		}
		return acc;
	}

	/** {@link #matvec} for a single-float matrix: f64 accumulation, narrowed on store. */
	static float[] matvecF(float[] w, int rows, int cols, float[] x) {
		float[] r = new float[rows];
		for (int row = 0; row < rows; row++) {
			int base = row * cols;
			int i = 0;
			double acc = 0.0;
			if (cols >= THRESHOLD) {
				DoubleVector vacc = DoubleVector.zero(SPECIES);
				int bound = FSPECIES.loopBound(cols);
				for (; i < bound; i += FSPECIES.length()) {
					FloatVector fw = FloatVector.fromArray(FSPECIES, w, base + i);
					FloatVector fx = FloatVector.fromArray(FSPECIES, x, i);
					DoubleVector w0 = (DoubleVector) fw.convert(VectorOperators.F2D, 0);
					DoubleVector w1 = (DoubleVector) fw.convert(VectorOperators.F2D, 1);
					DoubleVector x0 = (DoubleVector) fx.convert(VectorOperators.F2D, 0);
					DoubleVector x1 = (DoubleVector) fx.convert(VectorOperators.F2D, 1);
					vacc = vacc.add(w0.mul(x0)).add(w1.mul(x1));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < cols; i++) {
				acc += (double) w[base + i] * (double) x[i];
			}
			r[row] = (float) acc;
		}
		return r;
	}

}
