package am.ik.rontolisp.eval;

import java.math.BigInteger;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispVal;

import org.jspecify.annotations.Nullable;

/**
 * The exact quotient of the {@code floor}/{@code ceiling}/{@code round}/{@code truncate}
 * family when a float is involved.
 * <p>
 * A finite double is an exact binary rational, so {@code a/b} over two of them has one
 * mathematical value and one correctly rounded integer -- which is a BIGNUM past the
 * {@code long} range, exactly as every other numeric operator in rontolisp promotes
 * rather than saturating. Computing the quotient as a double instead rounds twice (the
 * division rounds, then the narrowing clamps), and the remainder derived from that
 * quotient breaks {@code quotient*divisor + remainder = number}, which CLHS states
 * outright. See {@code .kb/linalg-simd.md}, "mod/rem".
 */
final class ExactRounding {

	static final int TRUNCATE = 0;

	static final int FLOOR = 1;

	static final int CEILING = 2;

	static final int ROUND = 3;

	/** 2^63 as a double: the first magnitude a {@code long} cannot hold. */
	private static final double LONG_LIMIT = 9.223372036854776E18;

	private ExactRounding() {
	}

	/**
	 * The exact quotient of {@code a/b} under one of the four rounding modes, or
	 * {@code null} when the operands are not a float paired with a float or an exact
	 * integer (a ratio operand, a non-finite dividend and a zero divisor all decline, so
	 * the caller keeps its ordinary route).
	 * <p>
	 * An INFINITE divisor is settled by sign rather than by the rational route (infinity
	 * is not a rational, so {@link #rationalOf} declines on it): with a finite nonzero
	 * dividend, {@code a/b} is an infinitesimal whose magnitude is always under 1/2, so
	 * truncate and round are always 0, and floor/ceiling read off whether the dividend
	 * and the divisor agree in sign. See {@code .kb/linalg-simd.md}, "mod/rem".
	 * @param a the dividend
	 * @param b the divisor
	 * @param mode {@link #TRUNCATE}, {@link #FLOOR}, {@link #CEILING} or {@link #ROUND}
	 * @return the exact quotient, or {@code null} to decline
	 */
	static @Nullable LispVal quotient(LispVal a, LispVal b, int mode) {
		if (!(a instanceof LispDouble) && !(b instanceof LispDouble)) {
			return null;
		}
		if (b instanceof LispDouble bd && Double.isInfinite(bd.value())) {
			return infiniteDivisorQuotient(a, bd.value() > 0, mode);
		}
		BigInteger[] ra = rationalOf(a);
		BigInteger[] rb = rationalOf(b);
		if (ra == null || rb == null || rb[0].signum() == 0) {
			return null;
		}
		BigInteger num = ra[0].multiply(rb[1]);
		BigInteger den = ra[1].multiply(rb[0]);
		if (den.signum() < 0) {
			num = num.negate();
			den = den.negate();
		}
		return normalize(exactQuotient(num, den, mode));
	}

	/**
	 * The quotient for a finite dividend over an infinite divisor: {@code a/b} is then an
	 * infinitesimal whose sign is the dividend's sign XOR the divisor's, and whose
	 * magnitude is always under 1/2. Truncate and round are 0 either way; floor and
	 * ceiling round the infinitesimal down or up, so they read off whether the two signs
	 * agree. Declines (returns {@code null}) for a ratio operand, a non-finite dividend
	 * or an EXACT zero dividend -- {@code 0/infinity} is genuinely zero, not an
	 * infinitesimal, and the old f64 route already answers that correctly.
	 */
	private static @Nullable LispVal infiniteDivisorQuotient(LispVal a, boolean divisorPositive, int mode) {
		int signA;
		if (a instanceof LispInteger i) {
			signA = Long.signum(i.value());
		}
		else if (a instanceof LispBigInteger b) {
			signA = b.value().signum();
		}
		else if (a instanceof LispDouble d && Double.isFinite(d.value())) {
			double v = d.value();
			signA = v == 0.0 ? 0 : (v > 0 ? 1 : -1);
		}
		else {
			return null;
		}
		if (signA == 0) {
			return null;
		}
		boolean sameSign = (signA > 0) == divisorPositive;
		return switch (mode) {
			case FLOOR -> new LispInteger(sameSign ? 0 : -1);
			case CEILING -> new LispInteger(sameSign ? 1 : 0);
			default -> new LispInteger(0);
		};
	}

