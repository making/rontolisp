import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Tree {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    static void run(String label, Runnable r) {
        try {
            r.run();
            System.out.println(label + " ok");
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // natural order, the basic members
        TreeMap<String, Integer> m = new TreeMap<>();
        for (String w : "pear fig apple kiwi date banana cherry fig apple".split(" ")) {
            m.merge(w, 1, Integer::sum);
        }
        System.out.println(m + " " + m.size() + " " + m.firstKey() + " " + m.lastKey() + " " + m.comparator());
        System.out.println(m.keySet() + " " + m.values() + " " + m.entrySet());
        System.out.println(m.get("fig") + " " + m.get("grape") + " " + m.containsKey("kiwi") + " " + m.containsValue(2) + " " + m.containsValue(7));
        System.out.println(m.put("fig", 10) + " " + m.put("grape", 3) + " " + m.remove("pear") + " " + m.remove("pear") + " " + m);
        System.out.println(m.firstEntry() + " " + m.lastEntry() + " " + m.lowerEntry("date") + " " + m.floorEntry("date") + " " + m.ceilingEntry("dog") + " " + m.higherEntry("date"));
        System.out.println(m.lowerKey("apple") + " " + m.floorKey("b") + " " + m.ceilingKey("z") + " " + m.higherKey("kiwi") + " " + m.higherKey("grape"));
        System.out.println(m.headMap("date") + " " + m.headMap("date", true) + " " + m.tailMap("date") + " " + m.tailMap("date", false) + " " + m.subMap("b", "g") + " " + m.subMap("banana", false, "grape", true));
        System.out.println(m.descendingMap() + " " + m.descendingKeySet() + " " + m.navigableKeySet() + " " + m.descendingMap().firstKey() + " " + m.descendingMap().headMap("date"));
        System.out.println(m.pollFirstEntry() + " " + m.pollLastEntry() + " " + m);
        System.out.println(m.hashCode() + " " + m.equals(new HashMap<>(m)) + " " + new HashMap<>(m).equals(m) + " " + m.keySet().equals(new HashSet<>(m.keySet())));
        System.out.println((m.keySet() instanceof NavigableSet) + " " + (m instanceof SortedMap) + " " + (m instanceof NavigableMap) + " " + (m instanceof AbstractMap) + " " + m.getClass().getName());

        // empty maps
        TreeMap<Integer, String> e = new TreeMap<>();
        System.out.println(e + " " + e.firstEntry() + " " + e.lastEntry() + " " + e.pollFirstEntry() + " " + e.pollLastEntry() + " " + e.lowerKey(3) + " " + e.ceilingEntry(3) + " " + e.headMap(3) + " " + e.descendingMap());
        attempt("firstKey", () -> e.firstKey());
        attempt("lastKey", () -> e.lastKey());
        attempt("descending firstKey", () -> e.descendingMap().firstKey());
        attempt("head lastKey", () -> e.headMap(5).lastKey());
        attempt("get null empty", () -> e.get(null));
        attempt("containsKey null empty", () -> e.containsKey(null));
        attempt("remove null empty", () -> e.remove(null));
        attempt("put null empty", () -> e.put(null, "x"));
        attempt("ceiling null empty", () -> e.ceilingKey(null));
        attempt("headMap null empty", () -> e.headMap(null));
        TreeMap<Object, String> o = new TreeMap<>();
        attempt("put object empty", () -> o.put(new Object() {
            public String toString() {
                return "obj";
            }
        }, "x"));
        System.out.println(o);
        attempt("get object empty", () -> o.get(new Object()));
        o.put("s", "t");
        attempt("put int into strings", () -> o.put(1, "x"));
        attempt("get int from strings", () -> o.get(1));
        attempt("put null", () -> m.put(null, 1));
        attempt("get null", () -> m.get(null));
        attempt("remove null", () -> m.remove(null));
        attempt("containsKey null", () -> m.containsKey(null));
        attempt("floorKey null", () -> m.floorKey(null));
        attempt("put null value", () -> m.put("nul", null));
        System.out.println(m + " " + m.containsValue(null) + " " + m.get("nul") + " " + m.containsKey("nul"));
        m.remove("nul");

        // a comparator: the key first put is the one kept
        TreeMap<String, Integer> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.put("Apple", 1);
        ci.put("apple", 2);
        ci.put("BANANA", 3);
        ci.put("banana", 4);
        ci.put("cherry", 5);
        System.out.println(ci + " " + ci.get("APPLE") + " " + ci.containsKey("Cherry") + " " + (ci.comparator() == String.CASE_INSENSITIVE_ORDER) + " " + ci.firstKey() + " " + ci.ceilingKey("B"));
        System.out.println(ci.equals(Map.of("apple", 2, "banana", 4, "cherry", 5)) + " " + Map.of("apple", 2, "banana", 4, "cherry", 5).equals(ci) + " " + ci.keySet().contains("CHERRY"));
        TreeMap<Integer, String> rev = new TreeMap<>(Comparator.reverseOrder());
        for (int i = 0; i < 6; i++) {
            rev.put(i * 3 % 7, "v" + i);
        }
        System.out.println(rev + " " + rev.firstKey() + " " + rev.headMap(3) + " " + rev.tailMap(3) + " " + rev.subMap(5, 1) + " " + rev.descendingMap() + " " + rev.floorKey(4) + " " + rev.higherKey(4));
        attempt("subMap from > to", () -> rev.subMap(1, 5));
        attempt("subMap natural from > to", () -> m.subMap("z", "a"));
        attempt("subMap equal exclusive", () -> m.subMap("date", false, "date", false));
        attempt("subMap equal inclusive", () -> m.subMap("date", true, "date", true));
        TreeMap<String, Integer> nf = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        nf.put("b", 1);
        nf.put(null, 0);
        nf.put("a", 2);
        System.out.println(nf + " " + nf.get(null) + " " + nf.firstKey() + " " + nf.headMap("a") + " " + nf.containsKey(null));

        // copies
        TreeMap<String, Integer> copied = new TreeMap<>(ci);
        System.out.println(copied + " " + (copied.comparator() == String.CASE_INSENSITIVE_ORDER));
        Map<String, Integer> asMap = ci;
        TreeMap<String, Integer> copied2 = new TreeMap<>(asMap);
        System.out.println(copied2 + " " + copied2.comparator());
        SortedMap<String, Integer> sorted = ci;
        TreeMap<String, Integer> copied3 = new TreeMap<>(sorted);
        System.out.println(copied3 + " " + (copied3.comparator() == String.CASE_INSENSITIVE_ORDER));
        TreeMap<String, Integer> fromHash = new TreeMap<>(new HashMap<>(Map.of("z", 1, "y", 2, "x", 3)));
        System.out.println(fromHash);
        attempt("from hash with null key", () -> {
            HashMap<String, Integer> h = new HashMap<>();
            h.put(null, 1);
            return new TreeMap<>(h);
        });

        // the views change the map
        TreeMap<Integer, String> t = new TreeMap<>();
        for (int i = 1; i <= 10; i++) {
            t.put(i * 10, "n" + i);
        }
        NavigableMap<Integer, String> mid = t.subMap(30, true, 70, false);
        System.out.println(mid + " " + mid.size() + " " + mid.firstKey() + " " + mid.lastKey() + " " + mid.get(20) + " " + mid.get(40) + " " + mid.containsKey(70) + " " + mid.isEmpty());
        mid.put(35, "x");
        attempt("put 70 into mid", () -> mid.put(70, "y"));
        attempt("put 20 into mid", () -> mid.put(20, "y"));
        attempt("put 30 into mid", () -> mid.put(30, "z"));
        System.out.println(t + " " + mid);
        System.out.println(mid.remove(40) + " " + mid.remove(80) + " " + t.containsKey(40) + " " + t.get(80));
        System.out.println(mid.lowerKey(30) + " " + mid.lowerKey(31) + " " + mid.floorKey(100) + " " + mid.ceilingKey(0) + " " + mid.higherKey(60) + " " + mid.higherKey(5) + " " + mid.lowerEntry(1000) + " " + mid.ceilingEntry(-5));
        System.out.println(mid.firstEntry() + " " + mid.lastEntry() + " " + mid.descendingMap() + " " + mid.descendingMap().firstKey() + " " + mid.headMap(50) + " " + mid.tailMap(50) + " " + mid.headMap(50, true));
        attempt("mid.headMap(80)", () -> mid.headMap(80));
        attempt("mid.headMap(70)", () -> mid.headMap(70));
        attempt("mid.headMap(70, true)", () -> mid.headMap(70, true));
        attempt("mid.tailMap(20)", () -> mid.tailMap(20));
        attempt("mid.tailMap(30, false)", () -> mid.tailMap(30, false));
        attempt("mid.subMap(20, 50)", () -> mid.subMap(20, 50));
        attempt("mid.subMap(40, 90)", () -> mid.subMap(40, 90));
        attempt("mid.subMap(50, 40)", () -> mid.subMap(50, 40));
        attempt("mid.descendingMap().subMap(60, 35)", () -> mid.descendingMap().subMap(60, 35));
        attempt("mid.descendingMap().subMap(35, 60)", () -> mid.descendingMap().subMap(35, 60));
        attempt("mid.descendingMap().headMap(50)", () -> mid.descendingMap().headMap(50));
        attempt("mid.descendingMap().tailMap(50)", () -> mid.descendingMap().tailMap(50));
        attempt("mid.descendingMap().put(10)", () -> mid.descendingMap().put(10, "q"));
        System.out.println(mid.pollFirstEntry() + " " + mid.pollLastEntry() + " " + t + " " + mid);
        mid.keySet().remove(50);
        mid.values().remove("n6");
        System.out.println(t + " " + mid + " " + mid.size());
        NavigableMap<Integer, String> tail = t.tailMap(70, true);
        tail.clear();
        System.out.println(t + " " + tail + " " + tail.size() + " " + tail.isEmpty());
        t.put(100, "c");
        t.put(65, "sf");
        System.out.println(tail + " " + mid + " " + t.headMap(65) + " " + t.headMap(65).size());
        attempt("tail.firstKey", () -> t.tailMap(200, true).firstKey());
        attempt("headMap(0).lastKey", () -> t.headMap(0).lastKey());
        attempt("headMap(0).firstEntry", () -> t.headMap(0).firstEntry());

        // iterators: remove, changes while walking, setValue
        Iterator<Map.Entry<Integer, String>> each = t.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<Integer, String> en = each.next();
            if (en.getKey() % 20 == 0) {
                each.remove();
            } else {
                en.setValue(en.getValue() + "!");
            }
        }
        System.out.println(t);
        run("put while walking", () -> {
            for (Integer k : t.keySet()) {
                t.put(k + 1, "w");
            }
        });
        System.out.println(t);
        run("replace while walking", () -> {
            for (Integer k : t.keySet()) {
                t.put(k, "r");
            }
        });
        run("remove while walking", () -> {
            for (Integer k : t.keySet()) {
                t.remove(k);
            }
        });
        System.out.println(t);
        run("remove twice", () -> {
            Iterator<Integer> it = t.keySet().iterator();
            it.next();
            it.remove();
            it.remove();
        });
        run("remove first", () -> {
            Iterator<Integer> it = t.keySet().iterator();
            it.remove();
        });
        run("next past end", () -> {
            Iterator<Integer> it = t.headMap(0).keySet().iterator();
            it.next();
        });
        System.out.println(t);
        Map.Entry<Integer, String> fe = t.firstEntry();
        attempt("firstEntry setValue", () -> fe.setValue("no"));
        attempt("ceilingEntry setValue", () -> t.ceilingEntry(0).setValue("no"));
        Map.Entry<Integer, String> live = t.entrySet().iterator().next();
        attempt("entry setValue", () -> live.setValue("yes"));
        t.put(live.getKey(), "again");
        System.out.println(live + " " + fe + " " + t + " " + live.equals(fe) + " " + live.hashCode() + " " + fe.hashCode());
        System.out.println(t.entrySet().contains(Map.entry(live.getKey(), "again")) + " " + t.entrySet().contains(Map.entry(live.getKey(), "x")) + " " + t.entrySet().remove(Map.entry(live.getKey(), "again")) + " " + t);
        Iterator<Integer> down = t.descendingKeySet().iterator();
        StringBuilder sb = new StringBuilder();
        while (down.hasNext()) {
            int k = down.next();
            sb.append(k).append(' ');
            if (k == 65) {
                down.remove();
            }
        }
        System.out.println(sb + "" + t);
        t.put(5, "five");
        t.put(95, "ninety-five");
        t.put(50, "fifty");
        Iterator<Integer> asc = t.navigableKeySet().descendingIterator();
        System.out.println(asc.next() + " " + t.descendingKeySet().descendingIterator().next() + " " + t.descendingMap().descendingMap() + " " + t.descendingMap().navigableKeySet() + " " + t.descendingMap().descendingKeySet());

        // the defaults over a tree
        TreeMap<String, Integer> d = new TreeMap<>();
        System.out.println(d.putIfAbsent("a", 1) + " " + d.putIfAbsent("a", 2) + " " + d.computeIfAbsent("b", k -> 2) + " " + d.computeIfAbsent("a", k -> 9) + " " + d.computeIfPresent("a", (k, v) -> v + 10) + " " + d.computeIfPresent("z", (k, v) -> 0) + " " + d.compute("c", (k, v) -> v == null ? 3 : v + 1) + " " + d.compute("c", (k, v) -> v == null ? 3 : v + 1) + " " + d.merge("d", 4, Integer::sum) + " " + d.merge("d", 4, Integer::sum) + " " + d);
        System.out.println(d.replace("a", 0) + " " + d.replace("q", 0) + " " + d.replace("b", 2, 20) + " " + d.replace("b", 2, 30) + " " + d.remove("c", 3) + " " + d.remove("c", 4) + " " + d.getOrDefault("zz", -1) + " " + d);
        d.compute("a", (k, v) -> null);
        d.merge("b", 1, (x, y) -> null);
        d.computeIfPresent("d", (k, v) -> null);
        System.out.println(d);
        d.putAll(Map.of("m", 1, "k", 2, "l", 3));
        d.replaceAll((k, v) -> v * 100);
        StringBuilder fe2 = new StringBuilder();
        d.forEach((k, v) -> fe2.append(k).append(v));
        System.out.println(d + " " + fe2);
        attempt("computeIfAbsent that puts", () -> d.computeIfAbsent("new", k -> {
            d.put("other", 1);
            return 5;
        }));
        System.out.println(d);
        attempt("compute that puts", () -> d.compute("new2", (k, v) -> {
            d.put("other2", 1);
            return 5;
        }));
        System.out.println(d);
        attempt("merge that puts", () -> d.merge("k", 5, (x, y) -> {
            d.put("other3", 1);
            return 6;
        }));
        System.out.println(d);
        attempt("computeIfPresent that removes", () -> d.computeIfPresent("k", (k, v) -> {
            d.remove("m");
            return 7;
        }));
        System.out.println(d);
        attempt("putFirst", () -> d.putFirst("a", 1));
        attempt("putLast", () -> d.putLast("a", 1));
        System.out.println(d.reversed() + " " + d.reversed().firstKey());
        attempt("merge null value", () -> d.merge("zz", null, (x, y) -> x));
        attempt("computeIfAbsent null fn", () -> d.computeIfAbsent("zz", null));

        // streams and collectors into a tree
        Map<Integer, Long> byLength = Stream.of("pear", "fig", "apple", "kiwi", "date", "banana").collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.counting()));
        System.out.println(byLength + " " + byLength.getClass().getSimpleName());
        TreeSet<String> words = Stream.of("pear", "fig", "apple", "fig").collect(Collectors.toCollection(TreeSet::new));
        System.out.println(words + " " + t.entrySet().stream().map(x -> x.getKey() + ":" + x.getValue()).collect(Collectors.joining(",")) + " " + t.keySet().stream().mapToInt(Integer::intValue).sum());
        Map<Boolean, List<Integer>> parts = t.keySet().stream().collect(Collectors.partitioningBy(x -> x > 50));
        System.out.println(parts + " " + t.values().stream().sorted().toList() + " " + t.descendingMap().values().stream().toList());
    }
}
