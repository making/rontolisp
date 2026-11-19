// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.DoubleSummaryStatistics;
import java.util.OptionalDouble;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
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
import rontolisp.internal.DoubleFlow;

public interface DoubleStream extends BaseStream<Double, DoubleStream> {

    interface Builder extends DoubleConsumer {

        void accept(double t);

        default Builder add(double t) {
            accept(t);
            return this;
        }

        DoubleStream build();
    }

    interface DoubleMapMultiConsumer {

        void accept(double value, DoubleConsumer consumer);
    }

    DoubleStream filter(DoublePredicate predicate);

    DoubleStream map(DoubleUnaryOperator mapper);

    <U> Stream<U> mapToObj(DoubleFunction<? extends U> mapper);

    IntStream mapToInt(DoubleToIntFunction mapper);

    LongStream mapToLong(DoubleToLongFunction mapper);

    DoubleStream flatMap(DoubleFunction<? extends DoubleStream> mapper);

    DoubleStream mapMulti(DoubleMapMultiConsumer mapper);

    DoubleStream distinct();

    DoubleStream sorted();

    DoubleStream peek(DoubleConsumer action);

    DoubleStream limit(long maxSize);

    DoubleStream skip(long n);

    DoubleStream takeWhile(DoublePredicate predicate);

    DoubleStream dropWhile(DoublePredicate predicate);

    void forEach(DoubleConsumer action);

    void forEachOrdered(DoubleConsumer action);

    double[] toArray();

    double reduce(double identity, DoubleBinaryOperator op);

    OptionalDouble reduce(DoubleBinaryOperator op);

    <R> R collect(Supplier<R> supplier, ObjDoubleConsumer<R> accumulator, BiConsumer<R, R> combiner);

    double sum();

    OptionalDouble min();

    OptionalDouble max();

    long count();

    OptionalDouble average();

    DoubleSummaryStatistics summaryStatistics();

    boolean anyMatch(DoublePredicate predicate);

    boolean allMatch(DoublePredicate predicate);

    boolean noneMatch(DoublePredicate predicate);

    OptionalDouble findFirst();

    OptionalDouble findAny();

    Stream<Double> boxed();

    PrimitiveIterator.OfDouble iterator();

    Spliterator.OfDouble spliterator();

    static Builder builder() {
        return new DoubleFlow.Building();
    }

    static DoubleStream empty() {
        return DoubleFlow.of(new double[0], 0, 0);
    }

    static DoubleStream of(double t) {
        return DoubleFlow.of(new double[] { t }, 0, 1);
    }

    static DoubleStream of(double... values) {
        return DoubleFlow.of(values, 0, values.length);
    }

    static DoubleStream iterate(double seed, DoubleUnaryOperator f) {
        return DoubleFlow.iterated(seed, null, f);
    }

    static DoubleStream iterate(double seed, DoublePredicate hasNext, DoubleUnaryOperator next) {
        if (hasNext == null) {
            throw new NullPointerException();
        }
        return DoubleFlow.iterated(seed, hasNext, next);
    }

    static DoubleStream generate(DoubleSupplier s) {
        return DoubleFlow.generated(s);
    }

    static DoubleStream concat(DoubleStream a, DoubleStream b) {
        return DoubleFlow.concat(a, b);
    }
}
