import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Marks {
    static void row(String what, Collector<?, ?, ?> c) {
        System.out.println(what + ": " + c.characteristics());
    }

    public static void main(String[] args) {
        Collector<String, ?, List<String>> list = Collectors.toList();
        Collector<String, ?, Set<String>> set = Collectors.toSet();
        Collector<String, ?, Long> counting = Collectors.counting();
        Collector<String, ?, Set<String>> uset = Collectors.toUnmodifiableSet();
        Collector<String, int[], int[]> own = Collector.of(() -> new int[1], (a, s) -> a[0]++, (a, b) -> a, Collector.Characteristics.CONCURRENT, Collector.Characteristics.UNORDERED);
        Collector<String, int[], Integer> own2 = Collector.of(() -> new int[1], (a, s) -> a[0]++, (a, b) -> a, a -> a[0], Collector.Characteristics.IDENTITY_FINISH, Collector.Characteristics.CONCURRENT);
        row("own", own);
        row("own2", own2);
        row("toList", list);
        row("toUnmodifiableList", Collectors.toUnmodifiableList());
        row("toSet", set);
        row("toUnmodifiableSet", uset);
        row("toCollection", Collectors.toCollection(ArrayList::new));
        row("joining", Collectors.joining());
        row("joining3", Collectors.joining(",", "a", "b"));
        row("counting", counting);
        row("minBy", Collectors.minBy(Comparator.<String>naturalOrder()));
        row("summingInt", Collectors.summingInt(x -> 1));
        row("summingLong", Collectors.summingLong(x -> 1));
        row("summingDouble", Collectors.summingDouble(x -> 1));
        row("averagingInt", Collectors.averagingInt(x -> 1));
        row("averagingDouble", Collectors.averagingDouble(x -> 1));
        row("reducing1", Collectors.reducing((a, b) -> a));
        row("reducing2", Collectors.reducing("", (a, b) -> a));
        row("reducing3", Collectors.reducing("", x -> "", (a, b) -> a));
        row("summarizingInt", Collectors.summarizingInt(x -> 1));
        row("summarizingLong", Collectors.summarizingLong(x -> 1));
        row("summarizingDouble", Collectors.summarizingDouble(x -> 1));
        row("toMap2", Collectors.toMap(x -> x, x -> x));
        row("toMap3", Collectors.toMap(x -> x, x -> x, (a, b) -> a));
        row("toMap4", Collectors.toMap(x -> x, x -> x, (a, b) -> a, HashMap::new));
        row("toUnmodifiableMap2", Collectors.toUnmodifiableMap(x -> x, x -> x));
        row("toUnmodifiableMap3", Collectors.toUnmodifiableMap(x -> x, x -> x, (a, b) -> a));
        for (Collector<String, ?, ?> down : List.of(list, set, counting, uset, own, own2)) {
            String d = down.characteristics().toString();
            row("mapping " + d, Collectors.mapping((String x) -> x, down));
            row("filtering " + d, Collectors.filtering(x -> true, down));
            row("flatMapping " + d, Collectors.flatMapping((String x) -> Stream.of(x), down));
            row("collectingAndThen " + d, Collectors.collectingAndThen(down, x -> x));
            row("groupingBy " + d, Collectors.groupingBy(x -> x, down));
            row("groupingBy3 " + d, Collectors.groupingBy(x -> x, HashMap::new, down));
            row("partitioningBy " + d, Collectors.partitioningBy(x -> true, down));
            row("teeing with toList " + d, Collectors.teeing(down, list, (a, b) -> a));
            row("teeing with toSet " + d, Collectors.teeing(down, set, (a, b) -> a));
            row("teeing with own " + d, Collectors.teeing(down, own, (a, b) -> a));
            row("teeing with counting " + d, Collectors.teeing(counting, down, (a, b) -> a));
        }
        row("groupingBy1", Collectors.groupingBy(x -> x));
        row("partitioningBy1", Collectors.partitioningBy(x -> true));
        Collector<String, ?, Optional<String>> r = Collectors.reducing((a, b) -> null);
        System.out.println(Stream.of("a", "b").collect(r));
        System.out.println(Stream.of("a", "b").collect(Collectors.minBy((a, b) -> 0)));
        try {
            System.out.println(new IntSummaryStatistics(-1, 0, 0, 0));
        }
        catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        try {
            System.out.println(new IntSummaryStatistics(1, 2, 1, 0));
        }
        catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        try {
            System.out.println(new DoubleSummaryStatistics(1, 1, 2, Double.NaN));
        }
        catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        try {
            System.out.println(new LongSummaryStatistics(1, 3, 2, 0));
        }
        catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        System.out.println(new IntSummaryStatistics(0, 5, 1, 9) + " " + new IntSummaryStatistics(2, 1, 5, 9) + " " + new DoubleSummaryStatistics(2, 1, 5, 9) + " " + new DoubleSummaryStatistics(2, Double.NaN, Double.NaN, Double.NaN) + " " + new LongSummaryStatistics(2, 1, 5, 9) + " " + new IntSummaryStatistics() + " " + new DoubleSummaryStatistics() + " " + new LongSummaryStatistics());
    }
}
