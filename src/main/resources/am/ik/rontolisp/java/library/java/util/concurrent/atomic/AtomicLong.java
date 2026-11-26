// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Every operation holds the object's monitor: atomic among the threads of the program
// on every target, with no compare-and-swap of the host's.
package java.util.concurrent.atomic;

import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

public class AtomicLong extends Number {

    private long value;

    public AtomicLong() {
    }

    public AtomicLong(long initialValue) {
        this.value = initialValue;
    }

    public final synchronized long get() {
        return this.value;
    }

    public final synchronized void set(long newValue) {
        this.value = newValue;
    }

    public final void lazySet(long newValue) {
        set(newValue);
    }

    public final synchronized long getAndSet(long newValue) {
        long previous = this.value;
        this.value = newValue;
        return previous;
    }

    public final synchronized boolean compareAndSet(long expectedValue, long newValue) {
        if (this.value != expectedValue) {
            return false;
        }
        this.value = newValue;
        return true;
    }

    public final boolean weakCompareAndSetPlain(long expectedValue, long newValue) {
        return compareAndSet(expectedValue, newValue);
    }

    public final synchronized long getAndAdd(long delta) {
        long previous = this.value;
        this.value = previous + delta;
        return previous;
    }

    public final synchronized long addAndGet(long delta) {
        this.value += delta;
        return this.value;
    }

    public final long getAndIncrement() {
        return getAndAdd(1);
    }

    public final long getAndDecrement() {
        return getAndAdd(-1);
    }

    public final long incrementAndGet() {
        return addAndGet(1);
    }

    public final long decrementAndGet() {
        return addAndGet(-1);
    }

    public final synchronized long getAndUpdate(LongUnaryOperator updateFunction) {
        long previous = this.value;
        this.value = updateFunction.applyAsLong(previous);
        return previous;
    }

    public final synchronized long updateAndGet(LongUnaryOperator updateFunction) {
        this.value = updateFunction.applyAsLong(this.value);
        return this.value;
    }

    public final synchronized long getAndAccumulate(long x, LongBinaryOperator accumulatorFunction) {
        long previous = this.value;
        this.value = accumulatorFunction.applyAsLong(previous, x);
        return previous;
    }

    public final synchronized long accumulateAndGet(long x, LongBinaryOperator accumulatorFunction) {
        this.value = accumulatorFunction.applyAsLong(this.value, x);
        return this.value;
    }

    public int intValue() {
        return (int) get();
    }

    public long longValue() {
        return get();
    }

    public float floatValue() {
        return get();
    }

    public double doubleValue() {
        return get();
    }

    public String toString() {
        return Long.toString(get());
    }
}
