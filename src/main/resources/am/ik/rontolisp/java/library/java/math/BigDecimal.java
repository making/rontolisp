// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A decimal number: an integer of any size and the count of its digits that stand after
// the point (a negative count is so many zeros ahead of it). Each operation is the one
// the specification gives -- the exact result at its preferred scale, then one rounding
// of an integer quotient where a precision or a scale asks for it. What the
// specification leaves open -- the words of a refusal and which comes first, the scale
// of a result written with fewer digits than its preferred scale asks, the hash code --
// is a JDK 25's answers to a probe (.kb/java-frontend.md, "java.math").
package java.math;

import rontolisp.internal.Exact;

public class BigDecimal extends Number implements Comparable<BigDecimal>, Exact {

    public static final BigDecimal ZERO = new BigDecimal(BigInteger.ZERO, 0);

    public static final BigDecimal ONE = new BigDecimal(BigInteger.ONE, 0);

    public static final BigDecimal TWO = new BigDecimal(BigInteger.TWO, 0);

    public static final BigDecimal TEN = new BigDecimal(BigInteger.TEN, 0);

    @Deprecated
    public static final int ROUND_UP = 0;

    @Deprecated
    public static final int ROUND_DOWN = 1;

    @Deprecated
    public static final int ROUND_CEILING = 2;

    @Deprecated
    public static final int ROUND_FLOOR = 3;

    @Deprecated
    public static final int ROUND_HALF_UP = 4;

    @Deprecated
    public static final int ROUND_HALF_DOWN = 5;

    @Deprecated
    public static final int ROUND_HALF_EVEN = 6;

    @Deprecated
    public static final int ROUND_UNNECESSARY = 7;

    private final BigInteger unscaled;

    private final int scale;

    /** How many digits the unscaled value has; 0 until it is asked. */
    private int digits;

    public BigDecimal(BigInteger unscaledVal, int scale) {
        if (unscaledVal == null) {
            throw new NullPointerException();
        }
        this.unscaled = unscaledVal;
        this.scale = scale;
    }

    public BigDecimal(BigInteger unscaledVal, int scale, MathContext mc) {
        this(new BigDecimal(unscaledVal, scale).round(mc));
    }

    public BigDecimal(BigInteger val) {
        this(val, 0);
    }

    public BigDecimal(BigInteger val, MathContext mc) {
        this(val, 0, mc);
    }

    public BigDecimal(int val) {
        this(BigInteger.valueOf(val), 0);
    }

    public BigDecimal(int val, MathContext mc) {
        this(BigInteger.valueOf(val), 0, mc);
    }

    public BigDecimal(long val) {
        this(BigInteger.valueOf(val), 0);
    }

    public BigDecimal(long val, MathContext mc) {
        this(BigInteger.valueOf(val), 0, mc);
    }

    /** The double's own value, every digit of it. */
    public BigDecimal(double val) {
        this(binary(val));
    }

    public BigDecimal(double val, MathContext mc) {
        this(binary(val).round(mc));
    }

    public BigDecimal(String val) {
        this(read(val, true));
    }

    public BigDecimal(String val, MathContext mc) {
        this(read(val, true).round(mc));
    }

    public BigDecimal(char[] in) {
        this(read(new String(in), true));
    }

    public BigDecimal(char[] in, MathContext mc) {
        this(read(new String(in), true).round(mc));
    }

    public BigDecimal(char[] in, int offset, int len) {
        this(read(ranged(in, offset, len), offset + len == in.length));
    }

    public BigDecimal(char[] in, int offset, int len, MathContext mc) {
        this(read(ranged(in, offset, len), offset + len == in.length).round(mc));
    }

    private BigDecimal(BigDecimal made) {
        this.unscaled = made.unscaled;
        this.scale = made.scale;
    }

    private static String ranged(char[] in, int offset, int len) {
        if (offset < 0 || len < 0 || len > in.length - offset) {
            throw new NumberFormatException("Bad offset or len arguments for char[] input.");
        }
        return new String(in, offset, len);
    }

