// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// A map from the constants of one enum: a slot for each, by its ordinal, so the keys are
// in their order. An iterator reads the slots as they are when it comes to them and
// never refuses a change. An entry is its slot: it reads and sets the map's value of
// its key, and after the iterator that gave it removed it, it is no entry any more.
public class EnumMap<K extends Enum<K>, V> extends AbstractMap<K, V> implements Cloneable {

    private final Class<K> keyType;

    private final K[] keys;

    private Object[] vals;

    private boolean[] held;

    private int count;

    public EnumMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = keyType.getEnumConstants();
        this.vals = new Object[keys.length];
        this.held = new boolean[keys.length];
    }

    public EnumMap(EnumMap<K, ? extends V> m) {
        this(m.keyType);
        putAll(m);
    }

    public EnumMap(Map<K, ? extends V> m) {
        this(keyTypeOf(m));
        putAll(m);
    }

    // the same keys and values in arrays of its own
    @SuppressWarnings("unchecked")
    public EnumMap<K, V> clone() {
        EnumMap<K, V> copy = (EnumMap<K, V>) rontolisp.internal.Support.copy(this);
        copy.vals = vals.clone();
        copy.held = held.clone();
        return copy;
    }

    private static <K extends Enum<K>> Class<K> keyTypeOf(Map<K, ?> m) {
        if (m instanceof EnumMap) {
            EnumMap<K, ?> other = (EnumMap<K, ?>) m;
            return other.keyType;
        }
        if (m.isEmpty()) {
            throw new IllegalArgumentException("Specified map is empty");
        }
        return m.keySet().iterator().next().getDeclaringClass();
    }

    // the ordinal of a constant of the keys' enum, or -1
    private int place(Object key) {
        if (key instanceof Enum<?> e && e.getDeclaringClass() == keyType) {
            return e.ordinal();
        }
        return -1;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean containsKey(Object key) {
        int at = place(key);
        return at >= 0 && held[at];
    }

    public boolean containsValue(Object value) {
        for (int i = 0; i < keys.length; i++) {
            if (held[i] && Objects.equals(value, vals[i])) {
                return true;
            }
        }
        return false;
    }

    public V get(Object key) {
        int at = place(key);
        return at >= 0 && held[at] ? (V) vals[at] : null;
    }

    public V put(K key, V value) {
        if (key.getDeclaringClass() != keyType) {
            throw new ClassCastException(key.getClass() + " != " + keyType);
        }
        int at = key.ordinal();
        V old = held[at] ? (V) vals[at] : null;
        if (!held[at]) {
            held[at] = true;
            count++;
        }
        vals[at] = value;
        return old;
    }

    public V remove(Object key) {
        int at = place(key);
        if (at < 0 || !held[at]) {
            return null;
        }
        V old = (V) vals[at];
        vals[at] = null;
        held[at] = false;
        count--;
        return old;
    }

    public void clear() {
        for (int i = 0; i < keys.length; i++) {
            vals[i] = null;
            held[i] = false;
        }
        count = 0;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new Slots<>(this);
    }

    static final class Slots<K extends Enum<K>, V> extends AbstractSet<Map.Entry<K, V>> {

        private final EnumMap<K, V> map;

        Slots(EnumMap<K, V> map) {
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
            int at = map.place(entry.getKey());
            return at >= 0 && map.held[at] && Objects.equals(map.vals[at], entry.getValue());
        }

        public boolean remove(Object o) {
            if (!contains(o)) {
                return false;
            }
            map.remove(((Map.Entry<?, ?>) o).getKey());
            return true;
        }

        public void clear() {
            map.clear();
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Walk<>(map);
        }
    }

    static final class Walk<K extends Enum<K>, V> implements Iterator<Map.Entry<K, V>> {

        private final EnumMap<K, V> map;

        private int index;

        private Slot<K, V> last;

        Walk(EnumMap<K, V> map) {
            this.map = map;
        }

        public boolean hasNext() {
            while (index < map.keys.length && !map.held[index]) {
                index++;
            }
            return index < map.keys.length;
        }

        public Map.Entry<K, V> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            last = new Slot<>(map, index++);
            return last;
        }

        public void remove() {
            Slot<K, V> given = last;
            if (given == null) {
                throw new IllegalStateException();
            }
            map.remove(map.keys[given.index]);
            given.index = -1;
            last = null;
        }
    }

    static final class Slot<K extends Enum<K>, V> implements Map.Entry<K, V> {

        private final EnumMap<K, V> map;

        // the key's ordinal, or -1 once the iterator that gave it removed it
        int index;

        Slot(EnumMap<K, V> map, int index) {
            this.map = map;
            this.index = index;
        }

        private int at() {
            if (index < 0) {
                throw new IllegalStateException("Entry was removed");
            }
            return index;
        }

        public K getKey() {
            return map.keys[at()];
        }

        public V getValue() {
            int at = at();
            return map.held[at] ? (V) map.vals[at] : null;
        }

        // A slot set again after its key left the map holds the value: the map's count
        // is not told, as a JDK's is not.
        public V setValue(V value) {
            int at = at();
            V old = map.held[at] ? (V) map.vals[at] : null;
            map.vals[at] = value;
            map.held[at] = true;
            return old;
        }

        public boolean equals(Object o) {
            if (index < 0) {
                return o == this;
            }
            return o instanceof Map.Entry<?, ?> other && Objects.equals(getKey(), other.getKey())
                    && Objects.equals(getValue(), other.getValue());
        }

        public int hashCode() {
            if (index < 0) {
                return super.hashCode();
            }
            V value = getValue();
            return getKey().hashCode() ^ (value == null ? 0 : value.hashCode());
        }

        public String toString() {
            if (index < 0) {
                return super.toString();
            }
            return getKey() + "=" + getValue();
        }
    }
}
