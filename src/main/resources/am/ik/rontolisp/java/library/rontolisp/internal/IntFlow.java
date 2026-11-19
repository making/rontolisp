// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

// A stream of ints: the one class behind java.util.stream.IntStream (Flow says how a
// stream hands its elements on).
public final class IntFlow extends Flow implements IntStream {

    final IntPull pull;

    IntFlow(Origin origin, Ending ending, Count count, IntPull pull) {
        super(origin, ending, count);
        this.pull = pull;
    }

    public static final class Building implements IntStream.Builder {

        private int[] added = new int[8];

        private int size;

        public void accept(int t) {
            if (added == null) {
                throw new IllegalStateException();
            }
            if (size == added.length) {
                added = Arrays.copyOf(added, size * 2);
            }
            added[size++] = t;
        }

        public IntStream build() {
            int[] all = added;
            if (all == null) {
                throw new IllegalStateException();
            }
            added = null;
            return of(all, 0, size);
        }
    }

    public static IntFlow of(int[] array, int from, int to) {
        Ending ending = new Ending();
        int[] at = { from };
        long length = to - from;
        return new IntFlow(new Origin(), ending, () -> length, () -> {
            int i = at[0];
            if (i < to) {
                at[0] = i + 1;
                return array[i];
            }
            ending.reached = true;
            return 0;
        });
    }

    // from a number up to one that is still an element
    public static IntFlow range(int from, long last) {
        Ending ending = new Ending();
        long[] at = { from };
        long length = Math.max(0, last - from + 1);
        return new IntFlow(new Origin(), ending, () -> length, () -> {
            long i = at[0];
            if (i <= last) {
                at[0] = i + 1;
                return (int) i;
            }
            ending.reached = true;
            return 0;
        });
    }

    public static IntFlow iterated(int seed, IntPredicate more, IntUnaryOperator step) {
        given(step);
        Ending ending = new Ending();
        int[] last = new int[1];
        boolean[] started = new boolean[1];
        return new IntFlow(new Origin(), ending, null, () -> {
            int item = started[0] ? step.applyAsInt(last[0]) : seed;
            started[0] = true;
            if (more != null && !more.test(item)) {
                ending.reached = true;
                return 0;
            }
            last[0] = item;
            return item;
        });
    }

    public static IntFlow generated(IntSupplier supplier) {
        given(supplier);
        return new IntFlow(new Origin(), new Ending(), null, () -> supplier.getAsInt());
    }

    // so many elements of a supplier, each asked for when the stream is read: the
    // streams of a Random
    public static IntFlow drawn(IntSupplier supplier, long size) {
        Ending ending = new Ending();
        long[] left = { size };
        return new IntFlow(new Origin(), ending, () -> size, () -> {
            long n = left[0];
            if (n == 0) {
                ending.reached = true;
                return 0;
            }
            left[0] = n - 1;
            return supplier.getAsInt();
        });
    }

    public static IntFlow concat(IntStream a, IntStream b) {
        IntFlow first = entered(a);
        IntFlow second = entered(b);
        IntPull head = first.pull;
        IntPull tail = second.pull;
        Ending headEnding = first.ending;
        boolean[] passed = new boolean[1];
        return new IntFlow(joined(first, second), second.ending, sum(first.count, second.count), () -> {
            if (!passed[0]) {
                int item = head.next();
                if (!headEnding.reached) {
                    return item;
                }
                passed[0] = true;
            }
            return tail.next();
        });
    }

    // a stream another stream reads: it is asked here, once
    static IntFlow entered(IntStream stream) {
        IntFlow flow = (IntFlow) stream;
        flow.link();
        return flow;
    }

    public IntStream sequential() {
        origin.parallel = false;
        return this;
    }

    public IntStream parallel() {
        origin.parallel = true;
        return this;
    }

    public IntStream unordered() {
        link();
        return new IntFlow(origin, ending, count, pull);
    }

    public IntStream onClose(Runnable handler) {
        closing(handler);
        return this;
    }

    public IntStream filter(IntPredicate predicate) {
        given(predicate);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return new IntFlow(origin, ending, null, () -> {
            for (;;) {
                int item = pull.next();
                if (ending.reached || predicate.test(item)) {
                    return item;
                }
            }
        });
    }

