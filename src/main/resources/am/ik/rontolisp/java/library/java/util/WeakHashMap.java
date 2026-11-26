// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import rontolisp.internal.ObjectFlow;
import rontolisp.internal.Splits;

// A map whose keys the collector may drop once nothing else holds them -- which the
// collector of no target does (java.lang.ref.Reference), so a key stays until the program
// removes it. Its order is its own, read off a JDK's answers: a key's bucket is the
// exclusive-or of its hash code shifted right by 0, 4, 7, 12, 16, 19, 20, 24 and 27 bits,
// cut to the table's length (a power of two); a key is put ahead of its chain; the table
// doubles once it holds more than its length times the load factor, and a chain moved is
// reversed. The iterators read the buckets from the last; the spliterators, forEach and
// replaceAll from the first. A copy starts as long as its mappings need; putAll of more
// than the map may hold grows it ahead to that many over the load factor, but gives the
// length back while the map holds less than half of what it may. The null key's bucket is
// the last.
public class WeakHashMap<K, V> extends AbstractMap<K, V> {

    static final class Slot<K, V> implements Map.Entry<K, V> {

        final K key;

        V value;

        final int hash;

        Slot<K, V> next;

        Slot(K key, V value, int hash, Slot<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }

        public K getKey() {
            return this.key;
        }

        public V getValue() {
            return this.value;
        }

        public V setValue(V replacement) {
            V old = this.value;
            this.value = replacement;
            return old;
        }

        public boolean equals(Object o) {
            return o instanceof Map.Entry<?, ?> other && Objects.equals(this.key, other.getKey())
                    && Objects.equals(this.value, other.getValue());
        }

        public int hashCode() {
            return Objects.hashCode(this.key) ^ Objects.hashCode(this.value);
        }

        public String toString() {
            return this.key + "=" + this.value;
        }
    }

    private static final int LONGEST = 1 << 30;

    private Slot<K, V>[] table;

    private int size;

    private int threshold;

    private final float loadFactor;

    private int changes;

    // the views, made when first asked for
    private Set<K> keys;

    private Collection<V> values;

    private Set<Map.Entry<K, V>> entries;

