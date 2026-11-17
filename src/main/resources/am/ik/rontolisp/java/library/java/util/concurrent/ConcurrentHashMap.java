// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A table of chains every member reads and writes under the map's monitor. The order a
// program sees was measured against a JDK 25 by its outputs (.kb/java-frontend.md,
// "java.util.concurrent"): a key's place is its hash code folded with its upper half and
// masked by the table's length, a power of two; a key is put at the end of its chain;
// the table doubles when its keys reach three quarters of its length, and a doubling
// leaves the last keys of a chain that go the same way in their order and puts each key
// ahead of them at the head of its new chain; a ninth key in one chain of a table
// shorter than 64 grows the table, and so does a put of its eighth key. Not kept: the order of a chain of more than eight
// keys in a table of 64 or more. putAll into a map that has a table first grows it for
// the keys of both. Neither a key nor a value is null. An iterator walks
// the table there was when it was made and never refuses.
package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ConcurrentHashMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {

    private static final class Node<K, V> {

        final int hash;

        final K key;

        V value;

        Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private Node<K, V>[] table;

    private int count;

    /** While there is no table: the length the first one gets. */
    private int first = 16;

    /** How many keys of its chain the last find() of a key that is there walked. */
    private int walked;

    public ConcurrentHashMap() {
    }

    public ConcurrentHashMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException();
        }
        this.first = powerOfTwo(1L + initialCapacity + (initialCapacity + 2) / 3);
    }

    public ConcurrentHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity);
    }

    public ConcurrentHashMap(Map<? extends K, ? extends V> m) {
        // the table a map made for so many keys has
        this(m.size());
        putAll(m);
    }

    public static <T> KeySetView<T, Boolean> newKeySet() {
        return new KeySetView<>(new ConcurrentHashMap<>(), Boolean.TRUE);
    }

    public static <T> KeySetView<T, Boolean> newKeySet(int initialCapacity) {
        return new KeySetView<>(new ConcurrentHashMap<>(initialCapacity), Boolean.TRUE);
    }

    private static int powerOfTwo(long wanted) {
        int length = 1;
        while (length < wanted && length < 1073741824) {
            length <<= 1;
        }
        return length;
    }

    private static int spread(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static <T> T given(T value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return value;
    }

    private Node<K, V> find(Object key) {
        Node<K, V>[] held = this.table;
        if (held == null) {
            given(key);
            return null;
        }
        int hash = spread(key);
        int walked = 0;
        for (Node<K, V> node = held[hash & (held.length - 1)]; node != null; node = node.next) {
            walked++;
            if (node.hash == hash && (node.key == key || node.key.equals(key))) {
                this.walked = walked;
                return node;
            }
        }
        return null;
    }

    /**
     * What a member that may add a key does once it found the key there: the eighth key
     * of a chain, or one past it, grows a table shorter than 64 as a ninth key would.
     */
    private void crowded() {
        if (this.walked >= 8 && this.table.length < 64) {
            presize(this.table.length << 1);
        }
    }

    /** The table twice as long. */
    private void grow() {
        Node<K, V>[] old = this.table;
        int length = old.length;
        Node<K, V>[] larger = new Node[length << 1];
        for (int i = 0; i < length; i++) {
            Node<K, V> head = old[i];
            if (head == null) {
                continue;
            }
            // the last keys of the chain that go the same way stay as they are
            Node<K, V> run = head;
            for (Node<K, V> node = head.next; node != null; node = node.next) {
                if ((node.hash & length) != (run.hash & length)) {
                    run = node;
                }
            }
            Node<K, V> low = null;
            Node<K, V> high = null;
            if ((run.hash & length) == 0) {
                low = run;
            }
            else {
                high = run;
            }
            // each key ahead of them is put at the head of its new chain: a node of its
            // own, so an iterator over the table there was still walks the old chain
            for (Node<K, V> node = head; node != run; node = node.next) {
                if ((node.hash & length) == 0) {
                    low = new Node<>(node.hash, node.key, node.value, low);
                }
                else {
                    high = new Node<>(node.hash, node.key, node.value, high);
                }
            }
            larger[i] = low;
            larger[i + length] = high;
        }
        this.table = larger;
    }

    /** Room for so many keys: the table there is grown until it has. */
    private void presize(int size) {
        int wanted = powerOfTwo(1L + size + (size >>> 1));
        while (wanted > this.table.length - (this.table.length >>> 2) && this.table.length < 1073741824) {
            grow();
        }
    }

    /** Puts a key the map has not at the end of its chain. */
    private void add(int hash, K key, V value) {
        if (this.table == null) {
            this.table = new Node[this.first];
        }
        int index = hash & (this.table.length - 1);
        Node<K, V> added = new Node<>(hash, key, value, null);
        Node<K, V> last = this.table[index];
        int chain = 0;
        if (last == null) {
            this.table[index] = added;
        }
        else {
            chain = 1;
            while (last.next != null) {
                last = last.next;
                chain++;
            }
            last.next = added;
        }
        this.count++;
        if (chain >= 8 && this.table.length < 64) {
            presize(this.table.length << 1);
        }
        while (this.count >= this.table.length - (this.table.length >>> 2) && this.table.length < 1073741824) {
            grow();
        }
    }

    public synchronized int size() {
        return this.count;
    }

    public synchronized long mappingCount() {
        return this.count;
    }

    public synchronized boolean isEmpty() {
        return this.count == 0;
    }

    public synchronized V get(Object key) {
        Node<K, V> node = find(key);
        return node == null ? null : node.value;
    }

    public synchronized V getOrDefault(Object key, V other) {
        Node<K, V> node = find(key);
        return node == null ? other : node.value;
    }

    public synchronized boolean containsKey(Object key) {
        return find(key) != null;
    }

    public synchronized boolean containsValue(Object value) {
        given(value);
        Node<K, V>[] held = this.table;
        if (held != null) {
            for (int i = 0; i < held.length; i++) {
                for (Node<K, V> node = held[i]; node != null; node = node.next) {
                    if (node.value == value || node.value.equals(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean contains(Object value) {
        return containsValue(value);
    }

    public synchronized V put(K key, V value) {
        given(key);
        given(value);
        Node<K, V> node = find(key);
        if (node != null) {
            V old = node.value;
            node.value = value;
            crowded();
            return old;
        }
        add(spread(key), key, value);
        return null;
    }

    public synchronized V putIfAbsent(K key, V value) {
        given(key);
        given(value);
        Node<K, V> node = find(key);
        if (node != null) {
            crowded();
            return node.value;
        }
        add(spread(key), key, value);
        return null;
    }

    public synchronized void putAll(Map<? extends K, ? extends V> m) {
        if (this.table != null) {
            presize(this.count + m.size());
        }
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public synchronized V remove(Object key) {
        Node<K, V>[] held = this.table;
        if (held == null) {
            given(key);
            return null;
        }
        int hash = spread(key);
        int index = hash & (held.length - 1);
        Node<K, V> before = null;
        for (Node<K, V> node = held[index]; node != null; node = node.next) {
            if (node.hash == hash && (node.key == key || node.key.equals(key))) {
                if (before == null) {
                    held[index] = node.next;
                }
                else {
                    before.next = node.next;
                }
                this.count--;
                return node.value;
            }
            before = node;
        }
        return null;
    }

    public synchronized boolean remove(Object key, Object value) {
        Node<K, V> node = find(key);
        if (node == null || value == null || !node.value.equals(value)) {
            return false;
        }
        remove(key);
        return true;
    }

    public synchronized V replace(K key, V value) {
        given(value);
        Node<K, V> node = find(key);
        if (node == null) {
            return null;
        }
        V old = node.value;
        node.value = value;
        return old;
    }

    public synchronized boolean replace(K key, V oldValue, V newValue) {
        given(oldValue);
        given(newValue);
        Node<K, V> node = find(key);
        if (node == null || !node.value.equals(oldValue)) {
            return false;
        }
        node.value = newValue;
        return true;
    }

    public synchronized void clear() {
        Node<K, V>[] held = this.table;
        if (held != null) {
            for (int i = 0; i < held.length; i++) {
                held[i] = null;
            }
        }
        this.count = 0;
    }

    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        given(function);
        Node<K, V> node = find(key);
        if (node != null) {
            crowded();
            return node.value;
        }
        V made = function.apply(key);
        if (made != null) {
            add(spread(key), key, made);
        }
        return made;
    }

    public synchronized V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        given(function);
        Node<K, V> node = find(key);
        if (node == null) {
            return null;
        }
        V made = function.apply(key, node.value);
        if (made == null) {
            remove(key);
        }
        else {
            node.value = made;
        }
        return made;
    }

    public synchronized V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        given(function);
        Node<K, V> node = find(key);
        V made = function.apply(key, node == null ? null : node.value);
        if (made == null) {
            if (node != null) {
                remove(key);
            }
        }
        else if (node == null) {
            add(spread(key), key, made);
        }
        else {
            node.value = made;
            crowded();
        }
        return made;
    }

    public synchronized V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        given(value);
        given(function);
        Node<K, V> node = find(key);
        if (node == null) {
            add(spread(key), key, value);
            return value;
        }
        V made = function.apply(node.value, value);
        if (made == null) {
            remove(key);
        }
        else {
            node.value = made;
            crowded();
        }
        return made;
    }

    public synchronized void forEach(BiConsumer<? super K, ? super V> action) {
        given(action);
        Walk<K, V> walk = new Walk<>(this);
        while (walk.hasNext()) {
            Node<K, V> node = walk.step();
            action.accept(node.key, node.value);
        }
    }

    public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        given(function);
        Walk<K, V> walk = new Walk<>(this);
        while (walk.hasNext()) {
            Node<K, V> node = walk.step();
            node.value = given(function.apply(node.key, node.value));
        }
    }

    public KeySetView<K, V> keySet() {
        return new KeySetView<>(this, null);
    }

    public KeySetView<K, V> keySet(V mappedValue) {
        return new KeySetView<>(this, given(mappedValue));
    }

    public Collection<V> values() {
        return new Values<>(this);
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new Entries<>(this);
    }

    public Enumeration<K> keys() {
        return new Keys<>(this);
    }

    public Enumeration<V> elements() {
        return new ValueWalk<>(this);
    }

    public synchronized boolean equals(Object o) {
        return super.equals(o);
    }

    public synchronized int hashCode() {
        return super.hashCode();
    }

    public synchronized String toString() {
        return super.toString();
    }

    /** A walk of the table there was when it was made, chain by chain. */
    private static class Walk<K, V> {

        private final ConcurrentHashMap<K, V> map;

        private final Node<K, V>[] held;

        private int index;

        private Node<K, V> next;

        private Node<K, V> last;

        Walk(ConcurrentHashMap<K, V> map) {
            this.map = map;
            synchronized (map) {
                this.held = map.table;
            }
            advance(null);
        }

        private void advance(Node<K, V> from) {
            Node<K, V> node = from == null ? null : from.next;
            while (node == null && this.held != null && this.index < this.held.length) {
                node = this.held[this.index++];
            }
            this.next = node;
        }

        public boolean hasNext() {
            return this.next != null;
        }

        public boolean hasMoreElements() {
            return this.next != null;
        }

        Node<K, V> step() {
            Node<K, V> node = this.next;
            if (node == null) {
                throw new NoSuchElementException();
            }
            this.last = node;
            synchronized (this.map) {
                advance(node);
            }
            return node;
        }

        public void remove() {
            if (this.last == null) {
                throw new IllegalStateException();
            }
            this.map.remove(this.last.key);
            this.last = null;
        }
    }

    private static final class Keys<K, V> extends Walk<K, V> implements Iterator<K>, Enumeration<K> {

        Keys(ConcurrentHashMap<K, V> map) {
            super(map);
        }

        public K next() {
            return step().key;
        }

        public K nextElement() {
            return step().key;
        }
    }

    private static final class ValueWalk<K, V> extends Walk<K, V> implements Iterator<V>, Enumeration<V> {

        ValueWalk(ConcurrentHashMap<K, V> map) {
            super(map);
        }

        public V next() {
            return step().value;
        }

        public V nextElement() {
            return step().value;
        }
    }

    /** A key and the value it had when the walk reached it; a new value is the map's. */
    private static final class Pair<K, V> implements Map.Entry<K, V> {

        private final ConcurrentHashMap<K, V> map;

        private final K key;

        private V value;

        Pair(ConcurrentHashMap<K, V> map, K key, V value) {
            this.map = map;
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return this.key;
        }

        public V getValue() {
            return this.value;
        }

        public V setValue(V replacement) {
            given(replacement);
            V old = this.value;
            this.value = replacement;
            this.map.put(this.key, replacement);
            return old;
        }

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> other = (Map.Entry<?, ?>) o;
            return this.key.equals(other.getKey()) && this.value.equals(other.getValue());
        }

        public int hashCode() {
            return this.key.hashCode() ^ this.value.hashCode();
        }

        public String toString() {
            return this.key + "=" + this.value;
        }
    }

    private static final class EntryWalk<K, V> extends Walk<K, V> implements Iterator<Map.Entry<K, V>> {

        private final ConcurrentHashMap<K, V> owner;

        EntryWalk(ConcurrentHashMap<K, V> map) {
            super(map);
            this.owner = map;
        }

        public Map.Entry<K, V> next() {
            Node<K, V> node = step();
            return new Pair<>(this.owner, node.key, node.value);
        }
    }

    /** The keys of a map; with a value to put, a set a program adds to. */
    public static final class KeySetView<K, V> extends AbstractSet<K> {

        private final ConcurrentHashMap<K, V> map;

        private final V value;

        KeySetView(ConcurrentHashMap<K, V> map, V value) {
            this.map = map;
            this.value = value;
        }

        public ConcurrentHashMap<K, V> getMap() {
            return this.map;
        }

        public V getMappedValue() {
            return this.value;
        }

        public int size() {
            return this.map.size();
        }

        public boolean isEmpty() {
            return this.map.isEmpty();
        }

        public boolean contains(Object o) {
            return this.map.containsKey(o);
        }

        public boolean add(K e) {
            if (this.value == null) {
                throw new UnsupportedOperationException();
            }
            return this.map.putIfAbsent(e, this.value) == null;
        }

        public boolean addAll(Collection<? extends K> c) {
            boolean added = false;
            for (K e : c) {
                if (add(e)) {
                    added = true;
                }
            }
            return added;
        }

        public boolean remove(Object o) {
            return this.map.remove(o) != null;
        }

        public void clear() {
            this.map.clear();
        }

        public Iterator<K> iterator() {
            return new Keys<>(this.map);
        }
    }

    private static final class Values<K, V> extends AbstractCollection<V> {

        private final ConcurrentHashMap<K, V> map;

        Values(ConcurrentHashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return this.map.size();
        }

        public boolean contains(Object o) {
            return this.map.containsValue(o);
        }

        public void clear() {
            this.map.clear();
        }

        public Iterator<V> iterator() {
            return new ValueWalk<>(this.map);
        }
    }

    private static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {

        private final ConcurrentHashMap<K, V> map;

        Entries(ConcurrentHashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return this.map.size();
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            Object held = this.map.get(entry.getKey());
            return held != null && held.equals(entry.getValue());
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            return this.map.remove(entry.getKey(), entry.getValue());
        }

        public void clear() {
            this.map.clear();
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryWalk<>(this.map);
        }
    }
}
