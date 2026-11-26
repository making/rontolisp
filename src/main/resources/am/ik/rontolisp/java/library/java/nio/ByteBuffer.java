// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

import java.util.Objects;

// One class for every kind: an array of bytes from an offset, writable or read-only, heap or
// direct -- a direct buffer is an array here too, with no array to hand out. A value wider than
// a byte is read and written a byte at a time in the buffer's order, big-endian until changed.
// What was read off a JDK's answers: the words of each refusal and the order of the checks,
// hashCode (as CharBuffer's), that order(null) is little-endian, that slice, duplicate and
// asReadOnlyBuffer are big-endian and compact keeps the order, the text of each kind.
public class ByteBuffer extends Buffer implements Comparable<ByteBuffer> {

    private final byte[] store;

    // the index in store of the buffer's element 0
    private final int offset;

    private final boolean readOnly;

    private final boolean direct;

    private boolean big = true;

    ByteBuffer(int mark, int position, int limit, int capacity, byte[] store, int offset, boolean readOnly,
            boolean direct) {
        super(mark, position, limit, capacity);
        this.store = store;
        this.offset = offset;
        this.readOnly = readOnly;
        this.direct = direct;
    }

    public static ByteBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        return new ByteBuffer(-1, 0, capacity, capacity, new byte[capacity], 0, false, false);
    }

    public static ByteBuffer allocateDirect(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        return new ByteBuffer(-1, 0, capacity, capacity, new byte[capacity], 0, false, true);
    }

    public static ByteBuffer wrap(byte[] array, int offset, int length) {
        int n = array.length;
        if (offset < 0 || length < 0 || offset > n - length) {
            throw new IndexOutOfBoundsException();
        }
        return new ByteBuffer(-1, offset, offset + length, n, array, 0, false, false);
    }

    public static ByteBuffer wrap(byte[] array) {
        return wrap(array, 0, array.length);
    }

    private void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public ByteBuffer slice() {
        int rem = remaining();
        return new ByteBuffer(-1, 0, rem, rem, store, offset + position(), readOnly, direct);
    }

    public ByteBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return new ByteBuffer(-1, 0, length, length, store, offset + index, readOnly, direct);
    }

    // How far the byte at index is past the last address unitSize divides. A direct buffer's
    // memory starts at an address every unit size divides (a JDK's at one 16 divides on the
    // Linux host measured, and past that wherever it was allocated); a heap buffer's may move,
    // so it answers for a unit of one byte only, as a JDK's does.
    public final int alignmentOffset(int index, int unitSize) {
        if (index < 0) {
            throw new IllegalArgumentException("Index less than zero: " + index);
        }
        if (unitSize < 1 || (unitSize & (unitSize - 1)) != 0) {
            throw new IllegalArgumentException("Unit size not a power of two: " + unitSize);
        }
        if (unitSize > 1 && !direct) {
            throw new UnsupportedOperationException("Unit size unsupported for non-direct buffers: " + unitSize);
        }
        return (offset + index) & (unitSize - 1);
    }

    // The slice from the first aligned address at or after the position to the last one at or
    // before the limit, empty where there is none between them.
    public final ByteBuffer alignedSlice(int unitSize) {
        int pos = position();
        int lim = limit();
        int first = alignmentOffset(pos, unitSize);
        long from = first == 0 ? pos : (long) pos + unitSize - first;
        int to = lim - alignmentOffset(lim, unitSize);
        return from > to ? slice(pos, 0) : slice((int) from, to - (int) from);
    }

    public ByteBuffer duplicate() {
        return new ByteBuffer(markValue(), position(), limit(), capacity(), store, offset, readOnly, direct);
    }

    public ByteBuffer asReadOnlyBuffer() {
        return new ByteBuffer(markValue(), position(), limit(), capacity(), store, offset, true, direct);
    }

    public byte get() {
        return store[offset + taken(1)];
    }

    public ByteBuffer put(byte b) {
        writable();
        store[offset + given(1)] = b;
        return this;
    }

    public byte get(int index) {
        return store[offset + checked(index, 1)];
    }

    public ByteBuffer put(int index, byte b) {
        writable();
        store[offset + checked(index, 1)] = b;
        return this;
    }

    public ByteBuffer get(byte[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);
        System.arraycopy(store, this.offset + taken(length), dst, offset, length);
        return this;
    }

    public ByteBuffer get(byte[] dst) {
        return get(dst, 0, dst.length);
    }

    public ByteBuffer get(int index, byte[] dst, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        System.arraycopy(store, this.offset + index, dst, offset, length);
        return this;
    }

    public ByteBuffer get(int index, byte[] dst) {
        return get(index, dst, 0, dst.length);
    }

    public ByteBuffer put(ByteBuffer src) {
        if (src == this) {
            throw new IllegalArgumentException("The source buffer is this buffer");
        }
        writable();
        int n = src.remaining();
        if (n > remaining()) {
            throw new BufferOverflowException();
        }
        System.arraycopy(src.store, src.offset + src.step(n), store, offset + step(n), n);
        return this;
    }

    public ByteBuffer put(int index, ByteBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.limit());
        writable();
        System.arraycopy(src.store, src.offset + offset, store, this.offset + index, length);
        return this;
    }

    public ByteBuffer put(byte[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        System.arraycopy(src, offset, store, this.offset + given(length), length);
        return this;
    }

    public final ByteBuffer put(byte[] src) {
        return put(src, 0, src.length);
    }

    public ByteBuffer put(int index, byte[] src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        System.arraycopy(src, offset, store, this.offset + index, length);
        return this;
    }

    public ByteBuffer put(int index, byte[] src) {
        return put(index, src, 0, src.length);
    }

    public final boolean hasArray() {
        return !direct && !readOnly;
    }

    public final byte[] array() {
        if (direct) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return store;
    }

    public final int arrayOffset() {
        if (direct) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return offset;
    }

    public ByteBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    public ByteBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    public ByteBuffer mark() {
        super.mark();
        return this;
    }

    public ByteBuffer reset() {
        super.reset();
        return this;
    }

    public ByteBuffer clear() {
        super.clear();
        return this;
    }

    public ByteBuffer flip() {
        super.flip();
        return this;
    }

    public ByteBuffer rewind() {
        super.rewind();
        return this;
    }

    public ByteBuffer compact() {
        writable();
        int rem = remaining();
        System.arraycopy(store, offset + position(), store, offset, rem);
        limit(capacity());
        position(rem);
        unmark();
        return this;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isDirect() {
        return direct;
    }

    public String toString() {
        return described((direct ? "DirectByteBuffer" : "HeapByteBuffer") + (readOnly ? "R" : ""));
    }

    public int hashCode() {
        int h = 1;
        for (int i = limit() - 1; i >= position(); i--) {
            h = 31 * h + store[offset + i];
        }
        return h;
    }

    public boolean equals(Object ob) {
        if (this == ob) {
            return true;
        }
        if (!(ob instanceof ByteBuffer)) {
            return false;
        }
        ByteBuffer that = (ByteBuffer) ob;
        return remaining() == that.remaining() && mismatch(that) < 0;
    }

    public int compareTo(ByteBuffer that) {
        int i = mismatch(that);
        if (i >= 0 && i < remaining() && i < that.remaining()) {
            return Byte.compare(store[offset + position() + i], that.store[that.offset + that.position() + i]);
        }
        return remaining() - that.remaining();
    }

    public int mismatch(ByteBuffer that) {
        int n = Math.min(remaining(), that.remaining());
        int from = offset + position();
        int thatFrom = that.offset + that.position();
        for (int i = 0; i < n; i++) {
            if (store[from + i] != that.store[thatFrom + i]) {
                return i;
            }
        }
        return remaining() == that.remaining() ? -1 : n;
    }

    public final ByteOrder order() {
        return big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    public final ByteBuffer order(ByteOrder bo) {
        big = bo == ByteOrder.BIG_ENDIAN;
        return this;
    }

    // The size bytes of b from at as a number in the order given, and the number written
    // there: what every wider get and put of a buffer or of a view is.
    static long read(byte[] b, int at, int size, boolean big) {
        long v = 0;
        for (int k = 0; k < size; k++) {
            v = v << 8 | (b[big ? at + k : at + size - 1 - k] & 0xff);
        }
        return v;
    }

    static void write(byte[] b, int at, int size, boolean big, long v) {
        for (int k = 0; k < size; k++) {
            b[big ? at + size - 1 - k : at + k] = (byte) (v >> 8 * k);
        }
    }

    private long take(int size) {
        return read(store, offset + taken(size), size, big);
    }

    private long takeAt(int index, int size) {
        return read(store, offset + checked(index, size), size, big);
    }

    private ByteBuffer give(int size, long v) {
        writable();
        write(store, offset + given(size), size, big, v);
        return this;
    }

    private ByteBuffer giveAt(int index, int size, long v) {
        writable();
        write(store, offset + checked(index, size), size, big, v);
        return this;
    }

    public char getChar() {
        return (char) take(2);
    }

    public char getChar(int index) {
        return (char) takeAt(index, 2);
    }

    public ByteBuffer putChar(char value) {
        return give(2, value);
    }

    public ByteBuffer putChar(int index, char value) {
        return giveAt(index, 2, value);
    }

    public short getShort() {
        return (short) take(2);
    }

    public short getShort(int index) {
        return (short) takeAt(index, 2);
    }

    public ByteBuffer putShort(short value) {
        return give(2, value);
    }

    public ByteBuffer putShort(int index, short value) {
        return giveAt(index, 2, value);
    }

    public int getInt() {
        return (int) take(4);
    }

    public int getInt(int index) {
        return (int) takeAt(index, 4);
    }

    public ByteBuffer putInt(int value) {
        return give(4, value);
    }

    public ByteBuffer putInt(int index, int value) {
        return giveAt(index, 4, value);
    }

    public long getLong() {
        return take(8);
    }

    public long getLong(int index) {
        return takeAt(index, 8);
    }

    public ByteBuffer putLong(long value) {
        return give(8, value);
    }

    public ByteBuffer putLong(int index, long value) {
        return giveAt(index, 8, value);
    }

    public float getFloat() {
        return Float.intBitsToFloat((int) take(4));
    }

    public float getFloat(int index) {
        return Float.intBitsToFloat((int) takeAt(index, 4));
    }

    public ByteBuffer putFloat(float value) {
        return give(4, Float.floatToRawIntBits(value));
    }

    public ByteBuffer putFloat(int index, float value) {
        return giveAt(index, 4, Float.floatToRawIntBits(value));
    }

    public double getDouble() {
        return Double.longBitsToDouble(take(8));
    }

    public double getDouble(int index) {
        return Double.longBitsToDouble(takeAt(index, 8));
    }

    public ByteBuffer putDouble(double value) {
        return give(8, Double.doubleToRawLongBits(value));
    }

    public ByteBuffer putDouble(int index, double value) {
        return giveAt(index, 8, Double.doubleToRawLongBits(value));
    }

    // The views: the bytes from the position to the limit as elements of a size, in the
    // order the buffer has now, read-only and direct as it is.
    public CharBuffer asCharBuffer() {
        int n = remaining() / 2;
        return new ByteBufferAsCharBuffer(-1, 0, n, n, store, offset + position(), readOnly, direct, big);
    }

    public ShortBuffer asShortBuffer() {
        int n = remaining() / 2;
        return new ByteBufferAsShortBuffer(-1, 0, n, n, store, offset + position(), readOnly, direct, big);
    }

    public IntBuffer asIntBuffer() {
        int n = remaining() / 4;
        return new ByteBufferAsIntBuffer(-1, 0, n, n, store, offset + position(), readOnly, direct, big);
    }

    public LongBuffer asLongBuffer() {
        int n = remaining() / 8;
        return new ByteBufferAsLongBuffer(-1, 0, n, n, store, offset + position(), readOnly, direct, big);
    }

    public FloatBuffer asFloatBuffer() {
        int n = remaining() / 4;
        return new ByteBufferAsFloatBuffer(-1, 0, n, n, store, offset + position(), readOnly, direct, big);
    }

    public DoubleBuffer asDoubleBuffer() {
        int n = remaining() / 8;
        return new ByteBufferAsDoubleBuffer(-1, 0, n, n, store, offset + position(), readOnly, direct, big);
    }

    // The name of a view's kind: a JDK's text of it.
    static String viewKind(String element, boolean readOnly, boolean direct, boolean big) {
        String r = readOnly ? "R" : "";
        if (direct) {
            return "Direct" + element + "Buffer" + r + (big ? "S" : "U");
        }
        return "ByteBufferAs" + element + "Buffer" + r + (big ? "B" : "L");
    }
}
