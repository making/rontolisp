// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.UnmappableCharacterException;
import java.util.Objects;

// Characters written to a stream of bytes encoded strictly, as a JDK's writers of Files
// are: a character the charset cannot write is refused, as malformed (a surrogate with no
// partner) or unmappable, after the bytes of the characters before it are written. A high
// surrogate a write ends in waits for the unit after it.
public final class StrictWriter extends Writer {

    private OutputStream out;

    // the charset of what is still to write: past its start, UTF-16 writes no mark
    private Charset charset;

    // a high surrogate written last, or -1
    private int high = -1;

    public StrictWriter(OutputStream out, Charset charset) {
        this.out = Objects.requireNonNull(out);
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    // What a strict encoder's answer refuses, or null.
    public static CharacterCodingException refusal(long bad) {
        if (bad < 0) {
            return null;
        }
        int count = (int) (bad >> 1 & 3);
        return (bad & 1) != 0 ? new MalformedInputException(count) : new UnmappableCharacterException(count);
    }

    private void encode(String text) throws IOException {
        long bad = charset.unwritable(text, false);
        String written = bad < 0 ? text : text.substring(0, (int) (bad >> 3));
        if (!written.isEmpty()) {
            byte[] encoded = charset.bytes(written);
            charset = charset.writing();
            out.write(encoded, 0, encoded.length);
        }
        if (bad >= 0) {
            throw refusal(bad);
        }
    }

    public void write(char[] cbuf, int off, int len) throws IOException {
        if (out == null) {
            throw new IOException("Stream closed");
        }
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) {
            return;
        }
        int end = off + len;
        boolean waits = cbuf[end - 1] >= 0xd800 && cbuf[end - 1] <= 0xdbff;
        int n = waits ? len - 1 : len;
        if (high >= 0) {
            char[] joined = new char[n + 1];
            joined[0] = (char) high;
            System.arraycopy(cbuf, off, joined, 1, n);
            high = -1;
            encode(new String(joined, 0, n + 1));
        }
        else if (n > 0) {
            encode(new String(cbuf, off, n));
        }
        high = waits ? cbuf[end - 1] : -1;
    }

    public void write(String str, int off, int len) throws IOException {
        char[] units = str.substring(off, off + len).toCharArray();
        write(units, 0, units.length);
    }

    public void flush() throws IOException {
        if (out == null) {
            throw new IOException("Stream closed");
        }
        out.flush();
    }

    public void close() throws IOException {
        if (out == null) {
            return;
        }
        try {
            if (high >= 0) {
                int lone = high;
                high = -1;
                encode(String.valueOf((char) lone));
            }
        }
        finally {
            OutputStream under = out;
            out = null;
            under.close();
        }
    }
}
