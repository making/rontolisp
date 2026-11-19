import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Numbers {
    static void show(Object o) {
        System.out.println(o);
    }

    static void fails(String what, Supplier<Object> r) {
        try {
            System.out.println(what + ": " + r.get());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e);
        }
    }

    public static void main(String[] args) {
        // ints
        show(IntStream.range(0, 5).sum() + " " + IntStream.rangeClosed(1, 5).sum() + " " + IntStream.range(5, 0).sum() + " " + IntStream.rangeClosed(3, 3).count());
        show(Arrays.toString(IntStream.range(0, 5).toArray()) + Arrays.toString(IntStream.rangeClosed(-2, 2).toArray()) + Arrays.toString(IntStream.empty().toArray()));
        show(Arrays.toString(IntStream.rangeClosed(Integer.MAX_VALUE - 2, Integer.MAX_VALUE).toArray()) + IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MIN_VALUE + 1).count() + " " + IntStream.range(Integer.MIN_VALUE, Integer.MAX_VALUE).count() + " " + IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).count() + " " + IntStream.rangeClosed(Integer.MIN_VALUE, Integer.MAX_VALUE).skip(5).limit(9).count());
        show(IntStream.of(3, 1, 2).sorted().boxed().collect(Collectors.toList()) + " " + IntStream.of(3, 1, 3, 2, 1).distinct().boxed().collect(Collectors.toList()));
        show(IntStream.of(3, 1, 2).map(x -> x * x).filter(x -> x > 1).boxed().collect(Collectors.toList()));
        show(IntStream.of(3, 1, 2).min() + " " + IntStream.of(3, 1, 2).max() + " " + IntStream.empty().min() + " " + IntStream.empty().max() + " " + IntStream.of(7).min().getAsInt());
        show(IntStream.of(3, 1, 2).average() + " " + IntStream.empty().average() + " " + IntStream.of(Integer.MAX_VALUE, Integer.MAX_VALUE).average() + " " + IntStream.of(1, 2).average().getAsDouble());
        show(IntStream.of(Integer.MAX_VALUE, 1).sum() + " " + IntStream.of(Integer.MAX_VALUE, 1).asLongStream().sum() + " " + IntStream.of(1, 2).asDoubleStream().sum());
        show(IntStream.of(3, 1, 2).summaryStatistics() + " " + IntStream.empty().summaryStatistics());
        IntSummaryStatistics st = IntStream.of(3, 1, 2).summaryStatistics();
        show(st.getMin() + " " + st.getMax() + " " + st.getAverage() + " " + st.getSum() + " " + st.getCount());
        st.accept(10);
        IntSummaryStatistics other = new IntSummaryStatistics();
        other.accept(-5);
        st.combine(other);
        show(st);
        show(IntStream.of(3, 1, 2).reduce(0, Integer::sum) + " " + IntStream.of(3, 1, 2).reduce((a, b) -> a * b) + " " + IntStream.empty().reduce(Integer::max) + " " + IntStream.of(3, 1, 2).reduce(Integer::max).getAsInt());
        show(IntStream.of(3, 1, 2).anyMatch(x -> x == 2) + " " + IntStream.of(3, 1, 2).allMatch(x -> x > 1) + " " + IntStream.of(3, 1, 2).noneMatch(x -> x > 5) + " " + IntStream.empty().allMatch(x -> false) + " " + IntStream.empty().anyMatch(x -> true) + " " + IntStream.empty().noneMatch(x -> true));
        show(IntStream.of(3, 1, 2).findFirst() + " " + IntStream.of(3, 1, 2).findAny() + " " + IntStream.empty().findFirst() + " " + IntStream.of(3, 1, 2).filter(x -> x < 3).findFirst().getAsInt());
        show(IntStream.of(3, 1, 2).mapToObj(x -> "n" + x).collect(Collectors.joining(",")) + " " + IntStream.of(3, 1, 2).mapToLong(x -> x * 10000000000L).sum() + " " + IntStream.of(3, 1, 2).mapToDouble(x -> x / 2.0).sum());
        show(IntStream.of(1, 2, 3).flatMap(x -> IntStream.range(0, x)).boxed().collect(Collectors.toList()) + " " + IntStream.of(1, 2, 3).flatMap(x -> x == 2 ? null : IntStream.of(x)).sum());
        show(IntStream.iterate(1, x -> x * 2).limit(10).sum() + " " + IntStream.iterate(1, x -> x < 100, x -> x * 3).boxed().collect(Collectors.toList()) + " " + IntStream.generate(() -> 7).limit(3).sum());
        show(IntStream.range(0, 10).skip(3).limit(4).boxed().collect(Collectors.toList()) + " " + IntStream.range(0, 10).takeWhile(x -> x < 4).sum() + " " + IntStream.range(0, 10).dropWhile(x -> x < 4).sum() + " " + IntStream.range(0, 10).limit(0).count() + " " + IntStream.range(0, 3).skip(5).count());
        show(IntStream.concat(IntStream.range(0, 3), IntStream.of(9, 8)).boxed().collect(Collectors.toList()) + " " + IntStream.builder().add(1).add(2).build().sum() + " " + IntStream.of(5).boxed().findFirst().get());
        StringBuilder seen = new StringBuilder();
        IntStream.range(0, 3).peek(x -> seen.append("p").append(x)).forEach(x -> seen.append("f").append(x));
        IntStream.of(2, 1).forEachOrdered(x -> seen.append("o").append(x));
        show(seen);
        show(IntStream.range(0, 4).collect(ArrayList::new, ArrayList::add, ArrayList::addAll) + " " + IntStream.of(1, 2).boxed().map(x -> x + 1).mapToInt(Integer::intValue).sum() + " " + IntStream.of(1, 2).boxed().mapToInt(x -> x).max().getAsInt());
        PrimitiveIterator.OfInt it = IntStream.range(0, 3).iterator();
        int total = 0;
        while (it.hasNext()) {
            total += it.nextInt();
        }
        Iterator<Integer> boxed = IntStream.range(0, 3).iterator();
        show(total + " " + boxed.next() + " " + boxed.hasNext());
        fails("it end", () -> it.nextInt());
        show(IntStream.range(0, 20).parallel().filter(x -> x % 3 == 0).sum() + " " + IntStream.range(0, 3).parallel().isParallel() + " " + IntStream.range(0, 3).sequential().isParallel());
        show("hello world".chars().filter(ch -> ch == 'o').count() + " " + "hello".chars().mapToObj(ch -> (char) ch).collect(Collectors.toList()) + " " + "hello".chars().map(Character::toUpperCase).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append) + " " + "a1b2".chars().filter(Character::isDigit).map(ch -> ch - '0').sum() + " " + "abc".codePoints().max().getAsInt());
        show("hello".chars().mapToObj(ch -> String.valueOf((char) ch)).collect(Collectors.joining("-")) + " " + "hello".chars().distinct().count() + " " + "hello".chars().sorted().mapToObj(ch -> "" + (char) ch).collect(Collectors.joining()) + " " + new StringBuilder("xyz").chars().sum());
        fails("limit -1", () -> IntStream.range(0, 3).limit(-1));
        fails("skip -2", () -> IntStream.range(0, 3).skip(-2));
        fails("twice", () -> {
            IntStream s = IntStream.range(0, 3);
            s.sum();
            return s.sum();
        });
        fails("getAsInt", () -> IntStream.empty().max().getAsInt());
        fails("getAsDouble", () -> IntStream.empty().average().getAsDouble());
        fails("orElseThrow", () -> IntStream.empty().findFirst().orElseThrow());
        OptionalInt oi = IntStream.of(4).findFirst();
        OptionalInt none = OptionalInt.empty();
        show(oi + " " + oi.isPresent() + " " + oi.isEmpty() + " " + oi.orElse(9) + " " + none.orElse(9) + " " + none.orElseGet(() -> 8) + " " + oi.equals(OptionalInt.of(4)) + " " + oi.equals(none) + " " + oi.hashCode() + " " + none.hashCode() + " " + none + " " + oi.stream().sum() + " " + none.stream().count());
        oi.ifPresent(x -> show("present " + x));
        none.ifPresentOrElse(x -> show("present " + x), () -> show("absent"));
        OptionalDouble od = DoubleStream.of(2.5).findFirst();
        OptionalDouble dnone = OptionalDouble.empty();
        show(od + " " + od.isPresent() + " " + od.orElse(9) + " " + dnone.orElse(9) + " " + dnone.orElseGet(() -> 8) + " " + od.equals(OptionalDouble.of(2.5)) + " " + od.hashCode() + " " + dnone.hashCode() + " " + dnone + " " + od.stream().sum() + " " + OptionalDouble.of(Double.NaN).equals(OptionalDouble.of(Double.NaN)) + " " + OptionalDouble.of(0.0).equals(OptionalDouble.of(-0.0)) + " " + od.getAsDouble());
        OptionalLong ol = LongStream.of(5).findFirst();
        show(ol.stream().sum() + " " + OptionalLong.empty().stream().count() + " " + Optional.of("x").stream().count() + " " + Optional.empty().stream().count() + " " + Stream.of(Optional.of(1), Optional.<Integer>empty(), Optional.of(3)).flatMap(Optional::stream).collect(Collectors.toList()));
        // longs
        show(LongStream.range(0, 5).sum() + " " + LongStream.rangeClosed(1, 20).reduce(1, (a, b) -> a * b) + " " + LongStream.range(5, 0).count() + " " + LongStream.rangeClosed(Long.MAX_VALUE - 1, Long.MAX_VALUE).boxed().collect(Collectors.toList()) + " " + LongStream.rangeClosed(Long.MIN_VALUE, Long.MIN_VALUE).sum());
        show(LongStream.of(3, 1, 2).sorted().boxed().collect(Collectors.toList()) + " " + LongStream.of(3, 1, 3).distinct().count() + " " + LongStream.of(3, 1, 2).max() + " " + LongStream.of(3, 1, 2).min().getAsLong() + " " + LongStream.of(3, 1, 2).average() + " " + LongStream.empty().average() + " " + LongStream.of(Long.MAX_VALUE, Long.MAX_VALUE).average() + " " + LongStream.of(Long.MAX_VALUE, 1).sum());
        show(LongStream.of(3, 1, 2).summaryStatistics() + " " + LongStream.empty().summaryStatistics() + " " + LongStream.of(3, 1, 2).mapToInt(x -> (int) x).sum() + " " + LongStream.of(3, 1, 2).mapToObj(Long::toString).collect(Collectors.joining()) + " " + LongStream.of(3, 1, 2).mapToDouble(x -> x * 0.5).sum() + " " + LongStream.of(3, 1, 2).asDoubleStream().max());
        show(Arrays.toString(LongStream.of(3, 1, 2).map(x -> x << 40).toArray()) + " " + LongStream.iterate(1, x -> x * 3).limit(5).boxed().collect(Collectors.toList()) + " " + LongStream.iterate(1, x -> x < 50, x -> x * 3).count() + " " + LongStream.generate(() -> 2L).limit(3).sum() + " " + LongStream.concat(LongStream.of(1), LongStream.of(2)).sum() + " " + LongStream.of(1, 2, 3).flatMap(x -> LongStream.rangeClosed(1, x)).sum());
        show(LongStream.of(3, 1, 2).filter(x -> x > 1).findFirst() + " " + LongStream.of(3, 1, 2).anyMatch(x -> x == 1) + " " + LongStream.of(3, 1, 2).skip(1).limit(1).boxed().collect(Collectors.toList()) + " " + LongStream.range(0, 9).takeWhile(x -> x < 3).count() + " " + LongStream.range(0, 9).dropWhile(x -> x < 3).count() + " " + LongStream.builder().add(4).build().sum() + " " + LongStream.range(0, 3).collect(ArrayList::new, ArrayList::add, ArrayList::addAll) + " " + LongStream.range(0, 3).iterator().nextLong() + " " + LongStream.of(3, 1, 2).reduce(Long::max).getAsLong());
        LongSummaryStatistics ls = new LongSummaryStatistics();
        ls.accept(5);
        ls.accept(7L);
        show(ls + " " + ls.getAverage() + " " + ls.getMin() + " " + ls.getMax());
        show(Stream.of("a", "bb").mapToLong(String::length).sum() + " " + Stream.of("a", "bb").mapToDouble(String::length).average().getAsDouble() + " " + Stream.of("ab", "c").flatMapToInt(String::chars).sum() + " " + Stream.of(1, 2).flatMapToLong(x -> LongStream.of(x, x)).sum() + " " + Stream.of(1, 2).flatMapToDouble(x -> DoubleStream.of(x, 0.5)).sum());
        // doubles
        show(DoubleStream.of(0.1, 0.2, 0.3).sum() + " " + DoubleStream.of(0.1, 0.2, 0.3).average() + " " + DoubleStream.empty().sum() + " " + DoubleStream.empty().average() + " " + DoubleStream.of(1e308, 1e308, -1e308).sum() + " " + DoubleStream.of(Double.POSITIVE_INFINITY, 1).sum() + " " + DoubleStream.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).sum() + " " + DoubleStream.of(Double.NaN, 1).sum() + " " + DoubleStream.of(-0.0, -0.0).sum());
        show(DoubleStream.of(3, 1, 2).min() + " " + DoubleStream.of(3, 1, 2).max() + " " + DoubleStream.of(-0.0, 0.0).min() + " " + DoubleStream.of(0.0, -0.0).max() + " " + DoubleStream.of(1, Double.NaN).max() + " " + DoubleStream.of(1, Double.NaN).min() + " " + DoubleStream.empty().max());
        show(Arrays.toString(DoubleStream.of(3, -0.0, Double.NaN, 0.0, 1, Double.NEGATIVE_INFINITY).sorted().toArray()) + " " + Arrays.toString(DoubleStream.of(0.0, -0.0, Double.NaN, Double.NaN, 0.0, 1, 1).distinct().toArray()));
        show(DoubleStream.of(3, 1, 2).summaryStatistics() + " " + DoubleStream.empty().summaryStatistics() + " " + DoubleStream.of(1, Double.NaN).summaryStatistics() + " " + DoubleStream.of(1, Double.POSITIVE_INFINITY, 2).summaryStatistics());
        show(DoubleStream.of(3, 1, 2).map(x -> x / 4).boxed().collect(Collectors.toList()) + " " + DoubleStream.of(3.7, 1.2).mapToInt(x -> (int) x).sum() + " " + DoubleStream.of(3.7, 1.2).mapToLong(Math::round).sum() + " " + DoubleStream.of(3.7, 1.2).mapToObj(x -> "d" + x).collect(Collectors.joining()) + " " + DoubleStream.of(3, 1, 2).filter(x -> x > 1).count() + " " + DoubleStream.of(3, 1, 2).reduce(0, Double::sum) + " " + DoubleStream.of(3, 1, 2).reduce(Math::max));
        show(DoubleStream.iterate(1, x -> x / 2).limit(4).boxed().collect(Collectors.toList()) + " " + DoubleStream.iterate(1, x -> x > 0.2, x -> x / 2).count() + " " + DoubleStream.generate(() -> 0.5).limit(2).sum() + " " + DoubleStream.concat(DoubleStream.of(1), DoubleStream.of(2)).sum() + " " + DoubleStream.of(1, 2).flatMap(x -> DoubleStream.of(x, x)).sum() + " " + DoubleStream.builder().add(1.5).build().sum() + " " + DoubleStream.of(1, 2, 3).skip(1).limit(1).sum() + " " + DoubleStream.of(1, 2, 3).takeWhile(x -> x < 2).count() + " " + DoubleStream.of(1, 2, 3).dropWhile(x -> x < 2).count());
        show(DoubleStream.of(3, 1, 2).anyMatch(x -> x == 1) + " " + DoubleStream.of(3, 1, 2).allMatch(x -> x > 0) + " " + DoubleStream.of(3, 1, 2).noneMatch(x -> x > 0) + " " + DoubleStream.of(3, 1, 2).findFirst() + " " + DoubleStream.empty().findAny() + " " + DoubleStream.of(1, 2).collect(ArrayList::new, ArrayList::add, ArrayList::addAll) + " " + DoubleStream.of(1, 2).iterator().nextDouble() + " " + DoubleStream.of(1, 2).iterator().next());
        DoubleSummaryStatistics ds = new DoubleSummaryStatistics();
        DoubleSummaryStatistics ds2 = new DoubleSummaryStatistics();
        Random r = new Random(11);
        double[] all = new double[200];
        for (int i = 0; i < all.length; i++) {
            all[i] = (r.nextInt(2000) - 1000) * 0.01 * Math.pow(10, r.nextInt(12) - 6);
            (i < 77 ? ds : ds2).accept(all[i]);
        }
        show(DoubleStream.of(all).sum() + " " + DoubleStream.of(all).average().getAsDouble() + " " + ds.getSum() + " " + ds2.getSum());
        ds.combine(ds2);
        show(ds.getSum() + " " + ds.getAverage() + " " + ds.getMin() + " " + ds.getMax() + " " + ds.getCount() + " " + ds);
        show(Arrays.stream(all).boxed().collect(Collectors.summingDouble(x -> x)) + " " + Arrays.stream(all).boxed().collect(Collectors.averagingDouble(x -> x)) + " " + Arrays.stream(all, 10, 50).sum() + " " + Arrays.stream(all).map(Math::abs).max().getAsDouble() + " " + Arrays.stream(all).sorted().skip(100).findFirst().getAsDouble());
        // the arrays as sources
        int[] ints = { 5, 3, 8 };
        long[] longs = { 5, 3, 8 };
        String[] names = { "x", "y", "z" };
        show(Arrays.stream(ints).sum() + " " + Arrays.stream(ints, 1, 3).sum() + " " + Arrays.stream(longs).max().getAsLong() + " " + Arrays.stream(longs, 0, 1).sum() + " " + Arrays.stream(names).collect(Collectors.joining()) + " " + Arrays.stream(names, 1, 2).collect(Collectors.toList()) + " " + Stream.of(names).count() + " " + Arrays.asList(names).stream().map(String::toUpperCase).toList() + " " + Arrays.stream(ints).boxed().sorted(Comparator.reverseOrder()).toList() + " " + Arrays.stream(ints).map(x -> -x).sorted().map(x -> -x).boxed().toList());
        fails("2,1", () -> Arrays.stream(names, 2, 1));
        fails("-1,2", () -> Arrays.stream(names, -1, 2));
        fails("0,9", () -> Arrays.stream(ints, 0, 9));
        fails("4,4", () -> Arrays.stream(longs, 4, 4));
        show(Arrays.stream(names, 3, 3).count() + " " + Arrays.toString(Arrays.stream(names).toArray(String[]::new)) + " " + Arrays.toString(Stream.of("b", "a").sorted().toArray()) + " " + Stream.of("b", "a").toArray(String[]::new).length + " " + Arrays.toString(Stream.of(1, 2).toArray(Integer[]::new)) + " " + Arrays.toString(IntStream.range(0, 3).mapToObj(i -> new int[] { i, i * i }).map(a -> a[0] + a[1]).toArray()));
    }
}
