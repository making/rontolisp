// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import rontolisp.internal.Splits;

// The collections List.of, Set.of and Map.of answer: nothing changes them.
final class ImmutableCollections {

    // What the spliterator of each reports, as a JDK's does: the held collection's, the
    // default of its interface, an empty collection's (Collections.empty*) or a
    // collection's of one element (Collections.singleton*, List.of(e), Set.of(e)).
    static final int HELD = 0;

    static final int OWN = 1;

    static final int EMPTY = 2;

    static final int ONE = 3;

    private ImmutableCollections() {
    }

    static <E> Spliterator<E> split(Collection<E> self, int how, int own) {
        if (how == ONE) {
            return Splits.one(self);
        }
        return Splits.counted(self, how == EMPTY ? Spliterator.SIZED | Spliterator.SUBSIZED : own);
    }

    static final class ListN<E> extends AbstractList<E> implements RandomAccess {

        private final Object[] held;

        private final int how;

        ListN(Object[] held) {
            this(held, OWN);
        }

        ListN(Object[] held, int how) {
            this.held = held;
            this.how = how;
        }

        public Spliterator<E> spliterator() {
            return split(this, how, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
        }

        public int size() {
            return held.length;
        }

        public E get(int index) {
            if (index < 0 || index >= held.length) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + held.length);
            }
            return (E) held[index];
        }

        public ListIterator<E> listIterator(int index) {
            if (index < 0 || index > held.length) {
                throw new IndexOutOfBoundsException("Index: " + index + " Size: " + held.length);
            }
            return super.listIterator(index);
        }

        public boolean add(E e) {
            throw new UnsupportedOperationException();
        }

        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        public boolean addAll(Collection<? extends E> c) {
            throw new UnsupportedOperationException();
        }

        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        public void sort(Comparator<? super E> c) {
            throw new UnsupportedOperationException();
        }

