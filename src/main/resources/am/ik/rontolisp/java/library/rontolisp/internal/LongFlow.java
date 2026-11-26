// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LongSummaryStatistics;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.LongToDoubleFunction;
import java.util.function.LongToIntFunction;
import java.util.function.LongUnaryOperator;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

// A stream of longs: the one class behind java.util.stream.LongStream (Flow says how a
// stream hands its elements on).
public final class LongFlow extends Flow implements LongStream {

    final LongPull pull;

    LongFlow(Origin origin, Ending ending, Count count, LongPull pull) {
        super(origin, ending, count);
        this.pull = pull;
    }

    public static final class Building implements LongStream.Builder {

        private long[] added = new long[8];

        private int size;

        public void accept(long t) {
            if (added == null) {
                throw new IllegalStateException();
            }
            if (size == added.length) {
                added = Arrays.copyOf(added, size * 2);
            }
            added[size++] = t;
        }

        public LongStream build() {
            long[] all = added;
            if (all == null) {
                throw new IllegalStateException();
            }
            added = null;
            return of(all, 0, size);
        }
    }

    public static LongFlow of(long[] array, int from, int to) {
        Ending ending = new Ending();
        int[] at = { from };
        long length = to - from;
        return new LongFlow(new Origin(), ending, () -> length, () -> {
            int i = at[0];
            if (i < to) {
                at[0] = i + 1;
                return array[i];
            }
            ending.reached = true;
            return 0;
        });
    }

    public static LongFlow range(long from, long to, boolean closed) {
        Ending ending = new Ending();
        long[] at = { from };
        boolean[] over = { closed ? from > to : from >= to };
        long length = over[0] ? 0 : to - from + (closed ? 1 : 0);
        // a range of more elements than a long counts is counted by running it
        return new LongFlow(new Origin(), ending, length > 0 || over[0] ? () -> length : null, () -> {
            if (over[0]) {
                ending.reached = true;
                return 0;
            }
            long i = at[0];
            if (i == (closed ? to : to - 1)) {
                over[0] = true;
            }
            else {
                at[0] = i + 1;
            }
            return i;
        });
    }

    public static LongFlow iterated(long seed, LongPredicate more, LongUnaryOperator step) {
        given(step);
        Ending ending = new Ending();
        long[] last = new long[1];
        boolean[] started = new boolean[1];
        return new LongFlow(new Origin(), ending, null, () -> {
            long item = started[0] ? step.applyAsLong(last[0]) : seed;
            started[0] = true;
            if (more != null && !more.test(item)) {
                ending.reached = true;
                return 0;
            }
            last[0] = item;
            return item;
        });
    }

    public static LongFlow generated(LongSupplier supplier) {
        given(supplier);
        return new LongFlow(new Origin(), new Ending(), null, () -> supplier.getAsLong());
    }

    // so many elements of a supplier, each asked for when the stream is read: the
    // streams of a Random
    public static LongFlow drawn(LongSupplier supplier, long size) {
        Ending ending = new Ending();
        long[] left = { size };
        return new LongFlow(new Origin(), ending, () -> size, () -> {
            long n = left[0];
            if (n == 0) {
                ending.reached = true;
                return 0;
            }
            left[0] = n - 1;
            return supplier.getAsLong();
        });
    }

    public static LongFlow concat(LongStream a, LongStream b) {
        LongFlow first = entered(a);
        LongFlow second = entered(b);
        LongPull head = first.pull;
        LongPull tail = second.pull;
        Ending headEnding = first.ending;
        boolean[] passed = new boolean[1];
        return new LongFlow(joined(first, second), second.ending, sum(first.count, second.count), () -> {
            if (!passed[0]) {
                long item = head.next();
                if (!headEnding.reached) {
                    return item;
                }
                passed[0] = true;
            }
            return tail.next();
        });
    }

    // a stream another stream reads: it is asked here, once
    static LongFlow entered(LongStream stream) {
        LongFlow flow = (LongFlow) stream;
        flow.link();
        return flow;
    }

