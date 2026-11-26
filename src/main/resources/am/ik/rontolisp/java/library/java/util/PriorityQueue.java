// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import rontolisp.internal.Splits;

// A binary heap in an array (Williams, 1964): the least element at 0, the children of
// the element at k at 2k + 1 and 2k + 2. An element added climbs while it is less than
// its parent; the last element takes the place of one taken out and sinks below the
// lesser of its children, the left of two equal ones, while it is greater, or climbs. A
// queue made of a collection in no order sinks each element from the middle of the array
// to its start (Floyd, 1964), and so does what a removal of several leaves, kept in its
// order. Which of equal elements goes where, and the order an iterator that removes
// walks in, are a JDK's answers to seeded runs. The array doubles when full (this
// library's own choice: the specification states no growth and no member shows a capacity,
// so nothing observable depends on it).
public class PriorityQueue<E> extends AbstractQueue<E> implements java.io.Serializable {

    private Object[] queue;

    private int size;

    private final Comparator<? super E> comparator;

    // the changes made, which an iterator compares with those it saw
    private int changes;

    public PriorityQueue() {
        this(11, null);
    }

    public PriorityQueue(int initialCapacity) {
        this(initialCapacity, null);
    }

    public PriorityQueue(Comparator<? super E> comparator) {
        this(11, comparator);
    }

