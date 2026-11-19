// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

// A gatherer made of its four functions: what Gatherer.of, andThen and Gatherers answer.
final class Gathered<T, A, R> implements Gatherer<T, A, R> {

    // what a gatherer with no state, no way to join two halves and nothing to do at the
    // end answers: each one object, which is how a gatherer is asked whether it has one
    static final Supplier<Object> STATELESS = () -> null;

    static final BinaryOperator<Object> IN_ORDER = (left, right) -> {
        throw new UnsupportedOperationException("This combiner cannot be used!");
    };

    static final BiConsumer<Object, Object> NOTHING_LEFT = (state, downstream) -> {
    };

    private final Supplier<A> initializer;

    private final Integrator<A, T, R> integrator;

    private final BinaryOperator<A> combiner;

    private final BiConsumer<A, Downstream<? super R>> finisher;

    Gathered(Supplier<A> initializer, Integrator<A, T, R> integrator, BinaryOperator<A> combiner,
            BiConsumer<A, Downstream<? super R>> finisher) {
        this.initializer = initializer;
        this.integrator = integrator;
        this.combiner = combiner;
        this.finisher = finisher;
    }

    public Supplier<A> initializer() {
        return initializer;
    }

    public Integrator<A, T, R> integrator() {
        return integrator;
    }

    public BinaryOperator<A> combiner() {
        return combiner;
    }

    public BiConsumer<A, Downstream<? super R>> finisher() {
        return finisher;
    }

    // of two halves the one that came first: nothing here runs a stream in halves
    static <A> BinaryOperator<A> first() {
        return (left, right) -> left;
    }

    // The states of two gatherers, one handing what it pushes to the other: what the
    // first is given to push into. It answers false once the second has ended.
    static final class Both<A, AA, R, RR> implements Downstream<R> {

        final A first;

        final AA second;

        private final Integrator<AA, R, RR> integrator;

        Downstream<? super RR> downstream;

        boolean ended;

        Both(A first, AA second, Integrator<AA, R, RR> integrator) {
            this.first = first;
            this.second = second;
            this.integrator = integrator;
        }

        public boolean push(R element) {
            if (ended) {
                return false;
            }
            if (!integrator.integrate(second, element, downstream)) {
                ended = true;
                return false;
            }
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    static <T, A, R, AA, RR> Gatherer<T, ?, RR> both(Gatherer<T, A, R> first,
            Gatherer<? super R, AA, ? extends RR> given) {
        Gatherer<R, AA, RR> second = (Gatherer<R, AA, RR>) given;
        Integrator<A, T, R> integrator = first.integrator();
        BiConsumer<A, Downstream<? super R>> firstEnd = first.finisher();
        BiConsumer<AA, Downstream<? super RR>> secondEnd = second.finisher();
        BiConsumer<Both<A, AA, R, RR>, Downstream<? super RR>> finisher = Gatherer.defaultFinisher();
        if ((Object) firstEnd != NOTHING_LEFT || (Object) secondEnd != NOTHING_LEFT) {
            finisher = (both, downstream) -> {
                both.downstream = downstream;
                firstEnd.accept(both.first, both);
                secondEnd.accept(both.second, downstream);
            };
        }
        BinaryOperator<Both<A, AA, R, RR>> combiner = Gatherer.defaultCombiner();
        if ((Object) first.combiner() != IN_ORDER && (Object) second.combiner() != IN_ORDER) {
            combiner = first();
        }
        return new Gathered<T, Both<A, AA, R, RR>, RR>(
                () -> new Both<A, AA, R, RR>(first.initializer().get(), second.initializer().get(),
                        second.integrator()),
                (both, element, downstream) -> {
                    both.downstream = downstream;
                    return integrator.integrate(both.first, element, both) && !both.ended;
                }, combiner, finisher);
    }
}
