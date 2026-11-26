// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A set of the constants of one enum: a bit of a word for each, by its ordinal, so the
// constants are in their order. An iterator reads a word of 64 constants when it comes
// to it and gives what the word held then. A JDK names a set of more than 64 constants
// by another class; so do these (RegularEnumSet, JumboEnumSet), which hold nothing more.
public abstract class EnumSet<E extends Enum<E>> extends AbstractSet<E> implements Cloneable {

    final Class<E> elementType;

    final E[] universe;

    long[] words;

    EnumSet(Class<E> elementType, E[] universe) {
        this.elementType = elementType;
        this.universe = universe;
        this.words = new long[(universe.length + 63) >>> 6];
    }

    // the same constants in words of its own
    @SuppressWarnings("unchecked")
    public EnumSet<E> clone() {
        EnumSet<E> copy = (EnumSet<E>) rontolisp.internal.Support.copy(this);
        copy.words = words.clone();
        return copy;
    }

    private static <E extends Enum<E>> EnumSet<E> over(Class<E> elementType, E[] universe) {
        if (universe.length > 64) {
            return new JumboEnumSet<>(elementType, universe);
        }
        return new RegularEnumSet<>(elementType, universe);
    }

    public static <E extends Enum<E>> EnumSet<E> noneOf(Class<E> elementType) {
        return over(elementType, elementType.getEnumConstants());
    }

    public static <E extends Enum<E>> EnumSet<E> allOf(Class<E> elementType) {
        EnumSet<E> set = noneOf(elementType);
        set.complement();
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> copyOf(EnumSet<E> s) {
        EnumSet<E> set = over(s.elementType, s.universe);
        System.arraycopy(s.words, 0, set.words, 0, s.words.length);
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> copyOf(Collection<E> c) {
        if (c instanceof EnumSet) {
            return copyOf((EnumSet<E>) c);
        }
        Iterator<E> each = c.iterator();
        if (!each.hasNext()) {
            throw new IllegalArgumentException("Collection is empty");
        }
        EnumSet<E> set = of(each.next());
        while (each.hasNext()) {
            set.add(each.next());
        }
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> complementOf(EnumSet<E> s) {
        EnumSet<E> set = copyOf(s);
        set.complement();
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e) {
        EnumSet<E> set = noneOf(e.getDeclaringClass());
        set.add(e);
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2) {
        EnumSet<E> set = of(e1);
        set.add(e2);
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3) {
        EnumSet<E> set = of(e1, e2);
        set.add(e3);
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3, E e4) {
        EnumSet<E> set = of(e1, e2, e3);
        set.add(e4);
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> of(E e1, E e2, E e3, E e4, E e5) {
        EnumSet<E> set = of(e1, e2, e3, e4);
        set.add(e5);
        return set;
    }

    @SafeVarargs
    public static <E extends Enum<E>> EnumSet<E> of(E first, E... rest) {
        EnumSet<E> set = of(first);
        for (E e : rest) {
            set.add(e);
        }
        return set;
    }

    public static <E extends Enum<E>> EnumSet<E> range(E from, E to) {
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        EnumSet<E> set = noneOf(from.getDeclaringClass());
        for (int i = from.ordinal(); i <= to.ordinal(); i++) {
            set.words[i >>> 6] |= 1L << (i & 63);
        }
        return set;
    }

    // every constant this set does not hold, and none past the last
    private void complement() {
        for (int i = 0; i < words.length; i++) {
            words[i] = ~words[i];
        }
        int rest = universe.length & 63;
        if (rest != 0) {
            words[words.length - 1] &= (1L << rest) - 1;
        }
    }

    // the ordinal of a constant of this set's enum, or -1
    private int place(Object o) {
        if (o instanceof Enum<?> e && e.getDeclaringClass() == elementType) {
            return e.ordinal();
        }
        return -1;
    }

    public Iterator<E> iterator() {
        return new Walk<>(this);
    }

    public int size() {
        int n = 0;
        for (long word : words) {
            n += Long.bitCount(word);
        }
        return n;
    }

    public boolean isEmpty() {
        for (long word : words) {
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    public boolean contains(Object o) {
        int at = place(o);
        return at >= 0 && (words[at >>> 6] & (1L << (at & 63))) != 0;
    }

    public boolean add(E e) {
        if (e.getDeclaringClass() != elementType) {
            throw new ClassCastException(e.getClass() + " != " + elementType);
        }
        int at = e.ordinal();
        long before = words[at >>> 6];
        words[at >>> 6] = before | (1L << (at & 63));
        return words[at >>> 6] != before;
    }

    public boolean remove(Object o) {
        int at = place(o);
        if (at < 0) {
            return false;
        }
        long before = words[at >>> 6];
        words[at >>> 6] = before & ~(1L << (at & 63));
        return words[at >>> 6] != before;
    }

    public void clear() {
        for (int i = 0; i < words.length; i++) {
            words[i] = 0;
        }
    }

    static final class Walk<E extends Enum<E>> implements Iterator<E> {

        private final EnumSet<E> set;

        // the word read last, and what of it is still to give
        private int at;

        private long unseen;

        // the word and the bit given last
        private int lastWord;

        private long lastBit;

        Walk(EnumSet<E> set) {
            this.set = set;
            this.unseen = set.words.length == 0 ? 0 : set.words[0];
        }

        public boolean hasNext() {
            while (unseen == 0 && at < set.words.length - 1) {
                unseen = set.words[++at];
            }
            return unseen != 0;
        }

        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            long bit = unseen & -unseen;
            unseen -= bit;
            lastWord = at;
            lastBit = bit;
            return set.universe[(at << 6) + Long.numberOfTrailingZeros(bit)];
        }

        public void remove() {
            if (lastBit == 0) {
                throw new IllegalStateException();
            }
            set.words[lastWord] &= ~lastBit;
            lastBit = 0;
        }
    }
}
