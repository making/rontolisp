// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.time.Instant;
import java.time.LocalDateTime;
import rontolisp.internal.Dates;

// An instant to the nanosecond: java.util.Date's milliseconds hold the whole seconds,
// the nanoseconds the rest. getTime answers the two together in milliseconds, which is
// what Date's hashCode, equals, before and after read.
public class Timestamp extends java.util.Date {

    private int nanos;

    public Timestamp(long time) {
        super(Math.floorDiv(time, 1000L) * 1000L);
        this.nanos = (int) Math.floorMod(time, 1000L) * 1000000;
    }

    private static IllegalArgumentException format() {
        return new IllegalArgumentException("Timestamp format must be yyyy-mm-dd hh:mm:ss[.fffffffff]");
    }

    /**
     * {@code yyyy-[m]m-[d]d hh:mm:ss[.f...]} around blanks: the date is read and checked
     * before the time; the hours, minutes and seconds are any int, carried into the field
     * above; the fraction is one to nine digits.
     */
    public static Timestamp valueOf(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null string");
        }
        String text = s.trim();
        int space = text.indexOf(' ');
        if (space < 0) {
            throw format();
        }
        int[] date = Dates.date(text.substring(0, space));
        if (date == null) {
            throw format();
        }
        String time = text.substring(space + 1);
        int firstColon = time.indexOf(':');
        int secondColon = firstColon < 0 ? -1 : time.indexOf(':', firstColon + 1);
        if (secondColon < 0 || secondColon >= time.length() - 1) {
            throw format();
        }
        int hour = Dates.parse(time, 0, firstColon);
        int minute = Dates.parse(time, firstColon + 1, secondColon);
        int period = time.indexOf('.', secondColon + 1);
        int second;
        int fraction = 0;
        if (period < 0) {
            second = Dates.parse(time, secondColon + 1, time.length());
        }
        else if (period == time.length() - 1) {
            throw format();
        }
        else {
            second = Dates.parse(time, secondColon + 1, period);
            String digits = time.substring(period + 1);
            if (digits.length() > 9 || !Character.isDigit(digits.charAt(0))) {
                throw format();
            }
            fraction = Dates.parse(digits, 0, digits.length());
            for (int i = digits.length(); i < 9; i++) {
                fraction *= 10;
            }
        }
        Timestamp stamp = new Timestamp(
                Dates.millis(date[0], date[1], date[2], hour * 3600L + minute * 60L + second));
        stamp.nanos = fraction;
        return stamp;
    }

    public static Timestamp valueOf(LocalDateTime dateTime) {
        Timestamp stamp = new Timestamp(Dates.millis(dateTime.getYear(), dateTime.getMonthValue(),
                dateTime.getDayOfMonth(), dateTime.getHour() * 3600L + dateTime.getMinute() * 60L + dateTime.getSecond()));
        stamp.nanos = dateTime.getNano();
        return stamp;
    }

    public static Timestamp from(Instant instant) {
        try {
            Timestamp stamp = new Timestamp(Math.multiplyExact(instant.getEpochSecond(), 1000L));
            stamp.nanos = instant.getNano();
            return stamp;
        }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /** The whole seconds, in milliseconds. */
    private long seconds() {
        return super.getTime();
    }

    public long getTime() {
        return seconds() + nanos / 1000000;
    }

    public void setTime(long time) {
        super.setTime(Math.floorDiv(time, 1000L) * 1000L);
        this.nanos = (int) Math.floorMod(time, 1000L) * 1000000;
    }

    public int getNanos() {
        return nanos;
    }

    public void setNanos(int n) {
        if (n > 999999999 || n < 0) {
            throw new IllegalArgumentException("nanos > 999999999 or < 0");
        }
        this.nanos = n;
    }

    public boolean equals(Timestamp ts) {
        return ts != null && super.equals(ts) && nanos == ts.nanos;
    }

    public boolean equals(Object ts) {
        return ts instanceof Timestamp && equals((Timestamp) ts);
    }

    public int hashCode() {
        return super.hashCode();
    }

    public boolean before(Timestamp ts) {
        return compareTo(ts) < 0;
    }

    public boolean after(Timestamp ts) {
        return compareTo(ts) > 0;
    }

    public int compareTo(Timestamp ts) {
        int bySeconds = Long.compare(seconds(), ts.seconds());
        return bySeconds != 0 ? bySeconds : Integer.compare(nanos, ts.nanos);
    }

    /** A date that is no timestamp is compared as one of its milliseconds. */
    public int compareTo(java.util.Date o) {
        return o instanceof Timestamp ? compareTo((Timestamp) o) : compareTo(new Timestamp(o.getTime()));
    }

    public LocalDateTime toLocalDateTime() {
        long[] f = Dates.fields(seconds());
        return LocalDateTime.of((int) f[0], (int) f[1], (int) f[2], (int) f[4], (int) f[5], (int) f[6], nanos);
    }

    public Instant toInstant() {
        return Instant.ofEpochSecond(Math.floorDiv(seconds(), 1000L), nanos);
    }

    /** {@code yyyy-mm-dd hh:mm:ss.f}, the fraction with no zero after its last digit. */
    public String toString() {
        long[] f = Dates.fields(seconds());
        String year = f[0] < 1000 ? String.valueOf(10000 + f[0]).substring(1) : String.valueOf(f[0]);
        String fraction = "0";
        if (nanos > 0) {
            fraction = String.valueOf(1000000000 + nanos).substring(1);
            int end = fraction.length();
            while (fraction.charAt(end - 1) == '0') {
                end--;
            }
            fraction = fraction.substring(0, end);
        }
        return year + "-" + Dates.two(f[1]) + "-" + Dates.two(f[2]) + " " + Dates.two(f[4]) + ":" + Dates.two(f[5])
                + ":" + Dates.two(f[6]) + "." + fraction;
    }

}
