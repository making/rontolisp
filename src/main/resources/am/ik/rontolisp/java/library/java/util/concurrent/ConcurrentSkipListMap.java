// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.Spliterator;
import java.util.TreeMap;
import rontolisp.internal.Splits;

// A sorted map threads share: a TreeMap under this map's monitor (Ordered). An iterator
// gives the entry it read ahead and reads the next one by the key it gave, so it never
// refuses, may give an entry removed since it was read, and sees what was put beyond it.
public class ConcurrentSkipListMap<K, V> extends Ordered<K, V> implements Cloneable {

    public ConcurrentSkipListMap() {
        super(null, new TreeMap<K, V>(), null, true, null, false, true, null, false, false);
    }

    public ConcurrentSkipListMap(Comparator<? super K> comparator) {
        super(null, new TreeMap<K, V>(comparator), null, true, null, false, true, null, false, false);
    }

    public ConcurrentSkipListMap(Map<? extends K, ? extends V> m) {
        this();
        putAll(m);
    }

    public ConcurrentSkipListMap(SortedMap<K, ? extends V> m) {
        this(m.comparator());
        putAll(m);
    }

    // the same keys and values in a tree of its own, under a monitor of its own
    @SuppressWarnings("unchecked")
    public ConcurrentSkipListMap<K, V> clone() {
        synchronized (lock) {
            ConcurrentSkipListMap<K, V> copy = (ConcurrentSkipListMap<K, V>) rontolisp.internal.Support.copy(this);
            copy.lock = copy;
            copy.tree = (TreeMap<K, V>) tree.clone();
            copy.engine = copy.tree;
            return copy;
        }
    }

    static final class SubMap<K, V> extends Ordered<K, V> {

        SubMap(Object lock, TreeMap<K, V> tree, NavigableMap<K, V> engine, boolean fromStart, K lo,
                boolean loInclusive, boolean toEnd, K hi, boolean hiInclusive, boolean descending) {
            super(lock, tree, engine, fromStart, lo, loInclusive, toEnd, hi, hiInclusive, descending);
        }
    }

    // The entries of a map in its order, each a copy read under the monitor.
    static final class Walk<K, V, T> implements Iterator<T> {

        private final Ordered<K, V> map;

        private final int part;

        private Map.Entry<K, V> ahead;

        private Map.Entry<K, V> last;

        Walk(Ordered<K, V> map, int part) {
            this.map = map;
            this.part = part;
            synchronized (map.lock) {
                ahead = map.engine.firstEntry();
            }
        }

        public boolean hasNext() {
            return ahead != null;
        }

        public T next() {
            Map.Entry<K, V> entry = ahead;
            if (entry == null) {
                throw new NoSuchElementException();
            }
            synchronized (map.lock) {
                ahead = map.engine.higherEntry(entry.getKey());
            }
            last = entry;
            Object given = part == Ordered.KEYS ? entry.getKey() : part == Ordered.VALUES ? entry.getValue() : entry;
            return (T) given;
        }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            map.remove(last.getKey());
            last = null;
        }
    }

    // The keys of a map: a set over it to which nothing is added.
    static final class KeySet<K> extends ConcurrentSkipListSet<K> {

        KeySet(Ordered<K, ?> map) {
            super((ConcurrentNavigableMap<K, Object>) map);
        }

        NavigableSet<K> made(ConcurrentNavigableMap<K, Object> m) {
            return new KeySet<>((Ordered<K, ?>) m);
        }

        public boolean add(K e) {
            throw new UnsupportedOperationException();
        }

        public boolean addAll(Collection<? extends K> c) {
            if (c.isEmpty()) {
                return false;
            }
            throw new UnsupportedOperationException();
        }
    }

    static final class Values<K, V> extends AbstractCollection<V> {

        private final Ordered<K, V> map;

        Values(Ordered<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.size();
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public boolean contains(Object o) {
            return map.containsValue(o);
        }

        public void clear() {
            map.clear();
        }

        public Iterator<V> iterator() {
            return new Walk<K, V, V>(map, Ordered.VALUES);
        }

        public Spliterator<V> spliterator() {
            if (map instanceof ConcurrentSkipListMap) {
                return Splits.of(this, Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
            }
            return Splits.unsized(this, 0);
        }
    }

    static final class EntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {

        private final Ordered<K, V> map;

        EntrySet(Ordered<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.size();
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            V held = map.get(entry.getKey());
            return held != null && held.equals(entry.getValue());
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            return map.remove(entry.getKey(), entry.getValue());
        }

        public void clear() {
            map.clear();
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Walk<K, V, Map.Entry<K, V>>(map, Ordered.ENTRIES);
        }

        public Spliterator<Map.Entry<K, V>> spliterator() {
            if (!(map instanceof ConcurrentSkipListMap)) {
                return Splits.unsized(this, Spliterator.DISTINCT);
            }
            Comparator<? super K> order = map.comparator();
            Comparator<Map.Entry<K, V>> byKey = (Map.Entry<K, V> a, Map.Entry<K, V> b) -> {
                if (order == null) {
                    Comparable<Object> natural = (Comparable<Object>) a.getKey();
                    return natural.compareTo(b.getKey());
                }
                return order.compare(a.getKey(), b.getKey());
            };
            return Splits.sorted(this, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED
                    | Spliterator.NONNULL | Spliterator.CONCURRENT, byKey, -1);
        }
    }
}
