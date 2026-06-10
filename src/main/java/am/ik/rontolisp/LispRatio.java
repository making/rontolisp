package am.ik.rontolisp;

import java.math.BigInteger;

/**
 * A normalized rational value (numerator/denominator).
 *
 * @param numerator the numerator
 * @param denominator the denominator (always positive)
 */
public record LispRatio(BigInteger numerator, BigInteger denominator) implements LispVal {

	public LispRatio {
		if (denominator.signum() == 0) {
			throw new IllegalArgumentException("Denominator must not be zero");
		}
		if (denominator.signum() < 0) {
			numerator = numerator.negate();
			denominator = denominator.negate();
		}
		BigInteger gcd = numerator.gcd(denominator);
		if (!BigInteger.ONE.equals(gcd)) {
			numerator = numerator.divide(gcd);
			denominator = denominator.divide(gcd);
		}
	}

	public double doubleValue() {
		return this.numerator.doubleValue() / this.denominator.doubleValue();
	}

	@Override
	public String print() {
		return this.numerator + "/" + this.denominator;
	}

}
