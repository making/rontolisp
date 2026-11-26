// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;

// What DataOutputStream writes, read back: high byte first, a string as its two bytes
// of length and its UTF-16 units in one to three bytes each.
public class DataInputStream extends FilterInputStream implements DataInput {

    public DataInputStream(InputStream in) {
        super(in);
    }

    public final int read(byte[] b) throws IOException {
        return in.read(b, 0, b.length);
    }

    public final int read(byte[] b, int off, int len) throws IOException {
        return in.read(b, off, len);
    }

    public final void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    public final void readFully(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        int count = 0;
        while (count < len) {
            int n = in.read(b, off + count, len - count);
            if (n < 0) {
                throw new EOFException();
            }
            count += n;
        }
    }

    public final int skipBytes(int n) throws IOException {
        int total = 0;
        while (total < n) {
            int skipped = (int) in.skip(n - total);
            if (skipped <= 0) {
                break;
            }
            total += skipped;
        }
        return total;
    }

    private int next() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    public final boolean readBoolean() throws IOException {
        return next() != 0;
    }

    public final byte readByte() throws IOException {
        return (byte) next();
    }

    public final int readUnsignedByte() throws IOException {
        return next();
    }

    public final short readShort() throws IOException {
        int high = next();
        return (short) ((high << 8) | next());
    }

    public final int readUnsignedShort() throws IOException {
        int high = next();
        return (high << 8) | next();
    }

    public final char readChar() throws IOException {
        int high = next();
        return (char) ((high << 8) | next());
    }

    public final int readInt() throws IOException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | next();
        }
        return value;
    }

    public final long readLong() throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | next();
        }
        return value;
    }

    public final float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public final double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    // A line of bytes, each a character; the line's end is \n, \r or \r\n.
    public final String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int c = in.read();
        if (c < 0) {
            return null;
        }
        while (c >= 0 && c != '\n' && c != '\r') {
            line.append((char) c);
            c = in.read();
        }
        if (c == '\r' && in.markSupported()) {
            in.mark(1);
            if (in.read() != '\n') {
                in.reset();
            }
        }
        return line.toString();
    }

    public final String readUTF() throws IOException {
        return readUTF(this);
    }

    public static final String readUTF(DataInput in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] encoded = new byte[length];
        in.readFully(encoded, 0, length);
        char[] units = new char[length];
        int count = 0;
        int at = 0;
        while (at < length) {
            int first = encoded[at] & 0xff;
            int size = first < 0x80 ? 1 : (first & 0xe0) == 0xc0 ? 2 : (first & 0xf0) == 0xe0 ? 3 : 0;
            if (size == 0) {
                throw new UTFDataFormatException("malformed input around byte " + at);
            }
            if (at + size > length) {
                throw new UTFDataFormatException("malformed input: partial character at end");
            }
            int unit = size == 1 ? first : size == 2 ? first & 0x1f : first & 0x0f;
            for (int i = 1; i < size; i++) {
                int more = encoded[at + i] & 0xff;
                if ((more & 0xc0) != 0x80) {
                    throw new UTFDataFormatException("malformed input around byte " + (at + i));
                }
                unit = (unit << 6) | (more & 0x3f);
            }
            units[count++] = (char) unit;
            at += size;
        }
        return new String(units, 0, count);
    }
}
