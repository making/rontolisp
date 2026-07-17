package am.ik.rontolisp.eval;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * The lane loops behind the interpreter's opt-in {@code --simd} acceleration of the
 * {@code linalg:} kernels, the sibling of {@link VecSimdKernels}. Every method takes and
 * returns bare primitive arrays plus explicit dimensions: the interpreter's packed float
 * arrays ({@link am.ik.rontolisp.LispDoubleFloatArray} /
 * {@link am.ik.rontolisp.LispSingleFloatArray}) keep their dimensions beside the backing
 * store, so {@link LinalgSimd} passes {@code data()} straight through (zero copy in, a
 * fresh backing out).
 *
 * <p>
 * The kernels {@code linalg:} shares with {@code vec:} -- element-wise
 * {@code add}/{@code sub}/{@code mul}, {@code sum}, {@code dot} and GEMV -- are DELEGATED
 * to {@link VecSimdKernels} rather than copied, so the two packages cannot drift. Routing
 * them through this class (instead of letting {@link LinalgSimd} call
 * {@code VecSimdKernels} directly) keeps one kernel entry point per interceptor, which is
 * what makes {@code Target_LinalgSimd} sufficient to cut the incubator Vector API out of
 * the browser Web Image.
 *
 * <h2>The precision contract</h2>
 *
 * Element-wise results are <strong>bit-identical</strong> to the scalar {@code
 * linalg.lisp} oracle at both widths:
 * <ul>
 * <li>{@code array (+) array} at single width computes natively in {@code float}. That is
 * exact: {@code f64} carries 53 bits and a {@code float} has 24, so
 * {@code 53 >= 2 * 24 + 2} and the oracle's widen-compute-narrow round trip yields the
 * correctly rounded {@code float} for {@code +}, {@code -}, {@code *} and {@code /} alike
 * -- the classic innocuous-double-rounding bound.</li>
 * <li>{@code array (+) scalar} at single width does NOT enjoy that bound (the scalar is a
 * full {@code double}), so those kernels compute in {@code double} and narrow once, like
 * {@link VecSimdKernels#scaleF}. They stay scalar loops: widening a {@code float} lane
 * would need {@code FloatVector.convert(F2D)}, the one operation these kernels avoid
 * everywhere (it is the widening a JIT is least likely to intrinsify, and it costs more
 * than the lanes it feeds).</li>
 * </ul>
 *
 * The REDUCTIONS follow the contract shared by every {@code --simd} backend: a
 * single-float reduction accumulates in single precision and promotes to {@code f64}
 * once, at the value boundary; a double-float reduction differs from the oracle only by
 * summation associativity. {@link #trace} is the exception -- it reads elements widened
 * to {@code double} and accumulates in {@code double} at both widths, exactly as the
 * oracle does, so it is bit-identical. {@link #amax} / {@link #amin} / {@link #argmax} /
 * {@link #argmin} are plain scalar loops over Java's IEEE {@code >} / {@code <}, which is
 * the oracle's own comparison now that the interpreter no longer compares floats in a
 * total order, so they agree on ties, on {@code -0.0} and on {@code NaN} as well.
 *
 * <p>
 * {@link LinalgSimd} is this class's only caller. Keep it free of any other rontolisp
 * reference so that boundary stays simple.
 */
final class LinalgSimdKernels {

	private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

	private static final VectorSpecies<Float> FSPECIES = FloatVector.SPECIES_PREFERRED;

	/** Mirrors {@code VecSimdKernels.THRESHOLD}: below this the scalar loop wins. */
	private static final int THRESHOLD = 128;

	private LinalgSimdKernels() {
	}

	/**
	 * Links the Vector API. Used only as an availability probe, like
	 * {@code VecSimdKernels.laneCount()}.
	 * @return the preferred {@code double} lane count ({@code >= 1})
	 */
	static int laneCount() {
		return SPECIES.length();
	}

	// --- element-wise: array (+) array --------------------------------------------
	// add/sub/mul are exactly vec:'s kernels over the same flat backing; only div is new
	// (vec: has no divide). Rank plays no part -- a rank-n element-wise op walks the flat
	// row-major store, which is why these are rank-agnostic in linalg.lisp too.

	static double[] add(double[] x, double[] y) {
		return VecSimdKernels.add(x, y);
	}

	static double[] sub(double[] x, double[] y) {
		return VecSimdKernels.sub(x, y);
	}

	static double[] mul(double[] x, double[] y) {
		return VecSimdKernels.mul(x, y);
	}

	static double[] div(double[] x, double[] y) {
		int n = x.length;
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

	static float[] addF(float[] x, float[] y) {
		return VecSimdKernels.addF(x, y);
	}

	static float[] subF(float[] x, float[] y) {
		return VecSimdKernels.subF(x, y);
	}

	static float[] mulF(float[] x, float[] y) {
		return VecSimdKernels.mulF(x, y);
	}

	static float[] divF(float[] x, float[] y) {
		int n = x.length;
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

	// --- element-wise: array (+) scalar (broadcast) --------------------------------
	// linalg.lisp routes a scalar operand through emap, whose element read WIDENS to
	// double, applies the operator in double, and narrows on store. The double kernels
	// reproduce that exactly with lane arithmetic; the float ones must not (see the class
	// comment), so they stay scalar widen-compute-narrow loops.

	static double[] addScalar(double[] x, double s) {
		int n = x.length;
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).add(s).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] + s;
		}
		return r;
	}

	static double[] subScalar(double[] x, double s) {
		int n = x.length;
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).sub(s).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] - s;
		}
		return r;
	}

	/** {@code s - x[i]}: the operand order linalg.lisp gives {@code (linalg:sub 1 a)}. */
	static double[] subFrom(double s, double[] x) {
		int n = x.length;
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.broadcast(SPECIES, s).sub(DoubleVector.fromArray(SPECIES, x, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = s - x[i];
		}
		return r;
	}

	static double[] mulScalar(double[] x, double s) {
		return VecSimdKernels.scale(x, s);
	}

	static double[] divScalar(double[] x, double s) {
		int n = x.length;
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, i).div(s).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = x[i] / s;
		}
		return r;
	}

	/** {@code s / x[i]}. */
	static double[] divFrom(double s, double[] x) {
		int n = x.length;
		double[] r = new double[n];
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.broadcast(SPECIES, s).div(DoubleVector.fromArray(SPECIES, x, i)).intoArray(r, i);
			}
		}
		for (; i < n; i++) {
			r[i] = s / x[i];
		}
		return r;
	}

	static float[] addScalarF(float[] x, double s) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) (x[i] + s);
		}
		return r;
	}

	static float[] subScalarF(float[] x, double s) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) (x[i] - s);
		}
		return r;
	}

	static float[] subFromF(double s, float[] x) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) (s - x[i]);
		}
		return r;
	}

	static float[] mulScalarF(float[] x, double s) {
		return VecSimdKernels.scaleF(x, s);
	}

	static float[] divScalarF(float[] x, double s) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) (x[i] / s);
		}
		return r;
	}

	static float[] divFromF(double s, float[] x) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) (s / x[i]);
		}
		return r;
	}

	// --- element-wise comparison selects: maximum / minimum -------------------------
	// linalg:maximum / linalg:minimum are %la-bcast over (if (> x y) x y) and its
	// mirror, so the kernels mirror the comparison select, never Math.max/Math.min
	// (different NaN / -0.0 handling). The array-array loops are vec:'s; the scalar
	// broadcasts compare the widened element against the FULL double scalar and narrow
	// the selected value once, exactly as emap does (the same array-vs-scalar rule the
	// arithmetic broadcasts follow). linalg:clip and linalg:relu need no kernel: their
	// defuns compose linalg:maximum / linalg:minimum, which are already intercepted
	// (the square / reciprocal pattern).

	static double[] maximum(double[] x, double[] y) {
		return VecSimdKernels.maximum(x, y);
	}

	static double[] minimum(double[] x, double[] y) {
		return VecSimdKernels.minimum(x, y);
	}

	static float[] maximumF(float[] x, float[] y) {
		return VecSimdKernels.maximumF(x, y);
	}

	static float[] minimumF(float[] x, float[] y) {
		return VecSimdKernels.minimumF(x, y);
	}

	static double[] maxScalar(double[] x, double s) {
		double[] r = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = x[i] > s ? x[i] : s;
		}
		return r;
	}

	static double[] minScalar(double[] x, double s) {
		double[] r = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = x[i] < s ? x[i] : s;
		}
		return r;
	}

	/**
	 * {@code (if (> s x[i]) s x[i])}: the operand order {@code (linalg:maximum 3.0 a)}
	 * gives.
	 */
	static double[] maxFrom(double s, double[] x) {
		double[] r = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = s > x[i] ? s : x[i];
		}
		return r;
	}

	static double[] minFrom(double s, double[] x) {
		double[] r = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = s < x[i] ? s : x[i];
		}
		return r;
	}

	static float[] maxScalarF(float[] x, double s) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			double xd = x[i];
			r[i] = (float) (xd > s ? xd : s);
		}
		return r;
	}

	static float[] minScalarF(float[] x, double s) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			double xd = x[i];
			r[i] = (float) (xd < s ? xd : s);
		}
		return r;
	}

	static float[] maxFromF(double s, float[] x) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			double xd = x[i];
			r[i] = (float) (s > xd ? s : xd);
		}
		return r;
	}

	static float[] minFromF(double s, float[] x) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			double xd = x[i];
			r[i] = (float) (s < xd ? s : xd);
		}
		return r;
	}

	// --- element-wise unary ufuncs ---------------------------------------------------
	// linalg:exp / log / tanh / sin / cos / tan / asin / acos / atan / sinh / cosh /
	// sqrt / abs / negative / sign are named
	// emaps, so their oracle is the emap rule: read widened to double, apply the
	// operator, narrow on store. The loops are DELEGATED to VecSimdKernels' -into forms
	// (one implementation
	// per operation); linalg:square and linalg:reciprocal need no kernel at all -- their
	// defuns call linalg:mul / linalg:div, which are already intercepted.

	static double[] exp(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.expInto(r, x);
		return r;
	}

	static float[] expF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.expIntoF(r, x);
		return r;
	}

	static double[] log(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.logInto(r, x);
		return r;
	}

	static float[] logF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.logIntoF(r, x);
		return r;
	}

	static double[] tanh(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.tanhInto(r, x);
		return r;
	}

	static float[] tanhF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.tanhIntoF(r, x);
		return r;
	}

	static double[] sin(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.sinInto(r, x);
		return r;
	}

	static float[] sinF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.sinIntoF(r, x);
		return r;
	}

	static double[] cos(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.cosInto(r, x);
		return r;
	}

	static float[] cosF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.cosIntoF(r, x);
		return r;
	}

	static double[] tan(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.tanInto(r, x);
		return r;
	}

	static float[] tanF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.tanIntoF(r, x);
		return r;
	}

	static double[] asin(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.asinInto(r, x);
		return r;
	}

	static float[] asinF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.asinIntoF(r, x);
		return r;
	}

	static double[] acos(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.acosInto(r, x);
		return r;
	}

	static float[] acosF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.acosIntoF(r, x);
		return r;
	}

	static double[] atan(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.atanInto(r, x);
		return r;
	}

	static float[] atanF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.atanIntoF(r, x);
		return r;
	}

	static double[] sinh(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.sinhInto(r, x);
		return r;
	}

	static float[] sinhF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.sinhIntoF(r, x);
		return r;
	}

	static double[] cosh(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.coshInto(r, x);
		return r;
	}

	static float[] coshF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.coshIntoF(r, x);
		return r;
	}

	static double[] sqrt(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.sqrtInto(r, x);
		return r;
	}

	static float[] sqrtF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.sqrtIntoF(r, x);
		return r;
	}

	static double[] abs(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.absInto(r, x);
		return r;
	}

	static float[] absF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.absIntoF(r, x);
		return r;
	}

	static double[] negative(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.negInto(r, x);
		return r;
	}

	static float[] negativeF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.negIntoF(r, x);
		return r;
	}

	static double[] sign(double[] x) {
		double[] r = new double[x.length];
		VecSimdKernels.signInto(r, x);
		return r;
	}

	static float[] signF(float[] x) {
		float[] r = new float[x.length];
		VecSimdKernels.signIntoF(r, x);
		return r;
	}

	// --- reductions ----------------------------------------------------------------

	static double sum(double[] x) {
		return VecSimdKernels.sum(x);
	}

	static double sumF(float[] x) {
		return VecSimdKernels.sumF(x);
	}

	/**
	 * {@code sqrt(dot(x, x))}, fused: the oracle spells it {@code (sqrt (sum (emap square
	 * a)))} and allocates an intermediate array, which this kernel never materializes.
	 */
	static double norm(double[] x) {
		return Math.sqrt(VecSimdKernels.dot(x, x));
	}

	static double normF(float[] x) {
		return Math.sqrt(VecSimdKernels.dotF(x, x));
	}

	/**
	 * The oracle's {@code best = a[0]; when (> x best) best = x} loop, verbatim --
	 * including its comparison semantics. rontolisp's {@code >} on two floats IS the IEEE
	 * {@code >}, not a total order (a {@code 0.0}/{@code -0.0} tie keeps the earlier
	 * element, {@code NaN} never wins), so plain Java {@code >} is the defun's own
	 * comparison. A lane {@code MAX} reduce would diverge on those edges (and would have
	 * to work around the zero padding of a partial lane group) -- these stay scalar
	 * loops.
	 */
	static double amax(double[] x) {
		double best = x[0];
		for (int i = 1; i < x.length; i++) {
			if (x[i] > best) {
				best = x[i];
			}
		}
		return best;
	}

	static double amin(double[] x) {
		double best = x[0];
		for (int i = 1; i < x.length; i++) {
			if (x[i] < best) {
				best = x[i];
			}
		}
		return best;
	}

	static double amaxF(float[] x) {
		double best = x[0];
		for (int i = 1; i < x.length; i++) {
			if (x[i] > best) {
				best = x[i];
			}
		}
		return best;
	}

	static double aminF(float[] x) {
		double best = x[0];
		for (int i = 1; i < x.length; i++) {
			if (x[i] < best) {
				best = x[i];
			}
		}
		return best;
	}

	static int argmax(double[] x) {
		double best = x[0];
		int bi = 0;
		for (int i = 1; i < x.length; i++) {
			if (x[i] > best) {
				best = x[i];
				bi = i;
			}
		}
		return bi;
	}

	static int argmin(double[] x) {
		double best = x[0];
		int bi = 0;
		for (int i = 1; i < x.length; i++) {
			if (x[i] < best) {
				best = x[i];
				bi = i;
			}
		}
		return bi;
	}

	static int argmaxF(float[] x) {
		double best = x[0];
		int bi = 0;
		for (int i = 1; i < x.length; i++) {
			if (x[i] > best) {
				best = x[i];
				bi = i;
			}
		}
		return bi;
	}

	static int argminF(float[] x) {
		double best = x[0];
		int bi = 0;
		for (int i = 1; i < x.length; i++) {
			if (x[i] < best) {
				best = x[i];
				bi = i;
			}
		}
		return bi;
	}

	/**
	 * The main-diagonal sum of an {@code n x n} matrix, accumulated in {@code double} at
	 * both widths -- the oracle reads each element widened and folds in {@code double},
	 * so this is bit-identical rather than merely close.
	 */
	static double trace(double[] m, int n) {
		double acc = 0.0;
		for (int i = 0; i < n; i++) {
			acc += m[i * n + i];
		}
		return acc;
	}

	static double traceF(float[] m, int n) {
		double acc = 0.0;
		for (int i = 0; i < n; i++) {
			acc += m[i * n + i];
		}
		return acc;
	}

	// --- products -------------------------------------------------------------------

	static double dot(double[] x, double[] y) {
		return VecSimdKernels.dot(x, y);
	}

	static double dotF(float[] x, float[] y) {
		return VecSimdKernels.dotF(x, y);
	}

	/** Matrix times column vector (GEMV): exactly {@code vec:matvec}'s kernel. */
	static double[] matvec(double[] w, int rows, int cols, double[] x) {
		return VecSimdKernels.matvec(w, rows, cols, x);
	}

	static float[] matvecF(float[] w, int rows, int cols, float[] x) {
		return VecSimdKernels.matvecF(w, rows, cols, x);
	}

	/**
	 * The {@code n x m} by {@code m x p} matrix product, in <strong>ikj</strong> order:
	 * for each output row, accumulate {@code a[i][k] * b[k][*]} over {@code k}, where
	 * {@code b[k][*]} is a CONTIGUOUS row. No transpose of {@code b} and no gather -- the
	 * naive {@code ijk} form the oracle uses reads {@code b[k][j]} with stride {@code p},
	 * which no lane loop can follow.
	 *
	 * <p>
	 * The rewrite is not just faster, it is bit-identical: {@code ikj} visits {@code k}
	 * in increasing order into the same accumulator cell, which is the oracle's own
	 * summation order. See {@link #matmulF} for why the single-float sibling accumulates
	 * in {@code double} rather than following the reduction contract.
	 *
	 * <p>
	 * {@code vector . matrix} is this kernel with {@code n = 1}.
	 */
	static double[] matmul(double[] a, double[] b, int n, int m, int p) {
		double[] r = new double[n * p];
		for (int i = 0; i < n; i++) {
			int ro = i * p;
			int ao = i * m;
			for (int k = 0; k < m; k++) {
				double s = a[ao + k];
				int bo = k * p;
				int j = 0;
				if (p >= THRESHOLD) {
					int bound = SPECIES.loopBound(p);
					for (; j < bound; j += SPECIES.length()) {
						DoubleVector.fromArray(SPECIES, r, ro + j)
							.add(DoubleVector.fromArray(SPECIES, b, bo + j).mul(s))
							.intoArray(r, ro + j);
					}
				}
				for (; j < p; j++) {
					r[ro + j] += b[bo + j] * s;
				}
			}
		}
		return r;
	}

	/**
	 * The single-float matrix product, accumulated in {@code double} and narrowed once
	 * per output element -- so it is bit-identical to the oracle, not merely close.
	 *
	 * <p>
	 * This is where the reduction contract stops applying. An {@code #f} reduction
	 * accumulates in single precision under {@code --simd} because its LANES ARE the
	 * summation axis ({@code dot}, {@code sum}, and GEMV's per-row dot). Here the lanes
	 * run across the output row (the {@code j} axis of {@code ikj}), which carries no
	 * summation, so the accumulator's width is free -- and the oracle's {@code double} is
	 * both more accurate and free of the {@code convert(F2D)} widening these kernels
	 * otherwise avoid. Only the accumulator row is {@code double}; the operands and the
	 * result stay {@code float}.
	 */
	static float[] matmulF(float[] a, float[] b, int n, int m, int p) {
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

	/** {@code out[i][j] = u[i] * v[j]}: one scaled copy of {@code v} per row. */
	static double[] outer(double[] u, double[] v) {
		int n = u.length;
		int m = v.length;
		double[] r = new double[n * m];
		for (int i = 0; i < n; i++) {
			double s = u[i];
			int ro = i * m;
			int j = 0;
			if (m >= THRESHOLD) {
				int bound = SPECIES.loopBound(m);
				for (; j < bound; j += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, v, j).mul(s).intoArray(r, ro + j);
				}
			}
			for (; j < m; j++) {
				r[ro + j] = v[j] * s;
			}
		}
		return r;
	}

	static float[] outerF(float[] u, float[] v) {
		int n = u.length;
		int m = v.length;
		float[] r = new float[n * m];
		for (int i = 0; i < n; i++) {
			float s = u[i];
			int ro = i * m;
			int j = 0;
			if (m >= THRESHOLD) {
				int bound = FSPECIES.loopBound(m);
				for (; j < bound; j += FSPECIES.length()) {
					FloatVector.fromArray(FSPECIES, v, j).mul(s).intoArray(r, ro + j);
				}
			}
			for (; j < m; j++) {
				r[ro + j] = v[j] * s;
			}
		}
		return r;
	}

	// --- shape ----------------------------------------------------------------------
	// A strided scatter (transpose) and a straight copy (reshape / flatten). Neither has
	// a
	// lane form worth writing; both exist to skip the interpreter's boxed element loop
	// and,
	// on the compiled backends, the packed-element accessor call per element.

	static double[] transpose(double[] a, int r, int c) {
		double[] m = new double[c * r];
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				m[j * r + i] = a[i * c + j];
			}
		}
		return m;
	}

	static float[] transposeF(float[] a, int r, int c) {
		float[] m = new float[c * r];
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				m[j * r + i] = a[i * c + j];
			}
		}
		return m;
	}

	static double[] copy(double[] a) {
		return a.clone();
	}

	static float[] copyF(float[] a) {
		return a.clone();
	}

	// --- CNN window unfolding: %la-im2col / %la-col2im -------------------------------
	// Pure index arithmetic mirroring the linalg.lisp defuns loop for loop -- no lanes,
	// just compiled loops in place of the interpreter's boxed do-loop tree walk (im2col
	// dominated the accelerated CNN runs: ~97% of ch07 train time under --simd). im2col
	// only copies elements, so it is trivially bit-identical; col2im accumulates two
	// same-width elements per store, and at f32 width a float add IS the defun's
	// widen-add-narrow round trip (the exact double sum of two floats narrows to the
	// correctly rounded float), so both stay bit-identical at both widths. The caller
	// guarantees fh/fw/stride positive, pad non-negative and both padded extents
	// (h + 2*pad - fh, w + 2*pad - fw) non-negative, so the defun's floor is plain
	// truncating division here.

	static double[] im2col(double[] x, int n, int c, int h, int w, int fh, int fw, int stride, int pad) {
		int oh = (h + 2 * pad - fh) / stride + 1;
		int ow = (w + 2 * pad - fw) / stride + 1;
		double[] out = new double[n * oh * ow * c * fh * fw];
		int dst = 0;
		for (int ni = 0; ni < n; ni++) {
			for (int yo = 0; yo < oh; yo++) {
				for (int xo = 0; xo < ow; xo++) {
					for (int ci = 0; ci < c; ci++) {
						for (int fy = 0; fy < fh; fy++) {
							int iy = yo * stride + fy - pad;
							if (iy >= 0 && iy < h) {
								int base = ((ni * c + ci) * h + iy) * w;
								int ix0 = xo * stride - pad;
								for (int fx = 0; fx < fw; fx++) {
									int ix = ix0 + fx;
									if (ix >= 0 && ix < w) {
										out[dst] = x[base + ix];
									}
									dst++;
								}
							}
							else {
								// The whole filter row fell in the padding: skip it.
								dst += fw;
							}
						}
					}
				}
			}
		}
		return out;
	}

	static float[] im2colF(float[] x, int n, int c, int h, int w, int fh, int fw, int stride, int pad) {
		int oh = (h + 2 * pad - fh) / stride + 1;
		int ow = (w + 2 * pad - fw) / stride + 1;
		float[] out = new float[n * oh * ow * c * fh * fw];
		int dst = 0;
		for (int ni = 0; ni < n; ni++) {
			for (int yo = 0; yo < oh; yo++) {
				for (int xo = 0; xo < ow; xo++) {
					for (int ci = 0; ci < c; ci++) {
						for (int fy = 0; fy < fh; fy++) {
							int iy = yo * stride + fy - pad;
							if (iy >= 0 && iy < h) {
								int base = ((ni * c + ci) * h + iy) * w;
								int ix0 = xo * stride - pad;
								for (int fx = 0; fx < fw; fx++) {
									int ix = ix0 + fx;
									if (ix >= 0 && ix < w) {
										out[dst] = x[base + ix];
									}
									dst++;
								}
							}
							else {
								dst += fw;
							}
						}
					}
				}
			}
		}
		return out;
	}

	static double[] col2im(double[] col, int n, int c, int h, int w, int fh, int fw, int stride, int pad) {
		int oh = (h + 2 * pad - fh) / stride + 1;
		int ow = (w + 2 * pad - fw) / stride + 1;
		double[] img = new double[n * c * h * w];
		int src = 0;
		for (int ni = 0; ni < n; ni++) {
			for (int yo = 0; yo < oh; yo++) {
				for (int xo = 0; xo < ow; xo++) {
					for (int ci = 0; ci < c; ci++) {
						for (int fy = 0; fy < fh; fy++) {
							int iy = yo * stride + fy - pad;
							if (iy >= 0 && iy < h) {
								int base = ((ni * c + ci) * h + iy) * w;
								int ix0 = xo * stride - pad;
								for (int fx = 0; fx < fw; fx++) {
									int ix = ix0 + fx;
									if (ix >= 0 && ix < w) {
										img[base + ix] += col[src];
									}
									src++;
								}
							}
							else {
								src += fw;
							}
						}
					}
				}
			}
		}
		return img;
	}

	static float[] col2imF(float[] col, int n, int c, int h, int w, int fh, int fw, int stride, int pad) {
		int oh = (h + 2 * pad - fh) / stride + 1;
		int ow = (w + 2 * pad - fw) / stride + 1;
		float[] img = new float[n * c * h * w];
		int src = 0;
		for (int ni = 0; ni < n; ni++) {
			for (int yo = 0; yo < oh; yo++) {
				for (int xo = 0; xo < ow; xo++) {
					for (int ci = 0; ci < c; ci++) {
						for (int fy = 0; fy < fh; fy++) {
							int iy = yo * stride + fy - pad;
							if (iy >= 0 && iy < h) {
								int base = ((ni * c + ci) * h + iy) * w;
								int ix0 = xo * stride - pad;
								for (int fx = 0; fx < fw; fx++) {
									int ix = ix0 + fx;
									if (ix >= 0 && ix < w) {
										img[base + ix] += col[src];
									}
									src++;
								}
							}
							else {
								src += fw;
							}
						}
					}
				}
			}
		}
		return img;
	}

	// --- declined shapes: broadcast / axes transpose / axis folds --------------------
	// Pure scalar loops mirroring the linalg.lisp defuns element for element -- no
	// lanes. Every element is read widened to double, the operation runs in double,
	// and only a store into a single-float result narrows -- the oracle's own
	// widen-compute-narrow round trip -- so all of these are bit-identical at both
	// widths, unlike the lane reductions. The odometer walks are %la-bcast-loop's.

	static final int BOP_ADD = 0;

	static final int BOP_SUB = 1;

	static final int BOP_MUL = 2;

	static final int BOP_DIV = 3;

	/** The strict select {@code (if (> x y) x y)}: the SECOND operand wins ties/NaN. */
	static final int BOP_MAX = 4;

	static final int BOP_MIN = 5;

	private static double applyBinary(int op, double a, double b) {
		return switch (op) {
			case BOP_ADD -> a + b;
			case BOP_SUB -> a - b;
			case BOP_MUL -> a * b;
			case BOP_DIV -> a / b;
			case BOP_MAX -> a > b ? a : b;
			default -> a < b ? a : b;
		};
	}

	/**
	 * Row-major strides of the dims-{@code d} operand aligned to the broadcast shape
	 * {@code od}, with 0 on every stretched axis (extent 1 or missing) so the odometer
	 * re-reads the same element across it -- {@code %la-bcast-strides} verbatim.
	 */
	private static int[] bcastStrides(int[] d, int[] od) {
		int[] s = new int[od.length];
		int acc = 1;
		for (int k = od.length - 1, i = d.length - 1; k >= 0; k--, i--) {
			int n = i >= 0 ? d[i] : 1;
			s[k] = n == 1 ? 0 : acc;
			acc *= n;
		}
		return s;
	}

	/**
	 * The general numpy broadcast walk of {@code %la-bcast-loop}: the output's flat
	 * row-major index advances by 1 while each operand's flat index follows its
	 * stride-0-padded strides through an odometer carry from the innermost axis out. The
	 * caller has already validated the broadcast shape {@code od}.
	 */
	static double[] bcast(int op, double[] x, int[] dx, double[] y, int[] dy, int[] od) {
		int rank = od.length;
		int[] sx = bcastStrides(dx, od);
		int[] sy = bcastStrides(dy, od);
		int total = 1;
		for (int d : od) {
			total *= d;
		}
		double[] out = new double[total];
		int[] idx = new int[rank];
		int ox = 0;
		int oy = 0;
		for (int k = 0; k < total; k++) {
			out[k] = applyBinary(op, x[ox], y[oy]);
			for (int a = rank - 1; a >= 0; a--) {
				idx[a]++;
				ox += sx[a];
				oy += sy[a];
				if (idx[a] < od[a]) {
					break;
				}
				idx[a] = 0;
				ox -= od[a] * sx[a];
				oy -= od[a] * sy[a];
			}
		}
		return out;
	}

	static float[] bcastF(int op, float[] x, int[] dx, float[] y, int[] dy, int[] od) {
		int rank = od.length;
		int[] sx = bcastStrides(dx, od);
		int[] sy = bcastStrides(dy, od);
		int total = 1;
		for (int d : od) {
			total *= d;
		}
		float[] out = new float[total];
		int[] idx = new int[rank];
		int ox = 0;
		int oy = 0;
		for (int k = 0; k < total; k++) {
			out[k] = (float) applyBinary(op, x[ox], y[oy]);
			for (int a = rank - 1; a >= 0; a--) {
				idx[a]++;
				ox += sx[a];
				oy += sy[a];
				if (idx[a] < od[a]) {
					break;
				}
				idx[a] = 0;
				ox -= od[a] * sx[a];
				oy -= od[a] * sy[a];
			}
		}
		return out;
	}

	/**
	 * The rank-n axis permutation of {@code %la-transpose-axes}: output axis {@code k}
	 * draws from input axis {@code axes[k]}, the source flat index following the permuted
	 * row-major strides through the same odometer walk. A pure copy, so trivially
	 * bit-identical. The caller has already validated the permutation.
	 */
	static double[] transposeAxes(double[] x, int[] dims, int[] axes) {
		int rank = dims.length;
		int[] strides = new int[rank];
		int acc = 1;
		for (int i = rank - 1; i >= 0; i--) {
			strides[i] = acc;
			acc *= dims[i];
		}
		int[] od = new int[rank];
		int[] os = new int[rank];
		for (int k = 0; k < rank; k++) {
			od[k] = dims[axes[k]];
			os[k] = strides[axes[k]];
		}
		double[] out = new double[x.length];
		int[] idx = new int[rank];
		int src = 0;
		for (int k = 0; k < out.length; k++) {
			out[k] = x[src];
			for (int a = rank - 1; a >= 0; a--) {
				idx[a]++;
				src += os[a];
				if (idx[a] < od[a]) {
					break;
				}
				idx[a] = 0;
				src -= od[a] * os[a];
			}
		}
		return out;
	}

	static float[] transposeAxesF(float[] x, int[] dims, int[] axes) {
		int rank = dims.length;
		int[] strides = new int[rank];
		int acc = 1;
		for (int i = rank - 1; i >= 0; i--) {
			strides[i] = acc;
			acc *= dims[i];
		}
		int[] od = new int[rank];
		int[] os = new int[rank];
		for (int k = 0; k < rank; k++) {
			od[k] = dims[axes[k]];
			os[k] = strides[axes[k]];
		}
		float[] out = new float[x.length];
		int[] idx = new int[rank];
		int src = 0;
		for (int k = 0; k < out.length; k++) {
			out[k] = x[src];
			for (int a = rank - 1; a >= 0; a--) {
				idx[a]++;
				src += os[a];
				if (idx[a] < od[a]) {
					break;
				}
				idx[a] = 0;
				src -= od[a] * os[a];
			}
		}
		return out;
	}

	/**
	 * The oracle's {@code %la-fold-axis} over the flat index
	 * {@code (o * axlen + j) * inner + i}. {@link #BOP_ADD} folds from the defun's
	 * {@code 0} seed with {@code j} from 0; {@link #BOP_MAX} / {@link #BOP_MIN} seed from
	 * the first element along the axis and fold the defun's {@code (if (> x acc) x acc)}
	 * -- the ACCUMULATOR wins ties/NaN, the opposite of the element-wise select -- with
	 * {@code j} from 1 (the caller declines an empty axis). Always accumulates in
	 * {@code double}: an axis fold is NOT a lane reduction, so the oracle's boxed double
	 * arithmetic is mirrored exactly at both widths and the result is bit-identical.
	 */
	static double[] foldAxis(int op, double[] x, int axlen, int outer, int inner) {
		double[] out = new double[outer * inner];
		for (int o = 0; o < outer; o++) {
			for (int i = 0; i < inner; i++) {
				int base = o * axlen * inner + i;
				double acc;
				int j0;
				if (op == BOP_ADD) {
					acc = 0.0;
					j0 = 0;
				}
				else {
					acc = x[base];
					j0 = 1;
				}
				for (int j = j0; j < axlen; j++) {
					double v = x[base + j * inner];
					if (op == BOP_ADD) {
						acc += v;
					}
					else if (op == BOP_MAX ? v > acc : v < acc) {
						acc = v;
					}
				}
				out[o * inner + i] = acc;
			}
		}
		return out;
	}

	static double[] foldAxisF(int op, float[] x, int axlen, int outer, int inner) {
		double[] out = new double[outer * inner];
		for (int o = 0; o < outer; o++) {
			for (int i = 0; i < inner; i++) {
				int base = o * axlen * inner + i;
				double acc;
				int j0;
				if (op == BOP_ADD) {
					acc = 0.0;
					j0 = 0;
				}
				else {
					acc = x[base];
					j0 = 1;
				}
				for (int j = j0; j < axlen; j++) {
					double v = x[base + j * inner];
					if (op == BOP_ADD) {
						acc += v;
					}
					else if (op == BOP_MAX ? v > acc : v < acc) {
						acc = v;
					}
				}
				out[o * inner + i] = acc;
			}
		}
		return out;
	}

	/**
	 * The oracle's {@code %la-argfold-axis}: the per-slice index of the first element
	 * winning the strict comparison along the axis. Returns the indices as doubles -- the
	 * defun fills a packed DOUBLE array of index values at any input width.
	 */
	static double[] argFoldAxis(boolean max, double[] x, int axlen, int outer, int inner) {
		double[] out = new double[outer * inner];
		for (int o = 0; o < outer; o++) {
			for (int i = 0; i < inner; i++) {
				int base = o * axlen * inner + i;
				double best = x[base];
				int bi = 0;
				for (int j = 1; j < axlen; j++) {
					double v = x[base + j * inner];
					if (max ? v > best : v < best) {
						best = v;
						bi = j;
					}
				}
				out[o * inner + i] = bi;
			}
		}
		return out;
	}

	static double[] argFoldAxisF(boolean max, float[] x, int axlen, int outer, int inner) {
		double[] out = new double[outer * inner];
		for (int o = 0; o < outer; o++) {
			for (int i = 0; i < inner; i++) {
				int base = o * axlen * inner + i;
				double best = x[base];
				int bi = 0;
				for (int j = 1; j < axlen; j++) {
					double v = x[base + j * inner];
					if (max ? v > best : v < best) {
						best = v;
						bi = j;
					}
				}
				out[o * inner + i] = bi;
			}
		}
		return out;
	}

}
