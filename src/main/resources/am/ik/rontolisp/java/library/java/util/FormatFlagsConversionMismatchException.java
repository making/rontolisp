// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class FormatFlagsConversionMismatchException extends IllegalFormatException {

    private final String flags;

    private final char conversion;

    public FormatFlagsConversionMismatchException(String f, char c) {
        super("Conversion = " + c + ", Flags = " + Objects.requireNonNull(f));
        this.flags = f;
        this.conversion = c;
    }

    public String getFlags() {
        return flags;
    }

    public char getConversion() {
        return conversion;
    }

    /** The run time's, of its message: Conversion = c, Flags = f. */
    static FormatFlagsConversionMismatchException raised(String message) {
        return new FormatFlagsConversionMismatchException(message.substring(24), message.charAt(13));
    }
}
