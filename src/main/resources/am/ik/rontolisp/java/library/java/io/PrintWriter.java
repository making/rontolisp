// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// Every print ends in text(). An IOException of the writer under this one is kept for
// checkError(). With automatic flushing the writer is flushed at each println, printf
// and format, and at nothing else.
public class PrintWriter extends Writer {

    protected Writer out;

    private final boolean autoFlush;

    private boolean trouble;

    public PrintWriter(Writer out) {
        this(out, false);
    }

    public PrintWriter(Writer out, boolean autoFlush) {
        this.out = Objects.requireNonNull(out);
        this.autoFlush = autoFlush;
    }

    public PrintWriter(OutputStream out) {
        this(out, false);
    }

    public PrintWriter(OutputStream out, boolean autoFlush) {
        this(new BufferedWriter(new OutputStreamWriter(out)), autoFlush);
    }

    private void text(String s, boolean line) {
        try {
            if (out == null) {
                throw new IOException("Stream closed");
            }
            out.write(line ? s + "\n" : s);
            if (line && autoFlush) {
                out.flush();
            }
        }
        catch (IOException e) {
            trouble = true;
        }
    }

    public void write(int c) {
        text(String.valueOf((char) c), false);
    }

    public void write(char[] buf, int off, int len) {
        text(new String(buf, off, len), false);
    }

    public void write(char[] buf) {
        write(buf, 0, buf.length);
    }

    public void write(String s, int off, int len) {
        text(s.substring(off, off + len), false);
    }

    public void write(String s) {
        text(s, false);
    }

    public void flush() {
        try {
            if (out == null) {
                throw new IOException("Stream closed");
            }
            out.flush();
        }
        catch (IOException e) {
            trouble = true;
        }
    }

    public void close() {
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        }
        catch (IOException e) {
            trouble = true;
        }
    }

    public boolean checkError() {
        if (out != null) {
            flush();
        }
        return trouble;
    }

    protected void setError() {
        trouble = true;
    }

    protected void clearError() {
        trouble = false;
    }

    public PrintWriter append(CharSequence csq) {
        write(String.valueOf(csq));
        return this;
    }

    public PrintWriter append(CharSequence csq, int start, int end) {
        write(String.valueOf(csq).substring(start, end));
        return this;
    }

    public PrintWriter append(char c) {
        write(c);
        return this;
    }

    public void print(boolean b) {
        text(String.valueOf(b), false);
    }

    public void print(char c) {
        text(String.valueOf(c), false);
    }

    public void print(int i) {
        text(String.valueOf(i), false);
    }

    public void print(long l) {
        text(String.valueOf(l), false);
    }

    public void print(float f) {
        text(String.valueOf(f), false);
    }

    public void print(double d) {
        text(String.valueOf(d), false);
    }

    public void print(char[] s) {
        text(String.valueOf(s), false);
    }

    public void print(String s) {
        text(String.valueOf(s), false);
    }

    public void print(Object obj) {
        text(String.valueOf(obj), false);
    }

    public void println() {
        text("", true);
    }

    public void println(boolean b) {
        text(String.valueOf(b), true);
    }

    public void println(char c) {
        text(String.valueOf(c), true);
    }

    public void println(int i) {
        text(String.valueOf(i), true);
    }

    public void println(long l) {
        text(String.valueOf(l), true);
    }

    public void println(float f) {
        text(String.valueOf(f), true);
    }

    public void println(double d) {
        text(String.valueOf(d), true);
    }

    public void println(char[] s) {
        text(String.valueOf(s), true);
    }

    public void println(String s) {
        text(String.valueOf(s), true);
    }

    public void println(Object obj) {
        text(String.valueOf(obj), true);
    }

    public PrintWriter printf(String format, Object... args) {
        return format(format, args);
    }

    // A locale changes nothing: each one there is writes as the root does.
    public PrintWriter printf(java.util.Locale l, String format, Object... args) {
        return format(format, args);
    }

    public PrintWriter format(java.util.Locale l, String format, Object... args) {
        return format(format, args);
    }

    public PrintWriter format(String format, Object... args) {
        text(String.format(format, args), false);
        if (autoFlush) {
            flush();
        }
        return this;
    }
}
