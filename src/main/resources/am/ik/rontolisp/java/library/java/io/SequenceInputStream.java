// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Enumeration;
import java.util.Objects;

// Streams read one after another, each closed where its end is met.
public class SequenceInputStream extends InputStream {

    private final Enumeration<? extends InputStream> rest;

    private InputStream in;

    public SequenceInputStream(Enumeration<? extends InputStream> e) {
        rest = e;
        advance();
    }

    public SequenceInputStream(InputStream s1, InputStream s2) {
        java.util.Vector<InputStream> two = new java.util.Vector<>();
        two.add(s1);
        two.add(s2);
        rest = two.elements();
        advance();
    }

    // The stream read from is the next one; none is left when the enumeration ends.
    private void advance() {
        in = rest.hasMoreElements() ? Objects.requireNonNull(rest.nextElement()) : null;
    }

    private void next() throws IOException {
        if (in != null) {
            in.close();
        }
        advance();
    }

    public int available() throws IOException {
        return in == null ? 0 : in.available();
    }

    public int read() throws IOException {
        while (in != null) {
            int b = in.read();
            if (b != -1) {
                return b;
            }
            next();
        }
        return -1;
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (in == null) {
            return -1;
        }
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        while (in != null) {
            int n = in.read(b, off, len);
            if (n > 0) {
                return n;
            }
            next();
        }
        return -1;
    }

    // Every stream left is closed; an exception of one is thrown once all are.
    public void close() throws IOException {
        IOException first = null;
        while (in != null) {
            try {
                in.close();
            }
            catch (IOException e) {
                if (first == null) {
                    first = e;
                }
                else {
                    first.addSuppressed(e);
                }
            }
            advance();
        }
        if (first != null) {
            throw first;
        }
    }
}
