// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.BiFunction;
import java.util.function.Function;

// A table of chains. What a program can see of the order is kept as the specification's
// own implementation gives it: a key's place is its hash code folded with its upper half
// and masked by the table's length, a power of two that doubles when three quarters
// are used; a chain is in the order its keys were put, and a doubling keeps that order
// in the two chains a chain becomes. The table is made when the first key is put. Not
// kept: the order of a chain of more than eight keys in a table of 64 or more.
public class HashMap<K, V> extends AbstractMap<K, V> {

    static final class Node<K, V> implements Map.Entry<K, V> {

        final int hash;

        final K key;

        V value;

        Node<K, V> next;

        Node(int hash, K key, V value) {
            this.hash = hash;
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
            V old = value;
            value = replacement;
            return old;
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

    private Node<K, V>[] table;

    private int count;

    // while there is no table: the length the first one gets, 0 for 16
    private int limit;

    private int changes;

    public HashMap() {
    }

    public HashMap(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Illegal initial capacity: " + capacity);
        }
        limit = powerOfTwo(capacity);
    }

    public HashMap(int capacity, double loadFactor) {
        this(capacity);
    }

    public HashMap(Map<? extends K, ? extends V> m) {
        putAll(m);
    }

    private static int powerOfTwo(int capacity) {
        int length = 1;
        while (length < capacity && length < 1073741824) {
            length <<= 1;
        }
        return length;
    }

