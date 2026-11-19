// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.IntConsumer;

public class IntSummaryStatistics implements IntConsumer {

    private long count;

    private long sum;

    private int min = Integer.MAX_VALUE;

    private int max = Integer.MIN_VALUE;

    public IntSummaryStatistics() {
    }

    public IntSummaryStatistics(long count, int min, int max, long sum) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count value");
        }
        if (count > 0) {
            if (min > max) {
                throw new IllegalArgumentException("Minimum greater than maximum");
            }
            this.count = count;
            this.sum = sum;
            this.min = min;
            this.max = max;
        }
    }

    public void accept(int value) {
        count++;
        sum += value;
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
    }

    public void combine(IntSummaryStatistics other) {
        count += other.count;
        sum += other.sum;
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
    }

    public final long getCount() {
        return count;
    }

    public final long getSum() {
        return sum;
    }

    public final int getMin() {
        return min;
    }

    public final int getMax() {
        return max;
    }

    public final double getAverage() {
        return count > 0 ? (double) sum / count : 0.0;
    }

    public String toString() {
        return String.format("IntSummaryStatistics{count=%d, sum=%d, min=%d, average=%f, max=%d}", count, sum, min,
                getAverage(), max);
    }
}
