// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

// What Collections.synchronizedList and the like answer: every member holds one monitor
// -- the wrapper's own, or the map's for a view of a map -- around the same member of
// the collection wrapped. An iterator is the wrapped collection's and holds nothing: the
// program walking one holds the wrapper's monitor itself.
final class Synchronized {

    private Synchronized() {
    }

    static class Each<E> implements Collection<E> {

        final Collection<E> held;

        final Object lock;

        Each(Collection<E> held, Object lock) {
            if (held == null) {
                throw new NullPointerException();
            }
            this.held = held;
            this.lock = lock == null ? this : lock;
        }

        public int size() {
            synchronized (lock) {
                return held.size();
            }
        }

        public boolean isEmpty() {
            synchronized (lock) {
                return held.isEmpty();
            }
        }

        public boolean contains(Object o) {
            synchronized (lock) {
                return held.contains(o);
            }
        }

        public Object[] toArray() {
            synchronized (lock) {
                return held.toArray();
            }
        }

        public <T> T[] toArray(T[] a) {
            synchronized (lock) {
                return held.toArray(a);
            }
        }

        public boolean add(E e) {
            synchronized (lock) {
                return held.add(e);
            }
        }

        public boolean remove(Object o) {
            synchronized (lock) {
                return held.remove(o);
            }
        }

        public boolean containsAll(Collection<?> c) {
            synchronized (lock) {
                return held.containsAll(c);
            }
        }

        public boolean addAll(Collection<? extends E> c) {
            synchronized (lock) {
                return held.addAll(c);
            }
        }

        public boolean removeAll(Collection<?> c) {
            synchronized (lock) {
                return held.removeAll(c);
            }
        }

        public boolean retainAll(Collection<?> c) {
            synchronized (lock) {
                return held.retainAll(c);
            }
        }

        public boolean removeIf(Predicate<? super E> filter) {
            synchronized (lock) {
                return held.removeIf(filter);
            }
        }

        public void clear() {
            synchronized (lock) {
                held.clear();
            }
        }

        public void forEach(Consumer<? super E> action) {
            synchronized (lock) {
                held.forEach(action);
            }
        }

        public Iterator<E> iterator() {
            return held.iterator();
        }

        // to be held in the lock by the caller, as the iterator is
        public Spliterator<E> spliterator() {
            return held.spliterator();
        }

        public String toString() {
            synchronized (lock) {
                return held.toString();
            }
        }
    }

    static final class SetOf<E> extends Each<E> implements Set<E> {

        SetOf(Set<E> held, Object lock) {
            super(held, lock);
        }

        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            synchronized (lock) {
                return held.equals(o);
            }
        }

