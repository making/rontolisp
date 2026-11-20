import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class SeqMap {
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

    static void map(String label, SequencedMap<String, Integer> m) {
        SequencedMap<String, Integer> r = m.reversed();
        System.out.println(label + " " + m + " " + r + " " + (r.reversed() == m) + " " + r.size() + " " + r.isEmpty() + " " + r.equals(m) + " " + (r.hashCode() == m.hashCode()) + " " + r.get("c") + " " + r.containsKey("c") + " " + r.containsValue(3));
        attempt(label + " entries", () -> r.firstEntry() + " " + r.lastEntry() + " " + m.firstEntry() + " " + m.lastEntry());
        attempt(label + " firstEntry setValue", () -> m.firstEntry().setValue(9));
        attempt(label + " firstEntry class", () -> (m.firstEntry() instanceof AbstractMap.SimpleImmutableEntry) + " " + m.firstEntry().equals(Map.entry("a", 1)) + " " + m.firstEntry().hashCode());
        attempt(label + " views", () -> r.keySet() + " " + r.values() + " " + r.entrySet() + " " + m.sequencedKeySet() + " " + m.sequencedValues() + " " + m.sequencedEntrySet() + " " + r.sequencedKeySet() + " " + r.sequencedValues() + " " + r.sequencedEntrySet());
        attempt(label + " view reversed", () -> m.sequencedKeySet().reversed() + " " + m.sequencedValues().reversed() + " " + m.sequencedEntrySet().reversed() + " " + r.sequencedKeySet().reversed());
        attempt(label + " view firsts", () -> m.sequencedKeySet().getFirst() + " " + m.sequencedKeySet().getLast() + " " + m.sequencedValues().getFirst() + " " + m.sequencedValues().getLast() + " " + m.sequencedEntrySet().getFirst() + " " + m.sequencedEntrySet().getLast() + " " + r.sequencedKeySet().getFirst() + " " + r.sequencedValues().getLast());
        attempt(label + " put(x)", () -> r.put("x", 24) + " " + m + " " + r);
        attempt(label + " put(a)", () -> r.put("a", 10) + " " + m + " " + r);
        attempt(label + " putFirst(y)", () -> r.putFirst("y", 25) + " " + m + " " + r);
        attempt(label + " putLast(z)", () -> r.putLast("z", 26) + " " + m + " " + r);
        attempt(label + " putFirst(b)", () -> r.putFirst("b", 20) + " " + m + " " + r);
        attempt(label + " putLast(b)", () -> r.putLast("b", 21) + " " + m + " " + r);
        attempt(label + " m.putFirst(c)", () -> m.putFirst("c", 30) + " " + m + " " + r);
        attempt(label + " m.putLast(c)", () -> m.putLast("c", 31) + " " + m + " " + r);
        attempt(label + " m.putFirst(new)", () -> m.putFirst("n", 40) + " " + m + " " + r);
        attempt(label + " m.putLast(new)", () -> m.putLast("o", 41) + " " + m + " " + r);
        attempt(label + " remove(a)", () -> r.remove("a") + " " + m + " " + r);
        attempt(label + " pollFirstEntry", () -> r.pollFirstEntry() + " " + m + " " + r);
        attempt(label + " pollLastEntry", () -> r.pollLastEntry() + " " + m + " " + r);
        attempt(label + " m.pollFirstEntry", () -> m.pollFirstEntry() + " " + m + " " + r);
        attempt(label + " m.pollLastEntry", () -> m.pollLastEntry() + " " + m + " " + r);
        attempt(label + " putAll", () -> { r.putAll(new TreeMap<>(Map.of("p", 50, "q", 51))); return m + " " + r; });
        attempt(label + " putIfAbsent", () -> r.putIfAbsent("s", 52) + " " + r.putIfAbsent("p", 0) + " " + m + " " + r);
        attempt(label + " computeIfAbsent", () -> r.computeIfAbsent("t", k -> 53) + " " + m + " " + r);
        attempt(label + " merge", () -> r.merge("t", 1, Integer::sum) + " " + r.merge("u", 1, Integer::sum) + " " + m + " " + r);
        attempt(label + " compute", () -> r.compute("u", (k, v) -> null) + " " + m + " " + r);
        attempt(label + " replaceAll", () -> { r.replaceAll((k, v) -> v + 1); return m + " " + r; });
        attempt(label + " forEach", () -> { StringBuilder b = new StringBuilder(); r.forEach((k, v) -> b.append(k).append(v).append(';')); return b; });
        attempt(label + " entry setValue", () -> { Map.Entry<String, Integer> e = r.entrySet().iterator().next(); return e.setValue(99) + " " + m; });
        attempt(label + " keySet remove", () -> r.keySet().remove("p") + " " + m);
        attempt(label + " values remove", () -> r.values().remove(99) + " " + m);
        attempt(label + " iterator remove", () -> { Iterator<String> it = r.keySet().iterator(); it.next(); it.remove(); return m + " " + r; });
        attempt(label + " keySet add", () -> r.keySet().add("zz"));
        attempt(label + " sequencedKeySet add", () -> m.sequencedKeySet().add("zz"));
        attempt(label + " sequencedKeySet addFirst", () -> { m.sequencedKeySet().addFirst("zz"); return m; });
        attempt(label + " sequencedKeySet removeFirst", () -> m.sequencedKeySet().removeFirst() + " " + m);
        attempt(label + " sequencedValues removeLast", () -> m.sequencedValues().removeLast() + " " + m);
        attempt(label + " sequencedEntrySet removeFirst", () -> m.sequencedEntrySet().removeFirst() + " " + m);
        attempt(label + " spliterators", () -> split(r.keySet().spliterator()) + " / " + split(r.values().spliterator()) + " / " + split(r.entrySet().spliterator()) + " / " + split(m.sequencedKeySet().spliterator()) + " / " + split(m.sequencedKeySet().reversed().spliterator()));
        attempt(label + " walk while putFirst", () -> { int n = 0; for (String k : m.keySet()) { if (n++ < 1) m.putFirst(k, 0); } return m; });
        attempt(label + " walk while putLast existing", () -> { int n = 0; for (String k : m.keySet()) { if (n++ < 1) m.putLast(k, 0); } return m; });
        attempt(label + " walk while putFirst existing", () -> { int n = 0; for (String k : m.keySet()) { if (n++ < 1) m.putFirst(m.lastEntry().getKey(), 0); } return m; });
        attempt(label + " clear", () -> { r.clear(); return m + " " + r + " " + r.size(); });
        attempt(label + " empty", () -> r.firstEntry() + " " + r.lastEntry() + " " + r.pollFirstEntry() + " " + m.pollLastEntry());
        attempt(label + " empty key getFirst", () -> m.sequencedKeySet().getFirst());
        attempt(label + " empty key removeLast", () -> m.sequencedKeySet().reversed().removeLast());
    }

    public static void main(String[] args) {
        LinkedHashMap<String, Integer> l = new LinkedHashMap<>();
        for (String k : "a b c d".split(" ")) l.put(k, k.charAt(0) - 'a' + 1);
        map("LinkedHashMap", l);
        map("TreeMap", new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3, "d", 4)));
        LinkedHashMap<String, Integer> u = new LinkedHashMap<>();
        for (String k : "a b c d".split(" ")) u.put(k, k.charAt(0) - 'a' + 1);
        map("unmodifiableSequencedMap", Collections.unmodifiableSequencedMap(u));
        // access order and the eldest
        LinkedHashMap<String, Integer> lru = new LinkedHashMap<>(16, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                System.out.print("[eldest " + eldest + "]");
                return size() > 3;
            }
        };
        lru.put("a", 1);
        lru.put("b", 2);
        lru.put("c", 3);
        System.out.println(" " + lru);
        System.out.println(lru.putFirst("d", 4) + " " + lru);
        System.out.println(lru.putLast("e", 5) + " " + lru);
        System.out.println(lru.putFirst("c", 30) + " " + lru);
        System.out.println(lru.putLast("d", 40) + " " + lru);
        System.out.println(lru.reversed().get("c") + " " + lru);
        System.out.println(lru.reversed().put("f", 6) + " " + lru);
        System.out.println(lru.firstEntry() + " " + lru.lastEntry() + " " + lru);
        System.out.println(lru.reversed().putFirst("g", 7) + " " + lru);
        System.out.println(LinkedHashMap.newLinkedHashMap(3) + " " + HashMap.newHashMap(3));
        attempt("newHashMap(-1)", () -> HashMap.newHashMap(-1));
        attempt("newLinkedHashMap(-1)", () -> LinkedHashMap.newLinkedHashMap(-1));
        for (int n : new int[] {0, 12, 13, 25, 100}) {
            Map<Integer, Integer> h = HashMap.newHashMap(n);
            for (int i = 0; i < 40; i++) {
                h.put(i * 17, i);
            }
            System.out.println("newHashMap(" + n + ") " + h.keySet());
        }
        // putFirst of a key reached in access order: a change?
        LinkedHashMap<String, Integer> acc = new LinkedHashMap<>(16, 0.75f, true);
        acc.put("a", 1);
        acc.put("b", 2);
        attempt("acc walk putLast last", () -> { for (String k : acc.keySet()) { acc.putLast("b", 0); } return acc; });
        // a sorted map that is not navigable
        SortedMap<String, Integer> plain = Collections.unmodifiableSortedMap(new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3, "d", 4)));
        SortedMap<String, Integer> pr = plain.reversed();
        System.out.println((plain instanceof NavigableMap) + " " + pr + " " + pr.firstKey() + " " + pr.lastKey() + " " + pr.firstEntry() + " " + pr.lastEntry() + " " + (pr.reversed() == plain) + " " + pr.comparator().compare("a", "b"));
        System.out.println(pr.headMap("b") + " " + pr.tailMap("b") + " " + pr.subMap("c", "a") + " " + pr.headMap("b").reversed() + " " + pr.keySet() + " " + pr.values() + " " + pr.entrySet() + " " + pr.get("c") + " " + pr.equals(plain));
        attempt("pr subMap(a, c)", () -> pr.subMap("a", "c"));
        attempt("pr put", () -> pr.put("e", 5));
        attempt("pr spliterators", () -> split(pr.keySet().spliterator()) + " / " + split(pr.values().spliterator()) + " / " + split(pr.entrySet().spliterator()));
        SortedMap<String, Integer> sync = Collections.synchronizedSortedMap(new TreeMap<>(Map.of("a", 1, "b", 2, "c", 3, "d", 4)));
        SortedMap<String, Integer> sr = sync.reversed();
        System.out.println(sr + " " + sr.put("e", 5) + " " + sync + " " + sr.remove("a") + " " + sync + " " + sr.headMap("c") + " " + sr.pollFirstEntry() + " " + sync);
        attempt("sr putFirst", () -> sr.putFirst("z", 0));
        attempt("sr headMap put outside", () -> sr.headMap("c").put("b", 0));
        attempt("sr headMap put inside", () -> sr.headMap("c").put("cc", 0) + " " + sync);
        attempt("sr entry setValue", () -> sr.entrySet().iterator().next().setValue(7) + " " + sync);
        attempt("sr keySet iterator remove", () -> { Iterator<String> it = sr.keySet().iterator(); it.next(); it.remove(); return sync + " " + sr; });
        attempt("sr firstEntry setValue", () -> sr.firstEntry().setValue(1));
        attempt("sr empty", () -> { SortedMap<String, Integer> e = Collections.unmodifiableSortedMap(new TreeMap<String, Integer>()).reversed(); return e + " " + e.firstEntry(); });
        attempt("sr empty firstKey", () -> Collections.unmodifiableSortedMap(new TreeMap<String, Integer>()).reversed().firstKey());
        // a sequenced map of the program's own: the defaults
        SequencedMap<String, Integer> mine = new SequencedMap<String, Integer>() {
            final Map<String, Integer> held = new LinkedHashMap<>(Map.of("p", 1));
            public SequencedMap<String, Integer> reversed() { throw new UnsupportedOperationException("mine reversed"); }
            public int size() { return held.size(); }
            public boolean isEmpty() { return held.isEmpty(); }
            public boolean containsKey(Object k) { return held.containsKey(k); }
            public boolean containsValue(Object v) { return held.containsValue(v); }
            public Integer get(Object k) { return held.get(k); }
            public Integer put(String k, Integer v) { return held.put(k, v); }
            public Integer remove(Object k) { return held.remove(k); }
            public void putAll(Map<? extends String, ? extends Integer> m) { held.putAll(m); }
            public void clear() { held.clear(); }
            public Set<String> keySet() { return held.keySet(); }
            public Collection<Integer> values() { return held.values(); }
            public Set<Map.Entry<String, Integer>> entrySet() { return held.entrySet(); }
            public String toString() { return held.toString(); }
        };
        mine.put("q", 2);
        attempt("mine firstEntry", () -> mine.firstEntry() + " " + (mine.firstEntry() instanceof AbstractMap.SimpleImmutableEntry));
        attempt("mine firstEntry setValue", () -> mine.firstEntry().setValue(5));
        attempt("mine lastEntry", () -> mine.lastEntry());
        attempt("mine pollFirstEntry", () -> mine.pollFirstEntry() + " " + mine);
        attempt("mine pollLastEntry", () -> mine.pollLastEntry());
        attempt("mine putFirst", () -> mine.putFirst("z", 0));
        attempt("mine putLast", () -> mine.putLast("z", 0));
        attempt("mine sequencedKeySet", () -> mine.sequencedKeySet() + " " + mine.sequencedValues() + " " + mine.sequencedEntrySet() + " " + mine.sequencedKeySet().getFirst());
        attempt("mine sequencedKeySet add", () -> mine.sequencedKeySet().add("x"));
        attempt("mine sequencedValues add", () -> mine.sequencedValues().add(9));
        attempt("mine sequencedEntrySet add", () -> mine.sequencedEntrySet().add(Map.entry("x", 1)));
        attempt("mine sequencedKeySet reversed", () -> mine.sequencedKeySet().reversed());
        attempt("mine sequencedKeySet getLast", () -> mine.sequencedKeySet().getLast());
        attempt("mine sequencedKeySet remove", () -> mine.sequencedKeySet().remove("q") + " " + mine);
        attempt("mine sequencedKeySet equals", () -> { mine.put("q", 2); return mine.sequencedKeySet().equals(Set.of("q")) + " " + mine.sequencedKeySet().hashCode() + " " + mine.sequencedValues().equals(List.of(2)) + " " + mine.sequencedEntrySet().equals(Set.of(Map.entry("q", 2))); });
        attempt("lhm sequencedValues equals", () -> l.sequencedValues().equals(l.values()) + " " + l.values().equals(l.values()));
        attempt("entry of a null", () -> { LinkedHashMap<String, Integer> n = new LinkedHashMap<>(); n.put(null, null); return n.firstEntry() + " " + n.firstEntry().getKey() + " " + n.firstEntry().equals(new AbstractMap.SimpleEntry<String, Integer>(null, null)) + " " + n.firstEntry().hashCode(); });
    }
}
