import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.*;
import java.util.stream.*;

class TreeOrderPoint implements Comparable<TreeOrderPoint> {
    final int x;
    final int y;

    TreeOrderPoint(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int compareTo(TreeOrderPoint o) {
        return x != o.x ? Integer.compare(x, o.x) : Integer.compare(y, o.y);
    }

    public String toString() {
        return "(" + x + "," + y + ")";
    }
}

class TreeOrder {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + (e instanceof NullPointerException ? "" : " " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        // the keys in their order, and the members that find a neighbour
        TreeMap<String, Integer> m = new TreeMap<>();
        for (String w : "pear fig apple kiwi date banana cherry fig apple".split(" ")) {
            m.merge(w, 1, Integer::sum);
        }
        System.out.println(m + " " + m.firstKey() + " " + m.lastKey() + " " + m.get("fig") + " " + m.put("fig", 10) + " " + m.put("grape", 3) + " " + m.remove("pear") + " " + m.containsValue(3) + " " + m.comparator());
        System.out.println(m.firstEntry() + " " + m.lowerEntry("date") + " " + m.floorKey("dog") + " " + m.ceilingEntry("dog") + " " + m.higherKey("kiwi") + " " + m.pollLastEntry() + " " + m);
        System.out.println(m.headMap("date") + " " + m.tailMap("date", false) + " " + m.subMap("b", "g") + " " + m.descendingMap() + " " + m.descendingKeySet() + " " + m.descendingMap().headMap("date") + " " + m.reversed().firstKey());
        System.out.println(m.equals(new HashMap<>(m)) + " " + m.hashCode() + " " + (m.keySet() instanceof NavigableSet) + " " + (m instanceof SortedMap));
        // a view is a range of the map, and what is done to it is done to the map
        TreeMap<Integer, String> t = new TreeMap<>();
        for (int i = 1; i <= 10; i++) {
            t.put(i * 10, "n" + i);
        }
        NavigableMap<Integer, String> mid = t.subMap(30, true, 70, false);
        mid.put(35, "x");
        attempt("put out of range", () -> mid.put(70, "y"));
        System.out.println(mid + " " + mid.size() + " " + mid.lowerKey(31) + " " + mid.floorKey(100) + " " + mid.ceilingKey(0) + " " + mid.higherKey(60) + " " + mid.get(20) + " " + mid.descendingMap().headMap(50));
        attempt("headMap past the range", () -> mid.headMap(70, true));
        attempt("tailMap below the range", () -> mid.tailMap(20));
        attempt("subMap the wrong way", () -> mid.subMap(50, 40));
        attempt("subMap of a descending view", () -> mid.descendingMap().subMap(60, 35));
        attempt("subMap from above to below", () -> t.subMap(90, 10));
        System.out.println(mid.pollFirstEntry() + " " + mid.pollLastEntry() + " " + t);
        t.tailMap(70, true).clear();
        System.out.println(t + " " + t.headMap(35).size() + " " + t.tailMap(200).isEmpty());
        attempt("firstKey of nothing", () -> t.tailMap(200).firstKey());
        attempt("firstEntry of nothing", () -> t.headMap(0).firstEntry());
        // an order of its own: the key first put is the one kept
        TreeMap<String, Integer> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.put("Apple", 1);
        ci.put("apple", 2);
        ci.put("BANANA", 3);
        TreeMap<Integer, String> rev = new TreeMap<>(Comparator.reverseOrder());
        for (int i = 0; i < 6; i++) {
            rev.put(i * 3 % 7, "v" + i);
        }
        System.out.println(ci + " " + ci.get("APPLE") + " " + (new TreeMap<>(ci).comparator() == String.CASE_INSENSITIVE_ORDER) + " " + rev + " " + rev.headMap(3) + " " + rev.floorKey(4) + " " + rev.descendingMap());
        TreeMap<String, Integer> nulls = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        nulls.put("b", 1);
        nulls.put(null, 0);
        System.out.println(nulls + " " + nulls.get(null) + " " + nulls.headMap("b"));
        attempt("put null", () -> m.put(null, 1));
        attempt("get null", () -> new TreeMap<String, Integer>().get(null));
        attempt("ceilingKey null of nothing", () -> new TreeMap<String, Integer>().ceilingKey(null));
        TreeMap<TreeOrderPoint, String> points = new TreeMap<>();
        points.put(new TreeOrderPoint(2, 1), "c");
        points.put(new TreeOrderPoint(1, 5), "b");
        points.put(new TreeOrderPoint(1, 2), "a");
        System.out.println(points + " " + points.ceilingKey(new TreeOrderPoint(1, 3)));
        // walking it: an entry can be set, a change while walking is refused
        Iterator<Map.Entry<Integer, String>> each = t.entrySet().iterator();
        while (each.hasNext()) {
            Map.Entry<Integer, String> e = each.next();
            if (e.getKey() == 20) {
                each.remove();
            } else {
                e.setValue(e.getValue() + "!");
            }
        }
        System.out.println(t);
        attempt("put while walking", () -> {
            for (Integer k : t.keySet()) {
                t.put(k + 1, "w");
            }
            return t;
        });
        attempt("value set while walking", () -> {
            for (Integer k : t.keySet()) {
                t.put(k, "r");
            }
            return t;
        });
        attempt("firstEntry setValue", () -> t.firstEntry().setValue("no"));
        attempt("computeIfAbsent that puts", () -> t.computeIfAbsent(99, k -> {
            t.put(98, "side");
            return "v";
        }));
        System.out.println(t + " " + t.putIfAbsent(5, "five") + " " + t.compute(5, (k, v) -> v + "!") + " " + t.merge(5, "?", String::concat) + " " + t.computeIfPresent(98, (k, v) -> null) + " " + t);
        attempt("putFirst", () -> t.putFirst(1, "one"));
        // a set over the keys of a tree
        TreeSet<Integer> s = new TreeSet<>(List.of(50, 20, 80, 10, 30, 70, 90));
        System.out.println(s + " " + s.add(20) + " " + s.lower(20) + " " + s.ceiling(25) + " " + s.headSet(30, true) + " " + s.tailSet(70, false) + " " + s.subSet(20, 70) + " " + s.descendingSet() + " " + s.pollFirst() + " " + s.pollLast() + " " + s.getFirst() + " " + s.removeLast() + " " + s);
        NavigableSet<Integer> head = s.headSet(60, false);
        head.add(25);
        attempt("add out of range", () -> head.add(60));
        System.out.println(s + " " + head + " " + (head instanceof TreeSet));
        TreeSet<String> words = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Collections.addAll(words, "b", "A", "a", "C");
        System.out.println(words + " " + words.contains("c") + " " + words.removeAll(List.of("a")) + " " + words + " " + words.removeAll(List.of("b", "x")) + " " + words);
        attempt("first of nothing", () -> new TreeSet<Integer>().first());
        attempt("removeFirst of nothing", () -> new TreeSet<Integer>().removeFirst());
        attempt("addFirst", () -> {
            s.addFirst(1);
            return s;
        });
        // what a tree is collected into, and what wraps one
        Map<Integer, Long> byLength = Stream.of("pear", "fig", "apple", "kiwi", "date").collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.counting()));
        TreeSet<String> collected = Stream.of("pear", "fig", "apple", "fig").collect(Collectors.toCollection(TreeSet::new));
        System.out.println(byLength + " " + collected + " " + t.keySet().stream().mapToInt(Integer::intValue).sum());
        NavigableMap<String, Integer> fixed = Collections.unmodifiableNavigableMap(m);
        System.out.println(fixed.firstKey() + " " + fixed.descendingMap() + " " + fixed.headMap("c") + " " + Collections.unmodifiableSortedSet(s).first() + " " + Collections.synchronizedNavigableMap(new TreeMap<>(m)).descendingKeySet() + " " + Collections.emptyNavigableSet() + " " + Collections.emptySortedMap());
        attempt("unmodifiable pollFirstEntry", () -> fixed.pollFirstEntry());
        attempt("unmodifiable view put", () -> fixed.headMap("c").put("b", 1));
        attempt("unmodifiable entry setValue", () -> fixed.firstEntry().setValue(0));
        // a map's remove and replace of a value
        Map<String, Integer> h = new HashMap<>(Map.of("a", 1, "b", 2));
        System.out.println(h.remove("a", 2) + " " + h.remove("a", 1) + " " + h.replace("b", 1, 3) + " " + h.replace("b", 2, 3) + " " + h);
        // the skip list: sorted, shared, no null, an iterator that never refuses
        ConcurrentSkipListMap<String, Integer> sl = new ConcurrentSkipListMap<>();
        for (String w : "pear fig apple kiwi date".split(" ")) {
            sl.put(w, w.length());
        }
        StringBuilder seen = new StringBuilder();
        for (String k : sl.keySet()) {
            seen.append(k).append(' ');
            if (k.equals("date")) {
                sl.remove("kiwi");
                sl.put("cherry", 6);
                sl.put("grape", 5);
            }
        }
        System.out.println(seen + "" + sl + " " + sl.headMap("fig") + " " + sl.descendingMap() + " " + sl.ceilingEntry("e") + " " + sl.pollFirstEntry() + " " + sl.putIfAbsent("kiwi", 9) + " " + sl.replace("kiwi", 4, 44) + " " + sl);
        attempt("skip list null value", () -> sl.put("x", null));
        attempt("skip list inconsistent range", () -> sl.subMap("z", "a"));
        attempt("skip list view out of range", () -> sl.tailMap("d").tailMap("a"));
        attempt("skip list entry setValue", () -> sl.entrySet().iterator().next().setValue(1));
        ConcurrentNavigableMap<String, Integer> part = sl.tailMap("d");
        ConcurrentSkipListSet<Integer> ss = new ConcurrentSkipListSet<>(List.of(5, 3, 8, 1));
        System.out.println(part + " " + part.firstKey() + " " + ss + " " + ss.headSet(5) + " " + ss.add(3) + " " + ss.pollFirst() + " " + ss.descendingSet());
    }
}
