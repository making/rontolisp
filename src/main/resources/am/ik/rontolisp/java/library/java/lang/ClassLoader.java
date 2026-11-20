// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

// The loaders of a program, as a source launcher has them: the program's, which finds a
// resource in the program's source tree and then under the roots of its source path; its
// parent the system's, whose class path those roots are and which loads the classes of
// the libraries there; and that one's parent the platform's, which finds none of them.
// They load no class. A resource is found while the program is lowered, and the contents
// travel with the program (.kb/java-frontend.md, "Resources").
public abstract class ClassLoader {

    // how far a loader's resources reach: none, the roots of the source path, the source
    // tree and then the roots
    static final int NONE = 0;

    static final int ROOTS = 1;

    static final int TREE = 2;

    private static final ClassLoader PLATFORM = new Application(NONE, null);

    private static final ClassLoader SYSTEM = new Application(ROOTS, PLATFORM);

    private static final ClassLoader PROGRAM = new Application(TREE, SYSTEM);

    private final int reach;

    private final ClassLoader parent;

    protected ClassLoader() {
        this(SYSTEM);
    }

    protected ClassLoader(ClassLoader parent) {
        this(parent == null ? NONE : parent.reach, parent);
    }

    ClassLoader(int reach, ClassLoader parent) {
        this.reach = reach;
        this.parent = parent;
    }

    public static ClassLoader getSystemClassLoader() {
        return SYSTEM;
    }

    public static ClassLoader getPlatformClassLoader() {
        return PLATFORM;
    }

    public final ClassLoader getParent() {
        return parent;
    }

    // the loader of the program's classes
    static ClassLoader program() {
        return PROGRAM;
    }

    static boolean searchesTree(ClassLoader loader) {
        return loader.reach == TREE;
    }

    static boolean searchesRoots(ClassLoader loader) {
        return loader.reach != NONE;
    }

    static int reach(ClassLoader loader) {
        return loader.reach;
    }

    // the name as it is: one with a leading '/' is none
    public native InputStream getResourceAsStream(String name);

    public static native InputStream getSystemResourceAsStream(String name);

    public native URL getResource(String name);

    public static native URL getSystemResource(String name);

    // every place holding the name, the source tree's first
    public native Enumeration<URL> getResources(String name) throws IOException;

    public static native Enumeration<URL> getSystemResources(String name) throws IOException;

    private static final class Application extends ClassLoader {

        Application(int reach, ClassLoader parent) {
            super(reach, parent);
        }
    }
}
