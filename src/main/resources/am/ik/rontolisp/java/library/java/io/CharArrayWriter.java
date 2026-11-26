// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// Characters gathered in an array that grows; closing it changes nothing.
public class CharArrayWriter extends Writer {

    protected char[] buf;

    protected int count;

    public CharArrayWriter() {
        this(32);
    }

    public CharArrayWriter(int initialSize) {
        if (initialSize < 0) {
            throw new IllegalArgumentException("Negative initial size: " + initialSize);
        }
        buf = new char[initialSize];
    }

    private void room(int more) {
        if (count + more > buf.length) {
            char[] grown = new char[buf.length * 2 > count + more ? buf.length * 2 : count + more];
            System.arraycopy(buf, 0, grown, 0, count);
            buf = grown;
        }
    }

    public void write(int c) {
        room(1);
        buf[count++] = (char) c;
    }

    public void write(char[] c, int off, int len) {
        Objects.checkFromIndexSize(off, len, c.length);
        room(len);
        System.arraycopy(c, off, buf, count, len);
        count += len;
    }

    public void write(String str, int off, int len) {
        char[] units = str.substring(off, off + len).toCharArray();
        write(units, 0, units.length);
    }

    public void writeTo(Writer out) throws IOException {
        out.write(buf, 0, count);
    }

    public CharArrayWriter append(CharSequence csq) {
        String text = String.valueOf(csq);
        write(text, 0, text.length());
        return this;
    }

    public CharArrayWriter append(CharSequence csq, int start, int end) {
        return append(String.valueOf(csq).subSequence(start, end));
    }

    public CharArrayWriter append(char c) {
        write(c);
        return this;
    }

    public void reset() {
        count = 0;
    }

    public char[] toCharArray() {
        char[] copy = new char[count];
        System.arraycopy(buf, 0, copy, 0, count);
        return copy;
    }

    public int size() {
        return count;
    }

    public String toString() {
        return new String(buf, 0, count);
    }

    public void flush() {
    }

    public void close() {
    }
}
