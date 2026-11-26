// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Every operation holds the object's monitor: atomic among the threads of the program
// on every target, with no compare-and-swap of the host's.
package java.util.concurrent.atomic;

import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

public class AtomicInteger extends Number {

    private int value;

    public AtomicInteger() {
    }

    public AtomicInteger(int initialValue) {
        this.value = initialValue;
    }

    public final synchronized int get() {
        return this.value;
    }

    public final synchronized void set(int newValue) {
        this.value = newValue;
    }

    public final void lazySet(int newValue) {
        set(newValue);
    }

    public final synchronized int getAndSet(int newValue) {
        int previous = this.value;
        this.value = newValue;
        return previous;
    }

    public final synchronized boolean compareAndSet(int expectedValue, int newValue) {
        if (this.value != expectedValue) {
            return false;
        }
        this.value = newValue;
        return true;
    }

    public final boolean weakCompareAndSetPlain(int expectedValue, int newValue) {
        return compareAndSet(expectedValue, newValue);
    }

    public final synchronized int getAndAdd(int delta) {
        int previous = this.value;
        this.value = previous + delta;
        return previous;
    }

    public final synchronized int addAndGet(int delta) {
        this.value += delta;
        return this.value;
    }

    public final int getAndIncrement() {
        return getAndAdd(1);
    }

    public final int getAndDecrement() {
        return getAndAdd(-1);
    }

    public final int incrementAndGet() {
        return addAndGet(1);
    }

    public final int decrementAndGet() {
        return addAndGet(-1);
    }

    public final synchronized int getAndUpdate(IntUnaryOperator updateFunction) {
        int previous = this.value;
        this.value = updateFunction.applyAsInt(previous);
        return previous;
    }

    public final synchronized int updateAndGet(IntUnaryOperator updateFunction) {
        this.value = updateFunction.applyAsInt(this.value);
        return this.value;
    }

    public final synchronized int getAndAccumulate(int x, IntBinaryOperator accumulatorFunction) {
        int previous = this.value;
        this.value = accumulatorFunction.applyAsInt(previous, x);
        return previous;
    }

    public final synchronized int accumulateAndGet(int x, IntBinaryOperator accumulatorFunction) {
        this.value = accumulatorFunction.applyAsInt(this.value, x);
        return this.value;
    }

    public int intValue() {
        return get();
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
        return Integer.toString(get());
    }
}
