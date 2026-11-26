// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

public class StringWriter extends Writer {

    private final StringBuffer text;

    public StringWriter() {
        text = new StringBuffer();
    }

    public StringWriter(int initialSize) {
        if (initialSize < 0) {
            throw new IllegalArgumentException("Negative buffer size");
        }
        text = new StringBuffer();
    }

    public void write(int c) {
        text.append((char) c);
    }

    public void write(char[] cbuf, int off, int len) {
        text.append(new String(cbuf, off, len));
    }

    public void write(String str) {
        text.append(str);
    }

    public void write(String str, int off, int len) {
        text.append(str.substring(off, off + len));
    }

    public StringWriter append(CharSequence csq) {
        write(String.valueOf(csq));
        return this;
    }

    public StringWriter append(CharSequence csq, int start, int end) {
        write(String.valueOf(csq).substring(start, end));
        return this;
    }

    public StringWriter append(char c) {
        write(c);
        return this;
    }

    public String toString() {
        return text.toString();
    }

    public StringBuffer getBuffer() {
        return text;
    }

    public void flush() {
    }

    public void close() throws IOException {
    }
}
