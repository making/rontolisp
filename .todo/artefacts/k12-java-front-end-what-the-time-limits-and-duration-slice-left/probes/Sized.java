import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

public class Sized {
    static int ran;

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

    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>(List.of(1, 2, 3));
        Map<Integer, Integer> map = new HashMap<>(Map.of(1, 2, 3, 4));
        Integer[] array = { 1, 2, 3 };
        row("ArrayList", () -> list.stream());
        row("ArrayList parallelStream", () -> list.parallelStream());
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
        row("Stack", () -> {
            Stack<Integer> s = new Stack<>();
            s.push(1);
            s.push(2);
            return s.stream();
        });
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
        row("CopyOnWriteArrayList", () -> new CopyOnWriteArrayList<>(list).stream());
        row("CopyOnWriteArraySet", () -> new CopyOnWriteArraySet<>(list).stream());
        row("ConcurrentHashMap keys", () -> new ConcurrentHashMap<>(map).keySet().stream());
        row("ConcurrentHashMap values", () -> new ConcurrentHashMap<>(map).values().stream());
        row("ConcurrentLinkedQueue", () -> new ConcurrentLinkedQueue<>(list).stream());
        row("ConcurrentLinkedDeque", () -> new ConcurrentLinkedDeque<>(list).stream());
        row("LinkedBlockingQueue", () -> new LinkedBlockingQueue<>(list).stream());
        row("ArrayBlockingQueue", () -> new ArrayBlockingQueue<>(5, false, list).stream());
        row("LinkedBlockingDeque", () -> new LinkedBlockingDeque<>(list).stream());
        row("PriorityBlockingQueue", () -> new PriorityBlockingQueue<>(list).stream());
        row("ConcurrentSkipListSet", () -> new ConcurrentSkipListSet<>(list).stream());
        row("ConcurrentSkipListMap keys", () -> new ConcurrentSkipListMap<>(map).keySet().stream());
        row("LinkedTransferQueue", () -> new LinkedTransferQueue<>(list).stream());
        row("DelayQueue-free: SynchronousQueue", () -> new SynchronousQueue<Integer>().stream());
        row("AbstractList own", () -> new AbstractList<Integer>() {
            public Integer get(int i) {
                return i;
            }

            public int size() {
                return 3;
            }
        }.stream());
        row("AbstractSet own", () -> new AbstractSet<Integer>() {
            public Iterator<Integer> iterator() {
                return list.iterator();
            }

            public int size() {
                return 3;
            }
        }.stream());
        row("AbstractCollection own", () -> new AbstractCollection<Integer>() {
            public Iterator<Integer> iterator() {
                return list.iterator();
            }

            public int size() {
                return 3;
            }
        }.stream());
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
        row("parallel", () -> list.stream().parallel());
        row("onClose", () -> list.stream().onClose(() -> {
        }));
        row("skip skip", () -> list.stream().skip(1));
        row("limit limit", () -> list.stream().limit(2));
        row("filter", () -> list.stream().filter(x -> true));
        row("distinct", () -> list.stream().distinct());
        row("flatMap", () -> list.stream().flatMap(x -> Stream.of(x)));
        row("mapMulti", () -> list.stream().mapMulti((x, sink) -> sink.accept(x)));
        row("takeWhile", () -> list.stream().takeWhile(x -> true));
        row("dropWhile", () -> list.stream().dropWhile(x -> false));
        row("int sorted", () -> IntStream.of(3, 1, 2).sorted().boxed());
        row("int distinct", () -> IntStream.of(3, 1, 2).distinct().boxed());
        row("int map", () -> IntStream.of(3, 1, 2).map(x -> x).boxed());
        row("int skip", () -> IntStream.of(3, 1, 2).skip(1).boxed());
        row("int limit", () -> IntStream.of(3, 1, 2).limit(2).boxed());
        row("reader lines", () -> new java.io.BufferedReader(new java.io.StringReader("a\nb\nc")).lines());
        row("random ints", () -> new Random(1).ints(3).boxed());
    }
}