    public LongStream sequential() {
        origin.parallel = false;
        return this;
    }

    public LongStream parallel() {
        origin.parallel = true;
        return this;
    }

    public LongStream unordered() {
        link();
        return new LongFlow(origin, ending, count, pull);
    }

    public LongStream onClose(Runnable handler) {
        closing(handler);
        return this;
    }

    public LongStream filter(LongPredicate predicate) {
        given(predicate);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return new LongFlow(origin, ending, null, () -> {
            for (;;) {
                long item = pull.next();
                if (ending.reached || predicate.test(item)) {
                    return item;
                }
            }
        });
    }

    public LongStream map(LongUnaryOperator mapper) {
        given(mapper);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return new LongFlow(origin, ending, count, () -> {
            long item = pull.next();
            return ending.reached ? 0 : mapper.applyAsLong(item);
        });
    }

    public <U> Stream<U> mapToObj(LongFunction<? extends U> mapper) {
        given(mapper);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return new ObjectFlow<U>(origin, ending, count, () -> {
            long item = pull.next();
            return ending.reached ? null : mapper.apply(item);
        });
    }

    public IntStream mapToInt(LongToIntFunction mapper) {
        given(mapper);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return new IntFlow(origin, ending, count, () -> {
            long item = pull.next();
            return ending.reached ? 0 : mapper.applyAsInt(item);
        });
    }

    public DoubleStream mapToDouble(LongToDoubleFunction mapper) {
        given(mapper);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return new DoubleFlow(origin, ending, count, () -> {
            long item = pull.next();
            return ending.reached ? 0 : mapper.applyAsDouble(item);
        });
    }

    public LongStream flatMap(LongFunction<? extends LongStream> mapper) {
        given(mapper);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        Ending flat = new Ending();
        Held<LongFlow> inner = new Held<>();
        within(origin, inner);
        return new LongFlow(origin, flat, null, () -> {
            for (;;) {
                LongFlow in = inner.value;
                if (in != null) {
                    long item = in.pull.next();
                    if (!in.ending.reached) {
                        return item;
                    }
                    inner.value = null;
                    in.close();
                }
                long item = pull.next();
                if (ending.reached) {
                    flat.reached = true;
                    return 0;
                }
                LongStream mapped = mapper.apply(item);
                if (mapped != null) {
                    inner.value = entered(mapped);
                }
            }
        });
    }

