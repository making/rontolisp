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

    static final class MapOf<K, V> implements Map<K, V> {

        private final Map<K, V> held;

        MapOf(Map<K, V> held) {
            if (held == null) {
                throw new NullPointerException();
            }
            this.held = held;
        }

        public synchronized int size() {
            return held.size();
        }

        public synchronized boolean isEmpty() {
            return held.isEmpty();
        }

        public synchronized boolean containsKey(Object key) {
            return held.containsKey(key);
        }

        public synchronized boolean containsValue(Object value) {
            return held.containsValue(value);
        }

        public synchronized V get(Object key) {
            return held.get(key);
        }

        public synchronized V put(K key, V value) {
            return held.put(key, value);
        }

        public synchronized V remove(Object key) {
            return held.remove(key);
        }

        public synchronized void putAll(Map<? extends K, ? extends V> m) {
            held.putAll(m);
        }

        public synchronized void clear() {
            held.clear();
        }

        public synchronized Set<K> keySet() {
            return new SetOf<>(held.keySet(), this);
        }

        public synchronized Collection<V> values() {
            return new Each<>(held.values(), this);
        }

        public synchronized Set<Map.Entry<K, V>> entrySet() {
            return new SetOf<>(held.entrySet(), this);
        }

        public synchronized V getOrDefault(Object key, V other) {
            return held.getOrDefault(key, other);
        }

        public synchronized void forEach(BiConsumer<? super K, ? super V> action) {
            held.forEach(action);
        }

        public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            held.replaceAll(function);
        }

        public synchronized V putIfAbsent(K key, V value) {
            return held.putIfAbsent(key, value);
        }

        public synchronized V replace(K key, V value) {
            return held.replace(key, value);
        }

        public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
            return held.computeIfAbsent(key, function);
        }

        public synchronized V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            return held.computeIfPresent(key, function);
        }

        public synchronized V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            return held.compute(key, function);
        }

        public synchronized V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
            return held.merge(key, value, function);
        }

        public synchronized boolean equals(Object o) {
            return o == this || held.equals(o);
        }

        public synchronized int hashCode() {
            return held.hashCode();
        }

        public synchronized String toString() {
            return held.toString();
        }
    }
}
