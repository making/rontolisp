package am.ik.rontolisp.codegen.jvm;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import org.jspecify.annotations.Nullable;

/**
 * The {@code vec:} acceleration runtime injected into a compiled {@code .class} when the
 * {@code --simd} flag is passed. It reimplements the seven vectorizable {@code vec:}
 * kernels ({@code add}/{@code sub}/{@code mul}/{@code scale}/{@code dot}/{@code sum}/
 * {@code matvec}) against the compiled packed float-array representation using
 * {@code jdk.incubator.vector} so the JIT/native compiler can intrinsify the lane loop,
 * replacing the scalar {@code vec.lisp} reference at those call sites. {@code matvec} is
 * GEMV (matrix-by-vector): the vectorized dot product run once per row of a rank-2
 * matrix, amortizing the bridge entry over the whole matrix. {@code mean}/{@code norm}
 * are transitively accelerated because their spliced bodies call {@code sum}/{@code dot},
 * whose call sites are also intercepted.
 *
 * <p>
 * A simd vector is the ordinary rank-1 packed float array of the compiled runtime. It is
 * width-polymorphic: either a bare {@code double[]} (from {@code #d} / {@code make-array
 * :element-type 'double-float}) or a bare {@code float[]} (from {@code #f} /
 * {@code 'single-float}), each carrying an embedded dimension header {@code [rank, dim_0,
 * ..., dim_{rank-1}, e_0, ..., e_{total-1}]} (rank and dims stored in the backing element
 * type), so the elements start at {@code off = 1 + rank} (= 2 for a rank-1 vector; see
 * {@link JvmFloatArrayRuntimeBuilder}). Each kernel dispatches on the runtime backing
 * type: a {@code float[]} operand runs the {@code FloatVector} path and yields a fresh
 * {@code float[]} (width preserved, matching the scalar reference's
 * {@code (make-array n :element-type 'single-float)}); a {@code double[]} operand runs
 * the {@code DoubleVector} path and yields a {@code double[]}. Mixed-width operands are a
 * hard error -- {@code simd} is the fixed-contract, no-fallback package.
 *
 * <p>
 * Precision follows the {@code #f}/{@code #d} model: scalars stay f64. The element-wise
 * f32 kernels ({@code add}/{@code sub}/{@code mul}) run natively in f32 -- bit-identical
 * to the scalar reference's "widen each element to f64, compute, narrow back to f32"
 * because a single {@code +}/{@code -}/{@code *} of two floats has no double-rounding
 * error. {@code scale} multiplies by the f64 scalar in f64 then narrows (its scalar
 * argument is a genuine f64, so a native-f32 multiply would diverge for a non-f32-exact
 * scalar). The reductions ({@code sum}/{@code dot}) always produce a f64 scalar and
 * accumulate in f64 (an f32 operand is widened lane-by-lane via {@code F2D}), matching
 * the scalar oracle; the only scalar-vs-vector divergence is reduction associativity, so
 * the cross-backend tests use f64-exact (integer / power-of-two) inputs.
 *
 * <p>
 * Unboxing is zero-copy: each kernel reads directly from the backing array at the
 * header-shifted offset and writes the result straight into a fresh packed array (its own
 * {@code [1, n, ...]} header). A chained pipeline ({@code (vec:sum (vec:add a b))}) never
 * copies an input or an intermediate. Downstream {@code vec:aref}/{@code length}/ print
 * read the same packed shape through the header-aware {@code _fv*} helpers, so the
 * accelerated result is observationally identical to the scalar reference.
 *
 * <p>
 * Like {@link JavaBridgeTemplate} this class is never referenced by the rontolisp code
 * base at runtime: its compiled bytecode is read from the classpath by
 * {@link JvmSimdRuntimeBuilder}, renamed into the default package (a
 * {@code Lookup.defineClass} requirement), base64-embedded, and defined at first use by
 * the emitted {@code _simdInit} helper, so the output stays a single self-contained
 * {@code .class}. Because it references the incubator Vector API, running a compiled
 * program that uses {@code --simd} requires
 * {@code java --add-modules jdk.incubator.vector} on a JRE at least as new as the build
 * JRE (programs compiled without {@code --simd} keep running the scalar reference on any
 * Java 6+ JVM).
 *
 * <p>
 * Design constraints (as for {@link JavaBridgeTemplate}): no nested classes or records
 * (lambdas are fine) and no references to other rontolisp classes -- the bytes must stand
 * alone once embedded.
 */
