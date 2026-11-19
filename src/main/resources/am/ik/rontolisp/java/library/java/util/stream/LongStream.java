// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.LongSummaryStatistics;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.PrimitiveIterator;
import java.util.function.BiConsumer;
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
import rontolisp.internal.LongFlow;

public interface LongStream extends BaseStream<Long, LongStream> {

    interface Builder extends LongConsumer {

        void accept(long t);

        default Builder add(long t) {
            accept(t);
            return this;
        }

        LongStream build();
    }

    LongStream filter(LongPredicate predicate);

    LongStream map(LongUnaryOperator mapper);

    <U> Stream<U> mapToObj(LongFunction<? extends U> mapper);

    IntStream mapToInt(LongToIntFunction mapper);

    DoubleStream mapToDouble(LongToDoubleFunction mapper);

    LongStream flatMap(LongFunction<? extends LongStream> mapper);

    LongStream distinct();

    LongStream sorted();

    LongStream peek(LongConsumer action);

    LongStream limit(long maxSize);

    LongStream skip(long n);

    LongStream takeWhile(LongPredicate predicate);

    LongStream dropWhile(LongPredicate predicate);

    void forEach(LongConsumer action);

    void forEachOrdered(LongConsumer action);

    long[] toArray();

    long reduce(long identity, LongBinaryOperator op);

    OptionalLong reduce(LongBinaryOperator op);

    <R> R collect(Supplier<R> supplier, ObjLongConsumer<R> accumulator, BiConsumer<R, R> combiner);

    long sum();

    OptionalLong min();

    OptionalLong max();

    long count();

    OptionalDouble average();

    LongSummaryStatistics summaryStatistics();

    boolean anyMatch(LongPredicate predicate);

    boolean allMatch(LongPredicate predicate);

    boolean noneMatch(LongPredicate predicate);

    OptionalLong findFirst();

    OptionalLong findAny();

    DoubleStream asDoubleStream();

    Stream<Long> boxed();

    PrimitiveIterator.OfLong iterator();

    static Builder builder() {
        return new LongFlow.Building();
    }

    static LongStream empty() {
        return LongFlow.of(new long[0], 0, 0);
    }

    static LongStream of(long t) {
        return LongFlow.of(new long[] { t }, 0, 1);
    }

    static LongStream of(long... values) {
        return LongFlow.of(values, 0, values.length);
    }

    static LongStream iterate(long seed, LongUnaryOperator f) {
        return LongFlow.iterated(seed, null, f);
    }

    static LongStream iterate(long seed, LongPredicate hasNext, LongUnaryOperator next) {
        if (hasNext == null) {
            throw new NullPointerException();
        }
        return LongFlow.iterated(seed, hasNext, next);
    }

    static LongStream generate(LongSupplier s) {
        return LongFlow.generated(s);
    }

    static LongStream range(long startInclusive, long endExclusive) {
        return LongFlow.range(startInclusive, endExclusive, false);
    }

    static LongStream rangeClosed(long startInclusive, long endInclusive) {
        return LongFlow.range(startInclusive, endInclusive, true);
    }

    static LongStream concat(LongStream a, LongStream b) {
        return LongFlow.concat(a, b);
    }
}
