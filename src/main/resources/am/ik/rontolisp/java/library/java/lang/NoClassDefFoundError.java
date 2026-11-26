// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

public class NoClassDefFoundError extends LinkageError {

    public NoClassDefFoundError() {
    }

    public NoClassDefFoundError(String message) {
        super(message);
    }

    private NoClassDefFoundError(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * What a later use of a class whose static initialization threw throws, from what
     * the class kept of it: made by the lowering.
     */
    private static Throwable unusable(String name, String kept) {
        return new NoClassDefFoundError("Could not initialize class " + name, new ExceptionInInitializerError(kept));
    }
}
