// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.lang;

// The cause is unset until a constructor or initCause sets it, and then fixed; a trace
// is what printStackTrace prints of the causes and the suppressed exceptions, with no
// frame.
public class Throwable {

    private String message;

    private Throwable cause;

    private boolean causeSet;

    private Throwable[] suppressed;

    private int suppressedCount;

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
        System.err.println(this);
        Throwable[] seen = new Throwable[] {this};
        seen = enclosed(seen);
    }

    // The suppressed exceptions and the cause under this one: each once, the second
    // time a circular reference.
    private Throwable[] enclosed(Throwable[] seen) {
        for (int i = 0; i < suppressedCount; i++) {
            seen = suppressed[i].print("Suppressed: ", "\t", seen);
        }
        if (cause != null) {
            seen = cause.print("Caused by: ", "", seen);
        }
        return seen;
    }

    private Throwable[] print(String caption, String prefix, Throwable[] seen) {
        for (Throwable printed : seen) {
            if (printed == this) {
                System.err.println(prefix + caption + "[CIRCULAR REFERENCE: " + this + "]");
                return seen;
            }
        }
        Throwable[] more = new Throwable[seen.length + 1];
        System.arraycopy(seen, 0, more, 0, seen.length);
        more[seen.length] = this;
        System.err.println(prefix + caption + this);
        for (int i = 0; i < suppressedCount; i++) {
            more = suppressed[i].print("Suppressed: ", prefix + "\t", more);
        }
        if (cause != null) {
            more = cause.print("Caused by: ", prefix, more);
        }
        return more;
    }
}
