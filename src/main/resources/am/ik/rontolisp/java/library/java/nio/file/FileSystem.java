// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

// A file system: the subset has the host's alone (rontolisp.internal.LocalFileSystem),
// and no provider of another (FileSystems.newFileSystem finds none).
public abstract class FileSystem implements Closeable {

    protected FileSystem() {
    }

    public abstract Iterable<Path> getRootDirectories();

    public abstract boolean isOpen();

    public abstract boolean isReadOnly();

    public abstract String getSeparator();

    public abstract Path getPath(String first, String... more);

    public abstract PathMatcher getPathMatcher(String syntaxAndPattern);

    public abstract Set<String> supportedFileAttributeViews();

    public abstract void close() throws IOException;
}
