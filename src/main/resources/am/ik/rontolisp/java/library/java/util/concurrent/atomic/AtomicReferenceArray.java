// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Every operation holds the object's monitor: atomic among the threads of the program
// on every target, with no compare-and-swap of the host's.
package java.util.concurrent.atomic;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

public class AtomicReferenceArray<E> {

    private final Object[] array;

    public AtomicReferenceArray(int length) {
        this.array = new Object[length];
    }

    public AtomicReferenceArray(E[] array) {
        this.array = new Object[array.length];
        System.arraycopy(array, 0, this.array, 0, array.length);
    }

    public final int length() {
        return this.array.length;
    }

    @SuppressWarnings("unchecked")
    public final synchronized E get(int i) {
        return (E) this.array[i];
    }

    public final synchronized void set(int i, E newValue) {
        this.array[i] = newValue;
    }

    public final void lazySet(int i, E newValue) {
        set(i, newValue);
    }

    public final synchronized E getAndSet(int i, E newValue) {
        E previous = get(i);
        this.array[i] = newValue;
        return previous;
    }

    public final synchronized boolean compareAndSet(int i, E expectedValue, E newValue) {
        if (this.array[i] != expectedValue) {
            return false;
        }
        this.array[i] = newValue;
        return true;
    }

    public final boolean weakCompareAndSetPlain(int i, E expectedValue, E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final synchronized E getAndUpdate(int i, UnaryOperator<E> updateFunction) {
        E previous = get(i);
        this.array[i] = updateFunction.apply(previous);
        return previous;
    }

    public final synchronized E updateAndGet(int i, UnaryOperator<E> updateFunction) {
        E next = updateFunction.apply(get(i));
        this.array[i] = next;
        return next;
    }

    public final synchronized E getAndAccumulate(int i, E x, BinaryOperator<E> accumulatorFunction) {
        E previous = get(i);
        this.array[i] = accumulatorFunction.apply(previous, x);
        return previous;
    }

    public final synchronized E accumulateAndGet(int i, E x, BinaryOperator<E> accumulatorFunction) {
        E next = accumulatorFunction.apply(get(i), x);
        this.array[i] = next;
        return next;
    }

    public synchronized String toString() {
        return java.util.Arrays.toString(this.array);
    }
}
