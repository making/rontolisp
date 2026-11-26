// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An integer of any size: the host's own, which has no bound on any target, held in a
// field so that the object is told from an Integer and a Long. The arithmetic, the bits
// and the digits are the host's (the native members); what the specification leaves
// open -- the words of a refusal, the piece of a text a refusal names, the hash code --
// is a JDK 25's answers to a probe (.kb/java-frontend.md, "java.math").
package java.math;

import rontolisp.internal.Exact;

public class BigInteger extends Number implements Comparable<BigInteger>, Exact {

    public static final BigInteger ZERO = new BigInteger(wide(0L));

    public static final BigInteger ONE = new BigInteger(wide(1L));

    public static final BigInteger TWO = new BigInteger(wide(2L));

    public static final BigInteger TEN = new BigInteger(wide(10L));

    /** The host's integer. */
    private final Object value;

    private BigInteger(Object value) {
        this.value = value;
    }

    public BigInteger(String val) {
        this(val, 10);
    }

    public BigInteger(String val, int radix) {
        this.value = read(val, radix);
    }

    public BigInteger(byte[] val) {
        this(val, 0, val.length);
    }

    /** The two's complement of a number, its high byte first. */
    public BigInteger(byte[] val, int off, int len) {
        if (val.length == 0) {
            throw new NumberFormatException("Zero length BigInteger");
        }
        ranged(val, off, len);
        Object read = wide(len == 0 || val[off] >= 0 ? 0L : -1L);
        for (int i = 0; i < len; i++) {
            read = sum(shifted(read, 8), wide(val[off + i] & 0xFFL));
        }
        this.value = read;
    }

    public BigInteger(int signum, byte[] magnitude) {
        this(signum, magnitude, 0, magnitude.length);
    }

    public BigInteger(int signum, byte[] magnitude, int off, int len) {
        if (signum < -1 || signum > 1) {
            throw new NumberFormatException("Invalid signum value");
        }
        ranged(magnitude, off, len);
        Object read = wide(0L);
        for (int i = 0; i < len; i++) {
            read = sum(shifted(read, 8), wide(magnitude[off + i] & 0xFFL));
        }
        if (signum == 0 && sign(read) != 0) {
            throw new NumberFormatException("signum-magnitude mismatch");
        }
        this.value = signum < 0 ? difference(wide(0L), read) : read;
    }

    private static void ranged(byte[] bytes, int off, int len) {
        if (off < 0 || len < 0 || len > bytes.length - off) {
            throw new IndexOutOfBoundsException(
                    "Range [" + off + ", " + off + " + " + len + ") out of bounds for length " + bytes.length);
        }
    }

