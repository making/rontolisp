// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A reader that takes characters back: they are held ahead of the reader under it, the
// one taken back last read first.
public class PushbackReader extends FilterReader {

    // the characters taken back are buf[pos..]
    private char[] buf;

    private int pos;

    public PushbackReader(Reader in) {
        this(in, 1);
    }

    public PushbackReader(Reader in, int size) {
        super(in);
        if (size <= 0) {
            throw new IllegalArgumentException("size <= 0");
        }
        buf = new char[size];
        pos = size;
    }

    private void open() throws IOException {
        if (buf == null) {
            throw new IOException("Stream closed");
        }
    }

    public int read() throws IOException {
        open();
        return pos < buf.length ? buf[pos++] : in.read();
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return 0;
        }
        int held = buf.length - pos < len ? buf.length - pos : len;
        System.arraycopy(buf, pos, cbuf, off, held);
        pos += held;
        if (held == len) {
            return held;
        }
        int n = in.read(cbuf, off + held, len - held);
        return n < 0 ? held == 0 ? -1 : held : held + n;
    }

    public void unread(int c) throws IOException {
        open();
        if (pos == 0) {
            throw new IOException("Pushback buffer overflow");
        }
        buf[--pos] = (char) c;
    }

    public void unread(char[] cbuf, int off, int len) throws IOException {
        open();
        if (len > pos) {
            throw new IOException("Pushback buffer overflow");
        }
        pos -= len;
        System.arraycopy(cbuf, off, buf, pos, len);
    }

    public void unread(char[] cbuf) throws IOException {
        unread(cbuf, 0, cbuf.length);
    }

    public boolean ready() throws IOException {
        open();
        return pos < buf.length || in.ready();
    }

    public void mark(int readAheadLimit) throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public boolean markSupported() {
        return false;
    }

    public void close() throws IOException {
        in.close();
        buf = null;
    }

    public long skip(long n) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("skip value is negative");
        }
        open();
        long held = buf.length - pos < n ? buf.length - pos : n;
        pos += (int) held;
        return held < n ? held + in.skip(n - held) : held;
    }
}
