import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Collecting {
    record Person(String name, int age, String city, double pay) {
    }

    enum Size {
        SMALL, LARGE
    }

    static void fails(String what, Supplier<Object> r) {
        try {
            System.out.println(what + ": " + r.get());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e);
        }
    }

    static void show(Object o) {
        System.out.println(o);
    }

    static List<Person> people() {
        return List.of(new Person("Ann", 31, "Oslo", 410.5), new Person("Bob", 25, "Rome", 300.25),
                new Person("Cy", 40, "Oslo", 520.0), new Person("Di", 25, "Lima", 300.25),
                new Person("Ed", 58, "Rome", 99.9), new Person("Flo", 31, "Oslo", 0.1));
    }

    public static void main(String[] args) {
        List<String> words = List.of("apple", "fig", "kiwi", "plum", "pear", "banana", "fig");
        List<Person> people = people();
        // the lists and sets
        List<String> list = words.stream().collect(Collectors.toList());
        list.add("more");
        System.out.println(list);
        Set<String> set = words.stream().collect(Collectors.toSet());
        System.out.println(set + " " + set.size());
        show(words.stream().collect(Collectors.toCollection(ArrayList::new)));
        show(words.stream().collect(Collectors.toCollection(HashSet::new)));
        System.out.println(words.stream().collect(Collectors.toCollection(() -> new ArrayDeque<>())).peekLast());
        ArrayList<Integer> lengths = words.stream().map(String::length).collect(Collectors.toCollection(ArrayList::new));
        System.out.println(lengths);
        System.out.println(words.stream().collect(Collectors.toUnmodifiableList()));
        System.out.println(words.stream().limit(2).collect(Collectors.toUnmodifiableSet()).size());
        System.out.println(words.stream().toList());
        // joining
        System.out.println(words.stream().collect(Collectors.joining()));
        System.out.println(words.stream().collect(Collectors.joining(", ")));
        System.out.println(words.stream().collect(Collectors.joining(", ", "{", "}")));
        System.out.println(Stream.<String>empty().collect(Collectors.joining(", ", "{", "}")));
        System.out.println(Stream.<String>empty().collect(Collectors.joining(", ")).isEmpty());
        System.out.println(Stream.of("a", null, "b").collect(Collectors.joining("-")));
        System.out.println(Stream.of(new StringBuilder("sb"), "s").collect(Collectors.joining("+")));
        System.out.println(people.stream().map(Person::name).collect(Collectors.joining("/")));
        System.out.println(IntStream.range(0, 5).mapToObj(Integer::toString).collect(Collectors.joining(",")));
        System.out.println(IntStream.range(0, 5).mapToObj(i -> "" + i * i).collect(Collectors.joining(" ")));
        System.out.println(words.stream().map(w -> w.substring(0, 1)).collect(Collectors.joining()));
        // counting, summing, averaging
        System.out.println(words.stream().collect(Collectors.counting()));
        long counted = words.stream().collect(Collectors.counting());
        System.out.println(counted + 1);
        System.out.println(people.stream().collect(Collectors.summingInt(Person::age)));
        int ages = people.stream().collect(Collectors.summingInt(p -> p.age()));
        System.out.println(ages);
        System.out.println(people.stream().collect(Collectors.summingLong(p -> (long) p.age() << 33)));
        System.out.println(people.stream().collect(Collectors.summingDouble(Person::pay)));
        System.out.println(people.stream().collect(Collectors.averagingInt(Person::age)));
        System.out.println(people.stream().collect(Collectors.averagingLong(Person::age)));
        System.out.println(people.stream().collect(Collectors.averagingDouble(Person::pay)));
        System.out.println(Stream.<Person>empty().collect(Collectors.averagingInt(Person::age)) + " "
                + Stream.<Person>empty().collect(Collectors.averagingDouble(Person::pay)) + " "
                + Stream.<Person>empty().collect(Collectors.summingInt(Person::age)) + " "
                + Stream.<Person>empty().collect(Collectors.counting()));
        System.out.println(Stream.of(Integer.MAX_VALUE, 1).collect(Collectors.summingInt(x -> x)));
        System.out.println(Stream.of(Integer.MAX_VALUE, 1).collect(Collectors.averagingInt(x -> x)));
        System.out.println(Stream.of(Long.MAX_VALUE, 1L).collect(Collectors.averagingLong(x -> x)));
        // min, max, reducing
        System.out.println(people.stream().collect(Collectors.minBy(Comparator.comparingInt(Person::age))));
        System.out.println(people.stream().collect(Collectors.maxBy(Comparator.comparingInt(Person::age))));
        System.out.println(people.stream().collect(Collectors.maxBy(Comparator.comparing(Person::city))));
        System.out.println(people.stream().collect(Collectors.minBy(Comparator.comparing(Person::city).reversed())));
        System.out.println(Stream.<Person>empty().collect(Collectors.minBy(Comparator.comparingInt(Person::age))));
        System.out.println(people.stream().min(Comparator.comparingInt(Person::age)).get().name() + " "
                + people.stream().max(Comparator.comparingInt(Person::age)).get().name() + " "
                + people.stream().max(Comparator.comparing(Person::city)).get().name() + " "
                + people.stream().min(Comparator.comparing(Person::city).reversed()).get().name());
        System.out.println(words.stream().collect(Collectors.reducing("", (a, b) -> a + b.charAt(0))));
        System.out.println(words.stream().collect(Collectors.reducing((a, b) -> a.length() >= b.length() ? a : b)));
        System.out.println(words.stream().collect(Collectors.reducing(0, String::length, Integer::sum)));
        System.out.println(Stream.<String>empty().collect(Collectors.reducing((a, b) -> a)));
        // grouping
        System.out.println(words.stream().collect(Collectors.groupingBy(String::length)));
        System.out.println(words.stream().collect(Collectors.groupingBy(w -> w.charAt(0))));
        System.out.println(words.stream().collect(Collectors.groupingBy(String::length, Collectors.counting())));
        System.out.println(words.stream().collect(Collectors.groupingBy(w -> w.length(), Collectors.toSet())));
        System.out.println(words.stream().collect(Collectors.groupingBy(String::length, Collectors.joining("|"))));
        System.out.println(words.stream().collect(Collectors.groupingBy(String::length, Collectors.mapping(w -> w.charAt(0), Collectors.toList()))));
        show(words.stream().collect(Collectors.groupingBy(String::length, HashMap::new, Collectors.toList())));
        Map<String, List<Person>> byCity = people.stream().collect(Collectors.groupingBy(Person::city));
        System.out.println(byCity.get("Oslo").size() + " " + byCity.keySet());
        Map<String, Long> cityCount = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.counting()));
        System.out.println(cityCount);
        Map<String, Double> cityPay = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.averagingDouble(Person::pay)));
        System.out.println(cityPay);
        Map<String, Integer> cityAges = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.summingInt(Person::age)));
        System.out.println(cityAges);
        Map<String, Optional<Person>> oldest = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.maxBy(Comparator.comparingInt(Person::age))));
        System.out.println(oldest.get("Rome").map(Person::name).orElse("none"));
        Map<String, List<String>> names = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.mapping(Person::name, Collectors.toList())));
        System.out.println(names);
        Map<String, String> joined = people.stream().collect(Collectors.groupingBy(p -> p.city(), Collectors.mapping(p -> p.name(), Collectors.joining("+"))));
        System.out.println(joined);
        Map<Integer, Map<String, List<String>>> nested = people.stream().collect(Collectors.groupingBy(Person::age, Collectors.groupingBy(Person::city, Collectors.mapping(Person::name, Collectors.toList()))));
        System.out.println(nested);
        Map<String, Set<Integer>> ageSets = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.mapping(Person::age, Collectors.toSet())));
        System.out.println(ageSets);
        Map<String, IntSummaryStatistics> stats = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.summarizingInt(Person::age)));
        System.out.println(stats);
        Map<Boolean, Long> parity = IntStream.rangeClosed(1, 9).boxed().collect(Collectors.groupingBy(i -> i % 2 == 0, Collectors.counting()));
        System.out.println(parity);
        Map<Size, List<String>> sized = words.stream().collect(Collectors.groupingBy(w -> w.length() > 4 ? Size.LARGE : Size.SMALL));
        System.out.println(sized.get(Size.LARGE) + " " + sized.get(Size.SMALL));
        var grouped = people.stream().collect(Collectors.groupingBy(Person::city, Collectors.collectingAndThen(Collectors.toList(), List::size)));
        System.out.println(grouped);
        System.out.println(people.stream().collect(Collectors.groupingBy(Person::city, Collectors.filtering(p -> p.age() > 30, Collectors.counting()))));
        System.out.println(people.stream().collect(Collectors.groupingBy(Person::city, Collectors.flatMapping(p -> p.name().chars().boxed(), Collectors.toList()))));
        System.out.println(people.stream().collect(Collectors.groupingBy(Person::city, Collectors.reducing(0, Person::age, Integer::sum))));
        System.out.println(people.stream().collect(Collectors.groupingBy(Person::city, Collectors.teeing(Collectors.counting(), Collectors.summingInt(Person::age), (n, s) -> n + ":" + s))));
        // the order of a map's keys that share a bucket
        System.out.println(Stream.of(1, 17, 33, 2, 18, 49).collect(Collectors.groupingBy(i -> i, Collectors.counting())));
        System.out.println(Stream.of(17, 1, 33, 18, 2, 49, 1).collect(Collectors.groupingBy(i -> i)));
        System.out.println(Stream.of(1, 17, 33, 2, 18, 49).collect(Collectors.toMap(i -> i, i -> i * 2)));
        System.out.println(Stream.of(17, 1, 33, 18, 2, 49).collect(Collectors.toMap(i -> i, i -> i * 2, Integer::sum)));
        System.out.println(Stream.of(17, 1, 33, 18, 2, 49, 17).collect(Collectors.toMap(i -> i, i -> i * 2, Integer::sum)));
        System.out.println(Stream.of(17, 1, 33, 18, 2, 49).collect(Collectors.toSet()));
        show(Stream.of(17, 1, 33, 18, 2, 49).collect(Collectors.toMap(i -> i, i -> "v" + i, (a, b) -> a, HashMap::new)));
        show(Stream.of(17, 1, 33, 18, 2, 49).collect(Collectors.groupingBy(i -> i, HashMap::new, Collectors.counting())));
        System.out.println(Stream.of(17, 1, 33, 18, 2, 49).distinct().collect(Collectors.toList()));
        fails("group null key", () -> Stream.of("a", "b").collect(Collectors.groupingBy(s -> s.equals("b") ? null : s)));
        // partitioning
        Map<Boolean, List<String>> parts = words.stream().collect(Collectors.partitioningBy(w -> w.length() > 4));
        System.out.println(parts + " " + parts.get(true) + " " + parts.get(false) + " " + parts.size() + " " + parts.keySet() + " " + parts.values());
        System.out.println(words.stream().collect(Collectors.partitioningBy(w -> w.length() > 4, Collectors.counting())));
        System.out.println(Stream.<String>empty().collect(Collectors.partitioningBy(w -> w.length() > 4)));
        System.out.println(parts.containsKey(true) + " " + parts.containsKey("x") + " " + parts.get("x") + " " + parts.entrySet().iterator().next().getKey());
        fails("partition put", () -> parts.put(true, List.of()));
        fails("partition remove", () -> parts.remove(true));
        fails("partition list add", () -> parts.get(true).add("x"));
        parts.forEach((k, v) -> System.out.println(k + " -> " + v));
        Map<Boolean, Map<Integer, Long>> deep = words.stream().collect(Collectors.partitioningBy(w -> w.contains("a"), Collectors.groupingBy(String::length, Collectors.counting())));
        System.out.println(deep);
        // toMap
        Map<String, Integer> lens = words.stream().distinct().collect(Collectors.toMap(w -> w, String::length));
        System.out.println(lens);
        Map<String, Person> byName = people.stream().collect(Collectors.toMap(Person::name, Function.identity()));
        System.out.println(byName.get("Cy").city());
        Map<Integer, String> merged = people.stream().collect(Collectors.toMap(Person::age, Person::name, (a, b) -> a + "&" + b));
        System.out.println(merged);
        fails("toMap twice", () -> words.stream().collect(Collectors.toMap(w -> w, String::length)));
        fails("toMap twice people", () -> people.stream().collect(Collectors.toMap(Person::age, Person::name)));
        fails("toMap null value", () -> Stream.of("a", "b").collect(Collectors.toMap(w -> w, w -> w.equals("b") ? null : w)));
        fails("toMap null key", () -> Stream.of("a", "b").collect(Collectors.toMap(w -> w.equals("b") ? null : w, w -> w)));
        fails("toMap merge null value", () -> Stream.of("a", "b").collect(Collectors.toMap(w -> w, w -> w.equals("b") ? null : w, (a, b) -> a)));
        fails("toMap merge to null", () -> Stream.of("a", "a", "b").collect(Collectors.toMap(w -> w, w -> w, (a, b) -> null)));
        fails("toUnmodifiableMap", () -> Stream.of("a").collect(Collectors.toUnmodifiableMap(w -> w, w -> 1)));
        fails("toUnmodifiableMap put", () -> Stream.of("a").collect(Collectors.toUnmodifiableMap(w -> w, w -> 1)).put("b", 2));
        fails("toUnmodifiableMap merge", () -> Stream.of("a", "a").collect(Collectors.toUnmodifiableMap(w -> w, w -> 1, Integer::sum)));
        fails("toUnmodifiableMap twice", () -> Stream.of("a", "a").collect(Collectors.toUnmodifiableMap(w -> w, w -> 1)));
        fails("toUnmodifiableSet null", () -> Stream.of("a", null).collect(Collectors.toUnmodifiableSet()));
        fails("toUnmodifiableSet add", () -> Stream.of("a").collect(Collectors.toUnmodifiableSet()).add("b"));
        fails("toSet null", () -> Stream.of("a", null).collect(Collectors.toSet()).size());
        fails("toList null", () -> Stream.of("a", null).collect(Collectors.toList()));
        // summarizing, teeing, collectingAndThen
        System.out.println(people.stream().collect(Collectors.summarizingInt(Person::age)));
        System.out.println(people.stream().collect(Collectors.summarizingLong(Person::age)));
        System.out.println(people.stream().collect(Collectors.summarizingDouble(Person::pay)));
        DoubleSummaryStatistics pay = people.stream().collect(Collectors.summarizingDouble(Person::pay));
        System.out.println(pay.getMin() + " " + pay.getMax() + " " + pay.getAverage() + " " + pay.getSum() + " " + pay.getCount());
        show(people.stream().collect(Collectors.teeing(Collectors.minBy(Comparator.comparingInt(Person::age)), Collectors.maxBy(Comparator.comparingInt(Person::age)), (a, b) -> a.get().name() + ".." + b.get().name())));
        double mean = words.stream().collect(Collectors.teeing(Collectors.summingInt(String::length), Collectors.counting(), (s, n) -> (double) s / n));
        System.out.println(mean);
        List<String> frozen = words.stream().collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
        fails("frozen", () -> frozen.add("x"));
        show(words.stream().collect(Collectors.collectingAndThen(Collectors.toSet(), Set::size)));
        System.out.println(words.stream().collect(Collectors.collectingAndThen(Collectors.joining(), String::length)));
        // a collector of the program's own
        Collector<String, StringBuilder, String> initials = Collector.of(StringBuilder::new, (sb, s) -> sb.append(s.charAt(0)), StringBuilder::append, StringBuilder::toString);
        System.out.println(words.stream().collect(initials) + " " + initials.characteristics());
        Collector<String, List<String>, List<String>> reversed = Collector.of(ArrayList::new, (l, s) -> l.add(0, s), (a, b) -> {
            b.addAll(a);
            return b;
        });
        System.out.println(words.stream().collect(reversed) + " " + reversed.characteristics());
        Collector<String, ?, Integer> total = Collector.of(() -> new int[1], (a, s) -> a[0] += s.length(), (a, b) -> a, a -> a[0], Collector.Characteristics.UNORDERED);
        System.out.println(words.stream().collect(total) + " " + total.characteristics());
        System.out.println(Collectors.toList().characteristics() + " " + Collectors.toSet().characteristics() + " " + Collectors.joining().characteristics() + " " + Collectors.counting().characteristics() + " " + Collectors.groupingBy(x -> x).characteristics() + " " + Collectors.toMap(x -> x, x -> x).characteristics() + " " + Collectors.partitioningBy(x -> true).characteristics() + " " + Collectors.toUnmodifiableSet().characteristics() + " " + Collectors.toCollection(ArrayList::new).characteristics() + " " + Collectors.summarizingInt(x -> 1).characteristics() + " " + Collectors.mapping(x -> x, Collectors.toList()).characteristics() + " " + Collectors.minBy(Comparator.<String>naturalOrder()).characteristics());
        // a collector's parts used by hand
        Collector<String, ?, List<String>> c = Collectors.toList();
        System.out.println(collected(c, "x", "y") + " " + collected(Collectors.joining("-"), "x", "y") + " " + collected(Collectors.counting(), "x", "y") + " " + collected(Collectors.groupingBy(String::length), "x", "yy", "z") + " " + collected(Collectors.partitioningBy(s -> s.equals("x")), "x", "y", "x") + " " + collected(Collectors.toMap(s -> s, s -> 1, Integer::sum), "x", "y", "x") + " " + collected(Collectors.summingDouble(s -> 0.1), "x", "y", "x") + " " + collected(Collectors.averagingDouble(s -> 0.1), "x", "y", "x") + " " + collected(Collectors.maxBy(Comparator.<String>naturalOrder()), "x", "y", "x") + " " + collected(Collectors.joining(",", "<", ">"), "x", "y", "z") + " " + collected(Collectors.teeing(Collectors.counting(), Collectors.joining(), (n, s) -> s + n), "x", "y", "z") + " " + collected(Collectors.summarizingDouble(s -> 0.1), "x", "y", "z"));
        // the three-argument collect and reduce
        show(words.stream().collect(ArrayList::new, ArrayList::add, ArrayList::addAll));
        System.out.println(words.stream().collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString());
        System.out.println(words.stream().collect(() -> new HashMap<String, Integer>(), (m, w) -> m.merge(w, 1, Integer::sum), Map::putAll));
        System.out.println(words.stream().reduce(0, (n, w) -> n + w.length(), Integer::sum));
        System.out.println(words.stream().reduce("", String::concat));
        System.out.println(words.stream().reduce((a, b) -> b + a).get());
        System.out.println(words.stream().map(String::length).reduce(Integer::max).get() + " " + words.stream().map(String::length).reduce(0, Integer::sum) + " " + words.stream().map(String::length).reduce(1, (a, b) -> a * b));
        show(IntStream.range(0, 4).collect(ArrayList::new, ArrayList::add, ArrayList::addAll));
        show(IntStream.range(0, 4).collect(StringBuilder::new, (sb, i) -> sb.append(i), StringBuilder::append));
    }

    // both halves collected apart and combined, as a parallel stream would
    @SafeVarargs
    static <T, A, R> R collected(Collector<T, A, R> collector, T... items) {
        A left = collector.supplier().get();
        A right = collector.supplier().get();
        for (int i = 0; i < items.length; i++) {
            collector.accumulator().accept(i < items.length / 2 ? left : right, items[i]);
        }
        return collector.finisher().apply(collector.combiner().apply(left, right));
    }
}