    /**
     * A text as a number: an optional sign, then digits of the radix, read a group at a
     * time -- the most digits of the radix that always fit an int. A digit that is none is
     * refused with its group.
     */
    private static Object read(String text, int radix) {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new NumberFormatException("Radix out of range");
        }
        int length = text.length();
        if (length == 0) {
            throw new NumberFormatException("Zero length BigInteger");
        }
        if (text.lastIndexOf('-') > 0 || text.lastIndexOf('+') > 0) {
            throw new NumberFormatException("Illegal embedded sign character");
        }
        char first = text.charAt(0);
        int at = first == '-' || first == '+' ? 1 : 0;
        if (at == length) {
            throw new NumberFormatException("Zero length BigInteger");
        }
        while (at < length && Character.digit(text.charAt(at), radix) == 0) {
            at++;
        }
        int width = 1;
        long weight = radix;
        while (weight * radix <= Integer.MAX_VALUE) {
            weight *= radix;
            width++;
        }
        Object read = wide(0L);
        int end = at + (length - at) % width;
        if (end == at) {
            end = Math.min(at + width, length);
        }
        while (at < length) {
            long group = 0;
            for (int i = at; i < end; i++) {
                int digit = Character.digit(text.charAt(i), radix);
                if (digit < 0) {
                    throw new NumberFormatException("For input string: \"" + text.substring(at, end) + "\""
                            + (radix == 10 ? "" : " under radix " + radix));
                }
                group = group * radix + digit;
            }
            read = sum(product(read, wide(weight)), wide(group));
            at = end;
            end = at + width;
        }
        return first == '-' ? difference(wide(0L), read) : read;
    }

    public static BigInteger valueOf(long val) {
        return new BigInteger(wide(val));
    }

    public BigInteger add(BigInteger val) {
        return new BigInteger(sum(this.value, val.value));
    }

    public BigInteger subtract(BigInteger val) {
        return new BigInteger(difference(this.value, val.value));
    }

    public BigInteger multiply(BigInteger val) {
        return new BigInteger(product(this.value, val.value));
    }

    /** The quotient toward zero. */
    public BigInteger divide(BigInteger val) {
        return new BigInteger(quotient(this.value, divisor(val)));
    }

    /** What the quotient toward zero leaves: of the dividend's sign. */
    public BigInteger remainder(BigInteger val) {
        return new BigInteger(rest(this.value, divisor(val)));
    }

    public BigInteger[] divideAndRemainder(BigInteger val) {
        Object by = divisor(val);
        return new BigInteger[] {new BigInteger(quotient(this.value, by)), new BigInteger(rest(this.value, by))};
    }

    private static Object divisor(BigInteger val) {
        if (sign(val.value) == 0) {
            throw new ArithmeticException("BigInteger divide by zero");
        }
        return val.value;
    }

    /** The remainder that is never negative. */
    public BigInteger mod(BigInteger m) {
        return new BigInteger(modulo(this.value, modulus(m)));
    }

    private static Object modulus(BigInteger m) {
        if (sign(m.value) <= 0) {
            throw new ArithmeticException("BigInteger: modulus not positive");
        }
        return m.value;
    }

    public BigInteger pow(int exponent) {
        if (exponent < 0) {
            throw new ArithmeticException("Negative exponent");
        }
        return new BigInteger(power(this.value, exponent));
    }

    /** By squaring, every product reduced; a negative exponent raises the inverse. */
    public BigInteger modPow(BigInteger exponent, BigInteger m) {
        Object by = modulus(m);
        if (sign(exponent.value) < 0) {
            return modInverse(m).modPow(exponent.negate(), m);
        }
        Object result = modulo(wide(1L), by);
        Object base = modulo(this.value, by);
        int bits = length(exponent.value);
        for (int i = 0; i < bits; i++) {
            if (bit(i, exponent.value)) {
                result = modulo(product(result, base), by);
            }
            base = modulo(product(base, base), by);
        }
        return new BigInteger(result);
    }

    /** Euclid's algorithm keeping the coefficient of this number. */
    public BigInteger modInverse(BigInteger m) {
        Object by = modulus(m);
        Object a = modulo(this.value, by);
        Object b = by;
        Object x = wide(1L);
        Object y = wide(0L);
        while (sign(b) != 0) {
            Object q = quotient(a, b);
            Object r = difference(a, product(q, b));
            a = b;
            b = r;
            Object z = difference(x, product(q, y));
            x = y;
            y = z;
        }
        if (!same(a, wide(1L))) {
            throw new ArithmeticException("BigInteger not invertible.");
        }
        return new BigInteger(modulo(x, by));
    }

    public BigInteger gcd(BigInteger val) {
        return new BigInteger(common(this.value, val.value));
    }

    public BigInteger abs() {
        return sign(this.value) < 0 ? negate() : this;
    }

    public BigInteger negate() {
        return new BigInteger(difference(wide(0L), this.value));
    }

    public int signum() {
        return sign(this.value);
    }

    /** The largest integer whose square is not above this number. */
    public BigInteger sqrt() {
        if (sign(this.value) < 0) {
            throw new ArithmeticException("Negative BigInteger");
        }
        return new BigInteger(root(this.value));
    }

    public BigInteger[] sqrtAndRemainder() {
        BigInteger root = sqrt();
        return new BigInteger[] {root, subtract(root.multiply(root))};
    }

    // ---- the bits: of the two's complement, the sign repeated without end

    public BigInteger shiftLeft(int n) {
        return new BigInteger(shifted(this.value, n));
    }

    public BigInteger shiftRight(int n) {
        return new BigInteger(shifted(this.value, -(long) n));
    }

    public BigInteger and(BigInteger val) {
        return new BigInteger(both(this.value, val.value));
    }

    public BigInteger or(BigInteger val) {
        return new BigInteger(either(this.value, val.value));
    }

    public BigInteger xor(BigInteger val) {
        return new BigInteger(differing(this.value, val.value));
    }

    public BigInteger not() {
        return new BigInteger(inverted(this.value));
    }

    public BigInteger andNot(BigInteger val) {
        return new BigInteger(both(this.value, inverted(val.value)));
    }

    public boolean testBit(int n) {
        return bit(place(n), this.value);
    }

    public BigInteger setBit(int n) {
        return new BigInteger(either(this.value, shifted(wide(1L), place(n))));
    }

    public BigInteger clearBit(int n) {
        return new BigInteger(both(this.value, inverted(shifted(wide(1L), place(n)))));
    }

    public BigInteger flipBit(int n) {
        return new BigInteger(differing(this.value, shifted(wide(1L), place(n))));
    }

    private static int place(int n) {
        if (n < 0) {
            throw new ArithmeticException("Negative bit address");
        }
        return n;
    }

    public int getLowestSetBit() {
        if (sign(this.value) == 0) {
            return -1;
        }
        // the lowest bit set alone is the number and its negation in common
        return length(both(this.value, difference(wide(0L), this.value))) - 1;
    }

    public int bitLength() {
        return length(this.value);
    }

    public int bitCount() {
        return count(this.value);
    }

    // ---- the comparisons

    @Override
    public int compareTo(BigInteger val) {
        return below(this.value, val.value) ? -1 : same(this.value, val.value) ? 0 : 1;
    }

    @Override
    public boolean equals(Object x) {
        return x instanceof BigInteger other && same(this.value, other.value);
    }

    public BigInteger min(BigInteger val) {
        return compareTo(val) < 0 ? this : val;
    }

    public BigInteger max(BigInteger val) {
        return compareTo(val) > 0 ? this : val;
    }

    /**
     * The magnitude's 32-bit words from the highest, each added to 31 times the ones
     * before it, with the number's sign: a JDK's answers.
     */
    @Override
    public int hashCode() {
        int sign = sign(this.value);
        Object magnitude = sign < 0 ? difference(wide(0L), this.value) : this.value;
        int hash = 0;
        for (int word = (length(magnitude) + 31) / 32 - 1; word >= 0; word--) {
            hash = 31 * hash + (int) low(shifted(magnitude, -32L * word));
        }
        return hash * sign;
    }

    // ---- the text and the bytes

    @Override
    public String toString() {
        return digits(this.value, 10);
    }

    /** The digits in a radix, lower case; in ten where the radix is none. */
    public String toString(int radix) {
        return digits(this.value, radix < Character.MIN_RADIX || radix > Character.MAX_RADIX ? 10 : radix);
    }

    @Override
    public String written(int places) {
        return toString();
    }

    @Override
    public Object[] digits() {
        return new Object[] { this.value, null };
    }

    /** The two's complement in the fewest bytes that hold its sign, the high byte first. */
    public byte[] toByteArray() {
        int size = length(this.value) / 8 + 1;
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) low(shifted(this.value, -8L * (size - 1 - i)));
        }
        return bytes;
    }

    // ---- as the primitive types: the low bits, or the nearest

    @Override
    public int intValue() {
        return (int) low(this.value);
    }

    @Override
    public long longValue() {
        return low(this.value);
    }

    @Override
    public float floatValue() {
        return (float) nearest(this.value, 24, -149, 128);
    }

    @Override
    public double doubleValue() {
        return nearest(this.value, 53, -1074, 1024);
    }

    /** The value nearest to a number among those of a binary format, a tie to the even. */
    private static double nearest(Object value, int precision, int least, int most) {
        int sign = sign(value);
        if (sign == 0) {
            return 0.0;
        }
        double magnitude = rounded(sign < 0 ? difference(wide(0L), value) : value, wide(1L), precision, least, most);
        return sign < 0 ? -magnitude : magnitude;
    }

    public long longValueExact() {
        return fitting(64, "long");
    }

    public int intValueExact() {
        return (int) fitting(32, "int");
    }

    public short shortValueExact() {
        return (short) fitting(16, "short");
    }

    public byte byteValueExact() {
        return (byte) fitting(8, "byte");
    }

    private long fitting(int bits, String type) {
        if (length(this.value) >= bits) {
            throw new ArithmeticException("BigInteger out of " + type + " range");
        }
        return low(this.value);
    }

    // ---- what java.math asks of the host's integers

    /**
     * The double, or the float as a double, nearest to the ratio of two positive
     * numbers: what a decimal number asks for its own.
     */
    static double ratio(BigInteger numerator, BigInteger denominator, boolean wide) {
        return wide ? rounded(numerator.value, denominator.value, 53, -1074, 1024)
                : rounded(numerator.value, denominator.value, 24, -149, 128);
    }

    /** The value nearest to a positive ratio among those of a binary format. */
    private static native double rounded(Object numerator, Object denominator, int precision, int least,
            int most);

    private static native Object wide(long value);

    private static native long low(Object value);

    private static native Object sum(Object a, Object b);

    private static native Object difference(Object a, Object b);

    private static native Object product(Object a, Object b);

    private static native Object quotient(Object a, Object b);

    private static native Object rest(Object a, Object b);

    private static native Object modulo(Object a, Object b);

    private static native Object power(Object base, int exponent);

    private static native Object common(Object a, Object b);

    private static native Object root(Object value);

    private static native Object shifted(Object value, long by);

    private static native Object both(Object a, Object b);

    private static native Object either(Object a, Object b);

    private static native Object differing(Object a, Object b);

    private static native Object inverted(Object value);

    private static native boolean bit(int index, Object value);

    private static native int length(Object value);

    private static native int count(Object value);

    private static native int sign(Object value);

    private static native boolean below(Object a, Object b);

    private static native boolean same(Object a, Object b);

    private static native String digits(Object value, int radix);

}
