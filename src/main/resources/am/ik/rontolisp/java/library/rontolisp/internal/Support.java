// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Iterator;

// The members of String, the boxes and Math that are written in Java: the ones that
// throw the library's exceptions or walk its collections.
public final class Support {

    private Support() {
    }

    // What a text that is no number of a radix is refused with; the radix is named
    // when it is not ten.
    private static NumberFormatException malformed(String s, int radix) {
        return new NumberFormatException("For input string: \"" + s + "\"" + (radix == 10 ? "" : " under radix " + radix));
    }

    // A text there is and a radix that is one, before any digit is read.
    private static void parseable(String s, int radix) {
        if (s == null) {
            throw new NumberFormatException("Cannot parse null string");
        }
        if (radix < 2) {
            throw new NumberFormatException("radix " + radix + " less than Character.MIN_RADIX");
        }
        if (radix > 36) {
            throw new NumberFormatException("radix " + radix + " greater than Character.MAX_RADIX");
        }
    }

    public static long parseLong(String s, int radix) {
        return parse(s, radix, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static int parseInt(String s, int radix) {
        return (int) parse(s, radix, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    // The digits are accumulated as a negative number, whose range is the larger one.
    private static long parse(String s, int radix, long least, long most) {
        parseable(s, radix);
        int n = s.length();
        int i = 0;
        boolean negative = false;
        if (n > 0 && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            negative = s.charAt(0) == '-';
            i = 1;
        }
        if (i == n) {
            throw malformed(s, radix);
        }
        long limit = negative ? least : -most;
        long edge = limit / radix;
        long value = 0;
        while (i < n) {
            int digit = Character.digit(s.charAt(i++), radix);
            if (digit < 0 || value < edge) {
                throw malformed(s, radix);
            }
            value *= radix;
            if (value < limit + digit) {
                throw malformed(s, radix);
            }
            value -= digit;
        }
        return negative ? value : -value;
    }

    // Integer.decode and Long.decode: a sign, then 0x, 0X or # ahead of hexadecimal
    // digits or 0 ahead of octal ones, else decimal digits. The digits are parsed with
    // the sign in front of them, which is the text a refusal names.
    public static long decode(String s, long least, long most) {
        if (s == null) {
            throw new NullPointerException();
        }
        if (s.isEmpty()) {
            throw new NumberFormatException("Zero length string");
        }
        boolean negative = s.charAt(0) == '-';
        int i = negative || s.charAt(0) == '+' ? 1 : 0;
        int radix = 10;
        if (s.startsWith("0x", i) || s.startsWith("0X", i)) {
            i += 2;
            radix = 16;
        } else if (s.startsWith("#", i)) {
            i++;
            radix = 16;
        } else if (s.startsWith("0", i) && s.length() > i + 1) {
            i++;
            radix = 8;
        }
        if (s.startsWith("-", i) || s.startsWith("+", i)) {
            throw new NumberFormatException("Sign character in wrong position");
        }
        String digits = s.substring(i);
        return parse(negative ? "-" + digits : digits, radix, least, most);
    }

    // Integer.parseUnsignedInt and Long.parseUnsignedLong: no sign but a +, the digits
    // read from the left, a value past the type's bits refused at the digit that
    // reaches it. The value is the number of its bits; most is the largest one.
    private static long parseUnsigned(String s, int radix, long most, String type) {
        parseable(s, radix);
        int n = s.length();
        if (n > 0 && s.charAt(0) == '-') {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + s + ".");
        }
        int i = n > 0 && s.charAt(0) == '+' ? 1 : 0;
        if (i == n) {
            throw malformed(s, radix);
        }
        long edge = Long.divideUnsigned(most, radix);
        long last = Long.remainderUnsigned(most, radix);
        long value = 0;
        while (i < n) {
            int digit = Character.digit(s.charAt(i++), radix);
            if (digit < 0) {
                throw malformed(s, radix);
            }
            int order = Long.compareUnsigned(value, edge);
            if (order > 0 || (order == 0 && digit > last)) {
                throw new NumberFormatException("String value " + s + " exceeds range of unsigned " + type + ".");
            }
            value = value * radix + digit;
        }
        return value;
    }

    public static long parseUnsignedLong(String s, int radix) {
        return parseUnsigned(s, radix, -1L, "long");
    }

    public static int parseUnsignedInt(String s, int radix) {
        return (int) parseUnsigned(s, radix, 0xFFFFFFFFL, "int");
    }

    // StringBuilder.append(CharSequence, int, int), the text made already.
    public static StringBuilder appendRange(StringBuilder to, String text, int start, int end) {
        if (start < 0 || start > end || end > text.length()) {
            throw new IndexOutOfBoundsException(
                    "Range [" + start + ", " + end + ") out of bounds for length " + text.length());
        }
        return to.append(text.substring(start, end));
    }

    // StringBuilder.append(char[], int, int).
    public static StringBuilder appendChars(StringBuilder to, char[] chars, int offset, int count) {
        if (offset < 0 || count < 0 || offset > chars.length - count) {
            throw new IndexOutOfBoundsException(
                    "Range [" + offset + ", " + (offset + count) + ") out of bounds for length " + chars.length);
        }
        return to.append(new String(chars, offset, count));
    }

    // String.contentEquals: the characters of a text of any kind.
    public static boolean contentEquals(String s, Object text) {
        if (text == null) {
            throw new NullPointerException();
        }
        return s.equals(text.toString());
    }

    // String.indent: every line ended by a line feed, with n spaces ahead of it or, for
    // a negative n, without as many of its leading white space characters as it has.
    public static String indent(String s, int n) {
        StringBuilder indented = new StringBuilder();
        int length = s.length();
        int i = 0;
        while (i < length) {
            int end = i;
            while (end < length && s.charAt(end) != '\n' && s.charAt(end) != '\r') {
                end++;
            }
            for (int k = 0; k < n; k++) {
                indented.append(' ');
            }
            for (int k = n; k < 0 && i < end && Character.isWhitespace(s.charAt(i)); k++) {
                i++;
            }
            indented.append(s, i, end).append('\n');
            i = end + (s.startsWith("\r\n", end) ? 2 : 1);
        }
        return indented.toString();
    }

    // The digits of a fraction as hexadecimal, as many as hold it and no zero after
    // them but one.
    private static String fraction(long bits, int digits) {
        String text = Long.toHexString(bits);
        while (text.length() < digits) {
            text = "0" + text;
        }
        int end = digits;
        while (end > 1 && text.charAt(end - 1) == '0') {
            end--;
        }
        return text.substring(0, end);
    }

    // Double.toHexString: the sign, 0x1. or, for a subnormal, 0x0., the fraction's
    // hexadecimal digits and the exponent of two.
    public static String hexString(double d) {
        if (d != d) {
            return "NaN";
        }
        long bits = Double.doubleToLongBits(d);
        String sign = bits < 0 ? "-" : "";
        int exponent = (int) ((bits >>> 52) & 0x7ff);
        long mantissa = bits & 0xfffffffffffffL;
        if (exponent == 0x7ff) {
            return sign + "Infinity";
        }
        if (exponent == 0) {
            return sign + (mantissa == 0 ? "0x0.0p0" : "0x0." + fraction(mantissa, 13) + "p-1022");
        }
        return sign + "0x1." + fraction(mantissa, 13) + "p" + (exponent - 1023);
    }

    // Float.toHexString: the same over the float's bits, whose twenty-three of the
    // fraction are six digits.
    public static String hexStringOfFloat(float f) {
        if (f != f) {
            return "NaN";
        }
        int bits = Float.floatToIntBits(f);
        String sign = bits < 0 ? "-" : "";
        int exponent = (bits >>> 23) & 0xff;
        long mantissa = (long) (bits & 0x7fffff) << 1;
        if (exponent == 0xff) {
            return sign + "Infinity";
        }
        if (exponent == 0) {
            return sign + (mantissa == 0 ? "0x0.0p0" : "0x0." + fraction(mantissa, 6) + "p-126");
        }
        return sign + "0x1." + fraction(mantissa, 6) + "p" + (exponent - 127);
    }

    // System.getProperty: what every target can answer -- the separators and the
    // encodings; any other key has no value.
    public static String property(String key) {
        if (key == null) {
            throw new NullPointerException("key can't be null");
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key can't be empty");
        }
        if (key.equals("line.separator")) {
            return "\n";
        }
        if (key.equals("file.separator")) {
            return "/";
        }
        if (key.equals("path.separator")) {
            return ":";
        }
        if (key.equals("file.encoding") || key.equals("native.encoding") || key.equals("stdin.encoding")
                || key.equals("stdout.encoding") || key.equals("stderr.encoding")) {
            return "UTF-8";
        }
        return null;
    }

    public static String propertyOr(String key, String otherwise) {
        String value = property(key);
        return value == null ? otherwise : value;
    }

    // The charset of a name, for the members that take a name and not a Charset.
    public static Charset charset(String name) throws UnsupportedEncodingException {
        Charset found = Charset.find(name);
        if (found == null) {
            throw new UnsupportedEncodingException(name);
        }
        return found;
    }

    public static boolean isUtf8(Charset charset) {
        return charset == Charset.utf8();
    }

    /** Whether the target runs the threads of a program on one stack. */
    public static native boolean oneStack();

    /**
     * One stack: the thread started next runs no sooner than the time given, as
     * System.nanoTime() counts -- what stands for a thread waiting until then.
     */
    public static native void notBefore(long nanoTime);

    /** One stack: a thread started that has not run yet is not to run. */
    public static native void unstart(Thread thread);

    public static native byte[] utf8(String s);

    public static native String utf8String(byte[] b, int offset, int count);

    // String.getBytes(Charset) and new String(byte[], .., Charset): the charset's codec.
    public static byte[] encode(String s, Charset charset) {
        return charset.bytes(s);
    }

    public static byte[] encodeNamed(String s, String charsetName) throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException();
        }
        return charset(charsetName).bytes(s);
    }

    public static String decode(byte[] b, Charset charset) {
        return charset.text(b, 0, b.length);
    }

    public static String decode(byte[] b, int offset, int count, Charset charset) {
        return charset.text(b, offset, count);
    }

    public static String decodeNamed(byte[] b, String charsetName) throws UnsupportedEncodingException {
        return decodeNamed(b, 0, b.length, charsetName);
    }

    public static String decodeNamed(byte[] b, int offset, int count, String charsetName)
            throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException("charsetName");
        }
        return charset(charsetName).text(b, offset, count);
    }

    private static native Double readDouble(String s);

    // Float.hashCode: the bits of the float, one NaN
    public static native int floatHash(float f);

    private static native Float readFloat(String s);

    public static float parseFloat(String s) {
        if (s == null) {
            throw new NullPointerException();
        }
        Float value = readFloat(s);
        if (value == null) {
            throw new NumberFormatException(s.isBlank() ? "empty String" : "For input string: \"" + s + "\"");
        }
        return value;
    }

    public static byte parseByte(String s, int radix) {
        int value = parseInt(s, radix);
        if (value < -128 || value > 127) {
            throw new NumberFormatException("Value out of range. Value:\"" + s + "\" Radix:" + radix);
        }
        return (byte) value;
    }

    public static short parseShort(String s, int radix) {
        int value = parseInt(s, radix);
        if (value < -32768 || value > 32767) {
            throw new NumberFormatException("Value out of range. Value:\"" + s + "\" Radix:" + radix);
        }
        return (short) value;
    }

    public static double parseDouble(String s) {
        if (s == null) {
            throw new NullPointerException();
        }
        Double value = readDouble(s);
        if (value == null) {
            throw new NumberFormatException(s.isBlank() ? "empty String" : "For input string: \"" + s + "\"");
        }
        return value;
    }

    public static String join(String delimiter, Iterable<?> elements) {
        StringBuilder text = new StringBuilder();
        boolean first = true;
        for (Object element : elements) {
            if (!first) {
                text.append(delimiter);
            }
            first = false;
            text.append(element);
        }
        return text.toString();
    }

    public static int addExact(int a, int b) {
        long sum = (long) a + b;
        if (sum != (int) sum) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) sum;
    }

    public static int subtractExact(int a, int b) {
        long difference = (long) a - b;
        if (difference != (int) difference) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) difference;
    }

    public static int multiplyExact(int a, int b) {
        long product = (long) a * b;
        if (product != (int) product) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) product;
    }

    public static int negateExact(int a) {
        if (a == Integer.MIN_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return -a;
    }

    public static int toIntExact(long value) {
        if (value != (int) value) {
            throw new ArithmeticException("integer overflow");
        }
        return (int) value;
    }

    public static int absExact(int a) {
        if (a == Integer.MIN_VALUE) {
            throw new ArithmeticException("Overflow to represent absolute value of Integer.MIN_VALUE");
        }
        return a < 0 ? -a : a;
    }

    public static long absExactLong(long a) {
        if (a == Long.MIN_VALUE) {
            throw new ArithmeticException("Overflow to represent absolute value of Long.MIN_VALUE");
        }
        return a < 0 ? -a : a;
    }

    public static int incrementExact(int a) {
        if (a == Integer.MAX_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return a + 1;
    }

    public static long incrementExactLong(long a) {
        if (a == Long.MAX_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return a + 1;
    }

    public static int decrementExact(int a) {
        if (a == Integer.MIN_VALUE) {
            throw new ArithmeticException("integer overflow");
        }
        return a - 1;
    }

    public static long decrementExactLong(long a) {
        if (a == Long.MIN_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return a - 1;
    }

    public static long negateExactLong(long a) {
        if (a == Long.MIN_VALUE) {
            throw new ArithmeticException("long overflow");
        }
        return -a;
    }

    // Math.clamp: the value kept between two bounds, which have to be in order.
    public static int clamp(long value, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return (int) (value < min ? min : value > max ? max : value);
    }

    public static long clampLong(long value, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return value < min ? min : value > max ? max : value;
    }

    public static double clampDouble(double value, double min, double max) {
        if (min != min) {
            throw new IllegalArgumentException("min is NaN");
        }
        if (max != max) {
            throw new IllegalArgumentException("max is NaN");
        }
        if (Double.compare(min, max) > 0) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return Math.min(max, Math.max(value, min));
    }

    public static float clampFloat(float value, float min, float max) {
        if (min != min) {
            throw new IllegalArgumentException("min is NaN");
        }
        if (max != max) {
            throw new IllegalArgumentException("max is NaN");
        }
        if (Float.compare(min, max) > 0) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return Math.min(max, Math.max(value, min));
    }

    public static long addExactLong(long a, long b) {
        long sum = a + b;
        if ((a >= 0) == (b >= 0) && (sum >= 0) != (a >= 0)) {
            throw new ArithmeticException("long overflow");
        }
        return sum;
    }

    public static long subtractExactLong(long a, long b) {
        long difference = a - b;
        if ((a >= 0) != (b >= 0) && (difference >= 0) != (a >= 0)) {
            throw new ArithmeticException("long overflow");
        }
        return difference;
    }

    public static long multiplyExactLong(long a, long b) {
        long product = a * b;
        if (a != 0 && (product / a != b || (a == -1 && b == Long.MIN_VALUE))) {
            throw new ArithmeticException("long overflow");
        }
        return product;
    }
}
