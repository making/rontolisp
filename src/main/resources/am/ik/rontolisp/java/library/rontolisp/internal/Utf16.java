// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.nio.charset.Charset;

// UTF-16, UTF-16BE and UTF-16LE, with a JDK 25's answers (.kb/java-frontend.md,
// "java.io"). UTF-16 writes the mark FE FF ahead of a text that is not empty and the
// high byte first; it reads a mark at the start as the order of what follows and the
// high byte first without one. The other two write and read no mark. A surrogate with
// no partner is U+FFFD either way; so is a byte with no partner, and a high surrogate
// together with the unit after it when that is no low one.
public final class Utf16 extends Charset {

    private static final Utf16 MARKED = new Utf16("UTF-16", true, false);

    private static final Utf16 BIG = new Utf16("UTF-16BE", false, false);

    private static final Utf16 LITTLE = new Utf16("UTF-16LE", false, true);

    // UTF-16 past the start of an output or an input: the mark is behind
    private static final Utf16 BIG_REST = new Utf16("UTF-16", false, false);

    private static final Utf16 LITTLE_REST = new Utf16("UTF-16", false, true);

    private final boolean marks;

    private final boolean little;

    private Utf16(String name, boolean marks, boolean little) {
        super(name);
        this.marks = marks;
        this.little = little;
    }

    public static Charset marked() {
        return MARKED;
    }

    public static Charset big() {
        return BIG;
    }

    public static Charset little() {
        return LITTLE;
    }

    private static int unit(byte[] b, int at, boolean little) {
        int first = b[at] & 0xff;
        int second = b[at + 1] & 0xff;
        return little ? second << 8 | first : first << 8 | second;
    }

    public byte[] bytes(String s) {
        char[] units = s.toCharArray();
        int n = units.length;
        if (n == 0) {
            return new byte[0];
        }
        int at = marks ? 2 : 0;
        byte[] encoded = new byte[at + 2 * n];
        if (marks) {
            encoded[0] = (byte) 0xfe;
            encoded[1] = (byte) 0xff;
        }
        for (int i = 0; i < n; i++) {
            int unit = units[i];
            boolean paired = i > 0 && unit >= 0xdc00 && unit <= 0xdfff && units[i - 1] >= 0xd800
                    && units[i - 1] <= 0xdbff;
            paired = paired || (unit >= 0xd800 && unit <= 0xdbff && i + 1 < n && units[i + 1] >= 0xdc00
                    && units[i + 1] <= 0xdfff);
            if (unit >= 0xd800 && unit <= 0xdfff && !paired) {
                unit = 0xfffd;
            }
            encoded[at + (little ? 1 : 0)] = (byte) (unit >> 8);
            encoded[at + (little ? 0 : 1)] = (byte) unit;
            at += 2;
        }
        return encoded;
    }

    public String text(byte[] b, int offset, int count) {
        if (offset < 0 || count < 0 || offset > b.length - count) {
            throw new StringIndexOutOfBoundsException(
                    "Range [" + offset + ", " + offset + " + " + count + ") out of bounds for length " + b.length);
        }
        int at = offset;
        int end = offset + count;
        boolean order = little;
        if (marks && count >= 2) {
            int mark = unit(b, at, false);
            if (mark == 0xfeff || mark == 0xfffe) {
                order = mark == 0xfffe;
                at += 2;
            }
        }
        char[] units = new char[(end - at + 1) / 2];
        int n = 0;
        while (at < end) {
            int unit = 0xfffd;
            if (end - at >= 2) {
                unit = unit(b, at, order);
                at += 2;
                if (unit >= 0xd800 && unit <= 0xdbff) {
                    int next = end - at >= 2 ? unit(b, at, order) : 0;
                    if (next >= 0xdc00 && next <= 0xdfff) {
                        units[n++] = (char) unit;
                        unit = next;
                    } else {
                        unit = 0xfffd;
                    }
                    at += 2;
                } else if (unit >= 0xdc00 && unit <= 0xdfff) {
                    unit = 0xfffd;
                }
            } else {
                at = end;
            }
            units[n++] = (char) unit;
        }
        return new String(units, 0, n);
    }

    // A byte with no partner yet waits, and so does a high surrogate that ends the bytes
    // when the unit after it is its to take: of the high surrogates at the end, each
    // takes the one after it, so the last waits when they are an odd number.
    public int unfinished(byte[] b, int total) {
        int odd = total & 1;
        int whole = total - odd;
        if (whole < 2) {
            return odd;
        }
        boolean order = marks ? unit(b, 0, false) == 0xfffe : little;
        int highs = 0;
        for (int at = whole - 2; at >= 0; at -= 2) {
            int unit = unit(b, at, order);
            if (unit < 0xd800 || unit > 0xdbff) {
                break;
            }
            highs++;
        }
        return highs % 2 == 1 ? odd + 2 : odd;
    }

    public Charset writing() {
        return marks ? BIG_REST : this;
    }

    public Charset reading(byte[] b, int count) {
        return !marks ? this : count >= 2 && unit(b, 0, false) == 0xfffe ? LITTLE_REST : BIG_REST;
    }

    public String lone() {
        return "\ufffd";
    }

    public String historicalName() {
        return name().equals("UTF-16") ? "UTF-16" : little ? "UnicodeLittleUnmarked" : "UnicodeBigUnmarked";
    }
}
