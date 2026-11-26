// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

import java.util.Objects;

// One class for every buffer of floats but a view of a byte buffer: an array from an offset,
// writable or read-only. Element i is at(i) and set(i, v), and a buffer over the same elements
// is made(..), which a view overrides. Read off a JDK's answers: the words of each refusal,
// hashCode (CharBuffer's, each element cast to an int), the text of each kind. The five
// buffers of numbers are written from one template.
public class FloatBuffer extends Buffer implements Comparable<FloatBuffer> {

    private final float[] store;

    // the index in store of the buffer's element 0
    private final int offset;

    private final boolean readOnly;

    FloatBuffer(int mark, int position, int limit, int capacity, float[] store, int offset, boolean readOnly) {
        super(mark, position, limit, capacity);
        this.store = store;
        this.offset = offset;
        this.readOnly = readOnly;
    }

    public static FloatBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        return new FloatBuffer(-1, 0, capacity, capacity, new float[capacity], 0, false);
    }

    public static FloatBuffer wrap(float[] array, int offset, int length) {
        int n = array.length;
        if (offset < 0 || length < 0 || offset > n - length) {
            throw new IndexOutOfBoundsException();
        }
        return new FloatBuffer(-1, offset, offset + length, n, array, 0, false);
    }

    public static FloatBuffer wrap(float[] array) {
        return wrap(array, 0, array.length);
    }

    float at(int index) {
        return store[offset + index];
    }

    void set(int index, float value) {
        store[offset + index] = value;
    }

    // a buffer of the same elements from this one's element `skip` on
    FloatBuffer made(int mark, int position, int limit, int capacity, int skip, boolean readOnly) {
        return new FloatBuffer(mark, position, limit, capacity, store, offset + skip, readOnly);
    }

    private void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public FloatBuffer slice() {
        int rem = remaining();
        return made(-1, 0, rem, rem, position(), readOnly);
    }

    public FloatBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return made(-1, 0, length, length, index, readOnly);
    }

    public FloatBuffer duplicate() {
        return made(markValue(), position(), limit(), capacity(), 0, readOnly);
    }

    public FloatBuffer asReadOnlyBuffer() {
        return made(markValue(), position(), limit(), capacity(), 0, true);
    }

    public float get() {
        return at(taken(1));
    }

    public FloatBuffer put(float value) {
        writable();
        set(given(1), value);
        return this;
    }

    public float get(int index) {
        return at(checked(index, 1));
    }

    public FloatBuffer put(int index, float value) {
        writable();
        set(checked(index, 1), value);
        return this;
    }

    public FloatBuffer get(float[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);
        int from = taken(length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(from + i);
        }
        return this;
    }

    public FloatBuffer get(float[] dst) {
        return get(dst, 0, dst.length);
    }

    public FloatBuffer get(int index, float[] dst, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(index + i);
        }
        return this;
    }

    public FloatBuffer get(int index, float[] dst) {
        return get(index, dst, 0, dst.length);
    }

    // the elements of a range, copied out first so that a buffer sharing them may be put into
    private float[] elements(int from, int length) {
        float[] taken = new float[length];
        for (int i = 0; i < length; i++) {
            taken[i] = at(from + i);
        }
        return taken;
    }

    private void putElements(int index, float[] values, int from, int length) {
        for (int i = 0; i < length; i++) {
            set(index + i, values[from + i]);
        }
    }

    public FloatBuffer put(FloatBuffer src) {
        if (src == this) {
            throw new IllegalArgumentException("The source buffer is this buffer");
        }
        writable();
        int n = src.remaining();
        if (n > remaining()) {
            throw new BufferOverflowException();
        }
        float[] taken = src.elements(src.step(n), n);
        putElements(step(n), taken, 0, n);
        return this;
    }

    public FloatBuffer put(int index, FloatBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.limit());
        writable();
        putElements(index, src.elements(offset, length), 0, length);
        return this;
    }

    public FloatBuffer put(float[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(given(length), src, offset, length);
        return this;
    }

    public final FloatBuffer put(float[] src) {
        return put(src, 0, src.length);
    }

    public FloatBuffer put(int index, float[] src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putElements(index, src, offset, length);
        return this;
    }

    public FloatBuffer put(int index, float[] src) {
        return put(index, src, 0, src.length);
    }

    public final boolean hasArray() {
        return store != null && !readOnly;
    }

    public final float[] array() {
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

    public FloatBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    public FloatBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    public FloatBuffer mark() {
        super.mark();
        return this;
    }

    public FloatBuffer reset() {
        super.reset();
        return this;
    }

    public FloatBuffer clear() {
        super.clear();
        return this;
    }

    public FloatBuffer flip() {
        super.flip();
        return this;
    }

    public FloatBuffer rewind() {
        super.rewind();
        return this;
    }

    public FloatBuffer compact() {
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
        return described("HeapFloatBuffer" + (readOnly ? "R" : ""));
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
        if (!(ob instanceof FloatBuffer)) {
            return false;
        }
        FloatBuffer that = (FloatBuffer) ob;
        return remaining() == that.remaining() && mismatch(that) < 0;
    }

    public int compareTo(FloatBuffer that) {
        int i = mismatch(that);
        if (i >= 0 && i < remaining() && i < that.remaining()) {
            float a = at(position() + i);
            float b = that.at(that.position() + i);
            return a == b ? 0 : Float.compare(a, b);
        }
        return remaining() - that.remaining();
    }

    public int mismatch(FloatBuffer that) {
        int n = Math.min(remaining(), that.remaining());
        for (int i = 0; i < n; i++) {
            float a = at(position() + i);
            float b = that.at(that.position() + i);
            if (!(a == b || Float.isNaN(a) && Float.isNaN(b))) {
                return i;
            }
        }
        return remaining() == that.remaining() ? -1 : n;
    }

    public ByteOrder order() {
        return ByteOrder.nativeOrder();
    }
}
