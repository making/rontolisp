// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

public interface PrimitiveIterator<T, C> extends Iterator<T> {

    void forEachRemaining(C action);

    interface OfInt extends PrimitiveIterator<Integer, IntConsumer> {

        int nextInt();

        default Integer next() {
            return nextInt();
        }

        default void forEachRemaining(IntConsumer action) {
            while (hasNext()) {
                action.accept(nextInt());
            }
        }

        default void forEachRemaining(Consumer<? super Integer> action) {
            while (hasNext()) {
                action.accept(nextInt());
            }
        }
    }

    interface OfLong extends PrimitiveIterator<Long, LongConsumer> {

        long nextLong();

        default Long next() {
            return nextLong();
        }

        default void forEachRemaining(LongConsumer action) {
            while (hasNext()) {
                action.accept(nextLong());
            }
        }

        default void forEachRemaining(Consumer<? super Long> action) {
            while (hasNext()) {
                action.accept(nextLong());
            }
        }
    }

    interface OfDouble extends PrimitiveIterator<Double, DoubleConsumer> {

        double nextDouble();

        default Double next() {
            return nextDouble();
        }

        default void forEachRemaining(DoubleConsumer action) {
            while (hasNext()) {
                action.accept(nextDouble());
            }
        }

        default void forEachRemaining(Consumer<? super Double> action) {
            while (hasNext()) {
                action.accept(nextDouble());
            }
        }
    }
}
