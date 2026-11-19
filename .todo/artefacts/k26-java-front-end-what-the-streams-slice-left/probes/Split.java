import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Split {
    static String marks(int c) {
        StringBuilder s = new StringBuilder();
        String[] names = { "ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED" };
        int[] bits = { Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED };
        for (int i = 0; i < bits.length; i++) {
            if ((c & bits[i]) != 0) {
                s.append(s.isEmpty() ? "" : "|").append(names[i]);
                c &= ~bits[i];
            }
        }
        if (c != 0) s.append("+").append(Integer.toHexString(c));
        return s.isEmpty() ? "none" : s.toString();
    }

    static String told(Spliterator<?> s) {
        String size = s.estimateSize() == Long.MAX_VALUE ? "max" : Long.toString(s.estimateSize());
        String cmp;
        try {
            cmp = s.getComparator() == null ? " natural" : " comparator";
        }
        catch (IllegalStateException e) {
            cmp = "";
        }
        return marks(s.characteristics()) + " " + size + "/" + s.getExactSizeIfKnown() + cmp;
    }

    static void row(String what, Supplier<Stream<Integer>> source) {
        System.out.println(what + ": " + told(source.get().spliterator()));
        String[] names = { "map", "filter", "sorted", "sorted(c)", "distinct", "limit", "skip", "peek", "flatMap", "unordered", "takeWhile", "dropWhile", "mapToInt", "mapToObj", "boxed", "parallel", "onClose", "mapMulti", "gather" };
        List<Function<Stream<Integer>, BaseStream<?, ?>>> stages = List.of(s -> s.map(x -> x), s -> s.filter(x -> true), s -> s.sorted(), s -> s.sorted(Comparator.reverseOrder()), s -> s.distinct(), s -> s.limit(2), s -> s.skip(1), s -> s.peek(x -> { }), s -> s.flatMap(x -> Stream.of(x)), s -> s.unordered(), s -> s.takeWhile(x -> true), s -> s.dropWhile(x -> false), s -> s.mapToInt(x -> x), s -> s.mapToInt(x -> x).mapToObj(x -> x), s -> s.mapToInt(x -> x).boxed(), s -> s.parallel(), s -> s.onClose(() -> { }), s -> s.mapMulti((x, to) -> to.accept(x)), s -> s.gather(Gatherers.scan(() -> 0, (a, x) -> x)));
        for (int i = 0; i < names.length; i++) {
            System.out.println("  " + names[i] + ": " + told(stages.get(i).apply(source.get()).spliterator()));
        }
    }

    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>(List.of(3, 1, 2));
        row("ArrayList", () -> list.stream());
        row("List.of", () -> List.of(3, 1, 2).stream());
        row("HashSet", () -> new HashSet<>(list).stream());
        row("LinkedHashSet", () -> new LinkedHashSet<>(list).stream());
        row("LinkedList", () -> new LinkedList<>(list).stream());
        row("ArrayDeque", () -> new ArrayDeque<>(list).stream());
        row("keySet", () -> new HashMap<>(Map.of(1, 2, 3, 4)).keySet().stream());
        row("values", () -> new HashMap<>(Map.of(1, 2, 3, 4)).values().stream());
        row("Arrays.asList", () -> Arrays.asList(3, 1, 2).stream());
        row("Arrays.stream", () -> Arrays.stream(new Integer[] { 3, 1, 2 }));
        row("Stream.of", () -> Stream.of(3, 1, 2));
        row("Stream.of one", () -> Stream.of(3));
        row("empty", () -> Stream.empty());
        row("ofNullable", () -> Stream.ofNullable(3));
        row("builder", () -> Stream.<Integer>builder().add(3).add(1).build());
        row("iterate", () -> Stream.iterate(1, x -> x + 1));
        row("iterate to", () -> Stream.iterate(1, x -> x < 4, x -> x + 1));
        row("generate", () -> Stream.generate(() -> 1));
        row("concat", () -> Stream.concat(Stream.of(1, 2), list.stream()));
        row("range boxed", () -> IntStream.range(0, 5).boxed());
        row("chars boxed", () -> "abc".chars().boxed());
        row("Optional", () -> Optional.of(1).stream());
        row("StreamSupport", () -> StreamSupport.stream(Spliterators.spliterator(list.iterator(), 3, Spliterator.NONNULL), false));
        row("StreamSupport supplier", () -> StreamSupport.stream(() -> list.spliterator(), Spliterator.SORTED | Spliterator.SIZED, false));
        row("lines", () -> "3\n1".lines().map(Integer::valueOf));
        row("CopyOnWrite", () -> new CopyOnWriteArrayList<>(list).stream());
        row("ConcurrentLinkedQueue", () -> new ConcurrentLinkedQueue<>(list).stream());
        row("sorted distinct", () -> list.stream().sorted().distinct());
        row("distinct sorted", () -> list.stream().distinct().sorted());
    }
}
