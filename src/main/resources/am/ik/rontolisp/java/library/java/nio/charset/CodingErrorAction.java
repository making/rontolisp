// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

// What a coder does with input it cannot code: skip it, write its replacement for it, or
// answer the error.
public class CodingErrorAction {

    public static final CodingErrorAction IGNORE = new CodingErrorAction("IGNORE");

    public static final CodingErrorAction REPLACE = new CodingErrorAction("REPLACE");

    public static final CodingErrorAction REPORT = new CodingErrorAction("REPORT");

    private final String name;

    private CodingErrorAction(String name) {
        this.name = name;
    }

    public String toString() {
        return name;
    }
}
