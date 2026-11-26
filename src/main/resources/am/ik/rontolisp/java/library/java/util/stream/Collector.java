// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Collector<T, A, R> {

    enum Characteristics {

        CONCURRENT, UNORDERED, IDENTITY_FINISH

    }

    Supplier<A> supplier();

    BiConsumer<A, T> accumulator();

    BinaryOperator<A> combiner();

    Function<A, R> finisher();

    Set<Characteristics> characteristics();

    static <T, R> Collector<T, R, R> of(Supplier<R> supplier, BiConsumer<R, T> accumulator,
            BinaryOperator<R> combiner, Characteristics... characteristics) {
        if (supplier == null || accumulator == null || combiner == null || characteristics == null) {
            throw new NullPointerException();
        }
        return new Collected<T, R, R>(supplier, accumulator, combiner, container -> container,
                Collected.marks(characteristics) | Collected.IDENTITY);
    }

    static <T, A, R> Collector<T, A, R> of(Supplier<A> supplier, BiConsumer<A, T> accumulator,
            BinaryOperator<A> combiner, Function<A, R> finisher, Characteristics... characteristics) {
        if (supplier == null || accumulator == null || combiner == null || finisher == null
                || characteristics == null) {
            throw new NullPointerException();
        }
        return new Collected<T, A, R>(supplier, accumulator, combiner, finisher,
                Collected.marks(characteristics));
    }
}
