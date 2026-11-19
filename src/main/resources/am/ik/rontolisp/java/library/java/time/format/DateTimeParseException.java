// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.format;

import java.time.DateTimeException;

public class DateTimeParseException extends DateTimeException {

    private final String parsed;

    private final int index;

    public DateTimeParseException(String message, CharSequence parsedData, int errorIndex) {
        super(message);
        this.parsed = parsedData.toString();
        this.index = errorIndex;
    }

    public DateTimeParseException(String message, CharSequence parsedData, int errorIndex, Throwable cause) {
        super(message, cause);
        this.parsed = parsedData.toString();
        this.index = errorIndex;
    }

    public String getParsedString() {
        return parsed;
    }

    public int getErrorIndex() {
        return index;
    }
}
