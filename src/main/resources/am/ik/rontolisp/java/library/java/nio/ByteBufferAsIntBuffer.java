// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

// The ints of a byte buffer's bytes from an index, in the order it had when the view was made.
final class ByteBufferAsIntBuffer extends IntBuffer {

    private final byte[] bytes;

    // the index in bytes of the view's element 0
    private final int base;

    private final boolean direct;

    private final boolean big;

    ByteBufferAsIntBuffer(int mark, int position, int limit, int capacity, byte[] bytes, int base, boolean readOnly,
            boolean direct, boolean big) {
        super(mark, position, limit, capacity, null, 0, readOnly);
        this.bytes = bytes;
        this.base = base;
        this.direct = direct;
        this.big = big;
    }

    int at(int index) {
        return (int) ByteBuffer.read(bytes, base + 4 * index, 4, big);
    }

    void set(int index, int value) {
        ByteBuffer.write(bytes, base + 4 * index, 4, big, value);
    }

    IntBuffer made(int mark, int position, int limit, int capacity, int skip, boolean readOnly) {
        return new ByteBufferAsIntBuffer(mark, position, limit, capacity, bytes, base + 4 * skip, readOnly, direct,
                big);
    }

    public boolean isDirect() {
        return direct;
    }

    public ByteOrder order() {
        return big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    public String toString() {
        return described(ByteBuffer.viewKind("Int", isReadOnly(), direct, big));
    }
}
