import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Conmap {
    static void fails(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ran");
        }
        catch (NullPointerException e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + e.getMessage());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e);
        }
    }

    static String shown(Object map) {
        return map + " " + (map instanceof ConcurrentHashMap ? "chm" : map instanceof ConcurrentMap ? "concurrent" : "other");
    }

    public static void main(String[] args) {
        List<String> words = List.of("pear", "fig", "apple", "kiwi", "plum", "date", "lime", "peach");
        System.out.println(shown(words.stream().collect(Collectors.groupingByConcurrent(String::length))));
        System.out.println(shown(words.stream().collect(Collectors.groupingByConcurrent(String::length, Collectors.counting()))));
        System.out.println(shown(words.stream().collect(Collectors.groupingByConcurrent(w -> w.charAt(0), ConcurrentHashMap::new, Collectors.joining("+")))));
        System.out.println(shown(words.stream().collect(Collectors.groupingByConcurrent(String::length, Collectors.mapping(w -> w.charAt(0), Collectors.toList())))));
        System.out.println(shown(words.stream().collect(Collectors.toConcurrentMap(w -> w, String::length))));
        System.out.println(shown(words.stream().collect(Collectors.toConcurrentMap(String::length, w -> w, (a, b) -> a + "|" + b))));
        System.out.println(shown(words.stream().collect(Collectors.toConcurrentMap(String::length, w -> w, (a, b) -> b, ConcurrentHashMap::new))));
        System.out.println(Collectors.groupingByConcurrent(String::length).characteristics());
        System.out.println(Collectors.groupingByConcurrent(String::length, Collectors.counting()).characteristics());
        System.out.println(Collectors.groupingByConcurrent(String::length, Collectors.toSet()).characteristics());
        System.out.println(Collectors.groupingByConcurrent(String::length, ConcurrentHashMap::new, Collectors.toList()).characteristics());
        System.out.println(Collectors.groupingByConcurrent(String::length, Collectors.toConcurrentMap(w -> w, w -> w)).characteristics());
        System.out.println(Collectors.toConcurrentMap(w -> w, w -> w).characteristics());
        System.out.println(Collectors.toConcurrentMap(w -> w, w -> w, (a, b) -> a).characteristics());
        System.out.println(Collectors.toConcurrentMap(w -> w, w -> w, (a, b) -> a, ConcurrentHashMap::new).characteristics());
        System.out.println(Collectors.groupingBy(String::length, Collectors.groupingByConcurrent(String::length)).characteristics());
        System.out.println(Collectors.mapping(w -> w, Collectors.toConcurrentMap(w -> w, w -> w)).characteristics());
        System.out.println(Collectors.collectingAndThen(Collectors.toConcurrentMap(w -> w, w -> w), m -> m).characteristics());
        System.out.println(Collectors.teeing(Collectors.toConcurrentMap(w -> w, w -> w), Collectors.groupingByConcurrent(w -> w), (a, b) -> a).characteristics());
        System.out.println(Collectors.partitioningBy(w -> true, Collectors.groupingByConcurrent(w -> w)).characteristics());
        fails("duplicate", () -> words.stream().collect(Collectors.toConcurrentMap(String::length, w -> w)));
        fails("null key", () -> Stream.of("a", "b").collect(Collectors.groupingByConcurrent(w -> null)));
        fails("null key to", () -> Stream.of("a", "b").collect(Collectors.toConcurrentMap(w -> null, w -> w)));
        fails("null value to", () -> Stream.of("a", "b").collect(Collectors.toConcurrentMap(w -> w, w -> null)));
        fails("null value merge", () -> Stream.of("a", "b").collect(Collectors.toConcurrentMap(w -> w, w -> null, (a, b) -> a)));
        fails("null classifier", () -> Collectors.groupingByConcurrent(null));
        fails("null downstream", () -> Collectors.groupingByConcurrent(w -> w, null));
        fails("null factory", () -> Collectors.groupingByConcurrent(w -> w, null, Collectors.toList()));
        fails("null mapper", () -> Collectors.toConcurrentMap(null, w -> w));
        fails("null merge", () -> Collectors.toConcurrentMap(w -> w, w -> w, null));
        fails("null map factory", () -> Collectors.toConcurrentMap(w -> w, w -> w, (a, b) -> a, null));
        Collector<String, ?, ConcurrentMap<Integer, List<String>>> c = Collectors.groupingByConcurrent(String::length);
        System.out.println(by(c, words));
        ConcurrentMap<Integer, Long> counts = words.stream().collect(Collectors.groupingByConcurrent(String::length, Collectors.counting()));
        counts.merge(4, 10L, Long::sum);
        System.out.println(counts);
    }

    // a collector's parts used by hand, two halves combined
    static <T, A, R> R by(Collector<T, A, R> c, List<T> items) {
        A left = c.supplier().get();
        A right = c.supplier().get();
        for (int i = 0; i < items.size(); i++) {
            c.accumulator().accept(i % 2 == 0 ? left : right, items.get(i));
        }
        return c.finisher().apply(c.combiner().apply(left, right));
    }
}
