package am.ik.rontolisp.eval;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
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
	 * Sums a {@code DoubleVector}'s lanes in ascending index order, as a scalar {@code +}
	 * chain. {@code reduceLanes(ADD)} is NOT a substitute: the JDK is free to fold a
	 * floating-point {@code ADD} reduction in whatever order the hardware reduces
	 * fastest, and that order can change mid-run when a hotter compilation tier replaces
	 * a colder one -- see {@code .kb/vec.md} ("the fold order IS the value"). A manual
	 * lane walk uses only scalar {@code double} addition, whose order the JLS pins
	 * regardless of compilation tier.
	 */
	private static double sumLanes(DoubleVector v) {
		double s = 0.0;
		for (int lane = 0; lane < v.length(); lane++) {
			s += v.lane(lane);
		}
		return s;
	}

	/** {@link #sumLanes(DoubleVector)}, at single width. */
	private static float sumLanesF(FloatVector v) {
		float s = 0.0f;
		for (int lane = 0; lane < v.length(); lane++) {
			s += v.lane(lane);
		}
		return s;
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
			acc = sumLanes(vacc);
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
			acc = sumLanes(vacc);
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
				acc = sumLanes(vacc);
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
				acc = sumLanesF(vacc);
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
			acc = sumLanesF(vacc);
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
			acc = sumLanesF(vacc);
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

	// --- bfloat16 (bf16) fused kernels ----------------------------------------------
	// A bf16 value IS the top half of an f32 value, so widening it is EXACT: `bits <<
	// 16`,
	// no rounding, no range clamp, NaN payloads carried through unchanged. That is what
	// lets these kernels be defined by an equivalence rather than a tolerance -- each one
	// computes, bit for bit, what the f32 kernel above computes over the widened array.
	// They therefore repeat the f32 chain exactly: FSPECIES_REDUCE's four pinned lanes,
	// one f32 accumulator, the two-rounding mul-then-add (deliberately not `fma`), the
	// same THRESHOLD / MATVEC_ROW_THRESHOLD gates, the same scalar tail in index order.
	//
	// Fusing the decode into the lane loop, rather than widening into an f32 scratch and
	// reusing the f32 kernel, is the whole point: half the weight bytes for a decode that
	// is one shift, so the kernel is bandwidth-bound where the f32 one is. The scratch
	// route stores and reloads every element and loses on both JITs (.todo/488).
	//
	// One small method per width, and no decoder shared behind a flag: the probe that
	// carried both a bf16 and an f16 decoder in one method overran C2's inlining budget
	// for the Vector API chain, boxed every vector, and ran at 0.20x of f32 while running
	// at 1.5-2.1x under Graal on the identical arithmetic. Keep these methods small, and
	// take every number under both JITs.
	//
	// The operands are BARE short[] arrays with no dimension header -- the packed bf16
	// array type does not exist yet, so these are standalone kernels; the `--simd` /
	// `--parallel` interception binds to them once it does.

	/**
	 * The short species the bf16 decode loads: four lanes, matching
	 * {@link #FSPECIES_REDUCE}'s lane count, so one decoded group lines up with exactly
	 * one f32 lane group and the accumulation order stays the f32 kernel's. Pinned for
	 * the same reason {@code FSPECIES_REDUCE} is -- a wider species would change the lane
	 * count an f32 accumulator's value depends on.
	 */
	private static final VectorSpecies<Short> SSPECIES_BF16 = ShortVector.SPECIES_64;

	/** The int species the decode widens into: four lanes, 128 bits. */
	private static final VectorSpecies<Integer> ISPECIES_BF16 = IntVector.SPECIES_128;

	/**
	 * Widens four bf16 elements at {@code off} into four floats. {@code convertShape} --
	 * NOT {@code convert}, which preserves the vector SHAPE and would yield a two-lane
	 * int vector out of a 64-bit short vector -- reshapes the 64-bit short vector into a
	 * 128-bit int one; the left shift by 16 then puts each pattern in the high half of
	 * its int lane, which reinterpreted as a float IS the value the bf16 pattern denotes.
	 */
	private static FloatVector widenBf16(short[] w, int off) {
		return ((IntVector) ShortVector.fromArray(SSPECIES_BF16, w, off)
			.convertShape(VectorOperators.S2I, ISPECIES_BF16, 0)).lanewise(VectorOperators.LSHL, 16)
			.reinterpretAsFloats();
	}

	/**
	 * The scalar widening, the definition every kernel here is equivalent to. Exact: a
	 * bf16 pattern is an f32 pattern with its low 23 - 7 = 16 mantissa bits zero, so no
	 * value, infinity or NaN payload is lost or invented.
	 * @param bits the bf16 bit pattern
	 * @return the f32 value it denotes
	 */
	static float bf16ToFloat(short bits) {
		return Float.intBitsToFloat(bits << 16);
	}

	/**
	 * The scalar narrowing, rounded to nearest with ties to even -- {@code >>> 16} alone
	 * truncates towards zero, which biases every sum it feeds downwards. The tie-break
	 * adds the retained low bit, so a value exactly halfway between two bf16 patterns
	 * lands on the one with an even mantissa.
	 *
	 * <p>
	 * A NaN takes the guarded arm: rounding an f32 NaN whose surviving mantissa bits are
	 * all zero would carry into the exponent and answer an INFINITY, so the pattern is
	 * truncated and its quiet bit set instead. Every NaN therefore narrows to a NaN.
	 * @param value the f32 value
	 * @return its bf16 bit pattern
	 */
	static short floatToBf16(float value) {
		int bits = Float.floatToRawIntBits(value);
		if ((bits & 0x7f800000) == 0x7f800000 && (bits & 0x007fffff) != 0) {
			return (short) ((bits >>> 16) | 0x0040);
		}
		return (short) ((bits + 0x7fff + ((bits >>> 16) & 1)) >>> 16);
	}

	/**
	 * Widens {@code w} into {@code r} element for element -- the "widen-then-f32-kernel"
	 * route the fused kernels below are pinned equal to, and the bulk conversion a
	 * checkpoint loader wants. Bit-identical to a {@link #bf16ToFloat} loop at any lane
	 * count, the shift being exact.
	 */
	static void widenBf16Into(float[] r, short[] w) {
		int n = Math.min(r.length, w.length);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SSPECIES_BF16.loopBound(n);
			for (; i < bound; i += SSPECIES_BF16.length()) {
				widenBf16(w, i).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = bf16ToFloat(w[i]);
		}
	}

	/** Narrows {@code x} into {@code r} element for element, {@link #floatToBf16}. */
	static void narrowBf16Into(short[] r, float[] x) {
		int n = Math.min(r.length, x.length);
		for (int i = 0; i < n; i++) {
			r[i] = floatToBf16(x[i]);
		}
	}

	/**
	 * {@link #sumF} over a bf16 vector: the identical four-lane f32 accumulation over the
	 * decoded lanes, promoted to f64 once on return.
	 * @return {@code sumF(widen(x))}, bit for bit
	 */
	static double sumBf16(short[] x) {
		int n = x.length;
		int i = 0;
		float acc = 0.0f;
		if (n >= THRESHOLD) {
			FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
			int bound = FSPECIES_REDUCE.loopBound(n);
			for (; i < bound; i += FSPECIES_REDUCE.length()) {
				vacc = vacc.add(widenBf16(x, i));
			}
			acc = sumLanesF(vacc);
		}
		for (; i < n; i++) {
			acc += bf16ToFloat(x[i]);
		}
		return acc;
	}

	/**
	 * {@link #dotF} with the first operand bf16 and the second f32 -- the shape a decode
	 * step runs, weights narrow and activations not.
	 * @return {@code dotF(widen(w), x)}, bit for bit
	 */
	static double dotBf16(short[] w, float[] x) {
		int n = Math.min(w.length, x.length);
		int i = 0;
		float acc = 0.0f;
		if (n >= THRESHOLD) {
			FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
			int bound = FSPECIES_REDUCE.loopBound(n);
			for (; i < bound; i += FSPECIES_REDUCE.length()) {
				vacc = vacc.add(widenBf16(w, i).mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i)));
			}
			acc = sumLanesF(vacc);
		}
		for (; i < n; i++) {
			acc += bf16ToFloat(w[i]) * x[i];
		}
		return acc;
	}

	/**
	 * {@link #matvecRowsF} over a bf16 matrix: one {@link #dotBf16} chain per row, so a
	 * row's bits still depend on nothing but the row and {@code --parallel} may split
	 * them exactly as it splits the f32 rows.
	 */
	private static void matvecRowsBf16(float[] r, short[] w, int cols, float[] x, int from, int to) {
		for (int row = from; row < to; row++) {
			int base = row * cols;
			int i = 0;
			float acc = 0.0f;
			if (cols >= MATVEC_ROW_THRESHOLD) {
				FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
				int bound = FSPECIES_REDUCE.loopBound(cols);
				for (; i < bound; i += FSPECIES_REDUCE.length()) {
					vacc = vacc.add(widenBf16(w, base + i).mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i)));
				}
				acc = sumLanesF(vacc);
			}
			for (; i < cols; i++) {
				acc += bf16ToFloat(w[base + i]) * x[i];
			}
			r[row] = acc;
		}
	}

	/** {@link #matvecIntoF} over a bf16 matrix and an f32 vector, into an f32 result. */
	static void matvecIntoBf16(float[] r, short[] w, int rows, int cols, float[] x, boolean parallel) {
		if (parallel && SimdParallel.worth(rows, cols)) {
			SimdParallel.rows(rows, cols, (from, to) -> matvecRowsBf16(r, w, cols, x, from, to));
		}
		else {
			matvecRowsBf16(r, w, cols, x, 0, rows);
		}
	}

	/** {@link #matvecF} over a bf16 matrix: {@link #dotBf16} once per row. */
	static float[] matvecBf16(short[] w, int rows, int cols, float[] x, boolean parallel) {
		float[] r = new float[rows];
		matvecIntoBf16(r, w, rows, cols, x, parallel);
		return r;
	}

}
