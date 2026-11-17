// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The collections List.of, Set.of and Map.of answer: nothing changes them.
final class ImmutableCollections {

    private ImmutableCollections() {
    }

    static final class ListN<E> extends AbstractList<E> {

        private final Object[] held;

        ListN(Object[] held) {
            this.held = held;
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

        Unchanging(Collection<E> held) {
            this.held = held;
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

        SetN(Set<E> held) {
            super(held);
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

    static final class MapN<K, V> extends AbstractMap<K, V> {

        private final Map<K, V> held;

        MapN(Map<K, V> held) {
            this.held = held;
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

        public void clear() {
            throw new UnsupportedOperationException();
        }

        public Set<K> keySet() {
            return new SetN<>(held.keySet());
        }

        public Collection<V> values() {
            return new Unchanging<>(held.values());
        }

        public Set<Map.Entry<K, V>> entrySet() {
            ArrayList<Map.Entry<K, V>> ordered = new ArrayList<>();
            for (Map.Entry<K, V> entry : held.entrySet()) {
                ordered.add(new Pair<>(entry.getKey(), entry.getValue()));
            }
            return new Entries<>(ordered);
        }
    }

    // The entries of an unchanging map, in the map's order.
    static final class Entries<E> extends Unchanging<E> implements Set<E> {

        Entries(Collection<E> held) {
            super(held);
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
            throw new UnsupportedOperationException();
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
