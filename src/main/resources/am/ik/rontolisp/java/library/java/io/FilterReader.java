// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

public abstract class FilterReader extends Reader {

    protected Reader in;

    protected FilterReader(Reader in) {
        this.in = Objects.requireNonNull(in);
    }

    public int read() throws IOException {
        return in.read();
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        return in.read(cbuf, off, len);
    }

    public long skip(long n) throws IOException {
        return in.skip(n);
    }

    public boolean ready() throws IOException {
        return in.ready();
    }

    public boolean markSupported() {
        return in.markSupported();
    }

    public void mark(int readAheadLimit) throws IOException {
        in.mark(readAheadLimit);
    }

    public void reset() throws IOException {
        in.reset();
    }

    public void close() throws IOException {
        in.close();
    }
}
