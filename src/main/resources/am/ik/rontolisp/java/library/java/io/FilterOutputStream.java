// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

public class FilterOutputStream extends OutputStream {

    protected OutputStream out;

    private boolean closed;

    public FilterOutputStream(OutputStream out) {
        this.out = out;
    }

    public void write(int b) throws IOException {
        out.write(b);
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    // A byte at a time through write(int), which is what a subclass overrides.
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        for (int i = 0; i < len; i++) {
            write(b[off + i]);
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        }
        finally {
            out.close();
        }
    }
}
