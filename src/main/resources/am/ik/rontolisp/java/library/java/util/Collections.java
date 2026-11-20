// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

public final class Collections {

    private Collections() {
    }

    public static <T extends Comparable<? super T>> void sort(List<T> list) {
        list.sort(null);
    }

    public static <T> void sort(List<T> list, Comparator<? super T> c) {
        list.sort(c);
    }

    // The index of a key in a list in its order, or -(where it would go) - 1.
    public static <T> int binarySearch(List<? extends Comparable<? super T>> list, T key) {
        int low = 0;
        int high = list.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = ((Comparable) list.get(middle)).compareTo(key);
            if (order < 0) {
                low = middle + 1;
            }
            else if (order > 0) {
                high = middle - 1;
            }
            else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public static <T> int binarySearch(List<? extends T> list, T key, Comparator<? super T> c) {
        if (c == null) {
            List raw = list;
            return binarySearch(raw, key);
        }
        int low = 0;
        int high = list.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = c.compare(list.get(middle), key);
            if (order < 0) {
                low = middle + 1;
            }
            else if (order > 0) {
                high = middle - 1;
            }
            else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public static void swap(List<?> list, int i, int j) {
        List raw = list;
        Object moved = raw.get(i);
        raw.set(i, raw.get(j));
        raw.set(j, moved);
    }

    public static void reverse(List<?> list) {
        for (int i = 0, j = list.size() - 1; i < j; i++, j--) {
            swap(list, i, j);
        }
    }

    // From the last element down to the second, each swapped with one before it or itself.
    public static void shuffle(List<?> list, Random random) {
        for (int i = list.size(); i > 1; i--) {
            swap(list, i - 1, random.nextInt(i));
        }
    }

    public static void shuffle(List<?> list) {
        shuffle(list, new Random());
    }

    public static <T extends Comparable<? super T>> T max(Collection<? extends T> c) {
        Iterator<? extends T> each = c.iterator();
        T best = each.next();
        while (each.hasNext()) {
            T next = each.next();
            if (next.compareTo(best) > 0) {
                best = next;
            }
        }
        return best;
    }

    public static <T> T max(Collection<? extends T> c, Comparator<? super T> order) {
        Iterator<? extends T> each = c.iterator();
        T best = each.next();
        while (each.hasNext()) {
            T next = each.next();
            if (order.compare(next, best) > 0) {
                best = next;
            }
        }
        return best;
    }

    public static <T extends Comparable<? super T>> T min(Collection<? extends T> c) {
        Iterator<? extends T> each = c.iterator();
        T best = each.next();
        while (each.hasNext()) {
            T next = each.next();
            if (next.compareTo(best) < 0) {
                best = next;
            }
        }
        return best;
    }

    public static <T> T min(Collection<? extends T> c, Comparator<? super T> order) {
        Iterator<? extends T> each = c.iterator();
        T best = each.next();
        while (each.hasNext()) {
            T next = each.next();
            if (order.compare(next, best) < 0) {
                best = next;
            }
        }
        return best;
    }

    public static int frequency(Collection<?> c, Object o) {
        int found = 0;
        for (Object e : c) {
            if (o == null ? e == null : o.equals(e)) {
                found++;
            }
        }
        return found;
    }

    @SafeVarargs
    public static <T> boolean addAll(Collection<? super T> c, T... elements) {
        boolean changed = false;
        for (T element : elements) {
            if (c.add(element)) {
                changed = true;
            }
        }
        return changed;
    }

    public static <T> void fill(List<? super T> list, T value) {
        int n = list.size();
        for (int i = 0; i < n; i++) {
            list.set(i, value);
        }
    }

    public static <T> List<T> nCopies(int n, T value) {
        if (n < 0) {
            throw new IllegalArgumentException("List length = " + n);
        }
        Object[] held = new Object[n];
        for (int i = 0; i < n; i++) {
            held[i] = value;
        }
        return new ImmutableCollections.ListN<>(held);
    }

    public static <T> List<T> emptyList() {
        return new ImmutableCollections.ListN<>(new Object[0], ImmutableCollections.EMPTY);
    }

    public static <T> Set<T> emptySet() {
        return new ImmutableCollections.SetN<>(new HashSet<T>(), ImmutableCollections.EMPTY);
    }

    public static <K, V> Map<K, V> emptyMap() {
        return new ImmutableCollections.MapN<>(new HashMap<K, V>(), ImmutableCollections.EMPTY);
    }

    public static <T> List<T> singletonList(T value) {
        Object[] held = new Object[1];
        held[0] = value;
        return new ImmutableCollections.ListN<>(held, ImmutableCollections.ONE);
    }

    public static <T> Set<T> singleton(T value) {
        HashSet<T> held = new HashSet<>();
        held.add(value);
        return new ImmutableCollections.SetN<>(held, ImmutableCollections.ONE);
    }

    public static <T> List<T> unmodifiableList(List<? extends T> list) {
        List<T> held = (List<T>) list;
        return new ImmutableCollections.ListView<>(held);
    }

    public static <T> Set<T> unmodifiableSet(Set<? extends T> set) {
        Set<T> held = (Set<T>) set;
        return new ImmutableCollections.SetN<>(held, ImmutableCollections.HELD);
    }

    public static <T> Collection<T> unmodifiableCollection(Collection<? extends T> c) {
        Collection<T> held = (Collection<T>) c;
        return new ImmutableCollections.Unchanging<>(held, ImmutableCollections.HELD);
    }

    public static <K, V> Map<K, V> unmodifiableMap(Map<? extends K, ? extends V> map) {
        Map<K, V> held = (Map<K, V>) map;
        return new ImmutableCollections.MapN<>(held, ImmutableCollections.HELD);
    }

    public static <T> SequencedCollection<T> unmodifiableSequencedCollection(SequencedCollection<? extends T> c) {
        SequencedCollection<T> held = (SequencedCollection<T>) Objects.requireNonNull(c);
        return new ImmutableCollections.SequencedView<>(held);
    }

    public static <T> SequencedSet<T> unmodifiableSequencedSet(SequencedSet<? extends T> s) {
        SequencedSet<T> held = (SequencedSet<T>) Objects.requireNonNull(s);
        return new ImmutableCollections.SequencedSetView<>(held);
    }

    public static <K, V> SequencedMap<K, V> unmodifiableSequencedMap(SequencedMap<? extends K, ? extends V> m) {
        SequencedMap<K, V> held = (SequencedMap<K, V>) Objects.requireNonNull(m);
        return new ImmutableCollections.SequencedMapView<>(held);
    }

    public static <E> Set<E> newSetFromMap(Map<E, Boolean> map) {
        if (!map.isEmpty()) {
            throw new IllegalArgumentException("Map is non-empty");
        }
        return new SetFromMap<>(map);
    }

    public static <E> SequencedSet<E> newSequencedSetFromMap(SequencedMap<E, Boolean> map) {
        if (!map.isEmpty()) {
            throw new IllegalArgumentException("Map is non-empty");
        }
        return new SequencedSetFromMap<>(map);
    }

    // The set newSetFromMap answers: an element is a key the map holds TRUE for.
    static class SetFromMap<E> extends AbstractSet<E> {

        final Map<E, Boolean> map;

        SetFromMap(Map<E, Boolean> map) {
            this.map = map;
        }

        public int size() {
            return map.size();
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public boolean contains(Object o) {
            return map.containsKey(o);
        }

        public boolean add(E e) {
            return map.put(e, Boolean.TRUE) == null;
        }

        public boolean remove(Object o) {
            return map.remove(o) != null;
        }

        public void clear() {
            map.clear();
        }

        public Iterator<E> iterator() {
            return map.keySet().iterator();
        }

        public Spliterator<E> spliterator() {
            return map.keySet().spliterator();
        }

        public String toString() {
            return map.keySet().toString();
        }
    }

    // newSequencedSetFromMap: its ends are the map's, reversed it is over the map reversed.
    static final class SequencedSetFromMap<E> extends SetFromMap<E> implements SequencedSet<E> {

        private final SequencedMap<E, Boolean> sequenced;

        SequencedSetFromMap(SequencedMap<E, Boolean> sequenced) {
            super(sequenced);
            this.sequenced = sequenced;
        }

        private static <E> E key(Map.Entry<E, Boolean> entry) {
            if (entry == null) {
                throw new NoSuchElementException();
            }
            return entry.getKey();
        }

        public SequencedSet<E> reversed() {
            return new SequencedSetFromMap<>(sequenced.reversed());
        }

        public void addFirst(E e) {
            sequenced.putFirst(e, Boolean.TRUE);
        }

        public void addLast(E e) {
            sequenced.putLast(e, Boolean.TRUE);
        }

        public E getFirst() {
            return key(sequenced.firstEntry());
        }

        public E getLast() {
            return key(sequenced.lastEntry());
        }

        public E removeFirst() {
            return key(sequenced.pollFirstEntry());
        }

        public E removeLast() {
            return key(sequenced.pollLastEntry());
        }
    }

    public static <T> SortedSet<T> unmodifiableSortedSet(SortedSet<T> set) {
        return new ImmutableCollections.SortedView<>(Objects.requireNonNull(set));
    }

    public static <T> NavigableSet<T> unmodifiableNavigableSet(NavigableSet<T> set) {
        return new ImmutableCollections.NavigableView<>(Objects.requireNonNull(set));
    }

    public static <K, V> SortedMap<K, V> unmodifiableSortedMap(SortedMap<K, ? extends V> map) {
        SortedMap<K, V> held = (SortedMap<K, V>) Objects.requireNonNull(map);
        return new ImmutableCollections.SortedMapView<>(held);
    }

    public static <K, V> NavigableMap<K, V> unmodifiableNavigableMap(NavigableMap<K, ? extends V> map) {
        NavigableMap<K, V> held = (NavigableMap<K, V>) Objects.requireNonNull(map);
        return new ImmutableCollections.NavigableMapView<>(held);
    }

    // The empty sorted collections: views over an empty tree that nothing changes.
    public static <E> SortedSet<E> emptySortedSet() {
        return emptyNavigableSet();
    }

    public static <E> NavigableSet<E> emptyNavigableSet() {
        return new ImmutableCollections.NavigableView<>(new TreeSet<E>());
    }

    public static <K, V> SortedMap<K, V> emptySortedMap() {
        return emptyNavigableMap();
    }

    public static <K, V> NavigableMap<K, V> emptyNavigableMap() {
        return new ImmutableCollections.NavigableMapView<>(new TreeMap<K, V>());
    }

    public static <T> Collection<T> synchronizedCollection(Collection<T> c) {
        return new Synchronized.Each<>(c, null);
    }

    public static <T> List<T> synchronizedList(List<T> list) {
        return new Synchronized.Listed<>(list, null);
    }

    public static <T> Set<T> synchronizedSet(Set<T> set) {
        return new Synchronized.SetOf<>(set, null);
    }

    public static <K, V> Map<K, V> synchronizedMap(Map<K, V> map) {
        return new Synchronized.MapOf<>(map, null);
    }

    public static <T> SortedSet<T> synchronizedSortedSet(SortedSet<T> set) {
        return new Synchronized.SortedSetOf<>(set, null);
    }

    public static <T> NavigableSet<T> synchronizedNavigableSet(NavigableSet<T> set) {
        return new Synchronized.NavigableSetOf<>(set, null);
    }

    public static <K, V> SortedMap<K, V> synchronizedSortedMap(SortedMap<K, V> map) {
        return new Synchronized.SortedMapOf<>(map, null);
    }

    public static <K, V> NavigableMap<K, V> synchronizedNavigableMap(NavigableMap<K, V> map) {
        return new Synchronized.NavigableMapOf<>(map, null);
    }

    // The element at i moves to (i + distance) mod size: three reversals.
    public static void rotate(List<?> list, int distance) {
        int size = list.size();
        if (size == 0) {
            return;
        }
        int shift = distance % size;
        if (shift < 0) {
            shift += size;
        }
        if (shift == 0) {
            return;
        }
        reverse(list.subList(0, size - shift));
        reverse(list.subList(size - shift, size));
        reverse(list);
    }

    public static <T> boolean replaceAll(List<T> list, T oldVal, T newVal) {
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            T held = list.get(i);
            if (oldVal == null ? held == null : oldVal.equals(held)) {
                list.set(i, newVal);
                replaced = true;
            }
        }
        return replaced;
    }

    private static boolean sublistAt(List<?> source, List<?> target, int at) {
        for (int i = 0; i < target.size(); i++) {
            Object wanted = target.get(i);
            Object held = source.get(at + i);
            if (!(wanted == null ? held == null : wanted.equals(held))) {
                return false;
            }
        }
        return true;
    }

    public static int indexOfSubList(List<?> source, List<?> target) {
        for (int at = 0; at <= source.size() - target.size(); at++) {
            if (sublistAt(source, target, at)) {
                return at;
            }
        }
        return -1;
    }

    public static int lastIndexOfSubList(List<?> source, List<?> target) {
        for (int at = source.size() - target.size(); at >= 0; at--) {
            if (sublistAt(source, target, at)) {
                return at;
            }
        }
        return -1;
    }

    public static <T> Comparator<T> reverseOrder() {
        return (T a, T b) -> ((Comparable) b).compareTo(a);
    }

    public static <T> Comparator<T> reverseOrder(Comparator<T> order) {
        if (order == null) {
            return reverseOrder();
        }
        return (T a, T b) -> order.compare(b, a);
    }

    // the elements an enumeration answers, in its order
    public static <T> ArrayList<T> list(Enumeration<T> e) {
        ArrayList<T> listed = new ArrayList<>();
        while (e.hasMoreElements()) {
            listed.add(e.nextElement());
        }
        return listed;
    }

    // the elements of a collection, in its iterator's order
    public static <T> Enumeration<T> enumeration(Collection<T> c) {
        Iterator<T> elements = c.iterator();
        return new Enumeration<T>() {
            public boolean hasMoreElements() {
                return elements.hasNext();
            }

            public T nextElement() {
                return elements.next();
            }
        };
    }
}