    /**
     * A text as a number: an optional sign, digits with at most one point, an optional
     * exponent. The exponent is read before the digits are counted, so a text with no
     * digit and a bad exponent is refused for its exponent. A text that stops where a
     * character has to follow is refused with no message where it ends its array (as
     * every string does), and by name where it is a range ending inside one.
     */
    private static BigDecimal read(String text, boolean ends) {
        int length = text.length();
        if (length == 0) {
            throw ends ? new NumberFormatException() : new NumberFormatException("No digits found.");
        }
        char first = text.charAt(0);
        int at = first == '-' || first == '+' ? 1 : 0;
        StringBuilder whole = new StringBuilder();
        int fraction = -1;
        long exponent = 0;
        for (; at < length; at++) {
            char c = text.charAt(at);
            int digit = Character.digit(c, 10);
            if (digit >= 0) {
                whole.append((char) ('0' + digit));
                if (fraction >= 0) {
                    fraction++;
                }
            }
            else if (c == '.') {
                if (fraction >= 0) {
                    throw new NumberFormatException("Character array contains more than one decimal point.");
                }
                fraction = 0;
            }
            else if (c == 'e' || c == 'E') {
                exponent = exponent(text, at + 1, ends);
                break;
            }
            else {
                throw new NumberFormatException("Character " + c
                        + " is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.");
            }
        }
        if (whole.length() == 0) {
            throw new NumberFormatException("No digits found.");
        }
        long scale = Math.max(fraction, 0) - exponent;
        if (scale > Integer.MAX_VALUE || scale < Integer.MIN_VALUE) {
            throw new NumberFormatException("Exponent overflow.");
        }
        BigInteger unscaled = new BigInteger(whole.toString());
        return new BigDecimal(first == '-' ? unscaled.negate() : unscaled, (int) scale);
    }

    /** The exponent a text ends with: an optional sign and at most ten digits past its zeros. */
    private static long exponent(String text, int at, boolean ends) {
        int length = text.length();
        char sign = at < length ? text.charAt(at) : '0';
        if (sign == '-' || sign == '+') {
            at++;
        }
        if (at == length) {
            throw ends ? new NumberFormatException() : new NumberFormatException("No exponent digits.");
        }
        while (at < length - 1 && text.charAt(at) == '0') {
            at++;
        }
        if (length - at > 10) {
            throw new NumberFormatException("Too many nonzero exponent digits.");
        }
        long exponent = 0;
        for (; at < length; at++) {
            int digit = Character.digit(text.charAt(at), 10);
            if (digit < 0) {
                throw new NumberFormatException("Not a digit.");
            }
            exponent = exponent * 10 + digit;
        }
        return sign == '-' ? -exponent : exponent;
    }

