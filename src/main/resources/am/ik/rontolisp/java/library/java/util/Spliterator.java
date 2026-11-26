// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

public interface Spliterator<T> {

    int ORDERED = 0x00000010;

    int DISTINCT = 0x00000001;

    int SORTED = 0x00000004;

    int SIZED = 0x00000040;

    int NONNULL = 0x00000100;

    int IMMUTABLE = 0x00000400;

    int CONCURRENT = 0x00001000;

    int SUBSIZED = 0x00004000;

    boolean tryAdvance(Consumer<? super T> action);

    default void forEachRemaining(Consumer<? super T> action) {
        while (tryAdvance(action)) {
        }
    }

    Spliterator<T> trySplit();

    long estimateSize();

    default long getExactSizeIfKnown() {
        return (characteristics() & SIZED) == 0 ? -1L : estimateSize();
    }

    int characteristics();

    default boolean hasCharacteristics(int characteristics) {
        return (characteristics() & characteristics) == characteristics;
    }

    default Comparator<? super T> getComparator() {
        throw new IllegalStateException();
    }

    interface OfPrimitive<T, T_CONS, T_SPLITR extends OfPrimitive<T, T_CONS, T_SPLITR>> extends Spliterator<T> {

        T_SPLITR trySplit();

        boolean tryAdvance(T_CONS action);

        default void forEachRemaining(T_CONS action) {
            while (tryAdvance(action)) {
            }
        }
    }

    interface OfInt extends OfPrimitive<Integer, IntConsumer, OfInt> {

        OfInt trySplit();

        boolean tryAdvance(IntConsumer action);

        default void forEachRemaining(IntConsumer action) {
            while (tryAdvance(action)) {
            }
        }

        default boolean tryAdvance(Consumer<? super Integer> action) {
            Objects.requireNonNull(action);
            return tryAdvance((int item) -> action.accept(item));
        }

        default void forEachRemaining(Consumer<? super Integer> action) {
            Objects.requireNonNull(action);
            forEachRemaining((int item) -> action.accept(item));
        }
    }

    interface OfLong extends OfPrimitive<Long, LongConsumer, OfLong> {

        OfLong trySplit();

        boolean tryAdvance(LongConsumer action);

        default void forEachRemaining(LongConsumer action) {
            while (tryAdvance(action)) {
            }
        }

        default boolean tryAdvance(Consumer<? super Long> action) {
            Objects.requireNonNull(action);
            return tryAdvance((long item) -> action.accept(item));
        }

        default void forEachRemaining(Consumer<? super Long> action) {
            Objects.requireNonNull(action);
            forEachRemaining((long item) -> action.accept(item));
        }
    }

    interface OfDouble extends OfPrimitive<Double, DoubleConsumer, OfDouble> {

        OfDouble trySplit();

        boolean tryAdvance(DoubleConsumer action);

        default void forEachRemaining(DoubleConsumer action) {
            while (tryAdvance(action)) {
            }
        }

        default boolean tryAdvance(Consumer<? super Double> action) {
            Objects.requireNonNull(action);
            return tryAdvance((double item) -> action.accept(item));
        }

        default void forEachRemaining(Consumer<? super Double> action) {
            Objects.requireNonNull(action);
            forEachRemaining((double item) -> action.accept(item));
        }
    }
}
