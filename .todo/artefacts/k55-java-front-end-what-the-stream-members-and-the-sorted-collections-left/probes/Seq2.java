import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Seq2 {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SortedSet<Integer> plain = Collections.unmodifiableSortedSet(new TreeSet<>(List.of(5, 1, 4, 2, 3)));
        SortedSet<Integer> pr = plain.reversed();
        attempt("headSet(3).headSet(1)", () -> pr.headSet(3).headSet(1));
        attempt("headSet(3).headSet(5)", () -> pr.headSet(3).headSet(5));
        attempt("headSet(3).tailSet(9)", () -> pr.headSet(3).tailSet(9));
        attempt("headSet(3).tailSet(1)", () -> pr.headSet(3).tailSet(1));
        attempt("tailSet(3).headSet(4)", () -> pr.tailSet(3).headSet(4));
        attempt("tailSet(3).subSet(4, 0)", () -> pr.tailSet(3).subSet(4, 0));
        attempt("subSet(4, 2).first", () -> pr.subSet(4, 2).first() + " " + pr.subSet(4, 2).last() + " " + pr.subSet(4, 2).size() + " " + pr.subSet(4, 2).contains(2) + " " + pr.subSet(4, 2).contains(4));
        attempt("subSet(4, 2).reversed", () -> pr.subSet(4, 2).reversed() + " " + pr.subSet(4, 2).reversed().reversed() + " " + pr.subSet(4, 2).reversed().first());
        attempt("subSet(4, 2).comparator", () -> pr.subSet(4, 2).comparator().compare(1, 2) + " " + pr.subSet(4, 2).reversed().comparator());
        attempt("headSet(0)", () -> pr.headSet(0) + " " + pr.headSet(0).size());
        attempt("subSet(3, 3)", () -> pr.subSet(3, 3) + " " + pr.subSet(3, 3).isEmpty());
        attempt("subSet(3, 3).first", () -> pr.subSet(3, 3).first());
        attempt("set iterator after change", () -> { SortedSet<Integer> t = new TreeSet<>(List.of(1, 2, 3)); SortedSet<Integer> u = Collections.synchronizedSortedSet(t); Iterator<Integer> it = u.reversed().iterator(); String a = "" + it.next(); t.add(0); t.add(9); StringBuilder b = new StringBuilder(a); while (it.hasNext()) b.append(it.next()); return b; });
        attempt("set iterator remove twice", () -> { SortedSet<Integer> t = Collections.synchronizedSortedSet(new TreeSet<>(List.of(1, 2, 3))); Iterator<Integer> it = t.reversed().iterator(); it.next(); it.remove(); it.remove(); return t; });
        attempt("set toString hash equals", () -> pr.toString() + " " + pr.hashCode() + " " + pr.equals(Set.of(1, 2, 3, 4, 5)) + " " + pr.headSet(3).equals(Set.of(4, 5)));
        attempt("set stream count", () -> pr.stream().count() + " " + pr.headSet(3).stream().count());
        SortedMap<String, Integer> pm = Collections.unmodifiableSortedMap(new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3, "d", 4)));
        SortedMap<String, Integer> mr = pm.reversed();
        attempt("headMap(b).headMap(a)", () -> mr.headMap("b").headMap("a"));
        attempt("headMap(b).headMap(d)", () -> mr.headMap("b").headMap("d"));
        attempt("headMap(b).tailMap(z)", () -> mr.headMap("b").tailMap("z"));
        attempt("tailMap(c).headMap(d)", () -> mr.tailMap("c").headMap("d"));
        attempt("subMap(c, c)", () -> mr.subMap("c", "c"));
        attempt("subMap(d, b).subMap(c, a)", () -> mr.subMap("d", "b").subMap("c", "a"));
        attempt("subMap(d, b).firstKey", () -> mr.subMap("d", "b").firstKey() + " " + mr.subMap("d", "b").lastKey() + " " + mr.subMap("d", "b").size() + " " + mr.subMap("d", "b").get("b") + " " + mr.subMap("d", "b").get("c") + " " + mr.subMap("d", "b").containsKey("a"));
        attempt("subMap(d, b).reversed", () -> mr.subMap("d", "b").reversed() + " " + mr.subMap("d", "b").reversed().firstKey());
        attempt("map views", () -> mr.keySet() + " " + mr.values() + " " + mr.entrySet() + " " + mr.headMap("b").keySet() + " " + mr.sequencedKeySet() + " " + mr.sequencedKeySet().reversed());
        attempt("map firstEntry", () -> mr.firstEntry() + " " + mr.lastEntry() + " " + pm.firstEntry() + " " + pm.lastEntry());
        attempt("pm firstEntry setValue", () -> pm.firstEntry().setValue(0));
        attempt("pm lastEntry setValue", () -> pm.lastEntry().setValue(0));
        attempt("sync firstEntry setValue", () -> Collections.synchronizedSortedMap(new TreeMap<>(Map.of("a", 1))).firstEntry().setValue(0));
        attempt("sync lastEntry setValue", () -> Collections.synchronizedSortedMap(new TreeMap<>(Map.of("a", 1))).lastEntry().setValue(0));
        attempt("tree firstEntry setValue", () -> new TreeMap<>(Map.of("a", 1)).firstEntry().setValue(0));
        attempt("unmodNav firstEntry setValue", () -> Collections.unmodifiableNavigableMap(new TreeMap<>(Map.of("a", 1))).firstEntry().setValue(0));
        attempt("map toString hash equals", () -> mr.toString() + " " + mr.hashCode() + " " + mr.equals(pm) + " " + mr.headMap("b").equals(Map.of("c", 3, "d", 4)));
        attempt("map remove", () -> { SortedMap<String, Integer> t = Collections.synchronizedSortedMap(new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3))); SortedMap<String, Integer> v = t.reversed().headMap("a"); return v.remove("a") + " " + v.remove("b") + " " + t + " " + v.containsKey("a") + " " + v.get("a"); });
        attempt("map pollFirstEntry", () -> { SortedMap<String, Integer> t = Collections.synchronizedSortedMap(new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3))); return t.reversed().pollFirstEntry() + " " + t.reversed().pollLastEntry() + " " + t + " " + t.pollFirstEntry() + " " + t; });
        // the default sequencedKeySet: how often keySet() is asked
        int[] asked = new int[3];
        SequencedMap<String, Integer> counting = new SequencedMap<String, Integer>() {
            final LinkedHashMap<String, Integer> held = new LinkedHashMap<>(Map.of("p", 1));
            public SequencedMap<String, Integer> reversed() { return held.reversed(); }
            public int size() { return held.size(); }
            public boolean isEmpty() { return held.isEmpty(); }
            public boolean containsKey(Object k) { return held.containsKey(k); }
            public boolean containsValue(Object v) { return held.containsValue(v); }
            public Integer get(Object k) { return held.get(k); }
            public Integer put(String k, Integer v) { return held.put(k, v); }
            public Integer remove(Object k) { return held.remove(k); }
            public void putAll(Map<? extends String, ? extends Integer> m) { held.putAll(m); }
            public void clear() { held.clear(); }
            public Set<String> keySet() { asked[0]++; return held.keySet(); }
            public Collection<Integer> values() { asked[1]++; return held.values(); }
            public Set<Map.Entry<String, Integer>> entrySet() { asked[2]++; return held.entrySet(); }
        };
        SequencedSet<String> ks = counting.sequencedKeySet();
        SequencedCollection<Integer> vs = counting.sequencedValues();
        SequencedSet<Map.Entry<String, Integer>> es = counting.sequencedEntrySet();
        System.out.println(Arrays.toString(asked));
        ks.size(); ks.size(); ks.contains("p"); vs.size(); vs.size(); es.size(); es.isEmpty();
        System.out.println(Arrays.toString(asked));
        System.out.println(ks + " " + vs + " " + es + " " + ks.getFirst() + " " + ks.getLast() + " " + Arrays.toString(asked));
        counting.firstEntry(); counting.lastEntry();
        System.out.println(Arrays.toString(asked));
        attempt("default views spliterator", () -> ks.spliterator().characteristics() + " " + vs.spliterator().characteristics() + " " + es.spliterator().characteristics() + " " + Arrays.toString(asked));
        attempt("default views stream", () -> ks.stream().count() + " " + Arrays.toString(asked));
        attempt("default views toString", () -> ks.toString() + " " + ks.hashCode() + " " + ks.equals(Set.of("p")) + " " + Arrays.toString(asked));
        attempt("default views removeFirst", () -> ks.removeFirst() + " " + counting.size() + " " + Arrays.toString(asked));
        // newSetFromMap
        attempt("newSetFromMap", () -> { Set<String> s = Collections.newSetFromMap(new LinkedHashMap<>()); s.add("b"); s.add("a"); s.add("b"); return s + " " + s.size() + " " + s.contains("a") + " " + s.remove("a") + " " + s; });
        attempt("newSetFromMap non-empty", () -> Collections.newSetFromMap(new HashMap<>(Map.of("a", true))));
        attempt("newSequencedSetFromMap non-empty", () -> Collections.newSequencedSetFromMap(new LinkedHashMap<>(Map.of("a", true))));
        attempt("newSetFromMap spliterator", () -> Collections.newSetFromMap(new LinkedHashMap<>()).spliterator().characteristics() + " " + Collections.newSetFromMap(new HashMap<>()).spliterator().characteristics() + " " + Collections.newSetFromMap(new TreeMap<>()).spliterator().characteristics());
        attempt("newSequencedSetFromMap TreeMap", () -> { SequencedSet<String> s = Collections.newSequencedSetFromMap(new TreeMap<>()); s.add("b"); s.add("a"); return s + " " + s.reversed() + " " + s.getFirst() + " " + s.removeLast() + " " + s; });
        attempt("newSequencedSetFromMap TreeMap addFirst", () -> { SequencedSet<String> s = Collections.newSequencedSetFromMap(new TreeMap<>()); s.addFirst("z"); return s; });
        // access order
        LinkedHashMap<String, Integer> acc = new LinkedHashMap<>(16, 0.75f, true);
        acc.put("a", 1);
        acc.put("b", 2);
        acc.put("c", 3);
        attempt("acc walk putFirst first", () -> { for (String k : acc.keySet()) { acc.putFirst("a", 0); } return acc; });
        attempt("acc reversed get", () -> acc.reversed().get("a") + " " + acc);
        attempt("acc reversed getOrDefault", () -> acc.reversed().getOrDefault("b", 0) + " " + acc);
        attempt("acc sequencedKeySet getFirst", () -> acc.sequencedKeySet().getFirst() + " " + acc);
        attempt("acc reversed replaceAll", () -> { StringBuilder seen = new StringBuilder(); acc.reversed().replaceAll((k, v) -> { seen.append(k); return v + 1; }); return seen + " " + acc; });
        attempt("lhm reversed replaceAll", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); m.put("y", 2); StringBuilder seen = new StringBuilder(); m.reversed().replaceAll((k, v) -> { seen.append(k); return v + 1; }); m.reversed().forEach((k, v) -> seen.append(k)); return seen + " " + m; });
        attempt("lhm reversed computeIfPresent", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); m.put("y", 2); return m.reversed().computeIfPresent("x", (k, v) -> v * 10) + " " + m.reversed().replace("y", 7) + " " + m.reversed().replace("y", 7, 8) + " " + m.reversed().remove("x", 10) + " " + m; });
        attempt("lhm reversed keySet remove twice", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); m.put("y", 2); Iterator<String> it = m.reversed().keySet().iterator(); it.next(); it.remove(); it.remove(); return m; });
        attempt("lhm reversed iterator after change", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); m.put("y", 2); Iterator<String> it = m.reversed().keySet().iterator(); it.next(); m.put("z", 3); return it.next(); });
        attempt("lhm reversed instanceof", () -> (new LinkedHashMap<String, Integer>().reversed() instanceof LinkedHashMap) + " " + (new LinkedHashMap<String, Integer>().reversed() instanceof AbstractMap));
        attempt("lhm reversed entry equality", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); return m.reversed().entrySet().contains(Map.entry("x", 1)) + " " + m.reversed().entrySet().remove(Map.entry("x", 2)) + " " + m.reversed().entrySet().remove(Map.entry("x", 1)) + " " + m; });
        attempt("lhm reversed values remove", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); m.put("y", 1); m.reversed().values().remove(1); return m; });
        attempt("lhm values remove", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); m.put("y", 1); m.values().remove(1); return m; });
        attempt("lhs reversed remove", () -> { LinkedHashSet<String> s = new LinkedHashSet<>(List.of("a", "b")); return s.reversed().remove("a") + " " + s.reversed().contains("b") + " " + s; });
        attempt("lhs reversed iterator after change", () -> { LinkedHashSet<String> s = new LinkedHashSet<>(List.of("a", "b")); Iterator<String> it = s.reversed().iterator(); it.next(); s.add("c"); return it.next(); });
        attempt("lhs reversed removeIf", () -> { LinkedHashSet<String> s = new LinkedHashSet<>(List.of("a", "b", "c")); StringBuilder seen = new StringBuilder(); s.reversed().removeIf(x -> { seen.append(x); return x.equals("b"); }); return seen + " " + s; });
        attempt("lhs reversed toString", () -> { LinkedHashSet<Object> s = new LinkedHashSet<>(); s.add(1); s.add(s.reversed()); return "" + s.size(); });
        // newHashMap: the table it makes
        for (int n : new int[] {6, 7, 12, 13, 24, 25, 48, 49, 96, 97}) {
            Map<Integer, Integer> h = HashMap.newHashMap(n);
            for (int i = 0; i < n; i++) {
                h.put(i * 17, i);
            }
            Set<Integer> s = HashSet.newHashSet(n);
            for (int i = 0; i < n; i++) {
                s.add(i * 17);
            }
            System.out.println("newHashMap(" + n + ") " + h.keySet() + " " + s.equals(h.keySet()) + " " + s.toString().equals(h.keySet().toString()));
        }
        // a list of List.of
        attempt("List.of remove absent", () -> List.of(1, 2).reversed().remove(Integer.valueOf(3)));
        attempt("List.of removeIf none", () -> List.of(1, 2).reversed().removeIf(x -> false));
        attempt("List.of addAll empty", () -> List.of(1, 2).reversed().addAll(List.of()));
        attempt("List.of retainAll all", () -> List.of(1, 2).reversed().retainAll(List.of(1, 2)));
        attempt("List.of clear", () -> { List.of().reversed().clear(); return "ok"; });
        attempt("singletonList remove absent", () -> Collections.singletonList(1).remove(Integer.valueOf(3)));
        attempt("emptyList clear", () -> { Collections.emptyList().reversed().clear(); return "ok"; });
        attempt("RandomAccess", () -> (new ArrayList<>().reversed() instanceof RandomAccess) + " " + (new LinkedList<>().reversed() instanceof RandomAccess) + " " + (List.of().reversed() instanceof RandomAccess) + " " + (Arrays.asList(1).reversed() instanceof RandomAccess));
        attempt("unmodifiableSequencedCollection", () -> { SequencedCollection<Integer> c = Collections.unmodifiableSequencedCollection(new ArrayList<>(List.of(1, 2, 3))); return c + " " + c.reversed() + " " + c.getFirst() + " " + c.getLast() + " " + c.reversed().getFirst() + " " + (c instanceof List) + " " + c.equals(List.of(1, 2, 3)); });
        attempt("unmodifiableSequencedCollection removeFirst", () -> Collections.unmodifiableSequencedCollection(new ArrayList<>(List.of(1, 2, 3))).removeFirst());
        attempt("unmodifiableSequencedCollection spliterator", () -> Collections.unmodifiableSequencedCollection(new ArrayDeque<>(List.of(1, 2, 3))).spliterator().characteristics() + " " + Collections.unmodifiableSequencedCollection(new ArrayDeque<>(List.of(1, 2, 3))).reversed().spliterator().characteristics());
        attempt("unmodifiableSequencedMap entries", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); SequencedMap<String, Integer> u = Collections.unmodifiableSequencedMap(m); return u.firstEntry() + " " + u.lastEntry() + " " + u.sequencedKeySet() + " " + u.sequencedEntrySet().getFirst().getClass().getSimpleName().length(); });
        attempt("unmodifiableSequencedMap entry setValue", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); return Collections.unmodifiableSequencedMap(m).sequencedEntrySet().getFirst().setValue(3); });
        attempt("unmodifiableSequencedMap entry2 setValue", () -> { LinkedHashMap<String, Integer> m = new LinkedHashMap<>(); m.put("x", 1); return Collections.unmodifiableSequencedMap(m).entrySet().iterator().next().setValue(3); });
        attempt("unmodifiableSequencedSet sorted", () -> { SequencedSet<Integer> u = Collections.unmodifiableSequencedSet(new TreeSet<>(List.of(3, 1, 2))); return u + " " + u.reversed() + " " + u.getFirst() + " " + u.reversed().getFirst(); });
        attempt("deque empty", () -> new ArrayDeque<Integer>().reversed() + " " + new ArrayDeque<Integer>().reversed().contains(null) + " " + new LinkedList<Integer>().reversed().contains(null));
    }
}
