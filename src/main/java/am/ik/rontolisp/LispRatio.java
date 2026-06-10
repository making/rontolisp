package am.ik.rontolisp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * An exact rational number (Common Lisp ratio), printed as {@code numerator/denominator}
 * (e.g., {@code 1/3}). Instances are always normalized: the numerator and denominator are
 * coprime and the denominator is greater than one. A rational whose denominator reduces
 * to one is represented as {@link LispInteger} (or {@link LispBigInteger}), so use
 * {@link #valueOf(BigInteger, BigInteger)} to construct rational values.
 *
 * @param numerator the numerator (carries the sign)
 * @param denominator the denominator (always greater than one)
 */
public record LispRatio(BigInteger numerator, BigInteger denominator) implements LispVal {

	/**
	 * Validates the normalization invariants.
	 * @param numerator the numerator
	 * @param denominator the denominator
	 */
	public LispRatio {
		if (denominator.signum() <= 0 || denominator.equals(BigInteger.ONE)
				|| !numerator.gcd(denominator).equals(BigInteger.ONE)) {
			throw new IllegalArgumentException(
					"Ratio must be normalized (coprime, denominator > 1): " + numerator + "/" + denominator);
		}
	}

	/**
	 * Creates a normalized rational value: the sign is moved to the numerator, the
	 * fraction is reduced by the gcd, and a result whose denominator is one is demoted to
	 * {@link LispInteger} (or {@link LispBigInteger} outside the {@code long} range).
	 * @param numerator the numerator
	 * @param denominator the denominator (must be non-zero)
	 * @return the normalized rational value
	 * @throws ArithmeticException if the denominator is zero
	 */
	public static LispVal valueOf(BigInteger numerator, BigInteger denominator) {
		if (denominator.signum() == 0) {
			throw new ArithmeticException("Division by zero");
		}
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}
		BigInteger gcd = numerator.gcd(denominator);
		if (!gcd.equals(BigInteger.ONE)) {
			numerator = numerator.divide(gcd);
			denominator = denominator.divide(gcd);
		}
		if (denominator.equals(BigInteger.ONE)) {
			// bitLength() < 64 holds exactly for the signed long range [-2^63, 2^63-1].
			return numerator.bitLength() < 64 ? new LispInteger(numerator.longValue()) : new LispBigInteger(numerator);
		}
		return new LispRatio(numerator, denominator);
	}

	/**
	 * Returns the closest {@code double} approximation of this rational. Computed via
	 * {@link BigDecimal} division so that ratios of huge integers do not overflow to
	 * infinity before the final rounding.
	 * @return the double approximation
	 */
	public double doubleValue() {
		return new BigDecimal(this.numerator).divide(new BigDecimal(this.denominator), MathContext.DECIMAL64)
			.doubleValue();
	}

	/**
	 * Returns the integer obtained by truncating this rational toward zero.
	 * @return the truncated integer
	 */
	public BigInteger truncate() {
		return this.numerator.divide(this.denominator);
	}

	/**
	 * Returns the largest integer less than this rational.
	 * @return the floor integer
	 */
	public BigInteger floor() {
		// mod() is non-negative because the denominator is always positive.
		return this.numerator.subtract(this.numerator.mod(this.denominator)).divide(this.denominator);
	}

	/**
	 * Returns the smallest integer greater than this rational.
	 * @return the ceiling integer
	 */
	public BigInteger ceiling() {
		// A normalized ratio is never an integer, so ceiling is always floor + 1.
		return floor().add(BigInteger.ONE);
	}

	/**
	 * Returns the integer nearest to this rational, rounding ties to the even integer
	 * (Common Lisp {@code round} semantics).
	 * @return the rounded integer
	 */
	public BigInteger round() {
		BigInteger floor = floor();
		BigInteger remainder = this.numerator.subtract(floor.multiply(this.denominator));
		int cmp = remainder.shiftLeft(1).compareTo(this.denominator);
		if (cmp < 0) {
			return floor;
		}
		if (cmp > 0) {
			return floor.add(BigInteger.ONE);
		}
		return floor.testBit(0) ? floor.add(BigInteger.ONE) : floor;
	}

	@Override
	public String print() {
		return this.numerator + "/" + this.denominator;
	}

}