        public int hashCode() {
            synchronized (lock) {
                return held.hashCode();
            }
        }
    }

    static final class Listed<E> extends Each<E> implements List<E> {

        private final List<E> list;

        Listed(List<E> list, Object lock) {
            super(list, lock);
            this.list = list;
        }

        public E get(int index) {
            synchronized (lock) {
                return list.get(index);
            }
        }

        public E set(int index, E element) {
            synchronized (lock) {
                return list.set(index, element);
            }
        }

        public void add(int index, E element) {
            synchronized (lock) {
                list.add(index, element);
            }
        }

        public boolean addAll(int index, Collection<? extends E> c) {
            synchronized (lock) {
                return list.addAll(index, c);
            }
        }

        public E remove(int index) {
            synchronized (lock) {
                return list.remove(index);
            }
        }

        public int indexOf(Object o) {
            synchronized (lock) {
                return list.indexOf(o);
            }
        }

        public int lastIndexOf(Object o) {
            synchronized (lock) {
                return list.lastIndexOf(o);
            }
        }

        public List<E> subList(int from, int to) {
            synchronized (lock) {
                return new Listed<>(list.subList(from, to), lock);
            }
        }

        public ListIterator<E> listIterator() {
            return list.listIterator();
        }

        public ListIterator<E> listIterator(int index) {
            return list.listIterator(index);
        }

        public void sort(Comparator<? super E> c) {
            synchronized (lock) {
                list.sort(c);
            }
        }

        public void replaceAll(UnaryOperator<E> operator) {
            synchronized (lock) {
                list.replaceAll(operator);
            }
        }

        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            synchronized (lock) {
                return list.equals(o);
            }
        }

        public int hashCode() {
            synchronized (lock) {
                return list.hashCode();
            }
        }
    }

    static class MapOf<K, V> implements Map<K, V> {

        private final Map<K, V> held;

        final Object lock;

        MapOf(Map<K, V> held, Object lock) {
            if (held == null) {
                throw new NullPointerException();
            }
            this.held = held;
            this.lock = lock == null ? this : lock;
        }

        public int size() {
            synchronized (lock) {
                return held.size();
            }
        }

        public boolean isEmpty() {
            synchronized (lock) {
                return held.isEmpty();
            }
        }

        public boolean containsKey(Object key) {
            synchronized (lock) {
                return held.containsKey(key);
            }
        }

        public boolean containsValue(Object value) {
            synchronized (lock) {
                return held.containsValue(value);
            }
        }

        public V get(Object key) {
            synchronized (lock) {
                return held.get(key);
            }
        }

        public V put(K key, V value) {
            synchronized (lock) {
                return held.put(key, value);
            }
        }

        public V remove(Object key) {
            synchronized (lock) {
                return held.remove(key);
            }
        }

        public void putAll(Map<? extends K, ? extends V> m) {
            synchronized (lock) {
                held.putAll(m);
            }
        }

        public void clear() {
            synchronized (lock) {
                held.clear();
            }
        }

        public Set<K> keySet() {
            synchronized (lock) {
                return new SetOf<>(held.keySet(), lock);
            }
        }

        public Collection<V> values() {
            synchronized (lock) {
                return new Each<>(held.values(), lock);
            }
        }

        public Set<Map.Entry<K, V>> entrySet() {
            synchronized (lock) {
                return new SetOf<>(held.entrySet(), lock);
            }
        }

        public V getOrDefault(Object key, V other) {
            synchronized (lock) {
                return held.getOrDefault(key, other);
            }
        }

        public void forEach(BiConsumer<? super K, ? super V> action) {
            synchronized (lock) {
                held.forEach(action);
            }
        }

        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            synchronized (lock) {
                held.replaceAll(function);
            }
        }

        public V putIfAbsent(K key, V value) {
            synchronized (lock) {
                return held.putIfAbsent(key, value);
            }
        }

        public boolean remove(Object key, Object value) {
            synchronized (lock) {
                return held.remove(key, value);
            }
        }

        public boolean replace(K key, V oldValue, V newValue) {
            synchronized (lock) {
                return held.replace(key, oldValue, newValue);
            }
        }

        public V replace(K key, V value) {
            synchronized (lock) {
                return held.replace(key, value);
            }
        }

        public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
            synchronized (lock) {
                return held.computeIfAbsent(key, function);
            }
        }

        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            synchronized (lock) {
                return held.computeIfPresent(key, function);
            }
        }

        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            synchronized (lock) {
                return held.compute(key, function);
            }
        }

        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
            synchronized (lock) {
                return held.merge(key, value, function);
            }
        }

        public boolean equals(Object o) {
            synchronized (lock) {
                return o == this || held.equals(o);
            }
        }

        public int hashCode() {
            synchronized (lock) {
                return held.hashCode();
            }
        }

        public String toString() {
            synchronized (lock) {
                return held.toString();
            }
        }
    }

    static class SortedSetOf<E> extends Each<E> implements SortedSet<E> {

        private final SortedSet<E> sorted;

        SortedSetOf(SortedSet<E> sorted, Object lock) {
            super(sorted, lock);
            this.sorted = sorted;
        }

        public Comparator<? super E> comparator() {
            synchronized (lock) {
                return sorted.comparator();
            }
        }

        public E first() {
            synchronized (lock) {
                return sorted.first();
            }
        }

        public E last() {
            synchronized (lock) {
                return sorted.last();
            }
        }

        public SortedSet<E> subSet(E fromElement, E toElement) {
            synchronized (lock) {
                return new SortedSetOf<>(sorted.subSet(fromElement, toElement), lock);
            }
        }

        public SortedSet<E> headSet(E toElement) {
            synchronized (lock) {
                return new SortedSetOf<>(sorted.headSet(toElement), lock);
            }
        }

        public SortedSet<E> tailSet(E fromElement) {
            synchronized (lock) {
                return new SortedSetOf<>(sorted.tailSet(fromElement), lock);
            }
        }
    }

    static final class NavigableSetOf<E> extends SortedSetOf<E> implements NavigableSet<E> {

        private final NavigableSet<E> navigable;

        NavigableSetOf(NavigableSet<E> navigable, Object lock) {
            super(navigable, lock);
            this.navigable = navigable;
        }

        public E lower(E e) {
            synchronized (lock) {
                return navigable.lower(e);
            }
        }

        public E floor(E e) {
            synchronized (lock) {
                return navigable.floor(e);
            }
        }

        public E ceiling(E e) {
            synchronized (lock) {
                return navigable.ceiling(e);
            }
        }

        public E higher(E e) {
            synchronized (lock) {
                return navigable.higher(e);
            }
        }

        public E pollFirst() {
            synchronized (lock) {
                return navigable.pollFirst();
            }
        }

        public E pollLast() {
            synchronized (lock) {
                return navigable.pollLast();
            }
        }

        public NavigableSet<E> descendingSet() {
            synchronized (lock) {
                return new NavigableSetOf<>(navigable.descendingSet(), lock);
            }
        }

        public Iterator<E> descendingIterator() {
            return navigable.descendingIterator();
        }

        public NavigableSet<E> subSet(E fromElement, boolean fromInclusive, E toElement, boolean toInclusive) {
            synchronized (lock) {
                return new NavigableSetOf<>(navigable.subSet(fromElement, fromInclusive, toElement, toInclusive),
                        lock);
            }
        }

        public NavigableSet<E> headSet(E toElement, boolean inclusive) {
            synchronized (lock) {
                return new NavigableSetOf<>(navigable.headSet(toElement, inclusive), lock);
            }
        }

        public NavigableSet<E> tailSet(E fromElement, boolean inclusive) {
            synchronized (lock) {
                return new NavigableSetOf<>(navigable.tailSet(fromElement, inclusive), lock);
            }
        }
    }

    static class SortedMapOf<K, V> extends MapOf<K, V> implements SortedMap<K, V> {

        private final SortedMap<K, V> sorted;

        SortedMapOf(SortedMap<K, V> sorted, Object lock) {
            super(sorted, lock);
            this.sorted = sorted;
        }

        public Comparator<? super K> comparator() {
            synchronized (lock) {
                return sorted.comparator();
            }
        }

        public K firstKey() {
            synchronized (lock) {
                return sorted.firstKey();
            }
        }

        public K lastKey() {
            synchronized (lock) {
                return sorted.lastKey();
            }
        }

        public SortedMap<K, V> subMap(K fromKey, K toKey) {
            synchronized (lock) {
                return new SortedMapOf<>(sorted.subMap(fromKey, toKey), lock);
            }
        }

        public SortedMap<K, V> headMap(K toKey) {
            synchronized (lock) {
                return new SortedMapOf<>(sorted.headMap(toKey), lock);
            }
        }

        public SortedMap<K, V> tailMap(K fromKey) {
            synchronized (lock) {
                return new SortedMapOf<>(sorted.tailMap(fromKey), lock);
            }
        }
    }

    static final class NavigableMapOf<K, V> extends SortedMapOf<K, V> implements NavigableMap<K, V> {

        private final NavigableMap<K, V> navigable;

        NavigableMapOf(NavigableMap<K, V> navigable, Object lock) {
            super(navigable, lock);
            this.navigable = navigable;
        }

        public Map.Entry<K, V> lowerEntry(K key) {
            synchronized (lock) {
                return navigable.lowerEntry(key);
            }
        }

        public K lowerKey(K key) {
            synchronized (lock) {
                return navigable.lowerKey(key);
            }
        }

        public Map.Entry<K, V> floorEntry(K key) {
            synchronized (lock) {
                return navigable.floorEntry(key);
            }
        }

        public K floorKey(K key) {
            synchronized (lock) {
                return navigable.floorKey(key);
            }
        }

        public Map.Entry<K, V> ceilingEntry(K key) {
            synchronized (lock) {
                return navigable.ceilingEntry(key);
            }
        }

        public K ceilingKey(K key) {
            synchronized (lock) {
                return navigable.ceilingKey(key);
            }
        }

        public Map.Entry<K, V> higherEntry(K key) {
            synchronized (lock) {
                return navigable.higherEntry(key);
            }
        }

        public K higherKey(K key) {
            synchronized (lock) {
                return navigable.higherKey(key);
            }
        }

        public Map.Entry<K, V> firstEntry() {
            synchronized (lock) {
                return navigable.firstEntry();
            }
        }

        public Map.Entry<K, V> lastEntry() {
            synchronized (lock) {
                return navigable.lastEntry();
            }
        }

        public Map.Entry<K, V> pollFirstEntry() {
            synchronized (lock) {
                return navigable.pollFirstEntry();
            }
        }

        public Map.Entry<K, V> pollLastEntry() {
            synchronized (lock) {
                return navigable.pollLastEntry();
            }
        }

        public NavigableMap<K, V> descendingMap() {
            synchronized (lock) {
                return new NavigableMapOf<>(navigable.descendingMap(), lock);
            }
        }

        public Set<K> keySet() {
            return navigableKeySet();
        }

        public NavigableSet<K> navigableKeySet() {
            synchronized (lock) {
                return new NavigableSetOf<>(navigable.navigableKeySet(), lock);
            }
        }

        public NavigableSet<K> descendingKeySet() {
            synchronized (lock) {
                return new NavigableSetOf<>(navigable.descendingKeySet(), lock);
            }
        }

        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            synchronized (lock) {
                return new NavigableMapOf<>(navigable.subMap(fromKey, fromInclusive, toKey, toInclusive), lock);
            }
        }

        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            synchronized (lock) {
                return new NavigableMapOf<>(navigable.headMap(toKey, inclusive), lock);
            }
        }

        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            synchronized (lock) {
                return new NavigableMapOf<>(navigable.tailMap(fromKey, inclusive), lock);
            }
        }
    }
}
