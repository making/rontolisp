// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file;

import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.Objects;

public final class DirectoryIteratorException extends ConcurrentModificationException {

    public DirectoryIteratorException(IOException cause) {
        super(Objects.requireNonNull(cause));
    }

    public IOException getCause() {
        return (IOException) super.getCause();
    }
}
