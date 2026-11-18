// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.nio.charset.Charset;
import java.util.Objects;

// Every print ends in text(): the characters in the stream's charset, written to the
// stream under this one at once. An IOException of that stream is kept for
// checkError(). With automatic flushing the stream is flushed at each println, at each
// array of bytes and at each \n. The standard streams are StandardOutput, a subclass
// with no stream under it.
public class PrintStream extends FilterOutputStream implements Appendable {

    private final boolean autoFlush;

    // the charset of what is still to print: past its start, UTF-16 writes no mark
    private Charset charset;

    private boolean trouble;

    private boolean closing;

    public PrintStream(OutputStream out) {
        this(out, false);
    }

    public PrintStream(OutputStream out, boolean autoFlush) {
        this(out, autoFlush, Charset.defaultCharset());
    }

    public PrintStream(OutputStream out, boolean autoFlush, String encoding) throws UnsupportedEncodingException {
        this(Objects.requireNonNull(out, "Null output stream"), autoFlush,
                rontolisp.internal.Support.charset(Objects.requireNonNull(encoding, "charsetName")));
    }

    public PrintStream(OutputStream out, boolean autoFlush, Charset charset) {
        super(Objects.requireNonNull(out, "Null output stream"));
        this.autoFlush = autoFlush;
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    // A standard stream.
    PrintStream() {
        super(null);
        this.autoFlush = true;
        this.charset = Charset.defaultCharset();
    }

    public Charset charset() {
        return charset;
    }

    void text(String s, boolean line) {
        try {
            if (out == null) {
                throw new IOException("Stream closed");
            }
            String printed = line ? s + "\n" : s;
            if (!printed.isEmpty()) {
                byte[] encoded = charset.bytes(printed);
                charset = charset.writing();
                out.write(encoded, 0, encoded.length);
            }
            if (autoFlush && (line || s.indexOf('\n') >= 0)) {
                out.flush();
            }
        }
        catch (IOException e) {
            trouble = true;
        }
    }

    public void write(int b) {
        try {
            if (out == null) {
                throw new IOException("Stream closed");
            }
            out.write(b);
            if (autoFlush && b == '\n') {
                out.flush();
            }
        }
        catch (IOException e) {
            trouble = true;
        }
    }

    public void write(byte[] buf, int off, int len) {
        try {
            if (out == null) {
                throw new IOException("Stream closed");
            }
            out.write(buf, off, len);
            if (autoFlush) {
                out.flush();
            }
        }
        catch (IOException e) {
            trouble = true;
        }
    }

    public void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }

    public void writeBytes(byte[] buf) {
        write(buf, 0, buf.length);
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
        if (closing) {
            return;
        }
        closing = true;
        try {
            if (out != null) {
                out.close();
            }
        }
        catch (IOException e) {
            trouble = true;
        }
        out = null;
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

    public PrintStream append(CharSequence csq) {
        text(String.valueOf(csq), false);
        return this;
    }

    public PrintStream append(CharSequence csq, int start, int end) {
        return append(String.valueOf(csq).subSequence(start, end));
    }

    public PrintStream append(char c) {
        text(String.valueOf(c), false);
        return this;
    }

    public PrintStream printf(String format, Object... args) {
        return format(format, args);
    }

    // A locale changes nothing: each one there is writes as the root does.
    public PrintStream printf(java.util.Locale l, String format, Object... args) {
        return format(format, args);
    }

    public PrintStream format(java.util.Locale l, String format, Object... args) {
        return format(format, args);
    }

    public PrintStream format(String format, Object... args) {
        text(String.format(format, args), false);
        return this;
    }
}
