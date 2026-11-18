// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Every operation holds the object's monitor: atomic among the threads of the program
// on every target, with no compare-and-swap of the host's.
package java.util.concurrent.atomic;

import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

public class AtomicIntegerArray {

    private final int[] array;

    public AtomicIntegerArray(int length) {
        this.array = new int[length];
    }

    public AtomicIntegerArray(int[] array) {
        this.array = array.clone();
    }

    public final int length() {
        return this.array.length;
    }

    public final synchronized int get(int i) {
        return this.array[i];
    }

    public final synchronized void set(int i, int newValue) {
        this.array[i] = newValue;
    }

    public final void lazySet(int i, int newValue) {
        set(i, newValue);
    }

    public final synchronized int getAndSet(int i, int newValue) {
        int previous = this.array[i];
        this.array[i] = newValue;
        return previous;
    }

    public final synchronized boolean compareAndSet(int i, int expectedValue, int newValue) {
        if (this.array[i] != expectedValue) {
            return false;
        }
        this.array[i] = newValue;
        return true;
    }

    public final boolean weakCompareAndSetPlain(int i, int expectedValue, int newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final synchronized int getAndAdd(int i, int delta) {
        int previous = this.array[i];
        this.array[i] = previous + delta;
        return previous;
    }

    public final synchronized int addAndGet(int i, int delta) {
        this.array[i] += delta;
        return this.array[i];
    }

    public final int getAndIncrement(int i) {
        return getAndAdd(i, 1);
    }

    public final int getAndDecrement(int i) {
        return getAndAdd(i, -1);
    }

    public final int incrementAndGet(int i) {
        return addAndGet(i, 1);
    }

    public final int decrementAndGet(int i) {
        return addAndGet(i, -1);
    }

    public final synchronized int getAndUpdate(int i, IntUnaryOperator updateFunction) {
        int previous = this.array[i];
        this.array[i] = updateFunction.applyAsInt(previous);
        return previous;
    }

    public final synchronized int updateAndGet(int i, IntUnaryOperator updateFunction) {
        this.array[i] = updateFunction.applyAsInt(this.array[i]);
        return this.array[i];
    }

    public final synchronized int getAndAccumulate(int i, int x, IntBinaryOperator accumulatorFunction) {
        int previous = this.array[i];
        this.array[i] = accumulatorFunction.applyAsInt(previous, x);
        return previous;
    }

    public final synchronized int accumulateAndGet(int i, int x, IntBinaryOperator accumulatorFunction) {
        this.array[i] = accumulatorFunction.applyAsInt(this.array[i], x);
        return this.array[i];
    }

    public synchronized String toString() {
        return java.util.Arrays.toString(this.array);
    }
}
