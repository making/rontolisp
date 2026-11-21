// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

// A format written to an Appendable: the run time's format of String.format, its pieces
// appended as it reaches them -- a run of the control's text as a range of it, a
// converted argument as one text with its padding -- so that what an append refuses
// with an IOException is kept for ioException() and the format goes on with the next
// piece. A locale changes nothing a format writes: each one there is writes as the root
// does; a Formattable is handed a Formatter of the locale the format was given.
public final class Formatter implements Closeable, Flushable {

    private final Appendable a;

    private final Locale l;

    /** The locale of the format being written, which a Formattable's Formatter has. */
    private Locale current;

    private IOException lastException;

    private boolean closed;

    public Formatter() {
        this(new StringBuilder(), Locale.getDefault());
    }

    public Formatter(Appendable a) {
        this(a, Locale.getDefault());
    }

    public Formatter(Locale l) {
        this(new StringBuilder(), l);
    }

    public Formatter(Appendable a, Locale l) {
        this.a = a == null ? new StringBuilder() : a;
        this.l = l;
        this.current = l;
    }

    public Formatter(PrintStream ps) {
        this((Appendable) Objects.requireNonNull(ps), Locale.getDefault());
    }

    public Formatter(OutputStream os) {
        this(new BufferedWriter(new OutputStreamWriter(os)), Locale.getDefault());
    }

    public Formatter(OutputStream os, String csn) throws UnsupportedEncodingException {
        this(os, csn, Locale.getDefault());
    }

    public Formatter(OutputStream os, String csn, Locale l) throws UnsupportedEncodingException {
        this(new BufferedWriter(new OutputStreamWriter(os, csn)), l);
    }

    public Formatter(OutputStream os, Charset charset, Locale l) {
        this(new BufferedWriter(new OutputStreamWriter(os, charset)), l);
    }

    private void ensureOpen() {
        if (closed) {
            throw new FormatterClosedException();
        }
    }

    public Locale locale() {
        ensureOpen();
        return l;
    }

    public Appendable out() {
        ensureOpen();
        return a;
    }

    public String toString() {
        ensureOpen();
        return a.toString();
    }

    public void flush() {
        ensureOpen();
        if (a instanceof Flushable flushable) {
            try {
                flushable.flush();
            }
            catch (IOException ex) {
                lastException = ex;
            }
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (a instanceof Closeable closeable) {
            try {
                closeable.close();
            }
            catch (IOException ex) {
                lastException = ex;
            }
        }
    }

    public IOException ioException() {
        return lastException;
    }

    public Formatter format(String format, Object... args) {
        return format(l, format, args);
    }

    public Formatter format(Locale l, String format, Object... args) {
        ensureOpen();
        current = l;
        try {
            format0(this, format, args);
        }
        finally {
            current = this.l;
        }
        return this;
    }

    /** The run time's format of the control and the arguments, appended through put. */
    private static native void format0(Formatter formatter, String format, Object[] args);

    /**
     * A piece of a format: the text from start to end, or all of it where end is
     * negative.
     */
    static void put(Formatter formatter, CharSequence s, int start, int end) {
        try {
            if (end < 0) {
                formatter.a.append(s);
            }
            else {
                formatter.a.append(s, start, end);
            }
        }
        catch (IOException ex) {
            formatter.lastException = ex;
        }
    }

    /** The Formatter a Formattable writes itself into, of the format being written. */
    Formatter written() {
        return new Formatter(a, current);
    }
}
