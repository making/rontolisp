// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

// A walk that follows links met a directory it is already in.
public class FileSystemLoopException extends FileSystemException {

    public FileSystemLoopException(String file) {
        super(file);
    }
}
