// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// The characters read ahead are cb[next, end); a mark keeps the characters read since
// it at the start of cb.
public class BufferedReader extends Reader {

    private Reader in;

    private char[] cb;

    private int next;

    private int end;

    // where the mark is in cb; -1 with none, -2 once its limit was passed
    private int marked = -1;

    private int limit;

    // whether a line ended in \r, so that a \n right after it belongs to that end
    private boolean skipLF;

    private boolean markedSkipLF;

    public BufferedReader(Reader in) {
        this(in, 8192);
    }

    public BufferedReader(Reader in, int sz) {
        if (sz <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        this.in = Objects.requireNonNull(in);
        cb = new char[sz];
    }

    private void open() throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
    }

    private void fill() throws IOException {
        if (marked < 0) {
            next = 0;
        }
        else if (next - marked >= limit) {
            marked = -2;
            next = 0;
        }
        else {
            int kept = next - marked;
            if (marked > 0) {
                System.arraycopy(cb, marked, cb, 0, kept);
            }
            else if (kept == cb.length) {
                int size = kept * 2 > limit ? limit : kept * 2;
                char[] grown = new char[size > kept ? size : kept + 1];
                System.arraycopy(cb, 0, grown, 0, kept);
                cb = grown;
            }
            marked = 0;
            next = kept;
        }
        end = next;
        int n;
        do {
            n = in.read(cb, next, cb.length - next);
        }
        while (n == 0);
        if (n > 0) {
            end = next + n;
        }
    }

    // Whether a character is at hand after a read ahead; a \n ending a \r line is
    // dropped first.
    private boolean more() throws IOException {
        while (true) {
            if (next >= end) {
                fill();
                if (next >= end) {
                    return false;
                }
            }
            if (!skipLF) {
                return true;
            }
            skipLF = false;
            if (cb[next] != '\n') {
                return true;
            }
            next++;
        }
    }

    public int read() throws IOException {
        open();
        return more() ? cb[next++] : -1;
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return 0;
        }
        int total = 0;
        while (total < len) {
            if (next >= end && total > 0 && !in.ready()) {
                break;
            }
            if (!more()) {
                return total == 0 ? -1 : total;
            }
            int n = end - next < len - total ? end - next : len - total;
            System.arraycopy(cb, next, cbuf, off + total, n);
            next += n;
            total += n;
        }
        return total;
    }

    public String readLine() throws IOException {
        open();
        StringBuilder line = null;
        while (true) {
            if (!more()) {
                return line == null ? null : line.toString();
            }
            int start = next;
            while (next < end && cb[next] != '\n' && cb[next] != '\r') {
                next++;
            }
            String piece = new String(cb, start, next - start);
            if (next < end) {
                skipLF = cb[next] == '\r';
                next++;
                return line == null ? piece : line.append(piece).toString();
            }
            if (line == null) {
                line = new StringBuilder();
            }
            line.append(piece);
        }
    }

    public long skip(long n) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("skip value is negative");
        }
        open();
        long left = n;
        while (left > 0 && more()) {
            long step = end - next < left ? end - next : left;
            next += (int) step;
            left -= step;
        }
        return n - left;
    }

    public boolean ready() throws IOException {
        open();
        if (skipLF && next >= end && in.ready()) {
            fill();
        }
        if (skipLF && next < end) {
            if (cb[next] == '\n') {
                next++;
            }
            skipLF = false;
        }
        return next < end || in.ready();
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readAheadLimit) throws IOException {
        if (readAheadLimit < 0) {
            throw new IllegalArgumentException("Read-ahead limit < 0");
        }
        open();
        limit = readAheadLimit;
        marked = next;
        markedSkipLF = skipLF;
    }

    public void reset() throws IOException {
        open();
        if (marked < 0) {
            throw new IOException(marked == -2 ? "Mark invalid" : "Stream not marked");
        }
        next = marked;
        skipLF = markedSkipLF;
    }

    public void close() throws IOException {
        Reader under = in;
        if (under != null) {
            in = null;
            cb = null;
            under.close();
        }
    }
}
