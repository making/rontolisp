// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

public class StringReader extends Reader {

    private char[] units;

    private int next;

    private int mark;

    public StringReader(String s) {
        units = s.toCharArray();
    }

    private void open() throws IOException {
        if (units == null) {
            throw new IOException("Stream closed");
        }
    }

    public int read() throws IOException {
        open();
        return next < units.length ? units[next++] : -1;
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return 0;
        }
        if (next >= units.length) {
            return -1;
        }
        int n = units.length - next < len ? units.length - next : len;
        System.arraycopy(units, next, cbuf, off, n);
        next += n;
        return n;
    }

    // A negative count goes back, as far as the start.
    public long skip(long n) throws IOException {
        open();
        long to = next + n;
        int target = to > units.length ? units.length : to < 0 ? 0 : (int) to;
        long skipped = target - next;
        next = target;
        return skipped;
    }

    public boolean ready() throws IOException {
        open();
        return true;
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        if (readAheadLimit < 0) {
            throw new IllegalArgumentException("Read-ahead limit < 0");
        }
        open();
        mark = next;
    }

    public void reset() throws IOException {
        open();
        next = mark;
    }

    public void close() {
        units = null;
    }
}