    private static int spread(Object key) {
        if (key == null) {
            return 0;
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private Node<K, V> find(Object key) {
        Node<K, V>[] buckets = table;
        if (buckets == null) {
            return null;
        }
        int hash = spread(key);
        Node<K, V> node = buckets[hash & (buckets.length - 1)];
        while (node != null) {
            if (node.hash == hash) {
                K held = node.key;
                if (held == key || (key != null && key.equals(held))) {
                    return node;
                }
            }
            node = node.next;
        }
        return null;
    }

    private void grow() {
        Node<K, V>[] old = table;
        if (old == null) {
            int length = limit > 0 ? limit : 16;
            table = new Node[length];
            limit = length * 3 >> 2;
            return;
        }
        int length = old.length;
        Node<K, V>[] larger = new Node[length << 1];
        for (int i = 0; i < length; i++) {
            Node<K, V> node = old[i];
            Node<K, V> lowTail = null;
            Node<K, V> highTail = null;
            while (node != null) {
                Node<K, V> next = node.next;
                node.next = null;
                if ((node.hash & length) == 0) {
                    if (lowTail == null) {
                        larger[i] = node;
                    }
                    else {
                        lowTail.next = node;
                    }
                    lowTail = node;
                }
                else {
                    if (highTail == null) {
                        larger[i + length] = node;
                    }
                    else {
                        highTail.next = node;
                    }
                    highTail = node;
                }
                node = next;
            }
        }
        table = larger;
        limit = (length << 1) * 3 >> 2;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean containsKey(Object key) {
        return find(key) != null;
    }

    public boolean containsValue(Object value) {
        Node<K, V>[] buckets = table;
        if (buckets != null) {
            for (int i = 0; i < buckets.length; i++) {
                for (Node<K, V> node = buckets[i]; node != null; node = node.next) {
                    V held = node.value;
                    if (value == null ? held == null : value.equals(held)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public V get(Object key) {
        Node<K, V> node = find(key);
        return node == null ? null : node.value;
    }

    public V getOrDefault(Object key, V other) {
        Node<K, V> node = find(key);
        return node == null ? other : node.value;
    }

    public V put(K key, V value) {
        if (table == null) {
            grow();
        }
        Node<K, V>[] buckets = table;
        int hash = spread(key);
        int index = hash & (buckets.length - 1);
        Node<K, V> node = buckets[index];
        Node<K, V> tail = null;
        int chain = 0;
        while (node != null) {
            if (node.hash == hash) {
                K held = node.key;
                if (held == key || (key != null && key.equals(held))) {
                    V old = node.value;
                    node.value = value;
                    return old;
                }
            }
            tail = node;
            node = node.next;
            chain++;
        }
        Node<K, V> added = new Node<>(hash, key, value);
        if (tail == null) {
            buckets[index] = added;
        }
        else {
            tail.next = added;
        }
        crowded(chain, 8);
        changes++;
        if (++count > limit) {
            grow();
        }
        return null;
    }

    // A chain grown past a length doubles a table shorter than 64.
    private void crowded(int chain, int most) {
        if (chain >= most && table.length < 64) {
            grow();
        }
    }

    public V putIfAbsent(K key, V value) {
        Node<K, V> node = find(key);
        if (node != null && node.value != null) {
            return node.value;
        }
        return put(key, value);
    }

    // A key that compute, computeIfAbsent or merge adds goes ahead of its chain, where
    // put adds one behind it.
    private void addAhead(K key, V value) {
        if (table == null) {
            grow();
        }
        Node<K, V>[] buckets = table;
        int hash = spread(key);
        int index = hash & (buckets.length - 1);
        int chain = 0;
        for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
            chain++;
        }
        Node<K, V> added = new Node<>(hash, key, value);
        added.next = buckets[index];
        buckets[index] = added;
        crowded(chain, 7);
        changes++;
        if (++count > limit) {
            grow();
        }
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        Node<K, V> node = find(key);
        if (node != null && node.value != null) {
            return node.value;
        }
        int before = changes;
        V made = function.apply(key);
        if (changes != before) {
            throw new ConcurrentModificationException();
        }
        if (made != null) {
            if (node != null) {
                node.value = made;
            }
            else {
                addAhead(key, made);
            }
        }
        return made;
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        Node<K, V> node = find(key);
        int before = changes;
        V made = function.apply(key, node == null ? null : node.value);
        if (changes != before) {
            throw new ConcurrentModificationException();
        }
        if (node != null) {
            if (made == null) {
                remove(key);
            }
            else {
                node.value = made;
            }
        }
        else if (made != null) {
            addAhead(key, made);
        }
        return made;
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        if (value == null) {
            throw new NullPointerException();
        }
        Node<K, V> node = find(key);
        if (node == null) {
            addAhead(key, value);
            return value;
        }
        V made = node.value == null ? value : function.apply(node.value, value);
        if (made == null) {
            remove(key);
        }
        else {
            node.value = made;
        }
        return made;
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        int added = m.size();
        if (added == 0) {
            return;
        }
        if (table == null) {
            // room for all of them under three quarters
            int wanted = (added * 4 + 2) / 3;
            int length = powerOfTwo(wanted);
            if (length > limit) {
                limit = length;
            }
        }
        else {
            while (added > limit && table.length < 1073741824) {
                grow();
            }
        }
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public V remove(Object key) {
        Node<K, V>[] buckets = table;
        if (buckets == null) {
            return null;
        }
        int hash = spread(key);
        int index = hash & (buckets.length - 1);
        Node<K, V> node = buckets[index];
        Node<K, V> before = null;
        while (node != null) {
            if (node.hash == hash) {
                K held = node.key;
                if (held == key || (key != null && key.equals(held))) {
                    if (before == null) {
                        buckets[index] = node.next;
                    }
                    else {
                        before.next = node.next;
                    }
                    count--;
                    changes++;
                    return node.value;
                }
            }
            before = node;
            node = node.next;
        }
        return null;
    }

    public void clear() {
        Node<K, V>[] buckets = table;
        if (buckets != null && count > 0) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            count = 0;
            changes++;
        }
    }

    public Set<K> keySet() {
        return new KeySet<>(this);
    }

    public Collection<V> values() {
        return new ValueCollection<>(this);
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new EntrySet<>(this);
    }

    // The nodes in the table's order; what it answers of each is its subclass's.
    abstract static class Walk<K, V> {

        final HashMap<K, V> map;

        private int index;

        private Node<K, V> ahead;

        private Node<K, V> last;

        private int expected;

        Walk(HashMap<K, V> map) {
            this.map = map;
            this.expected = map.changes;
            advance(null);
        }

        private void advance(Node<K, V> from) {
            Node<K, V> node = from == null ? null : from.next;
            Node<K, V>[] buckets = map.table;
            if (buckets != null) {
                while (node == null && index < buckets.length) {
                    node = buckets[index++];
                }
            }
            ahead = node;
        }

        public boolean hasNext() {
            return ahead != null;
        }

        Node<K, V> nextNode() {
            Node<K, V> node = ahead;
            if (map.changes != expected) {
                throw new ConcurrentModificationException();
            }
            if (node == null) {
                throw new NoSuchElementException();
            }
            last = node;
            advance(node);
            return node;
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

    static final class KeyWalk<K, V> extends Walk<K, V> implements Iterator<K> {

        KeyWalk(HashMap<K, V> map) {
            super(map);
        }

        public K next() {
            return nextNode().key;
        }
    }

    static final class ValueWalk<K, V> extends Walk<K, V> implements Iterator<V> {

        ValueWalk(HashMap<K, V> map) {
            super(map);
        }

        public V next() {
            return nextNode().value;
        }
    }

    static final class EntryWalk<K, V> extends Walk<K, V> implements Iterator<Map.Entry<K, V>> {

        EntryWalk(HashMap<K, V> map) {
            super(map);
        }

        public Map.Entry<K, V> next() {
            return nextNode();
        }
    }

    static final class KeySet<K, V> extends AbstractSet<K> {

        private final HashMap<K, V> map;

        KeySet(HashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.count;
        }

        public boolean contains(Object o) {
            return map.containsKey(o);
        }

        public boolean remove(Object o) {
            if (map.find(o) == null) {
                return false;
            }
            map.remove(o);
            return true;
        }

        public void clear() {
            map.clear();
        }

        public Iterator<K> iterator() {
            return new KeyWalk<>(map);
        }
    }

    static final class ValueCollection<K, V> extends AbstractCollection<V> {

        private final HashMap<K, V> map;

        ValueCollection(HashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.count;
        }

        public boolean contains(Object o) {
            return map.containsValue(o);
        }

        public void clear() {
            map.clear();
        }

        public Iterator<V> iterator() {
            return new ValueWalk<>(map);
        }
    }

    static final class EntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {

        private final HashMap<K, V> map;

        EntrySet(HashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.count;
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            Node<K, V> node = map.find(entry.getKey());
            return node != null && node.equals(entry);
        }

        public void clear() {
            map.clear();
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryWalk<>(map);
        }
    }
}
