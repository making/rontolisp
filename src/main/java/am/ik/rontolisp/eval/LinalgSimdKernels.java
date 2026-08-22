package am.ik.rontolisp.eval;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import org.jspecify.annotations.Nullable;

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

	// --- the error function ----------------------------------------------------------
	//
	// linalg:erf is (linalg:emap #'%la-erf-1 a) and emap is never intercepted, so the
	// member itself is. The kernel is %la-erf-1's own arithmetic in the defun's exact
	// order, so it is BIT-IDENTICAL at both widths -- and deliberately SCALAR: the
	// per-element iteration count is data-dependent (it grows with x^2), so a lane loop
	// would have to run every lane to the maximum of its group's counts, and the win
	// here is escaping the tree-walk and the boxing, exactly as for %la-im2col. See
	// .kb/linalg-simd.md.

	/**
	 * {@code linalg::%la-erf-1} of one number, keeping the defun's order of operations
	 * (which is what makes the kernel bit-identical to it): the {@code |x| >= 6} short
	 * circuit, then the all-positive-term A&amp;S 7.1.6 series
	 * {@code term = term * 2x^2 / (2n+1)} broken at {@code term < 1e-17 * total} and
	 * capped at {@code n = 200}, then
	 * {@code 1.1283791670955126 * |x| * exp(-x^2) * total} with the sign applied last.
	 */
	static double erf1(double x) {
		double ax = Math.abs(x);
		if (ax >= 6.0) {
			return x < 0.0 ? -1.0 : 1.0;
		}
		double term = 1.0;
		double total = 1.0;
		double xx = 2.0 * ax * ax;
		for (int n = 1; n <= 200; n++) {
			term = term * xx / (2.0 * n + 1.0);
			total = total + term;
			if (term < 1.0e-17 * total) {
				break;
			}
		}
		double v = 1.1283791670955126 * ax * Math.exp(-(ax * ax)) * total;
		return x < 0.0 ? -v : v;
	}

	static double[] erf(double[] x) {
		double[] r = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = erf1(x[i]);
		}
		return r;
	}

	static float[] erfF(float[] x) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			// emap's rule (which the defun follows): read widened to double, compute in
			// DOUBLE, narrow only on the store. Accumulating the series in single would
			// be a silent cross-backend divergence.
			r[i] = (float) erf1(x[i]);
		}
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
	 * summation order. The single-float sibling ({@link #matmulF}) keeps the same
	 * {@code k} order, but folds it at single precision.
	 *
	 * <p>
	 * {@code vector . matrix} is this kernel with {@code n = 1}.
	 */
	static double[] matmul(double[] a, double[] b, int n, int m, int p) {
		double[] r = new double[n * p];
		matmulInto(a, 0, b, 0, r, 0, n, m, p);
		return r;
	}

	/**
	 * One {@code n x m} by {@code m x p} slab of the {@code ikj} loop, reading {@code a}
	 * at {@code oa}, {@code b} at {@code ob} and accumulating into {@code r} at
	 * {@code or}. The rank-2 product is the {@code oa = ob = or = 0} case and every batch
	 * of {@link #matmulNd} is one call, so there is exactly one lane loop.
	 */
	private static void matmulInto(double[] a, int oa, double[] b, int ob, double[] r, int or, int n, int m, int p) {
		for (int i = 0; i < n; i++) {
			int ro = or + i * p;
			int ao = oa + i * m;
			for (int k = 0; k < m; k++) {
				double s = a[ao + k];
				int bo = ob + k * p;
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
	}

	/**
	 * The single-float matrix product: the same {@code ikj} loop at {@code float} lane
	 * width, accumulating straight into the {@code float[]} result row.
	 *
	 * <p>
	 * This is a REDUCTION-CONTRACT kernel, not a bit-identical one. Every output cell
	 * still folds {@code k} in the oracle's ascending order, but at single precision, so
	 * the result is close to the scalar defun rather than equal to it -- and the defun
	 * cannot follow, because rontolisp has exactly one float type and it is {@code f64}.
	 * The lane count does not enter into it: the lanes run across {@code j}, which
	 * carries no summation, so the lane loop, the scalar tail and the wasm kernel all
	 * fold each cell identically.
	 *
	 * <p>
	 * A {@code double[]} accumulator row would be bit-identical -- it is what this kernel
	 * held before it had lanes -- but it forbids them: an f64 accumulator can only be fed
	 * by widening each f32 lane group through {@code FloatVector.convert(F2D)}, which
	 * loses on every architecture measured and has no intrinsic at all on aarch64, where
	 * it runs 190x slower than the scalar loop it would replace. The numbers, and the
	 * rerunnable probe behind them, are in {@code .kb/linalg-simd.md}.
	 */
	static float[] matmulF(float[] a, float[] b, int n, int m, int p) {
		float[] r = new float[n * p];
		matmulIntoF(a, 0, b, 0, r, 0, n, m, p);
		return r;
	}

	private static void matmulIntoF(float[] a, int oa, float[] b, int ob, float[] r, int or, int n, int m, int p) {
		for (int i = 0; i < n; i++) {
			int ro = or + i * p;
			int ao = oa + i * m;
			for (int k = 0; k < m; k++) {
				float s = a[ao + k];
				int bo = ob + k * p;
				int j = 0;
				if (p >= THRESHOLD) {
					int bound = FSPECIES.loopBound(p);
					for (; j < bound; j += FSPECIES.length()) {
						FloatVector.fromArray(FSPECIES, r, ro + j)
							.add(FloatVector.fromArray(FSPECIES, b, bo + j).mul(s))
							.intoArray(r, ro + j);
					}
				}
				for (; j < p; j++) {
					r[ro + j] += b[bo + j] * s;
				}
			}
		}
	}

	/**
	 * The STACKED matrix product ({@code linalg::%la-matmul-nd}): one {@link #matmulInto}
	 * slab per batch, the batch offsets advancing through the {@code %la-batch-strides}
	 * odometer (a broadcast leading axis has stride 0, so it simply re-reads the same
	 * slab). Every output cell therefore folds {@code k} exactly as a per-batch
	 * {@code linalg:dot} does -- the precision contract is {@code dot}'s, not the
	 * defun's.
	 * @param bd the broadcast batch shape, outermost first
	 * @param sa {@code a}'s batch strides, aligned to {@code bd}
	 * @param sb {@code b}'s batch strides, aligned to {@code bd}
	 */
	static double[] matmulNd(double[] a, double[] b, int[] bd, int[] sa, int[] sb, int n, int m, int p, int batches) {
		double[] r = new double[batches * n * p];
		int[] idx = new int[bd.length];
		int oa = 0;
		int ob = 0;
		for (int z = 0; z < batches; z++) {
			matmulInto(a, oa, b, ob, r, z * n * p, n, m, p);
			for (int ax = bd.length - 1; ax >= 0; ax--) {
				idx[ax]++;
				oa += sa[ax];
				ob += sb[ax];
				if (idx[ax] < bd[ax]) {
					break;
				}
				idx[ax] = 0;
				oa -= bd[ax] * sa[ax];
				ob -= bd[ax] * sb[ax];
			}
		}
		return r;
	}

	static float[] matmulNdF(float[] a, float[] b, int[] bd, int[] sa, int[] sb, int n, int m, int p, int batches) {
		float[] r = new float[batches * n * p];
		int[] idx = new int[bd.length];
		int oa = 0;
		int ob = 0;
		for (int z = 0; z < batches; z++) {
			matmulIntoF(a, oa, b, ob, r, z * n * p, n, m, p);
			for (int ax = bd.length - 1; ax >= 0; ax--) {
				idx[ax]++;
				oa += sa[ax];
				ob += sb[ax];
				if (idx[ax] < bd[ax]) {
					break;
				}
				idx[ax] = 0;
				oa -= bd[ax] * sa[ax];
				ob -= bd[ax] * sb[ax];
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

	// --- the fused optimizer update: %la-adam-step -----------------------------------
	//
	// Adam's rule over four aligned arrays, in the defun's own order of operations, so
	// it is BIT-IDENTICAL at both widths: every element is read widened to double, the
	// five multiplies, the sqrt and the divide run in double, and only the stores into a
	// single-float parameter or moment buffer narrow -- which is the widen-compute-narrow
	// round trip the boxed defun performs. Scalar by decision, like %la-im2col: the win
	// is escaping the tree-walk and the boxed double per element (31% of a --gpu --simd
	// training step), not the lanes.

	/**
	 * Adam's fused element-wise update in place over the parameter {@code x}, its
	 * gradient {@code g} and the two moment buffers {@code m} and {@code v}, with the
	 * rule packed into {@code ps} ({@code lr}, {@code lr*wd}, {@code wd}, {@code b1},
	 * {@code 1-b1}, {@code b2}, {@code 1-b2}, {@code eps}, {@code c1}, {@code c2},
	 * {@code mode}). {@code mode} is 0 for no weight decay, 1 for the COUPLED L2 term
	 * ({@code torch.optim.Adam}) and 2 for the DECOUPLED one ({@code torch.optim.AdamW}).
	 */
	static void adamStep(double[] x, double[] g, double[] m, double[] v, double[] ps) {
		double lr = ps[0], lrwd = ps[1], wd = ps[2], b1 = ps[3], omb1 = ps[4], b2 = ps[5], omb2 = ps[6], eps = ps[7],
				c1 = ps[8], c2 = ps[9];
		int mode = (int) ps[10];
		for (int k = 0; k < x.length; k++) {
			double x0 = x[k];
			double xv = mode == 2 ? x0 - lrwd * x0 : x0;
			double gv = mode == 1 ? g[k] + wd * x0 : g[k];
			double mk = b1 * m[k] + omb1 * gv;
			double vk = b2 * v[k] + omb2 * gv * gv;
			m[k] = mk;
			v[k] = vk;
			x[k] = xv - lr * (mk / c1) / (Math.sqrt(vk / c2) + eps);
		}
	}

	static void adamStepF(float[] x, float[] g, float[] m, float[] v, double[] ps) {
		double lr = ps[0], lrwd = ps[1], wd = ps[2], b1 = ps[3], omb1 = ps[4], b2 = ps[5], omb2 = ps[6], eps = ps[7],
				c1 = ps[8], c2 = ps[9];
		int mode = (int) ps[10];
		for (int k = 0; k < x.length; k++) {
			// The defun reads every element widened to double and narrows only on the
			// store, moment buffers included -- so mk / vk feed the parameter update at
			// FULL double width even though m[k] / v[k] keep the narrowed value.
			double x0 = x[k];
			double xv = mode == 2 ? x0 - lrwd * x0 : x0;
			double gv = mode == 1 ? g[k] + wd * x0 : g[k];
			double mk = b1 * m[k] + omb1 * gv;
			double vk = b2 * v[k] + omb2 * gv * gv;
			m[k] = (float) mk;
			v[k] = (float) vk;
			x[k] = (float) (xv - lr * (mk / c1) / (Math.sqrt(vk / c2) + eps));
		}
	}

	// --- the seeded generator: %la-rng-fill ------------------------------------------
	//
	// Wichmann-Hill, three multiplicative congruential states combined into one uniform
	// double, exactly as %la-rng-next spells it: three integer updates, three divides,
	// a left-associated sum and the frac by compares. Reproducing that operation for
	// operation is not optional -- linalg:seed promises one seed reproduces one sequence
	// on every backend, and the examples' expected output is pinned to it.
	//
	// The kernel keeps the states in int locals and writes the final one back, which is
	// where the win is: the defun boxed a double per draw and a fresh integer per state
	// update, twelve times per element for randn.

	/** The three multipliers and moduli of the generator ({@code %la-rng-next}). */
	private static final int RNG_A1 = 171, RNG_M1 = 30269;

	private static final int RNG_A2 = 172, RNG_M2 = 30307;

	private static final int RNG_A3 = 170, RNG_M3 = 30323;

	/**
	 * Fills {@code out} from the state {@code (s1, s2, s3)} and returns the state the
	 * generator ends on, as a fresh three-element vector. {@code mode} picks the element
	 * rule: 0 one uniform draw ({@code linalg:rand}), 1 the sum of twelve minus 6
	 * ({@code linalg:randn}), 2 {@code lo + span * draw} ({@code linalg:uniform}).
	 */
	static double[] rngFill(double[] out, int mode, double lo, double span, int s1, int s2, int s3) {
		int[] st = { s1, s2, s3 };
		for (int k = 0; k < out.length; k++) {
			out[k] = rngElement(mode, lo, span, st);
		}
		return new double[] { st[0], st[1], st[2] };
	}

	static double[] rngFillF(float[] out, int mode, double lo, double span, int s1, int s2, int s3) {
		int[] st = { s1, s2, s3 };
		for (int k = 0; k < out.length; k++) {
			// The defun narrows only on the store into a single-float array.
			out[k] = (float) rngElement(mode, lo, span, st);
		}
		return new double[] { st[0], st[1], st[2] };
	}

	private static double rngElement(int mode, double lo, double span, int[] st) {
		if (mode == 1) {
			// Irwin-Hall: twelve draws summed from a 0.0 seed, minus 6.
			double acc = 0.0;
			for (int j = 0; j < 12; j++) {
				acc = acc + rngNext(st);
			}
			return acc - 6.0;
		}
		if (mode == 0) {
			return rngNext(st);
		}
		return lo + span * rngNext(st);
	}

	/** {@code %la-rng-next}: the next uniform double in {@code [0, 1)}. */
	private static double rngNext(int[] st) {
		int s1 = RNG_A1 * st[0] % RNG_M1;
		int s2 = RNG_A2 * st[1] % RNG_M2;
		int s3 = RNG_A3 * st[2] % RNG_M3;
		st[0] = s1;
		st[1] = s2;
		st[2] = s3;
		double u = s1 / 30269.0 + s2 / 30307.0 + s3 / 30323.0;
		// frac(u) for u in [0, 3), by compares only -- the defun's own spelling.
		return u >= 2.0 ? u - 2.0 : (u >= 1.0 ? u - 1.0 : u);
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

	// The comparison MASKS -- linalg:greater / greater-equal / less / less-equal /
	// equal -- are %la-bcast over (if (> x y) 1 0) and its siblings: a 1.0 / 0.0 mask at
	// the first array operand's width. IEEE comparisons on the widened elements, exactly
	// the defun's, so the masks are bit-identical at both widths by construction (the
	// only values they hold are 1.0 and 0.0). The dropout mask
	// (linalg:greater (linalg:rand shape) p) is the shape that put them here.

	static final int BOP_GT = 6;

	static final int BOP_GE = 7;

	static final int BOP_LT = 8;

	static final int BOP_LE = 9;

	static final int BOP_EQ = 10;

	private static double applyBinary(int op, double a, double b) {
		return switch (op) {
			case BOP_ADD -> a + b;
			case BOP_SUB -> a - b;
			case BOP_MUL -> a * b;
			case BOP_DIV -> a / b;
			case BOP_MAX -> a > b ? a : b;
			case BOP_MIN -> a < b ? a : b;
			case BOP_GT -> a > b ? 1.0 : 0.0;
			case BOP_GE -> a >= b ? 1.0 : 0.0;
			case BOP_LT -> a < b ? 1.0 : 0.0;
			case BOP_LE -> a <= b ? 1.0 : 0.0;
			default -> a == b ? 1.0 : 0.0;
		};
	}

	/** The equal-shape comparison mask: {@code out[i] = (x[i] op y[i]) ? 1 : 0}. */
	static double[] compare(int op, double[] x, double[] y) {
		double[] r = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = applyBinary(op, x[i], y[i]);
		}
		return r;
	}

	static float[] compareF(int op, float[] x, float[] y) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) applyBinary(op, x[i], y[i]);
		}
		return r;
	}

	/** {@code (x[i] op s)}: the widened element against the FULL double scalar. */
	static double[] compareScalar(int op, double[] x, double s) {
		double[] r = new double[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = applyBinary(op, x[i], s);
		}
		return r;
	}

	static float[] compareScalarF(int op, float[] x, double s) {
		float[] r = new float[x.length];
		for (int i = 0; i < x.length; i++) {
			r[i] = (float) applyBinary(op, x[i], s);
		}
		return r;
	}

	/**
	 * {@code (s op y[i])}: the scalar on the LEFT, which matters for every op but equal.
	 */
	static double[] compareFrom(int op, double s, double[] y) {
		double[] r = new double[y.length];
		for (int i = 0; i < y.length; i++) {
			r[i] = applyBinary(op, s, y[i]);
		}
		return r;
	}

	static float[] compareFromF(int op, double s, float[] y) {
		float[] r = new float[y.length];
		for (int i = 0; i < y.length; i++) {
			r[i] = (float) applyBinary(op, s, y[i]);
		}
		return r;
	}

	/**
	 * Row-major strides of the dims-{@code d} operand aligned to the broadcast shape
	 * {@code od}, with 0 on every stretched axis (extent 1 or missing) so the odometer
	 * re-reads the same element across it -- {@code %la-bcast-strides} verbatim.
	 */
	static int[] bcastStrides(int[] d, int[] od) {
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

	// --- linalg:where, %la-gather-strided, take-rows and %la-scatter-rows -------------
	// Pure selects and copies, every one of them a scalar odometer walk or a slab copy:
	// no arithmetic but an IEEE `== 0` test and an add, so all four are bit-identical to
	// the defuns at both widths. What they remove is the boxed row-major-aref per element
	// -- in a --gpu --simd training step torch:masked-fill (where over a broadcast
	// mask) and the torch:cat / torch:index-select adjoints (slice, scatter-rows) were
	// a third of what was left once linalg: itself was on the device (.kb/gpu.md).

	/**
	 * {@code linalg:where} over operands already reduced to (array-or-null, scalar,
	 * per-output-axis strides): the element of {@code x} where the mask is non-zero, of
	 * {@code y} where it is zero, at the broadcast shape {@code od}, written at single
	 * width when {@code single}. An array operand is a raw {@code double[]} or
	 * {@code float[]} (read widened); a null one means the scalar beside it. The result
	 * is a {@code float[]} or a {@code double[]} of {@code od}'s element count.
	 */
	static Object where(@Nullable Object m, double ms, int @Nullable [] sm, @Nullable Object x, double xs,
			int @Nullable [] sx, @Nullable Object y, double ys, int @Nullable [] sy, int[] od, boolean single) {
		int rank = od.length;
		int total = 1;
		for (int d : od) {
			total *= d;
		}
		double[] md = m instanceof double[] v ? v : null, xd = x instanceof double[] v ? v : null,
				yd = y instanceof double[] v ? v : null;
		float[] mf = m instanceof float[] v ? v : null, xf = x instanceof float[] v ? v : null,
				yf = y instanceof float[] v ? v : null;
		int[] zero = new int[rank];
		int[] tm = sm != null ? sm : zero, tx = sx != null ? sx : zero, ty = sy != null ? sy : zero;
		int[] idx = new int[rank];
		int om = 0, ox = 0, oy = 0;
		// One of the two is the result and the other an empty sentinel, so the loop
		// branches on the flag and never on a null.
		float[] outF = single ? new float[total] : new float[0];
		double[] outD = single ? new double[0] : new double[total];
		for (int k = 0; k < total; k++) {
			double mv = md != null ? md[om] : (mf != null ? mf[om] : ms);
			double v = mv == 0.0 ? (yd != null ? yd[oy] : (yf != null ? yf[oy] : ys))
					: (xd != null ? xd[ox] : (xf != null ? xf[ox] : xs));
			if (single) {
				outF[k] = (float) v;
			}
			else {
				outD[k] = v;
			}
			for (int a = rank - 1; a >= 0; a--) {
				idx[a]++;
				om += tm[a];
				ox += tx[a];
				oy += ty[a];
				if (idx[a] < od[a]) {
					break;
				}
				idx[a] = 0;
				om -= od[a] * tm[a];
				ox -= od[a] * tx[a];
				oy -= od[a] * ty[a];
			}
		}
		return single ? outF : outD;
	}

	/**
	 * {@code %la-gather-strided}: a fresh {@code od}-shaped array (single width when
	 * {@code single}) filled by walking {@code a}'s flat index from {@code base} through
	 * the OUTERMOST-FIRST per-axis strides {@code s} -- the defun's innermost-first list
	 * reversed by the caller, who has also checked that every index the walk reaches is
	 * inside {@code a}.
	 */
	static Object gatherStrided(Object a, int[] od, int[] s, int base, boolean single) {
		int rank = od.length;
		int total = 1;
		for (int d : od) {
			total *= d;
		}
		double[] ad = a instanceof double[] v ? v : new double[0];
		float[] af = a instanceof float[] v ? v : new float[0];
		boolean wide = a instanceof double[];
		int[] idx = new int[rank];
		int src = base;
		float[] outF = single ? new float[total] : new float[0];
		double[] outD = single ? new double[0] : new double[total];
		for (int k = 0; k < total; k++) {
			double v = wide ? ad[src] : af[src];
			if (single) {
				outF[k] = (float) v;
			}
			else {
				outD[k] = v;
			}
			for (int x = rank - 1; x >= 0; x--) {
				idx[x]++;
				src += s[x];
				if (idx[x] < od[x]) {
					break;
				}
				idx[x] = 0;
				src -= od[x] * s[x];
			}
		}
		return single ? outF : outD;
	}

	/**
	 * {@code %la-sum-squares}: {@code acc + sum(v * v)} as a LEFT fold in double from
	 * {@code acc}, every element read widened -- the order {@code torch:clip-grad-norm}
	 * accumulates in, so this is byte identity and not a lane reduction.
	 */
	static double sumSquares(double[] g, double acc) {
		double total = acc;
		for (double v : g) {
			total = total + v * v;
		}
		return total;
	}

	static double sumSquares(float[] g, double acc) {
		double total = acc;
		for (float f : g) {
			double v = f;
			total = total + v * v;
		}
		return total;
	}

	/**
	 * {@code %la-scale}: {@code g[k] = g[k] * s} in place, narrowed only on an f32 store.
	 */
	static void scale(double[] g, double s) {
		for (int k = 0; k < g.length; k++) {
			g[k] = g[k] * s;
		}
	}

	static void scale(float[] g, double s) {
		for (int k = 0; k < g.length; k++) {
			g[k] = (float) ((double) g[k] * s);
		}
	}

	/**
	 * {@code linalg:take-rows}: slab {@code rows[i]} of {@code a} copied to slab
	 * {@code i}.
	 */
	static double[] takeRows(double[] a, int slab, int[] rows) {
		double[] out = new double[rows.length * slab];
		for (int i = 0; i < rows.length; i++) {
			System.arraycopy(a, rows[i] * slab, out, i * slab, slab);
		}
		return out;
	}

	static float[] takeRowsF(float[] a, int slab, int[] rows) {
		float[] out = new float[rows.length * slab];
		for (int i = 0; i < rows.length; i++) {
			System.arraycopy(a, rows[i] * slab, out, i * slab, slab);
		}
		return out;
	}

	/**
	 * {@code %la-scatter-rows}: slab {@code i} of {@code g} ADDED into slab
	 * {@code rows[i]} of {@code z}, in place -- {@code (+ z g)} on the widened elements,
	 * narrowed only on a single-float store, which is what the defun's
	 * {@code setf row-major-aref} does.
	 */
	static void scatterRows(double[] z, double[] g, int slab, int[] rows) {
		for (int i = 0; i < rows.length; i++) {
			int dst = rows[i] * slab, src = i * slab;
			for (int k = 0; k < slab; k++) {
				z[dst + k] = z[dst + k] + g[src + k];
			}
		}
	}

	static void scatterRowsF(float[] z, float[] g, int slab, int[] rows) {
		for (int i = 0; i < rows.length; i++) {
			int dst = rows[i] * slab, src = i * slab;
			for (int k = 0; k < slab; k++) {
				z[dst + k] = (float) ((double) z[dst + k] + (double) g[src + k]);
			}
		}
	}

}
