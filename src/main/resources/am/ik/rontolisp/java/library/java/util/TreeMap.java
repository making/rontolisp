// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.Serializable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import rontolisp.internal.Splits;

// The keys in their order, in a binary tree kept balanced as Adelson-Velsky and Landis
// published it: the heights of the two subtrees of a node differ by one at most, and a
// put or a remove that breaks that turns a node or two on the way back to the root. A
// node leaves the tree unlinked, never by taking another node's key, so an entry a
// program holds and the node an iterator stands on stay what they were. A view -- a
// range of keys, the keys against their order, or both -- is the tree and its bounds.
// Which keys a comparator is asked about follows this tree's shape, not a JDK's.
public class TreeMap<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V>, Cloneable, Serializable {

    static final class Entry<K, V> implements Map.Entry<K, V> {

        final K key;

        V value;

        Entry<K, V> left;

        Entry<K, V> right;

        Entry<K, V> up;

        int height;

        Entry(K key, V value, Entry<K, V> up) {
            this.key = key;
            this.value = value;
            this.up = up;
            this.height = 1;
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

    // where near() looks from a key
    static final int LOWER = 0;

    static final int FLOOR = 1;

    static final int CEILING = 2;

    static final int HIGHER = 3;

    // what a walk gives of each node
    static final int KEYS = 0;

    static final int VALUES = 1;

    static final int ENTRIES = 2;

    private final Comparator<? super K> comparator;

    private Entry<K, V> root;

    private int count;

    int changes;

    public TreeMap() {
        comparator = null;
    }

    public TreeMap(Comparator<? super K> comparator) {
        this.comparator = comparator;
    }

    public TreeMap(Map<? extends K, ? extends V> m) {
        comparator = null;
        putAll(m);
    }

    public TreeMap(SortedMap<K, ? extends V> m) {
        comparator = m.comparator();
        fill(m.entrySet().iterator(), m.size(), false, null);
    }

    final int compare(Object a, Object b) {
        Comparator<? super K> order = comparator;
        if (order == null) {
            Comparable<Object> natural = (Comparable<Object>) a;
            return natural.compareTo(b);
        }
        return order.compare((K) a, (K) b);
    }

    private static int height(Entry<?, ?> n) {
        return n == null ? 0 : n.height;
    }

    static <K, V> Entry<K, V> successor(Entry<K, V> n) {
        Entry<K, V> at = n.right;
        if (at != null) {
            while (at.left != null) {
                at = at.left;
            }
            return at;
        }
        at = n.up;
        while (at != null && n == at.right) {
            n = at;
            at = at.up;
        }
        return at;
    }

    static <K, V> Entry<K, V> predecessor(Entry<K, V> n) {
        Entry<K, V> at = n.left;
        if (at != null) {
            while (at.right != null) {
                at = at.right;
            }
            return at;
        }
        at = n.up;
        while (at != null && n == at.left) {
            n = at;
            at = at.up;
        }
        return at;
    }

    final Entry<K, V> first() {
        Entry<K, V> at = root;
        if (at != null) {
            while (at.left != null) {
                at = at.left;
            }
        }
        return at;
    }

    final Entry<K, V> last() {
        Entry<K, V> at = root;
        if (at != null) {
            while (at.right != null) {
                at = at.right;
            }
        }
        return at;
    }

    // The node of a key, or null. Under the keys' own order a key that has none (or is
    // null) is refused even where there is nothing to compare it with.
    final Entry<K, V> find(Object key) {
        Comparator<? super K> given = comparator;
        Comparable<Object> natural = given == null ? (Comparable<Object>) Objects.requireNonNull(key) : null;
        Entry<K, V> at = root;
        while (at != null) {
            int order = natural != null ? natural.compareTo(at.key) : given.compare((K) key, at.key);
            if (order < 0) {
                at = at.left;
            }
            else if (order > 0) {
                at = at.right;
            }
            else {
                return at;
            }
        }
        return null;
    }

    // The node of the nearest key below, at or below, at or above, or above a key.
    final Entry<K, V> near(Object key, int how) {
        boolean upward = how >= CEILING;
        Entry<K, V> best = null;
        Entry<K, V> at = root;
        while (at != null) {
            int order = compare(key, at.key);
            if (order == 0 && (how == FLOOR || how == CEILING)) {
                return at;
            }
            if (upward ? order < 0 : order > 0) {
                best = at;
                at = upward ? at.left : at.right;
            }
            else {
                at = upward ? at.right : at.left;
            }
        }
        return best;
    }

    // The node that took n's place under n's parent.
    private void replace(Entry<K, V> n, Entry<K, V> taker) {
        Entry<K, V> parent = n.up;
        if (taker != null) {
            taker.up = parent;
        }
        if (parent == null) {
            root = taker;
        }
        else if (parent.left == n) {
            parent.left = taker;
        }
        else {
            parent.right = taker;
        }
    }

    private static <K, V> void measure(Entry<K, V> n) {
        int left = height(n.left);
        int right = height(n.right);
        n.height = (left > right ? left : right) + 1;
    }

    private Entry<K, V> turnLeft(Entry<K, V> n) {
        Entry<K, V> raised = n.right;
        Entry<K, V> moved = raised.left;
        n.right = moved;
        if (moved != null) {
            moved.up = n;
        }
        replace(n, raised);
        raised.left = n;
        n.up = raised;
        measure(n);
        measure(raised);
        return raised;
    }

    private Entry<K, V> turnRight(Entry<K, V> n) {
        Entry<K, V> raised = n.left;
        Entry<K, V> moved = raised.right;
        n.left = moved;
        if (moved != null) {
            moved.up = n;
        }
        replace(n, raised);
        raised.right = n;
        n.up = raised;
        measure(n);
        measure(raised);
        return raised;
    }

    // From a node whose subtree changed up to the root: each height again, a node whose
    // subtrees differ by two turned, and nothing more once a height is what it was.
    private void rebalance(Entry<K, V> n) {
        while (n != null) {
            int left = height(n.left);
            int right = height(n.right);
            if (left > right + 1) {
                Entry<K, V> child = n.left;
                if (height(child.left) < height(child.right)) {
                    turnLeft(child);
                }
                n = turnRight(n);
            }
            else if (right > left + 1) {
                Entry<K, V> child = n.right;
                if (height(child.right) < height(child.left)) {
                    turnRight(child);
                }
                n = turnLeft(n);
            }
            else {
                int now = (left > right ? left : right) + 1;
                if (now == n.height) {
                    return;
                }
                n.height = now;
            }
            n = n.up;
        }
    }

    // Unlinks a node; a node with two children gives its place to the next node.
    final void delete(Entry<K, V> node) {
        Entry<K, V> from;
        if (node.left != null && node.right != null) {
            Entry<K, V> next = node.right;
            while (next.left != null) {
                next = next.left;
            }
            if (next.up == node) {
                from = next;
            }
            else {
                from = next.up;
                Entry<K, V> rest = next.right;
                from.left = rest;
                if (rest != null) {
                    rest.up = from;
                }
                next.right = node.right;
                node.right.up = next;
            }
            next.left = node.left;
            node.left.up = next;
            next.height = node.height;
            replace(node, next);
        }
        else {
            from = node.up;
            replace(node, node.left != null ? node.left : node.right);
        }
        node.left = null;
        node.right = null;
        node.up = null;
        count--;
        changes++;
        rebalance(from);
    }

    // A tree of so many items an iterator gives in order: entries, or keys of one value.
    // The middle one is the root and each half a tree of its own.
    private Entry<K, V> built(Iterator<?> items, int n, boolean keys, V value) {
        if (n == 0) {
            return null;
        }
        int lower = (n - 1) / 2;
        Entry<K, V> left = built(items, lower, keys, value);
        Object item = items.next();
        Entry<K, V> node;
        if (keys) {
            node = new Entry<>((K) item, value, null);
        }
        else {
            Map.Entry<K, V> entry = (Map.Entry<K, V>) item;
            node = new Entry<>(entry.getKey(), entry.getValue(), null);
        }
        Entry<K, V> right = built(items, n - 1 - lower, keys, value);
        node.left = left;
        node.right = right;
        if (left != null) {
            left.up = node;
        }
        if (right != null) {
            right.up = node;
        }
        measure(node);
        return node;
    }

    // An empty tree made of items in this tree's order, with no comparison.
    // the same keys and values in a tree of its own, built from them in their order
    @SuppressWarnings("unchecked")
    public Object clone() {
        TreeMap<K, V> copy = (TreeMap<K, V>) rontolisp.internal.Support.copy(this);
        copy.root = null;
        copy.count = 0;
        copy.changes = 0;
        copy.fill(entrySet().iterator(), count, false, null);
        return copy;
    }

    final void fill(Iterator<?> items, int n, boolean keys, V value) {
        if (n > 0) {
            root = built(items, n, keys, value);
            count = n;
            changes++;
        }
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public Comparator<? super K> comparator() {
        return comparator;
    }

    public boolean containsKey(Object key) {
        return find(key) != null;
    }

    public boolean containsValue(Object value) {
        for (Entry<K, V> n = first(); n != null; n = successor(n)) {
            if (Objects.equals(value, n.value)) {
                return true;
            }
        }
        return false;
    }

    public V get(Object key) {
        Entry<K, V> node = find(key);
        return node == null ? null : node.value;
    }

    public V getOrDefault(Object key, V other) {
        Entry<K, V> node = find(key);
        return node == null ? other : node.value;
    }

    public V put(K key, V value) {
        Entry<K, V> at = root;
        if (at == null) {
            // a key nothing can be compared with is refused as a comparison of it would be
            compare(key, key);
            root = new Entry<>(key, value, null);
            count = 1;
            changes++;
            return null;
        }
        Entry<K, V> parent;
        int order;
        do {
            parent = at;
            order = compare(key, at.key);
            if (order < 0) {
                at = at.left;
            }
            else if (order > 0) {
                at = at.right;
            }
            else {
                return at.setValue(value);
            }
        }
        while (at != null);
        Entry<K, V> added = new Entry<>(key, value, parent);
        if (order < 0) {
            parent.left = added;
        }
        else {
            parent.right = added;
        }
        count++;
        changes++;
        rebalance(parent);
        return null;
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        if (count == 0 && m instanceof SortedMap<?, ?> sorted && Objects.equals(comparator, sorted.comparator())) {
            fill(m.entrySet().iterator(), m.size(), false, null);
            return;
        }
        super.putAll(m);
    }

    public V remove(Object key) {
        Entry<K, V> node = find(key);
        if (node == null) {
            return null;
        }
        V value = node.value;
        delete(node);
        return value;
    }

    public void clear() {
        if (root != null) {
            root = null;
            count = 0;
            changes++;
        }
    }

    // A function given the map may not change it: what it changed stays, what it
    // answered is not put.
    public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
        Objects.requireNonNull(function);
        Entry<K, V> node = find(key);
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
                put(key, made);
            }
        }
        return made;
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        Entry<K, V> node = find(key);
        if (node == null || node.value == null) {
            return null;
        }
        int before = changes;
        V made = function.apply(key, node.value);
        if (changes != before) {
            throw new ConcurrentModificationException();
        }
        settle(node, made);
        return made;
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        Entry<K, V> node = find(key);
        int before = changes;
        V made = function.apply(key, node == null ? null : node.value);
        if (changes != before) {
            throw new ConcurrentModificationException();
        }
        if (node != null) {
            settle(node, made);
        }
        else if (made != null) {
            put(key, made);
        }
        return made;
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> function) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(function);
        Entry<K, V> node = find(key);
        if (node == null) {
            put(key, value);
            return value;
        }
        if (node.value == null) {
            node.value = value;
            return value;
        }
        int before = changes;
        V made = function.apply(node.value, value);
        if (changes != before) {
            throw new ConcurrentModificationException();
        }
        settle(node, made);
        return made;
    }

    // a node's new value, or the node gone where there is none
    private void settle(Entry<K, V> node, V made) {
        if (made == null) {
            delete(node);
        }
        else {
            node.value = made;
        }
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        int expected = changes;
        for (Entry<K, V> n = first(); n != null; n = successor(n)) {
            action.accept(n.key, n.value);
            if (changes != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        int expected = changes;
        for (Entry<K, V> n = first(); n != null; n = successor(n)) {
            n.value = function.apply(n.key, n.value);
            if (changes != expected) {
                throw new ConcurrentModificationException();
            }
        }
    }

    // What the navigation answers: a copy of the entry nothing changes.
    static <K, V> Map.Entry<K, V> snapshot(Entry<K, V> n) {
        return n == null ? null : new AbstractMap.SimpleImmutableEntry<>(n.key, n.value);
    }

    static <K> K key(Entry<K, ?> n) {
        if (n == null) {
            throw new NoSuchElementException();
        }
        return n.key;
    }

    static <K> K keyOrNull(Entry<K, ?> n) {
        return n == null ? null : n.key;
    }

    final Map.Entry<K, V> taken(Entry<K, V> n) {
        if (n == null) {
            return null;
        }
        Map.Entry<K, V> entry = snapshot(n);
        delete(n);
        return entry;
    }

    public K firstKey() {
        return key(first());
    }

    public K lastKey() {
        return key(last());
    }

    public Map.Entry<K, V> firstEntry() {
        return snapshot(first());
    }

    public Map.Entry<K, V> lastEntry() {
        return snapshot(last());
    }

    public Map.Entry<K, V> pollFirstEntry() {
        return taken(first());
    }

    public Map.Entry<K, V> pollLastEntry() {
        return taken(last());
    }

    public Map.Entry<K, V> lowerEntry(K key) {
        return snapshot(near(key, LOWER));
    }

    public K lowerKey(K key) {
        return keyOrNull(near(key, LOWER));
    }

    public Map.Entry<K, V> floorEntry(K key) {
        return snapshot(near(key, FLOOR));
    }

    public K floorKey(K key) {
        return keyOrNull(near(key, FLOOR));
    }

    public Map.Entry<K, V> ceilingEntry(K key) {
        return snapshot(near(key, CEILING));
    }

    public K ceilingKey(K key) {
        return keyOrNull(near(key, CEILING));
    }

    public Map.Entry<K, V> higherEntry(K key) {
        return snapshot(near(key, HIGHER));
    }

    public K higherKey(K key) {
        return keyOrNull(near(key, HIGHER));
    }

    public Set<K> keySet() {
        return navigableKeySet();
    }

    public NavigableSet<K> navigableKeySet() {
        return new KeySet<>(this);
    }

    public NavigableSet<K> descendingKeySet() {
        return descendingMap().navigableKeySet();
    }

    public Collection<V> values() {
        return new Values<>(this, null);
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return new EntrySet<>(this, null);
    }

    public NavigableMap<K, V> descendingMap() {
        return new View<>(this, true, true, null, false, true, null, false, true);
    }

    public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        return View.ranged(this, false, false, fromKey, fromInclusive, false, toKey, toInclusive);
    }

    public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        return View.ranged(this, false, true, null, false, false, toKey, inclusive);
    }

    public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        return View.ranged(this, false, false, fromKey, inclusive, true, null, false);
    }

    public SortedMap<K, V> subMap(K fromKey, K toKey) {
        return subMap(fromKey, true, toKey, false);
    }

    public SortedMap<K, V> headMap(K toKey) {
        return headMap(toKey, false);
    }

    public SortedMap<K, V> tailMap(K fromKey) {
        return tailMap(fromKey, true);
    }

    // The nodes of the tree or of a view, with or against the map's order.
    final Walk<K, V, ?> walk(int part, boolean down) {
        return down ? new Walk<>(this, last(), null, true, part) : new Walk<>(this, first(), null, false, part);
    }

    static <K> Iterator<K> keys(NavigableMap<K, ?> map, boolean down) {
        Walk<K, ?, ?> walk = map instanceof View ? ((View<K, ?>) map).walk(KEYS, down)
                : ((TreeMap<K, ?>) map).walk(KEYS, down);
        return (Iterator<K>) walk;
    }

    // The spliterator of the keys of the tree or of a view, as a JDK's reports: the
    // tree's sorted with its size, the descending map's of the tree with its size, any
    // other view's of a size not known, sorted where it is in the tree's order.
    static <K> Spliterator<K> keySplit(NavigableMap<K, ?> map, Collection<K> keys) {
        if (!(map instanceof View)) {
            return Splits.sorted(keys, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED
                    | Spliterator.SIZED, map.comparator(), -1);
        }
        View<K, ?> view = (View<K, ?>) map;
        if (view.whole) {
            return Splits.of(keys, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SIZED);
        }
        if (view.descending) {
            return Splits.unsized(keys, Spliterator.ORDERED | Spliterator.DISTINCT);
        }
        return Splits.sorted(keys, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED,
                view.tree.comparator, Long.MAX_VALUE);
    }

    static final class Walk<K, V, T> implements Iterator<T> {

        private final TreeMap<K, V> tree;

        private Entry<K, V> ahead;

        // the first node not to give, or null for the end of the tree
        private final Entry<K, V> fence;

        private final boolean down;

        private final int part;

        private Entry<K, V> last;

        private int expected;

        Walk(TreeMap<K, V> tree, Entry<K, V> ahead, Entry<K, V> fence, boolean down, int part) {
            this.tree = tree;
            this.ahead = ahead == fence ? null : ahead;
            this.fence = fence;
            this.down = down;
            this.part = part;
            this.expected = tree.changes;
        }

        public boolean hasNext() {
            return ahead != null;
        }

        public T next() {
            Entry<K, V> n = ahead;
            if (n == null) {
                throw new NoSuchElementException();
            }
            if (tree.changes != expected) {
                throw new ConcurrentModificationException();
            }
            Entry<K, V> next = down ? predecessor(n) : successor(n);
            ahead = next == fence ? null : next;
            last = n;
            Object given = part == KEYS ? n.key : part == VALUES ? n.value : n;
            return (T) given;
        }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            if (tree.changes != expected) {
                throw new ConcurrentModificationException();
            }
            tree.delete(last);
            last = null;
            expected = tree.changes;
        }
    }

    // The keys of the tree or of a view: a TreeSet over the map, to which nothing is added.
    static final class KeySet<K> extends TreeSet<K> {

        KeySet(NavigableMap<K, ?> map) {
            super((NavigableMap<K, Object>) map);
        }

        NavigableSet<K> made(NavigableMap<K, Object> m) {
            return new KeySet<>(m);
        }

        public boolean add(K e) {
            throw new UnsupportedOperationException();
        }

        public boolean addAll(Collection<? extends K> c) {
            if (c.isEmpty()) {
                return false;
            }
            throw new UnsupportedOperationException();
        }
    }

    // The values of the tree or of a view.
    static final class Values<K, V> extends AbstractCollection<V> {

        private final TreeMap<K, V> tree;

        private final View<K, V> view;

        Values(TreeMap<K, V> tree, View<K, V> view) {
            this.tree = tree;
            this.view = view;
        }

        public int size() {
            return view == null ? tree.count : view.size();
        }

        public boolean isEmpty() {
            return view == null ? tree.count == 0 : view.isEmpty();
        }

        public boolean contains(Object o) {
            return view == null ? tree.containsValue(o) : view.containsValue(o);
        }

        public void clear() {
            if (view == null) {
                tree.clear();
            }
            else {
                view.clear();
            }
        }

        public Iterator<V> iterator() {
            Walk<K, V, ?> walk = view == null ? tree.walk(VALUES, false) : view.walk(VALUES, false);
            return (Iterator<V>) walk;
        }

        public Spliterator<V> spliterator() {
            return Splits.of(this, view == null ? Spliterator.ORDERED | Spliterator.SIZED
                    : Spliterator.SIZED | Spliterator.SUBSIZED);
        }
    }

    // The entries of the tree or of a view: its nodes, whose value can be set.
    static final class EntrySet<K, V> extends AbstractSet<Map.Entry<K, V>> {

        private final TreeMap<K, V> tree;

        private final View<K, V> view;

        EntrySet(TreeMap<K, V> tree, View<K, V> view) {
            this.tree = tree;
            this.view = view;
        }

        public int size() {
            return view == null ? tree.count : view.size();
        }

        public boolean isEmpty() {
            return view == null ? tree.count == 0 : view.isEmpty();
        }

        // the node of an entry equal to o, or null
        private Entry<K, V> held(Object o) {
            if (!(o instanceof Map.Entry)) {
                return null;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            Object key = entry.getKey();
            if (view != null && !view.inRange(key)) {
                return null;
            }
            Entry<K, V> node = tree.find(key);
            return node != null && Objects.equals(node.value, entry.getValue()) ? node : null;
        }

        public boolean contains(Object o) {
            return held(o) != null;
        }

        public boolean remove(Object o) {
            Entry<K, V> node = held(o);
            if (node == null) {
                return false;
            }
            tree.delete(node);
            return true;
        }

        public void clear() {
            if (view == null) {
                tree.clear();
            }
            else {
                view.clear();
            }
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            Walk<K, V, ?> walk = view == null ? tree.walk(ENTRIES, false) : view.walk(ENTRIES, false);
            return (Iterator<Map.Entry<K, V>>) walk;
        }

        public Spliterator<Map.Entry<K, V>> spliterator() {
            if (view != null) {
                return Splits.of(this, Spliterator.DISTINCT | Spliterator.SIZED | Spliterator.SUBSIZED);
            }
            TreeMap<K, V> order = tree;
            Comparator<Map.Entry<K, V>> byKey = (Map.Entry<K, V> a, Map.Entry<K, V> b) -> order.compare(a.getKey(),
                    b.getKey());
            return Splits.sorted(this, Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED
                    | Spliterator.SIZED, byKey, -1);
        }
    }

    // The keys between two bounds (each one there or not, holding its key or not), in
    // the tree's order or against it. The bounds and the order are the tree's own:
    // against it, the low bound is where the view ends.
    static final class View<K, V> extends AbstractMap<K, V> implements NavigableMap<K, V>, Serializable {

        final TreeMap<K, V> tree;

        final boolean descending;

        private final boolean fromStart;

        private final K lo;

        private final boolean loInclusive;

        private final boolean toEnd;

        private final K hi;

        private final boolean hiInclusive;

        // the descending map of the tree itself, whose keys report their number
        final boolean whole;

        View(TreeMap<K, V> tree, boolean descending, boolean fromStart, K lo, boolean loInclusive, boolean toEnd, K hi,
                boolean hiInclusive, boolean whole) {
            this.tree = tree;
            this.descending = descending;
            this.fromStart = fromStart;
            this.lo = lo;
            this.loInclusive = loInclusive;
            this.toEnd = toEnd;
            this.hi = hi;
            this.hiInclusive = hiInclusive;
            this.whole = whole;
        }

        // A new range: its two bounds in order, or its one bound one the order can compare.
        static <K, V> View<K, V> ranged(TreeMap<K, V> tree, boolean descending, boolean fromStart, K lo,
                boolean loInclusive, boolean toEnd, K hi, boolean hiInclusive) {
            if (!fromStart && !toEnd) {
                if (tree.compare(lo, hi) > 0) {
                    throw new IllegalArgumentException("fromKey > toKey");
                }
            }
            else if (!fromStart) {
                tree.compare(lo, lo);
            }
            else if (!toEnd) {
                tree.compare(hi, hi);
            }
            return new View<>(tree, descending, fromStart, lo, loInclusive, toEnd, hi, hiInclusive, false);
        }

        private boolean tooLow(Object key) {
            if (fromStart) {
                return false;
            }
            int order = tree.compare(key, lo);
            return order < 0 || (order == 0 && !loInclusive);
        }

        private boolean tooHigh(Object key) {
            if (toEnd) {
                return false;
            }
            int order = tree.compare(key, hi);
            return order > 0 || (order == 0 && !hiInclusive);
        }

        boolean inRange(Object key) {
            return !tooLow(key) && !tooHigh(key);
        }

        // A bound of a view of this view: inside it, or on a bound of it the new one
        // leaves out too.
        private boolean bounds(Object key, boolean inclusive) {
            if (inclusive) {
                return inRange(key);
            }
            return (fromStart || tree.compare(key, lo) >= 0) && (toEnd || tree.compare(key, hi) <= 0);
        }

        private TreeMap.Entry<K, V> low() {
            TreeMap.Entry<K, V> n = fromStart ? tree.first() : tree.near(lo, loInclusive ? CEILING : HIGHER);
            return n == null || tooHigh(n.key) ? null : n;
        }

        private TreeMap.Entry<K, V> high() {
            TreeMap.Entry<K, V> n = toEnd ? tree.last() : tree.near(hi, hiInclusive ? FLOOR : LOWER);
            return n == null || tooLow(n.key) ? null : n;
        }

        // the node past the range in the tree's order, or null at the end of the tree
        private TreeMap.Entry<K, V> highFence() {
            return toEnd ? null : tree.near(hi, hiInclusive ? HIGHER : CEILING);
        }

        private TreeMap.Entry<K, V> lowFence() {
            return fromStart ? null : tree.near(lo, loInclusive ? LOWER : FLOOR);
        }

        // the node of the least key above, or at or above, a key in the range
        private TreeMap.Entry<K, V> above(Object key, boolean inclusive) {
            if (tooLow(key)) {
                return low();
            }
            TreeMap.Entry<K, V> n = tree.near(key, inclusive ? CEILING : HIGHER);
            return n == null || tooHigh(n.key) ? null : n;
        }

        private TreeMap.Entry<K, V> below(Object key, boolean inclusive) {
            if (tooHigh(key)) {
                return high();
            }
            TreeMap.Entry<K, V> n = tree.near(key, inclusive ? FLOOR : LOWER);
            return n == null || tooLow(n.key) ? null : n;
        }

        private TreeMap.Entry<K, V> first() {
            return descending ? high() : low();
        }

        private TreeMap.Entry<K, V> last() {
            return descending ? low() : high();
        }

        Walk<K, V, ?> walk(int part, boolean against) {
            if (descending != against) {
                TreeMap.Entry<K, V> start = high();
                return new Walk<>(tree, start, lowFence(), true, part);
            }
            TreeMap.Entry<K, V> start = low();
            return new Walk<>(tree, start, highFence(), false, part);
        }

        public int size() {
            if (fromStart && toEnd) {
                return tree.count;
            }
            int n = 0;
            TreeMap.Entry<K, V> at = low();
            TreeMap.Entry<K, V> fence = highFence();
            for (; at != null && at != fence; at = successor(at)) {
                n++;
            }
            return n;
        }

        public boolean isEmpty() {
            return fromStart && toEnd ? tree.count == 0 : low() == null;
        }

        public boolean containsKey(Object key) {
            return inRange(key) && tree.containsKey(key);
        }

        public V get(Object key) {
            return inRange(key) ? tree.get(key) : null;
        }

        public V put(K key, V value) {
            if (!inRange(key)) {
                throw new IllegalArgumentException("key out of range");
            }
            return tree.put(key, value);
        }

        public V remove(Object key) {
            return inRange(key) ? tree.remove(key) : null;
        }

        public void clear() {
            if (fromStart && toEnd) {
                tree.clear();
                return;
            }
            TreeMap.Entry<K, V> at = low();
            TreeMap.Entry<K, V> fence = highFence();
            while (at != null && at != fence) {
                TreeMap.Entry<K, V> next = successor(at);
                tree.delete(at);
                at = next;
            }
        }

        public Comparator<? super K> comparator() {
            Comparator<? super K> order = tree.comparator;
            if (!descending) {
                return order;
            }
            return order == null ? Collections.reverseOrder() : Collections.reverseOrder(order);
        }

        public K firstKey() {
            return key(first());
        }

        public K lastKey() {
            return key(last());
        }

        public Map.Entry<K, V> firstEntry() {
            return snapshot(first());
        }

        public Map.Entry<K, V> lastEntry() {
            return snapshot(last());
        }

        public Map.Entry<K, V> pollFirstEntry() {
            return tree.taken(first());
        }

        public Map.Entry<K, V> pollLastEntry() {
            return tree.taken(last());
        }

        public Map.Entry<K, V> lowerEntry(K key) {
            return snapshot(descending ? above(key, false) : below(key, false));
        }

        public K lowerKey(K key) {
            return keyOrNull(descending ? above(key, false) : below(key, false));
        }

        public Map.Entry<K, V> floorEntry(K key) {
            return snapshot(descending ? above(key, true) : below(key, true));
        }

        public K floorKey(K key) {
            return keyOrNull(descending ? above(key, true) : below(key, true));
        }

        public Map.Entry<K, V> ceilingEntry(K key) {
            return snapshot(descending ? below(key, true) : above(key, true));
        }

        public K ceilingKey(K key) {
            return keyOrNull(descending ? below(key, true) : above(key, true));
        }

        public Map.Entry<K, V> higherEntry(K key) {
            return snapshot(descending ? below(key, false) : above(key, false));
        }

        public K higherKey(K key) {
            return keyOrNull(descending ? below(key, false) : above(key, false));
        }

        public Set<K> keySet() {
            return navigableKeySet();
        }

        public NavigableSet<K> navigableKeySet() {
            return new KeySet<>(this);
        }

        public NavigableSet<K> descendingKeySet() {
            return descendingMap().navigableKeySet();
        }

        public Collection<V> values() {
            return new TreeMap.Values<>(tree, this);
        }

        public Set<Map.Entry<K, V>> entrySet() {
            return new EntrySet<>(tree, this);
        }

        public NavigableMap<K, V> descendingMap() {
            return new View<>(tree, !descending, fromStart, lo, loInclusive, toEnd, hi, hiInclusive, false);
        }

        // A view of this view, its bounds named as this view's order names them.
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            if (!bounds(fromKey, fromInclusive)) {
                throw new IllegalArgumentException("fromKey out of range");
            }
            if (!bounds(toKey, toInclusive)) {
                throw new IllegalArgumentException("toKey out of range");
            }
            return descending ? ranged(tree, true, false, toKey, toInclusive, false, fromKey, fromInclusive)
                    : ranged(tree, false, false, fromKey, fromInclusive, false, toKey, toInclusive);
        }

        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            if (!bounds(toKey, inclusive)) {
                throw new IllegalArgumentException("toKey out of range");
            }
            return descending ? ranged(tree, true, false, toKey, inclusive, toEnd, hi, hiInclusive)
                    : ranged(tree, false, fromStart, lo, loInclusive, false, toKey, inclusive);
        }

        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            if (!bounds(fromKey, inclusive)) {
                throw new IllegalArgumentException("fromKey out of range");
            }
            return descending ? ranged(tree, true, fromStart, lo, loInclusive, false, fromKey, inclusive)
                    : ranged(tree, false, false, fromKey, inclusive, toEnd, hi, hiInclusive);
        }

        public SortedMap<K, V> subMap(K fromKey, K toKey) {
            return subMap(fromKey, true, toKey, false);
        }

        public SortedMap<K, V> headMap(K toKey) {
            return headMap(toKey, false);
        }

        public SortedMap<K, V> tailMap(K fromKey) {
            return tailMap(fromKey, true);
        }
    }
}
