// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A subclass gives write(int); the array forms are written over it.
public abstract class OutputStream implements Closeable, Flushable {

    public OutputStream() {
    }

    // A stream that drops what it is given, until it is closed.
    public static OutputStream nullOutputStream() {
        return new OutputStream() {
            private boolean closed;

            private void open() throws IOException {
                if (closed) {
                    throw new IOException("Stream closed");
                }
            }

            public void write(int b) throws IOException {
                open();
            }

            public void write(byte[] b, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                open();
            }

            public void close() {
                closed = true;
            }
        };
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
