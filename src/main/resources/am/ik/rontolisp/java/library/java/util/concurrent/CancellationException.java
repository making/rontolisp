// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

public class CancellationException extends IllegalStateException {

    public CancellationException() {
    }

    public CancellationException(String message) {
        super(message);
    }
}
