// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.lang.annotation.Annotation;
import java.util.Arrays;

// What the class of an annotation the lowering writes answers of equals, hashCode and
// toString, for each kind of element: equal as java.lang.annotation.Annotation specifies
// (a float or a double as its box's equals, an array as Arrays.equals), the hash codes it
// specifies, and the texts a JDK writes -- a value as Java source writes it, a string's and a
// character's escapes, a byte in hexadecimal, NaN and the infinities as divisions --, read off
// a JDK's answers to probes.
public final class Annotations {

    private Annotations() {
    }

    // the annotations the lowering made of a declaration, a new vector of new ones or null
    // for none, as an array: every list of annotations (getAnnotations() and the rest) is
    // made here, which is how the lowering knows that a program lists them
    public static Annotation[] listed(Object made) {
        Object[] all = (Object[]) made;
        Annotation[] taken = new Annotation[all == null ? 0 : all.length];
        for (int index = 0; index < taken.length; index++) {
            taken[index] = (Annotation) all[index];
        }
        return taken;
    }

    // an annotation holding the annotations of a repeatable type: its value()
    public interface Container {

        Annotation[] contained();
    }

    public static boolean same(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    public static boolean same(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    public static boolean same(Object a, Object b) {
        return a.equals(b);
    }

    public static boolean same(boolean[] a, boolean[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(short[] a, short[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(char[] a, char[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(int[] a, int[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(long[] a, long[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(float[] a, float[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(double[] a, double[] b) {
        return Arrays.equals(a, b);
    }

    public static boolean same(Object[] a, Object[] b) {
        return Arrays.equals(a, b);
    }

    public static int hash(boolean value) {
        return value ? 1231 : 1237;
    }

    public static int hash(int value) {
        return value;
    }

    public static int hash(long value) {
        return (int) (value ^ (value >>> 32));
    }

    public static int hash(float value) {
        return Float.floatToIntBits(value);
    }

    public static int hash(double value) {
        long bits = Double.doubleToLongBits(value);
        return (int) (bits ^ (bits >>> 32));
    }

    public static int hash(Object value) {
        return value.hashCode();
    }

    public static int hash(boolean[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(byte[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(short[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(char[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(int[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(long[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(float[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(double[] value) {
        return Arrays.hashCode(value);
    }

    public static int hash(Object[] value) {
        return Arrays.hashCode(value);
    }

    public static String text(boolean value) {
        return value ? "true" : "false";
    }

    public static String text(byte value) {
        return "(byte)0x" + DIGITS.charAt((value >> 4) & 15) + DIGITS.charAt(value & 15);
    }

    public static String text(short value) {
        return String.valueOf((int) value);
    }

    public static String text(char value) {
        return "'" + (value == '\'' ? "\\'" : value == '"' ? "\"" : escaped(value)) + "'";
    }

    public static String text(int value) {
        return String.valueOf(value);
    }

    public static String text(long value) {
        return value + "L";
    }

    public static String text(float value) {
        if (Float.isNaN(value)) {
            return "0.0f/0.0f";
        }
        if (Float.isInfinite(value)) {
            return value > 0 ? "1.0f/0.0f" : "-1.0f/0.0f";
        }
        return Float.toString(value) + "f";
    }

    public static String text(double value) {
        if (Double.isNaN(value)) {
            return "0.0/0.0";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "1.0/0.0" : "-1.0/0.0";
        }
        return Double.toString(value);
    }

    public static String text(String value) {
        StringBuilder written = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            written.append(c == '"' ? "\\\"" : c == '\'' ? "'" : escaped(c));
        }
        return written.append('"').toString();
    }

    public static String text(Class<?> value) {
        return value.getCanonicalName() + ".class";
    }

    public static String text(Enum<?> value) {
        return value.name();
    }

    public static String text(Annotation value) {
        return value.toString();
    }

    public static String text(boolean[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(byte[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(short[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(char[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(int[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(long[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(float[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(double[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(String[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(Class<?>[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(Enum<?>[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    public static String text(Annotation[] values) {
        StringBuilder written = new StringBuilder("{");
        for (int index = 0; index < values.length; index++) {
            written.append(index == 0 ? "" : ", ").append(text(values[index]));
        }
        return written.append('}').toString();
    }

    private static final String DIGITS = "0123456789abcdef";

    // a character as a JDK writes it in an annotation's text: the escapes of backspace, tab,
    // line feed, form feed, carriage return and the backslash, the printable ASCII characters
    // as they are, any other as a Unicode escape of four hexadecimal digits
    private static String escaped(char c) {
        switch (c) {
            case '\b':
                return "\\b";
            case '\t':
                return "\\t";
            case '\n':
                return "\\n";
            case '\f':
                return "\\f";
            case '\r':
                return "\\r";
            case '\\':
                return "\\\\";
            default:
                if (c >= ' ' && c < 0x7f) {
                    return String.valueOf(c);
                }
                return "\\u" + DIGITS.charAt((c >> 12) & 15) + DIGITS.charAt((c >> 8) & 15) + DIGITS.charAt((c >> 4) & 15)
                        + DIGITS.charAt(c & 15);
        }
    }
}
