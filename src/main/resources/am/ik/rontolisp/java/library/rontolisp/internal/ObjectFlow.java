// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.Gatherer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

// A stream of objects: the one class behind java.util.stream.Stream (Flow says how a
// stream hands its elements on).
public final class ObjectFlow<T> extends Flow implements Stream<T> {

    final Pull<T> pull;

    // the gatherer this stream is the stage of, where it is one
    Gathering<?, ?, T> gathering;

    ObjectFlow(Origin origin, Ending ending, Count count, Pull<T> pull) {
        super(origin, ending, count);
        this.pull = pull;
    }

    public static final class Building<T> implements Stream.Builder<T> {

        private ArrayList<T> added = new ArrayList<>();

        public void accept(T t) {
            if (added == null) {
                throw new IllegalStateException();
            }
            added.add(t);
        }

        public Stream<T> build() {
            ArrayList<T> all = added;
            if (all == null) {
                throw new IllegalStateException();
            }
            added = null;
            return over(all, () -> all.size());
        }
    }

    public static <T> ObjectFlow<T> none() {
        Ending ending = new Ending();
        return new ObjectFlow<T>(new Origin(), ending, () -> 0, () -> {
            ending.reached = true;
            return null;
        });
    }

    public static <T> ObjectFlow<T> one(T only) {
        Ending ending = new Ending();
        boolean[] given = new boolean[1];
        return new ObjectFlow<T>(new Origin(), ending, () -> 1, () -> {
            if (given[0]) {
                ending.reached = true;
                return null;
            }
            given[0] = true;
            return only;
        });
    }

    // the elements an Iterable gives when the stream is first asked for one
    public static <T> ObjectFlow<T> over(Iterable<? extends T> source, Count count) {
        Ending ending = new Ending();
        Held<Iterator<? extends T>> held = new Held<>();
        return new ObjectFlow<T>(new Origin(), ending, count, () -> {
            Iterator<? extends T> each = held.value;
            if (each == null) {
                each = source.iterator();
                held.value = each;
            }
            if (each.hasNext()) {
                return each.next();
            }
            ending.reached = true;
            return null;
        });
    }

    public static <T> ObjectFlow<T> over(Iterator<? extends T> each) {
        Ending ending = new Ending();
        return new ObjectFlow<T>(new Origin(), ending, null, () -> {
            if (each.hasNext()) {
                return each.next();
            }
            ending.reached = true;
            return null;
        });
    }

    // the elements a spliterator gives: the supplier is asked for it at the first pull, or
    // at the count where the characteristics say SIZED
    public static <T> ObjectFlow<T> split(Supplier<? extends Spliterator<T>> supplier, int characteristics,
            boolean parallel) {
        Ending ending = new Ending();
        Held<Spliterator<T>> source = new Held<>();
        Held<T> taken = new Held<>();
        Consumer<T> take = item -> taken.value = item;
        Count count = (characteristics & Spliterator.SIZED) == 0 ? null
                : () -> supplied(source, supplier).getExactSizeIfKnown();
        ObjectFlow<T> flow = new ObjectFlow<T>(new Origin(), ending, count, () -> {
            if (supplied(source, supplier).tryAdvance(take)) {
                T item = taken.value;
                taken.value = null;
                return item;
            }
            ending.reached = true;
            return null;
        });
        flow.origin.parallel = parallel;
        return flow;
    }

    private static <T> Spliterator<T> supplied(Held<Spliterator<T>> source,
            Supplier<? extends Spliterator<T>> supplier) {
        Spliterator<T> spliterator = source.value;
        if (spliterator == null) {
            spliterator = supplier.get();
            source.value = spliterator;
        }
        return spliterator;
    }

    public static <T> ObjectFlow<T> of(T[] array, int from, int to) {
        Ending ending = new Ending();
        int[] at = { from };
        long length = to - from;
        return new ObjectFlow<T>(new Origin(), ending, () -> length, () -> {
            int i = at[0];
            if (i < to) {
                at[0] = i + 1;
                return array[i];
            }
            ending.reached = true;
            return null;
        });
    }

    public static <T> ObjectFlow<T> iterated(T seed, Predicate<? super T> more, UnaryOperator<T> step) {
        given(step);
        Ending ending = new Ending();
        Held<T> last = new Held<>();
        boolean[] started = new boolean[1];
        return new ObjectFlow<T>(new Origin(), ending, null, () -> {
            T item = started[0] ? step.apply(last.value) : seed;
            started[0] = true;
            if (more != null && !more.test(item)) {
                ending.reached = true;
                return null;
            }
            last.value = item;
            return item;
        });
    }

