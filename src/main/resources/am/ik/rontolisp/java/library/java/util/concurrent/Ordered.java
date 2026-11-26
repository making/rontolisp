// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import rontolisp.internal.Splits;

// What a ConcurrentSkipListMap and each of its views are: a TreeMap, or a view of one,
// read and changed under the map's monitor. Neither a key nor a value is null. A view
// is checked for its bounds as a JDK checks this map's, which is not as it checks a
// TreeMap's: a bound outside the range is out of range, a range ending before it starts
// is inconsistent.
abstract class Ordered<K, V> extends AbstractMap<K, V> implements ConcurrentNavigableMap<K, V> {

    // what a walk gives of each entry
    static final int KEYS = 0;

    static final int VALUES = 1;

    static final int ENTRIES = 2;

    // the ConcurrentSkipListMap's monitor
    Object lock;

    TreeMap<K, V> tree;

    // the tree, or the view of it this map is
    NavigableMap<K, V> engine;

    // the bounds, in the tree's order, and whether this map runs against it
    private final boolean fromStart;

    private final K lo;

    private final boolean loInclusive;

    private final boolean toEnd;

    private final K hi;

    private final boolean hiInclusive;

    private final boolean descending;

    Ordered(Object lock, TreeMap<K, V> tree, NavigableMap<K, V> engine, boolean fromStart, K lo, boolean loInclusive,
            boolean toEnd, K hi, boolean hiInclusive, boolean descending) {
        this.lock = lock == null ? this : lock;
        this.tree = tree;
        this.engine = engine == null ? tree : engine;
        this.fromStart = fromStart;
        this.lo = lo;
        this.loInclusive = loInclusive;
        this.toEnd = toEnd;
        this.hi = hi;
        this.hiInclusive = hiInclusive;
        this.descending = descending;
    }

    private int compare(K a, K b) {
        Comparator<? super K> order = tree.comparator();
        if (order == null) {
            Comparable<Object> natural = (Comparable<Object>) a;
            return natural.compareTo(b);
        }
        return order.compare(a, b);
    }

    // A view of this map, its bounds named in this map's order.
    private ConcurrentNavigableMap<K, V> view(boolean hasFrom, K fromKey, boolean fromInclusive, boolean hasTo, K toKey,
            boolean toInclusive) {
        boolean hasLow = descending ? hasTo : hasFrom;
        K low = descending ? toKey : fromKey;
        boolean lowInclusive = descending ? toInclusive : fromInclusive;
        boolean hasHigh = descending ? hasFrom : hasTo;
        K high = descending ? fromKey : toKey;
        boolean highInclusive = descending ? fromInclusive : toInclusive;
        if (hasLow) {
            Objects.requireNonNull(low);
            if (!fromStart) {
                int order = compare(low, lo);
                if (order < 0 || (order == 0 && !loInclusive && lowInclusive)) {
                    throw new IllegalArgumentException("key out of range");
                }
            }
        }
        else {
            low = lo;
            lowInclusive = loInclusive;
        }
        if (hasHigh) {
            Objects.requireNonNull(high);
            if (!toEnd) {
                int order = compare(high, hi);
                if (order > 0 || (order == 0 && !hiInclusive && highInclusive)) {
                    throw new IllegalArgumentException("key out of range");
                }
            }
        }
        else {
            high = hi;
            highInclusive = hiInclusive;
        }
        boolean start = !hasLow && fromStart;
        boolean end = !hasHigh && toEnd;
        if (!start && !end && compare(low, high) > 0) {
            throw new IllegalArgumentException("inconsistent range");
        }
        NavigableMap<K, V> range = start ? (end ? tree : tree.headMap(high, highInclusive))
                : end ? tree.tailMap(low, lowInclusive) : tree.subMap(low, lowInclusive, high, highInclusive);
        return new ConcurrentSkipListMap.SubMap<>(lock, tree, descending ? range.descendingMap() : range, start, low,
                lowInclusive, end, high, highInclusive, descending);
    }

    final Spliterator<K> keySplit(Collection<K> keys) {
        if (this instanceof ConcurrentSkipListMap) {
            return Splits.sorted(keys, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED
                    | Spliterator.NONNULL | Spliterator.CONCURRENT, comparator(), -1);
        }
        return Splits.sorted(keys, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED, comparator(),
                Long.MAX_VALUE);
    }

    public int size() {
        synchronized (lock) {
            return engine.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return engine.isEmpty();
        }
    }