final class JvmSimdVectorTemplate {

	private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

	private static final VectorSpecies<Float> FSPECIES = FloatVector.SPECIES_PREFERRED;

	/**
	 * Below this element count the Vector API setup cost outweighs the lane parallelism,
	 * so a plain scalar loop is used. Purely a performance gate -- both paths compute
	 * bit-identical results for the element-wise kernels, and identical results for the
	 * reductions on the f64-exact inputs the cross-backend tests use.
	 */
	private static final int THRESHOLD = 128;

	private JvmSimdVectorTemplate() {
	}

	// --- element-wise kernels (return a fresh packed simd vector) ----------------

	static @Nullable Object simdAdd(@Nullable Object a, @Nullable Object b) {
		if (a instanceof float[] fx) {
			return addF(fx, asFloat(b));
		}
		if (b instanceof float[]) {
			throw mixedWidth();
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] y = (double[]) java.util.Objects.requireNonNull(b);
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		double[] r = newVec(n);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i)
					.add(DoubleVector.fromArray(SPECIES, y, oy + i))
					.intoArray(r, 2 + i);
			}
		}
		for (; i < n; i++) {
			r[2 + i] = x[ox + i] + y[oy + i];
		}
		return r;
	}

	static @Nullable Object simdSub(@Nullable Object a, @Nullable Object b) {
		if (a instanceof float[] fx) {
			return subF(fx, asFloat(b));
		}
		if (b instanceof float[]) {
			throw mixedWidth();
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] y = (double[]) java.util.Objects.requireNonNull(b);
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		double[] r = newVec(n);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i)
					.sub(DoubleVector.fromArray(SPECIES, y, oy + i))
					.intoArray(r, 2 + i);
			}
		}
		for (; i < n; i++) {
			r[2 + i] = x[ox + i] - y[oy + i];
		}
		return r;
	}

	static @Nullable Object simdMul(@Nullable Object a, @Nullable Object b) {
		if (a instanceof float[] fx) {
			return mulF(fx, asFloat(b));
		}
		if (b instanceof float[]) {
			throw mixedWidth();
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] y = (double[]) java.util.Objects.requireNonNull(b);
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		double[] r = newVec(n);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i)
					.mul(DoubleVector.fromArray(SPECIES, y, oy + i))
					.intoArray(r, 2 + i);
			}
		}
		for (; i < n; i++) {
			r[2 + i] = x[ox + i] * y[oy + i];
		}
		return r;
	}

	static @Nullable Object simdScale(@Nullable Object v, @Nullable Object s) {
		if (v instanceof float[] fx) {
			return scaleF(fx, ((Number) java.util.Objects.requireNonNull(s)).doubleValue());
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(v);
		double scalar = ((Number) java.util.Objects.requireNonNull(s)).doubleValue();
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		double[] r = newVec(n);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i).mul(scalar).intoArray(r, 2 + i);
			}
		}
		for (; i < n; i++) {
			r[2 + i] = x[ox + i] * scalar;
		}
		return r;
	}

	// --- reductions (return a scalar Double) -------------------------------------

	static @Nullable Object simdSum(@Nullable Object v) {
		if (v instanceof float[] fx) {
			return sumF(fx);
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(v);
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				vacc = vacc.add(DoubleVector.fromArray(SPECIES, x, ox + i));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[ox + i];
		}
		return acc;
	}

	static @Nullable Object simdDot(@Nullable Object a, @Nullable Object b) {
		if (a instanceof float[] fx) {
			return dotF(fx, asFloat(b));
		}
		if (b instanceof float[]) {
			throw mixedWidth();
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] y = (double[]) java.util.Objects.requireNonNull(b);
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				// vacc += x * y (two-rounding mul then add, matching the scalar
				// reference; deliberately NOT a single-rounding fma so the only
				// scalar-vs-vector divergence is the reduction associativity).
				vacc = vacc
					.add(DoubleVector.fromArray(SPECIES, x, ox + i).mul(DoubleVector.fromArray(SPECIES, y, oy + i)));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[ox + i] * y[oy + i];
		}
		return acc;
	}

	/**
	 * {@code matvec(W, x)} -- GEMV: {@code W} is a rank-2 packed matrix
	 * {@code (d rows, n cols)} whose header is {@code [2, d, n, e_00, ..., e_{d-1,n-1}]}
	 * (row-major, elements at offset {@code 3}); {@code x} is a rank-1 vector of length
	 * {@code n}. The result is a fresh rank-1 vector of length {@code d} with
	 * {@code r[i] = dot(row_i of W, x)}, each dot computed by the same vectorized lane
	 * loop as {@link #simdDot} (two-rounding mul-then-add; only the reduction
	 * associativity differs from the scalar reference, so f64-exact inputs stay
	 * byte-identical). One bridge call covers the whole matrix, amortizing the entry cost
	 * over {@code d} rows.
	 */
	static @Nullable Object simdMatvec(@Nullable Object w, @Nullable Object x) {
		if (w instanceof float[] fw) {
			return matvecF(fw, asFloat(x));
		}
		if (x instanceof float[]) {
			throw mixedWidth();
		}
		double[] W = (double[]) java.util.Objects.requireNonNull(w);
		double[] X = (double[]) java.util.Objects.requireNonNull(x);
		int ow = 1 + (int) W[0]; // rank-2 matrix header -> elements start at 3
		int d = (int) W[1]; // rows
		int n = (int) W[2]; // columns = length of x
		int ox = 1 + (int) X[0];
		double[] r = newVec(d);
		for (int row = 0; row < d; row++) {
			int base = ow + row * n;
			int i = 0;
			double acc = 0.0;
			if (n >= THRESHOLD) {
				DoubleVector vacc = DoubleVector.zero(SPECIES);
				int bound = SPECIES.loopBound(n);
				for (; i < bound; i += SPECIES.length()) {
					vacc = vacc.add(DoubleVector.fromArray(SPECIES, W, base + i)
						.mul(DoubleVector.fromArray(SPECIES, X, ox + i)));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < n; i++) {
				acc += W[base + i] * X[ox + i];
			}
			r[2 + row] = acc;
		}
		return r;
	}

	// --- destination-passing kernels (write into out, allocate nothing) -----------
	// The -into siblings of the four element-wise kernels and matvec (todo 103). Each
	// returns the destination it was given, so a hot loop allocates no packed vector at
	// all. The element-wise ones tolerate out == a and/or out == b: within one lane block
	// the reads precede the store at the same indices, so in-place accumulation
	// (vec:add-into acc acc d) is well-defined. matvec-into cannot: out[row] is a fold
	// over ALL of x, so a store would clobber an element a later row still reads --
	// hence the identity guard, mirroring the eq check in the scalar vec.lisp defun
	// (which this call site replaces, so the guard has to be repeated here).

	static @Nullable Object simdAddInto(@Nullable Object out, @Nullable Object a, @Nullable Object b) {
		if (out instanceof float[] fr) {
			addIntoF(fr, asFloat(a), asFloat(b));
			return out;
		}
		requireDouble(a, b);
		double[] r = (double[]) java.util.Objects.requireNonNull(out);
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] y = (double[]) java.util.Objects.requireNonNull(b);
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i)
					.add(DoubleVector.fromArray(SPECIES, y, oy + i))
					.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = x[ox + i] + y[oy + i];
		}
		return out;
	}

	static @Nullable Object simdSubInto(@Nullable Object out, @Nullable Object a, @Nullable Object b) {
		if (out instanceof float[] fr) {
			subIntoF(fr, asFloat(a), asFloat(b));
			return out;
		}
		requireDouble(a, b);
		double[] r = (double[]) java.util.Objects.requireNonNull(out);
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] y = (double[]) java.util.Objects.requireNonNull(b);
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i)
					.sub(DoubleVector.fromArray(SPECIES, y, oy + i))
					.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = x[ox + i] - y[oy + i];
		}
		return out;
	}

	static @Nullable Object simdMulInto(@Nullable Object out, @Nullable Object a, @Nullable Object b) {
		if (out instanceof float[] fr) {
			mulIntoF(fr, asFloat(a), asFloat(b));
			return out;
		}
		requireDouble(a, b);
		double[] r = (double[]) java.util.Objects.requireNonNull(out);
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] y = (double[]) java.util.Objects.requireNonNull(b);
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i)
					.mul(DoubleVector.fromArray(SPECIES, y, oy + i))
					.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = x[ox + i] * y[oy + i];
		}
		return out;
	}

	static @Nullable Object simdScaleInto(@Nullable Object out, @Nullable Object v, @Nullable Object s) {
		double scalar = ((Number) java.util.Objects.requireNonNull(s)).doubleValue();
		if (out instanceof float[] fr) {
			scaleIntoF(fr, asFloat(v), scalar);
			return out;
		}
		if (v instanceof float[]) {
			throw mixedWidth();
		}
		double[] r = (double[]) java.util.Objects.requireNonNull(out);
		double[] x = (double[]) java.util.Objects.requireNonNull(v);
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector.fromArray(SPECIES, x, ox + i).mul(scalar).intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = x[ox + i] * scalar;
		}
		return out;
	}

	static @Nullable Object simdMatvecInto(@Nullable Object out, @Nullable Object w, @Nullable Object x) {
		if (out == x || out == w) {
			throw new IllegalArgumentException(
					"vec:matvec-into: out must not be the same array as w or x (each out element folds over all of x)");
		}
		if (out instanceof float[] fr) {
			matvecIntoF(fr, asFloat(w), asFloat(x));
			return out;
		}
		requireDouble(w, x);
		double[] r = (double[]) java.util.Objects.requireNonNull(out);
		double[] mw = (double[]) java.util.Objects.requireNonNull(w);
		double[] vx = (double[]) java.util.Objects.requireNonNull(x);
		int or = 1 + (int) r[0];
		int ow = 1 + (int) mw[0];
		int d = (int) mw[1];
		int n = (int) mw[2];
		int ox = 1 + (int) vx[0];
		for (int row = 0; row < d; row++) {
			int base = ow + row * n;
			int i = 0;
			double acc = 0.0;
			if (n >= THRESHOLD) {
				DoubleVector vacc = DoubleVector.zero(SPECIES);
				int bound = SPECIES.loopBound(n);
				for (; i < bound; i += SPECIES.length()) {
					vacc = vacc.add(DoubleVector.fromArray(SPECIES, mw, base + i)
						.mul(DoubleVector.fromArray(SPECIES, vx, ox + i)));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < n; i++) {
				acc += mw[base + i] * vx[ox + i];
			}
			r[or + row] = acc;
		}
		return out;
	}

	/** Rejects a single-float operand paired with a double-float destination. */
	private static void requireDouble(@Nullable Object a, @Nullable Object b) {
		if (a instanceof float[] || b instanceof float[]) {
			throw mixedWidth();
		}
	}

	// --- single-float (f32) kernels ----------------------------------------------
	// A float[] operand runs these instead of the double[] path above; the result
	// keeps the input width (element-wise) or is the usual f64 scalar (reductions).

	private static void addIntoF(float[] r, float[] x, float[] y) {
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, ox + i)
					.add(FloatVector.fromArray(FSPECIES, y, oy + i))
					.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = x[ox + i] + y[oy + i];
		}
	}

	private static void subIntoF(float[] r, float[] x, float[] y) {
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, ox + i)
					.sub(FloatVector.fromArray(FSPECIES, y, oy + i))
					.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = x[ox + i] - y[oy + i];
		}
	}

	private static void mulIntoF(float[] r, float[] x, float[] y) {
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, ox + i)
					.mul(FloatVector.fromArray(FSPECIES, y, oy + i))
					.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = x[ox + i] * y[oy + i];
		}
	}

	/**
	 * Scalar, like {@link #scaleF}: the f64 intermediate defeats a native f32 lane mul.
	 */
	private static void scaleIntoF(float[] r, float[] x, double s) {
		int or = 1 + (int) r[0];
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		for (int i = 0; i < n; i++) {
			r[or + i] = (float) (x[ox + i] * s);
		}
	}

	private static void matvecIntoF(float[] r, float[] w, float[] x) {
		int or = 1 + (int) r[0];
		int ow = 1 + (int) w[0];
		int d = (int) w[1];
		int n = (int) w[2];
		int ox = 1 + (int) x[0];
		for (int row = 0; row < d; row++) {
			int base = ow + row * n;
			int i = 0;
			double acc = 0.0;
			if (n >= THRESHOLD) {
				DoubleVector vacc = DoubleVector.zero(SPECIES);
				int bound = FSPECIES.loopBound(n);
				for (; i < bound; i += FSPECIES.length()) {
					FloatVector fw = FloatVector.fromArray(FSPECIES, w, base + i);
					FloatVector fx = FloatVector.fromArray(FSPECIES, x, ox + i);
					DoubleVector w0 = (DoubleVector) fw.convert(VectorOperators.F2D, 0);
					DoubleVector w1 = (DoubleVector) fw.convert(VectorOperators.F2D, 1);
					DoubleVector x0 = (DoubleVector) fx.convert(VectorOperators.F2D, 0);
					DoubleVector x1 = (DoubleVector) fx.convert(VectorOperators.F2D, 1);
					vacc = vacc.add(w0.mul(x0)).add(w1.mul(x1));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < n; i++) {
				acc += (double) w[base + i] * (double) x[ox + i];
			}
			r[or + row] = (float) acc;
		}
	}

	/** {@code r[i] = x[i] + y[i]} in native f32 (no double-rounding for a single +). */
	private static float[] addF(float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		float[] r = newVecF(n);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, ox + i)
					.add(FloatVector.fromArray(FSPECIES, y, oy + i))
					.intoArray(r, 2 + i);
			}
		}
		for (; i < n; i++) {
			r[2 + i] = x[ox + i] + y[oy + i];
		}
		return r;
	}

	/** {@code r[i] = x[i] - y[i]} in native f32. */
	private static float[] subF(float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		float[] r = newVecF(n);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, ox + i)
					.sub(FloatVector.fromArray(FSPECIES, y, oy + i))
					.intoArray(r, 2 + i);
			}
		}
		for (; i < n; i++) {
			r[2 + i] = x[ox + i] - y[oy + i];
		}
		return r;
	}

	/**
	 * {@code r[i] = x[i] * y[i]} in native f32 (exact product of two floats fits f64).
	 */
	private static float[] mulF(float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		float[] r = newVecF(n);
		int i = 0;
		if (n >= THRESHOLD) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector.fromArray(FSPECIES, x, ox + i)
					.mul(FloatVector.fromArray(FSPECIES, y, oy + i))
					.intoArray(r, 2 + i);
			}
		}
		for (; i < n; i++) {
			r[2 + i] = x[ox + i] * y[oy + i];
		}
		return r;
	}

	/**
	 * {@code r[i] = (float)((double)x[i] * s)} -- the scalar {@code s} is a genuine f64,
	 * so the multiply happens in f64 and narrows on store (a native-f32 multiply would
	 * diverge from the scalar reference for a scalar not exactly representable in f32).
	 * Kept a scalar loop: the f64 intermediate defeats a clean native-f32 lane multiply.
	 */
	private static float[] scaleF(float[] x, double s) {
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		float[] r = newVecF(n);
		for (int i = 0; i < n; i++) {
			r[2 + i] = (float) (x[ox + i] * s);
		}
		return r;
	}

	/**
	 * {@code sum(x)} as a f64 scalar: each f32 lane is widened to f64 (via {@code F2D})
	 * and accumulated in a {@code DoubleVector}, matching the scalar reference's
	 * widen-then-add. The {@code FloatVector} load reads two {@code DoubleVector}-worth
	 * of lanes per step (a preferred float vector holds twice the lanes of a preferred
	 * double vector).
	 */
	private static double sumF(float[] x) {
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector fv = FloatVector.fromArray(FSPECIES, x, ox + i);
				vacc = vacc.add((DoubleVector) fv.convert(VectorOperators.F2D, 0))
					.add((DoubleVector) fv.convert(VectorOperators.F2D, 1));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[ox + i];
		}
		return acc;
	}

	/**
	 * {@code dot(x, y)} as a f64 scalar: each f32 lane is widened to f64 and the products
	 * are accumulated in f64 (so the product itself is
	 * {@code (double)x[i] * (double)y[i]}, bit-identical to the scalar reference; only
	 * reduction associativity differs).
	 */
	private static double dotF(float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		double acc = 0.0;
		if (n >= THRESHOLD) {
			DoubleVector vacc = DoubleVector.zero(SPECIES);
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector fx = FloatVector.fromArray(FSPECIES, x, ox + i);
				FloatVector fy = FloatVector.fromArray(FSPECIES, y, oy + i);
				DoubleVector x0 = (DoubleVector) fx.convert(VectorOperators.F2D, 0);
				DoubleVector x1 = (DoubleVector) fx.convert(VectorOperators.F2D, 1);
				DoubleVector y0 = (DoubleVector) fy.convert(VectorOperators.F2D, 0);
				DoubleVector y1 = (DoubleVector) fy.convert(VectorOperators.F2D, 1);
				vacc = vacc.add(x0.mul(y0)).add(x1.mul(y1));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += (double) x[ox + i] * (double) y[oy + i];
		}
		return acc;
	}

	/**
	 * {@code matvec(W, x)} for a f32 matrix and vector: each output element {@code r[i]}
	 * is the f64-accumulated dot of row {@code i} of {@code W} with {@code x} (per-lane
	 * {@code F2D} widening, matching {@link #dotF}), narrowed back to f32 on store so the
	 * result keeps the single-float width -- bit-identical to the scalar {@code vec.lisp}
	 * reference (which widens on read, accumulates in f64, narrows on store) for
	 * f64-exact inputs.
	 */
	private static float[] matvecF(float[] w, float[] x) {
		int ow = 1 + (int) w[0]; // rank-2 matrix header -> elements start at 3
		int d = (int) w[1];
		int n = (int) w[2];
		int ox = 1 + (int) x[0];
		float[] r = newVecF(d);
		for (int row = 0; row < d; row++) {
			int base = ow + row * n;
			int i = 0;
			double acc = 0.0;
			if (n >= THRESHOLD) {
				DoubleVector vacc = DoubleVector.zero(SPECIES);
				int bound = FSPECIES.loopBound(n);
				for (; i < bound; i += FSPECIES.length()) {
					FloatVector fw = FloatVector.fromArray(FSPECIES, w, base + i);
					FloatVector fx = FloatVector.fromArray(FSPECIES, x, ox + i);
					DoubleVector w0 = (DoubleVector) fw.convert(VectorOperators.F2D, 0);
					DoubleVector w1 = (DoubleVector) fw.convert(VectorOperators.F2D, 1);
					DoubleVector x0 = (DoubleVector) fx.convert(VectorOperators.F2D, 0);
					DoubleVector x1 = (DoubleVector) fx.convert(VectorOperators.F2D, 1);
					vacc = vacc.add(w0.mul(x0)).add(w1.mul(x1));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < n; i++) {
				acc += (double) w[base + i] * (double) x[ox + i];
			}
			r[2 + row] = (float) acc;
		}
		return r;
	}

	// --- packed float-array construction -----------------------------------------

	/**
	 * Allocates a fresh rank-1 packed double vector holding {@code n} elements: a
	 * {@code double[]} of length {@code 2 + n} with the header {@code [1.0, n]} written
	 * and the {@code n} element slots left at {@code 0.0} for the caller to fill. Matches
	 * the layout the {@code _fv*} helpers ({@link JvmFloatArrayRuntimeBuilder}) read.
	 */
	private static double[] newVec(int n) {
		double[] r = new double[2 + n];
		r[0] = 1.0;
		r[1] = n;
		return r;
	}

	/**
	 * The {@code float[]} counterpart of {@link #newVec}: header {@code [1.0f, n]}, the
	 * {@code n} element slots left at {@code 0.0f}. Rank/dim in the f32 header are exact
	 * for any realistic dimension (< 2^24).
	 */
	private static float[] newVecF(int n) {
		float[] r = new float[2 + n];
		r[0] = 1.0f;
		r[1] = n;
		return r;
	}

	/** Unwraps a float[] operand, or reports the fixed-width contract violation. */
	private static float[] asFloat(@Nullable Object o) {
		if (o instanceof float[] f) {
			return f;
		}
		throw mixedWidth();
	}

	/** The error for mixing single-float and double-float operands in one simd op. */
	private static RuntimeException mixedWidth() {
		return new IllegalArgumentException(
				"vec: operands must share an element type (mixed single-float and double-float)");
	}

}
