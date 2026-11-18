// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A sum of doubles many threads add to, under the object's monitor as LongAdder's:
// one sum, added to in the order the values came.
package java.util.concurrent.atomic;

public class DoubleAdder {

    private double sum;

    public DoubleAdder() {
    }

    public synchronized void add(double x) {
        this.sum += x;
    }

    public synchronized double sum() {
        return this.sum;
    }

    public synchronized void reset() {
        this.sum = 0.0;
    }

    public synchronized double sumThenReset() {
        double was = this.sum;
        this.sum = 0.0;
        return was;
    }

    public long longValue() {
        return (long) sum();
    }

    public int intValue() {
        return (int) sum();
    }

    public float floatValue() {
        return (float) sum();
    }

    public double doubleValue() {
        return sum();
    }

    public String toString() {
        return Double.toString(sum());
    }
}