    public static <T> ObjectFlow<T> generated(Supplier<? extends T> supplier) {
        given(supplier);
        return new ObjectFlow<T>(new Origin(), new Ending(), null, () -> supplier.get());
    }

    public static <T> ObjectFlow<T> concat(Stream<? extends T> a, Stream<? extends T> b) {
        ObjectFlow<? extends T> first = entered(a);
        ObjectFlow<? extends T> second = entered(b);
        Pull<? extends T> head = first.pull;
        Pull<? extends T> tail = second.pull;
        Ending headEnding = first.ending;
        boolean[] passed = new boolean[1];
        return new ObjectFlow<T>(joined(first, second), second.ending, sum(first.count, second.count), () -> {
            if (!passed[0]) {
                T item = head.next();
                if (!headEnding.reached) {
                    return item;
                }
                passed[0] = true;
            }
            return tail.next();
        });
    }

    // a stream another stream reads: it is asked here, once
    static <T> ObjectFlow<T> entered(Stream<T> stream) {
        ObjectFlow<T> flow = (ObjectFlow<T>) stream;
        flow.link();
        return flow;
    }

    public Stream<T> sequential() {
        origin.parallel = false;
        return this;
    }

    public Stream<T> parallel() {
        origin.parallel = true;
        return this;
    }

    public Stream<T> unordered() {
        link();
        return new ObjectFlow<T>(origin, ending, count, pull);
    }

    public Stream<T> onClose(Runnable handler) {
        closing(handler);
        return this;
    }

    public Stream<T> filter(Predicate<? super T> predicate) {
        given(predicate);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return new ObjectFlow<T>(origin, ending, null, () -> {
            for (;;) {
                T item = pull.next();
                if (ending.reached || predicate.test(item)) {
                    return item;
                }
            }
        });
    }

    public <R> Stream<R> map(Function<? super T, ? extends R> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return new ObjectFlow<R>(origin, ending, count, () -> {
            T item = pull.next();
            return ending.reached ? null : mapper.apply(item);
        });
    }

    public IntStream mapToInt(ToIntFunction<? super T> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return new IntFlow(origin, ending, count, () -> {
            T item = pull.next();
            return ending.reached ? 0 : mapper.applyAsInt(item);
        });
    }

    public LongStream mapToLong(ToLongFunction<? super T> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return new LongFlow(origin, ending, count, () -> {
            T item = pull.next();
            return ending.reached ? 0 : mapper.applyAsLong(item);
        });
    }

    public DoubleStream mapToDouble(ToDoubleFunction<? super T> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return new DoubleFlow(origin, ending, count, () -> {
            T item = pull.next();
            return ending.reached ? 0 : mapper.applyAsDouble(item);
        });
    }

