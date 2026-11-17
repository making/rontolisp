// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

public class RejectedExecutionException extends RuntimeException {

    public RejectedExecutionException() {
    }

    public RejectedExecutionException(String message) {
        super(message);
    }

    public RejectedExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public RejectedExecutionException(Throwable cause) {
        super(cause);
    }
}