    /**
     * A double as the decimal number it is: its fraction times a power of two, a
     * negative power being that power of five over the power of ten.
     */
    private static BigDecimal binary(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) {
            throw new NumberFormatException("Infinite or NaN");
        }
        long bits = Double.doubleToLongBits(val);
        int biased = (int) (bits >>> 52 & 0x7FF);
        long fraction = bits & 0xFFFFFFFFFFFFFL;
        if (biased == 0 && fraction == 0) {
            return ZERO;
        }
        int power = biased == 0 ? -1074 : biased - 1075;
        if (biased != 0) {
            fraction |= 1L << 52;
        }
        while (power < 0 && (fraction & 1) == 0) {
            fraction >>= 1;
            power++;
        }
        BigInteger unscaled = BigInteger.valueOf(bits < 0 ? -fraction : fraction);
        return power >= 0 ? new BigDecimal(unscaled.shiftLeft(power), 0)
                : new BigDecimal(unscaled.multiply(BigInteger.valueOf(5).pow(-power)), -power);
    }

    public static BigDecimal valueOf(long val) {
        return new BigDecimal(BigInteger.valueOf(val), 0);
    }

    public static BigDecimal valueOf(long unscaledVal, int scale) {
        return new BigDecimal(BigInteger.valueOf(unscaledVal), scale);
    }

    /** The number the double's shortest text names. */
    public static BigDecimal valueOf(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) {
            throw new NumberFormatException("Infinite or NaN");
        }
        return read(Double.toString(val), true);
    }

    // ---- what the operations share

    private static BigInteger tenTo(long power) {
        if (power > Integer.MAX_VALUE) {
            throw new ArithmeticException("Overflow");
        }
        return BigInteger.TEN.pow((int) power);
    }

    /** An integer with so many zeros written after it. */
    private static BigInteger raised(BigInteger value, long zeros) {
        return zeros == 0 || value.signum() == 0 ? value : value.multiply(tenTo(zeros));
    }

    /**
     * A scale that fits, or the refusal of one that does not; zero is written with the
     * nearest scale that fits.
     */
    private static int scaled(long scale, BigInteger value) {
        if (scale > Integer.MAX_VALUE) {
            if (value.signum() != 0) {
                throw new ArithmeticException("Underflow");
            }
            return Integer.MAX_VALUE;
        }
        if (scale < Integer.MIN_VALUE) {
            if (value.signum() != 0) {
                throw new ArithmeticException("Overflow");
            }
            return Integer.MIN_VALUE;
        }
        return (int) scale;
    }

    private static BigDecimal zero(long scale) {
        return new BigDecimal(BigInteger.ZERO, scaled(scale, BigInteger.ZERO));
    }

    /** The power of ten of the first digit: 0 for 1 to 9, -1 for 0.1 to 0.9. */
    private long lead() {
        return (long) precision() - this.scale - 1;
    }

    /** The quotient of two integers as an integer, rounded in a mode where it is none. */
    private static BigInteger divided(BigInteger dividend, BigInteger divisor, RoundingMode mode) {
        BigInteger[] parts = dividend.divideAndRemainder(divisor);
        if (parts[1].signum() == 0) {
            return parts[0];
        }
        int sign = dividend.signum() * divisor.signum();
        int half = parts[1].abs().shiftLeft(1).compareTo(divisor.abs());
        return away(mode, sign, half, parts[0].testBit(0)) ? parts[0].add(BigInteger.valueOf(sign)) : parts[0];
    }

    /**
     * Whether a quotient that is no integer goes to the integer further from zero: by
     * its sign, by how what is left of it compares with a half, and by whether the
     * integer nearer zero is odd.
     */
    private static boolean away(RoundingMode mode, int sign, int half, boolean odd) {
        switch (mode) {
            case UP:
                return true;
            case DOWN:
                return false;
            case CEILING:
                return sign > 0;
            case FLOOR:
                return sign < 0;
            case HALF_UP:
                return half >= 0;
            case HALF_DOWN:
                return half > 0;
            case HALF_EVEN:
                return half > 0 || (half == 0 && odd);
            default:
                throw new ArithmeticException("Rounding necessary");
        }
    }

    /** With the zeros its value ends with taken off, as far as a scale; zero as it is. */
    private BigDecimal stripped(long preferred) {
        BigInteger value = this.unscaled;
        long scale = this.scale;
        while (scale > preferred && value.signum() != 0) {
            BigInteger[] parts = value.divideAndRemainder(BigInteger.TEN);
            if (parts[1].signum() != 0) {
                break;
            }
            value = parts[0];
            scale--;
        }
        return scale == this.scale ? this : new BigDecimal(value, scaled(scale, value));
    }

    /**
     * Written with zeros toward a scale, as many as a precision takes: the form of a
     * result that has fewer digits than its preferred scale asks for.
     */
    private BigDecimal padded(long preferred, int limit) {
        long scale = Math.min(preferred, (long) this.scale + limit - precision());
        return scale > this.scale ? setScale((int) scale) : this;
    }

    /** A rounded value whose rounding carried past its first digit: 999 as 1000. */
    private BigDecimal carried(int limit) {
        return precision() > limit ? new BigDecimal(this.unscaled.divide(BigInteger.TEN),
                scaled((long) this.scale - 1, this.unscaled)) : this;
    }

    // ---- the arithmetic

    public BigDecimal add(BigDecimal augend) {
        long gap = (long) this.scale - augend.scale;
        if (gap >= 0) {
            return new BigDecimal(this.unscaled.add(raised(augend.unscaled, gap)), this.scale);
        }
        return new BigDecimal(raised(this.unscaled, -gap).add(augend.unscaled), augend.scale);
    }

    public BigDecimal add(BigDecimal augend, MathContext mc) {
        int limit = mc.getPrecision();
        if (limit == 0) {
            return add(augend);
        }
        if (signum() == 0 && augend.signum() == 0) {
            return add(augend);
        }
        // beside zero the other number, written with the zeros of the sum's scale as
        // far as the precision takes them
        if (signum() == 0 || augend.signum() == 0) {
            BigDecimal other = signum() == 0 ? augend : this;
            return other.round(mc).padded(Math.max(this.scale, augend.scale), limit);
        }
        BigDecimal larger = lead() < augend.lead() ? augend : this;
        BigDecimal smaller = larger == this ? augend : this;
        // a number wholly below both the last digit of the other and the place the sum
        // is rounded at moves the sum off the other number and across no boundary of
        // any rounding: any number of its sign that small does the same
        long unit = Math.min(larger.lead() - limit, -(long) larger.scale);
        if (smaller.lead() < unit - 1 && 1 - unit <= Integer.MAX_VALUE) {
            smaller = new BigDecimal(BigInteger.valueOf(smaller.signum()), (int) (1 - unit));
        }
        return larger.add(smaller).round(mc);
    }

    public BigDecimal subtract(BigDecimal subtrahend) {
        return add(subtrahend.negate());
    }

    public BigDecimal subtract(BigDecimal subtrahend, MathContext mc) {
        return add(subtrahend.negate(), mc);
    }

    public BigDecimal multiply(BigDecimal multiplicand) {
        BigInteger product = this.unscaled.multiply(multiplicand.unscaled);
        return new BigDecimal(product, scaled((long) this.scale + multiplicand.scale, product));
    }

    public BigDecimal multiply(BigDecimal multiplicand, MathContext mc) {
        return multiply(multiplicand).round(mc);
    }

    /** The quotient at a scale. */
    public BigDecimal divide(BigDecimal divisor, int scale, RoundingMode roundingMode) {
        long raise = (long) scale - this.scale + divisor.scale;
        if (divisor.signum() == 0) {
            // a JDK's two wordings: the first where the scale moves a number at most 18
            // places and the dividend moved to it is below 2^63
            throw new ArithmeticException(Math.abs(raise) <= 18
                    && raised(this.unscaled, Math.max(raise, 0)).abs().bitLength() < 64 ? "/ by zero"
                            : "BigInteger divide by zero");
        }
        return new BigDecimal(raise >= 0 ? divided(raised(this.unscaled, raise), divisor.unscaled, roundingMode)
                : divided(this.unscaled, raised(divisor.unscaled, -raise), roundingMode), scale);
    }

    @Deprecated
    public BigDecimal divide(BigDecimal divisor, int scale, int roundingMode) {
        return divide(divisor, scale, mode(roundingMode));
    }

    public BigDecimal divide(BigDecimal divisor, RoundingMode roundingMode) {
        return divide(divisor, this.scale, roundingMode);
    }

    @Deprecated
    public BigDecimal divide(BigDecimal divisor, int roundingMode) {
        return divide(divisor, this.scale, mode(roundingMode));
    }

    private static RoundingMode mode(int roundingMode) {
        if (roundingMode < ROUND_UP || roundingMode > ROUND_UNNECESSARY) {
            throw new IllegalArgumentException("Invalid rounding mode");
        }
        return RoundingMode.valueOf(roundingMode);
    }

    private void dividing(BigDecimal divisor) {
        if (divisor.signum() == 0) {
            throw new ArithmeticException(signum() == 0 ? "Division undefined" : "Division by zero");
        }
    }

    /**
     * The exact quotient, where it has an end: the two numbers as a fraction in lowest
     * terms, whose denominator then holds no factor but two and five, and as many
     * digits after the point as the larger count of the two.
     */
    public BigDecimal divide(BigDecimal divisor) {
        dividing(divisor);
        long preferred = (long) this.scale - divisor.scale;
        if (signum() == 0) {
            return zero(preferred);
        }
        BigInteger common = this.unscaled.gcd(divisor.unscaled);
        BigInteger over = this.unscaled.divide(common);
        BigInteger under = divisor.unscaled.divide(common);
        if (under.signum() < 0) {
            over = over.negate();
            under = under.negate();
        }
        int twos = under.getLowestSetBit();
        BigInteger left = under.shiftRight(twos);
        BigInteger five = BigInteger.valueOf(5);
        int fives = 0;
        BigInteger[] parts = left.divideAndRemainder(five);
        while (parts[1].signum() == 0) {
            left = parts[0];
            fives++;
            parts = left.divideAndRemainder(five);
        }
        if (!left.equals(BigInteger.ONE)) {
            throw new ArithmeticException("Non-terminating decimal expansion; no exact representable decimal result.");
        }
        int places = Math.max(twos, fives);
        BigInteger quotient = raised(over, places).divide(under);
        return new BigDecimal(quotient, scaled(preferred + places, quotient));
    }

    /**
     * The quotient at a precision: the dividend raised by the power of ten that leaves
     * the integer quotient exactly so many digits, rounded once. An exact one loses the
     * zeros it ends with, as far as the preferred scale.
     */
    public BigDecimal divide(BigDecimal divisor, MathContext mc) {
        int limit = mc.getPrecision();
        if (limit == 0) {
            return divide(divisor);
        }
        dividing(divisor);
        long preferred = (long) this.scale - divisor.scale;
        if (signum() == 0) {
            return zero(preferred);
        }
        long raise = (long) limit - precision() + divisor.precision();
        BigInteger over = raised(this.unscaled, Math.max(raise, 0));
        BigInteger under = raised(divisor.unscaled, Math.max(-raise, 0));
        if (over.abs().compareTo(raised(under.abs(), limit)) >= 0) {
            under = under.multiply(BigInteger.TEN);
            raise--;
        }
        BigInteger quotient = divided(over, under, mc.getRoundingMode());
        BigDecimal result = new BigDecimal(quotient, scaled(preferred + raise, quotient));
        return quotient.multiply(under).equals(over) ? result.stripped(preferred) : result.carried(limit);
    }

    /** The whole part of the quotient. */
    public BigDecimal divideToIntegralValue(BigDecimal divisor) {
        dividing(divisor);
        long preferred = (long) this.scale - divisor.scale;
        if (abs().compareTo(divisor.abs()) < 0) {
            return zero(preferred);
        }
        BigInteger quotient = preferred > 0 ? this.unscaled.divide(raised(divisor.unscaled, preferred))
                : raised(this.unscaled, -preferred).divide(divisor.unscaled);
        BigDecimal whole = new BigDecimal(quotient, 0);
        return preferred > 0 ? whole.setScale(scaled(preferred, BigInteger.ZERO)) : whole.stripped(preferred);
    }

    public BigDecimal divideToIntegralValue(BigDecimal divisor, MathContext mc) {
        int limit = mc.getPrecision();
        BigDecimal whole = divideToIntegralValue(divisor);
        if (limit == 0) {
            return whole;
        }
        long preferred = (long) this.scale - divisor.scale;
        BigDecimal shortest = whole.stripped(Integer.MIN_VALUE);
        if (shortest.precision() > limit) {
            throw new ArithmeticException("Division impossible");
        }
        return shortest.padded(preferred, limit);
    }

    public BigDecimal remainder(BigDecimal divisor) {
        return subtract(divideToIntegralValue(divisor).multiply(divisor));
    }

    public BigDecimal remainder(BigDecimal divisor, MathContext mc) {
        return subtract(divideToIntegralValue(divisor, mc).multiply(divisor));
    }

    public BigDecimal[] divideAndRemainder(BigDecimal divisor) {
        BigDecimal whole = divideToIntegralValue(divisor);
        return new BigDecimal[] {whole, subtract(whole.multiply(divisor))};
    }

    public BigDecimal[] divideAndRemainder(BigDecimal divisor, MathContext mc) {
        BigDecimal whole = divideToIntegralValue(divisor, mc);
        return new BigDecimal[] {whole, subtract(whole.multiply(divisor))};
    }

    public BigDecimal pow(int n) {
        if (n < 0 || n > 999999999) {
            throw new ArithmeticException("Invalid operation");
        }
        BigInteger power = this.unscaled.pow(n);
        return new BigDecimal(power, scaled((long) this.scale * n, power));
    }

    /**
     * The power as the specification computes it: by squaring from the exponent's
     * highest bit, each product rounded to the precision asked and the exponent's
     * digits and one more, a negative exponent dividing one by the result.
     */
    public BigDecimal pow(int n, MathContext mc) {
        int limit = mc.getPrecision();
        if (limit == 0) {
            return pow(n);
        }
        if (n < -999999999 || n > 999999999) {
            throw new ArithmeticException("Invalid operation");
        }
        if (n == 0) {
            return ONE;
        }
        int exponent = Math.abs(n);
        int length = Integer.toString(exponent).length();
        if (length > limit) {
            throw new ArithmeticException("Invalid operation");
        }
        MathContext working = new MathContext(limit + length + 1, mc.getRoundingMode());
        BigDecimal result = ONE;
        boolean started = false;
        for (int bit = 30; bit >= 0; bit--) {
            if (started) {
                result = result.multiply(result, working);
            }
            if ((exponent >> bit & 1) != 0) {
                result = result.multiply(this, working);
                started = true;
            }
        }
        if (n < 0) {
            result = ONE.divide(result, working);
        }
        return result.round(mc);
    }

    /**
     * The square root at a precision: the integer root of the value raised to hold one
     * digit more than asked, with what the root left over, rounded once.
     */
    public BigDecimal sqrt(MathContext mc) {
        int sign = signum();
        if (sign < 0) {
            throw new ArithmeticException("Attempted square root of negative BigDecimal");
        }
        int preferred = this.scale / 2;
        if (sign == 0) {
            return new BigDecimal(BigInteger.ZERO, preferred);
        }
        int limit = mc.getPrecision();
        long raise = limit == 0 ? 0 : Math.max(0, 2L * limit + 1 - precision());
        if (((this.scale + raise) & 1) != 0) {
            raise++;
        }
        BigInteger square = raised(this.unscaled, raise);
        BigInteger root = square.sqrt();
        boolean exact = root.multiply(root).equals(square);
        BigDecimal result = new BigDecimal(root, scaled((this.scale + raise) / 2, root));
        if (limit != 0) {
            BigInteger unit = tenTo(result.precision() - limit);
            BigInteger[] parts = root.divideAndRemainder(unit);
            int half = parts[1].shiftLeft(1).compareTo(unit);
            if (half == 0 && !exact) {
                half = 1;
            }
            exact = exact && parts[1].signum() == 0;
            boolean away = !exact && mc.getRoundingMode() != RoundingMode.UNNECESSARY
                    && away(mc.getRoundingMode(), 1, half, parts[0].testBit(0));
            BigInteger kept = away ? parts[0].add(BigInteger.ONE) : parts[0];
            result = new BigDecimal(kept, scaled((long) result.scale - (result.precision() - limit), kept))
                .carried(limit);
        }
        if (!exact && (limit == 0 || mc.getRoundingMode() == RoundingMode.UNNECESSARY)) {
            throw new ArithmeticException("Computed square root not exact.");
        }
        // toward the preferred scale: a rounded root loses the zeros it ends with as an
        // exact one does (a JDK's answers), and an exact one gains them
        result = result.stripped(preferred);
        if (!exact) {
            return result;
        }
        return limit == 0 ? result.setScale(Math.max(result.scale, preferred)) : result.padded(preferred, limit);
    }

    public BigDecimal abs() {
        return signum() < 0 ? negate() : this;
    }

    public BigDecimal abs(MathContext mc) {
        return abs().round(mc);
    }

    public BigDecimal negate() {
        return new BigDecimal(this.unscaled.negate(), this.scale);
    }

    public BigDecimal negate(MathContext mc) {
        return negate().round(mc);
    }

    public BigDecimal plus() {
        return this;
    }

    public BigDecimal plus(MathContext mc) {
        return round(mc);
    }

    public int signum() {
        return this.unscaled.signum();
    }

    public int scale() {
        return this.scale;
    }

    public int precision() {
        if (this.digits == 0) {
            if (this.unscaled.bitLength() < 63) {
                long magnitude = Math.abs(this.unscaled.longValue());
                int count = 1;
                while (magnitude >= 10) {
                    magnitude /= 10;
                    count++;
                }
                this.digits = count;
            }
            else {
                this.digits = this.unscaled.abs().toString().length();
            }
        }
        return this.digits;
    }

    public BigInteger unscaledValue() {
        return this.unscaled;
    }

    /** At a precision: the digits past it divided away. */
    public BigDecimal round(MathContext mc) {
        int limit = mc.getPrecision();
        int drop = limit == 0 ? 0 : precision() - limit;
        if (drop <= 0) {
            return this;
        }
        BigInteger kept = divided(this.unscaled, tenTo(drop), mc.getRoundingMode());
        return new BigDecimal(kept, scaled((long) this.scale - drop, kept)).carried(limit);
    }

    public BigDecimal setScale(int newScale, RoundingMode roundingMode) {
        long drop = (long) this.scale - newScale;
        if (drop == 0) {
            return this;
        }
        if (drop < 0 || signum() == 0) {
            return new BigDecimal(raised(this.unscaled, -drop), newScale);
        }
        // every digit goes: what is left is below a half of the last place kept
        if (drop > precision()) {
            return new BigDecimal(divided(BigInteger.valueOf(signum()), BigInteger.TEN, roundingMode), newScale);
        }
        return new BigDecimal(divided(this.unscaled, tenTo(drop), roundingMode), newScale);
    }

    @Deprecated
    public BigDecimal setScale(int newScale, int roundingMode) {
        return setScale(newScale, mode(roundingMode));
    }

    public BigDecimal setScale(int newScale) {
        return setScale(newScale, RoundingMode.UNNECESSARY);
    }

    public BigDecimal movePointLeft(int n) {
        return moved((long) this.scale + n);
    }

    public BigDecimal movePointRight(int n) {
        return moved((long) this.scale - n);
    }

    /** At a scale by the same digits; a number with zeros ahead of its point is written out. */
    private BigDecimal moved(long scale) {
        BigDecimal moved = new BigDecimal(this.unscaled, scaled(scale, this.unscaled));
        return moved.scale < 0 ? moved.setScale(0) : moved;
    }

    public BigDecimal scaleByPowerOfTen(int n) {
        return new BigDecimal(this.unscaled, scaled((long) this.scale - n, this.unscaled));
    }

    public BigDecimal stripTrailingZeros() {
        return signum() == 0 ? ZERO : stripped(Integer.MIN_VALUE);
    }

    public BigDecimal ulp() {
        return new BigDecimal(BigInteger.ONE, this.scale);
    }

    // ---- the comparisons

    /** By value: 2.0 and 2.00 are the same. The place of the first digit decides before any digit is read. */
    @Override
    public int compareTo(BigDecimal val) {
        int sign = signum();
        int other = val.signum();
        if (sign != other) {
            return sign < other ? -1 : 1;
        }
        if (sign == 0) {
            return 0;
        }
        long gap = (long) this.scale - val.scale;
        if (gap != 0) {
            long lead = lead();
            long otherLead = val.lead();
            if (lead != otherLead) {
                return lead < otherLead ? -sign : sign;
            }
        }
        return gap < 0 ? raised(this.unscaled, -gap).compareTo(val.unscaled)
                : this.unscaled.compareTo(raised(val.unscaled, gap));
    }

    /** By value and scale: 2.0 is not 2.00. */
    @Override
    public boolean equals(Object x) {
        return x instanceof BigDecimal other && other.scale == this.scale && other.unscaled.equals(this.unscaled);
    }

    @Override
    public int hashCode() {
        return 31 * this.unscaled.hashCode() + this.scale;
    }

    public BigDecimal min(BigDecimal val) {
        return compareTo(val) <= 0 ? this : val;
    }

    public BigDecimal max(BigDecimal val) {
        return compareTo(val) >= 0 ? this : val;
    }

    // ---- the text

    /**
     * The digits with a point, or with an exponent where the scale is negative or the
     * first digit stands more than six places after the point.
     */
    @Override
    public String toString() {
        String magnitude = this.unscaled.abs().toString();
        long exponent = magnitude.length() - 1 - (long) this.scale;
        if (this.scale >= 0 && exponent >= -6) {
            return toPlainString();
        }
        StringBuilder text = new StringBuilder();
        if (signum() < 0) {
            text.append('-');
        }
        text.append(magnitude.charAt(0));
        if (magnitude.length() > 1) {
            text.append('.').append(magnitude.substring(1));
        }
        return text.append('E').append(exponent > 0 ? "+" : "").append(exponent).toString();
    }

    /** As toString, with an exponent that is a multiple of three and one to three digits ahead of the point. */
    public String toEngineeringString() {
        String magnitude = this.unscaled.abs().toString();
        long exponent = magnitude.length() - 1 - (long) this.scale;
        if (this.scale >= 0 && exponent >= -6) {
            return toPlainString();
        }
        int ahead = (int) Math.floorMod(exponent, 3L);
        exponent -= ahead;
        StringBuilder text = new StringBuilder();
        if (signum() < 0) {
            text.append('-');
        }
        if (signum() == 0) {
            // zero has no digit to move: it is written with the zeros the exponent left
            if (ahead != 0) {
                exponent += 3;
                text.append(ahead == 1 ? "0.00" : "0.0");
            }
            else {
                text.append('0');
            }
        }
        else if (magnitude.length() <= ahead + 1) {
            text.append(magnitude).append("0".repeat(ahead + 1 - magnitude.length()));
        }
        else {
            text.append(magnitude.substring(0, ahead + 1)).append('.').append(magnitude.substring(ahead + 1));
        }
        if (exponent != 0) {
            text.append('E').append(exponent > 0 ? "+" : "").append(exponent);
        }
        return text.toString();
    }

    /** The digits with a point and no exponent. */
    public String toPlainString() {
        String magnitude = this.unscaled.abs().toString();
        StringBuilder text = new StringBuilder();
        if (signum() < 0) {
            text.append('-');
        }
        if (this.scale <= 0) {
            text.append(magnitude);
            if (signum() != 0) {
                text.append("0".repeat(-this.scale));
            }
        }
        else if (magnitude.length() > this.scale) {
            int point = magnitude.length() - this.scale;
            text.append(magnitude.substring(0, point)).append('.').append(magnitude.substring(point));
        }
        else {
            text.append("0.").append("0".repeat(this.scale - magnitude.length())).append(magnitude);
        }
        return text.toString();
    }

    @Override
    public String written(int places) {
        String text = (this.scale > places ? setScale(places, RoundingMode.HALF_EVEN) : this).abs().toPlainString();
        return signum() < 0 ? "-" + text : text;
    }

    // ---- as the other numbers

    /** The whole part. */
    public BigInteger toBigInteger() {
        return setScale(0, RoundingMode.DOWN).unscaled;
    }

    public BigInteger toBigIntegerExact() {
        return setScale(0, RoundingMode.UNNECESSARY).unscaled;
    }

    /** The low 64 bits of the whole part. */
    @Override
    public long longValue() {
        // nothing ahead of the point, or a multiple of 2^64
        if (signum() == 0 || lead() < 0 || this.scale <= -64) {
            return 0;
        }
        return toBigInteger().longValue();
    }

    @Override
    public int intValue() {
        return (int) longValue();
    }

    public long longValueExact() {
        return whole(64);
    }

    public int intValueExact() {
        return (int) whole(32);
    }

    public short shortValueExact() {
        return (short) whole(16);
    }

    public byte byteValueExact() {
        return (byte) whole(8);
    }

    /** The value where it is whole and fits so many bits. */
    private long whole(int bits) {
        if (signum() == 0) {
            return 0;
        }
        // more digits ahead of the point than a long has
        if (lead() >= 19) {
            throw new ArithmeticException("Overflow");
        }
        BigInteger whole = toBigIntegerExact();
        if (whole.bitLength() >= bits) {
            throw new ArithmeticException("Overflow");
        }
        return whole.longValue();
    }

    @Override
    public float floatValue() {
        return (float) nearest(false);
    }

    @Override
    public double doubleValue() {
        return nearest(true);
    }

    /** The nearest double, or float as a double: the ratio of two integers rounded once. */
    private double nearest(boolean wide) {
        int sign = signum();
        if (sign == 0) {
            return 0.0;
        }
        long lead = lead();
        double magnitude;
        // past every finite value, or below half the smallest
        if (lead > 400) {
            magnitude = Double.POSITIVE_INFINITY;
        }
        else if (lead < -400) {
            magnitude = 0.0;
        }
        else if (this.scale <= 0) {
            magnitude = BigInteger.ratio(raised(this.unscaled.abs(), -(long) this.scale), BigInteger.ONE, wide);
        }
        else {
            magnitude = BigInteger.ratio(this.unscaled.abs(), tenTo(this.scale), wide);
        }
        return sign < 0 ? -magnitude : magnitude;
    }

}
