// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

import java.util.Objects;

// One class for every buffer of longs but a view of a byte buffer: an array from an offset,
// writable or read-only. Element i is at(i) and set(i, v), and a buffer over the same elements
// is made(..), which a view overrides. Read off a JDK's answers: the words of each refusal,
// hashCode (CharBuffer's, each element cast to an int), the text of each kind. The five
// buffers of numbers are written from one template.
public class LongBuffer extends Buffer implements Comparable<LongBuffer> {

    private final long[] store;

    // the index in store of the buffer's element 0
    private final int offset;

    private final boolean readOnly;

    LongBuffer(int mark, int position, int limit, int capacity, long[] store, int offset, boolean readOnly) {
        super(mark, position, limit, capacity);
        this.store = store;
        this.offset = offset;
        this.readOnly = readOnly;
    }

    public static LongBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        return new LongBuffer(-1, 0, capacity, capacity, new long[capacity], 0, false);
    }

    public static LongBuffer wrap(long[] array, int offset, int length) {
        int n = array.length;
        if (offset < 0 || length < 0 || offset > n - length) {
            throw new IndexOutOfBoundsException();
        }
        return new LongBuffer(-1, offset, offset + length, n, array, 0, false);
    }

    public static LongBuffer wrap(long[] array) {
        return wrap(array, 0, array.length);
    }

    long at(int index) {
        return store[offset + index];
    }

    void set(int index, long value) {
        store[offset + index] = value;
    }

    // a buffer of the same elements from this one's element `skip` on
    LongBuffer made(int mark, int position, int limit, int capacity, int skip, boolean readOnly) {
        return new LongBuffer(mark, position, limit, capacity, store, offset + skip, readOnly);
    }

    private void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public LongBuffer slice() {
        int rem = remaining();
        return made(-1, 0, rem, rem, position(), readOnly);
    }

    public LongBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return made(-1, 0, length, length, index, readOnly);
    }

    public LongBuffer duplicate() {
        return made(markValue(), position(), limit(), capacity(), 0, readOnly);
    }

    public LongBuffer asReadOnlyBuffer() {
        return made(markValue(), position(), limit(), capacity(), 0, true);
    }

    public long get() {
        return at(taken(1));
    }

    public LongBuffer put(long value) {
        writable();
        set(given(1), value);
        return this;
    }

    public long get(int index) {
        return at(checked(index, 1));
    }

    public LongBuffer put(int index, long value) {
        writable();
        set(checked(index, 1), value);
        return this;
    }

    public LongBuffer get(long[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);
        int from = taken(length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(from + i);
        }
        return this;
    }

    public LongBuffer get(long[] dst) {
        return get(dst, 0, dst.length);
    }

    public LongBuffer get(int index, long[] dst, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(index + i);
        }
        return this;
    }

    public LongBuffer get(int index, long[] dst) {
        return get(index, dst, 0, dst.length);
    }

    // the elements of a range, copied out first so that a buffer sharing them may be put into
    private long[] elements(int from, int length) {
        long[] taken = new long[length];
        for (int i = 0; i < length; i++) {
            taken[i] = at(from + i);
        }
        return taken;
    }

    private void putElements(int index, long[] values, int from, int length) {
        for (int i = 0; i < length; i++) {
            set(index + i, values[from + i]);
        }
    }

    public LongBuffer put(LongBuffer src) {
        if (src == this) {
            throw new IllegalArgumentException("The source buffer is this buffer");
        }
        writable();
        int n = src.remaining();
        if (n > remaining()) {
            throw new BufferOverflowException();
        }
        long[] taken = src.elements(src.step(n), n);
        putElements(step(n), taken, 0, n);
        return this;
    }

    public LongBuffer put(int index, LongBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.limit());
        writable();
        putElements(index, src.elements(offset, length), 0, length);
        return this;
    }

    public LongBuffer put(long[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(given(length), src, offset, length);
        return this;
    }

    public final LongBuffer put(long[] src) {
        return put(src, 0, src.length);
    }

    public LongBuffer put(int index, long[] src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(index, src, offset, length);
        return this;
    }

    public LongBuffer put(int index, long[] src) {
        return put(index, src, 0, src.length);
    }

    public final boolean hasArray() {
        return store != null && !readOnly;
    }

    public final long[] array() {
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

    public LongBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    public LongBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    public LongBuffer mark() {
        super.mark();
        return this;
    }

    public LongBuffer reset() {
        super.reset();
        return this;
    }

    public LongBuffer clear() {
        super.clear();
        return this;
    }

    public LongBuffer flip() {
        super.flip();
        return this;
    }

    public LongBuffer rewind() {
        super.rewind();
        return this;
    }

    public LongBuffer compact() {
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
        return described("HeapLongBuffer" + (readOnly ? "R" : ""));
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
        if (!(ob instanceof LongBuffer)) {
            return false;
        }
        LongBuffer that = (LongBuffer) ob;
        return remaining() == that.remaining() && mismatch(that) < 0;
    }

    public int compareTo(LongBuffer that) {
        int i = mismatch(that);
        if (i >= 0 && i < remaining() && i < that.remaining()) {
            long a = at(position() + i);
            long b = that.at(that.position() + i);
            return Long.compare(a, b);
        }
        return remaining() - that.remaining();
    }

    public int mismatch(LongBuffer that) {
        int n = Math.min(remaining(), that.remaining());
        for (int i = 0; i < n; i++) {
            long a = at(position() + i);
            long b = that.at(that.position() + i);
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
