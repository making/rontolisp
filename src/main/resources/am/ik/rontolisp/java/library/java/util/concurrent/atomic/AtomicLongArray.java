// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Every operation holds the object's monitor: atomic among the threads of the program
// on every target, with no compare-and-swap of the host's.
package java.util.concurrent.atomic;

import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

public class AtomicLongArray {

    private final long[] array;

    public AtomicLongArray(int length) {
        this.array = new long[length];
    }

    public AtomicLongArray(long[] array) {
        this.array = array.clone();
    }

    public final int length() {
        return this.array.length;
    }

    public final synchronized long get(int i) {
        return this.array[i];
    }

    public final synchronized void set(int i, long newValue) {
        this.array[i] = newValue;
    }

    public final void lazySet(int i, long newValue) {
        set(i, newValue);
    }

    public final synchronized long getAndSet(int i, long newValue) {
        long previous = this.array[i];
        this.array[i] = newValue;
        return previous;
    }

    public final synchronized boolean compareAndSet(int i, long expectedValue, long newValue) {
        if (this.array[i] != expectedValue) {
            return false;
        }
        this.array[i] = newValue;
        return true;
    }

    public final boolean weakCompareAndSetPlain(int i, long expectedValue, long newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    public final synchronized long getAndAdd(int i, long delta) {
        long previous = this.array[i];
        this.array[i] = previous + delta;
        return previous;
    }

    public final synchronized long addAndGet(int i, long delta) {
        this.array[i] += delta;
        return this.array[i];
    }

    public final long getAndIncrement(int i) {
        return getAndAdd(i, 1);
    }

    public final long getAndDecrement(int i) {
        return getAndAdd(i, -1);
    }

    public final long incrementAndGet(int i) {
        return addAndGet(i, 1);
    }

    public final long decrementAndGet(int i) {
        return addAndGet(i, -1);
    }

    public final synchronized long getAndUpdate(int i, LongUnaryOperator updateFunction) {
        long previous = this.array[i];
        this.array[i] = updateFunction.applyAsLong(previous);
        return previous;
    }

    public final synchronized long updateAndGet(int i, LongUnaryOperator updateFunction) {
        this.array[i] = updateFunction.applyAsLong(this.array[i]);
        return this.array[i];
    }

    public final synchronized long getAndAccumulate(int i, long x, LongBinaryOperator accumulatorFunction) {
        long previous = this.array[i];
        this.array[i] = accumulatorFunction.applyAsLong(previous, x);
        return previous;
    }

    public final synchronized long accumulateAndGet(int i, long x, LongBinaryOperator accumulatorFunction) {
        this.array[i] = accumulatorFunction.applyAsLong(this.array[i], x);
        return this.array[i];
    }

    public synchronized String toString() {
        return java.util.Arrays.toString(this.array);
    }
}
