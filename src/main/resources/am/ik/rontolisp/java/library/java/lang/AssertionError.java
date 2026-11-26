// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// The detail is the text of any value; a Throwable given as the detail is the cause too.
public class AssertionError extends Error {

    public AssertionError() {
    }

    public AssertionError(Object detailMessage) {
        super(String.valueOf(detailMessage));
        if (detailMessage instanceof Throwable cause) {
            initCause(cause);
        }
    }

    public AssertionError(boolean detailMessage) {
        super(String.valueOf(detailMessage));
    }

    public AssertionError(char detailMessage) {
        super(String.valueOf(detailMessage));
    }

    public AssertionError(int detailMessage) {
        super(String.valueOf(detailMessage));
    }

    public AssertionError(long detailMessage) {
        super(String.valueOf(detailMessage));
    }

    public AssertionError(float detailMessage) {
        super(String.valueOf(detailMessage));
    }

    public AssertionError(double detailMessage) {
        super(String.valueOf(detailMessage));
    }

    public AssertionError(String message, Throwable cause) {
        super(message, cause);
    }
}
