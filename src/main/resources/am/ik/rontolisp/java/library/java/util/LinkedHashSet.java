// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import rontolisp.internal.Splits;

// A set over the keys of a LinkedHashMap, so in the order its elements were added;
// addFirst and addLast are the map's putFirst and putLast.
public class LinkedHashSet<E> extends HashSet<E> implements SequencedSet<E> {

    public LinkedHashSet() {
        super(new LinkedHashMap<E, Boolean>());
    }

    public LinkedHashSet(int capacity) {
        super(new LinkedHashMap<E, Boolean>(capacity));
    }

    public LinkedHashSet(int capacity, float loadFactor) {
        super(new LinkedHashMap<E, Boolean>(capacity, loadFactor));
    }

    public LinkedHashSet(Collection<? extends E> c) {
        super(new LinkedHashMap<E, Boolean>());
        addAll(c);
    }

    public static <T> LinkedHashSet<T> newLinkedHashSet(int numElements) {
        return new LinkedHashSet<>(HashSet.capacityFor(numElements));
    }

    public Spliterator<E> spliterator() {
        return Splits.of(this, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED
                | Spliterator.SUBSIZED);
    }

    private LinkedHashMap<E, Boolean> linked() {
        return (LinkedHashMap<E, Boolean>) map;
    }

    public void addFirst(E e) {
        linked().putFirst(e, Boolean.TRUE);
    }

    public void addLast(E e) {
        linked().putLast(e, Boolean.TRUE);
    }

    public E getFirst() {
        LinkedHashMap.Linked<E, Boolean> first = linked().head;
        if (first == null) {
            throw new NoSuchElementException();
        }
        return first.key;
    }

    public E getLast() {
        LinkedHashMap.Linked<E, Boolean> last = linked().tail;
        if (last == null) {
            throw new NoSuchElementException();
        }
        return last.key;
    }

    public E removeFirst() {
        E e = getFirst();
        remove(e);
        return e;
    }

    public E removeLast() {
        E e = getLast();
        remove(e);
        return e;
    }

    public SequencedSet<E> reversed() {
        return new Reversed<>(this);
    }

    // The view reversed() answers: the set's elements walked back, an end the set's other.
    static final class Reversed<E> extends AbstractSet<E> implements SequencedSet<E> {

        private final LinkedHashSet<E> set;

        Reversed(LinkedHashSet<E> set) {
            this.set = set;
        }

        public SequencedSet<E> reversed() {
            return set;
        }

        public int size() {
            return set.size();
        }

        public boolean isEmpty() {
            return set.isEmpty();
        }

        public boolean contains(Object o) {
            return set.contains(o);
        }

        public boolean add(E e) {
            return set.add(e);
        }

        public boolean remove(Object o) {
            return set.remove(o);
        }

        public void clear() {
            set.clear();
        }

        public Iterator<E> iterator() {
            return new LinkedHashMap.Backward<E, Boolean, E>(set.linked(), 0);
        }

        public void addFirst(E e) {
            set.addLast(e);
        }

        public void addLast(E e) {
            set.addFirst(e);
        }

        public E getFirst() {
            return set.getLast();
        }

        public E getLast() {
            return set.getFirst();
        }

        public E removeFirst() {
            return set.removeLast();
        }

        public E removeLast() {
            return set.removeFirst();
        }
    }
}
