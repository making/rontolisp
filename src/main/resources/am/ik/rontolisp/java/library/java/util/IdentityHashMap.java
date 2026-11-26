// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A map that tells keys, and values, apart by ==: a table of chains placed by
// System.identityHashCode, doubled when it is three quarters full. The order it is walked
// in is the table's, which the specification leaves open.
public class IdentityHashMap<K, V> extends AbstractMap<K, V> implements Cloneable {

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
            return o instanceof Map.Entry<?, ?> other && other.getKey() == key && other.getValue() == value;
        }

        public int hashCode() {
            return System.identityHashCode(key) ^ System.identityHashCode(value);
        }

        public String toString() {
            return key + "=" + value;
        }

    }

    private Node<K, V>[] table;

    private int size;

    public IdentityHashMap() {
        this(21);
    }

    public IdentityHashMap(int expectedMaxSize) {
        if (expectedMaxSize < 0) {
            throw new IllegalArgumentException("expectedMaxSize is negative: " + expectedMaxSize);
        }
        int length = 8;
        while (length * 3 / 4 < expectedMaxSize && length < (1 << 29)) {
            length <<= 1;
        }
        table = newTable(length);
    }

    public IdentityHashMap(Map<? extends K, ? extends V> m) {
        this(m.size());
        putAll(m);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int length) {
        return (Node<K, V>[]) new Node[length];
    }

    private static int hash(Object key) {
        int h = System.identityHashCode(key);
        return h ^ (h >>> 16);
    }

    private Node<K, V> node(Object key) {
        int hash = hash(key);
        for (Node<K, V> n = table[hash & (table.length - 1)]; n != null; n = n.next) {
            if (n.key == key) {
                return n;
            }
        }
        return null;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public V get(Object key) {
        Node<K, V> n = node(key);
        return n == null ? null : n.value;
    }

    public boolean containsKey(Object key) {
        return node(key) != null;
    }

    public boolean containsValue(Object value) {
        for (Node<K, V> head : table) {
            for (Node<K, V> n = head; n != null; n = n.next) {
                if (n.value == value) {
                    return true;
                }
            }
        }
        return false;
    }

    public V put(K key, V value) {
        Node<K, V> found = node(key);
        if (found != null) {
            return found.setValue(value);
        }
        if (size + 1 > table.length * 3 / 4) {
            grow();
        }
        int hash = hash(key);
        int at = hash & (table.length - 1);
        Node<K, V> added = new Node<>(hash, key, value);
        added.next = table[at];
        table[at] = added;
        size++;
        return null;
    }

    // the same keys and values in a table as long as this one, each chain in its order
    @SuppressWarnings("unchecked")
    public Object clone() {
        IdentityHashMap<K, V> copy = (IdentityHashMap<K, V>) rontolisp.internal.Support.copy(this);
        copy.table = newTable(table.length);
        for (int i = 0; i < table.length; i++) {
            Node<K, V> tail = null;
            for (Node<K, V> n = table[i]; n != null; n = n.next) {
                Node<K, V> made = new Node<>(n.hash, n.key, n.value);
                if (tail == null) {
                    copy.table[i] = made;
                }
                else {
                    tail.next = made;
                }
                tail = made;
            }
        }
        return copy;
    }

    private void grow() {
        Node<K, V>[] old = table;
        table = newTable(old.length * 2);
        for (Node<K, V> head : old) {
            Node<K, V> n = head;
            while (n != null) {
                Node<K, V> next = n.next;
                int at = n.hash & (table.length - 1);
                n.next = table[at];
                table[at] = n;
                n = next;
            }
        }
    }

    public V remove(Object key) {
        Node<K, V> n = unlink(key);
        return n == null ? null : n.value;
    }

    private Node<K, V> unlink(Object key) {
        int at = hash(key) & (table.length - 1);
        Node<K, V> previous = null;
        for (Node<K, V> n = table[at]; n != null; previous = n, n = n.next) {
            if (n.key == key) {
                if (previous == null) {
                    table[at] = n.next;
                }
                else {
                    previous.next = n.next;
                }
                size--;
                return n;
            }
        }
        return null;
    }

    public void clear() {
        Arrays.fill(table, null);
        size = 0;
    }

    public boolean equals(Object o) {
        return o == this || (o instanceof Map<?, ?> other && entrySet().equals(other.entrySet()));
    }

    public int hashCode() {
        int sum = 0;
        for (Map.Entry<K, V> entry : entrySet()) {
            sum += entry.hashCode();
        }
        return sum;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new AbstractSet<Map.Entry<K, V>>() {

            public int size() {
                return size;
            }

            public boolean contains(Object o) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    return false;
                }
                Node<K, V> n = node(entry.getKey());
                return n != null && n.value == entry.getValue();
            }

            public void clear() {
                IdentityHashMap.this.clear();
            }

            public Iterator<Map.Entry<K, V>> iterator() {
                return new Walk();
            }

        };
    }

    // Each node in the table's order; remove unlinks the one last returned.
    private final class Walk implements Iterator<Map.Entry<K, V>> {

        private int index;

        private Node<K, V> next;

        private Node<K, V> last;

        Walk() {
            advance(null);
        }

        private void advance(Node<K, V> from) {
            next = from == null ? null : from.next;
            while (next == null && index < table.length) {
                next = table[index++];
            }
        }

        public boolean hasNext() {
            return next != null;
        }

        public Map.Entry<K, V> next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            last = next;
            advance(next);
            return last;
        }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            unlink(last.key);
            last = null;
        }

    }

}
