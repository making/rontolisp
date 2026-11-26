// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// The cause is set by every constructor, so initCause refuses. The two static members
// are what the lowering makes of a static initialization that threw.
public class ExceptionInInitializerError extends LinkageError {

    public ExceptionInInitializerError() {
        super(null, null);
    }

    public ExceptionInInitializerError(Throwable thrown) {
        super(null, thrown);
    }

    public ExceptionInInitializerError(String message) {
        super(message, null);
    }

    public Throwable getException() {
        return getCause();
    }

    /** What a static initialization that threw ends with. */
    private static Throwable of(Throwable thrown) {
        return thrown instanceof Error ? thrown : new ExceptionInInitializerError(thrown);
    }

    /** What a class keeps of the exception its initialization threw in a thread. */
    private static String record(Throwable thrown, String thread) {
        return "Exception " + thrown + " [in thread \"" + thread + "\"]";
    }
}
