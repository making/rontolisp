// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.nio.charset.Charset;
import java.util.Objects;

// The encoded bytes wait in a buffer of 8192 and reach the stream when it is full, at
// flush() and at close(); a high surrogate waits for its low one. The standard output
// takes the characters as they are.
public class OutputStreamWriter extends Writer {

    private OutputStream out;

    // the charset of what is still to write: past its start, UTF-16 writes no mark
    private Charset charset;

    private final StandardOutput standard;

    private final byte[] bytes = new byte[8192];

    private int count;

    // a high surrogate written last, or -1
    private int high = -1;

    public OutputStreamWriter(OutputStream out) {
        this(out, Charset.defaultCharset());
    }

    public OutputStreamWriter(OutputStream out, String charsetName) throws UnsupportedEncodingException {
        this(out, rontolisp.internal.Support.charset(Objects.requireNonNull(charsetName, "charsetName")));
    }

    public OutputStreamWriter(OutputStream out, Charset charset) {
        this.out = Objects.requireNonNull(out);
        this.charset = Objects.requireNonNull(charset, "charset");
        this.standard = out instanceof StandardOutput output ? output : null;
    }

    public String getEncoding() {
        return out == null ? null : charset.historicalName();
    }

    private void open() throws IOException {
        if (out == null) {
            throw new IOException("Stream closed");
        }
    }

    private void encode(String text) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        if (standard != null) {
            standard.text(text);
            return;
        }
        byte[] encoded = charset.bytes(text);
        charset = charset.writing();
        int at = 0;
        while (at < encoded.length) {
            int room = bytes.length - count;
            int n = encoded.length - at;
            if (n > room) {
                // a character's bytes stay together
                n = room;
                while (n > 0 && rontolisp.internal.Support.isUtf8(charset) && (encoded[at + n] & 0xc0) == 0x80) {
                    n--;
                }
            }
            System.arraycopy(encoded, at, bytes, count, n);
            count += n;
            at += n;
            if (at < encoded.length) {
                empty();
            }
        }
    }

    private void empty() throws IOException {
        if (count > 0) {
            out.write(bytes, 0, count);
            count = 0;
        }
    }

    public void write(int c) throws IOException {
        char[] one = new char[1];
        one[0] = (char) c;
        write(one, 0, 1);
    }

    public void write(char[] cbuf, int off, int len) throws IOException {
        open();
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
            encode(new String(joined, 0, n + 1));
        }
        else {
            encode(new String(cbuf, off, n));
        }
        high = waits ? cbuf[end - 1] : -1;
    }

    public void write(String str, int off, int len) throws IOException {
        open();
        // a high surrogate at the end waits for its partner, as one of an array does
        int last = len > 0 ? str.charAt(off + len - 1) : 0;
        if (high >= 0 || (last >= 0xd800 && last <= 0xdbff)) {
            char[] units = str.substring(off, off + len).toCharArray();
            write(units, 0, units.length);
            return;
        }
        encode(str.substring(off, off + len));
    }

    public void flush() throws IOException {
        open();
        empty();
        out.flush();
    }

    public void close() throws IOException {
        if (out == null) {
            return;
        }
        try {
            if (high >= 0) {
                high = -1;
                encode(charset.lone());
            }
            empty();
        }
        finally {
            OutputStream under = out;
            out = null;
            under.close();
        }
    }
}