    // The elements a function hands a consumer each time it is asked for more: what it
    // hands over in one call is held until the stages after it ask. The function says
    // through the ending that it has no more.
    static LongFlow fed(Origin origin, Ending ending, Consumer<LongConsumer> more) {
        Held<long[]> held = new Held<>();
        held.value = new long[8];
        int[] ends = new int[2];
        LongConsumer take = item -> {
            long[] all = held.value;
            int size = ends[1];
            if (size == all.length) {
                all = Arrays.copyOf(all, size * 2);
                held.value = all;
            }
            all[size] = item;
            ends[1] = size + 1;
        };
        return new LongFlow(origin, ending, null, () -> {
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

    public LongStream mapMulti(LongStream.LongMapMultiConsumer mapper) {
        given(mapper);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return fed(origin, ending, to -> {
            long item = pull.next();
            if (!ending.reached) {
                mapper.accept(item, to);
            }
        });
    }

    public LongStream distinct() {
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        HashSet<Long> met = new HashSet<>();
        return new LongFlow(origin, ending, null, () -> {
            for (;;) {
                long item = pull.next();
                if (ending.reached || met.add(item)) {
                    return item;
                }
            }
        });
    }

    // every element is taken when the first is asked for
    public LongStream sorted() {
        link();
        Ending sorted = new Ending();
        Held<long[]> held = new Held<>();
        int[] at = new int[1];
        return new LongFlow(origin, sorted, count, () -> {
            long[] all = held.value;
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

    private long[] taken() {
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long[] all = new long[16];
        int size = 0;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            if (size == all.length) {
                all = Arrays.copyOf(all, size * 2);
            }
            all[size++] = item;
        }
        return Arrays.copyOf(all, size);
    }

    public LongStream peek(LongConsumer action) {
        given(action);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return new LongFlow(origin, ending, count, () -> {
            long item = pull.next();
            if (!ending.reached) {
                action.accept(item);
            }
            return item;
        });
    }

    public LongStream limit(long maxSize) {
        atLeastZero(maxSize);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long[] left = { maxSize };
        Told before = origin.told;
        return new LongFlow(origin, ending, most(count, maxSize), () -> {
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

    public LongStream skip(long n) {
        atLeastZero(n);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long[] left = { n };
        return new LongFlow(origin, ending, less(count, n), () -> {
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

    public LongStream takeWhile(LongPredicate predicate) {
        given(predicate);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        Told before = origin.told;
        return new LongFlow(origin, ending, null, () -> {
            long item = pull.next();
            if (!ending.reached && !predicate.test(item)) {
                ending.reached = true;
                leave(before);
            }
            return item;
        });
    }

    public LongStream dropWhile(LongPredicate predicate) {
        given(predicate);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        boolean[] passed = new boolean[1];
        return new LongFlow(origin, ending, null, () -> {
            long item = pull.next();
            if (!passed[0]) {
                while (!ending.reached && predicate.test(item)) {
                    item = pull.next();
                }
                passed[0] = true;
            }
            return item;
        });
    }

    public void forEach(LongConsumer action) {
        given(action);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            action.accept(item);
        }
    }

    public void forEachOrdered(LongConsumer action) {
        forEach(action);
    }

    public long[] toArray() {
        link();
        return taken();
    }

    public long reduce(long identity, LongBinaryOperator op) {
        given(op);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long result = identity;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            result = op.applyAsLong(result, item);
        }
        return result;
    }

    public OptionalLong reduce(LongBinaryOperator op) {
        given(op);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long result = pull.next();
        if (ending.reached) {
            return OptionalLong.empty();
        }
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            result = op.applyAsLong(result, item);
        }
        return OptionalLong.of(result);
    }

    public <R> R collect(Supplier<R> supplier, ObjLongConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        given(supplier);
        given(accumulator);
        given(combiner);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        R result = supplier.get();
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            accumulator.accept(result, item);
        }
        return result;
    }

    public long sum() {
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long sum = 0;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            sum += item;
        }
        return sum;
    }

    public OptionalLong min() {
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long least = pull.next();
        if (ending.reached) {
            return OptionalLong.empty();
        }
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            if (item < least) {
                least = item;
            }
        }
        return OptionalLong.of(least);
    }

    public OptionalLong max() {
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long most = pull.next();
        if (ending.reached) {
            return OptionalLong.empty();
        }
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            if (item > most) {
                most = item;
            }
        }
        return OptionalLong.of(most);
    }

    public long count() {
        link();
        if (count != null) {
            return count.now();
        }
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long n = 0;
        for (pull.next(); !ending.reached; pull.next()) {
            n++;
        }
        return n;
    }

    public OptionalDouble average() {
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        long sum = 0;
        long n = 0;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            sum += item;
            n++;
        }
        return n == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) sum / n);
    }

    public LongSummaryStatistics summaryStatistics() {
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        LongSummaryStatistics all = new LongSummaryStatistics();
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            all.accept(item);
        }
        return all;
    }

