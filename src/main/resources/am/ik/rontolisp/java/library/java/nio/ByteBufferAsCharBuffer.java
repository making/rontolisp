// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

import java.util.Objects;

// The UTF-16 units of a byte buffer's bytes from an index, in the order it had when the view
// was made. Its text is its units, as any char buffer's. It overrides what CharBuffer reads,
// writes and makes inline, with CharBuffer's checks.
final class ByteBufferAsCharBuffer extends CharBuffer {

    private final byte[] bytes;

    // the index in bytes of the view's element 0
    private final int base;

    private final boolean direct;

    private final boolean big;

    ByteBufferAsCharBuffer(int mark, int position, int limit, int capacity, byte[] bytes, int base, boolean readOnly,
            boolean direct, boolean big) {
        super(mark, position, limit, capacity, null, 0, null, readOnly, true);
        this.bytes = bytes;
        this.base = base;
        this.direct = direct;
        this.big = big;
    }

    private CharBuffer made(int mark, int position, int limit, int capacity, int skip, boolean readOnly) {
        return new ByteBufferAsCharBuffer(mark, position, limit, capacity, bytes, base + 2 * skip, readOnly, direct,
                big);
    }

    char at(int index) {
        return (char) ByteBuffer.read(bytes, base + 2 * index, 2, big);
    }

    void putUnits(int index, char[] units, int from, int length) {
        for (int i = 0; i < length; i++) {
            ByteBuffer.write(bytes, base + 2 * (index + i), 2, big, units[from + i]);
        }
    }

    private void writable() {
        if (isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
    }

    public CharBuffer put(char c) {
        writable();
        ByteBuffer.write(bytes, base + 2 * given(1), 2, big, c);
        return this;
    }

    public CharBuffer put(int index, char c) {
        writable();
        ByteBuffer.write(bytes, base + 2 * checked(index, 1), 2, big, c);
        return this;
    }

    public CharBuffer slice() {
        int rem = remaining();
        return made(-1, 0, rem, rem, position(), isReadOnly());
    }

    public CharBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return made(-1, 0, length, length, index, isReadOnly());
    }

    public CharBuffer duplicate() {
        return made(markValue(), position(), limit(), capacity(), 0, isReadOnly());
    }

    public CharBuffer asReadOnlyBuffer() {
        return made(markValue(), position(), limit(), capacity(), 0, true);
    }

    public CharBuffer subSequence(int start, int end) {
        int pos = position();
        if (start < 0 || end > remaining() || start > end) {
            throw new IndexOutOfBoundsException();
        }
        return made(-1, pos + start, pos + end, capacity(), 0, isReadOnly());
    }

    public boolean isDirect() {
        return direct;
    }

    public ByteOrder order() {
        return big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }
}
