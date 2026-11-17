// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A subclass gives read(char[], int, int) and close(); a char is a UTF-16 unit.
public abstract class Reader implements Closeable {

    protected Reader() {
    }

    public abstract int read(char[] cbuf, int off, int len) throws IOException;

    public abstract void close() throws IOException;

    public int read() throws IOException {
        char[] one = new char[1];
        return read(one, 0, 1) < 0 ? -1 : one[0];
    }

    public int read(char[] cbuf) throws IOException {
        return read(cbuf, 0, cbuf.length);
    }

    public long skip(long n) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("skip value is negative");
        }
        char[] dropped = new char[n < 8192 ? (int) n : 8192];
        long left = n;
        while (left > 0) {
            int read = read(dropped, 0, left < dropped.length ? (int) left : dropped.length);
            if (read < 0) {
                break;
            }
            left -= read;
        }
        return n - left;
    }

    public boolean ready() throws IOException {
        return false;
    }

    public boolean markSupported() {
        return false;
    }

    public void mark(int readAheadLimit) throws IOException {
        throw new IOException("mark() not supported");
    }

    public void reset() throws IOException {
        throw new IOException("reset() not supported");
    }

    public long transferTo(Writer out) throws IOException {
        Objects.requireNonNull(out, "out");
        long moved = 0;
        char[] chunk = new char[8192];
        int n;
        while ((n = read(chunk, 0, chunk.length)) >= 0) {
            out.write(chunk, 0, n);
            moved += n;
        }
        return moved;
    }
}
