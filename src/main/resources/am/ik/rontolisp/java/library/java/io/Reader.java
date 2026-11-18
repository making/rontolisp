// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A subclass gives read(char[], int, int) and close(); a char is a UTF-16 unit.
public abstract class Reader implements Closeable {

    protected Reader() {
    }

    // A reader at its end from the start, until it is closed.
    public static Reader nullReader() {
        return new Reader() {
            private boolean closed;

            private void open() throws IOException {
                if (closed) {
                    throw new IOException("Stream closed");
                }
            }

            public int read() throws IOException {
                open();
                return -1;
            }

            public int read(char[] cbuf, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, cbuf.length);
                open();
                return len == 0 ? 0 : -1;
            }

            public boolean ready() throws IOException {
                open();
                return false;
            }

            public long skip(long n) throws IOException {
                open();
                return 0;
            }

            public long transferTo(Writer out) throws IOException {
                Objects.requireNonNull(out);
                open();
                return 0;
            }

            public void close() {
                closed = true;
            }
        };
    }

    public abstract int read(char[] cbuf, int off, int len) throws IOException;

    // Whether the host hands this reader a line whole: a reader standing directly on
    // the standard input, with nothing of it read ahead.
    boolean hasLines() {
        return false;
    }

    // That line without the \n that ends it, null at the end of the input.
    String line() throws IOException {
        return null;
    }

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
