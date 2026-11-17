// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

// The members of String, the boxes and Math that are written in Java: the ones that
// throw the library's exceptions or walk its collections.
public final class Support {

    private Support() {
    }

    private static NumberFormatException malformed(String s) {
        return new NumberFormatException(s == null ? "Cannot parse null string" : "For input string: \"" + s + "\"" );
    }

    public static long parseLong(String s, int radix) {
        return parse(s, radix, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static int parseInt(String s, int radix) {
        return (int) parse(s, radix, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    // The digits are accumulated as a negative number, whose range is the larger one.
    private static long parse(String s, int radix, long least, long most) {
        if (s == null || radix < 2 || radix > 36) {
            throw malformed(s);
        }
        int n = s.length();
        int i = 0;
        boolean negative = false;
        if (n > 0 && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            negative = s.charAt(0) == '-';
            i = 1;
        }
        if (i == n) {
            throw malformed(s);
        }
        long limit = negative ? least : -most;
        long edge = limit / radix;
        long value = 0;
        while (i < n) {
            int digit = Character.digit(s.charAt(i++), radix);
            if (digit < 0 || value < edge) {
                throw overflow(s, radix);
            }
            value *= radix;
            if (value < limit + digit) {
                throw overflow(s, radix);
            }
            value -= digit;
        }
        return negative ? value : -value;
    }

    private static NumberFormatException overflow(String s, int radix) {
        return new NumberFormatException("For input string: \"" + s + "\"" + (radix == 10 ? "" : " under radix " + radix));
    }

    // Integer.parseUnsignedInt: no sign but a +, a value under 2^32 as the int of its bits.
    public static int parseUnsignedInt(String s, int radix) {
        if (s == null) {
            throw new NumberFormatException("Cannot parse null string");
        }
        if (!s.isEmpty() && s.charAt(0) == '-') {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + s + ".");
        }
        long value = parse(s, radix, Long.MIN_VALUE, Long.MAX_VALUE);
        if (value > 0xFFFFFFFFL) {
            throw new NumberFormatException(
                    "String value " + s + " exceeds range of unsigned int.");
        }
        return (int) value;
    }

    // The charset of a name, for the members that take a name and not a Charset.
    public static Charset charset(String name) throws UnsupportedEncodingException {
        if (!Charset.isSupported(name)) {
            throw new UnsupportedEncodingException(name);
        }
        return Charset.forName(name);
    }

    public static boolean isUtf8(Charset charset) {
        return charset == StandardCharsets.UTF_8;
    }

    // What a reader or a writer answers for its encoding.
    public static String historicalName(Charset charset) {
        return charset == StandardCharsets.UTF_8 ? "UTF8" : charset == StandardCharsets.ISO_8859_1 ? "ISO8859_1" : "ASCII";
    }

    /** Whether the target runs the threads of a program on one stack. */
    public static native boolean oneStack();

    private static native byte[] utf8(String s);

    private static native String utf8String(byte[] b, int offset, int count);

    // String.getBytes(Charset): a character a charset of one byte does not have is ?.
    public static byte[] encode(String s, Charset charset) {
        if (isUtf8(charset)) {
            return utf8(s);
        }
        int last = charset == StandardCharsets.ISO_8859_1 ? 0xff : 0x7f;
        int n = s.length();
        byte[] encoded = new byte[n];
        for (int i = 0; i < n; i++) {
            int c = s.charAt(i);
            encoded[i] = (byte) (c <= last ? c : '?');
        }
        return encoded;
    }

    public static byte[] encodeNamed(String s, String charsetName) throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException();
        }
        return encode(s, charset(charsetName));
    }

    public static String decode(byte[] b, Charset charset) {
        return decode(b, 0, b.length, charset);
    }

    // new String(byte[], int, int, Charset): a byte a charset of one byte does not have
    // is U+FFFD.
    public static String decode(byte[] b, int offset, int count, Charset charset) {
        if (isUtf8(charset)) {
            return utf8String(b, offset, count);
        }
        if (offset < 0 || count < 0 || offset > b.length - count) {
            throw new StringIndexOutOfBoundsException(
                    "Range [" + offset + ", " + offset + " + " + count + ") out of bounds for length " + b.length);
        }
        int last = charset == StandardCharsets.ISO_8859_1 ? 0xff : 0x7f;
        char[] units = new char[count];
        for (int i = 0; i < count; i++) {
            int c = b[offset + i] & 0xff;
            units[i] = (char) (c <= last ? c : 0xfffd);
        }
        return new String(units, 0, count);
    }

    public static String decodeNamed(byte[] b, String charsetName) throws UnsupportedEncodingException {
        return decodeNamed(b, 0, b.length, charsetName);
    }

    public static String decodeNamed(byte[] b, int offset, int count, String charsetName)
            throws UnsupportedEncodingException {
        if (charsetName == null) {
            throw new NullPointerException("charsetName");
        }
        return decode(b, offset, count, charset(charsetName));
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
