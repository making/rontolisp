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
        return new ImmutableCollections.ListN<>(new Object[0]);
    }

    public static <T> Set<T> emptySet() {
        return new ImmutableCollections.SetN<>(new HashSet<T>());
    }

    public static <K, V> Map<K, V> emptyMap() {
        return new ImmutableCollections.MapN<>(new HashMap<K, V>());
    }

    public static <T> List<T> singletonList(T value) {
        Object[] held = new Object[1];
        held[0] = value;
        return new ImmutableCollections.ListN<>(held);
    }

    public static <T> Set<T> singleton(T value) {
        HashSet<T> held = new HashSet<>();
        held.add(value);
        return new ImmutableCollections.SetN<>(held);
    }

    public static <T> List<T> unmodifiableList(List<? extends T> list) {
        List<T> held = (List<T>) list;
        return new ImmutableCollections.ListView<>(held);
    }

    public static <T> Set<T> unmodifiableSet(Set<? extends T> set) {
        Set<T> held = (Set<T>) set;
        return new ImmutableCollections.SetN<>(held);
    }

    public static <T> Collection<T> unmodifiableCollection(Collection<? extends T> c) {
        Collection<T> held = (Collection<T>) c;
        return new ImmutableCollections.Unchanging<>(held);
    }

    public static <K, V> Map<K, V> unmodifiableMap(Map<? extends K, ? extends V> map) {
        Map<K, V> held = (Map<K, V>) map;
        return new ImmutableCollections.MapN<>(held);
    }

    public static <T> Comparator<T> reverseOrder() {
        return (T a, T b) -> ((Comparable) b).compareTo(a);
    }

    public static <T> Comparator<T> reverseOrder(Comparator<T> order) {
        return (T a, T b) -> order.compare(b, a);
    }
}
