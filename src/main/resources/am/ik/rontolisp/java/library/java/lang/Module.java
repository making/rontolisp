// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// A module as a source launcher's program sees one: the unnamed module of each of its
// loaders, which a class of the program or of a library on the source path is in, and
// java.base, which every class of the JDK is in. It reflects on nothing.
public final class Module {

    static final Module BASE = new Module("java.base", null);

    private final String name;

    private final ClassLoader loader;

    Module(String name, ClassLoader loader) {
        this.name = name;
        this.loader = loader;
    }

    public String getName() {
        return name;
    }

    public boolean isNamed() {
        return name != null;
    }

    public ClassLoader getClassLoader() {
        return loader;
    }

    public String toString() {
        return name != null ? "module " + name : "unnamed module @" + Integer.toHexString(hashCode());
    }
}
