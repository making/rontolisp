// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

public class MatchException extends RuntimeException {

    public MatchException(String message, Throwable cause) {
        super(message, cause);
    }

    // What a catch makes of the one the run time raises: no cause is handed, so a
    // program that catches it is not one whose exceptions can have a cause.
    private MatchException(String message) {
        super(message);
    }
}
