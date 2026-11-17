// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

public final class StandardCharsets {

    private StandardCharsets() {
    }

    public static final Charset UTF_8 = Charset.UTF_8;

    public static final Charset ISO_8859_1 = Charset.ISO_8859_1;

    public static final Charset US_ASCII = Charset.US_ASCII;
}
