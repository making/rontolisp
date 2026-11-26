// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

public class GeneralSecurityException extends Exception {

    public GeneralSecurityException() {
    }

    public GeneralSecurityException(String message) {
        super(message);
    }

    public GeneralSecurityException(String message, Throwable cause) {
        super(message, cause);
    }

    public GeneralSecurityException(Throwable cause) {
        super(cause);
    }
}
