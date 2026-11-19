// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.DoubleSummaryStatistics;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.PrimitiveIterator;
import java.util.function.BiConsumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoublePredicate;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleToLongFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

// A stream of doubles: the one class behind java.util.stream.DoubleStream (Flow says how a
// stream hands its elements on).
public final class DoubleFlow extends Flow implements DoubleStream {

    final DoublePull pull;

    DoubleFlow(Origin origin, Ending ending, Count count, DoublePull pull) {
        super(origin, ending, count);
        this.pull = pull;
    }

    public static final class Building implements DoubleStream.Builder {

        private double[] added = new double[8];

        private int size;

        public void accept(double t) {
            if (added == null) {
                throw new IllegalStateException();
            }
            if (size == added.length) {
                added = Arrays.copyOf(added, size * 2);
            }
            added[size++] = t;
        }

        public DoubleStream build() {
            double[] all = added;
            if (all == null) {
                throw new IllegalStateException();
            }
            added = null;
            return of(all, 0, size);
        }
    }

    public static DoubleFlow of(double[] array, int from, int to) {
        Ending ending = new Ending();
        int[] at = { from };
        long length = to - from;
        return new DoubleFlow(new Origin(), ending, () -> length, () -> {
            int i = at[0];
            if (i < to) {
                at[0] = i + 1;
                return array[i];
            }
            ending.reached = true;
            return 0;
        });
    }

    public static DoubleFlow iterated(double seed, DoublePredicate more, DoubleUnaryOperator step) {
        given(step);
        Ending ending = new Ending();
        double[] last = new double[1];
        boolean[] started = new boolean[1];
        return new DoubleFlow(new Origin(), ending, null, () -> {
            double item = started[0] ? step.applyAsDouble(last[0]) : seed;
            started[0] = true;
            if (more != null && !more.test(item)) {
                ending.reached = true;
                return 0;
            }
            last[0] = item;
            return item;
        });
    }

    public static DoubleFlow generated(DoubleSupplier supplier) {
        given(supplier);
        return new DoubleFlow(new Origin(), new Ending(), null, () -> supplier.getAsDouble());
    }

    public static DoubleFlow concat(DoubleStream a, DoubleStream b) {
        DoubleFlow first = entered(a);
        DoubleFlow second = entered(b);
        DoublePull head = first.pull;
        DoublePull tail = second.pull;
        Ending headEnding = first.ending;
        boolean[] passed = new boolean[1];
        return new DoubleFlow(joined(first, second), second.ending, sum(first.count, second.count), () -> {
            if (!passed[0]) {
                double item = head.next();
                if (!headEnding.reached) {
                    return item;
                }
                passed[0] = true;
            }
            return tail.next();
        });
    }

    // a stream another stream reads: it is asked here, once
    static DoubleFlow entered(DoubleStream stream) {
        DoubleFlow flow = (DoubleFlow) stream;
        flow.link();
        return flow;
    }

    public DoubleStream sequential() {
        origin.parallel = false;
        return this;
    }

    public DoubleStream parallel() {
        origin.parallel = true;
        return this;
    }

    public DoubleStream unordered() {
        link();
        return new DoubleFlow(origin, ending, count, pull);
    }

    public DoubleStream onClose(Runnable handler) {
        closing(handler);
        return this;
    }

    public DoubleStream filter(DoublePredicate predicate) {
        given(predicate);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new DoubleFlow(origin, ending, null, () -> {
            for (;;) {
                double item = pull.next();
                if (ending.reached || predicate.test(item)) {
                    return item;
                }
            }
        });
    }

    public DoubleStream map(DoubleUnaryOperator mapper) {
        given(mapper);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new DoubleFlow(origin, ending, count, () -> {
            double item = pull.next();
            return ending.reached ? 0 : mapper.applyAsDouble(item);
        });
    }

    public <U> Stream<U> mapToObj(DoubleFunction<? extends U> mapper) {
        given(mapper);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new ObjectFlow<U>(origin, ending, count, () -> {
            double item = pull.next();
            return ending.reached ? null : mapper.apply(item);
        });
    }

    public IntStream mapToInt(DoubleToIntFunction mapper) {
        given(mapper);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new IntFlow(origin, ending, count, () -> {
            double item = pull.next();
            return ending.reached ? 0 : mapper.applyAsInt(item);
        });
    }

    public LongStream mapToLong(DoubleToLongFunction mapper) {
        given(mapper);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new LongFlow(origin, ending, count, () -> {
            double item = pull.next();
            return ending.reached ? 0 : mapper.applyAsLong(item);
        });
    }

