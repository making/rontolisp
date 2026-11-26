// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

// Every value goes out with its high byte first. A string of writeUTF is two bytes of
// length, then each UTF-16 unit in one to three bytes: U+0000 in two, a unit past U+07FF
// in three, so a character outside the BMP is its two surrogates, six bytes.
public class DataOutputStream extends FilterOutputStream implements DataOutput {

    protected int written;

    private final byte[] scratch = new byte[8];

    public DataOutputStream(OutputStream out) {
        super(out);
    }

    private void counted(int n) {
        int total = written + n;
        written = total < 0 ? Integer.MAX_VALUE : total;
    }

    public void write(int b) throws IOException {
        out.write(b);
        counted(1);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        counted(len);
    }

    public void flush() throws IOException {
        out.flush();
    }

    public final void writeBoolean(boolean v) throws IOException {
        out.write(v ? 1 : 0);
        counted(1);
    }

    public final void writeByte(int v) throws IOException {
        out.write(v);
        counted(1);
    }

    public final void writeShort(int v) throws IOException {
        scratch[0] = (byte) (v >>> 8);
        scratch[1] = (byte) v;
        out.write(scratch, 0, 2);
        counted(2);
    }

    public final void writeChar(int v) throws IOException {
        writeShort(v);
    }

    public final void writeInt(int v) throws IOException {
        scratch[0] = (byte) (v >>> 24);
        scratch[1] = (byte) (v >>> 16);
        scratch[2] = (byte) (v >>> 8);
        scratch[3] = (byte) v;
        out.write(scratch, 0, 4);
        counted(4);
    }

    public final void writeLong(long v) throws IOException {
        for (int i = 0; i < 8; i++) {
            scratch[i] = (byte) (v >>> (56 - 8 * i));
        }
        out.write(scratch, 0, 8);
        counted(8);
    }

    public final void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public final void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public final void writeBytes(String s) throws IOException {
        char[] units = s.toCharArray();
        byte[] low = new byte[units.length];
        for (int i = 0; i < units.length; i++) {
            low[i] = (byte) units[i];
        }
        out.write(low, 0, low.length);
        counted(low.length);
    }

    public final void writeChars(String s) throws IOException {
        char[] units = s.toCharArray();
        byte[] pairs = new byte[units.length * 2];
        for (int i = 0; i < units.length; i++) {
            pairs[2 * i] = (byte) (units[i] >>> 8);
            pairs[2 * i + 1] = (byte) units[i];
        }
        out.write(pairs, 0, pairs.length);
        counted(pairs.length);
    }

    public final void writeUTF(String s) throws IOException {
        char[] units = s.toCharArray();
        int length = 0;
        for (int i = 0; i < units.length; i++) {
            int c = units[i];
            length += c >= 1 && c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
        }
        if (length > 65535) {
            throw new UTFDataFormatException("encoded string too long: " + length + " bytes");
        }
        byte[] encoded = new byte[length + 2];
        encoded[0] = (byte) (length >>> 8);
        encoded[1] = (byte) length;
        int at = 2;
        for (int i = 0; i < units.length; i++) {
            int c = units[i];
            if (c >= 1 && c <= 0x7f) {
                encoded[at++] = (byte) c;
            }
            else if (c <= 0x7ff) {
                encoded[at++] = (byte) (0xc0 | (c >> 6));
                encoded[at++] = (byte) (0x80 | (c & 0x3f));
            }
            else {
                encoded[at++] = (byte) (0xe0 | (c >> 12));
                encoded[at++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                encoded[at++] = (byte) (0x80 | (c & 0x3f));
            }
        }
        out.write(encoded, 0, encoded.length);
        counted(encoded.length);
    }

    public final int size() {
        return written;
    }
}
