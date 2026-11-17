// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

// The three charsets this library encodes and decodes: UTF-8, ISO-8859-1 and US-ASCII.
public class Charset {

    static final Charset UTF_8 = new Charset("UTF-8");

    static final Charset ISO_8859_1 = new Charset("ISO-8859-1");

    static final Charset US_ASCII = new Charset("US-ASCII");

    private final String name;

    private Charset(String name) {
        this.name = name;
    }

    public static Charset defaultCharset() {
        return UTF_8;
    }

    // The charset of a name or of one of its aliases, whatever the case; null for a name
    // of no charset here.
    private static Charset find(String charsetName) {
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
        return null;
    }

    public static Charset forName(String charsetName) {
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

}
