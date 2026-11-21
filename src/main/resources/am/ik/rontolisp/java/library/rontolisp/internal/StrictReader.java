// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.util.Objects;

// The characters of a stream of bytes decoded strictly, as a JDK's readers of Files and of
// a Scanner of a File are: bytes that are no text in the charset are refused, with how many
// they are. The read that meets them throws, and the characters it decoded before them are
// lost: a read of n characters fails where at most n come before them (a JDK's answers to
// a Scanner and a BufferedReader over such bytes at every offset around their reads).
public final class StrictReader extends Reader {

    private InputStream in;

    // the charset of what is still to read: past its start, UTF-16 is one of its orders
    private Charset charset;

    private final byte[] bytes = new byte[8192];

    // the bytes of a character not whole yet, at the start of bytes
    private int held;

    private char[] units = new char[0];

    private int next;

    private boolean ended;

    // the refusal of the bytes after the units, which are never read
    private IOException refused;

    public StrictReader(InputStream in, Charset charset) {
        this.in = Objects.requireNonNull(in);
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    // Decodes the next bytes behind the units not read yet, up to any that are no text.
    private void fill() throws IOException {
        int n = in.read(bytes, held, bytes.length - held);
        int total = held + (n < 0 ? 0 : n);
        int tail = n < 0 ? 0 : charset.unfinished(bytes, total);
        ended = n < 0;
        int whole = total - tail;
        long bad = charset.malformed(bytes, 0, whole, false);
        int good = bad < 0 ? whole : (int) (bad >> 3);
        if (good > 0) {
            char[] decoded = charset.text(bytes, 0, good).toCharArray();
            charset = charset.reading(bytes, good);
            char[] joined = new char[units.length - next + decoded.length];
            System.arraycopy(units, next, joined, 0, units.length - next);
            System.arraycopy(decoded, 0, joined, units.length - next, decoded.length);
            units = joined;
            next = 0;
        }
        if (bad >= 0) {
            refused = new MalformedInputException((int) (bad & 7));
            return;
        }
        System.arraycopy(bytes, whole, bytes, 0, tail);
        held = tail;
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return 0;
        }
        while (units.length - next <= len && refused == null && !ended) {
            fill();
        }
        int ready = units.length - next;
        if (refused != null && ready <= len) {
            throw refused;
        }
        if (ready == 0) {
            return -1;
        }
        int n = ready < len ? ready : len;
        if (n < ready && n > 1 && units[next + n - 1] >= 0xd800 && units[next + n - 1] <= 0xdbff) {
            // the two units of a character outside the BMP are read together
            n--;
        }
        System.arraycopy(units, next, cbuf, off, n);
        next += n;
        return n;
    }

    public boolean ready() throws IOException {
        if (in == null) {
            throw new IOException("Stream closed");
        }
        return next < units.length || in.available() > 0;
    }

    public void close() throws IOException {
        InputStream under = in;
        if (under != null) {
            in = null;
            under.close();
        }
    }
}
