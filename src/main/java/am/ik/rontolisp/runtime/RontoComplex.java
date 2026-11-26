package am.ik.rontolisp.runtime;

/**
 * The JVM-backend runtime representation of a complex number: the two real parts side by
 * side. A dedicated holder rather than an {@code Object[]} pair, because a pair is
 * structurally identical to a cons cell ({@code Object[2]} of car and cdr) and would
 * answer {@code consp} (and every other cons-shaped predicate) wrongly; an
 * {@code instanceof} test keeps every existing predicate answering without an exclusion
 * arm (`.kb/jvm-complex.md`).
 *
 * <p>
 * The parts are the compiled real representations ({@code Long}, {@code BigInteger},
 * {@code BigInteger[2]} ratios, {@code Double}) and are always canonical like the
 * interpreter's {@code LispComplex}: a rational zero imaginary part demotes to the real
 * itself (so no holder exists for it), while a float zero stays complex. Construction
 * canonicalizes through the generated {@code _ccomplex} helper; this class itself stays
 * dumb so it keeps importing nothing and keeps travelling beside a compiled program that
 * builds one ({@code .kb/jvm-export.md}, "What travels").
 *
 * <p>
 * Equality is part-wise over the compiled representations, which is the interpreter's
 * {@code eql} over the source values: both sides canonicalize through the same
 * normalize-then-box discipline, so equal values always carry equal shapes and a pairwise
 * comparison decides it.
 */
public final class RontoComplex {

	/**
	 * The real part: a {@code Long}, {@code BigInteger}, {@code BigInteger[2]} or
	 * {@code Double}.
	 */
	public final Object real;

	/**
	 * The imaginary part: a {@code Long}, {@code BigInteger}, {@code BigInteger[2]} or
	 * {@code Double}.
	 */
	public final Object imag;

	/**
	 * Creates a complex value from its two canonical parts.
	 * @param real the real part
	 * @param imag the imaginary part
	 */
	public RontoComplex(Object real, Object imag) {
		this.real = real;
		this.imag = imag;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof RontoComplex complex)) {
			return false;
		}
		return partEquals(this.real, complex.real) && partEquals(this.imag, complex.imag);
	}

	@Override
	public int hashCode() {
		return 31 * partHash(this.real) + partHash(this.imag);
	}

	private static boolean partEquals(Object a, Object b) {
		if (a instanceof java.math.BigInteger[] x && b instanceof java.math.BigInteger[] y) {
			return java.util.Arrays.equals(x, y);
		}
		if (a instanceof java.math.BigInteger[] || b instanceof java.math.BigInteger[]) {
			return false;
		}
		return a.equals(b);
	}

	private static int partHash(Object value) {
		if (value instanceof java.math.BigInteger[] pair) {
			return java.util.Arrays.hashCode(pair);
		}
		return value.hashCode();
	}

}
