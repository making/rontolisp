// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.zone;

import java.time.DateTimeException;

public class ZoneRulesException extends DateTimeException {

    public ZoneRulesException(String message) {
        super(message);
    }

    public ZoneRulesException(String message, Throwable cause) {
        super(message, cause);
    }
}
