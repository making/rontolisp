// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.io.IOException;

// The file systems of files: the subset has the host's alone, and no provider that
// reads a file as a file system (a zip archive's).
public final class FileSystems {

    private FileSystems() {
    }

    public static FileSystem getDefault() {
        return rontolisp.internal.LocalFileSystem.HOST;
    }

    public static FileSystem newFileSystem(Path path) throws IOException {
        if (path == null) {
            throw new NullPointerException();
        }
        throw new ProviderNotFoundException("Provider not found");
    }

    public static FileSystem newFileSystem(Path path, ClassLoader loader) throws IOException {
        return newFileSystem(path);
    }
}
