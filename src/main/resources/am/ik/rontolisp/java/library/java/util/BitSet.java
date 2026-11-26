// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import rontolisp.internal.IntFlow;

// Bit i is bit (i & 63) of word (i >> 6); the words past the last one that is not zero are
// out of use and zero. hashCode is the specification's formula. How many words the array
// holds -- what size() answers -- was read off a JDK's answers: a new set holds one word, or
// the words for the bits it was given, and a set given its size keeps it through a clone
// until it grows; growing takes twice the words or the words needed, whichever is more; a
// clone of any other set, and a set of valueOf, holds the words in use; get(from, to) is a
// set given the bits from `from` up to `to` or the length.
public class BitSet implements Cloneable, Serializable {

    private long[] words;

    // the words up to the last one that is not zero
    private int inUse;

    // whether the size was given and nothing grew it since
    private boolean given;

    public BitSet() {
        words = new long[1];
    }

    public BitSet(int nbits) {
        if (nbits < 0) {
            throw new NegativeArraySizeException("nbits < 0: " + nbits);
        }
        words = new long[wordsFor(nbits)];
        given = true;
    }

    private BitSet(long[] words, boolean given) {
        this.words = words;
        this.given = given;
        settle(words.length);
    }

    // the words holding the bits below `bits`
    private static int wordsFor(int bits) {
        return bits == 0 ? 0 : ((bits - 1) >> 6) + 1;
    }

    // counts the words in use, down from `from` past the zero ones
    private void settle(int from) {
        int n = from;
        while (n > 0 && words[n - 1] == 0) {
            n--;
        }
        inUse = n;
    }

    private void room(int needed) {
        if (words.length < needed) {
            words = Arrays.copyOf(words, Math.max(2 * words.length, needed));
            given = false;
        }
    }