        public Object[] toArray() {
            Object[] out = new Object[held.length];
            System.arraycopy(held, 0, out, 0, held.length);
            return out;
        }
    }

    static <K, V> Map<K, V> map(Object... pairs) {
        HashMap<K, V> held = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] == null || pairs[i + 1] == null) {
                throw new NullPointerException();
            }
            if (held.put((K) pairs[i], (V) pairs[i + 1]) != null) {
                throw new IllegalArgumentException("duplicate key: " + pairs[i]);
            }
        }
        return new MapN<>(held);
    }

    // A view of a collection that reads it and changes nothing.
    static class Unchanging<E> extends AbstractCollection<E> {

        private final Collection<E> held;

        private final int how;

        Unchanging(Collection<E> held, int how) {
            this.held = held;
            this.how = how;
        }

        public Spliterator<E> spliterator() {
            if (how == HELD) {
                return held.spliterator();
            }
            if (how == OWN) {
                return Splits.of(this, this instanceof Set ? Spliterator.DISTINCT | Spliterator.SIZED
                        | Spliterator.SUBSIZED : Spliterator.SIZED | Spliterator.SUBSIZED);
            }
            return split(this, how, 0);
        }

        public int size() {
            return held.size();
        }

        public boolean contains(Object o) {
            return held.contains(o);
        }

        public Iterator<E> iterator() {
            return new Reading<>(held.iterator());
        }

        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        public boolean equals(Object o) {
            return o == this || held.equals(o);
        }

        public int hashCode() {
            return held.hashCode();
        }
    }

    static final class SetN<E> extends Unchanging<E> implements Set<E> {

        SetN(Set<E> held, int how) {
            super(held, how);
        }
    }

    // An iterator that does not remove.
    static final class Reading<E> implements Iterator<E> {

        private final Iterator<E> each;

        Reading(Iterator<E> each) {
            this.each = each;
        }

        public boolean hasNext() {
            return each.hasNext();
        }

        public E next() {
            return each.next();
        }
    }

    static final class ListView<E> extends AbstractList<E> {

        private final List<E> held;

        ListView(List<E> held) {
            this.held = held;
        }

        public int size() {
            return held.size();
        }

        public E get(int index) {
            return held.get(index);
        }

        public Spliterator<E> spliterator() {
            return held.spliterator();
        }

        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        public void sort(Comparator<? super E> c) {
            throw new UnsupportedOperationException();
        }
    }

    static class MapN<K, V> extends AbstractMap<K, V> {

        private final Map<K, V> held;

        private final int how;

        MapN(Map<K, V> held) {
            this(held, OWN);
        }

        MapN(Map<K, V> held, int how) {
            this.held = held;
            this.how = how;
        }

        public int size() {
            return held.size();
        }

        public boolean containsKey(Object key) {
            return held.containsKey(key);
        }

        public V get(Object key) {
            return held.get(key);
        }

        public V remove(Object key) {
            throw new UnsupportedOperationException();
        }

        public boolean remove(Object key, Object value) {
            throw new UnsupportedOperationException();
        }

        public boolean replace(K key, V oldValue, V newValue) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        public Set<K> keySet() {
            return new SetN<>(held.keySet(), how);
        }

        public Collection<V> values() {
            return new Unchanging<>(held.values(), how);
        }

        public Set<Map.Entry<K, V>> entrySet() {
            ArrayList<Map.Entry<K, V>> ordered = new ArrayList<>();
            for (Map.Entry<K, V> entry : held.entrySet()) {
                ordered.add(new Pair<>(entry.getKey(), entry.getValue()));
            }
            if (how == HELD) {
                return new Entries<>(ordered, held.entrySet());
            }
            return new Entries<>(ordered, how == OWN && ordered.size() == 1 ? ONE : how);
        }
    }

    // The entries of an unchanging map, in the map's order.
    static final class Entries<E> extends Unchanging<E> implements Set<E> {

        // the entries of the map an unmodifiable view holds, or null
        private final Set<? extends Map.Entry<?, ?>> viewed;

        Entries(Collection<E> held, int how) {
            super(held, how);
            this.viewed = null;
        }

        Entries(Collection<E> held, Set<? extends Map.Entry<?, ?>> viewed) {
            super(held, HELD);
            this.viewed = viewed;
        }

        public Spliterator<E> spliterator() {
            return viewed == null ? super.spliterator() : new Paired<E>(viewed.spliterator());
        }
    }

    // what the spliterator of a map's entries gives, each as an entry nothing changes
    static final class Paired<E> implements Spliterator<E> {

        private final Spliterator<? extends Map.Entry<?, ?>> held;

        Paired(Spliterator<? extends Map.Entry<?, ?>> held) {
            this.held = held;
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action);
            return held.tryAdvance(entry -> action.accept((E) new Pair<>(entry.getKey(), entry.getValue())));
        }

        public Spliterator<E> trySplit() {
            return null;
        }

        public long estimateSize() {
            return held.estimateSize();
        }

        public int characteristics() {
            return held.characteristics();
        }

        public Comparator<? super E> getComparator() {
            Comparator<?> order = held.getComparator();
            return (Comparator<? super E>) order;
        }
    }

    // Collections.unmodifiableSortedSet: the views of the set are views of this kind.
    static class SortedView<E> extends Unchanging<E> implements SortedSet<E> {

        private final SortedSet<E> sorted;

        SortedView(SortedSet<E> sorted) {
            super(sorted, HELD);
            this.sorted = sorted;
        }

        public Comparator<? super E> comparator() {
            return sorted.comparator();
        }

        public E first() {
            return sorted.first();
        }

        public E last() {
            return sorted.last();
        }

        public SortedSet<E> subSet(E fromElement, E toElement) {
            return new SortedView<>(sorted.subSet(fromElement, toElement));
        }

        public SortedSet<E> headSet(E toElement) {
            return new SortedView<>(sorted.headSet(toElement));
        }

        public SortedSet<E> tailSet(E fromElement) {
            return new SortedView<>(sorted.tailSet(fromElement));
        }
    }

    // Collections.unmodifiableNavigableSet.
    static final class NavigableView<E> extends SortedView<E> implements NavigableSet<E> {

        private final NavigableSet<E> navigable;

        NavigableView(NavigableSet<E> navigable) {
            super(navigable);
            this.navigable = navigable;
        }

        public E lower(E e) {
            return navigable.lower(e);
        }

        public E floor(E e) {
            return navigable.floor(e);
        }

        public E ceiling(E e) {
            return navigable.ceiling(e);
        }

        public E higher(E e) {
            return navigable.higher(e);
        }

        public E pollFirst() {
            throw new UnsupportedOperationException();
        }

        public E pollLast() {
            throw new UnsupportedOperationException();
        }

        public NavigableSet<E> descendingSet() {
            return new NavigableView<>(navigable.descendingSet());
        }

        public Iterator<E> descendingIterator() {
            return new Reading<>(navigable.descendingIterator());
        }

        public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
            return new NavigableView<>(navigable.subSet(fromElement, fromInclusive, toElement, toInclusive));
        }

        public NavigableSet<E> headSet(E toElement, boolean inclusive) {
            return new NavigableView<>(navigable.headSet(toElement, inclusive));
        }

        public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
            return new NavigableView<>(navigable.tailSet(fromElement, inclusive));
        }
    }

    // Collections.unmodifiableSortedMap: the views of the map are views of this kind.
    static class SortedMapView<K, V> extends MapN<K, V> implements SortedMap<K, V> {

        private final SortedMap<K, V> sorted;

        SortedMapView(SortedMap<K, V> sorted) {
            super(sorted, HELD);
            this.sorted = sorted;
        }

        public Comparator<? super K> comparator() {
            return sorted.comparator();
        }

        public K firstKey() {
            return sorted.firstKey();
        }

        public K lastKey() {
            return sorted.lastKey();
        }

        public SortedMap<K, V> subMap(K fromKey, K toKey) {
            return new SortedMapView<>(sorted.subMap(fromKey, toKey));
        }

        public SortedMap<K, V> headMap(K toKey) {
            return new SortedMapView<>(sorted.headMap(toKey));
        }

        public SortedMap<K, V> tailMap(K fromKey) {
            return new SortedMapView<>(sorted.tailMap(fromKey));
        }
    }

    // Collections.unmodifiableNavigableMap: an entry it answers is one nothing changes.
    static final class NavigableMapView<K, V> extends SortedMapView<K, V> implements NavigableMap<K, V> {

        private final NavigableMap<K, V> navigable;

        NavigableMapView(NavigableMap<K, V> navigable) {
            super(navigable);
            this.navigable = navigable;
        }

        private static <K, V> Map.Entry<K, V> fixed(Map.Entry<K, V> entry) {
            return entry == null ? null : new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue());
        }

        public Map.Entry<K, V> lowerEntry(K key) {
            return fixed(navigable.lowerEntry(key));
        }

        public K lowerKey(K key) {
            return navigable.lowerKey(key);
        }

        public Map.Entry<K, V> floorEntry(K key) {
            return fixed(navigable.floorEntry(key));
        }

        public K floorKey(K key) {
            return navigable.floorKey(key);
        }

        public Map.Entry<K, V> ceilingEntry(K key) {
            return fixed(navigable.ceilingEntry(key));
        }

        public K ceilingKey(K key) {
            return navigable.ceilingKey(key);
        }

        public Map.Entry<K, V> higherEntry(K key) {
            return fixed(navigable.higherEntry(key));
        }

        public K higherKey(K key) {
            return navigable.higherKey(key);
        }

        public Map.Entry<K, V> firstEntry() {
            return fixed(navigable.firstEntry());
        }

        public Map.Entry<K, V> lastEntry() {
            return fixed(navigable.lastEntry());
        }

        public Map.Entry<K, V> pollFirstEntry() {
            throw new UnsupportedOperationException();
        }

        public Map.Entry<K, V> pollLastEntry() {
            throw new UnsupportedOperationException();
        }

        public NavigableMap<K, V> descendingMap() {
            return new NavigableMapView<>(navigable.descendingMap());
        }

        public NavigableSet<K> navigableKeySet() {
            return new NavigableView<>(navigable.navigableKeySet());
        }

        public NavigableSet<K> descendingKeySet() {
            return new NavigableView<>(navigable.descendingKeySet());
        }

        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return new NavigableMapView<>(navigable.subMap(fromKey, fromInclusive, toKey, toInclusive));
        }

        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            return new NavigableMapView<>(navigable.headMap(toKey, inclusive));
        }

        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            return new NavigableMapView<>(navigable.tailMap(fromKey, inclusive));
        }
    }

    // A key and a value nothing changes.
    static final class Pair<K, V> implements Map.Entry<K, V> {

        private final K key;

        private final V value;

        Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public V setValue(V replacement) {
            throw new UnsupportedOperationException("not supported");
        }

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> other = (Map.Entry<?, ?>) o;
            Object theirKey = other.getKey();
            Object theirValue = other.getValue();
            return (key == null ? theirKey == null : key.equals(theirKey))
                    && (value == null ? theirValue == null : value.equals(theirValue));
        }

        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        public String toString() {
            return key + "=" + value;
        }
    }
}
