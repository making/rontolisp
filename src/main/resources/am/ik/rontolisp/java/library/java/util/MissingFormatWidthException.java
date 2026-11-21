// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class MissingFormatWidthException extends IllegalFormatException {

    private final String specifier;

    public MissingFormatWidthException(String s) {
        super(Objects.requireNonNull(s));
        this.specifier = s;
    }

    public String getFormatSpecifier() {
        return specifier;
    }
}
