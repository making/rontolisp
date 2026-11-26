// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A raw offset and, where both rules are given, a schedule of daylight saving: a start and
// an end each year, each a day of a month (a day; the n-th or n-th last day of the week;
// the day of the week on or after, or on or before, a day) at a time read in wall,
// standard or UTC time. Daylight saving holds from the year's start to its end, across the
// new year where the end comes first, none in a year where they are the same instant; the
// year is the instant's. The checks and their words, what a rule of day 0 means, and what
// equals compares are a JDK's answers. What a JDK does with a schedule before 1600, near
// a new year where a change is, and with a time mode outside the three, no rule here says,
// and it is refused.
package java.util;

public class SimpleTimeZone extends TimeZone {

    public static final int WALL_TIME = 0;

    public static final int STANDARD_TIME = 1;

    public static final int UTC_TIME = 2;

    /** The days of the months a rule's day is checked against: those of a year of 365. */
    private static final int[] LENGTHS = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };

    /** 1600-01-01T00:00Z. */
    private static final long Y1600 = -11676096000000L;

    /** The long's last three years, where a year's end can be past it. */
    private static final long END = Long.MAX_VALUE - 3 * 366 * CalendarDays.DAY;

    private int raw;

    private int savings = 3600000;

    private int startYear;

    private int startMonth;

    private int startDay;

    private int startDayOfWeek;

    private int startTime;

    private int startTimeMode;

    private int endMonth;

    private int endDay;

    private int endDayOfWeek;

    private int endTime;

    private int endTimeMode;

    /** Whether each rule is given, with a day that is not 0. */
    private boolean starts;

    private boolean ends;

    public SimpleTimeZone(int rawOffset, String ID) {
        raw = rawOffset;
        setID(ID);
    }

    public SimpleTimeZone(int rawOffset, String ID, int startMonth, int startDay, int startDayOfWeek, int startTime,
            int endMonth, int endDay, int endDayOfWeek, int endTime) {
        this(rawOffset, ID, startMonth, startDay, startDayOfWeek, startTime, WALL_TIME, endMonth, endDay, endDayOfWeek,
                endTime, WALL_TIME, 3600000);
    }

    public SimpleTimeZone(int rawOffset, String ID, int startMonth, int startDay, int startDayOfWeek, int startTime,
            int endMonth, int endDay, int endDayOfWeek, int endTime, int dstSavings) {
        this(rawOffset, ID, startMonth, startDay, startDayOfWeek, startTime, WALL_TIME, endMonth, endDay, endDayOfWeek,
                endTime, WALL_TIME, dstSavings);
    }

    public SimpleTimeZone(int rawOffset, String ID, int startMonth, int startDay, int startDayOfWeek, int startTime,
            int startTimeMode, int endMonth, int endDay, int endDayOfWeek, int endTime, int endTimeMode,
            int dstSavings) {
        raw = rawOffset;
        setID(ID);
        start(startMonth, startDay, startDayOfWeek, startTime, startTimeMode);
        end(endMonth, endDay, endDayOfWeek, endTime, endTimeMode);
        if (dstSavings <= 0) {
            throw new IllegalArgumentException("Illegal daylight saving value: " + dstSavings);
        }
        savings = dstSavings;
    }

    public void setStartYear(int year) {
        startYear = year;
    }

    public void setStartRule(int startMonth, int startDay, int startDayOfWeek, int startTime) {
        start(startMonth, startDay, startDayOfWeek, startTime, WALL_TIME);
    }

    public void setEndRule(int endMonth, int endDay, int endDayOfWeek, int endTime) {
        end(endMonth, endDay, endDayOfWeek, endTime, WALL_TIME);
    }

    private void start(int month, int day, int dayOfWeek, int time, int mode) {
        starts = checked("start", month, day, dayOfWeek, time);
        startMonth = month;
        startDay = day;
        startDayOfWeek = dayOfWeek;
        startTime = time;
        startTimeMode = mode;
    }

    private void end(int month, int day, int dayOfWeek, int time, int mode) {
        ends = checked("end", month, day, dayOfWeek, time);
        endMonth = month;
        endDay = day;
        endDayOfWeek = dayOfWeek;
        endTime = time;
        endTimeMode = mode;
    }

    /**
     * Whether a rule is one: a day of 0 is none and nothing of it is checked; else the
     * month, the time, the day of the week and the day, in that order.
     */
    private static boolean checked(String which, int month, int day, int dayOfWeek, int time) {
        if (day == 0) {
            return false;
        }
        if (month < 0 || month > 11) {
            throw new IllegalArgumentException("Illegal " + which + " month " + month);
        }
        if (time < 0 || time > 86400000) {
            throw new IllegalArgumentException("Illegal " + which + " time " + time);
        }
        if (dayOfWeek < -7 || dayOfWeek > 7) {
            throw new IllegalArgumentException("Illegal " + which + " day of week " + Math.abs(dayOfWeek));
        }
        if (dayOfWeek == 0 ? day < 1 || day > LENGTHS[month] : dayOfWeek < 0 && Math.abs((long) day) > LENGTHS[month]) {
            throw new IllegalArgumentException("Illegal " + which + " day " + (dayOfWeek == 0 ? day : Math.abs(day)));
        }
        if (dayOfWeek > 0 && (day < -5 || day > 5)) {
            throw new IllegalArgumentException("Illegal " + which + " day of week in month " + day);
        }
        return true;
    }

    public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
        throw new UnsupportedOperationException(
                "SimpleTimeZone.getOffset(int, int, int, int, int, int) is not supported by the experimental Java subset yet");
    }

    public int getOffset(long date) {
        return daylight(date) ? raw + savings : raw;
    }

    public int getRawOffset() {
        return raw;
    }

    public void setRawOffset(int offsetMillis) {
        raw = offsetMillis;
    }

    public int getDSTSavings() {
        return useDaylightTime() ? savings : 0;
    }

    public boolean useDaylightTime() {
        return starts && ends;
    }

    public boolean inDaylightTime(Date date) {
        return daylight(date.getTime());
    }

    /** Whether daylight saving holds at an instant. */
    private boolean daylight(long instant) {
        if (!useDaylightTime()) {
            return false;
        }
        if (startTimeMode < WALL_TIME || startTimeMode > UTC_TIME || endTimeMode < WALL_TIME || endTimeMode > UTC_TIME) {
            throw new UnsupportedOperationException("a SimpleTimeZone's time mode other than WALL_TIME, STANDARD_TIME"
                    + " and UTC_TIME is not supported by the experimental Java subset yet");
        }
        if (instant < Y1600 || instant > END) {
            throw new UnsupportedOperationException("a SimpleTimeZone's daylight saving before 1600 or in the last three"
                    + " years a long can hold is not supported by the experimental Java subset yet");
        }
        long[] date = new long[3];
        // the new year nearest the instant: within the raw offset and the savings of it, with
        // a change as near, no rule says which year decides
        CalendarDays.date(Math.floorDiv(instant, CalendarDays.DAY), date);
        long year = date[0];
        long newYear = CalendarDays.yearStart(year, false) * CalendarDays.DAY;
        long next = CalendarDays.yearStart(year + 1, false) * CalendarDays.DAY;
        if (next - instant < instant - newYear) {
            newYear = next;
            year++;
        }
        long reach = Math.abs((long) raw) + savings;
        if (Math.abs(instant - newYear) <= reach && (near(year - 1, newYear, reach) || near(year, newYear, reach))) {
            throw new UnsupportedOperationException("a SimpleTimeZone's daylight saving about a new year where it changes"
                    + " is not supported by the experimental Java subset yet");
        }
        // the year of the instant in standard time
        CalendarDays.date(Math.floorDiv(instant, CalendarDays.DAY)
                + Math.floorDiv(Math.floorMod(instant, CalendarDays.DAY) + raw, CalendarDays.DAY), date);
        if (date[0] < startYear) {
            return false;
        }
        long start = change(date[0], true);
        long end = change(date[0], false);
        return start < end ? instant >= start && instant < end : start > end && (instant >= start || instant < end);
    }

    /** Whether a change of a year is within a reach of a new year. */
    private boolean near(long year, long newYear, long reach) {
        return Math.abs(change(year, true) - newYear) <= reach || Math.abs(change(year, false) - newYear) <= reach;
    }

    /** The instant daylight saving starts or ends at in a year. */
    private long change(long year, boolean start) {
        long day = start ? day(year, startMonth, startDay, startDayOfWeek) : day(year, endMonth, endDay, endDayOfWeek);
        int mode = start ? startTimeMode : endTimeMode;
        long clock = mode == UTC_TIME ? 0 : mode == STANDARD_TIME || start ? raw : (long) raw + savings;
        return day * CalendarDays.DAY + (start ? startTime : endTime) - clock;
    }

    /**
     * The day a rule names in a year of the Gregorian calendar: the day of the month; the
     * n-th day of the week from the month's first, or (n negative) back from its last; the
     * day of the week on or after a day, or (the day negative) on or before.
     */
    private static long day(long year, int month, int day, int dayOfWeek) {
        long first = CalendarDays.monthStart(year, month, false);
        if (dayOfWeek == 0) {
            return first + day - 1;
        }
        if (dayOfWeek > 0) {
            if (day > 0) {
                return first + Math.floorMod(dayOfWeek - CalendarDays.dayOfWeek(first), 7) + 7L * (day - 1);
            }
            long last = CalendarDays.first(year, month + 1L, false) - 1;
            return last - Math.floorMod(CalendarDays.dayOfWeek(last) - dayOfWeek, 7) + 7L * (day + 1);
        }
        if (day > 0) {
            long from = first + day - 1;
            return from + Math.floorMod(-dayOfWeek - CalendarDays.dayOfWeek(from), 7);
        }
        long to = first - day - 1;
        return to - Math.floorMod(CalendarDays.dayOfWeek(to) + dayOfWeek, 7);
    }

    public boolean hasSameRules(TimeZone other) {
        return other instanceof SimpleTimeZone that && raw == that.raw && sameSchedule(that);
    }

    /** The same schedule: none in either, or one of the same rules, start year and savings. */
    private boolean sameSchedule(SimpleTimeZone that) {
        boolean on = useDaylightTime();
        return on == that.useDaylightTime() && (!on || startMonth == that.startMonth && startDay == that.startDay
                && startDayOfWeek == that.startDayOfWeek && startTime == that.startTime
                && startTimeMode == that.startTimeMode && endMonth == that.endMonth && endDay == that.endDay
                && endDayOfWeek == that.endDayOfWeek && endTime == that.endTime && endTimeMode == that.endTimeMode
                && startYear == that.startYear && savings == that.savings);
    }

    public boolean equals(Object other) {
        return other instanceof SimpleTimeZone that && getID().equals(that.getID()) && hasSameRules(that);
    }

    public int hashCode() {
        int hash = getID().hashCode() * 31 + raw;
        if (useDaylightTime()) {
            hash = ((hash * 31 + startMonth) * 31 + startDay) * 31 + startDayOfWeek;
            hash = ((hash * 31 + startTime) * 31 + endMonth) * 31 + endDay;
            hash = ((hash * 31 + endDayOfWeek) * 31 + endTime) * 31 + savings;
        }
        return hash;
    }

}
