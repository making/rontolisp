// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

// The doubles of a byte buffer's bytes from an index, in the order it had when the view was made.
final class ByteBufferAsDoubleBuffer extends DoubleBuffer {

    private final byte[] bytes;

    // the index in bytes of the view's element 0
    private final int base;

    private final boolean direct;

    private final boolean big;

    ByteBufferAsDoubleBuffer(int mark, int position, int limit, int capacity, byte[] bytes, int base, boolean readOnly,
            boolean direct, boolean big) {
        super(mark, position, limit, capacity, null, 0, readOnly);
        this.bytes = bytes;
        this.base = base;
        this.direct = direct;
        this.big = big;
    }

    double at(int index) {
        return Double.longBitsToDouble(ByteBuffer.read(bytes, base + 8 * index, 8, big));
    }

    void set(int index, double value) {
        ByteBuffer.write(bytes, base + 8 * index, 8, big, Double.doubleToRawLongBits(value));
    }

    DoubleBuffer made(int mark, int position, int limit, int capacity, int skip, boolean readOnly) {
        return new ByteBufferAsDoubleBuffer(mark, position, limit, capacity, bytes, base + 8 * skip, readOnly, direct,
                big);
    }

    public boolean isDirect() {
        return direct;
    }

    public ByteOrder order() {
        return big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    public String toString() {
        return described(ByteBuffer.viewKind("Double", isReadOnly(), direct, big));
    }
}
