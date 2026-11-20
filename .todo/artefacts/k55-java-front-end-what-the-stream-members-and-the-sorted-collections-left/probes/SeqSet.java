import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class SeqSet {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    static String marks(Spliterator<?> s) {
        int c = s.characteristics();
        StringBuilder b = new StringBuilder();
        String[] names = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] bits = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        for (int i = 0; i < bits.length; i++) {
            if ((c & bits[i]) != 0) {
                b.append(b.length() == 0 ? "" : "|").append(names[i]);
            }
        }
        return b.toString();
    }

    static String split(Spliterator<?> s) {
        StringBuilder out = new StringBuilder(marks(s) + " " + s.estimateSize());
        s.tryAdvance(x -> out.append(" first=").append(x));
        out.append(" then ").append(s.estimateSize());
        return out.toString();
    }

    static void set(String label, SequencedSet<String> s) {
        SequencedSet<String> r = s.reversed();
        System.out.println(label + " " + s + " " + r + " " + (r.reversed() == s) + " " + r.size() + " " + r.isEmpty() + " " + r.equals(s) + " " + (r.hashCode() == s.hashCode()) + " " + r.contains("c"));
        attempt(label + " first", () -> r.getFirst() + " " + r.getLast() + " " + s.getFirst() + " " + s.getLast());
        attempt(label + " add(x)", () -> r.add("x") + " " + s + " " + r);
        attempt(label + " add(a)", () -> r.add("a") + " " + s + " " + r);
        attempt(label + " addFirst(y)", () -> { r.addFirst("y"); return s + " " + r; });
        attempt(label + " addLast(z)", () -> { r.addLast("z"); return s + " " + r; });
        attempt(label + " addFirst(b)", () -> { r.addFirst("b"); return s + " " + r; });
        attempt(label + " addLast(b)", () -> { r.addLast("b"); return s + " " + r; });
        attempt(label + " s.addFirst(c)", () -> { s.addFirst("c"); return s + " " + r; });
        attempt(label + " s.addLast(c)", () -> { s.addLast("c"); return s + " " + r; });
        attempt(label + " s.addFirst(new)", () -> { s.addFirst("n"); return s + " " + r; });
        attempt(label + " s.addLast(new)", () -> { s.addLast("m"); return s + " " + r; });
        attempt(label + " remove(a)", () -> r.remove("a") + " " + s + " " + r);
        attempt(label + " removeFirst", () -> r.removeFirst() + " " + s + " " + r);
        attempt(label + " removeLast", () -> r.removeLast() + " " + s + " " + r);
        attempt(label + " s.removeFirst", () -> s.removeFirst() + " " + s + " " + r);
        attempt(label + " s.removeLast", () -> s.removeLast() + " " + s + " " + r);
        attempt(label + " addAll", () -> r.addAll(List.of("p", "q", "c")) + " " + s + " " + r);
        attempt(label + " iterator", () -> { StringBuilder b = new StringBuilder(); for (String x : r) b.append(x).append(','); return b; });
        attempt(label + " iterator remove", () -> { Iterator<String> it = r.iterator(); it.next(); it.remove(); return s + " " + r; });
        attempt(label + " removeIf", () -> r.removeIf(x -> x.equals("p")) + " " + s + " " + r);
        attempt(label + " retainAll", () -> r.retainAll(List.of("b", "c", "d", "q", "x", "y", "z", "m")) + " " + s + " " + r);
        attempt(label + " removeAll", () -> r.removeAll(List.of("q")) + " " + s + " " + r);
        attempt(label + " stream", () -> r.stream().map(x -> x + x).collect(Collectors.toList()));
        attempt(label + " toArray", () -> Arrays.toString(r.toArray()) + Arrays.toString(r.toArray(new String[0])));
        attempt(label + " spliterator", () -> split(r.spliterator()));
        attempt(label + " s.spliterator", () -> split(s.spliterator()));
        attempt(label + " forEach", () -> { StringBuilder b = new StringBuilder(); r.forEach(x -> b.append(x).append(';')); return b; });
        attempt(label + " walk while adding", () -> { int n = 0; for (String x : r) { if (n++ < 2) s.add(x + "!"); } return s; });
        attempt(label + " walk while addFirst existing", () -> { int n = 0; for (String x : s) { if (n++ < 1) s.addFirst(s.getLast()); } return s; });
        attempt(label + " walk while addLast existing", () -> { int n = 0; for (String x : s) { if (n++ < 1) s.addLast(s.getFirst()); } return s; });
        attempt(label + " walk while addFirst first", () -> { int n = 0; for (String x : s) { if (n++ < 1) s.addFirst(s.getFirst()); } return s; });
        attempt(label + " clear", () -> { r.clear(); return s + " " + r + " " + r.size(); });
        attempt(label + " empty getFirst", () -> r.getFirst());
        attempt(label + " empty removeLast", () -> r.removeLast());
        attempt(label + " empty s.getLast", () -> s.getLast());
        attempt(label + " empty s.removeFirst", () -> s.removeFirst());
    }

    public static void main(String[] args) {
        set("LinkedHashSet", new LinkedHashSet<>(List.of("a", "b", "c", "d")));
        set("TreeSet", new TreeSet<>(List.of("a", "b", "c", "d")));
        set("unmodifiableSequencedSet", Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of("a", "b", "c", "d"))));
        set("newSequencedSetFromMap", Collections.newSequencedSetFromMap(new LinkedHashMap<>()));
        LinkedHashSet<String> l = new LinkedHashSet<>(List.of("a", "b", "c", "d"));
        System.out.println(l.reversed() instanceof LinkedHashSet ? "lhs" : "not lhs");
        System.out.println(LinkedHashSet.newLinkedHashSet(3) + " " + HashSet.newHashSet(3));
        attempt("newHashSet(-1)", () -> HashSet.newHashSet(-1));
        attempt("newLinkedHashSet(-1)", () -> LinkedHashSet.newLinkedHashSet(-1));
        // the order newHashSet(n) gives: a table for n
        for (int n : new int[] {0, 1, 12, 13, 24, 25, 100}) {
            Set<Integer> h = HashSet.newHashSet(n);
            for (int i = 0; i < 40; i++) {
                h.add(i * 17);
            }
            System.out.println("newHashSet(" + n + ") " + h);
        }
        // a sequenced set of the program's own: the defaults
        SequencedSet<String> mine = new SequencedSet<String>() {
            final List<String> held = new ArrayList<>(List.of("p", "q", "r"));
            public SequencedSet<String> reversed() { throw new UnsupportedOperationException("mine reversed"); }
            public int size() { return held.size(); }
            public boolean isEmpty() { return held.isEmpty(); }
            public boolean contains(Object o) { return held.contains(o); }
            public Iterator<String> iterator() { return held.iterator(); }
            public Object[] toArray() { return held.toArray(); }
            public <T> T[] toArray(T[] a) { return held.toArray(a); }
            public boolean add(String e) { return held.add(e); }
            public boolean remove(Object o) { return held.remove(o); }
            public boolean containsAll(Collection<?> c) { return held.containsAll(c); }
            public boolean addAll(Collection<? extends String> c) { return held.addAll(c); }
            public boolean removeAll(Collection<?> c) { return held.removeAll(c); }
            public boolean retainAll(Collection<?> c) { return held.retainAll(c); }
            public void clear() { held.clear(); }
            public String toString() { return held.toString(); }
        };
        attempt("mine getFirst", () -> mine.getFirst());
        attempt("mine getLast", () -> mine.getLast());
        attempt("mine removeFirst", () -> mine.removeFirst() + " " + mine);
        attempt("mine removeLast", () -> mine.removeLast() + " " + mine);
        attempt("mine addFirst", () -> { mine.addFirst("z"); return mine; });
        attempt("mine addLast", () -> { mine.addLast("z"); return mine; });
        SequencedCollection<String> coll = new SequencedCollection<String>() {
            final ArrayList<String> held = new ArrayList<>();
            public SequencedCollection<String> reversed() { return held.reversed(); }
            public int size() { return held.size(); }
            public boolean isEmpty() { return held.isEmpty(); }
            public boolean contains(Object o) { return held.contains(o); }
            public Iterator<String> iterator() { return held.iterator(); }
            public Object[] toArray() { return held.toArray(); }
            public <T> T[] toArray(T[] a) { return held.toArray(a); }
            public boolean add(String e) { return held.add(e); }
            public boolean remove(Object o) { return held.remove(o); }
            public boolean containsAll(Collection<?> c) { return held.containsAll(c); }
            public boolean addAll(Collection<? extends String> c) { return held.addAll(c); }
            public boolean removeAll(Collection<?> c) { return held.removeAll(c); }
            public boolean retainAll(Collection<?> c) { return held.retainAll(c); }
            public void clear() { held.clear(); }
            public String toString() { return held.toString(); }
        };
        attempt("coll empty getFirst", () -> coll.getFirst());
        attempt("coll empty getLast", () -> coll.getLast());
        attempt("coll empty removeFirst", () -> coll.removeFirst());
        attempt("coll empty removeLast", () -> coll.removeLast());
        coll.add("u");
        coll.add("v");
        coll.add("w");
        attempt("coll", () -> coll.getFirst() + " " + coll.getLast() + " " + coll.removeFirst() + " " + coll.removeLast() + " " + coll);
        // a sorted set that is not navigable
        SortedSet<Integer> plain = Collections.unmodifiableSortedSet(new TreeSet<>(List.of(5, 1, 4, 2, 3)));
        SortedSet<Integer> pr = plain.reversed();
        System.out.println((plain instanceof NavigableSet) + " " + pr + " " + pr.first() + " " + pr.last() + " " + pr.getFirst() + " " + pr.getLast() + " " + (pr.reversed() == plain) + " " + (pr.comparator() == null ? "null" : pr.comparator().compare(1, 2)));
        System.out.println(pr.headSet(3) + " " + pr.tailSet(3) + " " + pr.subSet(4, 2) + " " + pr.headSet(3).reversed() + " " + pr.contains(3) + " " + pr.size() + " " + pr.equals(plain) + " " + pr.stream().map(x -> x * 10).toList());
        attempt("pr subSet(2, 4)", () -> pr.subSet(2, 4));
        attempt("pr add", () -> pr.add(9));
        attempt("pr spliterator", () -> split(pr.spliterator()));
        SortedSet<Integer> sync = Collections.synchronizedSortedSet(new TreeSet<>(List.of(5, 1, 4, 2, 3)));
        SortedSet<Integer> sr = sync.reversed();
        System.out.println(sr + " " + sr.first() + " " + sr.headSet(3) + " " + sr.tailSet(3) + " " + sr.removeFirst() + " " + sync + " " + sr.add(7) + " " + sync + " " + sr.remove(1) + " " + sync);
        attempt("sr addFirst", () -> { sr.addFirst(0); return sync; });
        attempt("sr iterator remove", () -> { Iterator<Integer> it = sr.iterator(); it.next(); it.remove(); return sync + " " + sr; });
        attempt("sr headSet add", () -> sr.headSet(3).add(4) + " " + sync);
        attempt("sr headSet add outside", () -> sr.headSet(3).add(2) + " " + sync);
        attempt("sr tailSet", () -> sr.tailSet(3) + " " + sr.tailSet(9) + " " + sr.headSet(0));
        attempt("sr empty", () -> { SortedSet<Integer> e = Collections.unmodifiableSortedSet(new TreeSet<Integer>()).reversed(); return e + " " + e.size(); });
        attempt("sr empty first", () -> Collections.unmodifiableSortedSet(new TreeSet<Integer>()).reversed().first());
        attempt("sr empty getFirst", () -> Collections.unmodifiableSortedSet(new TreeSet<Integer>()).reversed().getFirst());
        attempt("sr cmp", () -> { SortedSet<String> cs = Collections.unmodifiableSortedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER)); return cs.reversed().comparator().compare("a", "B"); });
        attempt("descending null", () -> Collections.unmodifiableSortedSet(new TreeSet<Integer>(List.of(1, 2))).reversed().headSet(null));
        attempt("emptySortedSet", () -> (Collections.emptySortedSet() instanceof NavigableSet) + " " + Collections.emptySortedSet().reversed());
    }
}
