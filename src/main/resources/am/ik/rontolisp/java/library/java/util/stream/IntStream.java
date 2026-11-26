// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.IntSummaryStatistics;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.BiConsumer;
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
import rontolisp.internal.IntFlow;

public interface IntStream extends BaseStream<Integer, IntStream> {

    interface Builder extends IntConsumer {

        void accept(int t);

        default Builder add(int t) {
            accept(t);
            return this;
        }

        IntStream build();
    }

    interface IntMapMultiConsumer {

        void accept(int value, IntConsumer consumer);
    }

    IntStream filter(IntPredicate predicate);

    IntStream map(IntUnaryOperator mapper);

    <U> Stream<U> mapToObj(IntFunction<? extends U> mapper);

    LongStream mapToLong(IntToLongFunction mapper);

    DoubleStream mapToDouble(IntToDoubleFunction mapper);

    IntStream flatMap(IntFunction<? extends IntStream> mapper);

    IntStream mapMulti(IntMapMultiConsumer mapper);

    IntStream distinct();

    IntStream sorted();

    IntStream peek(IntConsumer action);

    IntStream limit(long maxSize);

    IntStream skip(long n);

    IntStream takeWhile(IntPredicate predicate);

    IntStream dropWhile(IntPredicate predicate);

    void forEach(IntConsumer action);

    void forEachOrdered(IntConsumer action);

    int[] toArray();

    int reduce(int identity, IntBinaryOperator op);

    OptionalInt reduce(IntBinaryOperator op);

    <R> R collect(Supplier<R> supplier, ObjIntConsumer<R> accumulator, BiConsumer<R, R> combiner);

    int sum();

    OptionalInt min();

    OptionalInt max();

    long count();

    OptionalDouble average();

    IntSummaryStatistics summaryStatistics();

    boolean anyMatch(IntPredicate predicate);

    boolean allMatch(IntPredicate predicate);

    boolean noneMatch(IntPredicate predicate);

    OptionalInt findFirst();

    OptionalInt findAny();

    LongStream asLongStream();

    DoubleStream asDoubleStream();

    Stream<Integer> boxed();

    PrimitiveIterator.OfInt iterator();

    Spliterator.OfInt spliterator();

    static Builder builder() {
        return new IntFlow.Building();
    }

    static IntStream empty() {
        return IntFlow.of(new int[0], 0, 0);
    }

    static IntStream of(int t) {
        return IntFlow.of(new int[] { t }, 0, 1);
    }

    static IntStream of(int... values) {
        return IntFlow.of(values, 0, values.length);
    }

    static IntStream iterate(int seed, IntUnaryOperator f) {
        return IntFlow.iterated(seed, null, f);
    }

    static IntStream iterate(int seed, IntPredicate hasNext, IntUnaryOperator next) {
        if (hasNext == null) {
            throw new NullPointerException();
        }
        return IntFlow.iterated(seed, hasNext, next);
    }

    static IntStream generate(IntSupplier s) {
        return IntFlow.generated(s);
    }

    static IntStream range(int startInclusive, int endExclusive) {
        return IntFlow.range(startInclusive, (long) endExclusive - 1);
    }

    static IntStream rangeClosed(int startInclusive, int endInclusive) {
        return IntFlow.range(startInclusive, endInclusive);
    }

    static IntStream concat(IntStream a, IntStream b) {
        return IntFlow.concat(a, b);
    }
}
