// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The days GregorianCalendar and the zones of java.util count with, in numbers: a day is
// counted from 1970-01-01, a year is astronomical (1 BC is 0), a month is 0 to 11. The
// Julian calendar has a leap year every fourth, year 0 included; the Gregorian leaves out
// the centuries 400 does not divide. Our own formulation over the day 1 January of a year
// is in each.
final class CalendarDays {

    private CalendarDays() {
    }

    /** The milliseconds of a day. */
    static final long DAY = 86400000L;

    /** 1582-10-15, the first day of the Gregorian calendar, which follows the Julian 1582-10-04. */
    static final long CUT = -141427L;

    /** The days of a year of 365 before each month, and in all. */
    private static final int[] BEFORE = { 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334, 365 };

    static boolean leap(long year, boolean julian) {
        return Math.floorMod(year, 4L) == 0
                && (julian || Math.floorMod(year, 100L) != 0 || Math.floorMod(year, 400L) == 0);
    }

    /**
     * The day 1 January of a year is in one of the calendars. Year 0 starts the count of
     * each: 719,528 days before 1970-01-01 in the Gregorian, 719,530 in the Julian, whose
     * 0001-01-01 is the Gregorian 0000-12-30.
     */
    static long yearStart(long year, boolean julian) {
        long days = 365 * year + Math.floorDiv(year + 3, 4L);
        return julian ? days - 719530L
                : days - Math.floorDiv(year + 99, 100L) + Math.floorDiv(year + 399, 400L) - 719528L;
    }

    /** The first day of a month of a year in one of the calendars. */
    static long monthStart(long year, int month, boolean julian) {
        return yearStart(year, julian) + BEFORE[month] + (month > 1 && leap(year, julian) ? 1 : 0);
    }

    /**
     * The first day of a month counted from a year's January (any number, carried into the
     * year by floor division) in one of the calendars.
     */
    static long first(long year, long month, boolean julian) {
        return monthStart(year + Math.floorDiv(month, 12L), (int) Math.floorMod(month, 12L), julian);
    }

    /**
     * The first day of a month counted from a year's January, in the calendar its days
     * are in: the Julian through October 1582, whose first is a Julian day.
     */
    static long first(long year, long month) {
        long carried = year + Math.floorDiv(month, 12L);
        int of = (int) Math.floorMod(month, 12L);
        return monthStart(carried, of, carried < 1582 || carried == 1582 && of <= 9);
    }

    /** The day of the week of a day, Sunday 1 to Saturday 7: 1970-01-01 was a Thursday. */
    static int dayOfWeek(long day) {
        return (int) Math.floorMod(day + 4, 7L) + 1;
    }

    /**
     * The astronomical year, the month and the day of the month of a day, in the calendar
     * the day is in, into date.
     */
    static void date(long day, long[] date) {
        boolean julian = day < CUT;
        // a first guess from the mean length of the calendar's year, then the year it is
        long year = julian ? Math.floorDiv((day + 719530L) * 4, 1461L) : Math.floorDiv((day + 719528L) * 400, 146097L);
        while (yearStart(year, julian) > day) {
            year--;
        }
        while (yearStart(year + 1, julian) <= day) {
            year++;
        }
        int month = 11;
        while (monthStart(year, month, julian) > day) {
            month--;
        }
        date[0] = year;
        date[1] = month;
        date[2] = day - monthStart(year, month, julian) + 1;
    }

    /**
     * The first day of week 1 of a year or a month that starts on a day: the seven days
     * from the first day of the week on or before it where at least the least count of
     * them are its own, else the seven days after.
     */
    static long weekOne(long first, int firstDayOfWeek, int minimalDays) {
        long start = first - Math.floorMod(dayOfWeek(first) - firstDayOfWeek, 7);
        return 7 - (first - start) >= minimalDays ? start : start + 7;
    }

}
