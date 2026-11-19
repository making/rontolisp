// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent.atomic;

// What newUpdater of the three field updaters throws for a field it does not take.
// The field is found where the call is lowered -- the class and the name are written
// there --, so the lowering knows which of these the call is, and the call throws it
// where it runs. The messages are a JDK 25's, asked of it over 49 calls.
final class Fields {

    private Fields() {
    }

    /** The class declares no field of the name. */
    static Object missing(String name) {
        throw new RuntimeException(new NoSuchFieldException(name));
    }

    /** A field of another type, one that is not volatile, a static one (no message). */
    static Object refused(String message) {
        if (message == null) {
            throw new IllegalArgumentException();
        }
        throw new IllegalArgumentException(message);
    }

    /** A reference field whose type is not the class given. */
    static Object mismatched() {
        throw new ClassCastException();
    }
}
