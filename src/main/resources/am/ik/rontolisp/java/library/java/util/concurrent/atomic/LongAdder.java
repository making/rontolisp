// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A sum many threads add to. Every operation holds the object's monitor, as the
// atomics' do: one sum, and no cell a thread.
package java.util.concurrent.atomic;

public class LongAdder extends Number {

    private long sum;

    public LongAdder() {
    }

    public synchronized void add(long x) {
        this.sum += x;
    }

    public void increment() {
        add(1L);
    }

    public void decrement() {
        add(-1L);
    }

    public synchronized long sum() {
        return this.sum;
    }

    public synchronized void reset() {
        this.sum = 0L;
    }

    public synchronized long sumThenReset() {
        long was = this.sum;
        this.sum = 0L;
        return was;
    }

    public long longValue() {
        return sum();
    }

    public int intValue() {
        return (int) sum();
    }

    public float floatValue() {
        return (float) sum();
    }

    public double doubleValue() {
        return (double) sum();
    }

    public String toString() {
        return Long.toString(sum());
    }
}
