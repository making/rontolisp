// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

import java.io.InputStream;

// The two loaders of a program, as a source launcher has them: the program's, which
// finds a resource in the program's source tree and then under the roots of its source
// path, and the system's, whose class path those roots are and which loads the classes of
// the libraries there. They load no class. A resource is found while the program is
// lowered, so a name is a constant and the contents travel with the program
// (.kb/java-frontend.md, "Resources").
public abstract class ClassLoader {

    private static final ClassLoader SYSTEM = new Application(false);

    private static final ClassLoader PROGRAM = new Application(true);

    // whether it finds the program's source tree first
    private final boolean tree;

    protected ClassLoader() {
        this(false);
    }

    ClassLoader(boolean tree) {
        this.tree = tree;
    }

    public static ClassLoader getSystemClassLoader() {
        return SYSTEM;
    }

    // the loader of the program's classes
    static ClassLoader program() {
        return PROGRAM;
    }

    static boolean searchesTree(ClassLoader loader) {
        return loader.tree;
    }

    // the name as it is: one with a leading '/' is none
    public native InputStream getResourceAsStream(String name);

    public static native InputStream getSystemResourceAsStream(String name);

    private static final class Application extends ClassLoader {

        Application(boolean tree) {
            super(tree);
        }
    }
}
