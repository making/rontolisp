// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// What every map has once it can enumerate its entries.
public abstract class AbstractMap<K, V> implements Map<K, V> {

    protected AbstractMap() {
    }

    public abstract Set<Map.Entry<K, V>> entrySet();

    public int size() {
        return entrySet().size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean containsKey(Object key) {
        for (Map.Entry<K, V> entry : entrySet()) {
            K held = entry.getKey();
            if (key == null ? held == null : key.equals(held)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsValue(Object value) {
        for (Map.Entry<K, V> entry : entrySet()) {
            V held = entry.getValue();
            if (value == null ? held == null : value.equals(held)) {
                return true;
            }
        }
        return false;
    }

    public V get(Object key) {
        for (Map.Entry<K, V> entry : entrySet()) {
            K held = entry.getKey();
            if (key == null ? held == null : key.equals(held)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    public V remove(Object key) {
        Iterator<Map.Entry<K, V>> each = entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<K, V> entry = each.next();
            K held = entry.getKey();
            if (key == null ? held == null : key.equals(held)) {
                V value = entry.getValue();
                each.remove();
                return value;
            }
        }
        return null;
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public void clear() {
        entrySet().clear();
    }

    public Set<K> keySet() {
        return new Keys<>(this);
    }

    public Collection<V> values() {
        return new Values<>(this);
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map)) {
            return false;
        }
        Map<?, ?> other = (Map<?, ?>) o;
        if (other.size() != size()) {
            return false;
        }
        for (Map.Entry<K, V> entry : entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            Object their = other.get(key);
            if (value == null ? !(their == null && other.containsKey(key)) : !value.equals(their)) {
                return false;
            }
        }
        return true;
    }

    public int hashCode() {
        int hash = 0;
        for (Map.Entry<K, V> entry : entrySet()) {
            hash += entry.hashCode();
        }
        return hash;
    }

    public String toString() {
        StringBuilder text = new StringBuilder();
        text.append('{');
        boolean first = true;
        for (Map.Entry<K, V> entry : entrySet()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            K key = entry.getKey();
            V value = entry.getValue();
            if (key == this) {
                text.append("(this Map)");
            }
            else {
                text.append(key);
            }
            text.append('=');
            if (value == this) {
                text.append("(this Map)");
            }
            else {
                text.append(value);
            }
        }
        return text.append('}').toString();
    }

    // The keys of a map as a set that reads, and removes from, the map.
    static final class Keys<K, V> extends AbstractSet<K> {

        private final Map<K, V> map;

        Keys(Map<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.size();
        }

        public boolean contains(Object o) {
            return map.containsKey(o);
        }

        public void clear() {
            map.clear();
        }

        public Iterator<K> iterator() {
            return new Part<>(map.entrySet().iterator(), true);
        }
    }

    static final class Values<K, V> extends AbstractCollection<V> {

        private final Map<K, V> map;

        Values(Map<K, V> map) {
            this.map = map;
        }

        public int size() {
            return map.size();
        }

        public boolean contains(Object o) {
            return map.containsValue(o);
        }

        public void clear() {
            map.clear();
        }

        public Iterator<V> iterator() {
            return new Part<>(map.entrySet().iterator(), false);
        }
    }

    // An entry of its own, whose value can be set.
    public static class SimpleEntry<K, V> implements Map.Entry<K, V> {

        private final K key;

        private V value;

        public SimpleEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public SimpleEntry(Map.Entry<? extends K, ? extends V> entry) {
            this.key = entry.getKey();
            this.value = entry.getValue();
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
            return o instanceof Map.Entry<?, ?> other && Objects.equals(key, other.getKey())
                    && Objects.equals(value, other.getValue());
        }

        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        public String toString() {
            return key + "=" + value;
        }
    }

    // An entry of its own nothing changes.
    public static class SimpleImmutableEntry<K, V> implements Map.Entry<K, V> {

        private final K key;

        private final V value;

        public SimpleImmutableEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public SimpleImmutableEntry(Map.Entry<? extends K, ? extends V> entry) {
            this.key = entry.getKey();
            this.value = entry.getValue();
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
            return o instanceof Map.Entry<?, ?> other && Objects.equals(key, other.getKey())
                    && Objects.equals(value, other.getValue());
        }

        public int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        public String toString() {
            return key + "=" + value;
        }
    }

    // The keys or the values of the entries an iterator gives.
    static final class Part<T, K, V> implements Iterator<T> {

        private final Iterator<Map.Entry<K, V>> entries;

        private final boolean keys;

        Part(Iterator<Map.Entry<K, V>> entries, boolean keys) {
            this.entries = entries;
            this.keys = keys;
        }

        public boolean hasNext() {
            return entries.hasNext();
        }

        public T next() {
            Map.Entry<K, V> entry = entries.next();
            Object part = keys ? entry.getKey() : entry.getValue();
            return (T) part;
        }

        public void remove() {
            entries.remove();
        }
    }
}
