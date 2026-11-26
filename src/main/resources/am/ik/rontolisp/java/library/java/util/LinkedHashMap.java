// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.BiFunction;
import java.util.function.Function;
import rontolisp.internal.Splits;

// A HashMap whose nodes are also one chain, in the order their keys were added or, made
// with accessOrder, in the order they were last reached; the views walk the chain. The
// table makes and drops the nodes through made and unlinked, so the chain costs a
// HashMap nothing it does not have. removeEldestEntry is asked after every key added.
// putFirst and putLast move a key to an end of the chain; the reversed view walks it
// back.
public class LinkedHashMap<K, V> extends HashMap<K, V> implements SequencedMap<K, V> {

    static final class Linked<K, V> extends HashMap.Node<K, V> {

        Linked<K, V> before;

        Linked<K, V> after;

        Linked(int hash, K key, V value) {
            super(hash, key, value);
        }
    }

    Linked<K, V> head;

    Linked<K, V> tail;

    private final boolean accessOrder;

    // while the copy constructor puts: no eldest entry is asked for
    private boolean copying;

    public LinkedHashMap() {
        accessOrder = false;
    }

    public LinkedHashMap(int capacity) {
        super(capacity);
        accessOrder = false;
    }

    public LinkedHashMap(int capacity, float loadFactor) {
        super(capacity, loadFactor);
        accessOrder = false;
    }

    public LinkedHashMap(int capacity, float loadFactor, boolean accessOrder) {
        super(capacity, loadFactor);
        this.accessOrder = accessOrder;
    }

    public LinkedHashMap(Map<? extends K, ? extends V> m) {
        accessOrder = false;
        copying = true;
        putAll(m);
        copying = false;
    }

    public static <K, V> LinkedHashMap<K, V> newLinkedHashMap(int numMappings) {
        return new LinkedHashMap<>(HashMap.capacityFor(numMappings));
    }

    // clone()'s: each key of the original, in the order of its chain
    void copyOf(HashMap<K, V> original) {
        head = null;
        tail = null;
        for (Linked<K, V> node = ((LinkedHashMap<K, V>) original).head; node != null; node = node.after) {
            added(node.hash, node.key, node.value);
        }
    }

    Node<K, V> made(int hash, K key, V value) {
        Linked<K, V> node = new Linked<>(hash, key, value);
        append(node);
        return node;
    }

    void unlinked(Node<K, V> node) {
        detach((Linked<K, V>) node);
    }

    private void append(Linked<K, V> node) {
        Linked<K, V> last = tail;
        node.before = last;
        node.after = null;
        if (last == null) {
            head = node;
        }
        else {
            last.after = node;
        }
        tail = node;
    }

    private void prepend(Linked<K, V> node) {
        Linked<K, V> first = head;
        node.after = first;
        node.before = null;
        if (first == null) {
            tail = node;
        }
        else {
            first.before = node;
        }
        head = node;
    }

    private void detach(Linked<K, V> node) {
        Linked<K, V> before = node.before;
        Linked<K, V> after = node.after;
        if (before == null) {
            head = after;
        }
        else {
            before.after = after;
        }
        if (after == null) {
            tail = before;
        }
        else {
            after.before = before;
        }
    }

    // In access order a node reached goes to the end of the chain, which is a change.
    private void reached(Node<K, V> node) {
        if (accessOrder && node != tail) {
            Linked<K, V> moved = (Linked<K, V>) node;
            detach(moved);
            append(moved);
            changes++;
        }
    }

