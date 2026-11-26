// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;

// The spliterator of the library's collections and of Spliterators: the elements an
// iterator gives. Made over a collection, it takes the collection's iterator, and its
// size where none is given, when it is first asked for an element or its size. Nothing
// splits: trySplit answers null. A counted one's estimate goes down by one an element
// given; any other keeps the estimate it took. Which collection reports what is a JDK's
// answer to a probe.
public final class Splits<T> implements Spliterator<T> {

    private final Collection<? extends T> source;

    private Iterator<? extends T> each;

    private long size;

    private final int marks;

    private final boolean counted;

    // the order a SORTED one reports: null for the elements' own
    private Comparator<? super T> order;

    // a size taken when it was made: asking for it does not take the iterator
    private boolean known;

    // size: -1 for the source's size when the iterator is taken
    public Splits(Collection<? extends T> source, Iterator<? extends T> each, long size, int marks, boolean counted) {
        this.source = source;
        this.each = each;
        this.size = size;
        this.marks = marks;
        this.counted = counted;
    }

    public static <T> Spliterator<T> of(Collection<? extends T> source, int marks) {
        return new Splits<>(source, null, -1, marks, false);
    }

    public static <T> Spliterator<T> counted(Collection<? extends T> source, int marks) {
        return new Splits<>(source, null, -1, marks, true);
    }

    // a counted one whose size is the one it has now, and which takes the collection's
    // iterator only at its first element
    public static <T> Spliterator<T> counted(Collection<? extends T> source, int marks, long size) {
        Splits<T> split = new Splits<>(source, null, size, marks, true);
        split.known = true;
        return split;
    }

    // a collection that does not know its size without walking it (a concurrent queue)
    public static <T> Spliterator<T> unsized(Collection<? extends T> source, int marks) {
        return new Splits<>(source, null, Long.MAX_VALUE, marks, false);
    }

    // the keys of a sorted map or set, of a size taken when first asked for (-1) or never
    // known (Long.MAX_VALUE)
    public static <T> Spliterator<T> sorted(Collection<? extends T> source, int marks, Comparator<? super T> order,
            long size) {
        Splits<T> split = new Splits<>(source, null, size, marks, false);
        split.order = order;
        return split;
    }

    // a collection of one element nothing changes (Collections.singleton, singletonList)
    public static <T> Spliterator<T> one(Collection<? extends T> source) {
        int marks = Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED | Spliterator.SUBSIZED
                | Spliterator.IMMUTABLE;
        return counted(source, source.contains(null) ? marks : marks | Spliterator.NONNULL);
    }

    private Iterator<? extends T> bound() {
        Iterator<? extends T> taken = each;
        if (taken == null) {
            taken = source.iterator();
            each = taken;
            if (size < 0) {
                size = source.size();
            }
        }
        return taken;
    }

    public boolean tryAdvance(Consumer<? super T> action) {
        Objects.requireNonNull(action);
        Iterator<? extends T> taken = bound();
        if (!taken.hasNext()) {
            return false;
        }
        T item = taken.next();
        if (counted && size > 0) {
            size--;
        }
        action.accept(item);
        return true;
    }

    public Spliterator<T> trySplit() {
        return null;
    }

    public long estimateSize() {
        if (!known) {
            bound();
        }
        return size;
    }

    public int characteristics() {
        return marks;
    }

    public Comparator<? super T> getComparator() {
        if ((marks & Spliterator.SORTED) != 0) {
            return order;
        }
        throw new IllegalStateException();
    }
}
