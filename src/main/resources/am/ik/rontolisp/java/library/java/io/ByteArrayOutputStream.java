// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.nio.charset.Charset;
import java.util.Objects;

public class ByteArrayOutputStream extends OutputStream {

    protected byte[] buf;

    protected int count;

    public ByteArrayOutputStream() {
        this(32);
    }

    public ByteArrayOutputStream(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Negative initial size: " + size);
        }
        buf = new byte[size];
    }

    private void room(int more) {
        if (count + more > buf.length) {
            int doubled = buf.length * 2;
            byte[] grown = new byte[doubled > count + more ? doubled : count + more];
            System.arraycopy(buf, 0, grown, 0, count);
            buf = grown;
        }
    }

    public void write(int b) {
        room(1);
        buf[count++] = (byte) b;
    }

    public void write(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        room(len);
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    public void writeBytes(byte[] b) {
        write(b, 0, b.length);
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(buf, 0, count);
    }

    public void reset() {
        count = 0;
    }

    public byte[] toByteArray() {
        byte[] copy = new byte[count];
        System.arraycopy(buf, 0, copy, 0, count);
        return copy;
    }

    public int size() {
        return count;
    }

    public String toString() {
        return new String(buf, 0, count);
    }

    public String toString(String charsetName) throws UnsupportedEncodingException {
        return new String(buf, 0, count, charsetName);
    }

    public String toString(Charset charset) {
        return new String(buf, 0, count, charset);
    }

    public void close() throws IOException {
    }
}
