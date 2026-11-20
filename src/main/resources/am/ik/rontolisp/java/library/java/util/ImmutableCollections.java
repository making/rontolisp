// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import rontolisp.internal.Splits;

// The collections List.of, Set.of and Map.of answer: nothing changes them.
final class ImmutableCollections {

    // What the spliterator of each reports, as a JDK's does: the held collection's, the
    // default of its interface, or a collection's of one element (List.of(e), Set.of(e)).
    static final int HELD = 0;

    static final int OWN = 1;

    static final int ONE = 3;

    private ImmutableCollections() {
    }

    static <E> Spliterator<E> split(Collection<E> self, int how, int own) {
        if (how == ONE) {
            return Splits.counted(self, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED
                    | Spliterator.SUBSIZED | Spliterator.IMMUTABLE | Spliterator.NONNULL);
        }
        return Splits.counted(self, own);
    }

    // The list of List.of and List.copyOf: it holds no null, refuses a null asked for,
    // and refuses every change at once. An index outside it is its array's.
    static class ListN<E> extends AbstractList<E> implements RandomAccess {

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

        // refuses every change at once, as the list does
        public List<E> reversed() {
            return ReversedList.of(this, false);
        }

        public int size() {
            return held.length;
        }

        public E get(int index) {
            return (E) held[index];
        }

        public int indexOf(Object o) {
            return super.indexOf(Objects.requireNonNull(o));
        }

        public int lastIndexOf(Object o) {
            return super.lastIndexOf(Objects.requireNonNull(o));
        }

        public ListIterator<E> listIterator(int index) {
            if (index < 0 || index > held.length) {
                throw new IndexOutOfBoundsException("Index: " + index + " Size: " + held.length);
            }
            return super.listIterator(index);
        }

        // a part of the elements, which is a list of its own
        public List<E> subList(int from, int to) {
            if (from < 0) {
                throw new IndexOutOfBoundsException("fromIndex = " + from);
            }
            if (to > held.length) {
                throw new IndexOutOfBoundsException("toIndex = " + to);
            }
            if (from > to) {
                throw new IllegalArgumentException("fromIndex(" + from + ") > toIndex(" + to + ")");
            }
            Object[] part = new Object[to - from];
            System.arraycopy(held, from, part, 0, to - from);
            return new ImmutableCollections.SubList<>(part);
        }

        // the array's elements from one index to another, as a view: what
        // Spliterators.spliterator walks
        List<E> range(int from, int to) {
            return super.subList(from, to);
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

        public boolean addAll(int index, Collection<? extends E> c) {
            throw new UnsupportedOperationException();
        }

        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        public boolean removeIf(Predicate<? super E> filter) {
            throw new UnsupportedOperationException();
        }

        public void replaceAll(UnaryOperator<E> operator) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        public void sort(Comparator<? super E> c) {
            throw new UnsupportedOperationException();
        }

        public E removeFirst() {
            throw new UnsupportedOperationException();
        }

        public E removeLast() {
            throw new UnsupportedOperationException();
        }

        public Object[] toArray() {
            Object[] out = new Object[held.length];
            System.arraycopy(held, 0, out, 0, held.length);
            return out;
        }
    }

    // A part of such a list, a list of its own: an index outside it is refused with
    // checkIndex's words.
    static final class SubList<E> extends ListN<E> {

        SubList(Object[] held) {
            super(held, OWN);
        }

        public E get(int index) {
            Objects.checkIndex(index, size());
            return super.get(index);
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

        final Collection<E> held;

        final int how;

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

        public boolean addAll(Collection<? extends E> c) {
            throw new UnsupportedOperationException();
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

        public boolean removeIf(Predicate<? super E> filter) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }
    }

    // A set is equal to a set of the same elements: one of these is to its held set's.
    // The set of Set.of refuses a null asked for.
    static class SetN<E> extends Unchanging<E> implements Set<E> {

        SetN(Set<E> held, int how) {
            super(held, how);
        }

        public boolean contains(Object o) {
            if (how != HELD) {
                Objects.requireNonNull(o);
            }
            return held.contains(o);
        }

        public boolean equals(Object o) {
            return o == this || held.equals(o);
        }