	/**
	 * The exact integer a finite double rounds to, as a bignum when it does not fit a
	 * {@code long}. A non-finite double keeps the saturating narrowing (there is no
	 * integer to answer, and the float rounders do not signal here).
	 * @param d the float to convert
	 * @param mode {@link #TRUNCATE}, {@link #FLOOR}, {@link #CEILING} or {@link #ROUND}
	 * @return the integer value
	 */
	static LispVal floatToInteger(double d, int mode) {
		double rounded = round(d, mode);
		if (rounded > -LONG_LIMIT && rounded < LONG_LIMIT) {
			return new LispInteger((long) rounded);
		}
		if (!Double.isFinite(rounded)) {
			return new LispInteger((long) rounded);
		}
		// A finite double at this magnitude is already a mathematical integer, so the
		// widening is exact and needs no rounding decision of its own.
		return normalize(exactNumerator(rounded));
	}

	/** Applies the mode's f64 rounding (what {@code Math} spells for each). */
	private static double round(double d, int mode) {
		return switch (mode) {
			case FLOOR -> Math.floor(d);
			case CEILING -> Math.ceil(d);
			case ROUND -> Math.rint(d);
			default -> d < 0 ? Math.ceil(d) : Math.floor(d);
		};
	}

	/**
	 * {numerator, denominator} of the exact value of a float or an exact integer, or
	 * {@code null} for anything else (a ratio, a non-finite float, a non-number).
	 */
	private static BigInteger @Nullable [] rationalOf(LispVal val) {
		if (val instanceof LispInteger i) {
			return new BigInteger[] { BigInteger.valueOf(i.value()), BigInteger.ONE };
		}
		if (val instanceof LispBigInteger b) {
			return new BigInteger[] { b.value(), BigInteger.ONE };
		}
		if (val instanceof LispDouble d && Double.isFinite(d.value())) {
			double v = d.value();
			int exponent = binaryExponent(v);
			BigInteger mantissa = BigInteger.valueOf(signedMantissa(v));
			return exponent >= 0 ? new BigInteger[] { mantissa.shiftLeft(exponent), BigInteger.ONE }
					: new BigInteger[] { mantissa, BigInteger.ONE.shiftLeft(-exponent) };
		}
		return null;
	}

	/** The exact integer value of a double already known to be integral. */
	private static BigInteger exactNumerator(double d) {
		int exponent = binaryExponent(d);
		BigInteger mantissa = BigInteger.valueOf(signedMantissa(d));
		return exponent >= 0 ? mantissa.shiftLeft(exponent) : mantissa.shiftRight(-exponent);
	}

	/** The {@code e} of {@code d = m * 2^e} with {@code m} the integer mantissa. */
	private static int binaryExponent(double d) {
		int biased = (int) ((Double.doubleToRawLongBits(d) >> 52) & 0x7ff);
		return biased == 0 ? -1074 : biased - 1075;
	}

	/** The {@code m} of {@code d = m * 2^e}, sign included. */
	private static long signedMantissa(double d) {
		long bits = Double.doubleToRawLongBits(d);
		long fraction = bits & 0x000f_ffff_ffff_ffffL;
		int biased = (int) ((bits >> 52) & 0x7ff);
		long mantissa = biased == 0 ? fraction : fraction | (1L << 52);
		return bits < 0 ? -mantissa : mantissa;
	}

	/**
	 * The exact quotient of {@code num/den} (the denominator positive) under one of the
	 * four rounding modes -- {@code round} is ties-to-even, as Common Lisp requires.
	 */
	private static BigInteger exactQuotient(BigInteger num, BigInteger den, int mode) {
		BigInteger[] qr = num.divideAndRemainder(den);
		if (qr[1].signum() == 0) {
			return qr[0];
		}
		BigInteger floor = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
		switch (mode) {
			case TRUNCATE:
				return qr[0];
			case FLOOR:
				return floor;
			case CEILING:
				return floor.add(BigInteger.ONE);
			default:
				// The floor remainder lies in [0, den); double it to place the fraction
				// against a half, and settle the tie on the floor quotient's parity.
				BigInteger rest = num.subtract(floor.multiply(den));
				int cmp = rest.shiftLeft(1).compareTo(den);
				if (cmp < 0) {
					return floor;
				}
				return cmp > 0 || floor.testBit(0) ? floor.add(BigInteger.ONE) : floor;
		}
	}

	/** Demotes a bignum back to a fixnum when it fits, the one-representation rule. */
	private static LispVal normalize(BigInteger value) {
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

}
