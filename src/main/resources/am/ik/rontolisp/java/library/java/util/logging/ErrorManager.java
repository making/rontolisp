// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.logging;

// The first error alone is written to the standard error: its code, the message after
// it, then the exception's trace. The words are a JDK 25's.
public class ErrorManager {

    public static final int GENERIC_FAILURE = 0;

    public static final int WRITE_FAILURE = 1;

    public static final int FLUSH_FAILURE = 2;

    public static final int CLOSE_FAILURE = 3;

    public static final int OPEN_FAILURE = 4;

    public static final int FORMAT_FAILURE = 5;

    private boolean reported;

    public ErrorManager() {
    }

    public synchronized void error(String msg, Exception ex, int code) {
        if (reported) {
            return;
        }
        reported = true;
        String text = "java.util.logging.ErrorManager: " + code;
        if (msg != null) {
            text = text + ": " + msg;
        }
        System.err.println(text);
        if (ex != null) {
            ex.printStackTrace();
        }
    }

}
