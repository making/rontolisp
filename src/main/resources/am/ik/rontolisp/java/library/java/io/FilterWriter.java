// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

public abstract class FilterWriter extends Writer {

    protected Writer out;

    protected FilterWriter(Writer out) {
        this.out = Objects.requireNonNull(out);
    }

    public void write(int c) throws IOException {
        out.write(c);
    }

    public void write(char[] cbuf, int off, int len) throws IOException {
        out.write(cbuf, off, len);
    }

    public void write(String str, int off, int len) throws IOException {
        out.write(str, off, len);
    }

    public void flush() throws IOException {
        out.flush();
    }

    public void close() throws IOException {
        out.close();
    }
}