    public PriorityQueue(int initialCapacity, Comparator<? super E> comparator) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException();
        }
        this.queue = new Object[initialCapacity];
        this.comparator = comparator;
    }

    // of a sorted set or a queue its order and its elements as they are, of any other
    // collection its elements made a heap
    public PriorityQueue(Collection<? extends E> c) {
        if (c instanceof SortedSet<?> sorted) {
            this.comparator = (Comparator<? super E>) sorted.comparator();
            taken(c);
        }
        else if (c instanceof PriorityQueue<?> other) {
            this.comparator = (Comparator<? super E>) other.comparator();
            taken(c);
        }
        else {
            this.comparator = null;
            taken(c);
            heapify();
        }
    }

    public PriorityQueue(PriorityQueue<? extends E> c) {
        this.comparator = (Comparator<? super E>) c.comparator();
        taken(c);
    }

    public PriorityQueue(SortedSet<? extends E> c) {
        this.comparator = (Comparator<? super E>) c.comparator();
        taken(c);
    }

    private void taken(Collection<? extends E> c) {
        Object[] held = c.toArray();
        for (Object e : held) {
            if (e == null) {
                throw new NullPointerException();
            }
        }
        this.queue = held.length == 0 ? new Object[1] : held;
        this.size = held.length;
    }

    private int order(Object a, Object b) {
        return comparator == null ? ((Comparable<? super E>) a).compareTo((E) b) : comparator.compare((E) a, (E) b);
    }

    // x put at k, or above it where it is less than a parent
    private void up(int k, Object x) {
        Object[] held = queue;
        Comparable<? super E> key = comparator == null ? (Comparable<? super E>) x : null;
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object above = held[parent];
            if ((key != null ? key.compareTo((E) above) : comparator.compare((E) x, (E) above)) >= 0) {
                break;
            }
            held[k] = above;
            k = parent;
        }
        held[k] = x;
    }

    // x put at k, or below it where it is greater than the lesser child
    private void down(int k, Object x) {
        Object[] held = queue;
        Comparable<? super E> key = comparator == null ? (Comparable<? super E>) x : null;
        int half = size >>> 1;
        while (k < half) {
            int child = 2 * k + 1;
            Object below = held[child];
            int right = child + 1;
            if (right < size && order(below, held[right]) > 0) {
                child = right;
                below = held[child];
            }
            if ((key != null ? key.compareTo((E) below) : comparator.compare((E) x, (E) below)) <= 0) {
                break;
            }
            held[k] = below;
            k = child;
        }
        held[k] = x;
    }

    private void heapify() {
        for (int i = (size >>> 1) - 1; i >= 0; i--) {
            down(i, queue[i]);
        }
    }

    public boolean add(E e) {
        return offer(e);
    }

    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        changes++;
        int at = size;
        if (at >= queue.length) {
            queue = Arrays.copyOf(queue, at * 2);
        }
        up(at, e);
        size = at + 1;
        return true;
    }

    public E peek() {
        return (E) queue[0];
    }

    public E poll() {
        if (size == 0) {
            return null;
        }
        Object[] held = queue;
        E head = (E) held[0];
        changes++;
        int last = --size;
        Object moved = held[last];
        held[last] = null;
        if (last > 0) {
            down(0, moved);
        }
        return head;
    }

    // the element at i taken out: the last element put in its place sinks or climbs;
    // where it climbed above i it is answered, an element a walk has not given yet
    E removeAt(int i) {
        changes++;
        int last = --size;
        if (last == i) {
            queue[i] = null;
            return null;
        }
        Object moved = queue[last];
        queue[last] = null;
        down(i, moved);
        if (queue[i] == moved) {
            up(i, moved);
            if (queue[i] != moved) {
                return (E) moved;
            }
        }
        return null;
    }

    private int indexOf(Object o) {
        if (o != null) {
            for (int i = 0; i < size; i++) {
                if (o.equals(queue[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    public boolean remove(Object o) {
        int i = indexOf(o);
        if (i < 0) {
            return false;
        }
        removeAt(i);
        return true;
    }

    // the element itself, not one equal to it
    private void removeItself(Object o) {
        for (int i = 0; i < size; i++) {
            if (queue[i] == o) {
                removeAt(i);
                return;
            }
        }
    }

    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

    public int size() {
        return size;
    }

    public void clear() {
        changes++;
        for (int i = 0; i < size; i++) {
            queue[i] = null;
        }
        size = 0;
    }

    public Comparator<? super E> comparator() {
        return comparator;
    }

    public Object[] toArray() {
        return Arrays.copyOf(queue, size);
    }

    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action);
        int seen = changes;
        Object[] held = queue;
        for (int i = 0, n = size; i < n; i++) {
            action.accept((E) held[i]);
        }
        if (changes != seen) {
            throw new ConcurrentModificationException();
        }
    }

    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        return removeWhere(filter);
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeWhere(e -> c.contains(e));
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeWhere(e -> !c.contains(e));
    }

    // every element of the array there is is asked first; a change meanwhile refuses
    // them all, else those kept close up in their order and are made a heap again
    private boolean removeWhere(Predicate<? super E> filter) {
        int seen = changes;
        Object[] held = queue;
        int n = size;
        boolean[] dropped = new boolean[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (filter.test((E) held[i])) {
                dropped[i] = true;
                any = true;
            }
        }
        if (changes != seen) {
            throw new ConcurrentModificationException();
        }
        changes++;
        if (!any) {
            return false;
        }
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (!dropped[i]) {
                queue[kept++] = queue[i];
            }
        }
        for (int i = kept; i < n; i++) {
            queue[i] = null;
        }
        size = kept;
        heapify();
        return true;
    }

    public Iterator<E> iterator() {
        return new Walk<>(this);
    }

    public Spliterator<E> spliterator() {
        return Splits.counted(this, Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.NONNULL);
    }

    // The array in its order; an element a removal moved behind the walk is given after
    // the array, in the order those were moved.
    static final class Walk<E> implements Iterator<E> {

        private final PriorityQueue<E> queue;

        private int cursor;

        // the index next() gave last, -1 when it gave none or one of later
        private int last = -1;

        // the elements moved behind the walk, and the one of them given last
        private ArrayDeque<E> later;

        private E given;

        private int seen;

        Walk(PriorityQueue<E> queue) {
            this.queue = queue;
            this.seen = queue.changes;
        }

        public boolean hasNext() {
            return cursor < queue.size || (later != null && !later.isEmpty());
        }

        public E next() {
            if (seen != queue.changes) {
                throw new ConcurrentModificationException();
            }
            if (cursor < queue.size) {
                last = cursor;
                return (E) queue.queue[cursor++];
            }
            if (later != null) {
                last = -1;
                given = later.poll();
                if (given != null) {
                    return given;
                }
            }
            throw new NoSuchElementException();
        }

        public void remove() {
            if (seen != queue.changes) {
                throw new ConcurrentModificationException();
            }
            if (last != -1) {
                E moved = queue.removeAt(last);
                last = -1;
                if (moved == null) {
                    cursor--;
                }
                else {
                    if (later == null) {
                        later = new ArrayDeque<>();
                    }
                    later.add(moved);
                }
            }
            else if (given != null) {
                queue.removeItself(given);
                given = null;
            }
            else {
                throw new IllegalStateException();
            }
            seen = queue.changes;
        }
    }
}
