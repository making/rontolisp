// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.security;

public class InvalidAlgorithmParameterException extends GeneralSecurityException {

    public InvalidAlgorithmParameterException() {
    }

    public InvalidAlgorithmParameterException(String message) {
        super(message);
    }

    public InvalidAlgorithmParameterException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidAlgorithmParameterException(Throwable cause) {
        super(cause);
    }
}
