// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

// A collector made of its four functions: what Collector.of and Collectors answer.
final class Collected<T, A, R> implements Collector<T, A, R> {

    private final Supplier<A> supplier;

    private final BiConsumer<A, T> accumulator;

    private final BinaryOperator<A> combiner;

    private final Function<A, R> finisher;

    static final int CONCURRENT = 1;

    static final int UNORDERED = 2;

    static final int IDENTITY = 4;

    // a bit for each mark, by its place in the enum
    private final int marks;

    Collected(Supplier<A> supplier, BiConsumer<A, T> accumulator, BinaryOperator<A> combiner,
            Function<A, R> finisher, int marks) {
        this.supplier = supplier;
        this.accumulator = accumulator;
        this.combiner = combiner;
        this.finisher = finisher;
        this.marks = marks;
    }

    // the marks of a collector made here; one of a program's own class is taken to
    // have none, which saves every program that groups from making a set of them
    static int marks(Collector<?, ?, ?> collector) {
        return collector instanceof Collected<?, ?, ?> made ? made.marks : 0;
    }

    static int marks(Characteristics... given) {
        int marks = 0;
        for (Characteristics mark : given) {
            marks |= 1 << mark.ordinal();
        }
        return marks;
    }

    public Supplier<A> supplier() {
        return supplier;
    }

    public BiConsumer<A, T> accumulator() {
        return accumulator;
    }

    public BinaryOperator<A> combiner() {
        return combiner;
    }

    public Function<A, R> finisher() {
        return finisher;
    }

    // made when asked for: a set read in the order the enum declares the marks in
    public Set<Characteristics> characteristics() {
        ArrayList<Characteristics> named = new ArrayList<>();
        for (Characteristics each : Characteristics.values()) {
            if ((marks & 1 << each.ordinal()) != 0) {
                named.add(each);
            }
        }
        List<Characteristics> ordered = Collections.unmodifiableList(named);
        return new AbstractSet<Characteristics>() {
            public int size() {
                return ordered.size();
            }

            public Iterator<Characteristics> iterator() {
                return ordered.iterator();
            }
        };
    }
}
