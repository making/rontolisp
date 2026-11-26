// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A value many threads fold their own into with one function. Every operation holds
// the object's monitor, as the atomics' do: one value, and no cell a thread, so the
// function is applied to the values in the order they came.
package java.util.concurrent.atomic;

import java.util.function.LongBinaryOperator;

public class LongAccumulator extends Number {

    private final LongBinaryOperator function;

    private final long identity;

    private long value;

    public LongAccumulator(LongBinaryOperator accumulatorFunction, long identity) {
        this.function = accumulatorFunction;
        this.identity = identity;
        this.value = identity;
    }

    public synchronized void accumulate(long x) {
        this.value = this.function.applyAsLong(this.value, x);
    }

    public synchronized long get() {
        return this.value;
    }

    public synchronized void reset() {
        this.value = this.identity;
    }

    public synchronized long getThenReset() {
        long was = this.value;
        this.value = this.identity;
        return was;
    }

    public long longValue() {
        return get();
    }

    public int intValue() {
        return (int) get();
    }

    public float floatValue() {
        return (float) get();
    }

    public double doubleValue() {
        return (double) get();
    }

    public String toString() {
        return Long.toString(get());
    }
}
