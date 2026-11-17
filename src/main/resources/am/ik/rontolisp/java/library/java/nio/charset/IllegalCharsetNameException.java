// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

public class IllegalCharsetNameException extends IllegalArgumentException {

    private final String charsetName;

    public IllegalCharsetNameException(String charsetName) {
        super(String.valueOf(charsetName));
        this.charsetName = charsetName;
    }

    public String getCharsetName() {
        return charsetName;
    }
}
