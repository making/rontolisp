// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

public class NoSuchFileException extends FileSystemException {

    public NoSuchFileException(String file) {
        super(file);
    }

    public NoSuchFileException(String file, String other, String reason) {
        super(file, other, reason);
    }
}
