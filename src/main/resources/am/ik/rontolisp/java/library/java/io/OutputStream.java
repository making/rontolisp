// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A subclass gives write(int); the array forms are written over it.
public abstract class OutputStream implements Closeable, Flushable {

    public OutputStream() {
    }

    public abstract void write(int b) throws IOException;

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        for (int i = 0; i < len; i++) {
            write(b[off + i]);
        }
    }

    public void flush() throws IOException {
    }

    public void close() throws IOException {
    }
}
