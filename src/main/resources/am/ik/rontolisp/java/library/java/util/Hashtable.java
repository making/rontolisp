// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

// The older table. Its order is its own, kept as a program sees it: a key's place is its
// hash code without its sign modulo the table's length, 11 at first and twice plus one
// when three quarters are used; a key is put ahead of its chain, a growth walks the
// chains from the last and puts each key ahead again, and the table is read from its
// last chain to its first. Neither a key nor a value is null. Every member holds the
// table's monitor, the ones Map and AbstractMap write over others included.
public class Hashtable<K, V> extends AbstractMap<K, V> {

    static final class Slot<K, V> implements Map.Entry<K, V> {

        final int hash;

        final K key;

        V value;

        Slot<K, V> next;

        Slot(int hash, K key, V value, Slot<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public V setValue(V replacement) {
            if (replacement == null) {
                throw new NullPointerException();
            }
            V old = value;
            value = replacement;
            return old;
        }

        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> other = (Map.Entry<?, ?>) o;
            return key.equals(other.getKey()) && value.equals(other.getValue());
        }

        public int hashCode() {
            return hash ^ value.hashCode();
        }

        public String toString() {
            return key + "=" + value;
        }
    }

    private Slot<K, V>[] table;

    private int count;

    private int limit;

    private int changes;

    public Hashtable() {
        this(11);
    }

    public Hashtable(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Illegal Capacity: " + capacity);
        }
        int length = capacity == 0 ? 1 : capacity;
        table = new Slot[length];
        limit = length * 3 / 4;
    }

    public Hashtable(Map<? extends K, ? extends V> m) {
        this(m.size() * 2 < 11 ? 11 : m.size() * 2);
        putAll(m);
    }

    private Slot<K, V> find(Object key) {
        int hash = key.hashCode();
        Slot<K, V> slot = table[(hash & 0x7FFFFFFF) % table.length];
        while (slot != null) {
            if (slot.hash == hash && slot.key.equals(key)) {
                return slot;
            }
            slot = slot.next;
        }
        return null;
    }

    private void grow() {
        Slot<K, V>[] old = table;
        int length = (old.length << 1) + 1;
        Slot<K, V>[] larger = new Slot[length];
        for (int i = old.length - 1; i >= 0; i--) {
            Slot<K, V> slot = old[i];
            while (slot != null) {
                Slot<K, V> next = slot.next;
                int index = (slot.hash & 0x7FFFFFFF) % length;
                slot.next = larger[index];
                larger[index] = slot;
                slot = next;
            }
        }
        table = larger;
        limit = length * 3 / 4;
    }

    public synchronized int size() {
        return count;
    }

    public synchronized boolean isEmpty() {
        return count == 0;
    }

    public synchronized boolean containsKey(Object key) {
        return find(key) != null;
    }

    public synchronized boolean contains(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
        for (int i = table.length - 1; i >= 0; i--) {
            for (Slot<K, V> slot = table[i]; slot != null; slot = slot.next) {
                if (slot.value.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized boolean containsValue(Object value) {
        return contains(value);
    }

    public synchronized V get(Object key) {
        Slot<K, V> slot = find(key);
        return slot == null ? null : slot.value;
    }

    public synchronized V put(K key, V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        Slot<K, V> held = find(key);
        if (held != null) {
            V old = held.value;
            held.value = value;
            return old;
        }
        if (count >= limit) {
            grow();
        }
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % table.length;
        table[index] = new Slot<>(hash, key, value, table[index]);
        count++;
        changes++;
        return null;
    }

    public synchronized V remove(Object key) {
        int hash = key.hashCode();
        int index = (hash & 0x7FFFFFFF) % table.length;
        Slot<K, V> before = null;
        for (Slot<K, V> slot = table[index]; slot != null; slot = slot.next) {
            if (slot.hash == hash && slot.key.equals(key)) {
                if (before == null) {
                    table[index] = slot.next;
                }
                else {
                    before.next = slot.next;
                }
                count--;
                changes++;
                return slot.value;
            }
            before = slot;
        }
        return null;
    }

    public synchronized void clear() {
        for (int i = 0; i < table.length; i++) {
            table[i] = null;
        }
        count = 0;
        changes++;
    }

    public synchronized Enumeration<K> keys() {
        return new Listing<>(new Walk<>(this), true);
    }

    public synchronized Enumeration<V> elements() {
        return new Listing<>(new Walk<>(this), false);
    }

    public synchronized Set<Map.Entry<K, V>> entrySet() {
        return new Entries<>(this);
    }

    public synchronized void putAll(Map<? extends K, ? extends V> m) {
        super.putAll(m);
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

    public synchronized V getOrDefault(Object key, V other) {
        return super.getOrDefault(key, other);
    }

    public synchronized void forEach(BiConsumer<? super K, ? super V> action) {
        super.forEach(action);
    }

    public synchronized void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        super.replaceAll(function);
    }

    public synchronized V putIfAbsent(K key, V value) {
        return super.putIfAbsent(key, value);
    }

    public synchronized V replace(K key, V value) {
        return super.replace(key, value);
    }

    public synchronized V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        return super.computeIfAbsent(key, function);
    }

    public synchronized V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        return super.computeIfPresent(key, function);
    }

    public synchronized V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        return super.compute(key, function);
    }

    public synchronized V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        return super.merge(key, value, function);
    }

    // The slots from the last chain to the first.
    static final class Walk<K, V> implements Iterator<Map.Entry<K, V>> {

        private final Hashtable<K, V> map;

        private int index;

        private Slot<K, V> ahead;

        private Slot<K, V> last;

        private int expected;

        Walk(Hashtable<K, V> map) {
            this.map = map;
            this.index = map.table.length;
            this.expected = map.changes;
            advance(null);
        }

        private void advance(Slot<K, V> from) {
            Slot<K, V> slot = from == null ? null : from.next;
            while (slot == null && index > 0) {
                slot = map.table[--index];
            }
            ahead = slot;
        }

        public boolean hasNext() {
            return ahead != null;
        }

        public Map.Entry<K, V> next() {
            Slot<K, V> slot = ahead;
            if (map.changes != expected) {
                throw new ConcurrentModificationException();
            }
            if (slot == null) {
                throw new NoSuchElementException();
            }
            last = slot;
            advance(slot);
            return slot;
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

    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {

        private final Hashtable<K, V> map;

        Entries(Hashtable<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.count;
        }

        public void clear() {
            map.clear();
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Walk<>(map);
        }
    }

    // The keys or the values as the older interface lists them.
    static final class Listing<T, K, V> implements Enumeration<T> {

        private final Iterator<Map.Entry<K, V>> entries;

        private final boolean keys;

        Listing(Iterator<Map.Entry<K, V>> entries, boolean keys) {
            this.entries = entries;
            this.keys = keys;
        }

        public boolean hasMoreElements() {
            return entries.hasNext();
        }

        public T nextElement() {
            Map.Entry<K, V> entry = entries.next();
            Object part = keys ? entry.getKey() : entry.getValue();
            return (T) part;
        }
    }
}
