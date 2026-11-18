// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

// A charset is its codec. This class is UTF-8, which is the default, ISO-8859-1 and
// US-ASCII; the UTF-16 ones are objects of a class of their own
// (rontolisp.internal.Utf16) that only a program naming one makes, so that no other
// program holds their codec.
public class Charset {

    private static final Charset UTF_8 = new Charset("UTF-8");

    private static final Charset ISO_8859_1 = new Charset("ISO-8859-1");

    private static final Charset US_ASCII = new Charset("US-ASCII");

    private final String name;

    protected Charset(String name) {
        this.name = name;
    }

    // What the fields of StandardCharsets are read through.
    public static Charset utf8() {
        return UTF_8;
    }

    public static Charset latin1() {
        return ISO_8859_1;
    }

    public static Charset ascii() {
        return US_ASCII;
    }

    public static Charset defaultCharset() {
        return UTF_8;
    }

    // The charset of a name or of one of its aliases, whatever the case; null for a name
    // of no charset here.
    public static Charset find(String charsetName) {
        String name = charsetName.toUpperCase();
        if (name.equals("UTF-8") || name.equals("UTF8") || name.equals("UNICODE-1-1-UTF-8")) {
            return UTF_8;
        }
        if (name.equals("ISO-8859-1") || name.equals("ISO8859_1") || name.equals("ISO_8859_1")
                || name.equals("ISO8859-1") || name.equals("LATIN1") || name.equals("L1") || name.equals("8859_1")
                || name.equals("ISO_8859-1") || name.equals("IBM819") || name.equals("CP819")) {
            return ISO_8859_1;
        }
        if (name.equals("US-ASCII") || name.equals("ASCII") || name.equals("ISO646-US")
                || name.equals("ANSI_X3.4-1968") || name.equals("646") || name.equals("ASCII7")
                || name.equals("ISO_646.IRV:1991") || name.equals("US") || name.equals("CP367")
                || name.equals("IBM367")) {
            return US_ASCII;
        }
        if (name.equals("UTF-16") || name.equals("UTF_16") || name.equals("UTF16") || name.equals("UNICODE")
                || name.equals("UNICODEBIG")) {
            return rontolisp.internal.Utf16.marked();
        }
        if (name.equals("UTF-16BE") || name.equals("UTF_16BE") || name.equals("X-UTF-16BE")
                || name.equals("UNICODEBIGUNMARKED") || name.equals("ISO-10646-UCS-2")) {
            return rontolisp.internal.Utf16.big();
        }
        if (name.equals("UTF-16LE") || name.equals("UTF_16LE") || name.equals("X-UTF-16LE")
                || name.equals("UNICODELITTLEUNMARKED")) {
            return rontolisp.internal.Utf16.little();
        }
        return null;
    }

    // Lowered where it is called: a name written as a constant is its charset there, so
    // that a program naming UTF-8 reaches no other; any other name is looked up.
    public static native Charset forName(String charsetName);

    public static Charset lookup(String charsetName) {
        if (charsetName == null) {
            throw new IllegalArgumentException("Null charset name");
        }
        Charset found = find(charsetName);
        if (found == null) {
            if (charsetName.isEmpty()) {
                throw new IllegalCharsetNameException(charsetName);
            }
            throw new UnsupportedCharsetException(charsetName);
        }
        return found;
    }

    public static boolean isSupported(String charsetName) {
        return find(charsetName) != null;
    }

    public final String name() {
        return name;
    }

    public String displayName() {
        return name;
    }

    public final String toString() {
        return name;
    }

    // The bytes of a text: a character a charset of one byte does not have is ?.
    public byte[] bytes(String s) {
        if (this == UTF_8) {
            return rontolisp.internal.Support.utf8(s);
        }
        int last = this == ISO_8859_1 ? 0xff : 0x7f;
        int n = s.length();
        byte[] encoded = new byte[n];
        for (int i = 0; i < n; i++) {
            int c = s.charAt(i);
            encoded[i] = (byte) (c <= last ? c : '?');
        }
        return encoded;
    }

    // The text of bytes: a byte a charset of one byte does not have is U+FFFD.
    public String text(byte[] b, int offset, int count) {
        if (this == UTF_8) {
            return rontolisp.internal.Support.utf8String(b, offset, count);
        }
        if (offset < 0 || count < 0 || offset > b.length - count) {
            throw new StringIndexOutOfBoundsException(
                    "Range [" + offset + ", " + offset + " + " + count + ") out of bounds for length " + b.length);
        }
        int last = this == ISO_8859_1 ? 0xff : 0x7f;
        char[] units = new char[count];
        for (int i = 0; i < count; i++) {
            int c = b[offset + i] & 0xff;
            units[i] = (char) (c <= last ? c : 0xfffd);
        }
        return new String(units, 0, count);
    }

    // How many bytes at the end of b[0, total) start a character and do not finish it:
    // a reader keeps them for the bytes after them.
    public int unfinished(byte[] b, int total) {
        if (this != UTF_8) {
            return 0;
        }
        for (int back = 1; back <= 3 && back <= total; back++) {
            int unit = b[total - back] & 0xff;
            if (unit >= 0xc0) {
                int size = unit >= 0xf0 ? 4 : unit >= 0xe0 ? 3 : 2;
                return size > back ? back : 0;
            }
            if (unit < 0x80) {
                return 0;
            }
        }
        return 0;
    }

    // The charset the rest of an output is written in once its start is, and the rest
    // of an input read in once the count bytes at the start of b are: the charset
    // itself, but for the one whose start holds a mark.
    public Charset writing() {
        return this;
    }

    public Charset reading(byte[] b, int count) {
        return this;
    }

    // What a writer closed on a surrogate with no partner writes for it.
    public String lone() {
        return "?";
    }

    // What a reader or a writer answers for its encoding.
    public String historicalName() {
        return this == UTF_8 ? "UTF8" : this == ISO_8859_1 ? "ISO8859_1" : "ASCII";
    }

}
