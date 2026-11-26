// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class IllegalFormatPrecisionException extends IllegalFormatException {

    private final int precision;

    public IllegalFormatPrecisionException(int p) {
        super(Integer.toString(p));
        this.precision = p;
    }

    public int getPrecision() {
        return precision;
    }

    /** The run time's, of its message: the precision. */
    static IllegalFormatPrecisionException raised(String message) {
        return new IllegalFormatPrecisionException(Integer.parseInt(message));
    }
}
