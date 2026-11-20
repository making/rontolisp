// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.IntStream;

// One class for every kind: an array of UTF-16 units from an offset, writable or read-only,
// or a text wrapped as it is. A String is wrapped as its units, read-only, with no array to
// hand out; any other CharSequence is read through charAt, so a change to it shows. Which
// refusal has which words, and hashCode -- 31 times the hash of the elements after one,
// from the last one back, starting at 1 -- were read off a JDK's answers.
public class CharBuffer extends Buffer implements Comparable<CharBuffer>, Appendable, CharSequence, Readable {

    private final char[] hb;

    // the index in hb, or in text, of the buffer's element 0
    private final int offset;

    // a CharSequence read as it is, or null
    private final CharSequence text;

    private final boolean readOnly;

    // whether the buffer is a wrapped text, whose array is no one's to have
    private final boolean textual;

    CharBuffer(int mark, int position, int limit, int capacity, char[] hb, int offset, CharSequence text,
            boolean readOnly, boolean textual) {
        super(mark, position, limit, capacity);
        this.hb = hb;
        this.offset = offset;
        this.text = text;
        this.readOnly = readOnly;
        this.textual = textual;
    }

    public static CharBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        return new CharBuffer(-1, 0, capacity, capacity, new char[capacity], 0, null, false, false);
    }

    public static CharBuffer wrap(char[] array, int offset, int length) {
        if (offset < 0 || length < 0 || offset > array.length - length) {
            throw new IndexOutOfBoundsException();
        }
        return new CharBuffer(-1, offset, offset + length, array.length, array, 0, null, false, false);
    }

    public static CharBuffer wrap(char[] array) {
        return wrap(array, 0, array.length);
    }

    public static CharBuffer wrap(CharSequence csq, int start, int end) {
        CharBuffer whole = wrap(csq);
        if (start < 0 || start > whole.capacity() || end < start || end > whole.capacity()) {
            throw new IndexOutOfBoundsException();
        }
        return new CharBuffer(-1, start, end, whole.capacity(), whole.hb, 0, whole.text, true, true);
    }

    public static CharBuffer wrap(CharSequence csq) {
        if (csq instanceof String s) {
            char[] units = s.toCharArray();
            return new CharBuffer(-1, 0, units.length, units.length, units, 0, null, true, true);
        }
        int n = csq.length();
        return new CharBuffer(-1, 0, n, n, null, 0, csq, true, true);
    }

    private char at(int index) {
        return text != null ? text.charAt(offset + index) : hb[offset + index];
    }

    private void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public CharBuffer slice() {
        int rem = remaining();
        return new CharBuffer(-1, 0, rem, rem, hb, offset + position(), text, readOnly, textual);
    }

    public CharBuffer slice(int index, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        return new CharBuffer(-1, 0, length, length, hb, offset + index, text, readOnly, textual);
    }

    public CharBuffer duplicate() {
        return new CharBuffer(markValue(), position(), limit(), capacity(), hb, offset, text, readOnly, textual);
    }

    public CharBuffer asReadOnlyBuffer() {
        return new CharBuffer(markValue(), position(), limit(), capacity(), hb, offset, text, true, textual);
    }

    public char get() {
        if (position() >= limit()) {
            throw new BufferUnderflowException();
        }
        return at(step(1));
    }

    public CharBuffer put(char c) {
        writable();
        if (position() >= limit()) {
            throw new BufferOverflowException();
        }
        hb[offset + step(1)] = c;
        return this;
    }

    public char get(int index) {
        if (index < 0 || index >= limit()) {
            throw new IndexOutOfBoundsException();
        }
        return at(index);
    }

    public CharBuffer put(int index, char c) {
        writable();
        if (index < 0 || index >= limit()) {
            throw new IndexOutOfBoundsException();
        }
        hb[offset + index] = c;
        return this;
    }

    public CharBuffer get(char[] dst, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, dst.length);
        if (length > remaining()) {
            throw new BufferUnderflowException();
        }
        int from = step(length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(from + i);
        }
        return this;
    }

    public CharBuffer get(char[] dst) {
        return get(dst, 0, dst.length);
    }

    public CharBuffer get(int index, char[] dst, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, dst.length);
        for (int i = 0; i < length; i++) {
            dst[offset + i] = at(index + i);
        }
        return this;
    }

    public CharBuffer get(int index, char[] dst) {
        return get(index, dst, 0, dst.length);
    }

    // the units of a range of a buffer, copied out first so that a buffer sharing the
    // array may be put into
    private char[] units(int from, int length) {
        char[] taken = new char[length];
        for (int i = 0; i < length; i++) {
            taken[i] = at(from + i);
        }
        return taken;
    }

    private void putUnits(int index, char[] units, int from, int length) {
        for (int i = 0; i < length; i++) {
            hb[offset + index + i] = units[from + i];
        }
    }

    public CharBuffer put(CharBuffer src) {
        if (src == this) {
            throw new IllegalArgumentException("The source buffer is this buffer");
        }
        writable();
        int n = src.remaining();
        if (n > remaining()) {
            throw new BufferOverflowException();
        }
        char[] taken = src.units(src.step(n), n);
        putUnits(step(n), taken, 0, n);
        return this;
    }

    public CharBuffer put(int index, CharBuffer src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.limit());
        writable();
        putUnits(index, src.units(offset, length), 0, length);
        return this;
    }

    public CharBuffer put(char[] src, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        if (length > remaining()) {
            throw new BufferOverflowException();
        }
        putUnits(step(length), src, offset, length);
        return this;
    }

    public final CharBuffer put(char[] src) {
        return put(src, 0, src.length);
    }

    public CharBuffer put(int index, char[] src, int offset, int length) {
        Objects.checkFromIndexSize(index, length, limit());
        Objects.checkFromIndexSize(offset, length, src.length);
        writable();
        putUnits(index, src, offset, length);
        return this;
    }

    public CharBuffer put(int index, char[] src) {
        return put(index, src, 0, src.length);
    }

    public CharBuffer put(String src, int start, int end) {
        Objects.checkFromIndexSize(start, end - start, src.length());
        writable();
        char[] taken = src.substring(start, end).toCharArray();
        if (taken.length > remaining()) {
            throw new BufferOverflowException();
        }
        putUnits(step(taken.length), taken, 0, taken.length);
        return this;
    }

    public final CharBuffer put(String src) {
        return put(src, 0, src.length());
    }

    public final boolean hasArray() {
        return hb != null && !readOnly;
    }

    public final char[] array() {
        if (textual) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return hb;
    }

    public final int arrayOffset() {
        if (textual) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return offset;
    }

    public CharBuffer position(int newPosition) {
        super.position(newPosition);
        return this;
    }

    public CharBuffer limit(int newLimit) {
        super.limit(newLimit);
        return this;
    }

    public CharBuffer mark() {
        super.mark();
        return this;
    }

    public CharBuffer reset() {
        super.reset();
        return this;
    }

    public CharBuffer clear() {
        super.clear();
        return this;
    }

    public CharBuffer flip() {
        super.flip();
        return this;
    }

    public CharBuffer rewind() {
        super.rewind();
        return this;
    }

    public CharBuffer compact() {
        writable();
        int rem = remaining();
        putUnits(0, units(position(), rem), 0, rem);
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

    public int hashCode() {
        int h = 1;
        for (int i = limit() - 1; i >= position(); i--) {
            h = 31 * h + at(i);
        }
        return h;
    }

    public boolean equals(Object ob) {
        if (this == ob) {
            return true;
        }
        if (!(ob instanceof CharBuffer)) {
            return false;
        }
        CharBuffer that = (CharBuffer) ob;
        return remaining() == that.remaining() && mismatch(that) < 0;
    }

    public int compareTo(CharBuffer that) {
        int i = mismatch(that);
        if (i >= 0 && i < remaining() && i < that.remaining()) {
            return Character.compare(at(position() + i), that.at(that.position() + i));
        }
        return remaining() - that.remaining();
    }

    public int mismatch(CharBuffer that) {
        int n = Math.min(remaining(), that.remaining());
        for (int i = 0; i < n; i++) {
            if (at(position() + i) != that.at(that.position() + i)) {
                return i;
            }
        }
        return remaining() == that.remaining() ? -1 : n;
    }

    public String toString() {
        return new String(units(position(), remaining()));
    }

    public final int length() {
        return remaining();
    }

    public final boolean isEmpty() {
        return remaining() == 0;
    }

    public final char charAt(int index) {
        if (index < 0 || index >= remaining()) {
            throw new IndexOutOfBoundsException();
        }
        return at(position() + index);
    }

    public CharBuffer subSequence(int start, int end) {
        int pos = position();
        if (start < 0 || end > remaining() || start > end) {
            throw new IndexOutOfBoundsException();
        }
        return new CharBuffer(-1, pos + start, pos + end, capacity(), hb, offset, text, readOnly, textual);
    }

    // the units from the position to the limit, as they are when the stream is walked
    public IntStream chars() {
        CharBuffer self = this;
        return IntStream.range(position(), limit()).map(i -> self.at(i));
    }

    public CharBuffer append(CharSequence csq) {
        return put(String.valueOf(csq));
    }

    public CharBuffer append(CharSequence csq, int start, int end) {
        CharSequence cs = csq == null ? "null" : csq;
        return put(cs.subSequence(start, end).toString());
    }

    public CharBuffer append(char c) {
        return put(c);
    }

    // what is left of this buffer moved into the target as far as it has room; -1 when
    // nothing is left
    public int read(CharBuffer target) throws IOException {
        int n = remaining();
        if (n == 0) {
            return -1;
        }
        int k = Math.min(n, target.remaining());
        if (k == 0) {
            return 0;
        }
        target.put(subSequence(0, k));
        step(k);
        return k;
    }
}
