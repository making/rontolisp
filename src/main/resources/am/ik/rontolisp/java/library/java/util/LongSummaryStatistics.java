// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

public class LongSummaryStatistics implements LongConsumer, IntConsumer {

    private long count;

    private long sum;

    private long min = Long.MAX_VALUE;

    private long max = Long.MIN_VALUE;

    public LongSummaryStatistics() {
    }

    public LongSummaryStatistics(long count, long min, long max, long sum) {
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
        accept((long) value);
    }

    public void accept(long value) {
        count++;
        sum += value;
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
    }

    public void combine(LongSummaryStatistics other) {
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

    public final long getMin() {
        return min;
    }

    public final long getMax() {
        return max;
    }

    public final double getAverage() {
        return count > 0 ? (double) sum / count : 0.0;
    }

    public String toString() {
        return String.format("LongSummaryStatistics{count=%d, sum=%d, min=%d, average=%f, max=%d}", count, sum, min,
                getAverage(), max);
    }
}
