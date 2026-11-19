// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.io.Serializable;
import java.time.Instant;
import rontolisp.internal.Dates;

// An instant as the milliseconds since 1970-01-01T00:00Z. It is written in the host's
// zone at that instant and in the calendar rontolisp.internal.Dates reads; the members
// that read or set one field of it, deprecated since JDK 1.1, are not here.
public class Date implements Serializable, Comparable<Date> {

    private static final String[] DAYS = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };

    private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec" };

    private long time;

    public Date() {
        this(System.currentTimeMillis());
    }

    public Date(long date) {
        this.time = date;
    }

    public static Date from(Instant instant) {
        try {
            return new Date(instant.toEpochMilli());
        }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public boolean before(Date when) {
        return getTime() < when.getTime();
    }

    public boolean after(Date when) {
        return getTime() > when.getTime();
    }

    public boolean equals(Object obj) {
        return obj instanceof Date && getTime() == ((Date) obj).getTime();
    }

    public int hashCode() {
        long ht = getTime();
        return (int) ht ^ (int) (ht >>> 32);
    }

    public int compareTo(Date anotherDate) {
        return Long.compare(getTime(), anotherDate.getTime());
    }

    public Instant toInstant() {
        return Instant.ofEpochMilli(getTime());
    }

    /** {@code EEE MMM dd HH:mm:ss zzz yyyy}, the year with no sign or padding. */
    public String toString() {
        long[] f = Dates.fields(getTime());
        return DAYS[(int) f[3]] + " " + MONTHS[(int) f[1] - 1] + " " + Dates.two(f[2]) + " " + Dates.two(f[4]) + ":"
                + Dates.two(f[5]) + ":" + Dates.two(f[6]) + " " + Dates.zoneName(f) + " " + f[0];
    }

}
