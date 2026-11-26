// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class IllegalFormatFlagsException extends IllegalFormatException {

    private final String flags;

    public IllegalFormatFlagsException(String f) {
        super("Flags = '" + Objects.requireNonNull(f) + "'");
        this.flags = f;
    }

    public String getFlags() {
        return flags;
    }

    /** The run time's, of its message: Flags = 'f'. */
    static IllegalFormatFlagsException raised(String message) {
        return new IllegalFormatFlagsException(message.substring(9, message.length() - 1));
    }
}
