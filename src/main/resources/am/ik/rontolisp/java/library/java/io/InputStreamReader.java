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

    private final Charset charset;

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
        return in == null ? null : rontolisp.internal.Support.historicalName(charset);
    }

    private void open() throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
    }

    // How many bytes at the end of bytes[0, total) start a UTF-8 character and do not
    // finish it.
    private int unfinished(int total) {
        if (!rontolisp.internal.Support.isUtf8(charset)) {
            return 0;
        }
        for (int back = 1; back <= 3 && back <= total; back++) {
            int b = bytes[total - back] & 0xff;
            if (b >= 0xc0) {
                int size = b >= 0xf0 ? 4 : b >= 0xe0 ? 3 : 2;
                return size > back ? back : 0;
            }
            if (b < 0x80) {
                return 0;
            }
        }
        return 0;
    }

    // Decodes the next bytes; false at the end.
    private boolean fill() throws IOException {
        while (!ended) {
            int n = in.read(bytes, held, bytes.length - held);
            int total = held + (n < 0 ? 0 : n);
            int tail = n < 0 ? 0 : unfinished(total);
            ended = n < 0;
            if (total - tail > 0) {
                units = new String(bytes, 0, total - tail, charset).toCharArray();
                next = 0;
                System.arraycopy(bytes, total - tail, bytes, 0, tail);
                held = tail;
                return true;
            }
            held = total;
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
