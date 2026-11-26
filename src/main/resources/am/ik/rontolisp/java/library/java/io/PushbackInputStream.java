// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A stream that takes bytes back: they are held ahead of the stream under it, the one
// taken back last read first.
public class PushbackInputStream extends FilterInputStream {

    // the bytes taken back are buf[pos..]
    protected byte[] buf;

    protected int pos;

    public PushbackInputStream(InputStream in) {
        this(in, 1);
    }

    public PushbackInputStream(InputStream in, int size) {
        super(in);
        if (size <= 0) {
            throw new IllegalArgumentException("size <= 0");
        }
        buf = new byte[size];
        pos = size;
    }

    private void open() throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
    }

    public int read() throws IOException {
        open();
        return pos < buf.length ? buf[pos++] & 0xff : in.read();
    }

    public int read(byte[] b, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        int held = buf.length - pos < len ? buf.length - pos : len;
        System.arraycopy(buf, pos, b, off, held);
        pos += held;
        if (held == len) {
            return held;
        }
        int n = in.read(b, off + held, len - held);
        return n < 0 ? held == 0 ? -1 : held : held + n;
    }

    public void unread(int b) throws IOException {
        open();
        if (pos == 0) {
            throw new IOException("Push back buffer is full");
        }
        buf[--pos] = (byte) b;
    }

    public void unread(byte[] b, int off, int len) throws IOException {
        open();
        if (len > pos) {
            throw new IOException("Push back buffer is full");
        }
        pos -= len;
        System.arraycopy(b, off, buf, pos, len);
    }

    public void unread(byte[] b) throws IOException {
        unread(b, 0, b.length);
    }

    public int available() throws IOException {
        open();
        int held = buf.length - pos;
        int under = in.available();
        return held > Integer.MAX_VALUE - under ? Integer.MAX_VALUE : held + under;
    }

    public long skip(long n) throws IOException {
        open();
        if (n <= 0) {
            return 0;
        }
        long held = buf.length - pos < n ? buf.length - pos : n;
        pos += (int) held;
        return held < n ? held + in.skip(n - held) : held;
    }

    public boolean markSupported() {
        return false;
    }

    public void mark(int readlimit) {
    }

    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public void close() throws IOException {
        if (in != null) {
            in.close();
            in = null;
            buf = null;
        }
    }
}