    @SuppressWarnings("unchecked")
    public WeakHashMap(int initialCapacity, float loadFactor) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal Initial Capacity: " + initialCapacity);
        }
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Illegal Load factor: " + loadFactor);
        }
        int length = 1;
        while (length < initialCapacity && length < LONGEST) {
            length <<= 1;
        }
        this.table = new Slot[length];
        this.loadFactor = loadFactor;
        this.threshold = (int) (length * loadFactor);
    }

    public WeakHashMap(int initialCapacity) {
        this(initialCapacity, 0.75f);
    }

    public WeakHashMap() {
        this(16, 0.75f);
    }

    public WeakHashMap(Map<? extends K, ? extends V> m) {
        this(Math.max((int) (m.size() / 0.75f), 16), 0.75f);
        putAll(m);
    }

    public static <K, V> WeakHashMap<K, V> newWeakHashMap(int numMappings) {
        if (numMappings < 0) {
            throw new IllegalArgumentException("Negative number of mappings: " + numMappings);
        }
        return new WeakHashMap<>((int) Math.ceil(numMappings / 0.75));
    }

    // the bucket a hash code gives before it is cut to the table's length; the null key's
    // is every bit
    static int spread(Object key) {
        if (key == null) {
            return -1;
        }
        int h = key.hashCode();
        return h ^ (h >>> 4) ^ (h >>> 7) ^ (h >>> 12) ^ (h >>> 16) ^ (h >>> 19) ^ (h >>> 20) ^ (h >>> 24)
                ^ (h >>> 27);
    }

    private Slot<K, V> find(Object key) {
        int hash = spread(key);
        for (Slot<K, V> slot = this.table[hash & (this.table.length - 1)]; slot != null; slot = slot.next) {
            if (slot.hash == hash && (key == slot.key || (key != null && key.equals(slot.key)))) {
                return slot;
            }
        }
        return null;
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public V get(Object key) {
        Slot<K, V> slot = find(key);
        return slot == null ? null : slot.value;
    }

    public boolean containsKey(Object key) {
        return find(key) != null;
    }

    public boolean containsValue(Object value) {
        for (int i = this.table.length - 1; i >= 0; i--) {
            for (Slot<K, V> slot = this.table[i]; slot != null; slot = slot.next) {
                if (Objects.equals(value, slot.value)) {
                    return true;
                }
            }
        }
        return false;
    }

    public V put(K key, V value) {
        Slot<K, V> held = find(key);
        if (held != null) {
            V old = held.value;
            held.value = value;
            return old;
        }
        int hash = spread(key);
        int index = hash & (this.table.length - 1);
        this.changes++;
        this.table[index] = new Slot<>(key, value, hash, this.table[index]);
        if (++this.size > this.threshold) {
            grow(this.table.length * 2);
        }
        return null;
    }

    // a table of a length the chains are moved to, each reversed; kept where the map holds
    // half of what it may, else the chains are moved back
    private void grow(int length) {
        Slot<K, V>[] old = this.table;
        if (old.length == LONGEST) {
            this.threshold = Integer.MAX_VALUE;
            return;
        }
        Slot<K, V>[] larger = moved(old, length);
        if (this.size >= this.threshold / 2) {
            this.table = larger;
            this.threshold = (int) (length * this.loadFactor);
        }
        else {
            this.table = moved(larger, old.length);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Slot<K, V>[] moved(Slot<K, V>[] from, int length) {
        Slot<K, V>[] to = new Slot[length];
        for (Slot<K, V> chain : from) {
            Slot<K, V> slot = chain;
            while (slot != null) {
                Slot<K, V> next = slot.next;
                int index = slot.hash & (length - 1);
                slot.next = to[index];
                to[index] = slot;
                slot = next;
            }
        }
        return to;
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        int adding = m.size();
        if (adding == 0) {
            return;
        }
        if (adding > this.threshold) {
            int target = Math.min((int) (adding / this.loadFactor), LONGEST);
            int length = this.table.length;
            while (length < target) {
                length <<= 1;
            }
            if (length > this.table.length) {
                grow(length);
            }
        }
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public V remove(Object key) {
        int hash = spread(key);
        int index = hash & (this.table.length - 1);
        Slot<K, V> before = null;
        for (Slot<K, V> slot = this.table[index]; slot != null; slot = slot.next) {
            if (slot.hash == hash && (key == slot.key || (key != null && key.equals(slot.key)))) {
                unlink(index, before, slot);
                return slot.value;
            }
            before = slot;
        }
        return null;
    }

    private void unlink(int index, Slot<K, V> before, Slot<K, V> slot) {
        if (before == null) {
            this.table[index] = slot.next;
        }
        else {
            before.next = slot.next;
        }
        this.size--;
        this.changes++;
    }

    // removes the slot a walk handed out, if it is still in the map
    private boolean removeSlot(Object o) {
        if (!(o instanceof Map.Entry<?, ?> entry)) {
            return false;
        }
        Object key = entry.getKey();
        int hash = spread(key);
        int index = hash & (this.table.length - 1);
        Slot<K, V> before = null;
        for (Slot<K, V> slot = this.table[index]; slot != null; slot = slot.next) {
            if (slot.hash == hash && slot.equals(entry)) {
                unlink(index, before, slot);
                return true;
            }
            before = slot;
        }
        return false;
    }

    public void clear() {
        Arrays.fill(this.table, null);
        this.size = 0;
        this.changes++;
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = this.changes;
        Slot<K, V>[] walked = this.table;
        for (Slot<K, V> chain : walked) {
            for (Slot<K, V> slot = chain; slot != null; slot = slot.next) {
                action.accept(slot.key, slot.value);
                if (this.changes != expected) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int expected = this.changes;
        Slot<K, V>[] walked = this.table;
        for (Slot<K, V> chain : walked) {
            for (Slot<K, V> slot = chain; slot != null; slot = slot.next) {
                slot.value = function.apply(slot.key, slot.value);
                if (this.changes != expected) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }

    public Set<K> keySet() {
        if (this.keys == null) {
            this.keys = new Keys<>(this);
        }
        return this.keys;
    }

    public Collection<V> values() {
        if (this.values == null) {
            this.values = new Values<>(this);
        }
        return this.values;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        if (this.entries == null) {
            this.entries = new Entries<>(this);
        }
        return this.entries;
    }

    // The slots from the last bucket to the first. The next one is looked for when it is
    // asked for, in the table as it is then; next() notices a change first.
    static final class Walk<K, V> {

        private final WeakHashMap<K, V> map;

        private int index;

        // the slot to look at next in its chain, or null to go to the bucket below
        private Slot<K, V> at;

        private Slot<K, V> ahead;

        private Slot<K, V> last;

        private int expected;

        Walk(WeakHashMap<K, V> map) {
            this.map = map;
            this.index = map.isEmpty() ? 0 : map.table.length;
            this.expected = map.changes;
        }

        boolean hasNext() {
            if (this.ahead != null) {
                return true;
            }
            Slot<K, V> slot = this.at;
            int i = this.index;
            Slot<K, V>[] table = this.map.table;
            while (slot == null && i > 0) {
                slot = table[--i];
            }
            this.at = slot;
            this.index = i;
            this.ahead = slot;
            return slot != null;
        }

        Slot<K, V> nextSlot() {
            if (this.map.changes != this.expected) {
                throw new ConcurrentModificationException();
            }
            if (this.ahead == null && !hasNext()) {
                throw new NoSuchElementException();
            }
            Slot<K, V> slot = this.ahead;
            this.last = slot;
            this.at = slot.next;
            this.ahead = null;
            return slot;
        }

        void remove() {
            if (this.last == null) {
                throw new IllegalStateException();
            }
            if (this.map.changes != this.expected) {
                throw new ConcurrentModificationException();
            }
            this.map.remove(this.last.key);
            this.expected = this.map.changes;
            this.last = null;
        }
    }

    // a walk answering its keys (0), values (1) or slots (2)
    static final class Each<T, K, V> implements Iterator<T> {

        private final Walk<K, V> walk;

        private final int part;

        Each(WeakHashMap<K, V> map, int part) {
            this.walk = new Walk<>(map);
            this.part = part;
        }

        public boolean hasNext() {
            return this.walk.hasNext();
        }

        @SuppressWarnings("unchecked")
        public T next() {
            Slot<K, V> slot = this.walk.nextSlot();
            Object answer = this.part == 0 ? slot.key : this.part == 1 ? slot.value : slot;
            return (T) answer;
        }

        public void remove() {
            this.walk.remove();
        }
    }

    // the keys, values or slots from the first bucket to the last
    static final class Ascending<T, K, V> implements Iterator<T> {

        private final Slot<K, V>[] table;

        private final int part;

        private int index;

        private Slot<K, V> ahead;

        Ascending(WeakHashMap<K, V> map, int part) {
            this.table = map.table;
            this.part = part;
            this.index = 0;
            advance(null);
        }

        private void advance(Slot<K, V> from) {
            Slot<K, V> slot = from == null ? null : from.next;
            while (slot == null && this.index < this.table.length) {
                slot = this.table[this.index++];
            }
            this.ahead = slot;
        }

        public boolean hasNext() {
            return this.ahead != null;
        }

        @SuppressWarnings("unchecked")
        public T next() {
            Slot<K, V> slot = this.ahead;
            if (slot == null) {
                throw new NoSuchElementException();
            }
            advance(slot);
            Object answer = this.part == 0 ? slot.key : this.part == 1 ? slot.value : slot;
            return (T) answer;
        }
    }

    static final class Keys<K, V> extends AbstractSet<K> {

        private final WeakHashMap<K, V> map;

        Keys(WeakHashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return this.map.size;
        }

        public boolean contains(Object o) {
            return this.map.containsKey(o);
        }

        public boolean remove(Object o) {
            if (this.map.containsKey(o)) {
                this.map.remove(o);
                return true;
            }
            return false;
        }

        public void clear() {
            this.map.clear();
        }

        public Iterator<K> iterator() {
            return new Each<>(this.map, 0);
        }

        public Spliterator<K> spliterator() {
            return new Splits<>(this, new Ascending<>(this.map, 0), this.map.size,
                    Spliterator.DISTINCT, false);
        }

        // the elements in the spliterator's order
        public Stream<K> stream() {
            return ObjectFlow.over(() -> new Ascending<>(this.map, 0), null);
        }
    }

    static final class Values<K, V> extends AbstractCollection<V> {

        private final WeakHashMap<K, V> map;

        Values(WeakHashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return this.map.size;
        }

        public boolean contains(Object o) {
            return this.map.containsValue(o);
        }

        public void clear() {
            this.map.clear();
        }

        public Iterator<V> iterator() {
            return new Each<>(this.map, 1);
        }

        public Spliterator<V> spliterator() {
            return new Splits<>(this, new Ascending<>(this.map, 1), this.map.size, 0, false);
        }

        // the elements in the spliterator's order
        public Stream<V> stream() {
            return ObjectFlow.over(() -> new Ascending<>(this.map, 1), null);
        }
    }

    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {

        private final WeakHashMap<K, V> map;

        Entries(WeakHashMap<K, V> map) {
            this.map = map;
        }

        public int size() {
            return this.map.size;
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?, ?> entry)) {
                return false;
            }
            Slot<K, V> slot = this.map.find(entry.getKey());
            return slot != null && slot.equals(entry);
        }

        public boolean remove(Object o) {
            return this.map.removeSlot(o);
        }

        public void clear() {
            this.map.clear();
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return new Each<>(this.map, 2);
        }

        public Spliterator<Map.Entry<K, V>> spliterator() {
            return new Splits<>(this, new Ascending<>(this.map, 2), this.map.size,
                    Spliterator.DISTINCT, false);
        }

        // the elements in the spliterator's order
        public Stream<Map.Entry<K, V>> stream() {
            return ObjectFlow.over(() -> new Ascending<>(this.map, 2), null);
        }
    }
}
