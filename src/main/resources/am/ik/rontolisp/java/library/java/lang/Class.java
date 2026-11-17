// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// The type alone: no program has a value of it. A class literal is refused but as a
// lock, and getClass() is read only before getName() and getSimpleName(), which the
// lowering answers itself. It is here for the signatures naming it -- Enum.valueOf,
// which the valueOf a JDK's compiler writes for an enum calls.
public final class Class<T> {

    private Class() {
    }

    public String getName() {
        throw new UnsupportedOperationException();
    }

    public String getSimpleName() {
        throw new UnsupportedOperationException();
    }
}