    private static void index(int bitIndex) {
        if (bitIndex < 0) {
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);
        }
    }

    private static void range(int fromIndex, int toIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        }
        if (toIndex < 0) {
            throw new IndexOutOfBoundsException("toIndex < 0: " + toIndex);
        }
        if (fromIndex > toIndex) {
            throw new IndexOutOfBoundsException("fromIndex: " + fromIndex + " > toIndex: " + toIndex);
        }
    }

    public static BitSet valueOf(long[] longs) {
        int n = longs.length;
        while (n > 0 && longs[n - 1] == 0) {
            n--;
        }
        return new BitSet(Arrays.copyOf(longs, n), false);
    }

    public static BitSet valueOf(byte[] bytes) {
        int n = bytes.length;
        while (n > 0 && bytes[n - 1] == 0) {
            n--;
        }
        long[] words = new long[(n + 7) >> 3];
        for (int i = 0; i < n; i++) {
            words[i >> 3] |= (bytes[i] & 0xFFL) << ((i & 7) << 3);
        }
        return new BitSet(words, false);
    }

    // the words, and the bytes as little-endian words, from the position to the limit; the
    // buffer is not moved
    public static BitSet valueOf(LongBuffer lb) {
        long[] words = new long[lb.remaining()];
        lb.get(lb.position(), words);
        return valueOf(words);
    }

    public static BitSet valueOf(ByteBuffer bb) {
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bb.position(), bytes);
        return valueOf(bytes);
    }

    public long[] toLongArray() {
        return Arrays.copyOf(words, inUse);
    }

    // the bytes up to the last one that is not zero, the lowest bits first
    public byte[] toByteArray() {
        if (inUse == 0) {
            return new byte[0];
        }
        int n = ((inUse - 1) << 3) + ((64 - Long.numberOfLeadingZeros(words[inUse - 1]) + 7) >> 3);
        byte[] bytes = new byte[n];
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) (words[i >> 3] >>> ((i & 7) << 3));
        }
        return bytes;
    }

    public void flip(int bitIndex) {
        index(bitIndex);
        int w = bitIndex >> 6;
        room(w + 1);
        words[w] ^= 1L << bitIndex;
        settle(Math.max(inUse, w + 1));
    }

    public void flip(int fromIndex, int toIndex) {
        range(fromIndex, toIndex);
        if (fromIndex == toIndex) {
            return;
        }
        int last = (toIndex - 1) >> 6;
        room(last + 1);
        apply(fromIndex, toIndex, 2);
        settle(Math.max(inUse, last + 1));
    }

    public void set(int bitIndex) {
        index(bitIndex);
        int w = bitIndex >> 6;
        room(w + 1);
        words[w] |= 1L << bitIndex;
        if (inUse <= w) {
            inUse = w + 1;
        }
    }

    public void set(int bitIndex, boolean value) {
        if (value) {
            set(bitIndex);
        }
        else {
            clear(bitIndex);
        }
    }

    public void set(int fromIndex, int toIndex) {
        range(fromIndex, toIndex);
        if (fromIndex == toIndex) {
            return;
        }
        int last = (toIndex - 1) >> 6;
        room(last + 1);
        apply(fromIndex, toIndex, 1);
        if (inUse <= last) {
            inUse = last + 1;
        }
    }

    public void set(int fromIndex, int toIndex, boolean value) {
        if (value) {
            set(fromIndex, toIndex);
        }
        else {
            clear(fromIndex, toIndex);
        }
    }

    public void clear(int bitIndex) {
        index(bitIndex);
        int w = bitIndex >> 6;
        if (w < inUse) {
            words[w] &= ~(1L << bitIndex);
            settle(inUse);
        }
    }

    // within the words in use only: past them every bit is clear
    public void clear(int fromIndex, int toIndex) {
        range(fromIndex, toIndex);
        if (fromIndex == toIndex || (fromIndex >> 6) >= inUse) {
            return;
        }
        if (((toIndex - 1) >> 6) >= inUse) {
            toIndex = inUse << 6;
        }
        apply(fromIndex, toIndex, 0);
        settle(inUse);
    }

    public void clear() {
        for (int i = 0; i < inUse; i++) {
            words[i] = 0;
        }
        inUse = 0;
    }

    // clears (0), sets (1) or flips (2) the bits from fromIndex below toIndex, which the
    // words hold
    private void apply(int fromIndex, int toIndex, int how) {
        int first = fromIndex >> 6;
        int last = (toIndex - 1) >> 6;
        for (int w = first; w <= last; w++) {
            long mask = -1L;
            if (w == first) {
                mask &= -1L << fromIndex;
            }
            if (w == last) {
                mask &= -1L >>> -toIndex;
            }
            if (how == 0) {
                words[w] &= ~mask;
            }
            else if (how == 1) {
                words[w] |= mask;
            }
            else {
                words[w] ^= mask;
            }
        }
    }

    public boolean get(int bitIndex) {
        index(bitIndex);
        int w = bitIndex >> 6;
        return w < inUse && (words[w] & (1L << bitIndex)) != 0;
    }

    public BitSet get(int fromIndex, int toIndex) {
        range(fromIndex, toIndex);
        int length = length();
        if (length <= fromIndex || fromIndex == toIndex) {
            return new BitSet(0);
        }
        if (toIndex > length) {
            toIndex = length;
        }
        BitSet part = new BitSet(toIndex - fromIndex);
        int n = part.words.length;
        int first = fromIndex >> 6;
        int shift = fromIndex & 63;
        for (int i = 0; i < n; i++) {
            long word = words[first + i] >>> shift;
            if (shift != 0 && first + i + 1 < inUse) {
                word |= words[first + i + 1] << (64 - shift);
            }
            part.words[i] = word;
        }
        part.words[n - 1] &= -1L >>> -(toIndex - fromIndex);
        part.settle(n);
        return part;
    }

    public int nextSetBit(int fromIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        }
        int w = fromIndex >> 6;
        if (w >= inUse) {
            return -1;
        }
        long word = words[w] & (-1L << fromIndex);
        while (word == 0) {
            w++;
            if (w == inUse) {
                return -1;
            }
            word = words[w];
        }
        return (w << 6) + Long.numberOfTrailingZeros(word);
    }

    public int nextClearBit(int fromIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        }
        int w = fromIndex >> 6;
        if (w >= inUse) {
            return fromIndex;
        }
        long word = ~words[w] & (-1L << fromIndex);
        while (word == 0) {
            w++;
            if (w == inUse) {
                return inUse << 6;
            }
            word = ~words[w];
        }
        return (w << 6) + Long.numberOfTrailingZeros(word);
    }

    public int previousSetBit(int fromIndex) {
        if (fromIndex < 0) {
            if (fromIndex == -1) {
                return -1;
            }
            throw new IndexOutOfBoundsException("fromIndex < -1: " + fromIndex);
        }
        int w = fromIndex >> 6;
        if (w >= inUse) {
            return length() - 1;
        }
        long word = words[w] & (-1L >>> -(fromIndex + 1));
        while (word == 0) {
            if (w == 0) {
                return -1;
            }
            w--;
            word = words[w];
        }
        return (w << 6) + 63 - Long.numberOfLeadingZeros(word);
    }

    public int previousClearBit(int fromIndex) {
        if (fromIndex < 0) {
            if (fromIndex == -1) {
                return -1;
            }
            throw new IndexOutOfBoundsException("fromIndex < -1: " + fromIndex);
        }
        int w = fromIndex >> 6;
        if (w >= inUse) {
            return fromIndex;
        }
        long word = ~words[w] & (-1L >>> -(fromIndex + 1));
        while (word == 0) {
            if (w == 0) {
                return -1;
            }
            w--;
            word = ~words[w];
        }
        return (w << 6) + 63 - Long.numberOfLeadingZeros(word);
    }

    public int length() {
        if (inUse == 0) {
            return 0;
        }
        return ((inUse - 1) << 6) + 64 - Long.numberOfLeadingZeros(words[inUse - 1]);
    }

    public boolean isEmpty() {
        return inUse == 0;
    }

    public boolean intersects(BitSet set) {
        int n = Math.min(inUse, set.inUse);
        for (int i = 0; i < n; i++) {
            if ((words[i] & set.words[i]) != 0) {
                return true;
            }
        }
        return false;
    }

    public int cardinality() {
        int count = 0;
        for (int i = 0; i < inUse; i++) {
            count += Long.bitCount(words[i]);
        }
        return count;
    }

    public void and(BitSet set) {
        int n = Math.min(inUse, set.inUse);
        for (int i = n; i < inUse; i++) {
            words[i] = 0;
        }
        for (int i = 0; i < n; i++) {
            words[i] &= set.words[i];
        }
        settle(n);
    }

    public void or(BitSet set) {
        int n = set.inUse;
        room(n);
        for (int i = 0; i < n; i++) {
            words[i] |= set.words[i];
        }
        if (inUse < n) {
            inUse = n;
        }
    }

    public void xor(BitSet set) {
        int n = set.inUse;
        room(n);
        for (int i = 0; i < n; i++) {
            words[i] ^= set.words[i];
        }
        settle(Math.max(inUse, n));
    }

    public void andNot(BitSet set) {
        int n = Math.min(inUse, set.inUse);
        for (int i = 0; i < n; i++) {
            words[i] &= ~set.words[i];
        }
        settle(inUse);
    }

    public int hashCode() {
        long h = 1234;
        for (int i = inUse; --i >= 0;) {
            h ^= words[i] * (i + 1);
        }
        return (int) ((h >> 32) ^ h);
    }

    public int size() {
        return words.length << 6;
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof BitSet)) {
            return false;
        }
        BitSet set = (BitSet) obj;
        if (set.inUse != inUse) {
            return false;
        }
        for (int i = 0; i < inUse; i++) {
            if (words[i] != set.words[i]) {
                return false;
            }
        }
        return true;
    }

    public Object clone() {
        BitSet copy = (BitSet) rontolisp.internal.Support.copy(this);
        copy.words = Arrays.copyOf(words, given ? words.length : inUse);
        return copy;
    }

    public String toString() {
        StringBuilder text = new StringBuilder("{");
        int i = nextSetBit(0);
        while (i >= 0) {
            if (text.length() > 1) {
                text.append(", ");
            }
            text.append(i);
            i = i == Integer.MAX_VALUE ? -1 : nextSetBit(i + 1);
        }
        return text.append('}').toString();
    }

    // The set bits, read from the set as the stream is walked; its count is the
    // cardinality when the stream asks for it.
    public IntStream stream() {
        BitSet set = this;
        return IntFlow.over(new PrimitiveIterator.OfInt() {
            // the index to look from, or -1 past the last bit there can be
            private int from;

            private int next = -2;

            public boolean hasNext() {
                if (next == -2) {
                    next = from < 0 ? -1 : set.nextSetBit(from);
                }
                return next >= 0;
            }

            public int nextInt() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int bit = next;
                from = bit == Integer.MAX_VALUE ? -1 : bit + 1;
                next = -2;
                return bit;
            }
        }, () -> set.cardinality());
    }
}
