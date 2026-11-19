import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

public class Everyday {
    record Item(String name, String kind, int price, List<String> tags) {
    }

    record Pair<A, B>(A first, B second) {
    }

    interface Shape {
        double area();
    }

    record Circle(double r) implements Shape {
        public double area() {
            return 3 * r * r;
        }
    }

    record Square(double side) implements Shape {
        public double area() {
            return side * side;
        }
    }

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

    static boolean isPrime(int n) {
        return n > 1 && IntStream.rangeClosed(2, (int) Math.sqrt(n)).noneMatch(d -> n % d == 0);
    }

    static <T> List<T> evens(Collection<T> all, Predicate<? super T> keep) {
        return all.stream().filter(keep).collect(Collectors.toList());
    }

    static <T extends Comparable<T>> Optional<T> largest(Stream<T> all) {
        return all.max(Comparator.naturalOrder());
    }

    static Stream<String> letters(String word) {
        return word.chars().mapToObj(c -> String.valueOf((char) c));
    }

    public static void main(String[] args) throws IOException {
        List<Item> items = List.of(new Item("pen", "desk", 120, List.of("blue", "fine")),
                new Item("ink", "desk", 480, List.of("blue")), new Item("mug", "kitchen", 650, List.of()),
                new Item("pan", "kitchen", 2400, List.of("iron", "heavy")),
                new Item("cup", "kitchen", 300, List.of("fine")), new Item("pad", "desk", 250, List.of("heavy")));
        List<String> words = Arrays.asList("delta", "alpha", "charlie", "bravo", "echo", "alpha");
        List<Integer> numbers = List.of(5, 3, 8, 1, 9, 2, 8);
        // the chains a program writes
        show(items.stream().filter(i -> i.price() > 280).map(Item::name).collect(Collectors.toList()));
        show(items.stream().map(Item::name).map(String::toUpperCase).sorted().toList());
        show(items.stream().sorted(Comparator.comparing(Item::price)).map(Item::name).toList());
        show(items.stream().sorted(Comparator.comparing(Item::price).reversed()).map(Item::name).toList());
        show(items.stream().sorted(Comparator.comparing(Item::kind).thenComparing(Item::name)).map(Item::name).toList());
        show(items.stream().sorted(Comparator.comparing(Item::kind).thenComparing(Item::price, Comparator.reverseOrder())).map(Item::name).toList());
        show(items.stream().sorted(Comparator.comparingInt(i -> -i.price())).map(i -> i.name() + ":" + i.price()).collect(Collectors.joining(" ")));
        show(items.stream().sorted((a, b) -> b.name().compareTo(a.name())).map(Item::name).findFirst().orElse("none"));
        show(items.stream().mapToInt(Item::price).sum() + " " + items.stream().mapToInt(Item::price).max().getAsInt() + " " + items.stream().mapToInt(Item::price).average().orElse(0) + " " + items.stream().mapToDouble(i -> i.price() * 1.1).sum());
        show(items.stream().map(Item::price).reduce(0, Integer::sum) + " " + items.stream().map(Item::price).reduce(Integer::max).get() + " " + items.stream().count() + " " + items.stream().filter(i -> i.kind().equals("desk")).count());
        show(items.stream().anyMatch(i -> i.price() > 2000) + " " + items.stream().allMatch(i -> i.price() > 200) + " " + items.stream().noneMatch(i -> i.tags().isEmpty()));
        show(items.stream().filter(i -> i.price() > 9000).findFirst().isPresent() + " " + items.stream().filter(i -> i.price() > 500).findFirst().map(Item::name).orElse("?") + " " + items.stream().map(Item::name).filter(n -> n.startsWith("p")).findAny().get());
        show(items.stream().max(Comparator.comparingInt(Item::price)).get().name() + " " + items.stream().min(Comparator.comparing(Item::name)).get().name() + " " + items.stream().map(Item::name).max(String::compareTo).get() + " " + items.stream().map(Item::price).max(Integer::compare).get() + " " + items.stream().map(Item::price).min(Comparator.naturalOrder()).get());
        show(items.stream().flatMap(i -> i.tags().stream()).distinct().sorted().collect(Collectors.toList()));
        show(items.stream().flatMap(i -> i.tags().stream().map(t -> i.name() + "#" + t)).collect(Collectors.joining(",")));
        show(items.stream().map(Item::tags).flatMap(List::stream).collect(Collectors.groupingBy(t -> t, Collectors.counting())));
        show(items.stream().collect(Collectors.groupingBy(Item::kind, Collectors.summingInt(Item::price))));
        show(items.stream().collect(Collectors.groupingBy(Item::kind, Collectors.mapping(Item::name, Collectors.joining("/")))));
        show(items.stream().collect(Collectors.partitioningBy(i -> i.price() >= 300, Collectors.mapping(Item::name, Collectors.toList()))));
        show(items.stream().collect(Collectors.toMap(Item::name, Item::price)).get("pan"));
        Map<String, Integer> totals = new HashMap<>();
        items.stream().forEach(i -> totals.merge(i.kind(), i.price(), Integer::sum));
        show(totals);
        show(totals.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList() + " " + totals.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")) + " " + totals.keySet().stream().sorted().toList() + " " + totals.values().stream().mapToInt(Integer::intValue).sum() + " " + totals.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey() + " " + totals.entrySet().stream().filter(e -> e.getValue() > 1000).map(Map.Entry::getKey).findFirst().get());
        show(totals.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() / 10)));
        show(words.stream().distinct().sorted(Comparator.comparing(String::length).thenComparing(Comparator.reverseOrder())).toList());
        show(words.stream().sorted(Comparator.reverseOrder()).limit(3).toList() + " " + words.stream().sorted(Collections.reverseOrder()).skip(4).toList() + " " + words.stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().get() + " " + words.stream().sorted(Comparator.comparing(w -> w.charAt(1))).toList());
        show(words.stream().map(String::length).collect(Collectors.toList()) + " " + words.stream().mapToInt(String::length).boxed().collect(Collectors.toSet()) + " " + words.stream().collect(Collectors.toMap(w -> w, w -> 1, Integer::sum)));
        show(words.stream().filter(w -> w.contains("a")).map(w -> w.replace('a', '4')).peek(w -> {
        }).skip(1).limit(3).collect(Collectors.joining(" ", "<", ">")));
        show(words.stream().map(w -> w.split("")).flatMap(Arrays::stream).distinct().sorted().collect(Collectors.joining()));
        show(words.stream().map(w -> w.charAt(0)).map(Character::toUpperCase).map(String::valueOf).collect(Collectors.joining()));
        show(words.stream().flatMap(Everyday::letters).filter(l -> "aeiou".contains(l)).count() + " " + letters("abc").map(String::toUpperCase).toList());
        show(numbers.stream().filter(n -> n % 2 == 0).map(n -> n * n).reduce(0, Integer::sum) + " " + numbers.stream().mapToInt(Integer::intValue).filter(Everyday::isPrime).boxed().toList() + " " + numbers.stream().sorted().distinct().skip(1).limit(3).toList() + " " + numbers.stream().mapToInt(n -> n).summaryStatistics().getMax());
        show(numbers.stream().sorted(Comparator.reverseOrder()).toList() + " " + numbers.stream().sorted(Collections.reverseOrder()).map(String::valueOf).collect(Collectors.joining("")) + " " + numbers.stream().map(n -> n + 0.5).toList() + " " + numbers.stream().map(Integer::doubleValue).mapToDouble(Double::doubleValue).sum() + " " + numbers.stream().map(n -> (long) n).reduce(1L, (a, b) -> a * b));
        show(IntStream.rangeClosed(1, 30).filter(Everyday::isPrime).boxed().toList() + " " + IntStream.rangeClosed(1, 5).mapToObj(i -> "x".repeat(i)).toList() + " " + IntStream.range(0, words.size()).filter(i -> i % 2 == 0).mapToObj(words::get).toList() + " " + IntStream.range(0, 3).mapToObj(i -> new Pair<>(i, words.get(i))).map(p -> p.first() + p.second()).toList());
        show(IntStream.rangeClosed(1, 5).reduce(1, (a, b) -> a * b) + " " + LongStream.rangeClosed(1, 15).reduce(1L, (a, b) -> a * b) + " " + IntStream.rangeClosed(1, 100).sum() + " " + IntStream.rangeClosed(1, 100).filter(i -> i % 3 == 0 || i % 5 == 0).sum() + " " + Stream.iterate(new int[] { 0, 1 }, f -> new int[] { f[1], f[0] + f[1] }).limit(10).map(f -> f[0]).toList() + " " + Stream.iterate(1, x -> x * 2).limit(8).mapToInt(x -> x).sum() + " " + Stream.iterate("a", s -> s.length() < 4, s -> s + "b").toList());
        show(IntStream.rangeClosed(1, 15).mapToObj(i -> i % 15 == 0 ? "FizzBuzz" : i % 3 == 0 ? "Fizz" : i % 5 == 0 ? "Buzz" : String.valueOf(i)).collect(Collectors.joining(" ")));
        show(evens(numbers, n -> n % 2 == 0) + " " + evens(words, w -> w.length() == 5) + " " + largest(numbers.stream()) + " " + largest(words.stream()).get() + " " + largest(Stream.<String>empty()));
        List<Shape> shapes = List.of(new Circle(1), new Square(2), new Circle(0.5));
        show(shapes.stream().mapToDouble(Shape::area).sum() + " " + shapes.stream().filter(s -> s instanceof Circle).count() + " " + shapes.stream().map(s -> s instanceof Circle c ? "c" + c.r() : "s").toList() + " " + shapes.stream().max(Comparator.comparingDouble(Shape::area)).get());
        List<List<Integer>> nested = List.of(List.of(1, 2), List.of(), List.of(3), List.of(4, 5, 6));
        show(nested.stream().flatMap(List::stream).map(n -> n * 2).toList() + " " + nested.stream().mapToInt(List::size).max().getAsInt() + " " + nested.stream().filter(l -> !l.isEmpty()).map(l -> l.get(0)).toList() + " " + nested.stream().flatMap(Collection::stream).mapToInt(Integer::intValue).sum() + " " + nested.stream().map(l -> l.stream().mapToInt(n -> n).sum()).toList());
        String text = "the quick brown fox jumps over the lazy dog the end";
        Map<String, Long> frequency = Arrays.stream(text.split(" ")).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        show(frequency.get("the") + " " + frequency.size() + " " + frequency.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList() + " " + Arrays.stream(text.split(" ")).map(String::length).collect(Collectors.groupingBy(n -> n, Collectors.counting())) + " " + Arrays.stream(text.split(" ")).max(Comparator.comparingInt(String::length)).get() + " " + Stream.of(text.split(" ")).filter(w -> w.length() > 3).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" ")));
        show(Stream.of("a", "b", "c").map(String::toUpperCase).toList() + " " + Stream.of(1, 2, 3).mapToInt(Integer::intValue).sum() + " " + Stream.of("x").findFirst().get() + " " + Stream.empty().findFirst().isPresent() + " " + Stream.of(1.5, 2.5).mapToDouble(Double::doubleValue).sum() + " " + Stream.of('a', 'b').map(c -> (int) c).toList() + " " + Stream.of(true, false).filter(b -> b).count() + " " + Stream.concat(Stream.of("a"), Stream.of("b", "c")).toList() + " " + Stream.concat(words.stream().limit(1), numbers.stream().limit(1).map(String::valueOf)).toList() + " " + Stream.ofNullable(null).count() + " " + Stream.ofNullable("v").toList());
        Stream.Builder<String> builder = Stream.builder();
        builder.add("one").add("two");
        builder.accept("three");
        show(builder.build().collect(Collectors.joining("+")) + " " + Stream.generate(() -> "z").limit(3).collect(Collectors.joining()) + " " + Stream.of("b", "a", "c").sorted().iterator().next() + " " + Stream.of(1, 2, 3, 4).takeWhile(n -> n < 3).toList() + Stream.of(1, 2, 3, 4).dropWhile(n -> n < 3).toList() + " " + Stream.of(3, 1, 2).sorted().peek(n -> {
        }).unordered().parallel().sequential().map(n -> n + 1).toList());
        Supplier<Stream<String>> source = () -> words.stream().filter(w -> w.length() > 4);
        show(source.get().count() + " " + source.get().findFirst().get() + " " + source.get().distinct().count());
        Function<Item, String> label = i -> i.kind().toUpperCase();
        Predicate<Item> cheap = i -> i.price() < 400;
        show(items.stream().filter(cheap.negate()).map(label.andThen(String::toLowerCase)).distinct().toList() + " " + items.stream().filter(cheap.and(i -> i.kind().equals("desk"))).map(Item::name).toList() + " " + items.stream().filter(Predicate.not(cheap)).count() + " " + items.stream().map(Item::tags).filter(Predicate.not(List::isEmpty)).count());
        Optional<Item> dear = items.stream().filter(i -> i.price() > 1000).findFirst();
        show(dear.map(Item::name).orElse("none") + " " + dear.stream().count() + " " + items.stream().map(Item::name).filter(n -> n.length() > 5).findFirst().or(() -> Optional.of("short")).get() + " " + items.stream().findFirst().map(Item::tags).map(List::size).orElse(-1));
        // strings, lines and patterns
        show("b,a,,c".chars().filter(c -> c == ',').count() + " " + Arrays.stream("b,a,,c".split(",")).filter(s -> !s.isEmpty()).sorted().toList() + " " + "one\ntwo\r\nthree\n".lines().map(String::length).toList() + " " + "  pad \n\n x".lines().map(String::strip).filter(Predicate.not(String::isEmpty)).toList() + " " + "".lines().count() + " " + "abc".chars().mapToObj(c -> (char) c).map(c -> (char) (c + 1)).map(String::valueOf).collect(Collectors.joining()) + " " + String.join("|", Stream.of("x", "y").toList()) + " " + "Hello World".chars().filter(Character::isUpperCase).count() + " " + "abc".chars().map(c -> c - 'a').boxed().toList());
        show(Pattern.compile("\\s*,\\s*").splitAsStream("a , b,c ,d").toList() + " " + Pattern.compile(",").splitAsStream("").count() + " " + Pattern.compile(",").splitAsStream("a,,b,,").toList().size() + " " + Pattern.compile(",").splitAsStream(",a").toList() + " " + Pattern.compile("").splitAsStream("abc").toList() + " " + Pattern.compile("x").splitAsStream("abc").toList() + " " + Pattern.compile("\\d+").matcher("a1b22c333").results().map(MatchResult::group).toList() + " " + Pattern.compile("(\\w)(\\d)").matcher("a1 b2 c3").results().map(m -> m.group(2) + m.group(1) + m.start()).collect(Collectors.joining()) + " " + Pattern.compile("z").matcher("abc").results().count());
        Matcher matcher = Pattern.compile("[a-z]+").matcher("ab cd ef gh");
        matcher.find();
        Stream<MatchResult> rest = matcher.results();
        matcher.find();
        show(rest.map(MatchResult::group).toList() + " " + matcher.results().count());
        fails("after results", () -> matcher.group());
        BufferedReader reader = new BufferedReader(new StringReader("first\nsecond\n\nfourth"));
        show(reader.readLine() + " " + reader.lines().map(String::toUpperCase).toList() + " " + reader.readLine() + " " + new BufferedReader(new StringReader("3\n1\n2")).lines().mapToInt(Integer::parseInt).sorted().boxed().toList() + " " + new BufferedReader(new StringReader("")).lines().count());
        BufferedReader lazy = new BufferedReader(new StringReader("a\nb\nc\nd"));
        show(lazy.lines().limit(2).toList() + " " + lazy.readLine());
        // the collections as sources
        Deque<Integer> deque = new ArrayDeque<>(List.of(1, 2, 3));
        Map<String, List<Integer>> multi = new HashMap<>(Map.of("a", List.of(1, 2), "b", List.of(3)));
        show(deque.stream().map(n -> n * 2).toList() + " " + new HashSet<>(numbers).stream().sorted().toList() + " " + multi.values().stream().flatMap(List::stream).mapToInt(n -> n).sum() + " " + multi.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + e.getValue().size()).toList() + " " + new Vector<>(numbers).stream().limit(2).toList() + " " + Collections.unmodifiableList(words).stream().count() + " " + words.subList(1, 3).stream().toList() + " " + Collections.nCopies(3, "ab").stream().collect(Collectors.joining()) + " " + new CopyOnWriteArrayList<>(numbers).stream().mapToInt(n -> n).max().getAsInt() + " " + new ConcurrentHashMap<>(Map.of("k", 1)).keySet().stream().toList() + " " + new ConcurrentLinkedQueue<>(words).stream().distinct().count() + " " + new LinkedBlockingQueue<>(numbers).stream().filter(n -> n > 5).count() + " " + numbers.parallelStream().mapToInt(n -> n).sum() + " " + words.parallelStream().map(String::length).reduce(0, Integer::sum));
        List<String> changing = new ArrayList<>(List.of("a", "b"));
        Stream<String> made = changing.stream();
        changing.add("c");
        show(made.toList());
        fails("changed while read", () -> {
            List<Integer> source2 = new ArrayList<>(List.of(1, 2, 3));
            source2.stream().forEach(n -> {
                if (n == 2) {
                    source2.add(9);
                }
            });
            return source2;
        });
        // forEach in its forms
        StringBuilder out = new StringBuilder();
        words.stream().forEach(out::append);
        words.stream().map(String::length).forEach(n -> out.append(n));
        numbers.stream().forEachOrdered(out::append);
        words.forEach(w -> out.append(w.charAt(0)));
        IntStream.range(0, 3).forEach(out::append);
        show(out);
        numbers.stream().limit(2).forEach(System.out::println);
        words.stream().limit(2).map(String::toUpperCase).forEach(System.out::println);
        IntStream.range(0, 2).forEach(System.out::println);
        DoubleStream.of(0.5).forEach(System.out::println);
        Stream.of('c', 'd').forEach(System.out::print);
        System.out.println();
        // a stream closed, a stream in try with resources
        List<String> closed = new ArrayList<>();
        try (Stream<String> s = words.stream().onClose(() -> closed.add("closed"))) {
            closed.add(s.findFirst().get());
        }
        try (IntStream s = IntStream.range(0, 3).onClose(() -> closed.add("ints"))) {
            closed.add("" + s.sum());
        }
        show(closed);
        // the arguments a stream refuses
        fails("not comparable", () -> {
            try {
                return Stream.of(new Circle(1), new Circle(2)).sorted().toList();
            }
            catch (ClassCastException e) {
                return "ClassCastException";
            }
        });
        fails("one not comparable", () -> Stream.of(new Circle(1)).sorted().toList().size());
        fails("sorted null", () -> Stream.of("a", null).sorted().toList());
        fails("null element", () -> Stream.of("a", null, "b").map(s -> s == null ? "?" : s).toList());
        fails("null toList", () -> Stream.of("a", null).toList().contains(null));
        fails("max of nulls", () -> Stream.of("a", null).max(Comparator.nullsFirst(Comparator.naturalOrder())));
        fails("first null", () -> Stream.of(null, "a").findFirst());
        fails("reduce to null", () -> Stream.of("a", "b").reduce((a, b) -> null));
        fails("toList frozen", () -> words.stream().toList().add("x"));
        fails("toList remove", () -> words.stream().toList().remove(0));
        fails("collect toList", () -> words.stream().collect(Collectors.toList()).remove(0));
        fails("get of none", () -> Stream.empty().findFirst().get());
        fails("used", () -> {
            Stream<String> once = words.stream();
            once.count();
            return once.count();
        });
        fails("used filter", () -> {
            Stream<String> once = words.stream();
            once.filter(w -> true);
            return once.map(w -> w).count();
        });
        fails("iterator", () -> {
            Iterator<String> each = words.stream().map(String::toUpperCase).iterator();
            String firstTwo = each.next() + each.next();
            each.forEachRemaining(w -> {
            });
            return firstTwo + each.hasNext();
        });
        fails("infinite", () -> Stream.iterate(1, n -> n + 1).filter(n -> n % 7 == 0).skip(2).findFirst().get());
        fails("generate", () -> {
            int[] next = { 0 };
            return Stream.generate(() -> next[0]++).takeWhile(n -> n < 4).toList();
        });
    }
}
