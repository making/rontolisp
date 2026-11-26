// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.nio.charset.Charset;
import java.util.Objects;

// Bytes are read a buffer at a time and decoded whole; a character whose bytes the
// buffer ends inside waits for the next read. The standard input hands its characters
// over as they are.
public class InputStreamReader extends Reader {

    private InputStream in;

    // the charset of what is still to read: past its start, UTF-16 is one of its orders
    private Charset charset;

    private final StandardInput standard;

    private final byte[] bytes = new byte[8192];

    // the bytes of a character not whole yet, at the start of bytes
    private int held;

    private char[] units = new char[0];

    private int next;

    private boolean ended;

    public InputStreamReader(InputStream in) {
        this(in, Charset.defaultCharset());
    }

    public InputStreamReader(InputStream in, String charsetName) throws UnsupportedEncodingException {
        this(in, rontolisp.internal.Support.charset(Objects.requireNonNull(charsetName, "charsetName")));
    }

    public InputStreamReader(InputStream in, Charset charset) {
        this.in = Objects.requireNonNull(in);
        this.charset = Objects.requireNonNull(charset, "charset");
        this.standard = in instanceof StandardInput input ? input : null;
    }

    public String getEncoding() {
        return in == null ? null : charset.historicalName();
    }

    private void open() throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
    }

    // Decodes the next bytes; false at the end.
    private boolean fill() throws IOException {
        while (!ended) {
            int n = in.read(bytes, held, bytes.length - held);
            int total = held + (n < 0 ? 0 : n);
            int tail = n < 0 ? 0 : charset.unfinished(bytes, total);
            ended = n < 0;
            if (total - tail > 0) {
                units = charset.text(bytes, 0, total - tail).toCharArray();
                charset = charset.reading(bytes, total - tail);
                next = 0;
                System.arraycopy(bytes, total - tail, bytes, 0, tail);
                held = tail;
                if (units.length > 0) {
                    return true;
                }
            }
            else {
                held = total;
            }
        }
        return false;
    }

    boolean hasLines() {
        return in != null && standard != null && next >= units.length && standard.whole();
    }

    String line() {
        return standard.line();
    }

    public int read() throws IOException {
        open();
        if (standard != null && next >= units.length) {
            return standard.unit();
        }
        if (next >= units.length && !fill()) {
            return -1;
        }
        return units[next++];
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return 0;
        }
        if (standard != null && next >= units.length) {
            return standard.units(cbuf, off, len);
        }
        if (next >= units.length && !fill()) {
            return -1;
        }
        int n = units.length - next < len ? units.length - next : len;
        if (n > 1 && next + n < units.length && units[next + n - 1] >= 0xd800 && units[next + n - 1] <= 0xdbff) {
            // the two units of a character outside the BMP are read together
            n--;
        }
        System.arraycopy(units, next, cbuf, off, n);
        next += n;
        return n;
    }

    public boolean ready() throws IOException {
        open();
        return next < units.length || in.available() > 0;
    }

    public void close() throws IOException {
        InputStream under = in;
        if (under != null) {
            in = null;
            under.close();
        }
    }
}
