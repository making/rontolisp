// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.IOException;

public class InvalidPropertiesFormatException extends IOException {

    public InvalidPropertiesFormatException(Throwable cause) {
        super(cause);
    }

    public InvalidPropertiesFormatException(String message) {
        super(message);
    }
}