    public boolean anyMatch(LongPredicate predicate) {
        given(predicate);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                leave(origin.told);
                return true;
            }
        }
        return false;
    }

    public boolean allMatch(LongPredicate predicate) {
        given(predicate);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            if (!predicate.test(item)) {
                leave(origin.told);
                return false;
            }
        }
        return true;
    }

    public boolean noneMatch(LongPredicate predicate) {
        given(predicate);
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        for (long item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                leave(origin.told);
                return false;
            }
        }
        return true;
    }

    public OptionalLong findFirst() {
        link();
        long item = pull.next();
        if (ending.reached) {
            return OptionalLong.empty();
        }
        leave(origin.told);
        return OptionalLong.of(item);
    }

    public OptionalLong findAny() {
        return findFirst();
    }

    public DoubleStream asDoubleStream() {
        link();
        LongPull pull = this.pull;
        return new DoubleFlow(origin, ending, count, () -> pull.next());
    }

    public Stream<Long> boxed() {
        link();
        LongPull pull = this.pull;
        return new ObjectFlow<Long>(origin, ending, count, () -> pull.next());
    }

    // the elements an iterator gives, counted where a count is given
    public static LongFlow over(PrimitiveIterator.OfLong each, Count count) {
        Ending ending = new Ending();
        return new LongFlow(new Origin(), ending, count, () -> {
            if (each.hasNext()) {
                return each.nextLong();
            }
            ending.reached = true;
            return 0;
        });
    }

    // the elements a spliterator gives: the supplier is asked for it at the first pull, or
    // at the count where the characteristics say SIZED
    public static LongFlow split(Supplier<? extends Spliterator.OfLong> supplier, int characteristics,
            boolean parallel) {
        Ending ending = new Ending();
        Held<Spliterator.OfLong> source = new Held<>();
        long[] taken = new long[1];
        LongConsumer take = item -> taken[0] = item;
        Count count = (characteristics & Spliterator.SIZED) == 0 ? null
                : () -> supplied(source, supplier).getExactSizeIfKnown();
        LongFlow flow = new LongFlow(new Origin(), ending, count, () -> {
            if (supplied(source, supplier).tryAdvance(take)) {
                return taken[0];
            }
            ending.reached = true;
            return 0;
        });
        flow.origin.parallel = parallel;
        return flow;
    }

    private static Spliterator.OfLong supplied(Held<Spliterator.OfLong> source,
            Supplier<? extends Spliterator.OfLong> supplier) {
        Spliterator.OfLong spliterator = source.value;
        if (spliterator == null) {
            spliterator = supplier.get();
            source.value = spliterator;
        }
        return spliterator;
    }

    // The spliterator of an iterator's numbers. A size given is reported, and stays what
    // it is while the numbers are taken; it is the exact one where the marks say SIZED.
    public static Spliterator.OfLong walk(PrimitiveIterator.OfLong each, long size, int marks) {
        Count given = size < 0 ? null : () -> size;
        return new Walk(over(each, null), (marks & Spliterator.SIZED) == 0 ? null : given, given, marks, false);
    }

    // the spliterator of an array's numbers, reporting the marks given
    public static Spliterator.OfLong walk(long[] array, int from, int to, int marks) {
        LongFlow flow = of(array, from, to);
        return new Walk(flow, flow.count, null, marks, true);
    }

    public Spliterator.OfLong spliterator() {
        link();
        return new Walk(this, count, null, marks(), true);
    }

    private static final class Walk extends Walked<Long> implements Spliterator.OfLong {

        private final LongPull pull;

        private final Ending ending;

        Walk(LongFlow flow, Count sized, Count about, int marks, boolean counted) {
            super(sized, about, marks, counted);
            this.pull = flow.pull;
            this.ending = flow.ending;
        }

        public boolean tryAdvance(LongConsumer action) {
            given(action);
            long item = pull.next();
            if (!took(ending)) {
                return false;
            }
            action.accept(item);
            return true;
        }

        public Spliterator.OfLong trySplit() {
            return null;
        }
    }

    public PrimitiveIterator.OfLong iterator() {
        link();
        LongPull pull = this.pull;
        Ending ending = this.ending;
        return new PrimitiveIterator.OfLong() {
            private boolean ready;

            private long item;

            public boolean hasNext() {
                if (!ready && !ending.reached) {
                    item = pull.next();
                    ready = true;
                }
                return !ending.reached;
            }

            public long nextLong() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return item;
            }
        };
    }
}
