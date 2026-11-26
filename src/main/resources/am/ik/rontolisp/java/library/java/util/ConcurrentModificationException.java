// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class ConcurrentModificationException extends RuntimeException {

    public ConcurrentModificationException() {
    }

    public ConcurrentModificationException(String message) {
        super(message);
    }

    public ConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConcurrentModificationException(Throwable cause) {
        super(cause);
    }
}
