// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

public class ByteArrayInputStream extends InputStream {

    protected byte[] buf;

    protected int pos;

    protected int mark;

    protected int count;

    public ByteArrayInputStream(byte[] buf) {
        this.buf = buf;
        this.count = buf.length;
    }

    public ByteArrayInputStream(byte[] buf, int offset, int length) {
        this.buf = buf;
        this.pos = offset;
        this.mark = offset;
        this.count = offset + length < buf.length ? offset + length : buf.length;
    }

    public int read() {
        return pos < count ? buf[pos++] & 0xff : -1;
    }

    // At the end this answers -1 even for no byte asked.
    public int read(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if (pos >= count) {
            return -1;
        }
        int n = count - pos < len ? count - pos : len;
        if (n <= 0) {
            return 0;
        }
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }

    public byte[] readAllBytes() {
        byte[] rest = new byte[count > pos ? count - pos : 0];
        System.arraycopy(buf, pos, rest, 0, rest.length);
        pos = count;
        return rest;
    }

    public int readNBytes(byte[] b, int off, int len) {
        int n = read(b, off, len);
        return n == -1 ? 0 : n;
    }

    public long skip(long n) {
        long left = count - pos;
        long skipped = n < left ? (n < 0 ? 0 : n) : left;
        pos += (int) skipped;
        return skipped;
    }

    public int available() {
        return count - pos;
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) {
        mark = pos;
    }

    public void reset() {
        pos = mark;
    }

    public void close() throws IOException {
    }

    public long transferTo(OutputStream out) throws IOException {
        int length = count - pos;
        out.write(buf, pos, length);
        pos = count;
        return length;
    }
}