    // After a call that may have added the key: the eldest entry is asked for when it
    // did, the key is reached when it did not and is there.
    private void settled(Object key, int before) {
        if (size() > before) {
            Linked<K, V> eldest = head;
            if (!copying && eldest != null && removeEldestEntry(eldest)) {
                remove(eldest.key);
            }
        }
        else if (accessOrder) {
            Node<K, V> node = find(key);
            if (node != null) {
                reached(node);
            }
        }
    }

    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return false;
    }

    public V get(Object key) {
        Node<K, V> node = find(key);
        if (node == null) {
            return null;
        }
        reached(node);
        return node.value;
    }

    public V getOrDefault(Object key, V other) {
        Node<K, V> node = find(key);
        if (node == null) {
            return other;
        }
        reached(node);
        return node.value;
    }

    public V put(K key, V value) {
        int before = size();
        V old = super.put(key, value);
        settled(key, before);
        return old;
    }

    public V putIfAbsent(K key, V value) {
        Node<K, V> node = find(key);
        if (node != null && node.value != null) {
            reached(node);
            return node.value;
        }
        return put(key, value);
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        int before = size();
        V made = super.computeIfAbsent(key, function);
        // a null made reaches nothing, though the key was there
        if (made != null) {
            settled(key, before);
        }
        return made;
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        int before = size();
        V made = super.compute(key, function);
        settled(key, before);
        return made;
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        int before = size();
        V made = super.merge(key, value, function);
        settled(key, before);
        return made;
    }

    public void clear() {
        super.clear();
        head = null;
        tail = null;
    }

    // A key there takes the value and goes to the end, which is a change unless it is
    // there already; a key added is put, then moved, then asked about as eldest.
    public V putFirst(K key, V value) {
        return putAt(key, value, true);
    }

    public V putLast(K key, V value) {
        return putAt(key, value, false);
    }

    private V putAt(K key, V value, boolean first) {
        Node<K, V> node = find(key);
        if (node != null) {
            V old = node.value;
            node.value = value;
            moveTo((Linked<K, V>) node, first);
            return old;
        }
        int before = size();
        super.put(key, value);
        if (first) {
            moveTo(tail, true);
        }
        settled(key, before);
        return null;
    }

    private void moveTo(Linked<K, V> node, boolean first) {
        if (node != (first ? head : tail)) {
            detach(node);
            if (first) {
                prepend(node);
            }
            else {
                append(node);
            }
            changes++;
        }
    }

    public Map.Entry<K, V> firstEntry() {
        Linked<K, V> first = head;
        return first == null ? null : new ImmutableCollections.Pair<>(first.key, first.value);
    }

    public Map.Entry<K, V> lastEntry() {
        Linked<K, V> last = tail;
        return last == null ? null : new ImmutableCollections.Pair<>(last.key, last.value);
    }

    public Map.Entry<K, V> pollFirstEntry() {
        Linked<K, V> first = head;
        if (first == null) {
            return null;
        }
        remove(first.key);
        return new ImmutableCollections.Pair<>(first.key, first.value);
    }

    public Map.Entry<K, V> pollLastEntry() {
        Linked<K, V> last = tail;
        if (last == null) {
            return null;
        }
        remove(last.key);
        return new ImmutableCollections.Pair<>(last.key, last.value);
    }

    public SequencedMap<K, V> reversed() {
        return new Reversed<>(this);
    }

    public SequencedSet<K> sequencedKeySet() {
        return new KeysInOrder<>(this);
    }

    public SequencedCollection<V> sequencedValues() {
        return new ValuesInOrder<>(this);
    }

    public SequencedSet<Map.Entry<K, V>> sequencedEntrySet() {
        return new EntriesInOrder<>(this);
    }

    public Set<K> keySet() {
        return new KeysInOrder<>(this);
    }

    public Collection<V> values() {
        return new ValuesInOrder<>(this);
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new EntriesInOrder<>(this);
    }

    // The nodes in the chain's order; part says what it answers of each: 0 the key, 1
    // the value, 2 the node.
    static final class Order<K, V, T> implements Iterator<T> {

        private final LinkedHashMap<K, V> map;

        private final int part;

        private Linked<K, V> ahead;

        private Linked<K, V> last;

        private int expected;

        Order(LinkedHashMap<K, V> map, int part) {
            this.map = map;
            this.part = part;
            this.ahead = map.head;
            this.expected = map.changes;
        }

        public boolean hasNext() {
            return ahead != null;
        }

        public T next() {
            Linked<K, V> node = ahead;
            if (map.changes != expected) {
                throw new ConcurrentModificationException();
            }
            if (node == null) {
                throw new NoSuchElementException();
            }
            last = node;
            ahead = node.after;
            if (part == 0) {
                return (T) node.key;
            }
            if (part == 1) {
                return (T) node.value;
            }
            return (T) node;
        }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            if (map.changes != expected) {
                throw new ConcurrentModificationException();
            }
            map.remove(last.key);
            last = null;
            expected = map.changes;
        }
    }

    // The nodes from the last back; part as Order's.
    static final class Backward<K, V, T> implements Iterator<T> {

        private final LinkedHashMap<K, V> map;

        private final int part;

        private Linked<K, V> ahead;

        private Linked<K, V> last;

        private int expected;

        Backward(LinkedHashMap<K, V> map, int part) {
            this.map = map;
            this.part = part;
            this.ahead = map.tail;
            this.expected = map.changes;
        }

        public boolean hasNext() {
            return ahead != null;
        }

        public T next() {
            Linked<K, V> node = ahead;
            if (map.changes != expected) {
                throw new ConcurrentModificationException();
            }
            if (node == null) {
                throw new NoSuchElementException();
            }
            last = node;
            ahead = node.before;
            if (part == 0) {
                return (T) node.key;
            }
            if (part == 1) {
                return (T) node.value;
            }
            return (T) node;
        }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            if (map.changes != expected) {
                throw new ConcurrentModificationException();
            }
            map.remove(last.key);
            last = null;
            expected = map.changes;
        }
    }

    static class KeysInOrder<K, V> extends HashMap.KeySet<K, V> implements SequencedSet<K> {

        final LinkedHashMap<K, V> map;

        KeysInOrder(LinkedHashMap<K, V> map) {
            super(map);
            this.map = map;
        }

        public Iterator<K> iterator() {
            return new Order<K, V, K>(map, 0);
        }

        public SequencedSet<K> reversed() {
            return new KeysBack<>(map);
        }

        public Spliterator<K> spliterator() {
            return Splits.of(this,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }

    static class ValuesInOrder<K, V> extends HashMap.ValueCollection<K, V> implements SequencedCollection<V> {

        final LinkedHashMap<K, V> map;

        ValuesInOrder(LinkedHashMap<K, V> map) {
            super(map);
            this.map = map;
        }

        public Iterator<V> iterator() {
            return new Order<K, V, V>(map, 1);
        }

        public SequencedCollection<V> reversed() {
            return new ValuesBack<>(map);
        }

        public Spliterator<V> spliterator() {
            return Splits.of(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }

    static class EntriesInOrder<K, V> extends HashMap.EntrySet<K, V> implements SequencedSet<Map.Entry<K, V>> {

        final LinkedHashMap<K, V> map;

        EntriesInOrder(LinkedHashMap<K, V> map) {
            super(map);
            this.map = map;
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Order<K, V, Map.Entry<K, V>>(map, 2);
        }

        public SequencedSet<Map.Entry<K, V>> reversed() {
            return new EntriesBack<>(map);
        }

        public Spliterator<Map.Entry<K, V>> spliterator() {
            return Splits.of(this,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }

    // The views of the reversed map: the chain walked back.
    static final class KeysBack<K, V> extends KeysInOrder<K, V> {

        KeysBack(LinkedHashMap<K, V> map) {
            super(map);
        }

        public Iterator<K> iterator() {
            return new Backward<K, V, K>(map, 0);
        }

        public SequencedSet<K> reversed() {
            return new KeysInOrder<>(map);
        }
    }

    static final class ValuesBack<K, V> extends ValuesInOrder<K, V> {

        ValuesBack(LinkedHashMap<K, V> map) {
            super(map);
        }

        public Iterator<V> iterator() {
            return new Backward<K, V, V>(map, 1);
        }

        public SequencedCollection<V> reversed() {
            return new ValuesInOrder<>(map);
        }
    }

    static final class EntriesBack<K, V> extends EntriesInOrder<K, V> {

        EntriesBack(LinkedHashMap<K, V> map) {
            super(map);
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Backward<K, V, Map.Entry<K, V>>(map, 2);
        }

        public SequencedSet<Map.Entry<K, V>> reversed() {
            return new EntriesInOrder<>(map);
        }
    }

    // The view reversed() answers: what reaches a key is the map's own member, what has
    // an end is the member of the other end, and its views walk the chain back.
    static final class Reversed<K, V> extends AbstractMap<K, V> implements SequencedMap<K, V> {

        private final LinkedHashMap<K, V> map;

        Reversed(LinkedHashMap<K, V> map) {
            this.map = map;
        }

        public SequencedMap<K, V> reversed() {
            return map;
        }

        public int size() {
            return map.size();
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public boolean containsKey(Object key) {
            return map.containsKey(key);
        }

        public boolean containsValue(Object value) {
            return map.containsValue(value);
        }

        public V get(Object key) {
            return map.get(key);
        }

        public V getOrDefault(Object key, V other) {
            return map.getOrDefault(key, other);
        }

        public V put(K key, V value) {
            return map.put(key, value);
        }

        public void putAll(Map<? extends K, ? extends V> m) {
            map.putAll(m);
        }

        public V putIfAbsent(K key, V value) {
            return map.putIfAbsent(key, value);
        }

        public V remove(Object key) {
            return map.remove(key);
        }

        public boolean remove(Object key, Object value) {
            return map.remove(key, value);
        }

        public boolean replace(K key, V oldValue, V newValue) {
            return map.replace(key, oldValue, newValue);
        }

        public V replace(K key, V value) {
            return map.replace(key, value);
        }

        public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
            return map.computeIfAbsent(key, function);
        }

        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            return map.computeIfPresent(key, function);
        }

        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
            return map.compute(key, function);
        }

        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
            return map.merge(key, value, function);
        }

        public void clear() {
            map.clear();
        }

        public Set<K> keySet() {
            return new KeysBack<>(map);
        }

        public Collection<V> values() {
            return new ValuesBack<>(map);
        }

        public Set<Map.Entry<K, V>> entrySet() {
            return new EntriesBack<>(map);
        }

        public Map.Entry<K, V> firstEntry() {
            return map.lastEntry();
        }

        public Map.Entry<K, V> lastEntry() {
            return map.firstEntry();
        }

        public Map.Entry<K, V> pollFirstEntry() {
            return map.pollLastEntry();
        }

        public Map.Entry<K, V> pollLastEntry() {
            return map.pollFirstEntry();
        }

        public V putFirst(K key, V value) {
            return map.putLast(key, value);
        }

        public V putLast(K key, V value) {
            return map.putFirst(key, value);
        }
    }
}
