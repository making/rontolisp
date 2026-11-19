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
public class LinkedHashMap<K, V> extends HashMap<K, V> {

    static final class Linked<K, V> extends HashMap.Node<K, V> {

        Linked<K, V> before;

        Linked<K, V> after;

        Linked(int hash, K key, V value) {
            super(hash, key, value);
        }
    }

    private Linked<K, V> head;

    private Linked<K, V> tail;

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

    static final class KeysInOrder<K, V> extends HashMap.KeySet<K, V> {

        private final LinkedHashMap<K, V> map;

        KeysInOrder(LinkedHashMap<K, V> map) {
            super(map);
            this.map = map;
        }

        public Iterator<K> iterator() {
            return new Order<K, V, K>(map, 0);
        }

        public Spliterator<K> spliterator() {
            return Splits.of(this,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }

    static final class ValuesInOrder<K, V> extends HashMap.ValueCollection<K, V> {

        private final LinkedHashMap<K, V> map;

        ValuesInOrder(LinkedHashMap<K, V> map) {
            super(map);
            this.map = map;
        }

        public Iterator<V> iterator() {
            return new Order<K, V, V>(map, 1);
        }

        public Spliterator<V> spliterator() {
            return Splits.of(this, Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }

    static final class EntriesInOrder<K, V> extends HashMap.EntrySet<K, V> {

        private final LinkedHashMap<K, V> map;

        EntriesInOrder(LinkedHashMap<K, V> map) {
            super(map);
            this.map = map;
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Order<K, V, Map.Entry<K, V>>(map, 2);
        }

        public Spliterator<Map.Entry<K, V>> spliterator() {
            return Splits.of(this,
                    Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }
}
