import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Prim {
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

    static void walk(String what, Spliterator<?> s) {
        StringBuilder line = new StringBuilder(what + ": " + told(s));
        List<Object> seen = new ArrayList<>();
        int n = 0;
        while (n++ < 4 && s.tryAdvance(seen::add)) {
            line.append(" -> ").append(size(s));
        }
        line.append(" end ").append(size(s)).append(" ").append(seen).append(" split ").append(s.trySplit() == null ? "null" : "some");
        System.out.println(line);
    }

    static void fails(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ran");
        }
        catch (NullPointerException e) {
            System.out.println(what + ": " + e.getClass().getName());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e);
        }
    }

    static PrimitiveIterator.OfInt counting(int n) {
        return IntStream.range(0, n).iterator();
    }

    public static void main(String[] args) {
        int[] ints = { 5, 6, 7 };
        long[] longs = { 5, 6, 7 };
        double[] doubles = { 5, 6, 7 };
        walk("empty int", Spliterators.emptyIntSpliterator());
        walk("empty long", Spliterators.emptyLongSpliterator());
        walk("empty double", Spliterators.emptyDoubleSpliterator());
        walk("int array", Spliterators.spliterator(ints, 0));
        walk("int array marks", Spliterators.spliterator(ints, Spliterator.DISTINCT | Spliterator.CONCURRENT));
        walk("int array range", Spliterators.spliterator(ints, 1, 3, Spliterator.ORDERED));
        walk("long array", Spliterators.spliterator(longs, 1, 2, Spliterator.SORTED));
        walk("double array", Spliterators.spliterator(doubles, Spliterator.NONNULL));
        fails("int array from > to", () -> Spliterators.spliterator(ints, 2, 1, 0));
        fails("int array to > length", () -> Spliterators.spliterator(ints, 0, 4, 0));
        fails("int array from < 0", () -> Spliterators.spliterator(ints, -1, 2, 0));
        fails("int array null", () -> Spliterators.spliterator((int[]) null, 0));
        walk("Arrays int", Arrays.spliterator(ints));
        walk("Arrays int range", Arrays.spliterator(ints, 1, 2));
        walk("Arrays long", Arrays.spliterator(longs));
        walk("Arrays double", Arrays.spliterator(doubles, 0, 2));
        walk("Arrays objects", Arrays.spliterator(new String[] { "a", "b" }));
        walk("iterator sized", Spliterators.spliterator(counting(3), 3, 0));
        walk("iterator sized marks", Spliterators.spliterator(counting(3), 3, Spliterator.ORDERED | Spliterator.CONCURRENT));
        walk("iterator sized short", Spliterators.spliterator(counting(3), 1, Spliterator.ORDERED));
        walk("iterator unknown", Spliterators.spliteratorUnknownSize(counting(3), Spliterator.ORDERED | Spliterator.SIZED));
        walk("long iterator", Spliterators.spliterator(LongStream.range(0, 2).iterator(), 2, Spliterator.NONNULL));
        walk("double iterator", Spliterators.spliteratorUnknownSize(DoubleStream.of(1.5).iterator(), 0));
        fails("iterator null", () -> Spliterators.spliterator((PrimitiveIterator.OfInt) null, 3, 0));
        PrimitiveIterator.OfInt it = Spliterators.iterator(Spliterators.spliterator(ints, 0));
        System.out.println("iterator of: " + it.hasNext() + " " + it.nextInt() + " " + it.next() + " " + it.nextInt() + " " + it.hasNext());
        fails("iterator of end", () -> it.nextInt());
        PrimitiveIterator.OfLong lit = Spliterators.iterator(Spliterators.spliterator(longs, 0));
        System.out.println("long iterator of: " + lit.nextLong());
        PrimitiveIterator.OfDouble dit = Spliterators.iterator(Spliterators.spliterator(doubles, 0));
        System.out.println("double iterator of: " + dit.nextDouble());
        fails("iterator of null", () -> Spliterators.iterator((Spliterator.OfInt) null));
        // a program's own
        Spliterator.OfInt own = new Spliterators.AbstractIntSpliterator(3, Spliterator.SIZED | Spliterator.ORDERED) {
            int at;

            public boolean tryAdvance(IntConsumer action) {
                if (at == 3) {
                    return false;
                }
                action.accept(at++ * 2);
                return true;
            }
        };
        System.out.println("own: " + told(own));
        System.out.println("own stream: " + StreamSupport.intStream(own, false).map(x -> x + 1).boxed().toList());
        Spliterator.OfLong ownLong = new Spliterators.AbstractLongSpliterator(Long.MAX_VALUE, 0) {
            long at;

            public boolean tryAdvance(LongConsumer action) {
                if (at == 3) {
                    return false;
                }
                action.accept(at++);
                return true;
            }
        };
        System.out.println("own long: " + told(ownLong) + " " + StreamSupport.longStream(ownLong, false).sum());
        Spliterator.OfDouble ownDouble = new Spliterators.AbstractDoubleSpliterator(2, Spliterator.SIZED) {
            double at = 0.5;

            public boolean tryAdvance(DoubleConsumer action) {
                if (at > 2) {
                    return false;
                }
                action.accept(at++);
                return true;
            }
        };
        System.out.println("own double: " + told(ownDouble) + " " + StreamSupport.doubleStream(ownDouble, false).boxed().toList());
        // streams of spliterators
        System.out.println("intStream: " + StreamSupport.intStream(Arrays.spliterator(ints), false).sum());
        System.out.println("intStream count: " + StreamSupport.intStream(Arrays.spliterator(ints), false).peek(x -> System.out.println("ran")).count());
        System.out.println("intStream supplier: " + StreamSupport.intStream(() -> { System.out.println("asked"); return Arrays.spliterator(ints); }, Spliterator.SIZED, true).isParallel());
        IntStream lazy = StreamSupport.intStream(() -> { System.out.println("asked"); return Arrays.spliterator(ints); }, Spliterator.SIZED, false);
        System.out.println("made");
        System.out.println("supplier count: " + lazy.count());
        System.out.println("longStream: " + StreamSupport.longStream(Arrays.spliterator(longs), false).max());
        System.out.println("doubleStream: " + StreamSupport.doubleStream(Arrays.spliterator(doubles), false).average());
        System.out.println("doubleStream supplier: " + StreamSupport.doubleStream(() -> Arrays.spliterator(doubles), 0, false).count());
        walk("intStream own", StreamSupport.intStream(Arrays.spliterator(ints), false).spliterator());
        walk("intStream staged", StreamSupport.intStream(Arrays.spliterator(ints), false).map(x -> x).spliterator());
        fails("intStream null", () -> StreamSupport.intStream(null, false));
        // boxed walks
        Spliterator.OfInt b = Arrays.spliterator(ints);
        b.tryAdvance((Consumer<Integer>) x -> System.out.println("boxed " + x));
        b.forEachRemaining((Consumer<Integer>) x -> System.out.println("boxed rest " + x));
        fails("null int action", () -> Arrays.spliterator(ints).tryAdvance((IntConsumer) null));
        fails("null boxed action", () -> Arrays.spliterator(ints).tryAdvance((Consumer<Integer>) null));
        fails("null rest", () -> Arrays.spliterator(ints).forEachRemaining((IntConsumer) null));
        // the streams of a Random
        walk("ints", new Random(1).ints().limit(2).map(x -> 0).spliterator());
        walk("ints own", new Random(1).ints().spliterator());
        walk("ints n", new Random(1).ints(3).spliterator());
        walk("ints range", new Random(1).ints(0, 10).spliterator());
        walk("ints n range", new Random(1).ints(3, 0, 10).spliterator());
        walk("longs n", new Random(1).longs(2).spliterator());
        walk("doubles n", new Random(1).doubles(2).spliterator());
        walk("results", java.util.regex.Pattern.compile("a").matcher("aa").results().map(m -> m.start()).spliterator());
        walk("results own", java.util.regex.Pattern.compile("a").matcher("aa").results().spliterator());
        walk("reader lines", new java.io.BufferedReader(new java.io.StringReader("a\nb")).lines().spliterator());
        walk("builder one", Stream.builder().add(1).build().spliterator());
        walk("builder none", Stream.builder().build().spliterator());
        walk("int builder two", IntStream.builder().add(1).add(2).build().spliterator());
        walk("int builder none", IntStream.builder().build().spliterator());
        walk("optional empty", Optional.empty().stream().spliterator());
        walk("ofNullable null", Stream.ofNullable(null).spliterator());
        walk("of none", Stream.of().spliterator());
        walk("of array one", Stream.of(new String[] { "a" }).spliterator());
        walk("Arrays.stream none", Arrays.stream(new int[0]).spliterator());
        walk("chm keys", new java.util.concurrent.ConcurrentHashMap<>(Map.of(1, 2)).keySet().stream().spliterator());
        walk("chm keys map", new java.util.concurrent.ConcurrentHashMap<>(Map.of(1, 2)).keySet().stream().map(x -> x).spliterator());
    }
}
