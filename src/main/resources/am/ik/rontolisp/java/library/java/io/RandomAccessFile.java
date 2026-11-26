// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.io;

import java.util.Objects;
import rontolisp.internal.Disk;

// A file read and written at a position the program moves: "r" opens one that is there
// to read, "rw", "rws" and "rwd" make it where it is not and keep what is there. Every
// write reaches the file when it answers, so the last two are the first. A position past
// the end reads the end, and a write there leaves zeros before it. A file is made shorter
// by writing again the bytes it keeps, which a JDK's host does in place. What is refused
// and its words are a JDK's.
public class RandomAccessFile implements DataOutput, DataInput, Closeable {

    private final String path;

    // the file, null once closed
    private Object file;

    private final boolean writes;

    // the position, which may be past the end
    private long at;

    // where the file's own position is, -1 where it is not known (after a read that
    // met the end, which a seek reads again)
    private long there;

    public RandomAccessFile(String name, String mode) throws FileNotFoundException {
        this(name == null ? null : new File(name), mode);
    }

    public RandomAccessFile(File file, String mode) throws FileNotFoundException {
        if (!mode.equals("r") && !mode.equals("rw") && !mode.equals("rws") && !mode.equals("rwd")) {
            throw new IllegalArgumentException("Illegal mode \"" + mode + "\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\"");
        }
        String name = file.getPath();
        this.path = name;
        this.writes = !mode.equals("r");
        this.file = FileInputStream.opened(name, writes ? Disk.UPDATE : Disk.READ);
        this.there = 0;
    }

    private Object open() throws IOException {
        if (file == null) {
            throw new IOException("Stream Closed");
        }
        return file;
    }

    // The file at the position, to read.
    private Object reading() throws IOException {
        Object opened = open();
        if (there != at) {
            Disk.seek(opened, at);
            there = at;
        }
        return opened;
    }

    // The file at the position, to write: past the end, the host fills the gap with
    // zeros.
    private Object writing() throws IOException {
        if (!writes) {
            open();
            throw new IOException("Bad file descriptor");
        }
        return reading();
    }

    public int read() throws IOException {
        int b = Disk.read(reading());
        if (b < 0) {
            there = -1;
        }
        else {
            at++;
            there = at;
        }
        return b;
    }

    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        Object from = reading();
        if (len == 0) {
            return 0;
        }
        int n = Disk.read(from, b, off, len);
        if (n < 0) {
            there = -1;
        }
        else {
            at += n;
            there = at;
        }
        return n;
    }

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public final void readFully(byte[] b) throws IOException {
        readFully(b, 0, b.length);
    }

    public final void readFully(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        int count = 0;
        while (count < len) {
            int n = read(b, off + count, len - count);
            if (n < 0) {
                throw new EOFException();
            }
            count += n;
        }
    }

    public int skipBytes(int n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long length = length();
        long to = Math.min(at + n, length);
        int skipped = to > at ? (int) (to - at) : 0;
        at += skipped;
        return skipped;
    }

    public void write(int b) throws IOException {
        Disk.write(writing(), b & 0xff);
        at++;
        there = at;
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        Object to = writing();
        if (len > 0) {
            Disk.write(to, b, off, len);
            at += len;
            there = at;
        }
    }

    public long getFilePointer() throws IOException {
        open();
        return at;
    }

    public void seek(long pos) throws IOException {
        open();
        if (pos < 0) {
            throw new IOException("Negative seek offset");
        }
        at = pos;
    }

    public long length() throws IOException {
        return Disk.length(open());
    }

    public void setLength(long newLength) throws IOException {
        Object opened = open();
        if (!writes) {
            throw new IOException("Bad file descriptor");
        }
        if (newLength < 0) {
            throw new IOException("Invalid argument");
        }
        long length = Disk.length(opened);
        there = -1;
        if (newLength > length) {
            long kept = at;
            at = newLength - 1;
            writing();
            Disk.write(opened, 0);
            at = kept;
            there = -1;
        }
        else if (newLength < length) {
            // the bytes kept, written again into the file emptied
            byte[] kept = new byte[(int) newLength];
            Disk.seek(opened, 0);
            int count = 0;
            while (count < kept.length) {
                int n = Disk.read(opened, kept, count, kept.length - count);
                if (n < 0) {
                    break;
                }
                count += n;
            }
            Disk.close(opened);
            Object emptied = Disk.open(path, Disk.WRITE);
            if (emptied == null) {
                file = null;
                throw new IOException(Disk.words(Disk.refused(path, true)));
            }
            if (count > 0) {
                Disk.write(emptied, kept, 0, count);
            }
            Disk.close(emptied);
            file = Disk.open(path, Disk.UPDATE);
            if (file == null) {
                throw new IOException(Disk.words(Disk.refused(path, true)));
            }
        }
        if (at > newLength) {
            at = newLength;
        }
    }

    public void close() throws IOException {
        Object closing = file;
        if (closing != null) {
            file = null;
            Disk.close(closing);
        }
    }

    private int next() throws IOException {
        int b = read();
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
        int c = read();
        if (c < 0) {
            return null;
        }
        StringBuilder line = new StringBuilder();
        while (c >= 0 && c != '\n' && c != '\r') {
            line.append((char) c);
            c = read();
        }
        if (c == '\r') {
            long mark = at;
            if (read() != '\n') {
                at = mark;
            }
        }
        return line.toString();
    }

    public final String readUTF() throws IOException {
        return DataInputStream.readUTF(this);
    }

    public final void writeBoolean(boolean v) throws IOException {
        write(v ? 1 : 0);
    }

    public final void writeByte(int v) throws IOException {
        write(v);
    }

    public final void writeShort(int v) throws IOException {
        write(new byte[] { (byte) (v >> 8), (byte) v });
    }

    public final void writeChar(int v) throws IOException {
        write(new byte[] { (byte) (v >> 8), (byte) v });
    }

    public final void writeInt(int v) throws IOException {
        write(new byte[] { (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v });
    }

    public final void writeLong(long v) throws IOException {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (v >> (56 - 8 * i));
        }
        write(bytes);
    }

    public final void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public final void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public final void writeBytes(String s) throws IOException {
        byte[] bytes = new byte[s.length()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) s.charAt(i);
        }
        write(bytes);
    }

    public final void writeChars(String s) throws IOException {
        char[] units = s.toCharArray();
        byte[] bytes = new byte[units.length * 2];
        for (int i = 0; i < units.length; i++) {
            bytes[2 * i] = (byte) (units[i] >> 8);
            bytes[2 * i + 1] = (byte) units[i];
        }
        write(bytes);
    }

    public final void writeUTF(String s) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        new DataOutputStream(encoded).writeUTF(s);
        write(encoded.toByteArray());
    }
}
