// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

import java.time.DateTimeException;

// The values a field takes: from a smallest to a largest, either of which may itself
// vary (a month's last day is 28 to 31).
public final class ValueRange {

    private final long minSmallest;

    private final long minLargest;

    private final long maxSmallest;

    private final long maxLargest;

    private ValueRange(long minSmallest, long minLargest, long maxSmallest, long maxLargest) {
        this.minSmallest = minSmallest;
        this.minLargest = minLargest;
        this.maxSmallest = maxSmallest;
        this.maxLargest = maxLargest;
    }

    public static ValueRange of(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("Minimum value must be less than maximum value");
        }
        return new ValueRange(min, min, max, max);
    }

    public static ValueRange of(long min, long maxSmallest, long maxLargest) {
        return of(min, min, maxSmallest, maxLargest);
    }

    public static ValueRange of(long minSmallest, long minLargest, long maxSmallest, long maxLargest) {
        if (minSmallest > minLargest) {
            throw new IllegalArgumentException("Smallest minimum value must be less than largest minimum value");
        }
        if (maxSmallest > maxLargest) {
            throw new IllegalArgumentException("Smallest maximum value must be less than largest maximum value");
        }
        if (minLargest > maxLargest) {
            throw new IllegalArgumentException("Minimum value must be less than maximum value");
        }
        if (minSmallest > maxSmallest) {
            throw new IllegalArgumentException("Smallest minimum value must be less than smallest maximum value");
        }
        return new ValueRange(minSmallest, minLargest, maxSmallest, maxLargest);
    }

    public boolean isFixed() {
        return minSmallest == minLargest && maxSmallest == maxLargest;
    }

    public long getMinimum() {
        return minSmallest;
    }

    public long getLargestMinimum() {
        return minLargest;
    }

    public long getSmallestMaximum() {
        return maxSmallest;
    }

    public long getMaximum() {
        return maxLargest;
    }

    public boolean isIntValue() {
        return minSmallest >= Integer.MIN_VALUE && maxLargest <= Integer.MAX_VALUE;
    }

    public boolean isValidValue(long value) {
        return value >= minSmallest && value <= maxLargest;
    }

    public boolean isValidIntValue(long value) {
        return isIntValue() && isValidValue(value);
    }

    private String refusal(long value, TemporalField field) {
        return field == null ? "Invalid value (valid values " + this + "): " + value
                : "Invalid value for " + field + " (valid values " + this + "): " + value;
    }

    public long checkValidValue(long value, TemporalField field) {
        if (!isValidValue(value)) {
            throw new DateTimeException(refusal(value, field));
        }
        return value;
    }

    public int checkValidIntValue(long value, TemporalField field) {
        if (!isValidIntValue(value)) {
            throw new DateTimeException(refusal(value, field));
        }
        return (int) value;
    }

    public boolean equals(Object other) {
        return other instanceof ValueRange that && that.minSmallest == minSmallest && that.minLargest == minLargest
                && that.maxSmallest == maxSmallest && that.maxLargest == maxLargest;
    }

    public int hashCode() {
        // the number is the library's own: the API names none
        return Long.hashCode(((minSmallest * 31 + minLargest) * 31 + maxSmallest) * 31 + maxLargest);
    }

    public String toString() {
        StringBuilder text = new StringBuilder();
        text.append(minSmallest);
        if (minSmallest != minLargest) {
            text.append('/').append(minLargest);
        }
        text.append(" - ").append(maxSmallest);
        if (maxSmallest != maxLargest) {
            text.append('/').append(maxLargest);
        }
        return text.toString();
    }
}
