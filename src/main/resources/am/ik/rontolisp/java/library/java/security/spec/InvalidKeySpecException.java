// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security.spec;

import java.security.GeneralSecurityException;

public class InvalidKeySpecException extends GeneralSecurityException {

    public InvalidKeySpecException() {
    }

    public InvalidKeySpecException(String message) {
        super(message);
    }

    public InvalidKeySpecException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidKeySpecException(Throwable cause) {
        super(cause);
    }
}
