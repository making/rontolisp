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
 * {@code SPECIES_PREFERRED} for the element-wise kernels, same {@code SPECIES_128} pin on
 * the single-float reductions, same {@code THRESHOLD}, same two-rounding mul-then-add in
 * the reductions (deliberately not {@code fma}), same single-precision accumulation for
 * the single-float reductions, same f64-then-narrow {@code scale}. Interpreter
 * {@code --simd} therefore produces bit-identical results to a compiled {@code .class
 * --simd}, and both differ from the scalar {@code vec.lisp} oracle only by reduction
 * associativity (and, at single width, by the accumulator width -- see below). The
 * duplication is deliberate: {@code eval} may not depend on {@code codegen.jvm} (the
 * package dependency rule), and the compiled template's kernels are written against the
 * header-in-array representation anyway.
 *
 * <p>
 * This class is the ONLY part of the interpreter that references the incubator Vector
 * API, and {@link VecSimd} is its only caller. Loading it fails with a
 * {@link NoClassDefFoundError} on a JVM without {@code --add-modules
 * jdk.incubator.vector} (the {@link #laneCount()} probe turns that into a graceful scalar
 * fallback), and the browser Web Image build cuts it out by substituting
 * {@link VecSimd}'s two methods. Keep it free of any other rontolisp reference so those
 * two boundaries stay simple -- {@link SimdParallel}, the {@code --parallel} row split
 * the GEMV kernels call with the flag on, holds no Vector API and is the one exception.
 */
final class VecSimdKernels {

	private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

	private static final VectorSpecies<Float> FSPECIES = FloatVector.SPECIES_PREFERRED;

	/**
	 * The species the single-float REDUCTIONS use, pinned to four lanes on every host.
	 * They accumulate in {@code float}, so their result depends on the lane count: a
	 * {@code SPECIES_PREFERRED} of 8 or 16 lanes would make {@code (vec:dot v v)} answer
	 * differently on an AVX2 or AVX-512 machine than on a 128-bit one. The WASM
	 * {@code --simd} kernels are always {@code f32x4}, so pinning here is what lets every
	 * {@code --simd} backend agree, and keeps a compiled {@code .class} / native binary
	 * host-independent. The element-wise kernels stay on {@code SPECIES_PREFERRED}: they
	 * are bit-exact at any width.
	 */
	private static final VectorSpecies<Float> FSPECIES_REDUCE = FloatVector.SPECIES_128;

	/**
	 * Below this element count the Vector API setup cost outweighs the lane parallelism,
	 * so a plain scalar loop runs instead. Purely a performance gate -- the same value
	 * the compiled bridge uses, so the two agree even on the reduction rounding.
	 */
	private static final int THRESHOLD = 128;

	/**
	 * The GEMV row-length gate, mirroring
	 * {@code JvmSimdVectorTemplate.MATVEC_ROW_THRESHOLD}.
	 */
	private static final int MATVEC_ROW_THRESHOLD = 16;

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

	static double[] div(double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).div(DoubleVector.fromArray(SPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] / y[i];
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
	 * @param parallel {@code --parallel}: split the rows across threads when the call is
	 * worth it ({@link SimdParallel}); the same row chains, so the same bits
	 * @return a fresh vector of length {@code rows}
	 */
	static double[] matvec(double[] w, int rows, int cols, double[] x, boolean parallel) {
		double[] r = new double[rows];
		matvecInto(r, w, rows, cols, x, parallel);
		return r;
	}

	/**
	 * Rows {@code [from, to)} of the f64 GEMV, one lane chain per row ({@link #dot}'s
	 * two-rounding mul-then-add, the scalar tail in index order): a row's bits depend on
	 * nothing but the row, which is what lets {@code --parallel} split them.
	 */
	private static void matvecRows(double[] r, double[] w, int cols, double[] x, int from, int to) {
		for (int row = from; row < to; row++) {
			int base = row * cols;
			int i = 0;
			double acc = 0.0;
			if (cols >= MATVEC_ROW_THRESHOLD) {
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
	}

	// --- element-wise unary ufuncs -------------------------------------------------
	// Each writes op(x[i]) into r[i]; the allocating wrappers in VecSimd pass a fresh r,
	// the -into ones the caller's (r MAY alias x -- element i depends only on element i,
	// the add-into rule). sqrt / abs / neg / 1-over-x have lane forms bit-identical to
	// the scalar defun (sqrt and div are correctly rounded, abs and neg exact, so the
	// f32 widen-compute-narrow round trip is exact); exp / log / tanh / sin / cos / tan
	// / asin / acos / atan / sinh / cosh / signum have NO bit-safe lane form
	// (VectorOperators.EXP etc. are not
	// bit-identical to Math.exp), so they stay de-boxed scalar loops calling the same
	// java.lang.Math the interpreter defun does.

	static void expInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.exp(x[i]);
		}
	}

	static void expIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.exp(x[i]);
		}
	}

	static void logInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.log(x[i]);
		}
	}

	static void logIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.log(x[i]);
		}
	}

	static void tanhInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.tanh(x[i]);
		}
	}

	static void tanhIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.tanh(x[i]);
		}
	}

	static void sinInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.sin(x[i]);
		}
	}

	static void sinIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.sin(x[i]);
		}
	}

	static void cosInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.cos(x[i]);
		}
	}

	static void cosIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.cos(x[i]);
		}
	}

	static void tanInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.tan(x[i]);
		}
	}

	static void tanIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.tan(x[i]);
		}
	}

	static void asinInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.asin(x[i]);
		}
	}

	static void asinIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.asin(x[i]);
		}
	}

	static void acosInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.acos(x[i]);
		}
	}

	static void acosIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.acos(x[i]);
		}
	}

	static void atanInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.atan(x[i]);
		}
	}

	static void atanIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.atan(x[i]);
		}
	}

	static void sinhInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.sinh(x[i]);
		}
	}

	static void sinhIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.sinh(x[i]);
		}
	}

	static void coshInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.cosh(x[i]);
		}
	}

	static void coshIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.cosh(x[i]);
		}
	}

	static void sqrtInto(double[] r, double[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).lanewise(VectorOperators.SQRT).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = Math.sqrt(x[i]);
		}
	}

	static void sqrtIntoF(float[] r, float[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).lanewise(VectorOperators.SQRT).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = (float) Math.sqrt(x[i]);
		}
	}

	static void absInto(double[] r, double[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).abs().intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = Math.abs(x[i]);
		}
	}

	static void absIntoF(float[] r, float[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).abs().intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = Math.abs(x[i]);
		}
	}

	static void negInto(double[] r, double[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).neg().intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = -x[i];
		}
	}

	static void negIntoF(float[] r, float[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).neg().intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = -x[i];
		}
	}

	static void signInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = Math.signum(x[i]);
		}
	}

	static void signIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) Math.signum((double) x[i]);
		}
	}

	static void reciprocalInto(double[] r, double[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.broadcast(SPECIES, 1.0).div(DoubleVector.fromArray(SPECIES, x, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = 1.0 / x[i];
		}
	}

	static void reciprocalIntoF(float[] r, float[] x) {
		int n = x.length;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.broadcast(FSPECIES, 1.0f).div(FloatVector.fromArray(FSPECIES, x, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = 1.0f / x[i];
		}
	}

	// --- comparison-select ufuncs ---------------------------------------------------
	// maximum / minimum / relu / clip mirror the vec.lisp comparison selects
	// ((if (> x y) x y) and its mirrors), NOT Math.max / Math.min -- whose NaN and
	// -0.0 handling differ (Math.max propagates a NaN from either side and orders
	// -0.0 below 0.0, where the select keeps the SECOND operand on any false
	// comparison). A float compare equals the widened double compare (widening is
	// exact and order-preserving) and a select only copies input bits, so the f32
	// loops compare natively and stay bit-identical to the widen-compute-narrow
	// oracle; clip compares against the FULL double bounds (the array-vs-scalar
	// rule), narrowing the selected value once. Plain scalar loops throughout: a
	// select's bits do not depend on lane grouping, so lane forms would buy speed
	// only, and the JIT vectorizes a branchless select fine.

	static double[] maximum(double[] x, double[] y) {
		double[] r = new double[Math.min(x.length, y.length)];
		maximumInto(r, x, y);
		return r;
	}

	static double[] minimum(double[] x, double[] y) {
		double[] r = new double[Math.min(x.length, y.length)];
		minimumInto(r, x, y);
		return r;
	}

	static float[] maximumF(float[] x, float[] y) {
		float[] r = new float[Math.min(x.length, y.length)];
		maximumIntoF(r, x, y);
		return r;
	}

	static float[] minimumF(float[] x, float[] y) {
		float[] r = new float[Math.min(x.length, y.length)];
		minimumIntoF(r, x, y);
		return r;
	}

	static void maximumInto(double[] r, double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		for (int i = 0; i < n; i++) {
			r[i] = x[i] > y[i] ? x[i] : y[i];
		}
	}

	static void minimumInto(double[] r, double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		for (int i = 0; i < n; i++) {
			r[i] = x[i] < y[i] ? x[i] : y[i];
		}
	}

	static void maximumIntoF(float[] r, float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		for (int i = 0; i < n; i++) {
			r[i] = x[i] > y[i] ? x[i] : y[i];
		}
	}

	static void minimumIntoF(float[] r, float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		for (int i = 0; i < n; i++) {
			r[i] = x[i] < y[i] ? x[i] : y[i];
		}
	}

	static void reluInto(double[] r, double[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = x[i] > 0.0 ? x[i] : 0.0;
		}
	}

	static void reluIntoF(float[] r, float[] x) {
		for (int i = 0; i < x.length; i++) {
			r[i] = x[i] > 0.0f ? x[i] : 0.0f;
		}
	}

	static void clipInto(double[] r, double[] x, double lo, double hi) {
		for (int i = 0; i < x.length; i++) {
			double t = x[i] > lo ? x[i] : lo;
			r[i] = t < hi ? t : hi;
		}
	}

	static void clipIntoF(float[] r, float[] x, double lo, double hi) {
		for (int i = 0; i < x.length; i++) {
			double xd = x[i];
			double t = xd > lo ? xd : lo;
			r[i] = (float) (t < hi ? t : hi);
		}
	}

	// --- destination-passing kernels (write into r, allocate nothing) -------------
	// The -into siblings. Lane logic identical to the allocating kernels
	// above, only the destination differs, so results stay bit-identical. r may alias x
	// and/or y in the element-wise kernels (within one lane block the reads precede the
	// store at the same indices); matvecInto may not -- VecSimd guards that.

	static void addInto(double[] r, double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
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
	}

	static void subInto(double[] r, double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
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
	}

	static void mulInto(double[] r, double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
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
	}

	static void divInto(double[] r, double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).div(DoubleVector.fromArray(SPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] / y[i];
		}
	}

	static void scaleInto(double[] r, double[] x, double s) {
		int n = x.length;
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
	}

	static void matvecInto(double[] r, double[] w, int rows, int cols, double[] x, boolean parallel) {
		if (parallel && SimdParallel.worth(rows, cols)) {
			SimdParallel.rows(rows, cols, (from, to) -> matvecRows(r, w, cols, x, from, to));
		}
		else {
			matvecRows(r, w, cols, x, 0, rows);
		}
	}

	static void addIntoF(float[] r, float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
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
	}

	static void subIntoF(float[] r, float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
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
	}

	static void mulIntoF(float[] r, float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
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
	}

	static void divIntoF(float[] r, float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).div(FloatVector.fromArray(FSPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] / y[i];
		}
	}

	/**
	 * Scalar, like {@link #scaleF}: the f64 intermediate defeats a native f32 lane mul.
	 */
	static void scaleIntoF(float[] r, float[] x, double s) {
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) (x[i] * s);
		}
	}

	static void matvecIntoF(float[] r, float[] w, int rows, int cols, float[] x, boolean parallel) {
		if (parallel && SimdParallel.worth(rows, cols)) {
			SimdParallel.rows(rows, cols, (from, to) -> matvecRowsF(r, w, cols, x, from, to));
		}
		else {
			matvecRowsF(r, w, cols, x, 0, rows);
		}
	}

	/**
	 * {@link #matvecRows} at single width: the {@link #dotF} chain, four lanes, f32
	 * accumulator.
	 */
	private static void matvecRowsF(float[] r, float[] w, int cols, float[] x, int from, int to) {
		for (int row = from; row < to; row++) {
			int base = row * cols;
			int i = 0;
			float acc = 0.0f;
			if (cols >= MATVEC_ROW_THRESHOLD) {
				FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
				int bound = FSPECIES_REDUCE.loopBound(cols);
				for (; i < bound; i += FSPECIES_REDUCE.length()) {
					vacc = vacc.add(FloatVector.fromArray(FSPECIES_REDUCE, w, base + i)
						.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i)));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < cols; i++) {
				acc += w[base + i] * x[i];
			}
			r[row] = acc;
		}
	}

	// --- single-float (f32) kernels ----------------------------------------------
	// Element-wise ops run natively in f32 (a single +/-/* of two floats has no
	// double-rounding error, so they stay bit-identical to the widen-compute-narrow
	// scalar reference); the reductions compute entirely in f32 and promote once, at the
	// value boundary; and scale multiplies by the genuine f64 scalar before narrowing.

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

	static float[] divF(float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		float[] r = new float[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, i).div(FloatVector.fromArray(FSPECIES, y, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] / y[i];
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

	/**
	 * {@code sum(x)} accumulated in {@code float} (four pinned lanes plus a {@code float}
	 * tail) and promoted to f64 once, on return. See {@link #dotF} for why.
	 */
	static double sumF(float[] x) {
		int n = x.length;
		int i = 0;
		float acc = 0.0f;
		if (n >= THRESHOLD) {
			FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
			int bound = FSPECIES_REDUCE.loopBound(n);
			for (; i < bound; i += FSPECIES_REDUCE.length()) {
				vacc = vacc.add(FloatVector.fromArray(FSPECIES_REDUCE, x, i));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[i];
		}
		return acc;
	}

	/**
	 * {@code dot(x, y)} multiplied and accumulated entirely in {@code float}, promoted to
	 * f64 once at the value boundary -- the contract the WASM {@code --simd} kernels
	 * already follow ("each width computes in its own native precision").
	 *
	 * <p>
	 * The obvious alternative, widening each lane to f64 first so the reduction is
	 * bit-identical to the f64-accumulating scalar {@code vec.lisp} reference, costs a
	 * {@code FloatVector.convert(F2D, part)} per lane group. That conversion is the one
	 * Vector API operation a JIT is most likely to leave un-intrinsified, dropping the
	 * whole loop to per-lane emulation (measured: {@code #f} {@code vec:dot} ~140x slower
	 * than {@code #d} on one compiler family), and it is never free even where it IS
	 * intrinsified. It also bought a bit-identity the WASM backends never honoured. So a
	 * single-float reduction under {@code --simd} accumulates in single precision on
	 * every backend, and the scalar reference stays the more accurate oracle.
	 */
	static double dotF(float[] x, float[] y) {
		int n = Math.min(x.length, y.length);
		int i = 0;
		float acc = 0.0f;
		if (n >= THRESHOLD) {
			FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
			int bound = FSPECIES_REDUCE.loopBound(n);
			for (; i < bound; i += FSPECIES_REDUCE.length()) {
				vacc = vacc.add(
						FloatVector.fromArray(FSPECIES_REDUCE, x, i).mul(FloatVector.fromArray(FSPECIES_REDUCE, y, i)));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[i] * y[i];
		}
		return acc;
	}

	/** {@link #matvec} for a single-float matrix: {@link #dotF} once per row. */
	static float[] matvecF(float[] w, int rows, int cols, float[] x, boolean parallel) {
		float[] r = new float[rows];
		matvecIntoF(r, w, rows, cols, x, parallel);
		return r;
	}

}
