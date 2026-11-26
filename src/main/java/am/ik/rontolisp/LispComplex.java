package am.ik.rontolisp;

/**
 * A complex number value, printed as {@code #C(real imag)} (e.g., {@code #C(1 2)}).
 * Instances are always canonical, like SBCL: a rational zero imaginary part demotes to
 * the real part (so {@link #valueOf} answers a real), while a float zero stays complex
 * (an integer zero coerced to {@code 0.0} when the other part is a float); a rational
 * part beside a float part is coerced to a float, so exact rationals stay exact only when
 * both parts are rational.
 *
 * @param real the real part (an integer, ratio or float, never a complex)
 * @param imag the imaginary part (an integer, ratio or float, never a complex)
 */
public record LispComplex(LispVal real, LispVal imag) implements LispVal {

	/**
	 * Validates that both parts are real numbers.
	 * @param real the real part
	 * @param imag the imaginary part
	 */
	public LispComplex {
		if (!isRealPart(real) || !isRealPart(imag)) {
			throw new IllegalArgumentException(
					"complex parts must be real numbers, got: " + real.print() + " " + imag.print());
		}
	}

	/**
	 * Whether the value may stand as a complex part: an integer, ratio or float. A nested
	 * complex is rejected, like SBCL.
	 * @param value the candidate part
	 * @return whether it is a real number
	 */
	public static boolean isRealPart(LispVal value) {
		return value instanceof LispInteger || value instanceof LispBigInteger || value instanceof LispRatio
				|| value instanceof LispDouble;
	}

	/**
	 * Creates the canonical value for the given parts: a rational zero imaginary part
	 * demotes to the real part itself; a float anywhere coerces both parts to floats (a
	 * float zero never demotes); otherwise the parts stay exact.
	 * @param real the real part (must be real, never a complex)
	 * @param imag the imaginary part (must be real, never a complex)
	 * @return the demoted real or a new complex value
	 * @throws IllegalArgumentException if either part is not a real number
	 */
	public static LispVal valueOf(LispVal real, LispVal imag) {
		if (!isRealPart(real) || !isRealPart(imag)) {
			throw new IllegalArgumentException(
					"complex parts must be real numbers, got: " + real.print() + " " + imag.print());
		}
		if (real instanceof LispDouble || imag instanceof LispDouble) {
			return new LispComplex(new LispDouble(toDouble(real)), new LispDouble(toDouble(imag)));
		}
		if (isRationalZero(imag)) {
			return real;
		}
		return new LispComplex(real, imag);
	}

	/**
	 * Whether the value is a rational zero (an integer or bignum zero; a normalized ratio
	 * is never zero).
	 * @param value the value
	 * @return whether it is a rational zero
	 */
	public static boolean isRationalZero(LispVal value) {
		if (value instanceof LispInteger i) {
			return i.value() == 0;
		}
		if (value instanceof LispBigInteger b) {
			return b.value().signum() == 0;
		}
		return false;
	}

	private static double toDouble(LispVal value) {
		if (value instanceof LispDouble d) {
			return d.value();
		}
		if (value instanceof LispInteger i) {
			return (double) i.value();
		}
		if (value instanceof LispBigInteger b) {
			return b.value().doubleValue();
		}
		if (value instanceof LispRatio r) {
			return r.doubleValue();
		}
		throw new IllegalArgumentException("complex parts must be real numbers, got: " + value.print());
	}

	@Override
	public String print() {
		return "#C(" + this.real.print() + " " + this.imag.print() + ")";
	}

}
