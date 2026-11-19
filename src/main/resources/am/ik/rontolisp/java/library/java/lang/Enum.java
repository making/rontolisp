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

    /** The enum a constant is of: a constant with a body is of a class under it. */
    @SuppressWarnings("unchecked")
    public final Class<E> getDeclaringClass() {
        Class<?> own = getClass();
        if (!own.isEnum()) {
            own = own.getSuperclass();
        }
        return (Class<E>) own;
    }

    public static <T extends Enum<T>> T valueOf(Class<T> enumClass, String name) {
        T[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            throw new IllegalArgumentException(enumClass.getName() + " is not an enum class");
        }
        if (name == null) {
            throw new NullPointerException("Name is null");
        }
        for (T constant : constants) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("No enum constant " + enumClass.getCanonicalName() + "." + name);
    }
}
