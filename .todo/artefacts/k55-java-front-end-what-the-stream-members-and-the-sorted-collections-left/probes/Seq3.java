import java.util.*;
import java.util.function.*;

public class Seq3 {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SortedSet<Integer> pr = Collections.unmodifiableSortedSet(new TreeSet<>(List.of(5, 1, 4, 2, 3))).reversed();
        String[] names = {"headSet(3)", "tailSet(3)", "subSet(4, 2)"};
        List<SortedSet<Integer>> views = List.of(pr.headSet(3), pr.tailSet(3), pr.subSet(4, 2));
        for (int v = 0; v < 3; v++) {
            SortedSet<Integer> s = views.get(v);
            for (int k = 0; k <= 6; k++) {
                int key = k;
                attempt(names[v] + ".headSet(" + k + ")", () -> s.headSet(key));
                attempt(names[v] + ".tailSet(" + k + ")", () -> s.tailSet(key));
                attempt(names[v] + ".add(" + k + ")", () -> s.add(key));
                attempt(names[v] + ".contains(" + k + ")", () -> s.contains(key) + " " + s.remove(key));
            }
            for (int a = 0; a <= 6; a += 2) {
                for (int b = 0; b <= 6; b += 3) {
                    int x = a;
                    int y = b;
                    attempt(names[v] + ".subSet(" + a + ", " + b + ")", () -> s.subSet(x, y));
                }
            }
        }
        SortedMap<Integer, String> mr = Collections.unmodifiableSortedMap(new TreeMap<>(Map.of(1, "a", 2, "b", 3, "c", 4, "d", 5, "e"))).reversed();
        List<SortedMap<Integer, String>> maps = List.of(mr.headMap(3), mr.tailMap(3), mr.subMap(4, 2));
        String[] mnames = {"headMap(3)", "tailMap(3)", "subMap(4, 2)"};
        for (int v = 0; v < 3; v++) {
            SortedMap<Integer, String> m = maps.get(v);
            for (int k = 0; k <= 6; k++) {
                int key = k;
                attempt(mnames[v] + ".headMap(" + k + ")", () -> m.headMap(key));
                attempt(mnames[v] + ".tailMap(" + k + ")", () -> m.tailMap(key));
                attempt(mnames[v] + ".put(" + k + ")", () -> m.put(key, "x"));
            }
            for (int a = 0; a <= 6; a += 2) {
                for (int b = 0; b <= 6; b += 3) {
                    int x = a;
                    int y = b;
                    attempt(mnames[v] + ".subMap(" + a + ", " + b + ")", () -> m.subMap(x, y));
                }
            }
        }
        for (int a = 0; a <= 6; a += 2) {
            for (int b = 0; b <= 6; b += 3) {
                int x = a;
                int y = b;
                attempt("mr.subMap(" + a + ", " + b + ")", () -> mr.subMap(x, y));
                attempt("pr.subSet(" + a + ", " + b + ")", () -> pr.subSet(x, y));
            }
        }
        // the entries a LinkedHashMap's sequenced entry set answers
        LinkedHashMap<String, Integer> l = new LinkedHashMap<>();
        l.put("x", 1);
        l.put("y", 2);
        attempt("lhm entry getFirst setValue", () -> l.sequencedEntrySet().getFirst().setValue(5) + " " + l);
        attempt("lhm entry getLast setValue", () -> l.sequencedEntrySet().getLast().setValue(6) + " " + l);
        attempt("lhm entry reversed getFirst setValue", () -> l.sequencedEntrySet().reversed().getFirst().setValue(7) + " " + l);
        attempt("lhm entry removeFirst", () -> l.sequencedEntrySet().removeFirst() + " " + l);
        attempt("lhm reversed entry getFirst setValue", () -> l.reversed().sequencedEntrySet().getFirst().setValue(8) + " " + l);
        attempt("lhm reversed keySet getFirst", () -> l.reversed().keySet().iterator().next() + " " + l.reversed().sequencedKeySet().getFirst() + " " + l.reversed().sequencedValues().getLast());
        // the default views of an empty map of the program
        SequencedMap<String, Integer> empty = new SequencedMap<String, Integer>() {
            final LinkedHashMap<String, Integer> held = new LinkedHashMap<>();
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
            public Set<String> keySet() { return held.keySet(); }
            public Collection<Integer> values() { return held.values(); }
            public Set<Map.Entry<String, Integer>> entrySet() { return held.entrySet(); }
        };
        attempt("default keys getFirst", () -> empty.sequencedKeySet().getFirst());
        attempt("default keys getLast", () -> empty.sequencedKeySet().getLast());
        attempt("default keys removeFirst", () -> empty.sequencedKeySet().removeFirst());
        attempt("default keys removeLast", () -> empty.sequencedKeySet().removeLast());
        attempt("default values getFirst", () -> empty.sequencedValues().getFirst());
        attempt("default entries removeLast", () -> empty.sequencedEntrySet().removeLast());
        empty.put("k", 1);
        attempt("default entries getFirst setValue", () -> empty.sequencedEntrySet().getFirst().setValue(2));
        attempt("default entries iterator setValue", () -> empty.sequencedEntrySet().iterator().next().setValue(3) + " " + empty.get("k"));
        attempt("default values removeFirst", () -> empty.sequencedValues().removeFirst() + " " + empty.size());
        attempt("default keys reversed", () -> { empty.put("a", 1); empty.put("b", 2); return empty.sequencedKeySet().reversed() + " " + empty.sequencedValues().reversed() + " " + empty.sequencedEntrySet().reversed() + " " + empty.sequencedKeySet().reversed().reversed(); });
        attempt("default keys addAll", () -> empty.sequencedKeySet().addAll(List.of()));
        attempt("default keys removeAll", () -> empty.sequencedKeySet().removeAll(List.of("a")) + " " + empty);
    }
}
