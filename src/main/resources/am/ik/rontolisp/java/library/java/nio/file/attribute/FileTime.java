// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio.file.attribute;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

// A point on the time line as whole seconds since 1970 and the nanoseconds past them. A
// value read in a unit is cut toward zero and, past what a long holds, the longest of its
// sign. Its text is the ISO 8601 date and time in UTC, the fraction with no zero at its
// end and none where it is zero, a year before 1 written as its number before Christ
// (year 0 is -0001), as a JDK's are.
public final class FileTime implements Comparable<FileTime> {

    private final long seconds;

    private final int nanos;

    private FileTime(long seconds, int nanos) {
        this.seconds = seconds;
        this.nanos = nanos;
    }

    public static FileTime from(long value, TimeUnit unit) {
        Objects.requireNonNull(unit, "'unit' is null");
        long per = unit.toNanos(1);
        if (per >= 1000000000L) {
            return new FileTime(unit.toSeconds(value), 0);
        }
        long inSecond = 1000000000L / per;
        return new FileTime(Math.floorDiv(value, inSecond), (int) (Math.floorMod(value, inSecond) * per));
    }

    public static FileTime fromMillis(long value) {
        return new FileTime(Math.floorDiv(value, 1000L), (int) (Math.floorMod(value, 1000L) * 1000000L));
    }

    public static FileTime from(Instant instant) {
        Objects.requireNonNull(instant, "'instant' is null");
        return new FileTime(instant.getEpochSecond(), instant.getNano());
    }

    public long to(TimeUnit unit) {
        Objects.requireNonNull(unit, "'unit' is null");
        // the seconds and the rest cut toward zero, the rest of the sign of the seconds
        long whole = seconds < 0 && nanos > 0 ? seconds + 1 : seconds;
        long rest = seconds < 0 && nanos > 0 ? nanos - 1000000000L : nanos;
        long first = unit.convert(whole, TimeUnit.SECONDS);
        if (first == Long.MAX_VALUE || first == Long.MIN_VALUE) {
            return first;
        }
        long sum = first + unit.convert(rest, TimeUnit.NANOSECONDS);
        if (((first ^ sum) & (rest ^ sum)) < 0) {
            return first < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }

    public long toMillis() {
        return to(TimeUnit.MILLISECONDS);
    }

    public Instant toInstant() {
        return Instant.ofEpochSecond(seconds, nanos);
    }

    public int compareTo(FileTime other) {
        int bySeconds = Long.compare(seconds, other.seconds);
        return bySeconds != 0 ? bySeconds : Integer.compare(nanos, other.nanos);
    }

    public boolean equals(Object obj) {
        return obj instanceof FileTime other && seconds == other.seconds && nanos == other.nanos;
    }

    public int hashCode() {
        return (int) (seconds ^ (seconds >>> 32)) + 51 * nanos;
    }

    // The leap years from year 1 up to a year, not counting it, in the Gregorian calendar
    // carried back before its start (negative for a year before 1).
    private static long leapsBefore(long year) {
        long y = year - 1;
        return Math.floorDiv(y, 4L) - Math.floorDiv(y, 100L) + Math.floorDiv(y, 400L);
    }

    // The day of 1 January of a year, counted from 1970-01-01.
    private static long yearStart(long year) {
        return 365 * (year - 1970) + leapsBefore(year) - leapsBefore(1970);
    }

    private static boolean leap(long year) {
        return Math.floorMod(year, 4L) == 0 && (Math.floorMod(year, 100L) != 0 || Math.floorMod(year, 400L) == 0);
    }

    private static void digits(StringBuilder out, long value, int width) {
        String text = Long.toString(value);
        for (int i = text.length(); i < width; i++) {
            out.append('0');
        }
        out.append(text);
    }

    public String toString() {
        long days = Math.floorDiv(seconds, 86400L);
        long second = Math.floorMod(seconds, 86400L);
        // a year of 365.2425 days on average, then corrected by its first day
        long year = 1970 + Math.floorDiv(days * 400, 146097L);
        while (yearStart(year) > days) {
            year--;
        }
        while (yearStart(year + 1) <= days) {
            year++;
        }
        int day = (int) (days - yearStart(year));
        int[] lengths = { 31, leap(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
        int month = 0;
        while (day >= lengths[month]) {
            day -= lengths[month];
            month++;
        }
        StringBuilder out = new StringBuilder();
        if (year <= 0) {
            out.append('-');
            digits(out, 1 - year, 4);
        }
        else {
            digits(out, year, 4);
        }
        out.append('-');
        digits(out, month + 1, 2);
        out.append('-');
        digits(out, day + 1, 2);
        out.append('T');
        digits(out, second / 3600, 2);
        out.append(':');
        digits(out, second / 60 % 60, 2);
        out.append(':');
        digits(out, second % 60, 2);
        if (nanos != 0) {
            out.append('.');
            StringBuilder fraction = new StringBuilder();
            digits(fraction, nanos, 9);
            int end = fraction.length();
            while (fraction.charAt(end - 1) == '0') {
                end--;
            }
            out.append(fraction, 0, end);
        }
        return out.append('Z').toString();
    }
}
