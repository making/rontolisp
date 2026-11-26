// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

public class BufferedWriter extends Writer {

    private Writer out;

    private char[] chars;

    private int count;

    public BufferedWriter(Writer out) {
        this(out, 8192);
    }

    public BufferedWriter(Writer out, int sz) {
        if (sz <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        this.out = Objects.requireNonNull(out);
        chars = new char[sz];
    }

    private void open() throws IOException {
        if (out == null) {
            throw new IOException("Stream closed");
        }
    }

    private void empty() throws IOException {
        if (count > 0) {
            out.write(chars, 0, count);
            count = 0;
        }
    }

    public void write(int c) throws IOException {
        open();
        if (count >= chars.length) {
            empty();
        }
        chars[count++] = (char) c;
    }

    // What is as long as the buffer goes straight to the writer under it.
    public void write(char[] cbuf, int off, int len) throws IOException {
        open();
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len >= chars.length) {
            empty();
            out.write(cbuf, off, len);
            return;
        }
        int at = off;
        int left = len;
        while (left > 0) {
            int n = chars.length - count < left ? chars.length - count : left;
            System.arraycopy(cbuf, at, chars, count, n);
            count += n;
            at += n;
            left -= n;
            if (count >= chars.length) {
                empty();
            }
        }
    }

    public void write(String s, int off, int len) throws IOException {
        open();
        char[] units = s.substring(off, off + len).toCharArray();
        int at = 0;
        int left = units.length;
        while (left > 0) {
            int n = chars.length - count < left ? chars.length - count : left;
            System.arraycopy(units, at, chars, count, n);
            count += n;
            at += n;
            left -= n;
            if (count >= chars.length) {
                empty();
            }
        }
    }

    public void newLine() throws IOException {
        write("\n");
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
            empty();
        }
        finally {
            Writer under = out;
            out = null;
            chars = null;
            under.close();
        }
    }
}
