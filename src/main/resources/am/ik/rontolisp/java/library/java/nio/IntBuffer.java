// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

import java.util.Objects;

// One class for every buffer of ints but a view of a byte buffer: an array from an offset,
// writable or read-only. Element i is at(i) and set(i, v), and a buffer over the same elements
// is made(..), which a view overrides. Read off a JDK's answers: the words of each refusal,
// hashCode (CharBuffer's, each element cast to an int), the text of each kind. The five
// buffers of numbers are written from one template.
public class IntBuffer extends Buffer implements Comparable<IntBuffer> {

    private final int[] store;

    // the index in store of the buffer's element 0
    private final int offset;

    private final boolean readOnly;

    IntBuffer(int mark, int position, int limit, int capacity, int[] store, int offset, boolean readOnly) {
        super(mark, position, limit, capacity);
        this.store = store;
        this.offset = offset;
        this.readOnly = readOnly;
    }

    public static IntBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        return new IntBuffer(-1, 0, capacity, capacity, new int[capacity], 0, false);
    }

    public static IntBuffer wrap(int[] array, int offset, int length) {
        int n = array.length;
        if (offset < 0 || length < 0 || offset > n - length) {
            throw new IndexOutOfBoundsException();
        }
        return new IntBuffer(-1, offset, offset + length, n, array, 0, false);
    }

    public static IntBuffer wrap(int[] array) {
        return wrap(array, 0, array.length);
    }

    int at(int index) {
        return store[offset + index];
    }

    void set(int index, int value) {
        store[offset + index] = value;
    }

    // a buffer of the same elements from this one's element `skip` on
    IntBuffer made(int mark, int position, int limit, int capacity, int skip, boolean readOnly) {
        return new IntBuffer(mark, position, limit, capacity, store, offset + skip, readOnly);
    }

    private void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public IntBuffer slice() {
        int rem = remaining();
        return made(-1, 0, rem, rem, position(), readOnly);
    }

    public IntBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return made(-1, 0, length, length, index, readOnly);
    }

    public IntBuffer duplicate() {
        return made(markValue(), position(), limit(), capacity(), 0, readOnly);
    }

    public IntBuffer asReadOnlyBuffer() {
        return made(markValue(), position(), limit(), capacity(), 0, true);
    }

    public int get() {
        return at(taken(1));
    }

    public IntBuffer put(int value) {
        writable();
        set(given(1), value);
        return this;
    }

    public int get(int index) {
        return at(checked(index, 1));
    }

    public IntBuffer put(int index, int value) {
        writable();
        set(checked(index, 1), value);
        return this;
    }

    public IntBuffer get(int[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);
        int from = taken(length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(from + i);
        }
        return this;
    }

    public IntBuffer get(int[] dst) {
        return get(dst, 0, dst.length);
    }

    public IntBuffer get(int index, int[] dst, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(index + i);
        }
        return this;
    }

    public IntBuffer get(int index, int[] dst) {
        return get(index, dst, 0, dst.length);
    }

    // the elements of a range, copied out first so that a buffer sharing them may be put into
    private int[] elements(int from, int length) {
        int[] taken = new int[length];
        for (int i = 0; i < length; i++) {
            taken[i] = at(from + i);
        }
        return taken;
    }

    private void putElements(int index, int[] values, int from, int length) {
        for (int i = 0; i < length; i++) {
            set(index + i, values[from + i]);
        }
    }

    public IntBuffer put(IntBuffer src) {
        if (src == this) {
            throw new IllegalArgumentException("The source buffer is this buffer");
        }
        writable();
        int n = src.remaining();
        if (n > remaining()) {
            throw new BufferOverflowException();
        }
        int[] taken = src.elements(src.step(n), n);
        putElements(step(n), taken, 0, n);
        return this;
    }

    public IntBuffer put(int index, IntBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.limit());
        writable();
        putElements(index, src.elements(offset, length), 0, length);
        return this;
    }

    public IntBuffer put(int[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(given(length), src, offset, length);
        return this;
    }

    public final IntBuffer put(int[] src) {
        return put(src, 0, src.length);
    }

    public IntBuffer put(int index, int[] src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(index, src, offset, length);
        return this;
    }

    public IntBuffer put(int index, int[] src) {
        return put(index, src, 0, src.length);
    }

    public final boolean hasArray() {
        return store != null && !readOnly;
    }

    public final int[] array() {
        if (store == null) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return store;
    }

    public final int arrayOffset() {
        if (store == null) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return offset;
    }

    public IntBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    public IntBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    public IntBuffer mark() {
        super.mark();
        return this;
    }

    public IntBuffer reset() {
        super.reset();
        return this;
    }

    public IntBuffer clear() {
        super.clear();
        return this;
    }

    public IntBuffer flip() {
        super.flip();
        return this;
    }

    public IntBuffer rewind() {
        super.rewind();
        return this;
    }

    public IntBuffer compact() {
        writable();
        int rem = remaining();
        putElements(0, elements(position(), rem), 0, rem);
        limit(capacity());
        position(rem);
        unmark();
        return this;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isDirect() {
        return false;
    }

    public String toString() {
        return described("HeapIntBuffer" + (readOnly ? "R" : ""));
    }

    public int hashCode() {
        int h = 1;
        for (int i = limit() - 1; i >= position(); i--) {
            h = 31 * h + (int) at(i);
        }
        return h;
    }

    public boolean equals(Object ob) {
        if (this == ob) {
            return true;
        }
        if (!(ob instanceof IntBuffer)) {
            return false;
        }
        IntBuffer that = (IntBuffer) ob;
        return remaining() == that.remaining() && mismatch(that) < 0;
    }

    public int compareTo(IntBuffer that) {
        int i = mismatch(that);
        if (i >= 0 && i < remaining() && i < that.remaining()) {
            int a = at(position() + i);
            int b = that.at(that.position() + i);
            return Integer.compare(a, b);
        }
        return remaining() - that.remaining();
    }

    public int mismatch(IntBuffer that) {
        int n = Math.min(remaining(), that.remaining());
        for (int i = 0; i < n; i++) {
            int a = at(position() + i);
            int b = that.at(that.position() + i);
            if (a != b) {
                return i;
            }
        }
        return remaining() == that.remaining() ? -1 : n;
    }

    public ByteOrder order() {
        return ByteOrder.nativeOrder();
    }
}
