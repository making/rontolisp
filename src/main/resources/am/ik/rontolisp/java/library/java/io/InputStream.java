// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A subclass gives read(); everything else here is written over it.
public abstract class InputStream implements Closeable {

    public InputStream() {
    }

    // A stream at its end from the start, until it is closed.
    public static InputStream nullInputStream() {
        return new InputStream() {
            private boolean closed;

            private void open() throws IOException {
                if (closed) {
                    throw new IOException("Stream closed");
                }
            }

            public int available() throws IOException {
                open();
                return 0;
            }

            public int read() throws IOException {
                open();
                return -1;
            }

            public int read(byte[] b, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                if (len == 0) {
                    return 0;
                }
                open();
                return -1;
            }

            public byte[] readNBytes(int len) throws IOException {
                if (len < 0) {
                    throw new IllegalArgumentException("len < 0");
                }
                open();
                return new byte[0];
            }

            public int readNBytes(byte[] b, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                open();
                return 0;
            }

            public long skip(long n) throws IOException {
                open();
                return 0;
            }

            public void skipNBytes(long n) throws IOException {
                open();
                if (n > 0) {
                    throw new EOFException();
                }
            }

            public long transferTo(OutputStream out) throws IOException {
                Objects.requireNonNull(out);
                open();
                return 0;
            }

            public void close() {
                closed = true;
            }
        };
    }

    public abstract int read() throws IOException;

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    // The first byte's exception is the caller's; one after it ends the read with what
    // was read.
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        int first = read();
        if (first == -1) {
            return -1;
        }
        b[off] = (byte) first;
        int count = 1;
        try {
            while (count < len) {
                int next = read();
                if (next == -1) {
                    break;
                }
                b[off + count++] = (byte) next;
            }
        }
        catch (IOException e) {
            return count;
        }
        return count;
    }

    public byte[] readAllBytes() throws IOException {
        return readNBytes(Integer.MAX_VALUE);
    }

    public byte[] readNBytes(int len) throws IOException {
        if (len < 0) {
            throw new IllegalArgumentException("len < 0");
        }
        byte[] held = new byte[len < 8192 ? len : 8192];
        int count = 0;
        while (count < len) {
            if (count == held.length) {
                byte[] grown = new byte[held.length * 2 > len || held.length * 2 < 0 ? len : held.length * 2];
                System.arraycopy(held, 0, grown, 0, count);
                held = grown;
            }
            int n = read(held, count, held.length - count);
            if (n < 0) {
                break;
            }
            count += n;
        }
        if (count == held.length) {
            return held;
        }
        byte[] all = new byte[count];
        System.arraycopy(held, 0, all, 0, count);
        return all;
    }

    public int readNBytes(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        int count = 0;
        while (count < len) {
            int n = read(b, off + count, len - count);
            if (n < 0) {
                break;
            }
            count += n;
        }
        return count;
    }

    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long left = n;
        byte[] dropped = new byte[left < 2048 ? (int) left : 2048];
        while (left > 0) {
            int read = read(dropped, 0, left < dropped.length ? (int) left : dropped.length);
            if (read < 0) {
                break;
            }
            left -= read;
        }
        return n - left;
    }

    public void skipNBytes(long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = skip(left);
            if (skipped == 0) {
                if (read() == -1) {
                    throw new EOFException();
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    public int available() throws IOException {
        return 0;
    }

    public void close() throws IOException {
    }

    public void mark(int readlimit) {
    }

    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public boolean markSupported() {
        return false;
    }

    public long transferTo(OutputStream out) throws IOException {
        Objects.requireNonNull(out, "out");
        long moved = 0;
        byte[] chunk = new byte[8192];
        int n;
        while ((n = read(chunk, 0, chunk.length)) >= 0) {
            out.write(chunk, 0, n);
            moved += n;
        }
        return moved;
    }
}
