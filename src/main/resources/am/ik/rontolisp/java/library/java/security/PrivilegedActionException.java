// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

// The checked exception a privileged action threw, as its cause; no message of its own.
public class PrivilegedActionException extends Exception {

    public PrivilegedActionException(Exception exception) {
        super(null, exception);
    }

    public Exception getException() {
        return (Exception) getCause();
    }

    public String toString() {
        Throwable cause = getCause();
        String name = getClass().getName();
        return cause == null ? name : name + ": " + cause;
    }
}
