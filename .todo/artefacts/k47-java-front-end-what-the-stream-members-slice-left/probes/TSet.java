import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class TSet {
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

    static String split(String label, Spliterator<?> s, Comparator<?> expected) {
        StringBuilder out = new StringBuilder(label + " " + marks(s) + " " + s.estimateSize() + " " + s.getExactSizeIfKnown());
        try {
            Comparator<?> c = s.getComparator();
            out.append(" cmp=").append(c == null ? "null" : c == expected ? "same" : "other");
        } catch (IllegalStateException e) {
            out.append(" cmp!ISE");
        }
        s.tryAdvance(x -> out.append(" first=").append(x));
        out.append(" then ").append(s.estimateSize());
        return out.toString();
    }

    public static void main(String[] args) {
        TreeSet<Integer> s = new TreeSet<>();
        for (int i : new int[] {50, 20, 80, 10, 30, 70, 90, 20, 60}) {
            System.out.print(s.add(i) ? "+" : "=");
        }
        System.out.println(" " + s + " " + s.size() + " " + s.first() + " " + s.last() + " " + s.comparator());
        System.out.println(s.lower(20) + " " + s.floor(25) + " " + s.ceiling(25) + " " + s.higher(90) + " " + s.lower(10) + " " + s.contains(70) + " " + s.contains(75));
        System.out.println(s.headSet(30) + " " + s.headSet(30, true) + " " + s.tailSet(70) + " " + s.tailSet(70, false) + " " + s.subSet(20, 70) + " " + s.subSet(20, false, 70, true));
        System.out.println(s.descendingSet() + " " + s.descendingSet().first() + " " + s.descendingSet().headSet(50) + " " + s.descendingSet().ceiling(55) + " " + s.descendingSet().lower(55));
        System.out.println(s.pollFirst() + " " + s.pollLast() + " " + s + " " + s.remove(50) + " " + s.remove(50) + " " + s);
        System.out.println(s.getFirst() + " " + s.getLast() + " " + s.removeFirst() + " " + s.removeLast() + " " + s + " " + s.reversed());
        attempt("addFirst", () -> {
            s.addFirst(1);
            return s;
        });
        attempt("addLast", () -> {
            s.addLast(1);
            return s;
        });
        NavigableSet<Integer> head = s.headSet(60, false);
        head.add(25);
        attempt("head add 60", () -> head.add(60));
        attempt("head add 99", () -> head.add(99));
        System.out.println(s + " " + head + " " + head.size() + " " + head.pollLast() + " " + s);
        head.clear();
        System.out.println(s + " " + head + " " + head.isEmpty());
        TreeSet<Integer> e = new TreeSet<>();
        attempt("first", () -> e.first());
        attempt("last", () -> e.last());
        attempt("getFirst", () -> e.getFirst());
        attempt("removeLast", () -> e.removeLast());
        attempt("pollFirst", () -> e.pollFirst());
        attempt("add null", () -> e.add(null));
        attempt("contains null", () -> e.contains(null));
        attempt("remove null", () -> e.remove(null));
        attempt("contains null nonempty", () -> s.contains(null));
        attempt("subSet from > to", () -> s.subSet(9, 1));
        TreeSet<String> ci = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Collections.addAll(ci, "b", "A", "a", "C", "B");
        System.out.println(ci + " " + ci.contains("c") + " " + ci.equals(Set.of("A", "b", "C")) + " " + Set.of("a", "B", "c").equals(ci) + " " + ci.hashCode());
        TreeSet<String> copy = new TreeSet<>(ci);
        Collection<String> asColl = ci;
        TreeSet<String> copy2 = new TreeSet<>(asColl);
        System.out.println(copy + " " + (copy.comparator() == String.CASE_INSENSITIVE_ORDER) + " " + copy2 + " " + copy2.comparator());
        TreeSet<String> fromList = new TreeSet<>(List.of("q", "w", "e", "r", "t", "y"));
        System.out.println(fromList + " " + fromList.removeAll(List.of("w", "e", "x")) + " " + fromList.retainAll(Set.of("q", "r", "y", "z")) + " " + fromList + " " + fromList.removeIf(x -> x.equals("r")) + " " + fromList);
        Iterator<String> it = fromList.iterator();
        it.next();
        it.remove();
        System.out.println(fromList + " " + fromList.descendingIterator().next());
        System.out.println(Arrays.toString(s.toArray()) + " " + Arrays.toString(s.toArray(new Integer[0])) + " " + s.stream().map(x -> x * 2).toList() + " " + s.containsAll(List.of(20, 30)));
        TreeSet<Integer> rv = new TreeSet<>(Comparator.reverseOrder());
        rv.addAll(List.of(3, 1, 4, 1, 5, 9, 2, 6));
        System.out.println(rv + " " + rv.headSet(4) + " " + rv.tailSet(4) + " " + rv.first() + " " + rv.ceiling(7) + " " + rv.descendingSet());

        // spliterators
        TreeMap<String, Integer> tm = new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3));
        TreeMap<String, Integer> tc = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        tc.putAll(tm);
        System.out.println(split("treeset", s.spliterator(), null));
        System.out.println(split("treeset cmp", ci.spliterator(), String.CASE_INSENSITIVE_ORDER));
        System.out.println(split("treeset rev", rv.spliterator(), null));
        System.out.println(split("treeset head", s.headSet(80).spliterator(), null));
        System.out.println(split("treeset desc", s.descendingSet().spliterator(), null));
        System.out.println(split("treeset cmp head", ci.headSet("c").spliterator(), String.CASE_INSENSITIVE_ORDER));
        System.out.println(split("keySet", tm.keySet().spliterator(), null));
        System.out.println(split("navigableKeySet", tm.navigableKeySet().spliterator(), null));
        System.out.println(split("descendingKeySet", tm.descendingKeySet().spliterator(), null));
        System.out.println(split("values", tm.values().spliterator(), null));
        System.out.println(split("entrySet", tm.entrySet().spliterator(), null));
        System.out.println(split("cmp keySet", tc.keySet().spliterator(), String.CASE_INSENSITIVE_ORDER));
        System.out.println(split("cmp entrySet", tc.entrySet().spliterator(), String.CASE_INSENSITIVE_ORDER));
        System.out.println(split("head keySet", tm.headMap("c").keySet().spliterator(), null));
        System.out.println(split("head values", tm.headMap("c").values().spliterator(), null));
        System.out.println(split("head entrySet", tm.headMap("c").entrySet().spliterator(), null));
        System.out.println(split("desc keySet", tm.descendingMap().keySet().spliterator(), null));
        System.out.println(split("desc entrySet", tm.descendingMap().entrySet().spliterator(), null));
        System.out.println(split("desc values", tm.descendingMap().values().spliterator(), null));
        System.out.println(split("empty treeset", new TreeSet<Integer>().spliterator(), null));
        Spliterator<Map.Entry<String, Integer>> ces = tc.entrySet().spliterator();
        Comparator<? super Map.Entry<String, Integer>> entryOrder = ces.getComparator();
        System.out.println(entryOrder.compare(Map.entry("A", 9), Map.entry("b", 0)) + " " + entryOrder.compare(Map.entry("b", 9), Map.entry("A", 0)));
        System.out.println(s.stream().count() + " " + tm.entrySet().stream().count());

        // unmodifiable and synchronized views
        SortedMap<String, Integer> um = Collections.unmodifiableSortedMap(tm);
        NavigableMap<String, Integer> unm = Collections.unmodifiableNavigableMap(tm);
        SortedSet<Integer> uss = Collections.unmodifiableSortedSet(s);
        NavigableSet<Integer> uns = Collections.unmodifiableNavigableSet(s);
        System.out.println(um + " " + um.firstKey() + " " + um.headMap("b") + " " + unm.descendingMap() + " " + unm.floorEntry("bb") + " " + uss + " " + uns.descendingSet() + " " + uns.ceiling(25));
        attempt("um.put", () -> um.put("d", 4));
        attempt("um.headMap.put", () -> um.headMap("b").put("0", 4));
        attempt("unm.pollFirstEntry", () -> unm.pollFirstEntry());
        attempt("unm.descendingMap.remove", () -> unm.descendingMap().remove("a"));
        attempt("unm.entry.setValue", () -> unm.entrySet().iterator().next().setValue(5));
        attempt("unm.firstEntry.setValue", () -> unm.firstEntry().setValue(5));
        attempt("uss.add", () -> uss.add(1));
        attempt("uns.pollFirst", () -> uns.pollFirst());
        attempt("uns.headSet.remove", () -> uns.headSet(70).remove(20));
        attempt("uns.iterator.remove", () -> {
            Iterator<Integer> i = uns.iterator();
            i.next();
            i.remove();
            return null;
        });
        System.out.println(split("unm keySet", unm.keySet().spliterator(), null));
        System.out.println(split("uns", uns.spliterator(), null));
        System.out.println(split("uss", uss.spliterator(), null));
        System.out.println(um.getClass().getName() + " " + unm.getClass().getName() + " " + uss.getClass().getName() + " " + uns.getClass().getName() + " " + um.equals(tm) + " " + uss.hashCode());
        SortedMap<String, Integer> sm = Collections.synchronizedSortedMap(new TreeMap<>(tm));
        NavigableMap<String, Integer> snm = Collections.synchronizedNavigableMap(new TreeMap<>(tm));
        SortedSet<Integer> sss = Collections.synchronizedSortedSet(new TreeSet<>(s));
        NavigableSet<Integer> sns = Collections.synchronizedNavigableSet(new TreeSet<>(s));
        sm.put("d", 4);
        snm.pollFirstEntry();
        sss.add(5);
        sns.pollLast();
        System.out.println(sm + " " + sm.headMap("c") + " " + snm + " " + snm.descendingMap() + " " + sss + " " + sss.headSet(30) + " " + sns + " " + sns.descendingSet());
        System.out.println(Collections.emptySortedMap() + " " + Collections.emptyNavigableMap() + " " + Collections.emptySortedSet() + " " + Collections.emptyNavigableSet() + " " + Collections.<Integer>emptyNavigableSet().headSet(3) + " " + Collections.emptyNavigableMap().descendingMap());
        attempt("emptySortedSet.first", () -> Collections.emptySortedSet().first());
        attempt("emptySortedSet.add", () -> Collections.<Integer>emptySortedSet().add(1));
        attempt("emptyNavigableMap.put", () -> Collections.<Integer, Integer>emptyNavigableMap().put(1, 1));
        System.out.println(split("emptyNavigableSet", Collections.emptyNavigableSet().spliterator(), null));
        System.out.println(split("emptySortedSet", Collections.emptySortedSet().spliterator(), null));
        System.out.println(split("emptyNavigableMap keys", Collections.emptyNavigableMap().keySet().spliterator(), null));

        // the skip list
        ConcurrentSkipListMap<String, Integer> sl = new ConcurrentSkipListMap<>();
        for (String w : "pear fig apple kiwi date".split(" ")) {
            sl.put(w, w.length());
        }
        System.out.println(sl + " " + sl.firstKey() + " " + sl.headMap("fig") + " " + sl.descendingMap() + " " + sl.ceilingEntry("e") + " " + sl.pollFirstEntry() + " " + sl.size());
        attempt("sl put null key", () -> sl.put(null, 1));
        attempt("sl put null value", () -> sl.put("x", null));
        attempt("sl get null", () -> sl.get(null));
        attempt("sl containsValue null", () -> sl.containsValue(null));
        attempt("sl putIfAbsent", () -> sl.putIfAbsent("fig", 9));
        attempt("sl remove(k, v)", () -> sl.remove("fig", 3));
        attempt("sl replace(k, o, n)", () -> sl.replace("kiwi", 4, 44));
        attempt("sl firstKey empty", () -> new ConcurrentSkipListMap<String, Integer>().firstKey());
        StringBuilder seen = new StringBuilder();
        for (String k : sl.keySet()) {
            seen.append(k).append(' ');
            if (k.startsWith("d")) {
                sl.remove("kiwi");
                sl.put("grape", 5);
                sl.put("cherry", 6);
            }
        }
        System.out.println(seen);
        System.out.println(sl);
        Iterator<Map.Entry<String, Integer>> sli = sl.entrySet().iterator();
        Map.Entry<String, Integer> sle = sli.next();
        attempt("sl entry setValue", () -> sle.setValue(1));
        attempt("sl firstEntry setValue", () -> sl.firstEntry().setValue(1));
        sli.remove();
        System.out.println(sl + " " + sl.getClass().getName() + " " + (sl instanceof ConcurrentNavigableMap) + " " + sl.keySet().getClass().getName());
        System.out.println(split("sl keySet", sl.keySet().spliterator(), null));
        System.out.println(split("sl values", sl.values().spliterator(), null));
        System.out.println(split("sl entrySet", sl.entrySet().spliterator(), null));
        System.out.println(split("sl descending keySet", sl.descendingMap().keySet().spliterator(), null));
        System.out.println(split("sl head keySet", sl.headMap("zz").keySet().spliterator(), null));
        ConcurrentSkipListSet<Integer> ss = new ConcurrentSkipListSet<>(List.of(5, 3, 8, 1));
        System.out.println(ss + " " + ss.first() + " " + ss.headSet(5) + " " + ss.descendingSet() + " " + ss.pollFirst() + " " + ss + " " + ss.add(3) + " " + ss.add(4));
        attempt("ss add null", () -> ss.add(null));
        System.out.println(split("ss", ss.spliterator(), null));
        ConcurrentSkipListSet<String> ssc = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
        ssc.addAll(List.of("b", "A", "a"));
        System.out.println(ssc + " " + split("ssc", ssc.spliterator(), String.CASE_INSENSITIVE_ORDER));
        System.out.println(sl.keySet().stream().count() + " " + ss.stream().count());
    }
}
