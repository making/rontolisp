// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

// A path of the host's file system, the one kind there is: rontolisp.internal.LocalPath.
public interface Path extends Comparable<Path>, Iterable<Path> {

    static Path of(String first, String... more) {
        return rontolisp.internal.LocalPath.of(first, more);
    }

    boolean isAbsolute();

    Path getRoot();

    Path getFileName();

    Path getParent();

    int getNameCount();

    Path getName(int index);

    Path subpath(int beginIndex, int endIndex);

    boolean startsWith(Path other);

    default boolean startsWith(String other) {
        return startsWith(of(other));
    }

    boolean endsWith(Path other);

    default boolean endsWith(String other) {
        return endsWith(of(other));
    }

    Path normalize();

    Path resolve(Path other);

    default Path resolve(String other) {
        return resolve(of(other));
    }

    default Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    default Path resolveSibling(String other) {
        return resolveSibling(of(other));
    }

    Path relativize(Path other);

    Path toAbsolutePath();

    Path toRealPath(LinkOption... options) throws IOException;

    default File toFile() {
        return new File(toString());
    }

    Iterator<Path> iterator();

    int compareTo(Path other);

    boolean equals(Object other);

    int hashCode();

    String toString();
}
