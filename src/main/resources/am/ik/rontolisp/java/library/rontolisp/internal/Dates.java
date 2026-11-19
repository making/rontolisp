// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

// The fields java.util.Date and the classes of java.sql read an instant as: the Julian
// calendar before 1582-10-15 and the Gregorian one from then on, in the host's zone at
// that instant, a year before 1 written as the year of its era (1 BC is 1). Which
// calendar a day near the change is read in, how fields past their range carry, and
// which offset a wall time the clock skips or shows twice takes, are a JDK's answers.
public final class Dates {

    private Dates() {
    }

    /** 1582-10-15, the Gregorian calendar's first day, counted from 1970-01-01. */
    private static final long GREGORIAN = -141427L;

    /** The day of the year each month starts on, in a year of 365 days. */
    private static final int[] STARTS = { 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334 };

    /** The farthest instant every host can be asked of, in seconds either way. */
    private static final long ASKED = 8640000000000L;

    /** The host's offset at an instant: the seconds east of UTC, doubled, one more in summer time. */
    private static native long local0(long epochSecond);

    private static long zone(long epochSecond) {
        return local0(Math.max(-ASKED, Math.min(ASKED, epochSecond)));
    }

    private static boolean leap(long year, boolean julian) {
        return Math.floorMod(year, 4L) == 0 && (julian || Math.floorMod(year, 100L) != 0 || Math.floorMod(year, 400L) == 0);
    }

    /**
     * The first day of a year, counted from 1970-01-01. Year 0 starts the count of either
     * calendar; it is 719,528 days before 1970-01-01 in the Gregorian, two more in the
     * Julian, whose 1582-10-04 is the day before the Gregorian 1582-10-15.
     */
    private static long yearStart(long year, boolean julian) {
        long days = 365 * year + Math.floorDiv(year + 3, 4L);
        if (julian) {
            return days - 719530L;
        }
        return days - Math.floorDiv(year + 99, 100L) + Math.floorDiv(year + 399, 400L) - 719528L;
    }

    private static long monthStart(long year, int month, boolean julian) {
        return yearStart(year, julian) + STARTS[month - 1] + (month > 2 && leap(year, julian) ? 1 : 0);
    }

    /**
     * An instant's fields in the host's zone: the year of its era, the month (1 to 12),
     * the day, the day of the week (0 is Sunday), the hour, minute, second and
     * millisecond, and the offset in seconds.
     */
    public static long[] fields(long millis) {
        long offset = zone(Math.floorDiv(millis, 1000L)) >> 1;
        // the offset added to the time of day, so that no instant overflows
        long shifted = Math.floorMod(millis, 86400000L) + offset * 1000L;
        long day = Math.floorDiv(millis, 86400000L) + Math.floorDiv(shifted, 86400000L);
        long ofDay = Math.floorMod(shifted, 86400000L);
        boolean julian = day < GREGORIAN;
        // a first guess from the mean length of the calendar's year, then the year it is
        long year = julian ? Math.floorDiv((day + 719530L) * 4, 1461L) : Math.floorDiv((day + 719528L) * 400, 146097L);
        while (yearStart(year, julian) > day) {
            year--;
        }
        while (yearStart(year + 1, julian) <= day) {
            year++;
        }
        int month = 12;
        while (monthStart(year, month, julian) > day) {
            month--;
        }
        return new long[] { year > 0 ? year : 1 - year, month, day - monthStart(year, month, julian) + 1,
                Math.floorMod(day + 4, 7L), ofDay / 3600000L, ofDay / 60000L % 60, ofDay / 1000L % 60, ofDay % 1000L,
                offset };
    }

    /**
     * The instant, in milliseconds, of a wall time in the host's zone. The month is 1 to
     * 12; the day and the seconds of the day may be past their range and are carried.
     * A date before 1582-10-15 once the seconds are carried is a Julian one.
     */
    public static long millis(long year, int month, long day, long seconds) {
        long carried = day + Math.floorDiv(seconds, 86400L);
        boolean julian = year < 1582 || year == 1582 && (month < 10 || month == 10 && carried < 15);
        long wall = (monthStart(year, month, julian) + carried - 1) * 86400L + Math.floorMod(seconds, 86400L);
        return instant(wall) * 1000L;
    }

    /**
     * The epoch second a wall time read as UTC stands for in the host's zone. The offsets
     * a day before and a day after are the two it can have; a time the clock shows twice,
     * or skips, is read at the one that is not summer time.
     */
    private static long instant(long wall) {
        long before = zone(wall - 86400L);
        long after = zone(wall + 86400L);
        long early = wall - (before >> 1);
        long late = wall - (after >> 1);
        boolean earlyHolds = zone(early) >> 1 == before >> 1;
        boolean lateHolds = zone(late) >> 1 == after >> 1;
        if (earlyHolds != lateHolds) {
            return earlyHolds ? early : late;
        }
        if ((before & 1) == 0 && (after & 1) != 0) {
            return early;
        }
        if ((after & 1) == 0 && (before & 1) != 0) {
            return late;
        }
        return earlyHolds ? Math.max(early, late) : early;
    }

    /** How a zone is written after a time: the offset, as a JDK writes a zone of no name. */
    public static String zoneName(long[] fields) {
        long offset = fields[8];
        if (offset == 0) {
            return "UTC";
        }
        long minutes = Math.abs(offset) / 60;
        return "GMT" + (offset < 0 ? "-" : "+") + two(minutes / 60) + ":" + two(minutes % 60);
    }

    /** A field in two digits at least. */
    public static String two(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    /**
     * The decimal int from begin to end, with a sign or none, refused as a JDK refuses a
     * range of a text: at the index in it where the digits stop or the int overflows.
     */
    public static int parse(String s, int begin, int end) {
        if (begin == end) {
            throw new NumberFormatException("For input string: \"\"");
        }
        boolean negative = s.charAt(begin) == '-';
        int at = negative || s.charAt(begin) == '+' ? begin + 1 : begin;
        if (at == end) {
            throw refused(s, begin, end, 1);
        }
        long limit = negative ? 2147483648L : 2147483647L;
        long value = 0;
        for (; at < end; at++) {
            int digit = Character.digit(s.charAt(at), 10);
            if (digit < 0 || value * 10 + digit > limit) {
                throw refused(s, begin, end, at - begin);
            }
            value = value * 10 + digit;
        }
        return (int) (negative ? -value : value);
    }

    private static NumberFormatException refused(String s, int begin, int end, int index) {
        return new NumberFormatException("Error at index " + index + " in: \"" + s.substring(begin, end) + "\"");
    }

    /**
     * Reads {@code yyyy-[m]m-[d]d} as a JDK's {@code java.sql} does: null where the text is
     * not of the form or a field out of its range, a {@code NumberFormatException} where a
     * field is no number. The fields are read before their ranges are checked.
     */
    public static int[] date(String s) {
        int firstDash = s.indexOf('-');
        int secondDash = s.indexOf('-', firstDash + 1);
        if (firstDash != 4 || secondDash - firstDash - 1 < 1 || secondDash - firstDash - 1 > 2
                || s.length() - secondDash - 1 < 1 || s.length() - secondDash - 1 > 2) {
            return null;
        }
        int year = parse(s, 0, firstDash);
        int month = parse(s, firstDash + 1, secondDash);
        int day = parse(s, secondDash + 1, s.length());
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        return new int[] { year, month, day };
    }

}
