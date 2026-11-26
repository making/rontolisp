// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.time.Instant;
import java.time.LocalTime;
import rontolisp.internal.Dates;

// A time of day as java.util.Date's instant, written as the time it is in the host's
// zone; one made from its fields is on 1970-01-01.
public class Time extends java.util.Date {

    public Time(long time) {
        super(time);
    }

    /** {@code hh:mm:ss}; each field is any int, carried into the one above. */
    public static Time valueOf(String s) {
        if (s == null) {
            throw new IllegalArgumentException();
        }
        int firstColon = s.indexOf(':');
        int secondColon = s.indexOf(':', firstColon + 1);
        if (firstColon <= 0 || secondColon <= 0 || secondColon >= s.length() - 1) {
            throw new IllegalArgumentException();
        }
        int hour = Dates.parse(s, 0, firstColon);
        int minute = Dates.parse(s, firstColon + 1, secondColon);
        int second = Dates.parse(s, secondColon + 1, s.length());
        return new Time(Dates.millis(1970, 1, 1, hour * 3600L + minute * 60L + second));
    }

    public static Time valueOf(LocalTime time) {
        return new Time(Dates.millis(1970, 1, 1, time.getHour() * 3600L + time.getMinute() * 60L + time.getSecond()));
    }

    public LocalTime toLocalTime() {
        long[] f = Dates.fields(getTime());
        return LocalTime.of((int) f[4], (int) f[5], (int) f[6]);
    }

    public Instant toInstant() {
        throw new UnsupportedOperationException();
    }

    /** {@code hh:mm:ss}. */
    public String toString() {
        long[] f = Dates.fields(getTime());
        return Dates.two(f[4]) + ":" + Dates.two(f[5]) + ":" + Dates.two(f[6]);
    }

}