    public IntStream map(IntUnaryOperator mapper) {
        given(mapper);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return new IntFlow(origin, ending, count, () -> {
            int item = pull.next();
            return ending.reached ? 0 : mapper.applyAsInt(item);
        });
    }

    public <U> Stream<U> mapToObj(IntFunction<? extends U> mapper) {
        given(mapper);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return new ObjectFlow<U>(origin, ending, count, () -> {
            int item = pull.next();
            return ending.reached ? null : mapper.apply(item);
        });
    }

    public LongStream mapToLong(IntToLongFunction mapper) {
        given(mapper);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return new LongFlow(origin, ending, count, () -> {
            int item = pull.next();
            return ending.reached ? 0 : mapper.applyAsLong(item);
        });
    }

    public DoubleStream mapToDouble(IntToDoubleFunction mapper) {
        given(mapper);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return new DoubleFlow(origin, ending, count, () -> {
            int item = pull.next();
            return ending.reached ? 0 : mapper.applyAsDouble(item);
        });
    }

    public IntStream flatMap(IntFunction<? extends IntStream> mapper) {
        given(mapper);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        Ending flat = new Ending();
        Held<IntFlow> inner = new Held<>();
        within(origin, inner);
        return new IntFlow(origin, flat, null, () -> {
            for (;;) {
                IntFlow in = inner.value;
                if (in != null) {
                    int item = in.pull.next();
                    if (!in.ending.reached) {
                        return item;
                    }
                    inner.value = null;
                    in.close();
                }
                int item = pull.next();
                if (ending.reached) {
                    flat.reached = true;
                    return 0;
                }
                IntStream mapped = mapper.apply(item);
                if (mapped != null) {
                    inner.value = entered(mapped);
                }
            }
        });
    }

    // The elements a function hands a consumer each time it is asked for more: what it
    // hands over in one call is held until the stages after it ask. The function says
    // through the ending that it has no more.
    static IntFlow fed(Origin origin, Ending ending, Consumer<IntConsumer> more) {
        Held<int[]> held = new Held<>();
        held.value = new int[8];
        int[] ends = new int[2];
        IntConsumer take = item -> {
            int[] all = held.value;
            int size = ends[1];
            if (size == all.length) {
                all = Arrays.copyOf(all, size * 2);
                held.value = all;
            }
            all[size] = item;
            ends[1] = size + 1;
        };
        return new IntFlow(origin, ending, null, () -> {
            for (;;) {
                int at = ends[0];
                if (at < ends[1]) {
                    ends[0] = at + 1;
                    return held.value[at];
                }
                ends[0] = 0;
                ends[1] = 0;
                more.accept(take);
                if (ending.reached) {
                    return 0;
                }
            }
        });
    }

    public IntStream mapMulti(IntStream.IntMapMultiConsumer mapper) {
        given(mapper);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return fed(origin, ending, to -> {
            int item = pull.next();
            if (!ending.reached) {
                mapper.accept(item, to);
            }
        });
    }

    public IntStream distinct() {
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        HashSet<Integer> met = new HashSet<>();
        return new IntFlow(origin, ending, null, () -> {
            for (;;) {
                int item = pull.next();
                if (ending.reached || met.add(item)) {
                    return item;
                }
            }
        });
    }

    // every element is taken when the first is asked for
    public IntStream sorted() {
        link();
        Ending sorted = new Ending();
        Held<int[]> held = new Held<>();
        int[] at = new int[1];
        return new IntFlow(origin, sorted, count, () -> {
            int[] all = held.value;
            if (all == null) {
                all = taken();
                Arrays.sort(all);
                held.value = all;
            }
            int i = at[0];
            if (i < all.length) {
                at[0] = i + 1;
                return all[i];
            }
            sorted.reached = true;
            return 0;
        });
    }

