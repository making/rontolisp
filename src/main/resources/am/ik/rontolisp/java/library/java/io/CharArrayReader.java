// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A reader over an array or a range of one; the mark starts at the range's start.
public class CharArrayReader extends Reader {

    protected char[] buf;

    protected int pos;

    protected int markedPos;

    protected int count;

    public CharArrayReader(char[] buf) {
        this.buf = buf;
        this.count = buf.length;
    }

    public CharArrayReader(char[] buf, int offset, int length) {
        if (offset < 0 || offset > buf.length || length < 0 || offset + length < 0) {
            throw new IllegalArgumentException();
        }
        this.buf = buf;
        this.pos = offset;
        this.markedPos = offset;
        this.count = buf.length - offset < length ? buf.length : offset + length;
    }

    private void open() throws IOException {
        if (buf == null) {
            throw new IOException("Stream closed");
        }
    }

    public int read() throws IOException {
        open();
        return pos < count ? buf[pos++] : -1;
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return 0;
        }
        if (pos >= count) {
            return -1;
        }
        int n = count - pos < len ? count - pos : len;
        System.arraycopy(buf, pos, cbuf, off, n);
        pos += n;
        return n;
    }

    public long skip(long n) throws IOException {
        open();
        long step = n < 0 ? 0 : count - pos < n ? count - pos : n;
        pos += (int) step;
        return step;
    }

    public boolean ready() throws IOException {
        open();
        return count - pos > 0;
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        open();
        markedPos = pos;
    }

    public void reset() throws IOException {
        open();
        pos = markedPos;
    }

    public void close() {
        buf = null;
    }
}