        public int hashCode() {
            return held.hashCode();
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

        // a view of the part, which changes nothing either
        public List<E> subList(int from, int to) {
            return new ListView<>(held.subList(from, to));
        }

        public boolean addAll(Collection<? extends E> c) {
            throw new UnsupportedOperationException();
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            throw new UnsupportedOperationException();
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

        public boolean removeIf(Predicate<? super E> filter) {
            throw new UnsupportedOperationException();
        }

        public void replaceAll(UnaryOperator<E> operator) {
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

        // a null asked of the map of Map.of is refused
        private void asked(Object o) {
            if (how != HELD) {
                Objects.requireNonNull(o);
            }
        }

        public boolean containsKey(Object key) {
            asked(key);
            return held.containsKey(key);
        }

        public boolean containsValue(Object value) {
            asked(value);
            return held.containsValue(value);
        }

        public V get(Object key) {
            asked(key);
            return held.get(key);
        }

        public V getOrDefault(Object key, V defaultValue) {
            asked(key);
            return held.getOrDefault(key, defaultValue);
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

        // what may change the map is refused even where it would not
        public V replace(K key, V value) {
            throw new UnsupportedOperationException();
        }

        public void putAll(Map<? extends K, ? extends V> m) {
            throw new UnsupportedOperationException();
        }

        public V putIfAbsent(K key, V value) {
            throw new UnsupportedOperationException();
        }

        public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
            throw new UnsupportedOperationException();
        }

        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            throw new UnsupportedOperationException();
        }

        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            throw new UnsupportedOperationException();
        }

        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
            throw new UnsupportedOperationException();
        }

        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        // the keys and values of Map.of's map are AbstractMap's, whose changes go through
        // the entries' iterator, which removes nothing
        public Set<K> keySet() {
            return how == HELD ? new SetN<>(held.keySet(), how) : super.keySet();
        }

        public Collection<V> values() {
            return how == HELD ? new Unchanging<>(held.values(), how) : super.values();
        }

        public Set<Map.Entry<K, V>> entrySet() {
            Set<Map.Entry<K, V>> entries = held.entrySet();
            ArrayList<Map.Entry<K, V>> ordered = new ArrayList<>();
            for (Map.Entry<K, V> entry : entries) {
                ordered.add(new Pair<>(entry.getKey(), entry.getValue()));
            }
            return new Entries<>(ordered, how == OWN && ordered.size() == 1 ? ONE : how, entries);
        }
    }

    // The entries of an unchanging map, in the map's order: a set equal to what the map's
    // own entries are equal to.
    static final class Entries<E> extends Unchanging<E> implements Set<E> {

        // the map's own entries
        private final Set<? extends Map.Entry<?, ?>> viewed;

        Entries(Collection<E> held, int how, Set<? extends Map.Entry<?, ?>> viewed) {
            super(held, how);
            this.viewed = viewed;
        }

        public Spliterator<E> spliterator() {
            return how == HELD ? new Paired<E>(viewed.spliterator()) : super.spliterator();
        }

        public boolean equals(Object o) {
            return o == this || viewed.equals(o);
        }

        public int hashCode() {
            return viewed.hashCode();
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
    static class SortedView<E> extends SetN<E> implements SortedSet<E> {

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

    // Collections.unmodifiableSequencedCollection: its ends are the held collection's,
    // reversed it is a view of the held collection reversed.
    static class SequencedView<E> extends Unchanging<E> implements SequencedCollection<E> {

        final SequencedCollection<E> sequenced;

        SequencedView(SequencedCollection<E> sequenced) {
            super(sequenced, HELD);
            this.sequenced = sequenced;
        }

        public SequencedCollection<E> reversed() {
            return new SequencedView<>(sequenced.reversed());
        }

        public E getFirst() {
            return sequenced.getFirst();
        }

        public E getLast() {
            return sequenced.getLast();
        }

        public E removeFirst() {
            throw new UnsupportedOperationException();
        }

        public E removeLast() {
            throw new UnsupportedOperationException();
        }
    }

    // Collections.unmodifiableSequencedSet: equal to a set of the same elements.
    static final class SequencedSetView<E> extends SequencedView<E> implements SequencedSet<E> {

        SequencedSetView(SequencedSet<E> sequenced) {
            super(sequenced);
        }

        public SequencedSet<E> reversed() {
            return new SequencedSetView<>((SequencedSet<E>) sequenced.reversed());
        }

        public boolean equals(Object o) {
            return o == this || held.equals(o);
        }

        public int hashCode() {
            return held.hashCode();
        }
    }

    // Collections.unmodifiableSequencedMap: an entry at an end is a copy nothing changes.
    static final class SequencedMapView<K, V> extends MapN<K, V> implements SequencedMap<K, V> {

        private final SequencedMap<K, V> sequenced;

        SequencedMapView(SequencedMap<K, V> sequenced) {
            super(sequenced, HELD);
            this.sequenced = sequenced;
        }

        public SequencedMap<K, V> reversed() {
            return new SequencedMapView<>(sequenced.reversed());
        }

        private static <K, V> Map.Entry<K, V> fixed(Map.Entry<K, V> entry) {
            return entry == null ? null : new Pair<>(entry.getKey(), entry.getValue());
        }

        public Map.Entry<K, V> firstEntry() {
            return fixed(sequenced.firstEntry());
        }

        public Map.Entry<K, V> lastEntry() {
            return fixed(sequenced.lastEntry());
        }

        public Map.Entry<K, V> pollFirstEntry() {
            throw new UnsupportedOperationException();
        }

        public Map.Entry<K, V> pollLastEntry() {
            throw new UnsupportedOperationException();
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
