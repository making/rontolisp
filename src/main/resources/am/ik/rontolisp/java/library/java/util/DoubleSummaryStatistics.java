// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.DoubleConsumer;
import rontolisp.internal.Flow;

public class DoubleSummaryStatistics implements DoubleConsumer {

    private long count;

    // the sum as rontolisp.internal.Flow keeps one
    private final double[] sum = new double[3];

    private double min = Double.POSITIVE_INFINITY;

    private double max = Double.NEGATIVE_INFINITY;

    public DoubleSummaryStatistics() {
    }

    public DoubleSummaryStatistics(long count, double min, double max, double sum) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count value");
        }
        if (count > 0) {
            if (min > max) {
                throw new IllegalArgumentException("Minimum greater than maximum");
            }
            int numbers = (Double.isNaN(min) ? 1 : 0) + (Double.isNaN(max) ? 1 : 0) + (Double.isNaN(sum) ? 1 : 0);
            if (numbers > 0 && numbers < 3) {
                throw new IllegalArgumentException("Some, not all, of the minimum, maximum, or sum is NaN");
            }
            this.count = count;
            this.sum[0] = sum;
            this.sum[2] = sum;
            this.min = min;
            this.max = max;
        }
    }

    public void accept(double value) {
        count++;
        Flow.add(sum, value);
        min = Math.min(min, value);
        max = Math.max(max, value);
    }

    public void combine(DoubleSummaryStatistics other) {
        count += other.count;
        double plain = sum[2] + other.sum[2];
        Flow.add(sum, other.sum[0]);
        Flow.add(sum, -other.sum[1]);
        sum[2] = plain;
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
    }

    public final long getCount() {
        return count;
    }

    public final double getSum() {
        return Flow.total(sum);
    }

    public final double getMin() {
        return min;
    }

    public final double getMax() {
        return max;
    }

    public final double getAverage() {
        return count > 0 ? getSum() / count : 0.0;
    }

    public String toString() {
        return String.format("DoubleSummaryStatistics{count=%d, sum=%f, min=%f, average=%f, max=%f}", count,
                getSum(), min, getAverage(), max);
    }
}
