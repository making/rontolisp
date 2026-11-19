import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Split2 {
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

    static String size(Spliterator<?> s) {
        return (s.estimateSize() == Long.MAX_VALUE ? "max" : Long.toString(s.estimateSize())) + "/" + s.getExactSizeIfKnown();
    }

    static String told(Spliterator<?> s) {
        String cmp;
        try {
            cmp = s.getComparator() == null ? " natural" : " comparator";
        }
        catch (IllegalStateException e) {
            cmp = "";
        }
        return marks(s.characteristics()) + " " + size(s) + cmp;
    }

    // the sizes as the elements are taken
    static void walk(String what, Spliterator<?> s) {
        StringBuilder line = new StringBuilder(what + ": " + told(s));
        List<Object> seen = new ArrayList<>();
        while (s.tryAdvance(seen::add)) {
            line.append(" -> ").append(size(s));
        }
        line.append(" end ").append(size(s)).append(" ").append(seen).append(" split ").append(s.trySplit());
        System.out.println(line);
    }

    static void ints(String what, Supplier<IntStream> source) {
        System.out.println(what + ": " + told(source.get().spliterator()));
        String[] names = { "map", "filter", "sorted", "distinct", "limit", "skip", "peek", "flatMap", "unordered", "boxed", "asLong", "asDouble", "mapToObj", "mapMulti", "takeWhile" };
        List<Function<IntStream, BaseStream<?, ?>>> stages = List.of(s -> s.map(x -> x), s -> s.filter(x -> true), s -> s.sorted(), s -> s.distinct(), s -> s.limit(2), s -> s.skip(1), s -> s.peek(x -> { }), s -> s.flatMap(x -> IntStream.of(x)), s -> s.unordered(), s -> s.boxed(), s -> s.asLongStream(), s -> s.asDoubleStream(), s -> s.mapToObj(x -> x), s -> s.mapMulti((x, to) -> to.accept(x)), s -> s.takeWhile(x -> true));
        for (int i = 0; i < names.length; i++) {
            System.out.println("  " + names[i] + ": " + told(stages.get(i).apply(source.get()).spliterator()));
        }
    }

    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>(List.of(3, 1, 2));
        walk("list", list.stream().spliterator());
        walk("list map", list.stream().map(x -> x).spliterator());
        walk("list filter", list.stream().filter(x -> x != 1).spliterator());
        walk("list limit", list.stream().limit(2).spliterator());
        walk("list skip", list.stream().skip(2).spliterator());
        walk("list skip 5", list.stream().skip(5).spliterator());
        walk("list sorted", list.stream().sorted().spliterator());
        walk("list flatMap", list.stream().flatMap(x -> Stream.of(x, x)).spliterator());
        walk("list filter limit", list.stream().filter(x -> true).limit(2).spliterator());
        walk("list map limit skip", list.stream().map(x -> x).limit(2).skip(1).spliterator());
        walk("set", new HashSet<>(list).stream().spliterator());
        walk("set map", new HashSet<>(list).stream().map(x -> x).spliterator());
        walk("of", Stream.of(3, 1, 2).spliterator());
        walk("of one", Stream.of(3).spliterator());
        walk("empty", Stream.empty().spliterator());
        walk("iterate limit", Stream.iterate(1, x -> x + 1).limit(2).spliterator());
        walk("iterate to", Stream.iterate(1, x -> x < 3, x -> x + 1).spliterator());
        walk("concat", Stream.concat(Stream.of(1), list.stream()).spliterator());
        walk("concat sets", Stream.concat(new HashSet<>(list).stream(), new HashSet<>(list).stream()).spliterator());
        walk("concat set list", Stream.concat(new HashSet<>(list).stream(), list.stream()).spliterator());
        walk("concat iterate", Stream.concat(Stream.of(1), Stream.iterate(1, x -> x < 3, x -> x + 1)).spliterator());
        walk("concat sorted", Stream.concat(IntStream.range(0, 2).boxed(), IntStream.range(0, 2).boxed()).spliterator());
        walk("builder", Stream.builder().add(1).add(2).build().spliterator());
        walk("lines", "a\nbc\n".lines().spliterator());
        walk("lines empty", "".lines().spliterator());
        walk("lines long", "abcdefgh\nabcdefgh".lines().spliterator());
        walk("chars", "abc".chars().spliterator());
        walk("codePoints", "abc".codePoints().spliterator());
        walk("range", IntStream.range(0, 3).spliterator());
        walk("rangeClosed", IntStream.rangeClosed(0, 3).spliterator());
        walk("long range", LongStream.range(0, 3).spliterator());
        walk("ints of", IntStream.of(3, 1).spliterator());
        walk("ints of one", IntStream.of(3).spliterator());
        walk("ints empty", IntStream.empty().spliterator());
        walk("doubles of", DoubleStream.of(3, 1).spliterator());
        walk("arrays int", Arrays.stream(new int[] { 1, 2, 3 }, 1, 3).spliterator());
        walk("int iterate", IntStream.iterate(1, x -> x < 3, x -> x + 1).spliterator());
        walk("int generate limit", IntStream.generate(() -> 1).limit(2).spliterator());
        walk("int builder", IntStream.builder().add(1).build().spliterator());
        walk("int concat", IntStream.concat(IntStream.of(1), IntStream.range(0, 2)).spliterator());
        walk("optional", Optional.of(1).stream().spliterator());
        walk("optional int", OptionalInt.of(1).stream().spliterator());
        walk("splitAsStream", java.util.regex.Pattern.compile(",").splitAsStream("a,b").spliterator());
        walk("mapToObj", IntStream.range(0, 3).mapToObj(x -> "s" + x).spliterator());
        walk("sorted c", list.stream().sorted(Comparator.reverseOrder()).spliterator());
        walk("supplied", StreamSupport.stream(() -> list.spliterator(), Spliterator.SIZED, false).spliterator());
        walk("supplied map", StreamSupport.stream(() -> list.spliterator(), Spliterator.SIZED | Spliterator.ORDERED | Spliterator.NONNULL, false).map(x -> x).spliterator());
        walk("supplied unsized map", StreamSupport.stream(() -> list.spliterator(), Spliterator.ORDERED, false).map(x -> x).spliterator());
        walk("given unsized", StreamSupport.stream(Spliterators.spliteratorUnknownSize(list.iterator(), Spliterator.ORDERED), false).map(x -> x).spliterator());
        ints("range", () -> IntStream.range(0, 5));
        ints("ints of", () -> IntStream.of(3, 1, 2));
        ints("chars", () -> "abc".chars());
        ints("int iterate", () -> IntStream.iterate(1, x -> x + 1));
        ints("int generate", () -> IntStream.generate(() -> 1));
        ints("mapToInt", () -> list.stream().mapToInt(x -> x));
        // is a stream's spliterator the source's own, and what happens after
        Spliterator<Integer> own = list.spliterator();
        System.out.println("own: " + (StreamSupport.stream(own, false).spliterator() == own) + " " + (StreamSupport.stream(own, false).parallel().onClose(() -> { }).spliterator() == own));
        Stream<Integer> used = list.stream();
        used.spliterator();
        try {
            used.count();
        }
        catch (IllegalStateException e) {
            System.out.println("used: " + e.getMessage());
        }
        Spliterator<Integer> lazy = list.stream().peek(x -> System.out.println("peek " + x)).spliterator();
        System.out.println("made");
        lazy.tryAdvance(x -> System.out.println("got " + x));
        lazy.forEachRemaining(x -> System.out.println("rest " + x));
        Spliterator.OfInt prim = IntStream.range(0, 3).map(x -> x * 2).spliterator();
        prim.tryAdvance((IntConsumer) x -> System.out.println("int " + x));
        prim.tryAdvance((Consumer<Integer>) x -> System.out.println("boxed " + x));
        prim.forEachRemaining((IntConsumer) x -> System.out.println("rest " + x));
        try {
            list.stream().spliterator().tryAdvance(null);
        }
        catch (NullPointerException e) {
            System.out.println("null action");
        }
        try {
            list.stream().map(x -> x).spliterator().tryAdvance(null);
        }
        catch (NullPointerException e) {
            System.out.println("null action staged");
        }
        Spliterator<Integer> closed = list.stream().onClose(() -> System.out.println("closed")).map(x -> x).spliterator();
        closed.forEachRemaining(x -> { });
        System.out.println("walked");
        // sorted and distinct over what is so already
        IntStream.range(0, 2).peek(x -> System.out.println("a" + x)).sorted().forEach(x -> System.out.println("b" + x));
        IntStream.range(0, 2).boxed().peek(x -> System.out.println("a" + x)).sorted().forEach(x -> System.out.println("b" + x));
        list.stream().sorted().peek(x -> System.out.println("a" + x)).sorted().forEach(x -> System.out.println("b" + x));
        new HashSet<>(list).stream().distinct().spliterator();
        Stream<Integer> s = new HashSet<>(list).stream();
        Stream<Integer> u = s.unordered();
        System.out.println("unordered same: " + (s == u));
        Stream<Integer> o = list.stream();
        System.out.println("unordered ordered same: " + (o == o.unordered()));
    }
}