    public boolean containsKey(Object key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.containsKey(key);
        }
    }

    public boolean containsValue(Object value) {
        Objects.requireNonNull(value);
        synchronized (lock) {
            return engine.containsValue(value);
        }
    }

    public V get(Object key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.get(key);
        }
    }

    public V getOrDefault(Object key, V other) {
        V value = get(key);
        return value == null ? other : value;
    }

    public V put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        synchronized (lock) {
            return engine.put(key, value);
        }
    }

    public V remove(Object key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.remove(key);
        }
    }

    public void clear() {
        synchronized (lock) {
            engine.clear();
        }
    }

    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        synchronized (lock) {
            V held = engine.get(key);
            if (held == null) {
                engine.put(key, value);
            }
            return held;
        }
    }

    public boolean remove(Object key, Object value) {
        Objects.requireNonNull(key);
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            V held = engine.get(key);
            if (held == null || !held.equals(value)) {
                return false;
            }
            engine.remove(key);
            return true;
        }
    }

    public boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);
        synchronized (lock) {
            V held = engine.get(key);
            if (held == null || !held.equals(oldValue)) {
                return false;
            }
            engine.put(key, newValue);
            return true;
        }
    }

    public V replace(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        synchronized (lock) {
            V held = engine.get(key);
            if (held != null) {
                engine.put(key, value);
            }
            return held;
        }
    }

    // A function runs under the monitor, once.
    public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(function);
        synchronized (lock) {
            V held = engine.get(key);
            if (held != null) {
                return held;
            }
            V made = function.apply(key);
            if (made != null) {
                engine.put(key, made);
            }
            return made;
        }
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(function);
        synchronized (lock) {
            V held = engine.get(key);
            if (held == null) {
                return null;
            }
            return settle(key, function.apply(key, held));
        }
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(function);
        synchronized (lock) {
            V held = engine.get(key);
            V made = function.apply(key, held);
            if (made == null && held == null) {
                return null;
            }
            return settle(key, made);
        }
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Objects.requireNonNull(function);
        synchronized (lock) {
            V held = engine.get(key);
            return settle(key, held == null ? value : function.apply(held, value));
        }
    }

    private V settle(K key, V made) {
        if (made == null) {
            engine.remove(key);
        }
        else {
            engine.put(key, made);
        }
        return made;
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        synchronized (lock) {
            for (Map.Entry<K, V> entry : engine.entrySet()) {
                entry.setValue(Objects.requireNonNull(function.apply(entry.getKey(), entry.getValue())));
            }
        }
    }

    public Comparator<? super K> comparator() {
        return engine.comparator();
    }

    public K firstKey() {
        synchronized (lock) {
            return engine.firstKey();
        }
    }

    public K lastKey() {
        synchronized (lock) {
            return engine.lastKey();
        }
    }

    public Map.Entry<K, V> firstEntry() {
        synchronized (lock) {
            return engine.firstEntry();
        }
    }

    public Map.Entry<K, V> lastEntry() {
        synchronized (lock) {
            return engine.lastEntry();
        }
    }

    public Map.Entry<K, V> pollFirstEntry() {
        synchronized (lock) {
            return engine.pollFirstEntry();
        }
    }

    public Map.Entry<K, V> pollLastEntry() {
        synchronized (lock) {
            return engine.pollLastEntry();
        }
    }

    public Map.Entry<K, V> lowerEntry(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.lowerEntry(key);
        }
    }

    public K lowerKey(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.lowerKey(key);
        }
    }

    public Map.Entry<K, V> floorEntry(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.floorEntry(key);
        }
    }

    public K floorKey(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.floorKey(key);
        }
    }

    public Map.Entry<K, V> ceilingEntry(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.ceilingEntry(key);
        }
    }

    public K ceilingKey(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.ceilingKey(key);
        }
    }

    public Map.Entry<K, V> higherEntry(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.higherEntry(key);
        }
    }

    public K higherKey(K key) {
        Objects.requireNonNull(key);
        synchronized (lock) {
            return engine.higherKey(key);
        }
    }

    public NavigableSet<K> keySet() {
        return new ConcurrentSkipListMap.KeySet<>(this);
    }

    public NavigableSet<K> navigableKeySet() {
        return keySet();
    }

    public NavigableSet<K> descendingKeySet() {
        return descendingMap().navigableKeySet();
    }

    public Collection<V> values() {
        return new ConcurrentSkipListMap.Values<>(this);
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new ConcurrentSkipListMap.EntrySet<>(this);
    }

    public ConcurrentNavigableMap<K, V> descendingMap() {
        return new ConcurrentSkipListMap.SubMap<>(lock, tree, engine.descendingMap(), fromStart, lo, loInclusive,
                toEnd, hi, hiInclusive, !descending);
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        return view(true, fromKey, fromInclusive, true, toKey, toInclusive);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        return view(false, null, false, true, toKey, inclusive);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        return view(true, fromKey, inclusive, false, null, false);
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
        return subMap(fromKey, true, toKey, false);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey) {
        return headMap(toKey, false);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey) {
        return tailMap(fromKey, true);
    }
}
