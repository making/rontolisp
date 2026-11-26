// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// A subclass gives write(char[], int, int), flush() and close(); a char is a UTF-16
// unit.
public abstract class Writer implements Appendable, Closeable, Flushable {

    protected Writer() {
    }

    // A writer that drops what it is given, until it is closed.
    public static Writer nullWriter() {
        return new Writer() {
            private boolean closed;

            private void open() throws IOException {
                if (closed) {
                    throw new IOException("Stream closed");
                }
            }

            public Writer append(char c) throws IOException {
                open();
                return this;
            }

            public Writer append(CharSequence csq) throws IOException {
                open();
                return this;
            }

            public Writer append(CharSequence csq, int start, int end) throws IOException {
                open();
                if (csq != null) {
                    Objects.checkFromToIndex(start, end, csq.length());
                }
                return this;
            }

            public void write(int c) throws IOException {
                open();
            }

            public void write(char[] cbuf, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, cbuf.length);
                open();
            }

            public void write(String str) throws IOException {
                Objects.requireNonNull(str);
                open();
            }

            public void write(String str, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, str.length());
                open();
            }

            public void flush() throws IOException {
                open();
            }

            public void close() throws IOException {
                closed = true;
            }
        };
    }

    public abstract void write(char[] cbuf, int off, int len) throws IOException;

    public abstract void flush() throws IOException;

    public abstract void close() throws IOException;

    public void write(int c) throws IOException {
        char[] one = new char[1];
        one[0] = (char) c;
        write(one, 0, 1);
    }

    public void write(char[] cbuf) throws IOException {
        write(cbuf, 0, cbuf.length);
    }

    public void write(String str) throws IOException {
        write(str, 0, str.length());
    }

    public void write(String str, int off, int len) throws IOException {
        char[] units = str.substring(off, off + len).toCharArray();
        write(units, 0, units.length);
    }

    public Writer append(CharSequence csq) throws IOException {
        write(String.valueOf(csq));
        return this;
    }

    public Writer append(CharSequence csq, int start, int end) throws IOException {
        write(String.valueOf(csq).substring(start, end));
        return this;
    }

    public Writer append(char c) throws IOException {
        write(c);
        return this;
    }
}
