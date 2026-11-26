// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

import java.time.DateTimeException;

public class UnsupportedTemporalTypeException extends DateTimeException {

    public UnsupportedTemporalTypeException(String message) {
        super(message);
    }

    public UnsupportedTemporalTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
