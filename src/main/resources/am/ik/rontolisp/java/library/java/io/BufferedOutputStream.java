// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

public class BufferedOutputStream extends FilterOutputStream {

    protected byte[] buf;

    protected int count;

    public BufferedOutputStream(OutputStream out) {
        this(out, 8192);
    }

    public BufferedOutputStream(OutputStream out, int size) {
        super(out);
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = new byte[size];
    }

    private void empty() throws IOException {
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    public void write(int b) throws IOException {
        if (count >= buf.length) {
            empty();
        }
        buf[count++] = (byte) b;
    }

    // What is as long as the buffer goes straight to the stream under it.
    public void write(byte[] b, int off, int len) throws IOException {
        if (len >= buf.length) {
            empty();
            out.write(b, off, len);
            return;
        }
        if (len > buf.length - count) {
            empty();
        }
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    public void flush() throws IOException {
        empty();
        out.flush();
    }
}
