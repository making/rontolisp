// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The view SortedMap.reversed() answers for a sorted map that is not navigable: the map
// by the reverse of its order, walked from its last key back, each next key the last of
// the map's head before the one given. A range of the view is a first key (from, kept)
// and one it stops before (to), in the view's order, either null where there is none; as
// a JDK 25's view does, it refuses a range that ends before it starts, and a range of a
// range refuses a bound outside it. An entry it gives sets its value through put.
final class ReversedSortedMap<K, V> extends AbstractMap<K, V> implements SortedMap<K, V> {

    final SortedMap<K, V> map;

    // the view's order, the reverse of the map's
    private final Comparator<? super K> order;

    private final K from;

    private final K to;

    ReversedSortedMap(SortedMap<K, V> map) {
        this(map, Collections.reverseOrder(map.comparator()), null, null);
    }

    private ReversedSortedMap(SortedMap<K, V> map, Comparator<? super K> order, K from, K to) {
        this.map = map;
        this.order = order;
        this.from = from;
        this.to = to;
    }

    private boolean bounded() {
        return from != null || to != null;
    }

    private boolean inRange(K key) {
        return (from == null || order.compare(from, key) <= 0) && (to == null || order.compare(key, to) < 0);
    }

    private void bound(K key) {
        if (!inRange(key)) {
            throw new IllegalArgumentException();
        }
    }

    public Comparator<? super K> comparator() {
        return order;
    }

    public SortedMap<K, V> reversed() {
        return bounded() ? new ReversedSortedMap<>(this) : map;
    }

    public int size() {
        if (!bounded()) {
            return map.size();
        }
        int count = 0;
        for (Iterator<K> each = new Walk<K, V, K>(this, 0); each.hasNext(); each.next()) {
            count++;
        }
        return count;
    }

    public boolean isEmpty() {
        return bounded() ? !new Walk<K, V, K>(this, 0).hasNext() : map.isEmpty();
    }

    public boolean containsKey(Object key) {
        return inRange((K) key) && map.containsKey(key);
    }

    public V get(Object key) {
        return inRange((K) key) ? map.get(key) : null;
    }

    public V put(K key, V value) {
        bound(key);
        return map.put(key, value);
    }

    public V remove(Object key) {
        return inRange((K) key) ? map.remove(key) : null;
    }

    public void clear() {
        if (bounded()) {
            super.clear();
        }
        else {
            map.clear();
        }
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new Entries<>(this);
    }

    public K firstKey() {
        return new Walk<K, V, K>(this, 0).next();
    }

    public K lastKey() {
        if (!bounded()) {
            return map.firstKey();
        }
        Iterator<K> each = new Walk<K, V, K>(this, 0);
        K last = each.next();
        while (each.hasNext()) {
            last = each.next();
        }
        return last;
    }

    public SortedMap<K, V> headMap(K toKey) {
        if (bounded()) {
            bound(toKey);
        }
        return new ReversedSortedMap<>(map, order, from, toKey);
    }

    public SortedMap<K, V> tailMap(K fromKey) {
        if (bounded()) {
            bound(fromKey);
        }
        return new ReversedSortedMap<>(map, order, fromKey, to);
    }

    public SortedMap<K, V> subMap(K fromKey, K toKey) {
        if (bounded()) {
            bound(fromKey);
            bound(toKey);
        }
        if (order.compare(fromKey, toKey) > 0) {
            throw new IllegalArgumentException();
        }
        return new ReversedSortedMap<>(map, order, fromKey, toKey);
    }

    // The entries of the view, each a key and the value it had when it was walked to.
    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {

        private final ReversedSortedMap<K, V> view;

        Entries(ReversedSortedMap<K, V> view) {
            this.view = view;
        }

        public int size() {
            return view.size();
        }

        public boolean isEmpty() {
            return view.isEmpty();
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Walk<K, V, Map.Entry<K, V>>(view, 1);
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            Object key = entry.getKey();
            return view.containsKey(key) && Objects.equals(view.get(key), entry.getValue());
        }

        public boolean remove(Object o) {
            if (!contains(o)) {
                return false;
            }
            view.remove(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    // A walked key's entry: its value set is put into the map.
    static final class Through<K, V> extends AbstractMap.SimpleEntry<K, V> {

        private final SortedMap<K, V> map;

        Through(SortedMap<K, V> map, K key, V value) {
            super(key, value);
            this.map = map;
        }

        public V setValue(V value) {
            V old = map.put(getKey(), value);
            super.setValue(value);
            return old;
        }
    }

    // The keys of the view, or with what: 1, their entries; each found when it is asked for.
    static final class Walk<K, V, T> implements Iterator<T> {

        private final ReversedSortedMap<K, V> view;

        private final int what;

        private boolean begun;

        private K given;

        private boolean found;

        private K ahead;

        private boolean removable;

        Walk(ReversedSortedMap<K, V> view, int what) {
            this.view = view;
            this.what = what;
        }

        private void find() {
            SortedMap<K, V> map = view.map;
            SortedMap<K, V> rest;
            if (begun) {
                rest = map.headMap(given);
            }
            else if (view.from != null) {
                SortedMap<K, V> above = map.tailMap(view.from);
                if (!above.isEmpty()) {
                    K least = above.firstKey();
                    if (view.order.compare(least, view.from) == 0) {
                        ahead = least;
                        found = view.to == null || view.order.compare(least, view.to) < 0;
                        return;
                    }
                }
                rest = map.headMap(view.from);
            }
            else {
                rest = map;
            }
            if (rest.isEmpty()) {
                return;
            }
            ahead = rest.lastKey();
            found = view.to == null || view.order.compare(ahead, view.to) < 0;
        }

        public boolean hasNext() {
            if (!found) {
                find();
            }
            return found;
        }

        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            found = false;
            begun = true;
            given = ahead;
            removable = true;
            if (what == 0) {
                return (T) given;
            }
            Object entry = new Through<>(view.map, given, view.map.get(given));
            return (T) entry;
        }

        public void remove() {
            if (!removable) {
                throw new IllegalStateException();
            }
            removable = false;
            view.map.remove(given);
        }
    }
}
