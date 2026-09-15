package am.ik.rontolisp;

import java.math.BigInteger;

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
	 * Returns the closest {@code double} approximation of this rational, correctly
	 * rounded (round-half-even per IEEE 754): the exact binary quotient is computed with
	 * {@link BigInteger} arithmetic -- a 56-bit head plus the remainder as the sticky bit
	 * -- and only then narrowed to a double, so no intermediate decimal or double
	 * rounding can move the answer. In particular an exactly representable quotient (e.g.
	 * {@code 1/8388608} is {@code 2^-23}) converts back exactly, which is what the
	 * {@code RATIONAL.1}/{@code RATIONALIZE.1}/{@code /.12} round trips require. Huge
	 * magnitudes answer signed infinity, tinies denormalize down to signed zero. The JVM
	 * backend's generated {@code _ratToDouble} and the WASM backend's {@code _as_f64}
	 * ratio arm (an f64 division, correctly rounded for the i32 components it can hold)
	 * answer bit-identically; see the {@code floatOfRatio}-family pinning tests on each
	 * backend.
	 * @return the double approximation
	 */
	public double doubleValue() {
		return ratioToDouble(this.numerator, this.denominator);
	}

	/**
	 * The correctly-rounded {@code double} nearest {@code num}/{@code den}.
	 * @param num the numerator (any sign; zero answers positive zero)
	 * @param den the denominator (non-zero; a negative sign is honored)
	 * @return the nearest double, ties to even
	 */
	static double ratioToDouble(BigInteger num, BigInteger den) {
		if (den.signum() == 0) {
			throw new ArithmeticException("Division by zero");
		}
		if (num.signum() == 0) {
			// Unreachable through valueOf (a zero numerator demotes to an integer),
			// kept so a hand-built pair still has a defined conversion.
			return 0.0;
		}
		boolean neg = num.signum() < 0;
		BigInteger n = neg ? num.negate() : num;
		BigInteger d = den.signum() < 0 ? den.negate() : den;
		// exp = floor(log2(n/d)): the bit-length difference, adjusted down by one
		// when the shifted denominator still overshoots the numerator.
		int exp = n.bitLength() - d.bitLength();
		if (exp >= 0 ? n.compareTo(d.shiftLeft(exp)) < 0 : n.shiftLeft(-exp).compareTo(d) < 0) {
			exp--;
		}
		if (exp > 1023) {
			return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
		}
		double mag;
		if (exp >= -1022) {
			// Normal range: q = floor(n * 2^(55-exp) / d) holds 56 bits (the 53-bit
			// mantissa plus guard, round and one sticky bit); the true remainder
			// joins the low two bits as the rest of the sticky bit.
			int shift = 55 - exp;
			BigInteger[] qr = shift >= 0 ? n.shiftLeft(shift).divideAndRemainder(d)
					: n.divideAndRemainder(d.shiftLeft(-shift));
			long q = qr[0].longValue();
			boolean round = (q & 4L) != 0;
			boolean sticky = (q & 3L) != 0 || qr[1].signum() != 0;
			long m = q >>> 3;
			if (round && (sticky || (m & 1L) != 0)) {
				m++;
			}
			int e = exp;
			if (m == 1L << 53) {
				m >>>= 1;
				e++;
			}
			if (e > 1023) {
				return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
			}
			mag = Double.longBitsToDouble(((long) (e + 1023) << 52) | (m & 0xF_FFFF_FFFF_FFFFL));
		}
		else {
			// Subnormal range: k = round-half-even(n * 2^1074 / d) is the mantissa
			// directly; a k of 2^52 is the smallest normal, reached by rounding up.
			BigInteger[] qr = n.shiftLeft(1074).divideAndRemainder(d);
			BigInteger k = qr[0];
			int cmp = qr[1].shiftLeft(1).compareTo(d);
			if (cmp > 0 || (cmp == 0 && k.testBit(0))) {
				k = k.add(BigInteger.ONE);
			}
			mag = Double.longBitsToDouble(k.longValue());
		}
		return neg ? -mag : mag;
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
