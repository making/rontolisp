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
 * scalar). The reductions ({@code sum}/{@code dot}/{@code matvec}) always produce a f64
 * scalar, but they compute in the operand's OWN width: an f64 operand accumulates in f64,
 * an f32 operand accumulates in f32 and promotes once, at the value boundary (see
 * {@link #dotF}). Against the f64-accumulating scalar {@code vec.lisp} oracle an f64
 * reduction therefore differs only by reduction associativity, an f32 one also by the
 * accumulator width -- so the cross-backend tests use inputs exact at the operand width
 * (integer / power-of-two).
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
	 * The species the single-float REDUCTIONS use, pinned to four lanes on every host.
	 * They accumulate in {@code float}, so their result depends on the lane count: a
	 * {@code SPECIES_PREFERRED} of 8 or 16 lanes would make {@code (vec:dot v v)} answer
	 * differently on an AVX2 or AVX-512 machine than on a 128-bit one, and a compiled
	 * {@code .class} would stop being portable. The WASM {@code --simd} kernels are
	 * always {@code f32x4}, so pinning here is what lets every {@code --simd} backend
	 * agree. The element-wise kernels stay on {@code SPECIES_PREFERRED}: they are
	 * bit-exact at any width.
	 */
	private static final VectorSpecies<Float> FSPECIES_REDUCE = FloatVector.SPECIES_128;

	/**
	 * Below this element count the Vector API setup cost outweighs the lane parallelism,
	 * so a plain scalar loop is used. Purely a performance gate -- both paths compute
	 * bit-identical results for the element-wise kernels, and identical results for the
	 * reductions on the inputs the cross-backend tests use (exact at the operand width).
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

	// --- comparison-select ufuncs ---------------------------------------------------
	// maximum / minimum / relu / clip mirror the vec.lisp comparison selects
	// ((if (> x y) x y) and its mirrors), never Math.max / Math.min (different NaN /
	// -0.0 handling: the select keeps the SECOND operand or the bound on any false
	// comparison). A float compare equals the widened double compare and a select
	// only copies input bits, so the f32 array-array loops compare natively; clip
	// compares the widened element against the FULL double bounds and narrows the
	// selected value once (the array-vs-scalar rule). Plain scalar loops: a select's
	// bits do not depend on lane grouping, so a lane form would buy speed only.

	static @Nullable Object simdMaximum(@Nullable Object a, @Nullable Object b) {
		if (a instanceof float[] fx) {
			return maximumF(fx, asFloat(b));
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
		for (int i = 0; i < n; i++) {
			r[2 + i] = x[ox + i] > y[oy + i] ? x[ox + i] : y[oy + i];
		}
		return r;
	}

	static @Nullable Object simdMinimum(@Nullable Object a, @Nullable Object b) {
		if (a instanceof float[] fx) {
			return minimumF(fx, asFloat(b));
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
		for (int i = 0; i < n; i++) {
			r[2 + i] = x[ox + i] < y[oy + i] ? x[ox + i] : y[oy + i];
		}
		return r;
	}

	private static float[] maximumF(float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		float[] r = newVecF(n);
		for (int i = 0; i < n; i++) {
			r[2 + i] = x[ox + i] > y[oy + i] ? x[ox + i] : y[oy + i];
		}
		return r;
	}

	private static float[] minimumF(float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		float[] r = newVecF(n);
		for (int i = 0; i < n; i++) {
			r[2 + i] = x[ox + i] < y[oy + i] ? x[ox + i] : y[oy + i];
		}
		return r;
	}

	static @Nullable Object simdClip(@Nullable Object v, @Nullable Object lo, @Nullable Object hi) {
		double l = ((Number) java.util.Objects.requireNonNull(lo)).doubleValue();
		double h = ((Number) java.util.Objects.requireNonNull(hi)).doubleValue();
		if (v instanceof float[] fx) {
			int o = 1 + (int) fx[0];
			int n = fx.length - o;
			float[] r = newVecF(n);
			clipIntoF(r, 2, fx, o, n, l, h);
			return r;
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(v);
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		double[] r = newVec(n);
		clipIntoD(r, 2, x, ox, n, l, h);
		return r;
	}

	static @Nullable Object simdClipInto(@Nullable Object out, @Nullable Object v, @Nullable Object lo,
			@Nullable Object hi) {
		double l = ((Number) java.util.Objects.requireNonNull(lo)).doubleValue();
		double h = ((Number) java.util.Objects.requireNonNull(hi)).doubleValue();
		if (out instanceof float[] fr) {
			float[] fx = asFloat(v);
			int ox = 1 + (int) fx[0];
			clipIntoF(fr, 1 + (int) fr[0], fx, ox, fx.length - ox, l, h);
			return out;
		}
		if (v instanceof float[]) {
			throw mixedWidth();
		}
		double[] r = (double[]) java.util.Objects.requireNonNull(out);
		double[] x = (double[]) java.util.Objects.requireNonNull(v);
		int ox = 1 + (int) x[0];
		clipIntoD(r, 1 + (int) r[0], x, ox, x.length - ox, l, h);
		return out;
	}

	// clip mirrors the composition semantics the vec.lisp lambda spells out:
	// t = (if (> x lo) x lo), then (if (< t hi) t hi) -- so a NaN element becomes lo
	// and inverted bounds (lo > hi) end at hi.
	private static void clipIntoD(double[] r, int or, double[] x, int ox, int n, double lo, double hi) {
		for (int i = 0; i < n; i++) {
			double t = x[ox + i] > lo ? x[ox + i] : lo;
			r[or + i] = t < hi ? t : hi;
		}
	}

	private static void clipIntoF(float[] r, int or, float[] x, int ox, int n, double lo, double hi) {
		for (int i = 0; i < n; i++) {
			double xd = x[ox + i];
			double t = xd > lo ? xd : lo;
			r[or + i] = (float) (t < hi ? t : hi);
		}
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
	// The -into siblings of the four element-wise kernels and matvec. Each returns the
	// destination it was given, so a hot loop allocates no packed vector at all. The
	// element-wise ones tolerate out == a and/or out == b: within one lane block the
	// reads precede the store at the same indices, so in-place accumulation
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

	static @Nullable Object simdMaximumInto(@Nullable Object out, @Nullable Object a, @Nullable Object b) {
		if (out instanceof float[] fr) {
			float[] fx = asFloat(a);
			float[] fy = asFloat(b);
			int or = 1 + (int) fr[0];
			int ox = 1 + (int) fx[0];
			int oy = 1 + (int) fy[0];
			int n = Math.min(fx.length - ox, fy.length - oy);
			for (int i = 0; i < n; i++) {
				fr[or + i] = fx[ox + i] > fy[oy + i] ? fx[ox + i] : fy[oy + i];
			}
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
		for (int i = 0; i < n; i++) {
			r[or + i] = x[ox + i] > y[oy + i] ? x[ox + i] : y[oy + i];
		}
		return out;
	}

	static @Nullable Object simdMinimumInto(@Nullable Object out, @Nullable Object a, @Nullable Object b) {
		if (out instanceof float[] fr) {
			float[] fx = asFloat(a);
			float[] fy = asFloat(b);
			int or = 1 + (int) fr[0];
			int ox = 1 + (int) fx[0];
			int oy = 1 + (int) fy[0];
			int n = Math.min(fx.length - ox, fy.length - oy);
			for (int i = 0; i < n; i++) {
				fr[or + i] = fx[ox + i] < fy[oy + i] ? fx[ox + i] : fy[oy + i];
			}
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
		for (int i = 0; i < n; i++) {
			r[or + i] = x[ox + i] < y[oy + i] ? x[ox + i] : y[oy + i];
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

	// --- element-wise unary ufuncs -------------------------------------------------
	// exp / sqrt / abs / negative / sign / reciprocal, each with an -into sibling that
	// writes into the caller's destination (which MAY alias the operand -- element i
	// depends only on element i, the add-into rule; the guard comment is repeated here
	// because this call site replaces the vec.lisp defun that carries it). square and
	// square-into are not kernels: their spliced defuns call vec:mul / vec:mul-into,
	// whose call sites are intercepted. The oracle is the emap rule -- read widened to
	// f64, apply, narrow on store. sqrt / abs / neg / 1-over-x have lane forms
	// bit-identical to that (sqrt and div correctly rounded, abs and neg exact, and the
	// f32 widen-compute-narrow round trip is exact by the 53 >= 2*24+2 bound); exp /
	// log / tanh / sin / cos / tan / asin / acos / atan / sinh / cosh / signum have
	// NO bit-safe lane form
	// (VectorOperators.EXP etc. are not bit-identical to Math.exp), so they stay
	// de-boxed scalar loops over the same java.lang.Math calls the compiled defun makes.

	private static final int UOP_EXP = 0;

	private static final int UOP_SQRT = 1;

	private static final int UOP_ABS = 2;

	private static final int UOP_NEG = 3;

	private static final int UOP_SIGN = 4;

	private static final int UOP_RECIP = 5;

	private static final int UOP_LOG = 6;

	private static final int UOP_TANH = 7;

	private static final int UOP_SIN = 8;

	private static final int UOP_COS = 9;

	private static final int UOP_TAN = 10;

	private static final int UOP_ASIN = 11;

	private static final int UOP_ACOS = 12;

	private static final int UOP_ATAN = 13;

	private static final int UOP_SINH = 14;

	private static final int UOP_COSH = 15;

	/**
	 * {@code (if (> x 0.0) x 0.0)}: the comparison select, not Math.max.
	 */
	private static final int UOP_RELU = 16;

	static @Nullable Object simdExp(@Nullable Object v) {
		return simdUnary(UOP_EXP, v);
	}

	static @Nullable Object simdLog(@Nullable Object v) {
		return simdUnary(UOP_LOG, v);
	}

	static @Nullable Object simdTanh(@Nullable Object v) {
		return simdUnary(UOP_TANH, v);
	}

	static @Nullable Object simdSin(@Nullable Object v) {
		return simdUnary(UOP_SIN, v);
	}

	static @Nullable Object simdCos(@Nullable Object v) {
		return simdUnary(UOP_COS, v);
	}

	static @Nullable Object simdTan(@Nullable Object v) {
		return simdUnary(UOP_TAN, v);
	}

	static @Nullable Object simdAsin(@Nullable Object v) {
		return simdUnary(UOP_ASIN, v);
	}

	static @Nullable Object simdAcos(@Nullable Object v) {
		return simdUnary(UOP_ACOS, v);
	}

	static @Nullable Object simdAtan(@Nullable Object v) {
		return simdUnary(UOP_ATAN, v);
	}

	static @Nullable Object simdSinh(@Nullable Object v) {
		return simdUnary(UOP_SINH, v);
	}

	static @Nullable Object simdCosh(@Nullable Object v) {
		return simdUnary(UOP_COSH, v);
	}

	static @Nullable Object simdSqrt(@Nullable Object v) {
		return simdUnary(UOP_SQRT, v);
	}

	static @Nullable Object simdAbs(@Nullable Object v) {
		return simdUnary(UOP_ABS, v);
	}

	static @Nullable Object simdNegative(@Nullable Object v) {
		return simdUnary(UOP_NEG, v);
	}

	static @Nullable Object simdSign(@Nullable Object v) {
		return simdUnary(UOP_SIGN, v);
	}

	static @Nullable Object simdReciprocal(@Nullable Object v) {
		return simdUnary(UOP_RECIP, v);
	}

	static @Nullable Object simdExpInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_EXP, out, v);
	}

	static @Nullable Object simdLogInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_LOG, out, v);
	}

	static @Nullable Object simdTanhInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_TANH, out, v);
	}

	static @Nullable Object simdSinInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_SIN, out, v);
	}

	static @Nullable Object simdCosInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_COS, out, v);
	}

	static @Nullable Object simdTanInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_TAN, out, v);
	}

	static @Nullable Object simdAsinInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_ASIN, out, v);
	}

	static @Nullable Object simdAcosInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_ACOS, out, v);
	}

	static @Nullable Object simdAtanInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_ATAN, out, v);
	}

	static @Nullable Object simdSinhInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_SINH, out, v);
	}

	static @Nullable Object simdCoshInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_COSH, out, v);
	}

	static @Nullable Object simdSqrtInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_SQRT, out, v);
	}

	static @Nullable Object simdAbsInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_ABS, out, v);
	}

	static @Nullable Object simdNegativeInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_NEG, out, v);
	}

	static @Nullable Object simdSignInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_SIGN, out, v);
	}

	static @Nullable Object simdReciprocalInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_RECIP, out, v);
	}

	static @Nullable Object simdRelu(@Nullable Object v) {
		return simdUnary(UOP_RELU, v);
	}

	static @Nullable Object simdReluInto(@Nullable Object out, @Nullable Object v) {
		return simdUnaryInto(UOP_RELU, out, v);
	}

	private static @Nullable Object simdUnary(int op, @Nullable Object v) {
		if (v instanceof float[] fx) {
			int o = 1 + (int) fx[0];
			int n = fx.length - o;
			float[] r = newVecF(n);
			unaryIntoF(op, r, 2, fx, o, n);
			return r;
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(v);
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		double[] r = newVec(n);
		unaryIntoD(op, r, 2, x, ox, n);
		return r;
	}

	private static @Nullable Object simdUnaryInto(int op, @Nullable Object out, @Nullable Object v) {
		if (out instanceof float[] fr) {
			float[] fx = asFloat(v);
			int ox = 1 + (int) fx[0];
			unaryIntoF(op, fr, 1 + (int) fr[0], fx, ox, fx.length - ox);
			return out;
		}
		if (v instanceof float[]) {
			throw mixedWidth();
		}
		double[] r = (double[]) java.util.Objects.requireNonNull(out);
		double[] x = (double[]) java.util.Objects.requireNonNull(v);
		int ox = 1 + (int) x[0];
		unaryIntoD(op, r, 1 + (int) r[0], x, ox, x.length - ox);
		return out;
	}

	/** {@code r[or+i] = op(x[ox+i])} over {@code n} elements, lanes where bit-safe. */
	private static void unaryIntoD(int op, double[] r, int or, double[] x, int ox, int n) {
		int i = 0;
		if (n >= THRESHOLD && hasLaneForm(op)) {
			int bound = SPECIES.loopBound(n);
			for (; i < bound; i += SPECIES.length()) {
				DoubleVector v = DoubleVector.fromArray(SPECIES, x, ox + i);
				DoubleVector w;
				if (op == UOP_SQRT) {
					w = v.lanewise(VectorOperators.SQRT);
				}
				else if (op == UOP_ABS) {
					w = v.abs();
				}
				else if (op == UOP_NEG) {
					w = v.neg();
				}
				else {
					w = DoubleVector.broadcast(SPECIES, 1.0).div(v);
				}
				w.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = applyUnary(op, x[ox + i]);
		}
	}

	private static void unaryIntoF(int op, float[] r, int or, float[] x, int ox, int n) {
		int i = 0;
		if (n >= THRESHOLD && hasLaneForm(op)) {
			int bound = FSPECIES.loopBound(n);
			for (; i < bound; i += FSPECIES.length()) {
				FloatVector v = FloatVector.fromArray(FSPECIES, x, ox + i);
				FloatVector w;
				if (op == UOP_SQRT) {
					w = v.lanewise(VectorOperators.SQRT);
				}
				else if (op == UOP_ABS) {
					w = v.abs();
				}
				else if (op == UOP_NEG) {
					w = v.neg();
				}
				else {
					w = FloatVector.broadcast(FSPECIES, 1.0f).div(v);
				}
				w.intoArray(r, or + i);
			}
		}
		for (; i < n; i++) {
			r[or + i] = (float) applyUnary(op, x[ox + i]);
		}
	}

	// exp / log / tanh / sin / cos / tan / asin / acos / atan / sinh / cosh / signum
	// have no lane form bit-identical to java.lang.Math, so they stay scalar loops
	// (see the section comment above).
	private static boolean hasLaneForm(int op) {
		return op == UOP_SQRT || op == UOP_ABS || op == UOP_NEG || op == UOP_RECIP;
	}

	private static double applyUnary(int op, double x) {
		if (op == UOP_EXP) {
			return Math.exp(x);
		}
		if (op == UOP_LOG) {
			return Math.log(x);
		}
		if (op == UOP_TANH) {
			return Math.tanh(x);
		}
		if (op == UOP_SIN) {
			return Math.sin(x);
		}
		if (op == UOP_COS) {
			return Math.cos(x);
		}
		if (op == UOP_TAN) {
			return Math.tan(x);
		}
		if (op == UOP_ASIN) {
			return Math.asin(x);
		}
		if (op == UOP_ACOS) {
			return Math.acos(x);
		}
		if (op == UOP_ATAN) {
			return Math.atan(x);
		}
		if (op == UOP_SINH) {
			return Math.sinh(x);
		}
		if (op == UOP_COSH) {
			return Math.cosh(x);
		}
		if (op == UOP_SQRT) {
			return Math.sqrt(x);
		}
		if (op == UOP_ABS) {
			return Math.abs(x);
		}
		if (op == UOP_NEG) {
			return -x;
		}
		if (op == UOP_SIGN) {
			return Math.signum(x);
		}
		if (op == UOP_RELU) {
			return x > 0.0 ? x : 0.0;
		}
		return 1.0 / x;
	}

	// ================================================================================
	// linalg: kernels
	// ================================================================================
	//
	// The vec: kernels above are TOTAL: vec: accepts packed float arrays and nothing
	// else, so they signal on anything they cannot read. The linalg: kernels below are
	// PARTIAL. linalg.lisp also accepts general (boxed) arrays, mixed widths, a scalar
	// operand on either side, plain numbers, and mismatched shapes (which it turns into a
	// specific error), so every kernel here returns NULL for an input it does not handle,
	// and the call site JvmLinalgSimdCompiler emitted then invokes the scalar defun. The
	// library stays the single source of truth for every edge case, error messages
	// included, and nothing is duplicated. Nil is ACONST_NULL in compiled code, but none
	// of these fifteen ever returns nil, so null is an unambiguous "declined".
	//
	// Two differences from vec: drive the rest. (1) A result must keep the operand's
	// rank-n dimension header -- every vec: kernel produces a rank-1 [1, n, ...] vector.
	// (2) A packed argument is only USABLE when its partner has the same width; a mixed
	// pair declines rather than signalling.
	//
	// Precision matches the interpreter's LinalgSimdKernels everywhere:
	// amax/amin/argmax/argmin compare with the plain IEEE `>`, which is every backend's
	// `>` on two doubles (interpreter compareNumeric, the JVM's DCMPG/DCMPL + _cmpb,
	// wasm's f64.gt) -- their edge semantics were reconciled, so ties, -0.0 and NaN
	// agree with the scalar defun on all of them.

	private static final int OP_ADD = 0;

	private static final int OP_SUB = 1;

	private static final int OP_MUL = 2;

	private static final int OP_DIV = 3;

	// The comparison selects: scalar loops only -- the lane blocks
	// below are gated to op <= OP_DIV, and laApply mirrors the %la-bcast lambdas
	// ((if (> x y) x y) / (if (< x y) x y)). NOT commutative on ties and NaN (the
	// second operand wins on a false comparison), so the scalar-on-the-left shape must
	// run laEwSD / laEwSF, never the array-scalar shortcut. linalg:clip / linalg:relu
	// have no kernel: their spliced defuns compose linalg:maximum / linalg:minimum.

	private static final int OP_MAX = 4;

	private static final int OP_MIN = 5;

	static @Nullable Object laAdd(@Nullable Object a, @Nullable Object b) {
		return laElementwise(OP_ADD, a, b);
	}

	static @Nullable Object laSub(@Nullable Object a, @Nullable Object b) {
		return laElementwise(OP_SUB, a, b);
	}

	static @Nullable Object laMul(@Nullable Object a, @Nullable Object b) {
		return laElementwise(OP_MUL, a, b);
	}

	static @Nullable Object laDiv(@Nullable Object a, @Nullable Object b) {
		return laElementwise(OP_DIV, a, b);
	}

	static @Nullable Object laMaximum(@Nullable Object a, @Nullable Object b) {
		return laElementwise(OP_MAX, a, b);
	}

	static @Nullable Object laMinimum(@Nullable Object a, @Nullable Object b) {
		return laElementwise(OP_MIN, a, b);
	}

	// The named element-wise unary ufuncs: the vec: unary loops at the
	// operand's own header offset, PARTIAL like every linalg kernel (a general boxed
	// array or a plain number declines to the defun), and the result keeps the
	// operand's rank-n header (laNewLike). linalg:square / linalg:reciprocal have no
	// kernel -- their spliced defuns call linalg:mul / linalg:div.

	static @Nullable Object laExp(@Nullable Object a) {
		return laUnary(UOP_EXP, a);
	}

	static @Nullable Object laLog(@Nullable Object a) {
		return laUnary(UOP_LOG, a);
	}

	static @Nullable Object laTanh(@Nullable Object a) {
		return laUnary(UOP_TANH, a);
	}

	static @Nullable Object laSin(@Nullable Object a) {
		return laUnary(UOP_SIN, a);
	}

	static @Nullable Object laCos(@Nullable Object a) {
		return laUnary(UOP_COS, a);
	}

	static @Nullable Object laTan(@Nullable Object a) {
		return laUnary(UOP_TAN, a);
	}

	static @Nullable Object laAsin(@Nullable Object a) {
		return laUnary(UOP_ASIN, a);
	}

	static @Nullable Object laAcos(@Nullable Object a) {
		return laUnary(UOP_ACOS, a);
	}

	static @Nullable Object laAtan(@Nullable Object a) {
		return laUnary(UOP_ATAN, a);
	}

	static @Nullable Object laSinh(@Nullable Object a) {
		return laUnary(UOP_SINH, a);
	}

	static @Nullable Object laCosh(@Nullable Object a) {
		return laUnary(UOP_COSH, a);
	}

	static @Nullable Object laSqrt(@Nullable Object a) {
		return laUnary(UOP_SQRT, a);
	}

	static @Nullable Object laAbs(@Nullable Object a) {
		return laUnary(UOP_ABS, a);
	}

	static @Nullable Object laNegative(@Nullable Object a) {
		return laUnary(UOP_NEG, a);
	}

	static @Nullable Object laSign(@Nullable Object a) {
		return laUnary(UOP_SIGN, a);
	}

	private static @Nullable Object laUnary(int op, @Nullable Object a) {
		if (a instanceof double[] x) {
			int off = 1 + (int) x[0];
			double[] r = laNewLike(x);
			unaryIntoD(op, r, off, x, off, x.length - off);
			return r;
		}
		if (a instanceof float[] x) {
			int off = 1 + (int) x[0];
			float[] r = laNewLikeF(x);
			unaryIntoF(op, r, off, x, off, x.length - off);
			return r;
		}
		return null;
	}

	/**
	 * The three shapes {@code linalg::%la-bcast} distinguishes: array with array (equal
	 * shapes), array with scalar, and scalar with array.
	 */
	private static @Nullable Object laElementwise(int op, @Nullable Object a, @Nullable Object b) {
		if (a instanceof double[] x) {
			if (b instanceof double[] y) {
				// Two same-width arrays of different shapes broadcast by the numpy
				// rules; an incompatible pair declines so the defun signals its own
				// shape-mismatch error.
				return laSameDims(x, y) ? laEwDD(op, x, y) : laBcastDD(op, x, y);
			}
			if (b instanceof float[]) {
				return null;
			}
			Object s = laScalar(b);
			return s == null ? null : laEwDS(op, x, (Double) s);
		}
		if (a instanceof float[] x) {
			if (b instanceof float[] y) {
				return laSameDims(x, y) ? laEwFF(op, x, y) : laBcastFF(op, x, y);
			}
			if (b instanceof double[]) {
				return null;
			}
			Object s = laScalar(b);
			return s == null ? null : laEwFS(op, x, (Double) s);
		}
		Object s = laScalar(a);
		if (s == null) {
			return null;
		}
		// A commutative operator with the scalar on the left is the array-scalar kernel.
		if (b instanceof double[] y) {
			return (op == OP_ADD || op == OP_MUL) ? laEwDS(op, y, (Double) s) : laEwSD(op, (Double) s, y);
		}
		if (b instanceof float[] y) {
			return (op == OP_ADD || op == OP_MUL) ? laEwFS(op, y, (Double) s) : laEwSF(op, (Double) s, y);
		}
		return null;
	}

	private static double[] laEwDD(int op, double[] x, double[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = x.length - ox;
		double[] r = laNewLike(x);
		int or = ox;
		int i = 0;
		// The lane block serves the four arithmetic ops only; OP_MAX / OP_MIN run the
		// scalar select tail (laApply) over the whole range.
		if (n >= THRESHOLD && op <= OP_DIV) {
			int bound = SPECIES.loopBound(n);
			if (op == OP_ADD) {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i)
						.add(DoubleVector.fromArray(SPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
			else if (op == OP_SUB) {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i)
						.sub(DoubleVector.fromArray(SPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
			else if (op == OP_MUL) {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i)
						.mul(DoubleVector.fromArray(SPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
			else {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i)
						.div(DoubleVector.fromArray(SPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
		}
		for (; i < n; i++) {
			r[or + i] = laApply(op, x[ox + i], y[oy + i]);
		}
		return r;
	}

	private static float[] laEwFF(int op, float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = x.length - ox;
		float[] r = laNewLikeF(x);
		int or = ox;
		int i = 0;
		if (n >= THRESHOLD && op <= OP_DIV) {
			int bound = FSPECIES.loopBound(n);
			if (op == OP_ADD) {
				for (; i < bound; i += FSPECIES.length()) {
					FloatVector.fromArray(FSPECIES, x, ox + i)
						.add(FloatVector.fromArray(FSPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
			else if (op == OP_SUB) {
				for (; i < bound; i += FSPECIES.length()) {
					FloatVector.fromArray(FSPECIES, x, ox + i)
						.sub(FloatVector.fromArray(FSPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
			else if (op == OP_MUL) {
				for (; i < bound; i += FSPECIES.length()) {
					FloatVector.fromArray(FSPECIES, x, ox + i)
						.mul(FloatVector.fromArray(FSPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
			else {
				for (; i < bound; i += FSPECIES.length()) {
					FloatVector.fromArray(FSPECIES, x, ox + i)
						.div(FloatVector.fromArray(FSPECIES, y, oy + i))
						.intoArray(r, or + i);
				}
			}
		}
		for (; i < n; i++) {
			r[or + i] = (float) laApply(op, x[ox + i], y[oy + i]);
		}
		return r;
	}

	private static double[] laEwDS(int op, double[] x, double s) {
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		double[] r = laNewLike(x);
		int i = 0;
		if (n >= THRESHOLD && op <= OP_DIV) {
			int bound = SPECIES.loopBound(n);
			if (op == OP_ADD) {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i).add(s).intoArray(r, ox + i);
				}
			}
			else if (op == OP_SUB) {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i).sub(s).intoArray(r, ox + i);
				}
			}
			else if (op == OP_MUL) {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i).mul(s).intoArray(r, ox + i);
				}
			}
			else {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, x, ox + i).div(s).intoArray(r, ox + i);
				}
			}
		}
		for (; i < n; i++) {
			r[ox + i] = laApply(op, x[ox + i], s);
		}
		return r;
	}

	private static double[] laEwSD(int op, double s, double[] y) {
		int oy = 1 + (int) y[0];
		int n = y.length - oy;
		double[] r = laNewLike(y);
		int i = 0;
		if (n >= THRESHOLD && op <= OP_DIV) {
			int bound = SPECIES.loopBound(n);
			if (op == OP_SUB) {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.broadcast(SPECIES, s)
						.sub(DoubleVector.fromArray(SPECIES, y, oy + i))
						.intoArray(r, oy + i);
				}
			}
			else {
				for (; i < bound; i += SPECIES.length()) {
					DoubleVector.broadcast(SPECIES, s)
						.div(DoubleVector.fromArray(SPECIES, y, oy + i))
						.intoArray(r, oy + i);
				}
			}
		}
		for (; i < n; i++) {
			r[oy + i] = laApply(op, s, y[oy + i]);
		}
		return r;
	}

	/**
	 * A single-float array against a genuine f64 scalar: the widen-compute-narrow round
	 * trip is NOT innocuous here (the scalar carries more than 24 bits), so these stay
	 * scalar loops computing in double, exactly like {@link #scaleF}. Splatting
	 * {@code (float) s} into f32 lanes would diverge from the oracle -- and
	 * {@code (linalg:mul grad 0.1)} over an {@code #f} gradient is the common shape.
	 */
	private static float[] laEwFS(int op, float[] x, double s) {
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		float[] r = laNewLikeF(x);
		for (int i = 0; i < n; i++) {
			r[ox + i] = (float) laApply(op, x[ox + i], s);
		}
		return r;
	}

	private static float[] laEwSF(int op, double s, float[] y) {
		int oy = 1 + (int) y[0];
		int n = y.length - oy;
		float[] r = laNewLikeF(y);
		for (int i = 0; i < n; i++) {
			r[oy + i] = (float) laApply(op, s, y[oy + i]);
		}
		return r;
	}

	private static double laApply(int op, double a, double b) {
		if (op == OP_ADD) {
			return a + b;
		}
		if (op == OP_SUB) {
			return a - b;
		}
		if (op == OP_MUL) {
			return a * b;
		}
		if (op == OP_MAX) {
			return a > b ? a : b;
		}
		if (op == OP_MIN) {
			return a < b ? a : b;
		}
		return a / b;
	}

	// --- reductions ------------------------------------------------------------------

	static @Nullable Object laSum(@Nullable Object a) {
		return laNonEmpty(a) ? simdSum(a) : null;
	}

	/** Fused: the oracle spells it {@code (sqrt (sum (emap square a)))} and allocates. */
	static @Nullable Object laNorm(@Nullable Object a) {
		return laNonEmpty(a) ? Math.sqrt((Double) java.util.Objects.requireNonNull(simdDot(a, a))) : null;
	}

	static @Nullable Object laAmax(@Nullable Object a) {
		if (!laNonEmpty(a)) {
			return null;
		}
		if (a instanceof float[] x) {
			int ox = 1 + (int) x[0];
			double best = x[ox];
			for (int i = ox + 1; i < x.length; i++) {
				if (x[i] > best) {
					best = x[i];
				}
			}
			return best;
		}
		double[] x = laDoubles(a);
		int ox = 1 + (int) x[0];
		double best = x[ox];
		for (int i = ox + 1; i < x.length; i++) {
			if (x[i] > best) {
				best = x[i];
			}
		}
		return best;
	}

	static @Nullable Object laAmin(@Nullable Object a) {
		if (!laNonEmpty(a)) {
			return null;
		}
		if (a instanceof float[] x) {
			int ox = 1 + (int) x[0];
			double best = x[ox];
			for (int i = ox + 1; i < x.length; i++) {
				if (x[i] < best) {
					best = x[i];
				}
			}
			return best;
		}
		double[] x = laDoubles(a);
		int ox = 1 + (int) x[0];
		double best = x[ox];
		for (int i = ox + 1; i < x.length; i++) {
			if (x[i] < best) {
				best = x[i];
			}
		}
		return best;
	}

	/** Vector-only in linalg.lisp (it uses {@code length}), so rank 1 is required. */
	static @Nullable Object laArgmax(@Nullable Object a) {
		if (!laNonEmpty(a) || laRank(a) != 1) {
			return null;
		}
		if (a instanceof float[] x) {
			float best = x[2];
			int bi = 0;
			for (int i = 1; i < x.length - 2; i++) {
				if (x[2 + i] > best) {
					best = x[2 + i];
					bi = i;
				}
			}
			return (long) bi;
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double best = x[2];
		int bi = 0;
		for (int i = 1; i < x.length - 2; i++) {
			if (x[2 + i] > best) {
				best = x[2 + i];
				bi = i;
			}
		}
		return (long) bi;
	}

	static @Nullable Object laArgmin(@Nullable Object a) {
		if (!laNonEmpty(a) || laRank(a) != 1) {
			return null;
		}
		if (a instanceof float[] x) {
			float best = x[2];
			int bi = 0;
			for (int i = 1; i < x.length - 2; i++) {
				if (x[2 + i] < best) {
					best = x[2 + i];
					bi = i;
				}
			}
			return (long) bi;
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double best = x[2];
		int bi = 0;
		for (int i = 1; i < x.length - 2; i++) {
			if (x[2 + i] < best) {
				best = x[2 + i];
				bi = i;
			}
		}
		return (long) bi;
	}

	/**
	 * The main-diagonal sum of a square matrix, accumulated in {@code double} at both
	 * widths -- the oracle reads each element widened, so this is bit-identical.
	 */
	static @Nullable Object laTrace(@Nullable Object a) {
		if (!laPacked(a) || laRank(a) != 2 || laDim(a, 0) != laDim(a, 1)) {
			return null;
		}
		int n = laDim(a, 0);
		double acc = 0.0;
		if (a instanceof float[] x) {
			for (int i = 0; i < n; i++) {
				acc += x[3 + i * n + i];
			}
		}
		else {
			double[] x = (double[]) java.util.Objects.requireNonNull(a);
			for (int i = 0; i < n; i++) {
				acc += x[3 + i * n + i];
			}
		}
		return acc;
	}

	// --- shape --------------------------------------------------------------------

	static @Nullable Object laTranspose(@Nullable Object a) {
		if (!laPacked(a) || laRank(a) > 2) {
			return null;
		}
		if (laRank(a) == 1) {
			// linalg.lisp returns a vector unchanged -- the same object, so eq holds.
			return a;
		}
		int r = laDim(a, 0);
		int c = laDim(a, 1);
		if (a instanceof float[] x) {
			float[] m = laNewMatF(c, r);
			for (int i = 0; i < r; i++) {
				for (int j = 0; j < c; j++) {
					m[3 + j * r + i] = x[3 + i * c + j];
				}
			}
			return m;
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] m = laNewMat(c, r);
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				m[3 + j * r + i] = x[3 + i * c + j];
			}
		}
		return m;
	}

	/**
	 * {@code (linalg:reshape a shape)} where {@code shape} is a Lisp integer (a
	 * {@code Long}) or a proper list of them (a chain of two-element {@code Object[]}
	 * cons cells ending in {@code null}). {@code linalg:flatten} rides on this.
	 */
	static @Nullable Object laReshape(@Nullable Object a, @Nullable Object shape) {
		if (!laPacked(a)) {
			return null;
		}
		long[] dims = laShape(shape);
		if (dims == null) {
			return null;
		}
		long total = 1;
		for (long d : dims) {
			total *= d;
		}
		int n = laTotal(a);
		if (total != n) {
			return null;
		}
		int head = 1 + dims.length;
		if (a instanceof float[] x) {
			float[] r = new float[head + n];
			r[0] = dims.length;
			for (int i = 0; i < dims.length; i++) {
				r[1 + i] = dims[i];
			}
			System.arraycopy(x, 1 + (int) x[0], r, head, n);
			return r;
		}
		double[] x = (double[]) java.util.Objects.requireNonNull(a);
		double[] r = new double[head + n];
		r[0] = dims.length;
		for (int i = 0; i < dims.length; i++) {
			r[1 + i] = dims[i];
		}
		System.arraycopy(x, 1 + (int) x[0], r, head, n);
		return r;
	}

	/** A shape designator: a {@code Long}, or a proper cons list of {@code Long}s. */
	private static long @Nullable [] laShape(@Nullable Object shape) {
		if (shape instanceof Long n) {
			return n >= 0 ? new long[] { n } : null;
		}
		int count = 0;
		Object cursor = shape;
		while (cursor instanceof Object[] cell && cell.length == 2 && cell[0] instanceof Long) {
			count++;
			cursor = cell[1];
		}
		if (cursor != null || count == 0) {
			return null;
		}
		long[] dims = new long[count];
		Object walk = shape;
		for (int i = 0; i < count; i++) {
			// The chain was already validated above, so every cell up to `count` is a
			// two-element cons whose car is a Long; only the last cdr is nil (null).
			Object[] cell = (Object[]) java.util.Objects.requireNonNull(walk);
			long d = (Long) java.util.Objects.requireNonNull(cell[0]);
			if (d < 0) {
				return null;
			}
			dims[i] = d;
			walk = cell[1];
		}
		return dims;
	}

	// --- products ------------------------------------------------------------------

	/**
	 * The numpy dispatch of {@code linalg:dot}, for two packed operands of the same width
	 * and rank {@code <= 2}: {@code v.v} to a scalar, {@code M.v} (GEMV) and {@code v.M}
	 * to a vector, {@code M.M} to a matrix. A scalar operand declines -- the defun routes
	 * that to {@code linalg:mul}, itself intercepted.
	 */
	static @Nullable Object laDot(@Nullable Object a, @Nullable Object b) {
		if (!laPacked(a) || !laPacked(b) || laRank(a) > 2 || laRank(b) > 2) {
			return null;
		}
		boolean single = a instanceof float[];
		if (single != (b instanceof float[])) {
			return null;
		}
		if (laRank(a) == 1 && laRank(b) == 1) {
			return laDim(a, 0) == laDim(b, 0) ? simdDot(a, b) : null;
		}
		if (laRank(a) == 2 && laRank(b) == 1) {
			return laDim(a, 1) == laDim(b, 0) ? simdMatvec(a, b) : null;
		}
		if (laRank(a) == 1 && laRank(b) == 2) {
			// A row vector times a matrix is the n = 1 case of the matrix product; the
			// result is rank 1, so the header is rewritten below.
			int n = laDim(b, 0);
			int p = laDim(b, 1);
			if (laDim(a, 0) != n) {
				return null;
			}
			if (single) {
				float[] m = laMatmulF(laFloats(a), laFloats(b), 1, n, p);
				float[] r = newVecF(p);
				System.arraycopy(m, 3, r, 2, p);
				return r;
			}
			double[] m = laMatmul(laDoubles(a), laDoubles(b), 1, n, p);
			double[] r = newVec(p);
			System.arraycopy(m, 3, r, 2, p);
			return r;
		}
		int n = laDim(a, 0);
		int m = laDim(a, 1);
		int p = laDim(b, 1);
		if (m != laDim(b, 0)) {
			return null;
		}
		return single ? laMatmulF(laFloats(a), laFloats(b), n, m, p) : laMatmul(laDoubles(a), laDoubles(b), n, m, p);
	}

	/**
	 * The {@code n x m} by {@code m x p} product in <strong>ikj</strong> order: for each
	 * output row, accumulate {@code a[i][k] * b[k][*]} over {@code k}, where
	 * {@code b[k][*]} is a CONTIGUOUS row. The oracle's naive {@code ijk} form reads
	 * {@code b[k][j]} with stride {@code p}, which no lane loop can follow, and a
	 * transpose would need a scratch buffer.
	 *
	 * <p>
	 * The rewrite is not merely faster, it is bit-identical: {@code ikj} visits {@code k}
	 * in increasing order into the same accumulator cell, which is the oracle's own
	 * summation order. See {@link #laMatmulF} for why the single-float sibling
	 * accumulates in {@code double} rather than following the reduction contract.
	 */
	private static double[] laMatmul(double[] a, double[] b, int n, int m, int p) {
		int oa = 1 + (int) a[0];
		int ob = 1 + (int) b[0];
		double[] r = laNewMat(n, p);
		for (int i = 0; i < n; i++) {
			int ro = 3 + i * p;
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
		return r;
	}

	/**
	 * The single-float matrix product, accumulated in {@code double} and narrowed once
	 * per output element, so it is bit-identical to the oracle. The reduction contract
	 * (an {@code #f} reduction accumulates in single precision) applies where the LANES
	 * ARE the summation axis -- {@code dot}, {@code sum}, GEMV's per-row dot. Here the
	 * lanes run across the output row, which carries no summation, so the accumulator's
	 * width is free and the oracle's {@code double} costs nothing.
	 */
	private static float[] laMatmulF(float[] a, float[] b, int n, int m, int p) {
		int oa = 1 + (int) a[0];
		int ob = 1 + (int) b[0];
		float[] r = laNewMatF(n, p);
		double[] acc = new double[p];
		for (int i = 0; i < n; i++) {
			java.util.Arrays.fill(acc, 0.0);
			int ao = oa + i * m;
			for (int k = 0; k < m; k++) {
				double s = a[ao + k];
				int bo = ob + k * p;
				for (int j = 0; j < p; j++) {
					acc[j] += b[bo + j] * s;
				}
			}
			int ro = 3 + i * p;
			for (int j = 0; j < p; j++) {
				r[ro + j] = (float) acc[j];
			}
		}
		return r;
	}

	/** {@code out[i][j] = u[i] * v[j]}; the operands are flattened first, like numpy. */
	static @Nullable Object laOuter(@Nullable Object u, @Nullable Object v) {
		if (!laPacked(u) || !laPacked(v) || (u instanceof float[]) != (v instanceof float[])) {
			return null;
		}
		int n = laTotal(u);
		int m = laTotal(v);
		if (u instanceof float[] uf) {
			float[] vf = laFloats(v);
			int ou = 1 + (int) uf[0];
			int ov = 1 + (int) vf[0];
			float[] r = laNewMatF(n, m);
			for (int i = 0; i < n; i++) {
				float s = uf[ou + i];
				int ro = 3 + i * m;
				int j = 0;
				if (m >= THRESHOLD) {
					int bound = FSPECIES.loopBound(m);
					for (; j < bound; j += FSPECIES.length()) {
						FloatVector.fromArray(FSPECIES, vf, ov + j).mul(s).intoArray(r, ro + j);
					}
				}
				for (; j < m; j++) {
					r[ro + j] = vf[ov + j] * s;
				}
			}
			return r;
		}
		double[] ud = (double[]) java.util.Objects.requireNonNull(u);
		double[] vd = (double[]) java.util.Objects.requireNonNull(v);
		int ou = 1 + (int) ud[0];
		int ov = 1 + (int) vd[0];
		double[] r = laNewMat(n, m);
		for (int i = 0; i < n; i++) {
			double s = ud[ou + i];
			int ro = 3 + i * m;
			int j = 0;
			if (m >= THRESHOLD) {
				int bound = SPECIES.loopBound(m);
				for (; j < bound; j += SPECIES.length()) {
					DoubleVector.fromArray(SPECIES, vd, ov + j).mul(s).intoArray(r, ro + j);
				}
			}
			for (; j < m; j++) {
				r[ro + j] = vd[ov + j] * s;
			}
		}
		return r;
	}

	// --- linalg marshalling helpers ---------------------------------------------------

	private static double[] laDoubles(@Nullable Object o) {
		return (double[]) java.util.Objects.requireNonNull(o);
	}

	private static float[] laFloats(@Nullable Object o) {
		return (float[]) java.util.Objects.requireNonNull(o);
	}

	private static boolean laPacked(@Nullable Object o) {
		return o instanceof double[] || o instanceof float[];
	}

	private static boolean laNonEmpty(@Nullable Object o) {
		return laPacked(o) && laTotal(o) > 0;
	}

	private static int laRank(@Nullable Object o) {
		return o instanceof float[] f ? (int) f[0] : (int) ((double[]) java.util.Objects.requireNonNull(o))[0];
	}

	private static int laDim(@Nullable Object o, int i) {
		return o instanceof float[] f ? (int) f[1 + i] : (int) ((double[]) java.util.Objects.requireNonNull(o))[1 + i];
	}

	private static int laTotal(@Nullable Object o) {
		return o instanceof float[] f ? f.length - 1 - (int) f[0]
				: ((double[]) java.util.Objects.requireNonNull(o)).length - 1 - laRank(o);
	}

	private static boolean laSameDims(double[] a, double[] b) {
		if (a[0] != b[0]) {
			return false;
		}
		for (int i = 1; i <= (int) a[0]; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean laSameDims(float[] a, float[] b) {
		if (a[0] != b[0]) {
			return false;
		}
		for (int i = 1; i <= (int) a[0]; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	/** A Lisp number the kernels can broadcast, boxed as a {@code Double}, else null. */
	private static @Nullable Object laScalar(@Nullable Object o) {
		if (o instanceof Double d) {
			return d;
		}
		if (o instanceof Long l) {
			return (double) l;
		}
		return null;
	}

	/** A fresh packed array with the same rank-n header as {@code x}, zeroed data. */
	private static double[] laNewLike(double[] x) {
		double[] r = new double[x.length];
		System.arraycopy(x, 0, r, 0, 1 + (int) x[0]);
		return r;
	}

	private static float[] laNewLikeF(float[] x) {
		float[] r = new float[x.length];
		System.arraycopy(x, 0, r, 0, 1 + (int) x[0]);
		return r;
	}

	private static double[] laNewMat(int rows, int cols) {
		double[] m = new double[3 + rows * cols];
		m[0] = 2.0;
		m[1] = rows;
		m[2] = cols;
		return m;
	}

	private static float[] laNewMatF(int rows, int cols) {
		float[] m = new float[3 + rows * cols];
		m[0] = 2.0f;
		m[1] = rows;
		m[2] = cols;
		return m;
	}

	// --- the declined call shapes: broadcast / axes transpose / axis folds ----------
	// Pure scalar loops mirroring the linalg.lisp defuns element for element -- no
	// lanes. Every element is read widened to double, the operation runs in double, and
	// only a store into a single-float result narrows (the oracle's widen-compute-narrow
	// round trip), so all of these are bit-identical at both widths. The odometer walks
	// are %la-bcast-loop's own.

	/** The dims of a packed operand's in-array header, as an {@code int[]}. */
	private static int[] laDims(@Nullable Object a) {
		int rank = laRank(a);
		int[] d = new int[rank];
		for (int i = 0; i < rank; i++) {
			d[i] = laDim(a, i);
		}
		return d;
	}

	/**
	 * The numpy broadcast shape of two dims arrays ({@code %la-bcast-shape}): trailing
	 * axes align, a pair agrees when equal or either is 1, the output extent is the
	 * larger. Returns {@code null} (decline) on any other disagreement or an output too
	 * large for one Java array.
	 */
	private static int @Nullable [] laBcastShape(int[] dx, int[] dy) {
		int rank = Math.max(dx.length, dy.length);
		int[] od = new int[rank];
		long total = 1;
		for (int k = 0; k < rank; k++) {
			int i = dx.length - rank + k;
			int j = dy.length - rank + k;
			int a = i >= 0 ? dx[i] : 1;
			int b = j >= 0 ? dy[j] : 1;
			if (a != b && a != 1 && b != 1) {
				return null;
			}
			od[k] = Math.max(a, b);
			total *= od[k];
			if (!laSizeFits(total + 1 + rank)) {
				return null;
			}
		}
		return od;
	}

	/**
	 * Row-major strides of the dims-{@code d} operand aligned to the broadcast shape
	 * {@code od}, with 0 on every stretched axis (extent 1 or missing) so the odometer
	 * re-reads the same element across it -- {@code %la-bcast-strides} verbatim.
	 */
	private static int[] laBcastStrides(int[] d, int[] od) {
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
	 * stride-0-padded strides through an odometer carry from the innermost axis out.
	 */
	private static double @Nullable [] laBcastDD(int op, double[] x, double[] y) {
		int[] dx = laDims(x);
		int[] dy = laDims(y);
		int[] od = laBcastShape(dx, dy);
		if (od == null) {
			return null;
		}
		int rank = od.length;
		int[] sx = laBcastStrides(dx, od);
		int[] sy = laBcastStrides(dy, od);
		int total = 1;
		for (int d : od) {
			total *= d;
		}
		double[] r = new double[1 + rank + total];
		r[0] = rank;
		for (int k = 0; k < rank; k++) {
			r[1 + k] = od[k];
		}
		int off = 1 + rank;
		int x0 = 1 + dx.length;
		int y0 = 1 + dy.length;
		int[] idx = new int[rank];
		int ox = 0;
		int oy = 0;
		for (int k = 0; k < total; k++) {
			r[off + k] = laApply(op, x[x0 + ox], y[y0 + oy]);
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
		return r;
	}

	private static float @Nullable [] laBcastFF(int op, float[] x, float[] y) {
		int[] dx = laDims(x);
		int[] dy = laDims(y);
		int[] od = laBcastShape(dx, dy);
		if (od == null) {
			return null;
		}
		int rank = od.length;
		int[] sx = laBcastStrides(dx, od);
		int[] sy = laBcastStrides(dy, od);
		int total = 1;
		for (int d : od) {
			total *= d;
		}
		float[] r = new float[1 + rank + total];
		r[0] = rank;
		for (int k = 0; k < rank; k++) {
			r[1 + k] = od[k];
		}
		int off = 1 + rank;
		int x0 = 1 + dx.length;
		int y0 = 1 + dy.length;
		int[] idx = new int[rank];
		int ox = 0;
		int oy = 0;
		for (int k = 0; k < total; k++) {
			r[off + k] = (float) laApply(op, x[x0 + ox], y[y0 + oy]);
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
		return r;
	}

	/**
	 * The axes form of {@code linalg:transpose} ({@code %la-transpose-axes}): a rank-n
	 * axis permutation, a pure copy so trivially bit-identical. The axes argument must be
	 * a proper list of integers forming a permutation of {@code 0..rank-1}; anything else
	 * -- nil (the defun's plain-transpose branch), a bare integer, a bad permutation (the
	 * defun's error) -- declines.
	 */
	static @Nullable Object laTransposeAxes(@Nullable Object a, @Nullable Object axes) {
		if (!laPacked(a)) {
			return null;
		}
		int rank = laRank(a);
		int[] perm = laPermutation(axes, rank);
		if (perm == null) {
			return null;
		}
		int[] dims = laDims(a);
		int[] strides = new int[rank];
		int acc = 1;
		for (int i = rank - 1; i >= 0; i--) {
			strides[i] = acc;
			acc *= dims[i];
		}
		int[] od = new int[rank];
		int[] os = new int[rank];
		for (int k = 0; k < rank; k++) {
			od[k] = dims[perm[k]];
			os[k] = strides[perm[k]];
		}
		int off = 1 + rank;
		int total = laTotal(a);
		if (a instanceof float[] x) {
			float[] r = new float[x.length];
			r[0] = rank;
			for (int k = 0; k < rank; k++) {
				r[1 + k] = od[k];
			}
			int[] idx = new int[rank];
			int src = 0;
			for (int k = 0; k < total; k++) {
				r[off + k] = x[off + src];
				for (int i = rank - 1; i >= 0; i--) {
					idx[i]++;
					src += os[i];
					if (idx[i] < od[i]) {
						break;
					}
					idx[i] = 0;
					src -= od[i] * os[i];
				}
			}
			return r;
		}
		double[] x = laDoubles(a);
		double[] r = new double[x.length];
		r[0] = rank;
		for (int k = 0; k < rank; k++) {
			r[1 + k] = od[k];
		}
		int[] idx = new int[rank];
		int src = 0;
		for (int k = 0; k < total; k++) {
			r[off + k] = x[off + src];
			for (int i = rank - 1; i >= 0; i--) {
				idx[i]++;
				src += os[i];
				if (idx[i] < od[i]) {
					break;
				}
				idx[i] = 0;
				src -= od[i] * os[i];
			}
		}
		return r;
	}

	/** A proper cons list of integers forming a permutation of {@code 0..rank-1}. */
	private static int @Nullable [] laPermutation(@Nullable Object axes, int rank) {
		int[] out = new int[rank];
		boolean[] seen = new boolean[rank];
		int count = 0;
		Object cursor = axes;
		while (cursor instanceof Object[] cell && cell.length == 2) {
			if (count >= rank || !(cell[0] instanceof Long l)) {
				return null;
			}
			long v = l;
			if (v < 0 || v >= rank || seen[(int) v]) {
				return null;
			}
			seen[(int) v] = true;
			out[count++] = (int) v;
			cursor = cell[1];
		}
		return cursor == null && count == rank ? out : null;
	}

	/**
	 * Normalizes a possibly negative integer axis argument against the rank
	 * ({@code %la-norm-axis}); a non-integer or out-of-range axis declines.
	 */
	private static @Nullable Integer laAxis(@Nullable Object v, int rank) {
		if (!(v instanceof Long l)) {
			return null;
		}
		long ax = l < 0 ? l + rank : l;
		return ax >= 0 && ax < rank ? Integer.valueOf((int) ax) : null;
	}

	/** The dims with the axis dropped -- or kept as extent 1 ({@code %la-axis-shape}). */
	private static int[] laAxisShape(int[] d, int ax, boolean keep) {
		int[] od = new int[keep ? d.length : d.length - 1];
		int k = 0;
		for (int i = 0; i < d.length; i++) {
			if (i != ax) {
				od[k++] = d[i];
			}
			else if (keep) {
				od[k++] = 1;
			}
		}
		return od;
	}

	static @Nullable Object laSumAxis(@Nullable Object a, @Nullable Object axis, @Nullable Object keepdims) {
		return laFoldAxis(OP_ADD, a, axis, keepdims);
	}

	static @Nullable Object laAmaxAxis(@Nullable Object a, @Nullable Object axis, @Nullable Object keepdims) {
		return laFoldAxis(OP_MAX, a, axis, keepdims);
	}

	static @Nullable Object laAminAxis(@Nullable Object a, @Nullable Object axis, @Nullable Object keepdims) {
		return laFoldAxis(OP_MIN, a, axis, keepdims);
	}

	/**
	 * The axis form of {@code sum}/{@code amax}/{@code amin} ({@code %la-fold-axis}) over
	 * the flat index {@code (o * axlen + j) * inner + i}. {@code OP_ADD} folds from the
	 * defun's {@code 0} seed with {@code j} from 0; {@code OP_MAX} / {@code OP_MIN} seed
	 * from the first element along the axis and fold the defun's
	 * {@code (if (> x acc) x acc)} -- the ACCUMULATOR wins ties/NaN, the opposite of the
	 * element-wise select -- with {@code j} from 1. Always accumulates in {@code double}:
	 * an axis fold is NOT a lane reduction, so the oracle's boxed double arithmetic is
	 * mirrored exactly at both widths. An empty axis declines (the defun errors for
	 * amax/amin and returns an INTEGER 0 for a keepdims-less vector sum); a vector
	 * without keepdims reduces to the scalar accumulator itself.
	 */
	private static @Nullable Object laFoldAxis(int op, @Nullable Object a, @Nullable Object axisv,
			@Nullable Object keepdims) {
		if (!laPacked(a)) {
			return null;
		}
		int rank = laRank(a);
		Integer axi = laAxis(axisv, rank);
		if (axi == null) {
			return null;
		}
		int ax = axi;
		int[] d = laDims(a);
		int axlen = d[ax];
		if (axlen == 0) {
			return null;
		}
		int outer = 1;
		int inner = 1;
		for (int i = 0; i < ax; i++) {
			outer *= d[i];
		}
		for (int i = ax + 1; i < d.length; i++) {
			inner *= d[i];
		}
		int off = 1 + rank;
		double[] acc = new double[outer * inner];
		for (int o = 0; o < outer; o++) {
			for (int i = 0; i < inner; i++) {
				int base = o * axlen * inner + i;
				double s;
				int j0;
				if (op == OP_ADD) {
					s = 0.0;
					j0 = 0;
				}
				else {
					s = laAt(a, off + base);
					j0 = 1;
				}
				for (int j = j0; j < axlen; j++) {
					double v = laAt(a, off + base + j * inner);
					if (op == OP_ADD) {
						s += v;
					}
					else if (op == OP_MAX ? v > s : v < s) {
						s = v;
					}
				}
				acc[o * inner + i] = s;
			}
		}
		boolean keep = keepdims != null;
		int[] od = laAxisShape(d, ax, keep);
		if (od.length == 0) {
			return acc[0];
		}
		// The result keeps the input's width (%la-etype): a store into a single-float
		// out narrows each accumulator once, the defun's own final store.
		if (a instanceof float[]) {
			float[] r = new float[1 + od.length + acc.length];
			r[0] = od.length;
			for (int k = 0; k < od.length; k++) {
				r[1 + k] = od[k];
			}
			for (int k = 0; k < acc.length; k++) {
				r[1 + od.length + k] = (float) acc[k];
			}
			return r;
		}
		double[] r = new double[1 + od.length + acc.length];
		r[0] = od.length;
		for (int k = 0; k < od.length; k++) {
			r[1 + k] = od[k];
		}
		System.arraycopy(acc, 0, r, 1 + od.length, acc.length);
		return r;
	}

	/** One element of a packed operand's flat store, widened to {@code double}. */
	private static double laAt(@Nullable Object a, int flat) {
		return a instanceof float[] f ? f[flat] : ((double[]) java.util.Objects.requireNonNull(a))[flat];
	}

	static @Nullable Object laArgmaxAxis(@Nullable Object a, @Nullable Object axis) {
		return laArgFoldAxis(true, a, axis);
	}

	static @Nullable Object laArgminAxis(@Nullable Object a, @Nullable Object axis) {
		return laArgFoldAxis(false, a, axis);
	}

	/**
	 * The axis form of {@code argmax}/{@code argmin} ({@code %la-argfold-axis}): the
	 * per-slice index of the first element winning the strict comparison along the axis,
	 * the axis always dropped. A vector reduces to the integer index itself; a higher
	 * rank fills a packed DOUBLE array of index values at any input width.
	 */
	private static @Nullable Object laArgFoldAxis(boolean max, @Nullable Object a, @Nullable Object axisv) {
		if (!laPacked(a)) {
			return null;
		}
		int rank = laRank(a);
		Integer axi = laAxis(axisv, rank);
		if (axi == null) {
			return null;
		}
		int ax = axi;
		int[] d = laDims(a);
		int axlen = d[ax];
		if (axlen == 0) {
			return null;
		}
		int outer = 1;
		int inner = 1;
		for (int i = 0; i < ax; i++) {
			outer *= d[i];
		}
		for (int i = ax + 1; i < d.length; i++) {
			inner *= d[i];
		}
		int off = 1 + rank;
		double[] idx = new double[outer * inner];
		for (int o = 0; o < outer; o++) {
			for (int i = 0; i < inner; i++) {
				int base = o * axlen * inner + i;
				double best = laAt(a, off + base);
				int bi = 0;
				for (int j = 1; j < axlen; j++) {
					double v = laAt(a, off + base + j * inner);
					if (max ? v > best : v < best) {
						best = v;
						bi = j;
					}
				}
				idx[o * inner + i] = bi;
			}
		}
		int[] od = laAxisShape(d, ax, false);
		if (od.length == 0) {
			return (long) idx[0];
		}
		double[] r = new double[1 + od.length + idx.length];
		r[0] = od.length;
		for (int k = 0; k < od.length; k++) {
			r[1 + k] = od[k];
		}
		System.arraycopy(idx, 0, r, 1 + od.length, idx.length);
		return r;
	}

	// --- CNN window unfolding: %la-im2col / %la-col2im ------------------------------
	// Pure index arithmetic mirroring the linalg.lisp defuns loop for loop -- no lanes,
	// just compiled loops in place of the boxed defun (im2col dominated the accelerated
	// convolution runs). im2col only copies elements; col2im accumulates two same-width
	// elements per store, which at f32 width IS the defun's widen-add-narrow round trip
	// (the exact double sum of two floats narrows to the correctly rounded float), so
	// both are bit-identical at both widths. The window guard requires both padded
	// extents non-negative, so the defun's floor is a plain truncating division.

	static @Nullable Object laIm2col(@Nullable Object a, @Nullable Object fhv, @Nullable Object fwv,
			@Nullable Object stridev, @Nullable Object padv) {
		if (!laPacked(a) || laRank(a) != 4) {
			return null;
		}
		int h = laDim(a, 2);
		int w = laDim(a, 3);
		long[] p = laWindow(h, w, fhv, fwv, stridev, padv);
		if (p == null) {
			return null;
		}
		int n = laDim(a, 0);
		int c = laDim(a, 1);
		int fh = (int) p[0], fw = (int) p[1], stride = (int) p[2], pad = (int) p[3], oh = (int) p[4], ow = (int) p[5];
		long rows = (long) n * oh * ow;
		long cols = (long) c * fh * fw;
		if (!laSizeFits(rows) || !laSizeFits(cols) || !laSizeFits(rows * cols)) {
			return null;
		}
		if (a instanceof float[] x) {
			float[] out = laNewMatF((int) rows, (int) cols);
			int dst = 3;
			for (int ni = 0; ni < n; ni++) {
				for (int yo = 0; yo < oh; yo++) {
					for (int xo = 0; xo < ow; xo++) {
						for (int ci = 0; ci < c; ci++) {
							for (int fy = 0; fy < fh; fy++) {
								int iy = yo * stride + fy - pad;
								if (iy >= 0 && iy < h) {
									int base = 5 + ((ni * c + ci) * h + iy) * w;
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
									// The whole filter row fell in the padding.
									dst += fw;
								}
							}
						}
					}
				}
			}
			return out;
		}
		double[] x = laDoubles(a);
		double[] out = laNewMat((int) rows, (int) cols);
		int dst = 3;
		for (int ni = 0; ni < n; ni++) {
			for (int yo = 0; yo < oh; yo++) {
				for (int xo = 0; xo < ow; xo++) {
					for (int ci = 0; ci < c; ci++) {
						for (int fy = 0; fy < fh; fy++) {
							int iy = yo * stride + fy - pad;
							if (iy >= 0 && iy < h) {
								int base = 5 + ((ni * c + ci) * h + iy) * w;
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

	static @Nullable Object laCol2im(@Nullable Object col, @Nullable Object dims, @Nullable Object fhv,
			@Nullable Object fwv, @Nullable Object stridev, @Nullable Object padv) {
		if (!laPacked(col)) {
			return null;
		}
		long[] d = laShape(dims);
		if (d == null || d.length != 4 || d[0] > Integer.MAX_VALUE || d[1] > Integer.MAX_VALUE
				|| d[2] > Integer.MAX_VALUE || d[3] > Integer.MAX_VALUE) {
			return null;
		}
		int n = (int) d[0];
		int c = (int) d[1];
		int h = (int) d[2];
		int w = (int) d[3];
		long[] p = laWindow(h, w, fhv, fwv, stridev, padv);
		if (p == null) {
			return null;
		}
		int fh = (int) p[0], fw = (int) p[1], stride = (int) p[2], pad = (int) p[3], oh = (int) p[4], ow = (int) p[5];
		long rows = (long) n * oh * ow;
		long cols = (long) c * fh * fw;
		long total = (long) n * c * ((long) h * w);
		if (!laSizeFits(rows) || !laSizeFits(cols) || !laSizeFits(rows * cols) || !laSizeFits(total)
				|| laTotal(col) != rows * cols) {
			return null;
		}
		int off = 1 + laRank(col);
		if (col instanceof float[] x) {
			float[] img = new float[5 + (int) total];
			img[0] = 4.0f;
			img[1] = n;
			img[2] = c;
			img[3] = h;
			img[4] = w;
			int src = off;
			for (int ni = 0; ni < n; ni++) {
				for (int yo = 0; yo < oh; yo++) {
					for (int xo = 0; xo < ow; xo++) {
						for (int ci = 0; ci < c; ci++) {
							for (int fy = 0; fy < fh; fy++) {
								int iy = yo * stride + fy - pad;
								if (iy >= 0 && iy < h) {
									int base = 5 + ((ni * c + ci) * h + iy) * w;
									int ix0 = xo * stride - pad;
									for (int fx = 0; fx < fw; fx++) {
										int ix = ix0 + fx;
										if (ix >= 0 && ix < w) {
											img[base + ix] += x[src];
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
		double[] x = laDoubles(col);
		double[] img = new double[5 + (int) total];
		img[0] = 4.0;
		img[1] = n;
		img[2] = c;
		img[3] = h;
		img[4] = w;
		int src = off;
		for (int ni = 0; ni < n; ni++) {
			for (int yo = 0; yo < oh; yo++) {
				for (int xo = 0; xo < ow; xo++) {
					for (int ci = 0; ci < c; ci++) {
						for (int fy = 0; fy < fh; fy++) {
							int iy = yo * stride + fy - pad;
							if (iy >= 0 && iy < h) {
								int base = 5 + ((ni * c + ci) * h + iy) * w;
								int ix0 = xo * stride - pad;
								for (int fx = 0; fx < fw; fx++) {
									int ix = ix0 + fx;
									if (ix >= 0 && ix < w) {
										img[base + ix] += x[src];
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

	/**
	 * Validates the four window parameters against the spatial extent {@code (h, w)}:
	 * Lisp integers, positive filter/stride, non-negative pad, and both padded extents
	 * non-negative. Returns {@code [fh, fw, stride, pad, oh, ow]}, or {@code null} to
	 * decline to the defun.
	 */
	private static long @Nullable [] laWindow(int h, int w, @Nullable Object fhv, @Nullable Object fwv,
			@Nullable Object stridev, @Nullable Object padv) {
		if (!(fhv instanceof Long fh) || !(fwv instanceof Long fw) || !(stridev instanceof Long stride)
				|| !(padv instanceof Long pad)) {
			return null;
		}
		if (fh < 1 || fw < 1 || stride < 1 || pad < 0 || fh > Integer.MAX_VALUE || fw > Integer.MAX_VALUE
				|| stride > Integer.MAX_VALUE || pad > Integer.MAX_VALUE) {
			return null;
		}
		long eh = h + 2L * pad - fh;
		long ew = w + 2L * pad - fw;
		if (eh < 0 || ew < 0) {
			return null;
		}
		long oh = eh / stride + 1;
		long ow = ew / stride + 1;
		if (oh > Integer.MAX_VALUE || ow > Integer.MAX_VALUE) {
			return null;
		}
		return new long[] { fh, fw, stride, pad, oh, ow };
	}

	private static boolean laSizeFits(long total) {
		return total >= 0 && total <= Integer.MAX_VALUE - 8;
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
			float acc = 0.0f;
			if (n >= THRESHOLD) {
				FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
				int bound = FSPECIES_REDUCE.loopBound(n);
				for (; i < bound; i += FSPECIES_REDUCE.length()) {
					vacc = vacc.add(FloatVector.fromArray(FSPECIES_REDUCE, w, base + i)
						.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, ox + i)));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < n; i++) {
				acc += w[base + i] * x[ox + i];
			}
			r[or + row] = acc;
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
	 * {@code sum(x)} accumulated in {@code float} (four pinned lanes plus a {@code float}
	 * tail) and promoted to the f64 return value once. See {@link #dotF} for why.
	 */
	private static double sumF(float[] x) {
		int ox = 1 + (int) x[0];
		int n = x.length - ox;
		int i = 0;
		float acc = 0.0f;
		if (n >= THRESHOLD) {
			FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
			int bound = FSPECIES_REDUCE.loopBound(n);
			for (; i < bound; i += FSPECIES_REDUCE.length()) {
				vacc = vacc.add(FloatVector.fromArray(FSPECIES_REDUCE, x, ox + i));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[ox + i];
		}
		return acc;
	}

	/**
	 * {@code dot(x, y)} multiplied and accumulated entirely in {@code float}, promoted to
	 * the f64 return value once -- the contract the WASM {@code --simd} kernels already
	 * follow ("each width computes in its own native precision").
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
	private static double dotF(float[] x, float[] y) {
		int ox = 1 + (int) x[0];
		int oy = 1 + (int) y[0];
		int n = Math.min(x.length - ox, y.length - oy);
		int i = 0;
		float acc = 0.0f;
		if (n >= THRESHOLD) {
			FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
			int bound = FSPECIES_REDUCE.loopBound(n);
			for (; i < bound; i += FSPECIES_REDUCE.length()) {
				vacc = vacc.add(FloatVector.fromArray(FSPECIES_REDUCE, x, ox + i)
					.mul(FloatVector.fromArray(FSPECIES_REDUCE, y, oy + i)));
			}
			acc = vacc.reduceLanes(VectorOperators.ADD);
		}
		for (; i < n; i++) {
			acc += x[ox + i] * y[oy + i];
		}
		return acc;
	}

	/**
	 * {@code matvec(W, x)} for a f32 matrix and vector: each output element {@code r[i]}
	 * is the f32-accumulated dot of row {@code i} of {@code W} with {@code x}
	 * ({@link #dotF} once per row), stored at single-float width so the result keeps the
	 * width of its operands.
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
			float acc = 0.0f;
			if (n >= THRESHOLD) {
				FloatVector vacc = FloatVector.zero(FSPECIES_REDUCE);
				int bound = FSPECIES_REDUCE.loopBound(n);
				for (; i < bound; i += FSPECIES_REDUCE.length()) {
					vacc = vacc.add(FloatVector.fromArray(FSPECIES_REDUCE, w, base + i)
						.mul(FloatVector.fromArray(FSPECIES_REDUCE, x, ox + i)));
				}
				acc = vacc.reduceLanes(VectorOperators.ADD);
			}
			for (; i < n; i++) {
				acc += w[base + i] * x[ox + i];
			}
			r[2 + row] = acc;
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
