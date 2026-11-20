// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

import java.io.InputStream;

// The object standing for a class, an interface, an array type or a primitive type. The
// lowering makes one of each type a program has as a value -- a class literal or
// getClass() read as anything but a comparison with another or its name -- before
// anything of the program runs, with what the program asks of one: the classes above it
// where a member reads them, the test of an instance where isInstance or cast is
// reached, the constants where getEnumConstants is. It has no reflection: no member, no
// constructor and no class by name.
public final class Class<T> {

    private final String name;

    private final String simpleName;

    // null where it is the name, empty for a local or an anonymous class, which has none
    private final String canonical;

    // 1 an interface, 2 an array type, 4 a primitive type, 8 an enum, 16 a record
    private final int kind;

    private final Class<?> superclass;

    // null where the program has no member reading them
    private final Class<?>[] interfaces;

    private final Class<?> component;

    // the function of one value answering whether it is an instance
    private final Object test;

    // the function of no value answering an enum's constants, or null
    private final Object constants;

    Class(String name, String simpleName, String canonical, int kind, Class<?> superclass, Class<?>[] interfaces,
            Class<?> component, Object test, Object constants) {
        this.name = name;
        this.simpleName = simpleName;
        this.canonical = canonical;
        this.kind = kind;
        this.superclass = superclass;
        this.interfaces = interfaces;
        this.component = component;
        this.test = test;
        this.constants = constants;
    }

    public String getName() {
        return name;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public String getCanonicalName() {
        if (component != null) {
            String element = component.getCanonicalName();
            return element == null ? null : element + "[]";
        }
        return canonical == null ? name : canonical.isEmpty() ? null : canonical;
    }

    public String getTypeName() {
        return component != null ? component.getTypeName() + "[]" : name;
    }

    public String getPackageName() {
        Class<?> element = this;
        while (element.component != null) {
            element = element.component;
        }
        int dot = element.name.lastIndexOf('.');
        return (element.kind & 4) != 0 ? "java.lang" : dot < 0 ? "" : element.name.substring(0, dot);
    }

    public boolean isInterface() {
        return (kind & 1) != 0;
    }

    public boolean isArray() {
        return (kind & 2) != 0;
    }

    public boolean isPrimitive() {
        return (kind & 4) != 0;
    }

    public boolean isEnum() {
        return (kind & 8) != 0;
    }

    public boolean isRecord() {
        return (kind & 16) != 0;
    }

    @SuppressWarnings("unchecked")
    public Class<? super T> getSuperclass() {
        return (Class<? super T>) superclass;
    }

    public Class<?> getComponentType() {
        return component;
    }

    public boolean isInstance(Object value) {
        return test0(test, value);
    }

    @SuppressWarnings("unchecked")
    public T cast(Object value) {
        if (value != null && !test0(test, value)) {
            throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to " + name);
        }
        return (T) value;
    }

    public boolean isAssignableFrom(Class<?> other) {
        if (other == this) {
            return true;
        }
        if ((kind & 2) != 0) {
            // an array of references is one of what its elements are
            return (other.kind & 2) != 0 && (component.kind & 4) == 0
                    && (other.component.kind & 4) == 0 && component.isAssignableFrom(other.component);
        }
        if ((other.kind & 4) != 0) {
            return false;
        }
        if (superclass == null && (kind & 5) == 0) {
            // Object: every class, interface and array type
            return true;
        }
        if (other.superclass != null && isAssignableFrom(other.superclass)) {
            return true;
        }
        for (Class<?> implemented : other.interfaces) {
            if (isAssignableFrom(implemented)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public T[] getEnumConstants() {
        return constants == null ? null : (T[]) constants0(constants);
    }

    // a resource beside the class, as its loader finds it: a name with no leading '/' in
    // the class's package. The lowering finds it, so the name is a constant; a class of
    // the JDK and a primitive type have none of the program's
    public native InputStream getResourceAsStream(String name);

    // where that resource was found when the program was compiled
    public native java.net.URL getResource(String name);

    // the program's loader for a class of the program, the system's for one of a library
    // on the source path, none for a class of the JDK and a primitive type
    public native ClassLoader getClassLoader();

    // the unnamed module of that loader, java.base for none
    public native Module getModule();

    // the binary name of the class a resource is found beside -- an array's element
    // class --, or null for a primitive type
    static String located(Class<?> type) {
        Class<?> element = type;
        while (element.component != null) {
            element = element.component;
        }
        return (element.kind & 4) != 0 ? null : element.name;
    }

    public String toString() {
        return ((kind & 1) != 0 ? "interface " : (kind & 4) != 0 ? "" : "class ") + name;
    }

    private static native boolean test0(Object test, Object value);

    private static native Object[] constants0(Object constants);
}