    public DoubleStream flatMap(DoubleFunction<? extends DoubleStream> mapper) {
        given(mapper);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        Ending flat = new Ending();
        Held<DoubleFlow> inner = new Held<>();
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
                double item = pull.next();
                if (ending.reached) {
                    flat.reached = true;
                    return 0;
                }
                DoubleStream mapped = mapper.apply(item);
                if (mapped != null) {
                    inner.value = entered(mapped);
                }
            }
        });
    }

    public DoubleStream distinct() {
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        HashSet<Double> met = new HashSet<>();
        return new DoubleFlow(origin, ending, null, () -> {
            for (;;) {
                double item = pull.next();
                if (ending.reached || met.add(item)) {
                    return item;
                }
            }
        });
    }

    // every element is taken when the first is asked for
    public DoubleStream sorted() {
        link();
        Ending sorted = new Ending();
        Held<double[]> held = new Held<>();
        int[] at = new int[1];
        return new DoubleFlow(origin, sorted, count, () -> {
            double[] all = held.value;
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

    private double[] taken() {
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        double[] all = new double[16];
        int size = 0;
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            if (size == all.length) {
                all = Arrays.copyOf(all, size * 2);
            }
            all[size++] = item;
        }
        return Arrays.copyOf(all, size);
    }

    public DoubleStream peek(DoubleConsumer action) {
        given(action);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new DoubleFlow(origin, ending, count, () -> {
            double item = pull.next();
            if (!ending.reached) {
                action.accept(item);
            }
            return item;
        });
    }

    public DoubleStream limit(long maxSize) {
        atLeastZero(maxSize);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        long[] left = { maxSize };
        return new DoubleFlow(origin, ending, most(count, maxSize), () -> {
            long n = left[0];
            if (n == 0) {
                ending.reached = true;
                return 0;
            }
            left[0] = n - 1;
            return pull.next();
        });
    }

    public DoubleStream skip(long n) {
        atLeastZero(n);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        long[] left = { n };
        return new DoubleFlow(origin, ending, less(count, n), () -> {
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

    public DoubleStream takeWhile(DoublePredicate predicate) {
        given(predicate);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new DoubleFlow(origin, ending, null, () -> {
            double item = pull.next();
            if (!ending.reached && !predicate.test(item)) {
                ending.reached = true;
            }
            return item;
        });
    }

    public DoubleStream dropWhile(DoublePredicate predicate) {
        given(predicate);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        boolean[] passed = new boolean[1];
        return new DoubleFlow(origin, ending, null, () -> {
            double item = pull.next();
            if (!passed[0]) {
                while (!ending.reached && predicate.test(item)) {
                    item = pull.next();
                }
                passed[0] = true;
            }
            return item;
        });
    }

    public void forEach(DoubleConsumer action) {
        given(action);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            action.accept(item);
        }
    }

    public void forEachOrdered(DoubleConsumer action) {
        forEach(action);
    }

    public double[] toArray() {
        link();
        return taken();
    }

    public double reduce(double identity, DoubleBinaryOperator op) {
        given(op);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        double result = identity;
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            result = op.applyAsDouble(result, item);
        }
        return result;
    }

    public OptionalDouble reduce(DoubleBinaryOperator op) {
        given(op);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        double result = pull.next();
        if (ending.reached) {
            return OptionalDouble.empty();
        }
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            result = op.applyAsDouble(result, item);
        }
        return OptionalDouble.of(result);
    }

    public <R> R collect(Supplier<R> supplier, ObjDoubleConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        given(supplier);
        given(accumulator);
        given(combiner);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        R result = supplier.get();
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            accumulator.accept(result, item);
        }
        return result;
    }

    public double sum() {
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        double[] sum = new double[3];
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            add(sum, item);
        }
        return total(sum);
    }

    public OptionalDouble min() {
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        double least = pull.next();
        if (ending.reached) {
            return OptionalDouble.empty();
        }
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            least = Math.min(least, item);
        }
        return OptionalDouble.of(least);
    }

    public OptionalDouble max() {
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        double most = pull.next();
        if (ending.reached) {
            return OptionalDouble.empty();
        }
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            most = Math.max(most, item);
        }
        return OptionalDouble.of(most);
    }

    public long count() {
        link();
        if (count != null) {
            return count.now();
        }
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        long n = 0;
        for (pull.next(); !ending.reached; pull.next()) {
            n++;
        }
        return n;
    }

    public OptionalDouble average() {
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        double[] sum = new double[3];
        long n = 0;
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            add(sum, item);
            n++;
        }
        return n == 0 ? OptionalDouble.empty() : OptionalDouble.of(total(sum) / n);
    }

    public DoubleSummaryStatistics summaryStatistics() {
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        DoubleSummaryStatistics all = new DoubleSummaryStatistics();
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            all.accept(item);
        }
        return all;
    }

    public boolean anyMatch(DoublePredicate predicate) {
        given(predicate);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean allMatch(DoublePredicate predicate) {
        given(predicate);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            if (!predicate.test(item)) {
                return false;
            }
        }
        return true;
    }

    public boolean noneMatch(DoublePredicate predicate) {
        given(predicate);
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        for (double item = pull.next(); !ending.reached; item = pull.next()) {
            if (predicate.test(item)) {
                return false;
            }
        }
        return true;
    }

    public OptionalDouble findFirst() {
        link();
        double item = pull.next();
        return ending.reached ? OptionalDouble.empty() : OptionalDouble.of(item);
    }

    public OptionalDouble findAny() {
        return findFirst();
    }

    public Stream<Double> boxed() {
        link();
        DoublePull pull = this.pull;
        return new ObjectFlow<Double>(origin, ending, count, () -> pull.next());
    }

    public PrimitiveIterator.OfDouble iterator() {
        link();
        DoublePull pull = this.pull;
        Ending ending = this.ending;
        return new PrimitiveIterator.OfDouble() {
            private boolean ready;

            private double item;

            public boolean hasNext() {
                if (!ready && !ending.reached) {
                    item = pull.next();
                    ready = true;
                }
                return !ending.reached;
            }

            public double nextDouble() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                ready = false;
                return item;
            }
        };
    }
}
