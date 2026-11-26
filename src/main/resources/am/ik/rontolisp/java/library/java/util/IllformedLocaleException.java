// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public class IllformedLocaleException extends RuntimeException {

    private int errIdx = -1;

    public IllformedLocaleException() {
    }

    public IllformedLocaleException(String message) {
        super(message);
    }

    public IllformedLocaleException(String message, int errorIndex) {
        super(message + (errorIndex < 0 ? "" : " [at index " + errorIndex + "]"));
        this.errIdx = errorIndex;
    }

    public int getErrorIndex() {
        return errIdx;
    }
}
