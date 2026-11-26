// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.security.auth;

public class DestroyFailedException extends Exception {

    public DestroyFailedException() {
    }

    public DestroyFailedException(String message) {
        super(message);
    }
}
