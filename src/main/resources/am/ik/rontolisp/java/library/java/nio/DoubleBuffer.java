// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

import java.util.Objects;

// One class for every buffer of doubles but a view of a byte buffer: an array from an offset,
// writable or read-only. Element i is at(i) and set(i, v), and a buffer over the same elements
// is made(..), which a view overrides. Read off a JDK's answers: the words of each refusal,
// hashCode (CharBuffer's, each element cast to an int), the text of each kind. The five
// buffers of numbers are written from one template.
public class DoubleBuffer extends Buffer implements Comparable<DoubleBuffer> {

    private final double[] store;

    // the index in store of the buffer's element 0
    private final int offset;

    private final boolean readOnly;

    DoubleBuffer(int mark, int position, int limit, int capacity, double[] store, int offset, boolean readOnly) {
        super(mark, position, limit, capacity);
        this.store = store;
        this.offset = offset;
        this.readOnly = readOnly;
    }

    public static DoubleBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        return new DoubleBuffer(-1, 0, capacity, capacity, new double[capacity], 0, false);
    }

    public static DoubleBuffer wrap(double[] array, int offset, int length) {
        int n = array.length;
        if (offset < 0 || length < 0 || offset > n - length) {
            throw new IndexOutOfBoundsException();
        }
        return new DoubleBuffer(-1, offset, offset + length, n, array, 0, false);
    }

    public static DoubleBuffer wrap(double[] array) {
        return wrap(array, 0, array.length);
    }

    double at(int index) {
        return store[offset + index];
    }

    void set(int index, double value) {
        store[offset + index] = value;
    }

    // a buffer of the same elements from this one's element `skip` on
    DoubleBuffer made(int mark, int position, int limit, int capacity, int skip, boolean readOnly) {
        return new DoubleBuffer(mark, position, limit, capacity, store, offset + skip, readOnly);
    }

    private void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public DoubleBuffer slice() {
        int rem = remaining();
        return made(-1, 0, rem, rem, position(), readOnly);
    }

    public DoubleBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return made(-1, 0, length, length, index, readOnly);
    }

    public DoubleBuffer duplicate() {
        return made(markValue(), position(), limit(), capacity(), 0, readOnly);
    }

    public DoubleBuffer asReadOnlyBuffer() {
        return made(markValue(), position(), limit(), capacity(), 0, true);
    }

    public double get() {
        return at(taken(1));
    }

    public DoubleBuffer put(double value) {
        writable();
        set(given(1), value);
        return this;
    }

    public double get(int index) {
        return at(checked(index, 1));
    }

    public DoubleBuffer put(int index, double value) {
        writable();
        set(checked(index, 1), value);
        return this;
    }

    public DoubleBuffer get(double[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);
        int from = taken(length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(from + i);
        }
        return this;
    }

    public DoubleBuffer get(double[] dst) {
        return get(dst, 0, dst.length);
    }

    public DoubleBuffer get(int index, double[] dst, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(index + i);
        }
        return this;
    }

    public DoubleBuffer get(int index, double[] dst) {
        return get(index, dst, 0, dst.length);
    }

    // the elements of a range, copied out first so that a buffer sharing them may be put into
    private double[] elements(int from, int length) {
        double[] taken = new double[length];
        for (int i = 0; i < length; i++) {
            taken[i] = at(from + i);
        }
        return taken;
    }

    private void putElements(int index, double[] values, int from, int length) {
        for (int i = 0; i < length; i++) {
            set(index + i, values[from + i]);
        }
    }

    public DoubleBuffer put(DoubleBuffer src) {
        if (src == this) {
            throw new IllegalArgumentException("The source buffer is this buffer");
        }
        writable();
        int n = src.remaining();
        if (n > remaining()) {
            throw new BufferOverflowException();
        }
        double[] taken = src.elements(src.step(n), n);
        putElements(step(n), taken, 0, n);
        return this;
    }

    public DoubleBuffer put(int index, DoubleBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.limit());
        writable();
        putElements(index, src.elements(offset, length), 0, length);
        return this;
    }

    public DoubleBuffer put(double[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(given(length), src, offset, length);
        return this;
    }

    public final DoubleBuffer put(double[] src) {
        return put(src, 0, src.length);
    }

    public DoubleBuffer put(int index, double[] src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(index, src, offset, length);
        return this;
    }

    public DoubleBuffer put(int index, double[] src) {
        return put(index, src, 0, src.length);
    }

    public final boolean hasArray() {
        return store != null && !readOnly;
    }

    public final double[] array() {
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

    public DoubleBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    public DoubleBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    public DoubleBuffer mark() {
        super.mark();
        return this;
    }

    public DoubleBuffer reset() {
        super.reset();
        return this;
    }

    public DoubleBuffer clear() {
        super.clear();
        return this;
    }

    public DoubleBuffer flip() {
        super.flip();
        return this;
    }

    public DoubleBuffer rewind() {
        super.rewind();
        return this;
    }

    public DoubleBuffer compact() {
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
        return described("HeapDoubleBuffer" + (readOnly ? "R" : ""));
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
        if (!(ob instanceof DoubleBuffer)) {
            return false;
        }
        DoubleBuffer that = (DoubleBuffer) ob;
        return remaining() == that.remaining() && mismatch(that) < 0;
    }

    public int compareTo(DoubleBuffer that) {
        int i = mismatch(that);
        if (i >= 0 && i < remaining() && i < that.remaining()) {
            double a = at(position() + i);
            double b = that.at(that.position() + i);
            return a == b ? 0 : Double.compare(a, b);
        }
        return remaining() - that.remaining();
    }

    public int mismatch(DoubleBuffer that) {
        int n = Math.min(remaining(), that.remaining());
        for (int i = 0; i < n; i++) {
            double a = at(position() + i);
            double b = that.at(that.position() + i);
            if (!(a == b || Double.isNaN(a) && Double.isNaN(b))) {
                return i;
            }
        }
        return remaining() == that.remaining() ? -1 : n;
    }

    public ByteOrder order() {
        return ByteOrder.nativeOrder();
    }
}
