package am.ik.rontolisp.eval;

import jdk.incubator.vector.ByteVector;
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

	/**
	 * How many independent accumulators a long GEMV row folds into. Four beat two at
	 * every shape measured and eight lost to four at all of them (the register file and
	 * the loop's own bookkeeping), on both JITs. It is part of the CROSS-BACKEND
	 * bit-identity contract, not a tuning knob: the fold order is the value, so all four
	 * {@code --simd} implementations use this count and this tree.
	 */
	private static final int MATVEC_ACCUMULATORS = 4;

	/**
	 * Columns at or above which a GEMV row runs {@link #MATVEC_ACCUMULATORS} independent
	 * accumulators instead of one. One accumulator is one dependency chain -- four lanes
	 * per add of four-cycle latency, so ~1 multiply-add per cycle however wide the core
	 * issues -- which bounds a row well short of memory; independent chains lift it, at
	 * the cost of four zeroed vectors and a three-add fold per row.
	 *
	 * <p>
	 * It must be a pure function of the COLUMN COUNT and nothing else. An attention
	 * {@code V^T . att} is a GEMV whose columns are the sequence length, so one call site
	 * crosses this gate as generation proceeds -- and it may cross it only because the
	 * column count changed, never because of a row count, a call count or any other
	 * state, or the four {@code --simd} implementations stop agreeing bit for bit. Row
	 * counts are therefore not consulted even where they would predict better.
	 *
	 * <p>
	 * The gate is NOT a head-dimension question, which is how it was first framed:
	 * measured cleanly (2026-09-03, GB10) four accumulators win at every head dimension a
	 * real model uses -- 48 (stories15M) 1.21x under Graal / 1.15x under C2, 64
	 * (SmolLM2-135M, TinyLlama-1.1B, LFM2.5-1.2B) 1.23x / 1.26x, 128 (Qwen3-0.6B, and the
	 * Gated DeltaNet product) 1.27x / 1.52x, 256 (Qwen3.5-0.8B) 1.29x / 1.57x. An earlier
	 * probe reported a 0.48x regression at 48 columns; it dispatched the row kernel
	 * through a five-implementation interface ONCE PER ROW, so the call went megamorphic,
	 * the Vector API stopped being inlined, and the cost scaled with the number of live
	 * vectors -- it measured boxing, not the fold. The shipped kernel has its row loop
	 * inside one method and pays none of that.
	 *
	 * <p>
	 * What a gate is genuinely needed for is a row too short to fill the wide loop twice.
	 * At 24 columns -- one wide iteration plus two leftover lane groups -- the setup is
	 * most of the row and C2 measures 0.70x. So the gate is exactly two full wide
	 * iterations, {@code 2 * MATVEC_ACCUMULATORS * lanes} = 32, derived from the kernel's
	 * own shape rather than picked, and below every real head dimension so no model is
	 * left on the slow path. It sits above {@link #MATVEC_ROW_THRESHOLD}, so a row
	 * between the two gates runs the single chain it ran before.
	 */
	private static final int MATVEC_ACC_THRESHOLD = 2 * MATVEC_ACCUMULATORS * FSPECIES_REDUCE.length();

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
	 * Rows {@code [from, to)} of the f32 GEMV: four pinned lanes, an f32 accumulator, the
	 * two-rounding mul-then-add (deliberately not {@code fma} -- wasm SIMD has no
	 * deterministic fused multiply-add, so a kernel that needed one could not be mirrored
	 * there).
	 *
	 * <p>
	 * A row of {@link #MATVEC_ACC_THRESHOLD} columns or more folds into
	 * {@link #MATVEC_ACCUMULATORS} independent accumulators, summed pairwise
	 * ({@code (a0 + a1) + (a2 + a3)}) into the single accumulator that then takes the
	 * leftover lane groups and the scalar tail; a shorter row keeps the one chain. That
	 * order is the value, so it is repeated exactly in the other three {@code --simd}
	 * implementations -- see {@code .kb/vec.md}. Within a row the whole chain still
	 * depends on nothing but that row, which is what lets {@code --parallel} split them.
	 */
	private static void matvecRowsF(float[] r, float[] w, int cols, float[] x, int from, int to) {
		int lanes = FSPECIES_REDUCE.length();
		int wide = cols >= MATVEC_ACC_THRESHOLD ? cols / (MATVEC_ACCUMULATORS * lanes) * (MATVEC_ACCUMULATORS * lanes)
				: 0;
		int bound = FSPECIES_REDUCE.loopBound(cols);
		for (int row = from; row < to; row++) {
			int base = row * cols;
			int i = 0;
			float acc = 0.0f;
			if (cols >= MATVEC_ROW_THRESHOLD) {
				FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
				if (wide > 0) {
					FloatVector a0 = FloatVector.zero(FSPECIES_REDUCE);
					FloatVector a1 = a0;
					FloatVector a2 = a0;
					FloatVector a3 = a0;
					for (; i < wide; i += MATVEC_ACCUMULATORS * lanes) {
						a0 = a0.add(FloatVector.fromArray(FSPECIES_REDUCE, w, base + i)
							.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i)));
						a1 = a1.add(FloatVector.fromArray(FSPECIES_REDUCE, w, base + i + lanes)
							.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i + lanes)));
						a2 = a2.add(FloatVector.fromArray(FSPECIES_REDUCE, w, base + i + 2 * lanes)
							.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i + 2 * lanes)));
						a3 = a3.add(FloatVector.fromArray(FSPECIES_REDUCE, w, base + i + 3 * lanes)
							.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i + 3 * lanes)));
					}
					vacc = a0.add(a1).add(a2.add(a3));
				}
				for (; i < bound; i += lanes) {
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
		int lanes = FSPECIES_REDUCE.length();
		int wide = cols >= MATVEC_ACC_THRESHOLD ? cols / (MATVEC_ACCUMULATORS * lanes) * (MATVEC_ACCUMULATORS * lanes)
				: 0;
		int bound = FSPECIES_REDUCE.loopBound(cols);
		for (int row = from; row < to; row++) {
			int base = row * cols;
			int i = 0;
			float acc = 0.0f;
			if (cols >= MATVEC_ROW_THRESHOLD) {
				FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
				if (wide > 0) {
					FloatVector a0 = FloatVector.zero(FSPECIES_REDUCE);
					FloatVector a1 = a0;
					FloatVector a2 = a0;
					FloatVector a3 = a0;
					for (; i < wide; i += MATVEC_ACCUMULATORS * lanes) {
						a0 = a0.add(widenBf16(w, base + i).mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i)));
						a1 = a1.add(widenBf16(w, base + i + lanes)
							.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i + lanes)));
						a2 = a2.add(widenBf16(w, base + i + 2 * lanes)
							.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i + 2 * lanes)));
						a3 = a3.add(widenBf16(w, base + i + 3 * lanes)
							.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, i + 3 * lanes)));
					}
					vacc = a0.add(a1).add(a2.add(a3));
				}
				for (; i < bound; i += lanes) {
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

	// --- Q8_0 quantized-matrix GEMV: the integer dot ----------------------------------
	// ggml's Q8_0 x Q8_1 shape, runq.c's shape (.kb/quantized-matrix.md). The matrix is
	// the ggml block layout verbatim -- per block one binary16 scale then 32 int8 quants,
	// 34 bytes -- and the activation is quantized to int8 per block of 32 first (absmax /
	// 127 in DOUBLE, round half even, exactly what the vec.lisp defun writes). Per row
	// and block: B2S widen, short multiply, short add of the two halves (|2 x 128 x 127|
	// < 32767, which is why the activation is clipped to +-127 and never -128), S2I
	// widen, int add, ONE horizontal integer reduce, and ONE double multiply-add,
	// isum * (sw * sx), into a double accumulator; the store narrows to the result
	// width. An integer sum is exact in any order, so the lane fold and the defun's
	// scalar loop agree on isum by construction, and the only floating-point steps are
	// that product and that add, in the same order on both -- this kernel is the defun
	// BIT FOR BIT, a stronger contract than the f32 kernels' lane-count pin, and it
	// costs nothing: one double op per 32 elements. That is also why there is no
	// threshold and no accumulator split here: a block IS the unit, a row of one block
	// runs the same lanes, and independent int accumulators would not change a bit.
	//
	// No FMA in the double step, deliberately: `acc + isum * p` is two roundings on
	// both sides, and the defun has no fused form to mirror one with.

	/** The byte species one half-block loads: sixteen quants. */
	private static final VectorSpecies<Byte> BSPECIES_Q8 = ByteVector.SPECIES_128;

	/** The short species the products land in: eight lanes, 128 bits. */
	private static final VectorSpecies<Short> SSPECIES_Q8 = ShortVector.SPECIES_128;

	/** The int species the block sum lands in: four lanes, 128 bits. */
	private static final VectorSpecies<Integer> ISPECIES_Q8 = IntVector.SPECIES_128;

	/** ggml's Q8_0 block: 32 elements. */
	static final int Q8_BLOCK = 32;

	/** ggml's Q8_0 block bytes: a binary16 scale and 32 quants. */
	static final int Q8_BLOCK_BYTES = 34;

	/**
	 * The scale of the block at {@code off}: its binary16 {@code d}, widened (exact).
	 * @param w the blocks
	 * @param off the block's first byte
	 * @return the scale
	 */
	static double q8Scale(byte[] w, int off) {
		return Float.float16ToFloat((short) ((w[off] & 0xff) | (w[off + 1] << 8)));
	}

	/**
	 * The integer dot of one block's 32 weight quants at {@code wo} against the 32
	 * activation quants at {@code xo}. Exact.
	 */
	private static int q8BlockDot(byte[] w, int wo, byte[] xq, int xo) {
		ByteVector w0 = ByteVector.fromArray(BSPECIES_Q8, w, wo);
		ByteVector w1 = ByteVector.fromArray(BSPECIES_Q8, w, wo + 16);
		ByteVector x0 = ByteVector.fromArray(BSPECIES_Q8, xq, xo);
		ByteVector x1 = ByteVector.fromArray(BSPECIES_Q8, xq, xo + 16);
		ShortVector p = ((ShortVector) w0.convertShape(VectorOperators.B2S, SSPECIES_Q8, 0))
			.mul((ShortVector) x0.convertShape(VectorOperators.B2S, SSPECIES_Q8, 0))
			.add(((ShortVector) w0.convertShape(VectorOperators.B2S, SSPECIES_Q8, 1))
				.mul((ShortVector) x0.convertShape(VectorOperators.B2S, SSPECIES_Q8, 1)));
		ShortVector q = ((ShortVector) w1.convertShape(VectorOperators.B2S, SSPECIES_Q8, 0))
			.mul((ShortVector) x1.convertShape(VectorOperators.B2S, SSPECIES_Q8, 0))
			.add(((ShortVector) w1.convertShape(VectorOperators.B2S, SSPECIES_Q8, 1))
				.mul((ShortVector) x1.convertShape(VectorOperators.B2S, SSPECIES_Q8, 1)));
		IntVector sum = ((IntVector) p.convertShape(VectorOperators.S2I, ISPECIES_Q8, 0))
			.add((IntVector) p.convertShape(VectorOperators.S2I, ISPECIES_Q8, 1))
			.add((IntVector) q.convertShape(VectorOperators.S2I, ISPECIES_Q8, 0))
			.add((IntVector) q.convertShape(VectorOperators.S2I, ISPECIES_Q8, 1));
		return sum.reduceLanes(VectorOperators.ADD);
	}

	/**
	 * Quantizes {@code n} activations at {@code xo} to int8 per block of 32, the defun's
	 * rule exactly: {@code sx = amax / 127} in double, {@code q = rint(x / sx)} (CL's
	 * {@code round}, half to even), all zero where the block is. A NaN never raises
	 * {@code amax} (the compare is strict, like the defun's {@code >}).
	 */
	static void quantizeActivationF(float[] x, int xo, int n, byte[] xq, double[] xs) {
		for (int b = 0; b * Q8_BLOCK < n; b++) {
			int base = b * Q8_BLOCK;
			double amax = 0.0;
			for (int k = 0; k < Q8_BLOCK; k++) {
				double v = Math.abs((double) x[xo + base + k]);
				if (v > amax) {
					amax = v;
				}
			}
			double sx = amax / 127.0;
			xs[b] = sx;
			for (int k = 0; k < Q8_BLOCK; k++) {
				xq[base + k] = sx == 0.0 ? 0 : (byte) (int) Math.rint(x[xo + base + k] / sx);
			}
		}
	}

	/** {@link #quantizeActivationF} over a double vector. */
	static void quantizeActivationD(double[] x, int xo, int n, byte[] xq, double[] xs) {
		for (int b = 0; b * Q8_BLOCK < n; b++) {
			int base = b * Q8_BLOCK;
			double amax = 0.0;
			for (int k = 0; k < Q8_BLOCK; k++) {
				double v = Math.abs(x[xo + base + k]);
				if (v > amax) {
					amax = v;
				}
			}
			double sx = amax / 127.0;
			xs[b] = sx;
			for (int k = 0; k < Q8_BLOCK; k++) {
				xq[base + k] = sx == 0.0 ? 0 : (byte) (int) Math.rint(x[xo + base + k] / sx);
			}
		}
	}

	/**
	 * The row loop: the double accumulator of one row's blocks, narrowed on the store.
	 * The same rows whichever thread runs them, so {@code --parallel} splits them as it
	 * splits every GEMV.
	 */
	private static void matvecRowsQ8F(float[] r, byte[] w, int cols, byte[] xq, double[] xs, int from, int to) {
		int nb = cols / Q8_BLOCK;
		int rowBytes = nb * Q8_BLOCK_BYTES;
		for (int row = from; row < to; row++) {
			int base = row * rowBytes;
			double acc = 0.0;
			for (int b = 0; b < nb; b++) {
				int bo = base + b * Q8_BLOCK_BYTES;
				int isum = q8BlockDot(w, bo + 2, xq, b * Q8_BLOCK);
				acc = acc + isum * (q8Scale(w, bo) * xs[b]);
			}
			r[row] = (float) acc;
		}
	}

	/** {@link #matvecRowsQ8F} into a double result. */
	private static void matvecRowsQ8D(double[] r, byte[] w, int cols, byte[] xq, double[] xs, int from, int to) {
		int nb = cols / Q8_BLOCK;
		int rowBytes = nb * Q8_BLOCK_BYTES;
		for (int row = from; row < to; row++) {
			int base = row * rowBytes;
			double acc = 0.0;
			for (int b = 0; b < nb; b++) {
				int bo = base + b * Q8_BLOCK_BYTES;
				int isum = q8BlockDot(w, bo + 2, xq, b * Q8_BLOCK);
				acc = acc + isum * (q8Scale(w, bo) * xs[b]);
			}
			r[row] = acc;
		}
	}

	/**
	 * {@code vec:matvec-into} over a Q8_0 matrix ({@code rows x cols} blocks at
	 * {@code w}) and an f32 vector, into a bare f32 result.
	 */
	static void matvecIntoQ8F(float[] r, byte[] w, int rows, int cols, float[] x, boolean parallel) {
		byte[] xq = new byte[cols];
		double[] xs = new double[cols / Q8_BLOCK];
		quantizeActivationF(x, 0, cols, xq, xs);
		if (parallel && SimdParallel.worth(rows, cols)) {
			SimdParallel.rows(rows, cols, (from, to) -> matvecRowsQ8F(r, w, cols, xq, xs, from, to));
		}
		else {
			matvecRowsQ8F(r, w, cols, xq, xs, 0, rows);
		}
	}

	/** {@link #matvecIntoQ8F} over a double vector, into a bare double result. */
	static void matvecIntoQ8D(double[] r, byte[] w, int rows, int cols, double[] x, boolean parallel) {
		byte[] xq = new byte[cols];
		double[] xs = new double[cols / Q8_BLOCK];
		quantizeActivationD(x, 0, cols, xq, xs);
		if (parallel && SimdParallel.worth(rows, cols)) {
			SimdParallel.rows(rows, cols, (from, to) -> matvecRowsQ8D(r, w, cols, xq, xs, from, to));
		}
		else {
			matvecRowsQ8D(r, w, cols, xq, xs, 0, rows);
		}
	}

	/** {@code vec:matvec} over a Q8_0 matrix and an f32 vector: a fresh f32 result. */
	static float[] matvecQ8F(byte[] w, int rows, int cols, float[] x, boolean parallel) {
		float[] r = new float[rows];
		matvecIntoQ8F(r, w, rows, cols, x, parallel);
		return r;
	}

	/**
	 * {@code vec:matvec} over a Q8_0 matrix and a double vector: a fresh double result.
	 */
	static double[] matvecQ8D(byte[] w, int rows, int cols, double[] x, boolean parallel) {
		double[] r = new double[rows];
		matvecIntoQ8D(r, w, rows, cols, x, parallel);
		return r;
	}

}