    public <R> Stream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        Ending flat = new Ending();
        Held<ObjectFlow<? extends R>> inner = new Held<>();
        within(origin, inner);
        return new ObjectFlow<R>(origin, flat, null, () -> {
            for (;;) {
                ObjectFlow<? extends R> in = inner.value;
                if (in != null) {
                    R item = in.pull.next();
                    if (!in.ending.reached) {
                        return item;
                    }
                    inner.value = null;
                    in.close();
                }
                T item = pull.next();
                if (ending.reached) {
                    flat.reached = true;
                    return null;
                }
                Stream<? extends R> mapped = mapper.apply(item);
                if (mapped != null) {
                    inner.value = entered(mapped);
                }
            }
        });
    }

    public IntStream flatMapToInt(Function<? super T, ? extends IntStream> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
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
                T item = pull.next();
                if (ending.reached) {
                    flat.reached = true;
                    return 0;
                }
                IntStream mapped = mapper.apply(item);
                if (mapped != null) {
                    inner.value = IntFlow.entered(mapped);
                }
            }
        });
    }

    public LongStream flatMapToLong(Function<? super T, ? extends LongStream> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
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
                T item = pull.next();
                if (ending.reached) {
                    flat.reached = true;
                    return 0;
                }
                LongStream mapped = mapper.apply(item);
                if (mapped != null) {
                    inner.value = LongFlow.entered(mapped);
                }
            }
        });
    }

    public DoubleStream flatMapToDouble(Function<? super T, ? extends DoubleStream> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        Ending flat = new Ending();
        Held<DoubleFlow> inner = new Held<>();
        within(origin, inner);
        return new DoubleFlow(origin, flat, null, () -> {
            for (;;) {
                DoubleFlow in = inner.value;
                if (in != null) {
                    double item = in.pull.next();
                    if (!in.ending.reached) {
                        return item;
                    }
                    inner.value = null;
                    in.close();
                }
                T item = pull.next();
                if (ending.reached) {
                    flat.reached = true;
                    return 0;
                }
                DoubleStream mapped = mapper.apply(item);
                if (mapped != null) {
                    inner.value = DoubleFlow.entered(mapped);
                }
            }
        });
    }

    // what the function hands over for one element is held until the stages after it ask
    public <R> Stream<R> mapMulti(BiConsumer<? super T, ? super Consumer<R>> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        ArrayList<R> held = new ArrayList<>();
        int[] at = new int[1];
        Consumer<R> take = item -> held.add(item);
        return new ObjectFlow<R>(origin, ending, null, () -> {
            for (;;) {
                int i = at[0];
                if (i < held.size()) {
                    at[0] = i + 1;
                    return held.get(i);
                }
                held.clear();
                at[0] = 0;
                T item = pull.next();
                if (ending.reached) {
                    return null;
                }
                mapper.accept(item, take);
            }
        });
    }

    public IntStream mapMultiToInt(BiConsumer<? super T, ? super IntConsumer> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return IntFlow.fed(origin, ending, to -> {
            T item = pull.next();
            if (!ending.reached) {
                mapper.accept(item, to);
            }
        });
    }

    public LongStream mapMultiToLong(BiConsumer<? super T, ? super LongConsumer> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return LongFlow.fed(origin, ending, to -> {
            T item = pull.next();
            if (!ending.reached) {
                mapper.accept(item, to);
            }
        });
    }

    public DoubleStream mapMultiToDouble(BiConsumer<? super T, ? super DoubleConsumer> mapper) {
        given(mapper);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return DoubleFlow.fed(origin, ending, to -> {
            T item = pull.next();
            if (!ending.reached) {
                mapper.accept(item, to);
            }
        });
    }

    // a gatherer straight after a gatherer is one gatherer with it
    @SuppressWarnings("unchecked")
    public <R> Stream<R> gather(Gatherer<? super T, ?, R> gatherer) {
        given(gatherer);
        link();
        Gathering<?, ?, T> stage = gathering;
        return stage != null ? stage.then(origin, gatherer)
                : Gathering.over(origin, pull, ending, (Gatherer<T, Object, R>) gatherer);
    }

    public Stream<T> distinct() {
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        HashSet<T> met = new HashSet<>();
        return new ObjectFlow<T>(origin, ending, null, () -> {
            for (;;) {
                T item = pull.next();
                if (ending.reached || met.add(item)) {
                    return item;
                }
            }
        });
    }

    public Stream<T> sorted() {
        return inOrder(null);
    }

    public Stream<T> sorted(Comparator<? super T> comparator) {
        given(comparator);
        return inOrder(comparator);
    }

    // every element is taken when the first is asked for
    private Stream<T> inOrder(Comparator<? super T> order) {
        link();
        Ending sorted = new Ending();
        Held<ArrayList<T>> held = new Held<>();
        int[] at = new int[1];
        return new ObjectFlow<T>(origin, sorted, count, () -> {
            ArrayList<T> all = held.value;
            if (all == null) {
                all = taken();
                all.sort(order);
                held.value = all;
            }
            int i = at[0];
            if (i < all.size()) {
                at[0] = i + 1;
                return all.get(i);
            }
            sorted.reached = true;
            return null;
        });
    }

    private ArrayList<T> taken() {
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        ArrayList<T> all = new ArrayList<>();
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            all.add(item);
        }
        return all;
    }

    public Stream<T> peek(Consumer<? super T> action) {
        given(action);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return new ObjectFlow<T>(origin, ending, count, () -> {
            T item = pull.next();
            if (!ending.reached) {
                action.accept(item);
            }
            return item;
        });
    }

    public Stream<T> limit(long maxSize) {
        atLeastZero(maxSize);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        long[] left = { maxSize };
        Told before = origin.told;
        return new ObjectFlow<T>(origin, ending, most(count, maxSize), () -> {
            long n = left[0];
            if (n == 0) {
                ending.reached = true;
                leave(before);
                return null;
            }
            left[0] = n - 1;
            return pull.next();
        });
    }

    public Stream<T> skip(long n) {
        atLeastZero(n);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        long[] left = { n };
        return new ObjectFlow<T>(origin, ending, less(count, n), () -> {
            while (left[0] > 0) {
                left[0]--;
                pull.next();
                if (ending.reached) {
                    return null;
                }
            }
            return pull.next();
        });
    }

    public Stream<T> takeWhile(Predicate<? super T> predicate) {
        given(predicate);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        Told before = origin.told;
        return new ObjectFlow<T>(origin, ending, null, () -> {
            T item = pull.next();
            if (!ending.reached && !predicate.test(item)) {
                ending.reached = true;
                leave(before);
            }
            return item;
        });
    }

    public Stream<T> dropWhile(Predicate<? super T> predicate) {
        given(predicate);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        boolean[] passed = new boolean[1];
        return new ObjectFlow<T>(origin, ending, null, () -> {
            T item = pull.next();
            if (!passed[0]) {
                while (!ending.reached && predicate.test(item)) {
                    item = pull.next();
                }
                passed[0] = true;
            }
            return item;
        });
    }

    public void forEach(Consumer<? super T> action) {
        given(action);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            action.accept(item);
        }
    }

    public void forEachOrdered(Consumer<? super T> action) {
        forEach(action);
    }

    public Object[] toArray() {
        link();
        return taken().toArray();
    }

    public <A> A[] toArray(IntFunction<A[]> generator) {
        given(generator);
        link();
        ArrayList<T> all = taken();
        return all.toArray(generator.apply(all.size()));
    }

    public T reduce(T identity, BinaryOperator<T> accumulator) {
        given(accumulator);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        T result = identity;
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            result = accumulator.apply(result, item);
        }
        return result;
    }

    public Optional<T> reduce(BinaryOperator<T> accumulator) {
        given(accumulator);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        T result = pull.next();
        if (ending.reached) {
            return Optional.empty();
        }
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            result = accumulator.apply(result, item);
        }
        return Optional.of(result);
    }

    public <U> U reduce(U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
        given(accumulator);
        given(combiner);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        U result = identity;
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            result = accumulator.apply(result, item);
        }
        return result;
    }

    public <R> R collect(Supplier<R> supplier, BiConsumer<R, ? super T> accumulator, BiConsumer<R, R> combiner) {
        given(supplier);
        given(accumulator);
        given(combiner);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        R result = supplier.get();
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            accumulator.accept(result, item);
        }
        return result;
    }

    public <R, A> R collect(Collector<? super T, A, R> collector) {
        given(collector);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        A container = collector.supplier().get();
        BiConsumer<A, ? super T> accumulator = collector.accumulator();
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            accumulator.accept(container, item);
        }
        return collector.finisher().apply(container);
    }

    public List<T> toList() {
        link();
        return Collections.unmodifiableList(taken());
    }

    public Optional<T> min(Comparator<? super T> comparator) {
        given(comparator);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        T least = pull.next();
        if (ending.reached) {
            return Optional.empty();
        }
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            if (comparator.compare(item, least) < 0) {
                least = item;
            }
        }
        return Optional.of(least);
    }

    public Optional<T> max(Comparator<? super T> comparator) {
        given(comparator);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        T most = pull.next();
        if (ending.reached) {
            return Optional.empty();
        }
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            if (comparator.compare(item, most) > 0) {
                most = item;
            }
        }
        return Optional.of(most);
    }

    public long count() {
        link();
        if (count != null) {
            return count.now();
        }
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        long n = 0;
        for (pull.next(); !ending.reached; pull.next()) {
            n++;
        }
        return n;
    }

    public boolean anyMatch(Predicate<? super T> predicate) {
        given(predicate);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                leave(origin.told);
                return true;
            }
        }
        return false;
    }

    public boolean allMatch(Predicate<? super T> predicate) {
        given(predicate);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            if (!predicate.test(item)) {
                leave(origin.told);
                return false;
            }
        }
        return true;
    }

    public boolean noneMatch(Predicate<? super T> predicate) {
        given(predicate);
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        for (T item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                leave(origin.told);
                return false;
            }
        }
        return true;
    }

    public Optional<T> findFirst() {
        link();
        T item = pull.next();
        if (ending.reached) {
            return Optional.empty();
        }
        leave(origin.told);
        return Optional.of(item);
    }

    public Optional<T> findAny() {
        return findFirst();
    }

    public Spliterator<T> spliterator() {
        link();
        return new Walk<T>(this);
    }

    private static final class Walk<T> extends Walked<T> implements Spliterator<T> {

        private final Pull<T> pull;

        private final Ending ending;

        Walk(ObjectFlow<T> flow) {
            super(flow.count, null, flow.marks(), true);
            this.pull = flow.pull;
            this.ending = flow.ending;
        }

        public boolean tryAdvance(Consumer<? super T> action) {
            given(action);
            T item = pull.next();
            if (!took(ending)) {
                return false;
            }
            action.accept(item);
            return true;
        }

        public Spliterator<T> trySplit() {
            return null;
        }
    }

    public Iterator<T> iterator() {
        link();
        Pull<T> pull = this.pull;
        Ending ending = this.ending;
        return new Iterator<T>() {
            private boolean ready;

            private T item;

            public boolean hasNext() {
                if (!ready && !ending.reached) {
                    item = pull.next();
                    ready = true;
                }
                return !ending.reached;
            }

            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return item;
            }
        };
    }
}