    private int[] taken() {
        IntPull pull = this.pull;
        Ending ending = this.ending;
        int[] all = new int[16];
        int size = 0;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            if (size == all.length) {
                all = Arrays.copyOf(all, size * 2);
            }
            all[size++] = item;
        }
        return Arrays.copyOf(all, size);
    }

    public IntStream peek(IntConsumer action) {
        given(action);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return new IntFlow(origin, ending, count, () -> {
            int item = pull.next();
            if (!ending.reached) {
                action.accept(item);
            }
            return item;
        });
    }

    public IntStream limit(long maxSize) {
        atLeastZero(maxSize);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        long[] left = { maxSize };
        Told before = origin.told;
        return new IntFlow(origin, ending, most(count, maxSize), () -> {
            long n = left[0];
            if (n == 0) {
                ending.reached = true;
                leave(before);
                return 0;
            }
            left[0] = n - 1;
            return pull.next();
        });
    }

    public IntStream skip(long n) {
        atLeastZero(n);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        long[] left = { n };
        return new IntFlow(origin, ending, less(count, n), () -> {
            while (left[0] > 0) {
                left[0]--;
                pull.next();
                if (ending.reached) {
                    return 0;
                }
            }
            return pull.next();
        });
    }

    public IntStream takeWhile(IntPredicate predicate) {
        given(predicate);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        Told before = origin.told;
        return new IntFlow(origin, ending, null, () -> {
            int item = pull.next();
            if (!ending.reached && !predicate.test(item)) {
                ending.reached = true;
                leave(before);
            }
            return item;
        });
    }

    public IntStream dropWhile(IntPredicate predicate) {
        given(predicate);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        boolean[] passed = new boolean[1];
        return new IntFlow(origin, ending, null, () -> {
            int item = pull.next();
            if (!passed[0]) {
                while (!ending.reached && predicate.test(item)) {
                    item = pull.next();
                }
                passed[0] = true;
            }
            return item;
        });
    }

    public void forEach(IntConsumer action) {
        given(action);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            action.accept(item);
        }
    }

    public void forEachOrdered(IntConsumer action) {
        forEach(action);
    }

    public int[] toArray() {
        link();
        return taken();
    }

    public int reduce(int identity, IntBinaryOperator op) {
        given(op);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        int result = identity;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            result = op.applyAsInt(result, item);
        }
        return result;
    }

    public OptionalInt reduce(IntBinaryOperator op) {
        given(op);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        int result = pull.next();
        if (ending.reached) {
            return OptionalInt.empty();
        }
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            result = op.applyAsInt(result, item);
        }
        return OptionalInt.of(result);
    }

    public <R> R collect(Supplier<R> supplier, ObjIntConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        given(supplier);
        given(accumulator);
        given(combiner);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        R result = supplier.get();
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            accumulator.accept(result, item);
        }
        return result;
    }

    public int sum() {
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        int sum = 0;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            sum += item;
        }
        return sum;
    }

    public OptionalInt min() {
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        int least = pull.next();
        if (ending.reached) {
            return OptionalInt.empty();
        }
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            if (item < least) {
                least = item;
            }
        }
        return OptionalInt.of(least);
    }

    public OptionalInt max() {
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        int most = pull.next();
        if (ending.reached) {
            return OptionalInt.empty();
        }
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            if (item > most) {
                most = item;
            }
        }
        return OptionalInt.of(most);
    }

    public long count() {
        link();
        if (count != null) {
            return count.now();
        }
        IntPull pull = this.pull;
        Ending ending = this.ending;
        long n = 0;
        for (pull.next(); !ending.reached; pull.next()) {
            n++;
        }
        return n;
    }

    public OptionalDouble average() {
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        long sum = 0;
        long n = 0;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            sum += item;
            n++;
        }
        return n == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) sum / n);
    }

    public IntSummaryStatistics summaryStatistics() {
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        IntSummaryStatistics all = new IntSummaryStatistics();
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            all.accept(item);
        }
        return all;
    }

    public boolean anyMatch(IntPredicate predicate) {
        given(predicate);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                leave(origin.told);
                return true;
            }
        }
        return false;
    }

    public boolean allMatch(IntPredicate predicate) {
        given(predicate);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            if (!predicate.test(item)) {
                leave(origin.told);
                return false;
            }
        }
        return true;
    }

    public boolean noneMatch(IntPredicate predicate) {
        given(predicate);
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        for (int item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                leave(origin.told);
                return false;
            }
        }
        return true;
    }

    public OptionalInt findFirst() {
        link();
        int item = pull.next();
        if (ending.reached) {
            return OptionalInt.empty();
        }
        leave(origin.told);
        return OptionalInt.of(item);
    }

    public OptionalInt findAny() {
        return findFirst();
    }

    public LongStream asLongStream() {
        link();
        IntPull pull = this.pull;
        return new LongFlow(origin, ending, count, () -> pull.next());
    }

    public DoubleStream asDoubleStream() {
        link();
        IntPull pull = this.pull;
        return new DoubleFlow(origin, ending, count, () -> pull.next());
    }

    public Stream<Integer> boxed() {
        link();
        IntPull pull = this.pull;
        return new ObjectFlow<Integer>(origin, ending, count, () -> pull.next());
    }

    // the elements an iterator gives, counted where a count is given
    public static IntFlow over(PrimitiveIterator.OfInt each, Count count) {
        Ending ending = new Ending();
        return new IntFlow(new Origin(), ending, count, () -> {
            if (each.hasNext()) {
                return each.nextInt();
            }
            ending.reached = true;
            return 0;
        });
    }

    // the elements a spliterator gives: the supplier is asked for it at the first pull, or
    // at the count where the characteristics say SIZED
    public static IntFlow split(Supplier<? extends Spliterator.OfInt> supplier, int characteristics,
            boolean parallel) {
        Ending ending = new Ending();
        Held<Spliterator.OfInt> source = new Held<>();
        int[] taken = new int[1];
        IntConsumer take = item -> taken[0] = item;
        Count count = (characteristics & Spliterator.SIZED) == 0 ? null
                : () -> supplied(source, supplier).getExactSizeIfKnown();
        IntFlow flow = new IntFlow(new Origin(), ending, count, () -> {
            if (supplied(source, supplier).tryAdvance(take)) {
                return taken[0];
            }
            ending.reached = true;
            return 0;
        });
        flow.origin.parallel = parallel;
        return flow;
    }

    private static Spliterator.OfInt supplied(Held<Spliterator.OfInt> source,
            Supplier<? extends Spliterator.OfInt> supplier) {
        Spliterator.OfInt spliterator = source.value;
        if (spliterator == null) {
            spliterator = supplier.get();
            source.value = spliterator;
        }
        return spliterator;
    }

    // The spliterator of an iterator's numbers. A size given is reported, and stays what
    // it is while the numbers are taken; it is the exact one where the marks say SIZED.
    public static Spliterator.OfInt walk(PrimitiveIterator.OfInt each, long size, int marks) {
        Count given = size < 0 ? null : () -> size;
        return new Walk(over(each, null), (marks & Spliterator.SIZED) == 0 ? null : given, given, marks, false);
    }

    // the spliterator of an array's numbers, reporting the marks given
    public static Spliterator.OfInt walk(int[] array, int from, int to, int marks) {
        IntFlow flow = of(array, from, to);
        return new Walk(flow, flow.count, null, marks, true);
    }

    public Spliterator.OfInt spliterator() {
        link();
        return new Walk(this, count, null, marks(), true);
    }

    private static final class Walk extends Walked<Integer> implements Spliterator.OfInt {

        private final IntPull pull;

        private final Ending ending;

        Walk(IntFlow flow, Count sized, Count about, int marks, boolean counted) {
            super(sized, about, marks, counted);
            this.pull = flow.pull;
            this.ending = flow.ending;
        }

        public boolean tryAdvance(IntConsumer action) {
            given(action);
            int item = pull.next();
            if (!took(ending)) {
                return false;
            }
            action.accept(item);
            return true;
        }

        public Spliterator.OfInt trySplit() {
            return null;
        }
    }

    public PrimitiveIterator.OfInt iterator() {
        link();
        IntPull pull = this.pull;
        Ending ending = this.ending;
        return new PrimitiveIterator.OfInt() {
            private boolean ready;

            private int item;

            public boolean hasNext() {
                if (!ready && !ending.reached) {
                    item = pull.next();
                    ready = true;
                }
                return !ending.reached;
            }

            public int nextInt() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return item;
            }
        };
    }
}
