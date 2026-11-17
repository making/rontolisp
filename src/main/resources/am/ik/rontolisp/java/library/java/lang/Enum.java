// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that declares an enum.
package java.lang;

public abstract class Enum<E extends Enum<E>> {

    private final String name;

    private final int ordinal;

    protected Enum(String name, int ordinal) {
        this.name = name;
        this.ordinal = ordinal;
    }

    public final String name() {
        return name;
    }

    public final int ordinal() {
        return ordinal;
    }

    public String toString() {
        return name;
    }

    public final int compareTo(E other) {
        return ordinal - other.ordinal();
    }

    /**
     * What the valueOf(String) a JDK's compiler writes for an enum calls: this front
     * end writes an enum's own, and has no class object to call this one with.
     */
    public static <T extends Enum<T>> T valueOf(Class<T> enumClass, String name) {
        throw new IllegalArgumentException("No enum constant " + name);
    }
}
