// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang.reflect;

// A method a class declares, as Class.getDeclaredMethods() answers it: its name and the
// class declaring it, and nothing else of the method -- no types, no modifiers, no call.
// Two made for one method are equal.
public final class Method {

    private final Class<?> declaring;

    private final String name;

    // where it stands among the methods its class declares, which tells it from another
    // of the same name
    private final int index;

    private Method(Class<?> declaring, String name, int index) {
        this.declaring = declaring;
        this.name = name;
        this.index = index;
    }

    // what Class.getDeclaredMethods() makes each of
    static Method declared(Class<?> declaring, String name, int index) {
        return new Method(declaring, name, index);
    }

    public String getName() {
        return name;
    }

    public Class<?> getDeclaringClass() {
        return declaring;
    }

    public boolean equals(Object other) {
        return other instanceof Method method && method.declaring == declaring && method.index == index;
    }

    // the hash code of the declaring class's name and of the method's, exclusive-or'd
    public int hashCode() {
        return declaring.getName().hashCode() ^ name.hashCode();
    }

    // a method's text names its types and modifiers, which the program does not keep
    public String toString() {
        throw new UnsupportedOperationException(
                "Method.toString() is not supported by the experimental Java subset yet");
    }
}
