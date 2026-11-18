// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// The cause is unset until a constructor or initCause sets it, and then fixed; a trace
// is the exception with its causes and the exceptions it suppressed, with no frame:
// what printStackTrace prints, and what is reported of an exception nothing caught.
public class Throwable {

    private String message;

    private Throwable cause;

    private boolean causeSet;

    private Throwable[] suppressed;

    private int suppressedCount;

    private static int traces;

    private int traced;

    public Throwable() {
    }

    public Throwable(String message) {
        this.message = message;
    }

    public Throwable(String message, Throwable cause) {
        this.message = message;
        this.cause = cause;
        this.causeSet = true;
    }

    public Throwable(Throwable cause) {
        this.message = cause == null ? null : cause.toString();
        this.cause = cause;
        this.causeSet = true;
    }

    public String getMessage() {
        return message;
    }

    public String getLocalizedMessage() {
        return getMessage();
    }

    public Throwable getCause() {
        return cause;
    }

    public Throwable initCause(Throwable cause) {
        if (causeSet) {
            throw new IllegalStateException("Can't overwrite cause with " + (cause == null ? "a null" : cause.toString()), this);
        }
        if (cause == this) {
            throw new IllegalArgumentException("Self-causation not permitted", this);
        }
        this.cause = cause;
        this.causeSet = true;
        return this;
    }

    public final void addSuppressed(Throwable exception) {
        if (exception == this) {
            throw new IllegalArgumentException("Self-suppression not permitted", exception);
        }
        if (exception == null) {
            throw new NullPointerException("Cannot suppress a null exception.");
        }
        if (suppressed == null) {
            suppressed = new Throwable[2];
        }
        else if (suppressedCount == suppressed.length) {
            Throwable[] grown = new Throwable[suppressedCount * 2];
            System.arraycopy(suppressed, 0, grown, 0, suppressedCount);
            suppressed = grown;
        }
        suppressed[suppressedCount++] = exception;
    }

    public final Throwable[] getSuppressed() {
        Throwable[] copy = new Throwable[suppressedCount];
        if (suppressedCount > 0) {
            System.arraycopy(suppressed, 0, copy, 0, suppressedCount);
        }
        return copy;
    }

    public String toString() {
        String name = getClass().getName();
        String text = getLocalizedMessage();
        return text == null ? name : name + ": " + text;
    }

    public void printStackTrace() {
        System.err.println(trace(this));
    }

    // What printStackTrace writes of an exception, and what is reported of one nothing
    // caught: the exception, then the suppressed exceptions and the cause under it,
    // each once, the second time a circular reference. An exception written keeps the
    // number of the trace that wrote it, and the lines are one text built by
    // concatenation: an array of the exceptions seen and a StringBuilder cost each
    // program that reports an exception more than the trace itself
    // (.kb/java-frontend.md, "Exceptions"). Two threads tracing at once read each
    // other's numbers.
    private static String trace(Throwable thrown) {
        traces++;
        thrown.traced = traces;
        return thrown.enclosed("", thrown.toString());
    }

    private String enclosed(String prefix, String lines) {
        for (int i = 0; i < suppressedCount; i++) {
            lines = suppressed[i].under("Suppressed: ", prefix + "\t", lines);
        }
        return cause == null ? lines : cause.under("Caused by: ", prefix, lines);
    }

    private String under(String caption, String prefix, String lines) {
        String line = lines + "\n" + prefix + caption;
        if (traced == traces) {
            return line + "[CIRCULAR REFERENCE: " + toString() + "]";
        }
        traced = traces;
        return enclosed(prefix, line + toString());
    }
}
