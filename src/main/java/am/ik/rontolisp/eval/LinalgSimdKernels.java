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
 * would need {@code FloatVector.convert(F2D)}, the one operation todo-106 removed.</li>
 * </ul>
 *
 * The REDUCTIONS follow the todo-106 contract shared by every {@code --simd} backend: a
 * single-float reduction accumulates in single precision and promotes to {@code f64}
 * once, at the value boundary; a double-float reduction differs from the oracle only by
 * summation associativity. {@link #trace} is the exception -- it reads elements widened
 * to {@code double} and accumulates in {@code double} at both widths, exactly as the
 * oracle does, so it is bit-identical. {@link #amax} / {@link #amin} / {@link #argmax} /
 * {@link #argmin} are plain scalar loops over Java's IEEE {@code >} / {@code <}, the
 * oracle's own comparison since the todo-108 group-A fix, so they agree on ties, on
 * {@code -0.0} and on {@code NaN} as well.
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

	// --- element-wise unary ufuncs (todo 109) ----------------------------------------
	// linalg:exp / log / tanh / sqrt / abs / negative / sign are named emaps, so their
	// oracle is the emap rule: read widened to double, apply the operator, narrow on
	// store. The loops are DELEGATED to VecSimdKernels' -into forms (one implementation
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
	 * including its comparison semantics. Since the todo-108 group-A fix, rontolisp's
	 * {@code >} on two floats IS the IEEE {@code >} (a {@code 0.0}/{@code -0.0} tie keeps
	 * the earlier element, {@code NaN} never wins), so plain Java {@code >} is the
	 * defun's own comparison. A lane {@code MAX} reduce would diverge on those edges (and
	 * would have to work around the zero padding of a partial lane group) -- these stay
	 * scalar loops.
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
	 * both more accurate and free of the {@code convert(F2D)} todo-106 removed. Only the
	 * accumulator row is {@code double}; the operands and the result stay {@code float}.
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

}
