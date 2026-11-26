// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

// The views the defaults of SequencedMap answer. Each asks the map for its own view of
// the part every time it is used, refuses what is added, reaches its ends through the
// map's entries at its ends and is reversed through the reversed map.
final class MapSequence {

    private MapSequence() {
    }

    abstract static class View<K, V, E> implements SequencedCollection<E> {

        final SequencedMap<K, V> map;

        View(SequencedMap<K, V> map) {
            this.map = map;
        }

        // the map's own view of the part
        abstract Collection<E> part();

        // what an entry at an end gives of the part
        abstract E of(Map.Entry<K, V> entry);

        private E end(Map.Entry<K, V> entry) {
            if (entry == null) {
                throw new NoSuchElementException();
            }
            return of(entry);
        }

        public int size() {
            return part().size();
        }

        public boolean isEmpty() {
            return part().isEmpty();
        }

        public boolean contains(Object o) {
            return part().contains(o);
        }

        public boolean containsAll(Collection<?> c) {
            return part().containsAll(c);
        }

        public Object[] toArray() {
            return part().toArray();
        }

        public <T> T[] toArray(T[] a) {
            return part().toArray(a);
        }

        public boolean add(E e) {
            throw new UnsupportedOperationException();
        }

        public boolean addAll(Collection<? extends E> c) {
            throw new UnsupportedOperationException();
        }

        public boolean remove(Object o) {
            return part().remove(o);
        }

        public boolean removeAll(Collection<?> c) {
            return part().removeAll(c);
        }

        public boolean retainAll(Collection<?> c) {
            return part().retainAll(c);
        }

        public boolean removeIf(Predicate<? super E> filter) {
            return part().removeIf(filter);
        }

        public void clear() {
            part().clear();
        }

        public Iterator<E> iterator() {
            return part().iterator();
        }

        public void forEach(Consumer<? super E> action) {
            part().forEach(action);
        }

        public Spliterator<E> spliterator() {
            return part().spliterator();
        }

        public Stream<E> stream() {
            return part().stream();
        }

        public boolean equals(Object o) {
            return part().equals(o);
        }

        public int hashCode() {
            return part().hashCode();
        }

        public String toString() {
            return part().toString();
        }

        public E getFirst() {
            return end(map.firstEntry());
        }

        public E getLast() {
            return end(map.lastEntry());
        }

        public E removeFirst() {
            return end(map.pollFirstEntry());
        }

        public E removeLast() {
            return end(map.pollLastEntry());
        }
    }

    static final class Keys<K, V> extends View<K, V, K> implements SequencedSet<K> {

        Keys(SequencedMap<K, V> map) {
            super(map);
        }

        Collection<K> part() {
            return map.keySet();
        }

        K of(Map.Entry<K, V> entry) {
            return entry.getKey();
        }

        public SequencedSet<K> reversed() {
            return map.reversed().sequencedKeySet();
        }
    }

    static final class Values<K, V> extends View<K, V, V> {

        Values(SequencedMap<K, V> map) {
            super(map);
        }

        Collection<V> part() {
            return map.values();
        }

        V of(Map.Entry<K, V> entry) {
            return entry.getValue();
        }

        public SequencedCollection<V> reversed() {
            return map.reversed().sequencedValues();
        }
    }

    static final class Entries<K, V> extends View<K, V, Map.Entry<K, V>> implements SequencedSet<Map.Entry<K, V>> {

        Entries(SequencedMap<K, V> map) {
            super(map);
        }

        Collection<Map.Entry<K, V>> part() {
            return map.entrySet();
        }

        Map.Entry<K, V> of(Map.Entry<K, V> entry) {
            return entry;
        }

        public SequencedSet<Map.Entry<K, V>> reversed() {
            return map.reversed().sequencedEntrySet();
        }
    }
}
