// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.charset;

// What a coder throws when the loop it runs throws.
public class CoderMalfunctionError extends Error {

    public CoderMalfunctionError(Exception cause) {
        super(cause);
    }
}
