import java.util.*;
import java.util.function.*;

public class SplitRows {
    static String marks(Spliterator<?> s) {
        int c = s.characteristics();
        StringBuilder b = new StringBuilder();
        String[] names = { "ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED" };
        int[] bits = { Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL,
                Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED };
        for (int i = 0; i < names.length; i++) {
            if ((c & bits[i]) != 0) {
                b.append(b.length() == 0 ? "" : "|").append(names[i]);
            }
        }
        return c + " " + b;
    }

    static void row(String what, Iterable<?> source) {
        Spliterator<?> s = source.spliterator();
        StringBuilder line = new StringBuilder(what + ": " + marks(s));
        line.append(", size ").append(s.estimateSize()).append(" exact ").append(s.getExactSizeIfKnown());
        List<Object> seen = new ArrayList<>();
        boolean one = s.tryAdvance(seen::add);
        line.append(", advanced ").append(one).append(" size ").append(s.estimateSize());
        s.forEachRemaining(seen::add);
        line.append(", ").append(seen).append(" size ").append(s.estimateSize()).append(" again ").append(s.tryAdvance(seen::add));
        System.out.println(line);
    }

    static void fails(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ran");
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e.getClass().getName() + (e instanceof NullPointerException ? "" : " " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>(List.of(3, 1, 2));
        Map<String, Integer> map = new HashMap<>(Map.of("a", 1, "b", 2));
        TreeSet<Integer> treeSet = new TreeSet<>(list);
        TreeMap<String, Integer> tree = new TreeMap<>(map);
        java.util.concurrent.ConcurrentSkipListMap<String, Integer> skip = new java.util.concurrent.ConcurrentSkipListMap<>(map);
        row("TreeSet", treeSet);
        row("TreeSet headSet", treeSet.headSet(3));
        row("TreeSet descendingSet", treeSet.descendingSet());
        row("TreeMap keySet", tree.keySet());
        row("TreeMap values", tree.values());
        row("TreeMap entrySet", tree.entrySet());
        row("TreeMap descendingKeySet", tree.descendingKeySet());
        row("TreeMap headMap keySet", tree.headMap("b", true).keySet());
        row("TreeMap headMap values", tree.headMap("b", true).values());
        row("TreeMap headMap entrySet", tree.headMap("b", true).entrySet());
        row("TreeMap headMap descendingKeySet", tree.headMap("b", true).descendingKeySet());
        row("TreeMap descendingMap entrySet", tree.descendingMap().entrySet());
        row("unmodifiableNavigableSet", Collections.unmodifiableNavigableSet(treeSet));
        row("emptySortedSet", Collections.emptySortedSet());
        row("ConcurrentSkipListMap keySet", skip.keySet());
        row("ConcurrentSkipListMap values", skip.values());
        row("ConcurrentSkipListMap entrySet", skip.entrySet());
        row("ConcurrentSkipListMap descendingKeySet", skip.descendingKeySet());
        row("ConcurrentSkipListMap headMap values", skip.headMap("b", true).values());
        row("ConcurrentSkipListMap headMap entrySet", skip.headMap("b", true).entrySet());
        row("ConcurrentSkipListSet", new java.util.concurrent.ConcurrentSkipListSet<>(list));
        TreeSet<String> byLength = new TreeSet<>(Comparator.comparing(String::length));
        System.out.println("sorted by: " + treeSet.spliterator().getComparator() + " " + (byLength.spliterator().getComparator() == byLength.comparator()) + " "
                + tree.entrySet().spliterator().getComparator().compare(Map.entry("a", 9), Map.entry("b", 0)) + " "
                + skip.descendingKeySet().spliterator().getComparator().compare("a", "b"));
        fails("descendingSet getComparator", () -> treeSet.descendingSet().spliterator().getComparator());
    }
}
