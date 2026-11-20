import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Skip {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        ConcurrentSkipListMap<String, Integer> m = new ConcurrentSkipListMap<>();
        for (String w : "pear fig apple kiwi date banana cherry".split(" ")) {
            m.put(w, w.length());
        }
        System.out.println(m + " " + m.size() + " " + m.firstKey() + " " + m.lastEntry() + " " + m.comparator() + " " + m.headMap("d") + " " + m.tailMap("kiwi", false) + " " + m.subMap("b", "e"));
        System.out.println(m.computeIfAbsent("grape", k -> k.length()) + " " + m.computeIfAbsent("fig", k -> 99) + " " + m.computeIfPresent("fig", (k, v) -> v * 10) + " " + m.compute("lime", (k, v) -> v == null ? 4 : v + 1) + " " + m.compute("lime", (k, v) -> v == null ? 4 : v + 1) + " " + m.merge("pear", 6, Integer::sum) + " " + m.merge("plum", 4, Integer::sum) + " " + m);
        System.out.println(m.compute("lime", (k, v) -> null) + " " + m.computeIfPresent("plum", (k, v) -> null) + " " + m.merge("pear", 1, (a, b) -> null) + " " + m.replace("fig", 3) + " " + m.replace("nope", 3) + " " + m.replace("fig", 3, 33) + " " + m.replace("fig", 3, 34) + " " + m.remove("fig", 3) + " " + m.remove("fig", 33) + " " + m.remove("fig", null) + " " + m);
        m.replaceAll((k, v) -> v + k.length());
        StringBuilder each = new StringBuilder();
        m.forEach((k, v) -> each.append(k.charAt(0)).append(v));
        System.out.println(m + " " + each + " " + m.getOrDefault("zz", -1) + " " + m.containsValue(10) + " " + m.descendingKeySet() + " " + m.navigableKeySet().headSet("c"));
        attempt("replace null", () -> m.replace("apple", null, 1));
        attempt("compute null key", () -> m.compute(null, (k, v) -> v));
        attempt("merge null value", () -> m.merge("apple", null, (a, b) -> a));
        attempt("putIfAbsent null", () -> m.putIfAbsent("q", null));
        attempt("ceilingKey null", () -> m.ceilingKey(null));
        attempt("headMap null", () -> m.headMap(null));
        attempt("remove null", () -> m.remove(null));
        attempt("containsKey null", () -> m.containsKey(null));
        attempt("putFirst", () -> m.putFirst("a", 1));
        NavigableMap<String, Integer> view = m.subMap("b", true, "g", false);
        System.out.println(view + " " + view.size() + " " + view.firstKey() + " " + view.pollLastEntry() + " " + m + " " + view.descendingMap() + " " + view.descendingMap().headMap("c"));
        attempt("view put out", () -> view.put("zz", 1));
        attempt("view putIfAbsent out", () -> view.putIfAbsent("zz", 1));
        attempt("view get out", () -> view.get("zz"));
        attempt("view remove out", () -> view.remove("zz"));
        attempt("view firstKey of nothing", () -> m.subMap("x", "y").firstKey());
        view.clear();
        System.out.println(m + " " + view + " " + view.isEmpty());
        // the iterators read ahead and see what is put beyond them
        ConcurrentSkipListSet<Integer> s = new ConcurrentSkipListSet<>(List.of(10, 20, 30, 40, 50));
        StringBuilder seen = new StringBuilder();
        for (Integer x : s) {
            seen.append(x).append(' ');
            if (x == 20) {
                s.remove(30);
                s.add(25);
                s.add(45);
                s.add(5);
            }
        }
        Iterator<Integer> down = s.descendingIterator();
        while (down.hasNext()) {
            int x = down.next();
            seen.append(x).append(',');
            if (x == 45) {
                down.remove();
                s.add(15);
            }
        }
        System.out.println(seen + " " + s + " " + s.size() + " " + s.first() + " " + s.headSet(20) + " " + s.tailSet(20, false) + " " + s.descendingSet().headSet(20) + " " + s.ceiling(21) + " " + s.lower(5));
        attempt("set headSet out", () -> s.headSet(20).add(30));
        attempt("set inconsistent", () -> s.subSet(30, 10));
        attempt("set view out", () -> s.headSet(20).tailSet(25));
        System.out.println(s.pollFirst() + " " + s.pollLast() + " " + s + " " + s.removeFirst() + " " + s.getLast() + " " + s.reversed());
        ConcurrentSkipListSet<Integer> e = new ConcurrentSkipListSet<>();
        attempt("empty first", () -> e.first());
        attempt("empty pollFirst", () -> e.pollFirst());
        attempt("empty removeFirst", () -> e.removeFirst());
        // threads putting into one map
        ConcurrentSkipListMap<Integer, Integer> shared = new ConcurrentSkipListMap<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int base = t;
            Thread th = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException ex) {
                    return;
                }
                for (int i = 0; i < 500; i++) {
                    shared.merge(i, 1, Integer::sum);
                    shared.put(10000 + base * 1000 + i, base);
                }
            });
            threads.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : threads) {
            th.join();
        }
        int ones = 0;
        for (int v : shared.headMap(500).values()) {
            ones += v;
        }
        System.out.println(shared.size() + " " + ones + " " + shared.firstKey() + " " + shared.lastKey() + " " + shared.tailMap(13000).size());
    }
}
