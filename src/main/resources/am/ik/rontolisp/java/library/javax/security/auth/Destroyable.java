// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package javax.security.auth;

// An object holding a secret that it may clear: by default it cannot, as a JDK's keys of
// java.security and javax.crypto cannot.
public interface Destroyable {

    default void destroy() throws DestroyFailedException {
        throw new DestroyFailedException();
    }

    default boolean isDestroyed() {
        return false;
    }
}
