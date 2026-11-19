import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

public class Lazy {
    static StringBuilder log = new StringBuilder();

    static int ran;

    static <T> T seen(T x) {
        log.append(x).append(' ');
        return x;
    }

    static String seenSoFar() {
        return log.isEmpty() ? "" : " | " + log.toString().trim();
    }

    static void show(String what, Object value) {
        System.out.println(what + ": " + value + seenSoFar());
        log.setLength(0);
    }

    static void fails(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ran" + seenSoFar());
        }
        catch (NullPointerException e) {
            System.out.println(what + ": " + e.getClass().getName() + seenSoFar());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e + seenSoFar());
        }
        log.setLength(0);
    }

    // whether a stream of a source knows how many elements it holds: asked for its
    // count, and for it past a skip, a limit and a sort, it runs its stages or does not
    static void row(String what, Supplier<Stream<?>> source) {
        StringBuilder line = new StringBuilder(what + ":");
        ran = 0;
        long n = source.get().peek(x -> ran++).count();
        line.append(" count ").append(n).append(ran == 0 ? " known" : " run");
        ran = 0;
        n = source.get().peek(x -> ran++).skip(1).count();
        line.append(", skip ").append(n).append(ran == 0 ? " known" : " run");
        ran = 0;
        n = source.get().peek(x -> ran++).limit(1).count();
        line.append(", limit ").append(n).append(ran == 0 ? " known" : " run " + ran);
        ran = 0;
        n = source.get().peek(x -> ran++).sorted().skip(1).count();
        line.append(", sorted skip ").append(n).append(ran == 0 ? " known" : " run " + ran);
        System.out.println(line);
    }

    static void sources() {
        List<Integer> list = new ArrayList<>(List.of(1, 2, 3));
        Map<Integer, Integer> map = new HashMap<>(Map.of(1, 2, 3, 4));
        Integer[] array = { 1, 2, 3 };
        row("ArrayList", () -> list.stream());
        row("subList", () -> list.subList(0, 2).stream());
        row("List.of 2", () -> List.of(1, 2).stream());
        row("List.of 3", () -> List.of(1, 2, 3).stream());
        row("List.of 0", () -> List.of().stream());
        row("List.copyOf", () -> List.copyOf(list).stream());
        row("Arrays.asList", () -> Arrays.asList(array).stream());
        row("Arrays.stream", () -> Arrays.stream(array));
        row("Arrays.stream range", () -> Arrays.stream(array, 1, 3));
        row("Stream.of", () -> Stream.of(1, 2, 3));
        row("Stream.of one", () -> Stream.of(1));
        row("Stream.empty", () -> Stream.empty());
        row("Stream.ofNullable", () -> Stream.ofNullable(1));
        row("Stream.ofNullable null", () -> Stream.ofNullable(null));
        row("builder", () -> Stream.builder().add(1).add(2).build());
        row("builder one", () -> Stream.builder().add(1).build());
        row("builder none", () -> Stream.builder().build());
        row("concat", () -> Stream.concat(list.stream(), list.stream()));
        row("concat unknown", () -> Stream.concat(list.stream(), list.stream().filter(x -> true)));
        row("iterate", () -> Stream.iterate(1, x -> x + 1).limit(3));
        row("iterate3", () -> Stream.iterate(1, x -> x < 3, x -> x + 1));
        row("generate", () -> Stream.generate(() -> 1).limit(3));
        row("HashSet", () -> new HashSet<>(list).stream());
        row("Set.of", () -> Set.of(1, 2, 3).stream());
        row("Set.of 2", () -> Set.of(1, 2).stream());
        row("HashMap keySet", () -> map.keySet().stream());
        row("HashMap values", () -> map.values().stream());
        row("HashMap entrySet", () -> map.entrySet().stream());
        row("Map.of keySet", () -> Map.of(1, 2).keySet().stream());
        row("Map.of entrySet", () -> Map.of(1, 2, 3, 4).entrySet().stream());
        row("Hashtable keySet", () -> new Hashtable<>(map).keySet().stream());
        row("Hashtable values", () -> new Hashtable<>(map).values().stream());
        row("ArrayDeque", () -> new ArrayDeque<>(list).stream());
        row("Vector", () -> new Vector<>(list).stream());
        row("unmodifiableList", () -> Collections.unmodifiableList(list).stream());
        row("unmodifiableSet", () -> Collections.unmodifiableSet(new HashSet<>(list)).stream());
        row("unmodifiableCollection", () -> Collections.unmodifiableCollection(list).stream());
        row("unmodifiableMap keys", () -> Collections.unmodifiableMap(map).keySet().stream());
        row("unmodifiableMap entries", () -> Collections.unmodifiableMap(map).entrySet().stream());
        row("synchronizedList", () -> Collections.synchronizedList(list).stream());
        row("synchronizedSet", () -> Collections.synchronizedSet(new HashSet<>(list)).stream());
        row("synchronizedMap keys", () -> Collections.synchronizedMap(map).keySet().stream());
        row("emptyList", () -> Collections.emptyList().stream());
        row("emptySet", () -> Collections.emptySet().stream());
        row("singletonList", () -> Collections.singletonList(1).stream());
        row("singleton", () -> Collections.singleton(1).stream());
        row("nCopies", () -> Collections.nCopies(3, 1).stream());
        row("CopyOnWriteArrayList", () -> new java.util.concurrent.CopyOnWriteArrayList<>(list).stream());
        row("CopyOnWriteArraySet", () -> new java.util.concurrent.CopyOnWriteArraySet<>(list).stream());
        row("ConcurrentHashMap keys", () -> new java.util.concurrent.ConcurrentHashMap<>(map).keySet().stream());
        row("ConcurrentHashMap values", () -> new java.util.concurrent.ConcurrentHashMap<>(map).values().stream());
        row("ConcurrentLinkedQueue", () -> new java.util.concurrent.ConcurrentLinkedQueue<>(list).stream());
        row("ConcurrentLinkedDeque", () -> new java.util.concurrent.ConcurrentLinkedDeque<>(list).stream());
        row("LinkedBlockingQueue", () -> new java.util.concurrent.LinkedBlockingQueue<>(list).stream());
        row("ArrayBlockingQueue", () -> new java.util.concurrent.ArrayBlockingQueue<>(5, false, list).stream());
        row("LinkedBlockingDeque", () -> new java.util.concurrent.LinkedBlockingDeque<>(list).stream());
        row("LinkedTransferQueue", () -> new java.util.concurrent.LinkedTransferQueue<>(list).stream());
        row("SynchronousQueue", () -> new java.util.concurrent.SynchronousQueue<Integer>().stream());
        row("Optional", () -> Optional.of(1).stream());
        row("Optional empty", () -> Optional.empty().stream());
        row("OptionalInt", () -> OptionalInt.of(1).stream().boxed());
        row("chars", () -> "abc".chars().boxed());
        row("codePoints", () -> "abc".codePoints().boxed());
        row("sb chars", () -> new StringBuilder("abc").chars().boxed());
        row("lines", () -> "a\nb\nc".lines());
        row("splitAsStream", () -> Pattern.compile(",").splitAsStream("a,b,c"));
        row("results", () -> Pattern.compile("a").matcher("aaa").results().map(r -> r.group()));
        row("IntStream.range", () -> IntStream.range(0, 3).boxed());
        row("IntStream.rangeClosed", () -> IntStream.rangeClosed(0, 3).boxed());
        row("IntStream.of", () -> IntStream.of(1, 2, 3).boxed());
        row("IntStream.of one", () -> IntStream.of(1).boxed());
        row("IntStream.empty", () -> IntStream.empty().boxed());
        row("IntStream.iterate", () -> IntStream.iterate(0, x -> x + 1).limit(3).boxed());
        row("IntStream.concat", () -> IntStream.concat(IntStream.range(0, 2), IntStream.of(1)).boxed());
        row("Arrays.stream int", () -> Arrays.stream(new int[] { 1, 2, 3 }).boxed());
        row("Arrays.stream int range", () -> Arrays.stream(new int[] { 1, 2, 3 }, 1, 3).boxed());
        row("LongStream.range", () -> LongStream.range(0, 3).boxed());
        row("LongStream.rangeClosed", () -> LongStream.rangeClosed(0, 3).boxed());
        row("DoubleStream.of", () -> DoubleStream.of(1, 2, 3).boxed());
        row("Arrays.stream double", () -> Arrays.stream(new double[] { 1, 2, 3 }).boxed());
        row("mapToObj", () -> IntStream.range(0, 3).mapToObj(x -> "s" + x));
        row("asLongStream", () -> IntStream.range(0, 3).asLongStream().boxed());
        row("asDoubleStream", () -> IntStream.range(0, 3).asDoubleStream().boxed());
        row("mapToLong", () -> IntStream.range(0, 3).mapToLong(x -> x).boxed());
        row("mapToDouble", () -> list.stream().mapToDouble(x -> x).boxed());
        row("map", () -> list.stream().map(x -> x));
        row("sorted", () -> list.stream().sorted());
        row("sorted cmp", () -> list.stream().sorted(Comparator.reverseOrder()));
        row("unordered", () -> list.stream().unordered());
        row("skip skip", () -> list.stream().skip(1));
        row("limit limit", () -> list.stream().limit(2));
        row("filter", () -> list.stream().filter(x -> true));
        row("distinct", () -> list.stream().distinct());
        row("flatMap", () -> list.stream().flatMap(x -> Stream.of(x)));
        row("takeWhile", () -> list.stream().takeWhile(x -> true));
        row("dropWhile", () -> list.stream().dropWhile(x -> false));
        row("int sorted", () -> IntStream.of(3, 1, 2).sorted().boxed());
        row("int distinct", () -> IntStream.of(3, 1, 2).distinct().boxed());
        row("int map", () -> IntStream.of(3, 1, 2).map(x -> x).boxed());
        row("int skip", () -> IntStream.of(3, 1, 2).skip(1).boxed());
        row("int limit", () -> IntStream.of(3, 1, 2).limit(2).boxed());
        row("reader lines", () -> new java.io.BufferedReader(new java.io.StringReader("a\nb\nc")).lines());
    }

    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4));
        Set<Integer> set = new HashSet<>(list);
        Integer[] array = { 1, 2, 3, 4 };
        // count: which pipelines run their stages
        show("count list peek", list.stream().peek(Lazy::seen).count());
        show("count list map", list.stream().map(Lazy::seen).count());
        show("count list filter", list.stream().peek(Lazy::seen).filter(x -> true).count());
        show("count list sorted", list.stream().peek(Lazy::seen).sorted().count());
        show("count list distinct", list.stream().peek(Lazy::seen).distinct().count());
        show("count list skip", list.stream().peek(Lazy::seen).skip(1).count());
        show("count list limit", list.stream().peek(Lazy::seen).limit(3).count());
        show("count list limit over", list.stream().peek(Lazy::seen).limit(9).count());
        show("count list skip over", list.stream().peek(Lazy::seen).skip(9).count());
        show("count list skip limit", list.stream().peek(Lazy::seen).skip(1).limit(2).count());
        show("count list boxedmap", list.stream().peek(Lazy::seen).mapToInt(x -> x).count());
        show("count list mapToObj", list.stream().mapToInt(x -> x).peek(Lazy::seen).mapToObj(x -> x).count());
        show("count list boxed", list.stream().mapToInt(x -> x).peek(Lazy::seen).boxed().count());
        show("count list asLong", list.stream().mapToInt(x -> x).peek(Lazy::seen).asLongStream().count());
        show("count list flatMap", list.stream().peek(Lazy::seen).flatMap(x -> Stream.of(x)).count());
        show("count list takeWhile", list.stream().peek(Lazy::seen).takeWhile(x -> true).count());
        show("count list dropWhile", list.stream().peek(Lazy::seen).dropWhile(x -> false).count());
        show("count set peek", set.stream().peek(Lazy::seen).count());
        show("count array peek", Arrays.stream(array).peek(Lazy::seen).count());
        show("count asList peek", Arrays.asList(array).stream().peek(Lazy::seen).count());
        show("count of peek", Stream.of(1, 2, 3).peek(Lazy::seen).count());
        show("count of one peek", Stream.of(1).peek(Lazy::seen).count());
        show("count empty peek", Stream.empty().peek(Lazy::seen).count());
        show("count range peek", IntStream.range(0, 4).peek(Lazy::seen).count());
        show("count rangeClosed peek", IntStream.rangeClosed(0, 4).peek(Lazy::seen).count());
        show("count longrange peek", LongStream.range(0, 4).peek(Lazy::seen).count());
        show("count intof peek", IntStream.of(1, 2).peek(Lazy::seen).count());
        show("count iterate limit", Stream.iterate(1, x -> x + 1).peek(Lazy::seen).limit(3).count());
        show("count generate limit", Stream.generate(() -> 7).peek(Lazy::seen).limit(3).count());
        show("count concat", Stream.concat(list.stream().peek(Lazy::seen), list.stream().peek(Lazy::seen)).count());
        show("count builder", Stream.builder().add(1).add(2).build().peek(Lazy::seen).count());
        show("count map keys", new HashMap<>(Map.of(1, 2)).keySet().stream().peek(Lazy::seen).count());
        show("count map entries", new HashMap<>(Map.of(1, 2)).entrySet().stream().peek(Lazy::seen).count());
        show("count deque", new ArrayDeque<>(list).stream().peek(Lazy::seen).count());
        show("count listof", List.of(1, 2).stream().peek(Lazy::seen).count());
        show("count sublist", list.subList(1, 3).stream().peek(Lazy::seen).count());
        show("count chars", "abc".chars().peek(Lazy::seen).count());
        show("count lines", "a\nb".lines().peek(Lazy::seen).count());
        show("count optional", Optional.of(1).stream().peek(Lazy::seen).count());
        show("count arrays int", Arrays.stream(new int[] { 1, 2 }).peek(Lazy::seen).count());
        show("count array range", Arrays.stream(array, 1, 3).peek(Lazy::seen).count());
        show("count parallel", list.stream().parallel().peek(Lazy::seen).sequential().count());
        show("toArray sized", Arrays.toString(list.stream().peek(Lazy::seen).toArray()));
        // laziness and interleaving
        show("interleave", list.stream().peek(x -> seen("a" + x)).map(x -> x * 2).peek(x -> seen("b" + x)).filter(x -> x > 2).peek(x -> seen("c" + x)).collect(Collectors.toList()));
        show("sorted barrier", Stream.of(3, 1, 2).peek(x -> seen("a" + x)).sorted().peek(x -> seen("b" + x)).limit(2).collect(Collectors.toList()));
        show("limit stops", list.stream().peek(Lazy::seen).limit(2).collect(Collectors.toList()));
        show("limit zero", list.stream().peek(Lazy::seen).limit(0).collect(Collectors.toList()));
        show("findFirst", list.stream().peek(Lazy::seen).filter(x -> x > 1).findFirst());
        show("anyMatch", list.stream().peek(Lazy::seen).anyMatch(x -> x == 2));
        show("allMatch", list.stream().peek(Lazy::seen).allMatch(x -> x < 2));
        show("noneMatch", list.stream().peek(Lazy::seen).noneMatch(x -> x == 3));
        show("flatMap lazy", list.stream().flatMap(x -> Stream.of(x, x * 10).peek(Lazy::seen)).findFirst());
        show("flatMap limit", list.stream().flatMap(x -> Stream.of(x, x * 10, x * 100).peek(Lazy::seen)).limit(4).collect(Collectors.toList()));
        show("flatMap null", list.stream().flatMap(x -> x == 2 ? null : Stream.of(x)).collect(Collectors.toList()));
        show("takeWhile", list.stream().peek(Lazy::seen).takeWhile(x -> x < 3).collect(Collectors.toList()));
        show("dropWhile", list.stream().peek(Lazy::seen).dropWhile(x -> x < 3).collect(Collectors.toList()));
        show("skip", list.stream().peek(Lazy::seen).skip(2).collect(Collectors.toList()));
        show("skip limit", Stream.iterate(1, x -> seen(x + 1)).skip(2).limit(2).collect(Collectors.toList()));
        show("iterate", Stream.iterate(1, x -> seen(x * 2)).limit(4).collect(Collectors.toList()));
        show("iterate3", Stream.iterate(1, x -> x < 20, x -> seen(x * 2)).collect(Collectors.toList()));
        show("generate", Stream.generate(() -> seen("g")).limit(2).collect(Collectors.toList()));
        show("distinct", Stream.of(3, 1, 3, null, 2, null, 1).peek(Lazy::seen).distinct().collect(Collectors.toList()));
        show("concat", Stream.concat(Stream.of(1).peek(Lazy::seen), Stream.of(2).peek(Lazy::seen)).findFirst());
        Iterator<Integer> it = list.stream().peek(Lazy::seen).map(x -> x * 3).iterator();
        show("iterator made", it.hasNext());
        show("iterator next", it.next());
        show("iterator next", it.next());
        // late binding
        List<String> late = new ArrayList<>(List.of("a", "b"));
        Stream<String> s = late.stream();
        late.add("c");
        show("late", s.collect(Collectors.joining()));
        // used twice
        Stream<Integer> once = list.stream();
        once.map(x -> x);
        fails("twice map", () -> once.map(x -> x));
        fails("twice count", () -> once.count());
        fails("twice onClose", () -> once.onClose(() -> {
        }));
        fails("twice parallel", () -> once.parallel());
        fails("twice iterator", () -> once.iterator());
        fails("twice close", () -> once.close());
        show("twice isParallel", once.isParallel());
        Stream<Integer> closed = list.stream();
        closed.close();
        fails("closed map", () -> closed.map(x -> x));
        Stream<Integer> counted = list.stream();
        counted.count();
        fails("counted count", () -> counted.count());
        fails("counted iterator", () -> counted.iterator());
        // close handlers
        Stream<Integer> c = list.stream().onClose(() -> seen("h1")).map(x -> x).onClose(() -> seen("h2"));
        Stream<Integer> c2 = c.filter(x -> true).onClose(() -> seen("h3"));
        c2.close();
        show("closed", "once");
        c2.close();
        show("closed again", "no more");
        fails("close throws", () -> list.stream().onClose(() -> {
            seen("x1");
            throw new IllegalStateException("one");
        }).onClose(() -> {
            seen("x2");
            throw new IllegalArgumentException("two");
        }).onClose(() -> seen("x3")).close());
        try {
            list.stream().onClose(() -> {
                throw new IllegalStateException("one");
            }).onClose(() -> {
                throw new IllegalArgumentException("two");
            }).close();
        }
        catch (RuntimeException e) {
            show("suppressed", Arrays.toString(e.getSuppressed()));
        }
        show("flatMap closes", list.stream().flatMap(x -> Stream.of(x).onClose(() -> seen("closed" + x))).count());
        show("same onClose", ((Object) once) == once ? "y" : "n");
        Stream<Integer> p = list.stream();
        show("onClose same", p.onClose(() -> {
        }) == p);
        show("parallelStream", list.parallelStream().isParallel());
        show("parallel map", list.stream().parallel().map(x -> x).isParallel());
        show("map parallel", list.stream().map(x -> x).parallel().isParallel());
        // refusals of arguments
        fails("limit -1", () -> list.stream().limit(-1));
        fails("skip -1", () -> list.stream().skip(-1));
        fails("map null", () -> list.stream().map(null));
        fails("filter null", () -> list.stream().filter(null));
        fails("forEach null", () -> list.stream().forEach(null));
        fails("findFirst null", () -> Stream.of((Integer) null).findFirst());
        fails("findAny null", () -> Stream.of(null, 1).findAny());
        fails("max null", () -> Stream.of(1, null).max(Comparator.nullsFirst(Comparator.naturalOrder())));
        fails("min null", () -> Stream.of(null, 1).min(Comparator.nullsFirst(Comparator.naturalOrder())));
        fails("reduce null", () -> Stream.of(1, 2).reduce((a, b) -> null));
        fails("sorted null", () -> Stream.of(1, null).sorted().count());
        fails("of null array", () -> Stream.of((Object[]) null));
        fails("toList null", () -> System.out.println(Stream.of(1, null).toList()));
        fails("toList add", () -> Stream.of(1).toList().add(2));
        fails("toList set", () -> Stream.of(1).toList().set(0, 2));
        fails("collect toList add", () -> Stream.of(1).collect(Collectors.toList()).add(2));
        fails("toUnmodifiableList null", () -> Stream.of(1, null).collect(Collectors.toUnmodifiableList()));
        fails("toUnmodifiableList add", () -> Stream.of(1).collect(Collectors.toUnmodifiableList()).add(1));
        fails("iterate null", () -> Stream.iterate(1, null));
        fails("generate null", () -> Stream.generate(null));
        fails("range", () -> System.out.println(IntStream.range(5, 1).count() + " " + IntStream.rangeClosed(5, 5).count() + " " + IntStream.rangeClosed(5, 4).count()));
        fails("iterator remove", () -> {
            Iterator<Integer> each = list.stream().iterator();
            each.next();
            each.remove();
        });
        fails("iterator end", () -> {
            Iterator<Integer> each = Stream.<Integer>empty().iterator();
            each.next();
        });
        fails("builder after build", () -> {
            Stream.Builder<Integer> b = Stream.builder();
            b.accept(1);
            b.build();
            b.accept(2);
        });
        fails("builder build twice", () -> {
            Stream.Builder<Integer> b = Stream.builder();
            b.build();
            b.build();
        });
        sources();
    }
}
