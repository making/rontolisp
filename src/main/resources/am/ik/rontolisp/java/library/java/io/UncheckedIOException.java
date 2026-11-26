// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

public class UncheckedIOException extends RuntimeException {

    public UncheckedIOException(String message, IOException cause) {
        super(message, Objects.requireNonNull(cause));
    }

    public UncheckedIOException(IOException cause) {
        super(Objects.requireNonNull(cause));
    }
}
