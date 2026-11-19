// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

public interface Gatherer<T, A, R> {

    interface Downstream<T> {

        boolean push(T element);

        default boolean isRejecting() {
            return false;
        }
    }

    interface Integrator<A, T, R> {

        boolean integrate(A state, T element, Downstream<? super R> downstream);

        static <A, T, R> Integrator<A, T, R> of(Integrator<A, T, R> integrator) {
            return integrator;
        }

        static <A, T, R> Greedy<A, T, R> ofGreedy(Greedy<A, T, R> greedy) {
            return greedy;
        }

        interface Greedy<A, T, R> extends Integrator<A, T, R> {

        }
    }

    default Supplier<A> initializer() {
        return defaultInitializer();
    }

    Integrator<A, T, R> integrator();

    default BinaryOperator<A> combiner() {
        return defaultCombiner();
    }

    default BiConsumer<A, Downstream<? super R>> finisher() {
        return defaultFinisher();
    }

    default <RR> Gatherer<T, ?, RR> andThen(Gatherer<? super R, ?, ? extends RR> that) {
        Objects.requireNonNull(that);
        return Gathered.both(this, that);
    }

    @SuppressWarnings("unchecked")
    static <A> Supplier<A> defaultInitializer() {
        return (Supplier<A>) Gathered.STATELESS;
    }

    @SuppressWarnings("unchecked")
    static <A> BinaryOperator<A> defaultCombiner() {
        return (BinaryOperator<A>) Gathered.IN_ORDER;
    }

    @SuppressWarnings("unchecked")
    static <A, R> BiConsumer<A, Downstream<? super R>> defaultFinisher() {
        return (BiConsumer<A, Downstream<? super R>>) (BiConsumer<?, ?>) Gathered.NOTHING_LEFT;
    }

    static <T, R> Gatherer<T, Void, R> ofSequential(Integrator<Void, T, R> integrator) {
        return of(defaultInitializer(), integrator, defaultCombiner(), defaultFinisher());
    }

    static <T, R> Gatherer<T, Void, R> ofSequential(Integrator<Void, T, R> integrator,
            BiConsumer<Void, Downstream<? super R>> finisher) {
        return of(defaultInitializer(), integrator, defaultCombiner(), finisher);
    }

    static <T, A, R> Gatherer<T, A, R> ofSequential(Supplier<A> initializer, Integrator<A, T, R> integrator) {
        return of(initializer, integrator, defaultCombiner(), defaultFinisher());
    }

    static <T, A, R> Gatherer<T, A, R> ofSequential(Supplier<A> initializer, Integrator<A, T, R> integrator,
            BiConsumer<A, Downstream<? super R>> finisher) {
        return of(initializer, integrator, defaultCombiner(), finisher);
    }

    static <T, R> Gatherer<T, Void, R> of(Integrator<Void, T, R> integrator) {
        return of(defaultInitializer(), integrator, Gathered.first(), defaultFinisher());
    }

    static <T, R> Gatherer<T, Void, R> of(Integrator<Void, T, R> integrator,
            BiConsumer<Void, Downstream<? super R>> finisher) {
        return of(defaultInitializer(), integrator, Gathered.first(), finisher);
    }

    static <T, A, R> Gatherer<T, A, R> of(Supplier<A> initializer, Integrator<A, T, R> integrator,
            BinaryOperator<A> combiner, BiConsumer<A, Downstream<? super R>> finisher) {
        if (initializer == null || integrator == null || combiner == null || finisher == null) {
            throw new NullPointerException();
        }
        return new Gathered<T, A, R>(initializer, integrator, combiner, finisher);
    }
}
