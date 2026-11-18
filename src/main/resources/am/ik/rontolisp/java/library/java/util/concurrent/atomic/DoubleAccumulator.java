// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A double many threads fold their own into with one function, under the object's
// monitor as LongAccumulator's.
package java.util.concurrent.atomic;

import java.util.function.DoubleBinaryOperator;

public class DoubleAccumulator {

    private final DoubleBinaryOperator function;

    private final double identity;

    private double value;

    public DoubleAccumulator(DoubleBinaryOperator accumulatorFunction, double identity) {
        this.function = accumulatorFunction;
        this.identity = identity;
        this.value = identity;
    }

    public synchronized void accumulate(double x) {
        this.value = this.function.applyAsDouble(this.value, x);
    }

    public synchronized double get() {
        return this.value;
    }

    public synchronized void reset() {
        this.value = this.identity;
    }

    public synchronized double getThenReset() {
        double was = this.value;
        this.value = this.identity;
        return was;
    }

    public long longValue() {
        return (long) get();
    }

    public int intValue() {
        return (int) get();
    }

    public float floatValue() {
        return (float) get();
    }

    public double doubleValue() {
        return get();
    }

    public String toString() {
        return Double.toString(get());
    }
}
