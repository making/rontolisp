// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.sql;

import java.time.Instant;
import java.time.LocalDate;
import rontolisp.internal.Dates;

// A date as java.util.Date's instant, written as the day it is in the host's zone.
public class Date extends java.util.Date {

    public Date(long date) {
        super(date);
    }

    /** {@code yyyy-[m]m-[d]d}; a day past the month's last is carried into the next. */
    public static Date valueOf(String s) {
        if (s == null) {
            throw new IllegalArgumentException();
        }
        int[] fields = Dates.date(s);
        if (fields == null) {
            throw new IllegalArgumentException();
        }
        return new Date(Dates.millis(fields[0], fields[1], fields[2], 0));
    }

    public static Date valueOf(LocalDate date) {
        return new Date(Dates.millis(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), 0));
    }

    public LocalDate toLocalDate() {
        long[] f = Dates.fields(getTime());
        return LocalDate.of((int) f[0], (int) f[1], (int) f[2]);
    }

    public Instant toInstant() {
        throw new UnsupportedOperationException();
    }

    /** {@code yyyy-mm-dd}, the year's last four digits. */
    public String toString() {
        long[] f = Dates.fields(getTime());
        String year = String.valueOf(10000 + f[0] % 10000);
        return year.substring(1) + "-" + Dates.two(f[1]) + "-" + Dates.two(f[2]);
    }

}
