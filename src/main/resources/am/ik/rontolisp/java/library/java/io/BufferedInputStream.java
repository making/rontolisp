// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// The bytes read ahead are buf[pos, count); a mark keeps the bytes read since it at the
// start of buf for as long as its limit allows.
public class BufferedInputStream extends FilterInputStream {

    protected byte[] buf;

    protected int count;

    protected int pos;

    protected int markpos = -1;

    protected int marklimit;

    public BufferedInputStream(InputStream in) {
        this(in, 8192);
    }

    public BufferedInputStream(InputStream in, int size) {
        super(in);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = new byte[size];
    }

    private void open() throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
    }

    // Reads ahead once; what the mark still needs moves to the start first.
    private void fill() throws IOException {
        if (markpos < 0) {
            pos = 0;
        }
        else if (pos - markpos >= marklimit) {
            markpos = -1;
            pos = 0;
        }
        else {
            int kept = pos - markpos;
            if (markpos > 0) {
                System.arraycopy(buf, markpos, buf, 0, kept);
            }
            else if (kept == buf.length) {
                int size = kept * 2 > marklimit ? marklimit : kept * 2;
                byte[] grown = new byte[size > kept ? size : kept + 1];
                System.arraycopy(buf, 0, grown, 0, kept);
                buf = grown;
            }
            markpos = 0;
            pos = kept;
        }
        count = pos;
        int n = in.read(buf, pos, buf.length - pos);
        if (n > 0) {
            count = pos + n;
        }
    }

    public int read() throws IOException {
        open();
        if (pos >= count) {
            fill();
            if (pos >= count) {
                return -1;
            }
        }
        return buf[pos++] & 0xff;
    }

    // Goes on reading while the stream under it has bytes at hand.
    public int read(byte[] b, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        int total = 0;
        while (true) {
            if (pos >= count) {
                if (len - total >= buf.length && markpos < 0) {
                    // a request as long as the buffer goes past it
                    int direct = in.read(b, off + total, len - total);
                    return direct < 0 ? (total == 0 ? -1 : total) : total + direct;
                }
                fill();
                if (pos >= count) {
                    return total == 0 ? -1 : total;
                }
            }
            int n = count - pos < len - total ? count - pos : len - total;
            System.arraycopy(buf, pos, b, off + total, n);
            pos += n;
            total += n;
            if (total >= len || in.available() <= 0) {
                return total;
            }
        }
    }

    public long skip(long n) throws IOException {
        open();
        if (n <= 0) {
            return 0;
        }
        if (pos >= count) {
            if (markpos < 0) {
                return in.skip(n);
            }
            fill();
            if (pos >= count) {
                return 0;
            }
        }
        long skipped = count - pos < n ? count - pos : n;
        pos += (int) skipped;
        return skipped;
    }

    public int available() throws IOException {
        open();
        int held = count - pos;
        int under = in.available();
        return held > Integer.MAX_VALUE - under ? Integer.MAX_VALUE : held + under;
    }

    public void mark(int readlimit) {
        marklimit = readlimit;
        markpos = pos;
    }

    public void reset() throws IOException {
        open();
        if (markpos < 0) {
            throw new IOException("Resetting to invalid mark");
        }
        pos = markpos;
    }

    public boolean markSupported() {
        return true;
    }

    public void close() throws IOException {
        InputStream under = in;
        if (under != null) {
            in = null;
            under.close();
        }
    }
}
