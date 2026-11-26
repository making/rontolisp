// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The calendar of the Julian days before 1582-10-15 and the Gregorian from then on
// (CalendarDays), lenient: any value of a field counts, carried into the larger ones.
// Beyond the documentation, every answer here is a JDK's: which fields decide and how a
// value past its range carries, which calendar a date is counted in (the Julian through
// a year, else the Gregorian unless the local time is before the change), a local time
// past the long (wrapped), the weeks, and what add and roll make of each field. Where no
// JDK answer was found to model -- a roll of a week field about the change of calendar,
// or of a day in a week another year numbers -- it is refused.
package java.util;

public class GregorianCalendar extends Calendar {

    public static final int BC = 0;

    public static final int AD = 1;

    private static final long DAY = CalendarDays.DAY;

    public GregorianCalendar() {
        this(TimeZone.getDefault());
    }

    public GregorianCalendar(TimeZone zone) {
        super(zone);
        if (zone == null) {
            throw new NullPointerException();
        }
        setTimeInMillis(System.currentTimeMillis());
    }

    // ---- the fields of the instant

    protected void computeFields() {
        TimeZone zone = getTimeZone();
        int[] split = new int[2];
        long offset = zone.offsets(time, split);
        if (kept != 0) {
            // an offset field the program set keeps its value, the other is the zone's
            if ((kept & 1) != 0) {
                split[0] = fields[ZONE_OFFSET];
            }
            if ((kept & 2) != 0) {
                split[1] = fields[DST_OFFSET];
            }
            offset = (long) split[0] + split[1];
        }
        // the local time, exactly: its day and its millisecond of the day
        long shifted = Math.floorMod(time, DAY) + offset;
        long day = Math.floorDiv(time, DAY) + Math.floorDiv(shifted, DAY);
        long ofDay = Math.floorMod(shifted, DAY);
        long[] date = new long[3];
        CalendarDays.date(day, date);
        long year = date[0];
        int month = (int) date[1];
        fields[ERA] = year >= 1 ? AD : BC;
        fields[YEAR] = (int) (year >= 1 ? year : 1 - year);
        fields[MONTH] = month;
        fields[DAY_OF_MONTH] = (int) date[2];
        // the days the year and the month have had, which 1582 lacks ten of in October
        long yearFirst = CalendarDays.first(year, 0);
        long monthFirst = CalendarDays.first(year, month);
        fields[DAY_OF_YEAR] = (int) (day - yearFirst + 1);
        fields[DAY_OF_WEEK] = CalendarDays.dayOfWeek(day);
        fields[DAY_OF_WEEK_IN_MONTH] = (int) ((day - monthFirst) / 7 + 1);
        int weekStart = getFirstDayOfWeek();
        int least = getMinimalDaysInFirstWeek();
        long one = CalendarDays.weekOne(yearFirst, weekStart, least);
        if (day >= CalendarDays.weekOne(CalendarDays.first(year + 1, 0), weekStart, least)) {
            fields[WEEK_OF_YEAR] = 1;
        }
        else if (day >= one) {
            fields[WEEK_OF_YEAR] = (int) ((day - one) / 7 + 1);
        }
        else {
            fields[WEEK_OF_YEAR] = (int) ((day - CalendarDays.weekOne(CalendarDays.first(year - 1, 0), weekStart, least)) / 7 + 1);
        }
        long monthOne = CalendarDays.weekOne(monthFirst, weekStart, least);
        fields[WEEK_OF_MONTH] = day < monthOne ? 0 : (int) ((day - monthOne) / 7 + 1);
        int hour = (int) (ofDay / 3600000);
        fields[AM_PM] = hour / 12;
        fields[HOUR] = hour % 12;
        fields[HOUR_OF_DAY] = hour;
        fields[MINUTE] = (int) (ofDay / 60000 % 60);
        fields[SECOND] = (int) (ofDay / 1000 % 60);
        fields[MILLISECOND] = (int) (ofDay % 1000);
        fields[ZONE_OFFSET] = split[0];
        fields[DST_OFFSET] = split[1];
        for (int field = 0; field < FIELD_COUNT; field++) {
            isSet[field] = true;
            order[field] = 1;
        }
        nextOrder = 2;
        refused = null;
    }

    void instantSet() {
        try {
            super.instantSet();
        }
        catch (UnsupportedOperationException e) {
            // a program that only reads the instant goes on
            refused = e.getMessage();
        }
    }

    void paired() {
        if (!isTimeSet || refused != null) {
            // a set pending, no instant, or fields refused: the next computation takes the
            // new pair
            return;
        }
        if (areFieldsSet) {
            computeFields();
            return;
        }
        // a new zone since the fields were computed: the fields as they stand, read in it,
        // with the new pair; the instant and what the next computation takes stay
        long held = time;
        try {
            computeTime();
            computeFields();
        }
        finally {
            time = held;
        }
    }

    // ---- the instant of the fields

    protected void computeTime() {
        int era = order[ERA] == 0 ? AD : fields[ERA];
        if (era != BC && era != AD) {
            throw new IllegalArgumentException("Invalid era");
        }
        long year = order[YEAR] == 0 ? 1970 : fields[YEAR];
        if (era == BC) {
            year = 1 - year;
        }
        long month = order[MONTH] == 0 ? JANUARY : fields[MONTH];
        // the latest complete combination of the date's fields decides, a week field with
        // the day of the week; on a tie the day of the month, the day of the year, then the
        // week field set last
        int ofMonth = order[DAY_OF_MONTH];
        int ofYear = order[DAY_OF_YEAR];
        int weekday = order[DAY_OF_WEEK];
        int weekOfMonth = order[WEEK_OF_MONTH];
        int inMonth = order[DAY_OF_WEEK_IN_MONTH];
        int weekOfYear = order[WEEK_OF_YEAR];
        int weekOfMonthBy = weekOfMonth == 0 || weekday == 0 ? 0 : Math.max(weekOfMonth, weekday);
        int inMonthBy = inMonth == 0 || weekday == 0 ? 0 : Math.max(inMonth, weekday);
        int weekOfYearBy = weekOfYear == 0 || weekday == 0 ? 0 : Math.max(weekOfYear, weekday);
        int best = Math.max(Math.max(ofMonth, ofYear), Math.max(weekOfMonthBy, Math.max(inMonthBy, weekOfYearBy)));
        int by;
        if (best == 0) {
            // none complete: the latest week field from the first day of the week, the day
            // of the week alone as the first in the month, else the first of the month
            int week = Math.max(weekOfMonth, Math.max(inMonth, weekOfYear));
            by = week == 0 ? (weekday == 0 ? DAY_OF_MONTH : DAY_OF_WEEK_IN_MONTH)
                    : weekOfMonth == week ? WEEK_OF_MONTH : inMonth == week ? DAY_OF_WEEK_IN_MONTH : WEEK_OF_YEAR;
        }
        else if (ofMonth == best) {
            by = DAY_OF_MONTH;
        }
        else if (ofYear == best) {
            by = DAY_OF_YEAR;
        }
        else {
            int week = Math.max(weekOfMonthBy == best ? weekOfMonth : 0,
                    Math.max(inMonthBy == best ? inMonth : 0, weekOfYearBy == best ? weekOfYear : 0));
            by = weekOfMonthBy == best && weekOfMonth == week ? WEEK_OF_MONTH
                    : inMonthBy == best && inMonth == week ? DAY_OF_WEEK_IN_MONTH : WEEK_OF_YEAR;
        }
        // the time of day: the hour of the day or the half and its hour, the later; where
        // neither is complete, those of them set; then the minutes, seconds, milliseconds
        int ofDay = order[HOUR_OF_DAY];
        int half = order[AM_PM];
        int hour = order[HOUR];
        int pair = half == 0 || hour == 0 ? 0 : Math.max(half, hour);
        long hours;
        if (ofDay == 0 && pair == 0) {
            hours = (half == 0 ? 0 : 12 * fields[AM_PM]) + (hour == 0 ? 0L : fields[HOUR]);
        }
        else {
            // the half's hours in 32 bits
            hours = ofDay >= pair ? fields[HOUR_OF_DAY] : 12 * fields[AM_PM] + (long) fields[HOUR];
        }
        long timeOfDay = hours * 3600000 + (order[MINUTE] == 0 ? 0 : fields[MINUTE] * 60000L)
                + (order[SECOND] == 0 ? 0 : fields[SECOND] * 1000L) + (order[MILLISECOND] == 0 ? 0 : fields[MILLISECOND]);
        // the day of the week brought into 1 to 7, the subtraction in 32 bits
        int wanted = Math.floorMod((weekday == 0 ? getFirstDayOfWeek() : fields[DAY_OF_WEEK]) - 1, 7) + 1;
        long day;
        if (year <= (by == DAY_OF_YEAR || by == WEEK_OF_YEAR ? 1582 : 1581)) {
            day = day(by, year, month, true, wanted);
        }
        else {
            day = day(by, year, month, false, wanted);
            if (day + Math.floorDiv(timeOfDay, DAY) < CalendarDays.CUT) {
                day = day(by, year, month, true, wanted);
            }
        }
        boolean zoneSet = order[ZONE_OFFSET] > 1;
        boolean dstSet = order[DST_OFFSET] > 1;
        time = read(day, timeOfDay, zoneSet, dstSet);
        kept = (zoneSet ? 1 : 0) | (dstSet ? 2 : 0);
    }

    /** The day a combination of the date's fields names in one of the calendars. */
    private long day(int by, long year, long month, boolean julian, int wanted) {
        int weekDay = Math.floorMod(wanted - getFirstDayOfWeek(), 7);
        int least = getMinimalDaysInFirstWeek();
        switch (by) {
            case DAY_OF_MONTH:
                return CalendarDays.first(year, month, julian) + (order[DAY_OF_MONTH] == 0 ? 1 : fields[DAY_OF_MONTH]) - 1L;
            case DAY_OF_YEAR:
                return CalendarDays.first(year, 0, julian) + fields[DAY_OF_YEAR] - 1L;
            case WEEK_OF_YEAR:
                return CalendarDays.weekOne(CalendarDays.first(year, 0, julian), getFirstDayOfWeek(), least)
                        + 7 * (fields[WEEK_OF_YEAR] - 1L) + weekDay;
            case WEEK_OF_MONTH:
                // the weeks in 32 bits
                return CalendarDays.weekOne(CalendarDays.first(year, month, julian), getFirstDayOfWeek(), least)
                        + 7 * (fields[WEEK_OF_MONTH] - 1) + weekDay;
            default: {
                // the n-th of the day of the week: on or before the month's first plus 7n - 1
                // (7n in 32 bits), or (n negative) on or after its last plus 7n + 1
                int n = order[DAY_OF_WEEK_IN_MONTH] == 0 ? 1 : fields[DAY_OF_WEEK_IN_MONTH];
                if (n >= 0) {
                    long anchor = CalendarDays.first(year, month, julian) + 7 * n - 1;
                    return anchor - Math.floorMod(CalendarDays.dayOfWeek(anchor) - wanted, 7);
                }
                if (n < -306783378) {
                    throw new UnsupportedOperationException("a DAY_OF_WEEK_IN_MONTH below -306783378"
                            + " is not supported by the experimental Java subset yet");
                }
                long from = CalendarDays.first(year, month + 1, julian) + 7L * n;
                return from + Math.floorMod(wanted - CalendarDays.dayOfWeek(from), 7);
            }
        }
    }

    /**
     * The instant a local day and time of day name in the zone, with an offset field the
     * program set where so: past the long, the exact local time less a multiple of 2^64.
     */
    private long read(long day, long timeOfDay, boolean zoneSet, boolean dstSet) {
        long whole = day + Math.floorDiv(timeOfDay, DAY);
        long ofDay = Math.floorMod(timeOfDay, DAY);
        // a long holds 106,751,991,167 days and 25,975,807 milliseconds either side of
        // 1970, and 60,424,192 more before it
        int past = whole > 106751991167L || whole == 106751991167L && ofDay > 25975807 ? 1
                : whole < -106751991168L || whole == -106751991168L && ofDay < 60424192 ? -1 : 0;
        return getTimeZone().instant(day * DAY + timeOfDay, past, zoneSet, fields[ZONE_OFFSET], dstSet,
                fields[DST_OFFSET]);
    }

    /**
     * The instant a date and a time of day name as a set of them names it: the Julian
     * calendar through 1581, else the Gregorian unless the local time is before its first
     * day.
     */
    private long read(long year, long month, long dayOfMonth, long timeOfDay) {
        long day = CalendarDays.first(year, month, year <= 1581) + dayOfMonth - 1;
        if (year > 1581 && day + Math.floorDiv(timeOfDay, DAY) < CalendarDays.CUT) {
            day = CalendarDays.first(year, month, true) + dayOfMonth - 1;
        }
        return read(day, timeOfDay, false, false);
    }

    /** The millisecond of the day the computed fields are at. */
    private long timeOfDay() {
        return fields[HOUR_OF_DAY] * 3600000L + fields[MINUTE] * 60000L + fields[SECOND] * 1000L + fields[MILLISECOND];
    }

    // ---- add and roll

    public void add(int field, int amount) {
        if (amount == 0) {
            return;
        }
        if (field < 0 || field >= ZONE_OFFSET) {
            throw new IllegalArgumentException();
        }
        complete();
        long instant = time;
        long year = fields[ERA] == AD ? fields[YEAR] : 1L - fields[YEAR];
        switch (field) {
            case MILLISECOND:
                setTimeInMillis(instant + amount);
                return;
            case SECOND:
                setTimeInMillis(instant + amount * 1000L);
                return;
            case MINUTE:
                setTimeInMillis(instant + amount * 60000L);
                return;
            case HOUR:
            case HOUR_OF_DAY:
                setTimeInMillis(instant + amount * 3600000L);
                return;
            case MONTH:
            case YEAR: {
                // the month or the year in 32 bits, the day of the month cut to the new
                // month but in 1582, the time of day kept
                long months = field == MONTH ? year * 12 + (fields[MONTH] + amount)
                        : (long) (int) (year + amount) * 12 + fields[MONTH];
                long to = Math.floorDiv(months, 12L);
                long month = Math.floorMod(months, 12L);
                long dayOfMonth = fields[DAY_OF_MONTH];
                if (to != 1582) {
                    dayOfMonth = Math.min(dayOfMonth, CalendarDays.first(to, month + 1) - CalendarDays.first(to, month));
                }
                setTimeInMillis(read(to, month, dayOfMonth, timeOfDay()));
                return;
            }
            case ERA: {
                // held to BC and AD; the year of the era, the month and the day kept
                int era = Math.max(BC, Math.min(AD, fields[ERA] + amount));
                setTimeInMillis(read(era == AD ? fields[YEAR] : 1L - fields[YEAR], fields[MONTH], fields[DAY_OF_MONTH],
                        timeOfDay()));
                return;
            }
            default: {
                // a day, a week or half a day: the time elapsed, moved back by the change of
                // the offset, then the local time where the clock skips it and that shows
                // another date
                long unit = field == AM_PM ? DAY / 2
                        : field == DAY_OF_MONTH || field == DAY_OF_YEAR || field == DAY_OF_WEEK ? DAY : 7 * DAY;
                TimeZone zone = getTimeZone();
                int offset = fields[ZONE_OFFSET] + fields[DST_OFFSET];
                long elapsed = instant + amount * unit;
                int then = zone.getOffset(elapsed);
                long found = elapsed + offset - then;
                int there = zone.getOffset(found);
                if (there != then && there != offset) {
                    found = elapsed;
                    there = then;
                }
                long moved = instant + offset + amount * unit;
                long[] change = new long[2];
                if (zone.skips(moved, change) && Math.floorDiv(found + there, DAY) != Math.floorDiv(moved, DAY)) {
                    long early = moved - change[1];
                    found = found == early ? moved - change[0] : early;
                }
                setTimeInMillis(found);
            }
        }
    }

    public void roll(int field, boolean up) {
        roll(field, up ? 1 : -1);
    }

    public void roll(int field, int amount) {
        if (amount == 0) {
            return;
        }
        if (field < 0 || field >= ZONE_OFFSET) {
            throw new IllegalArgumentException();
        }
        complete();
        int era = fields[ERA];
        long ofEra = fields[YEAR];
        long year = era == AD ? ofEra : 1 - ofEra;
        long month = fields[MONTH];
        long dayOfMonth = fields[DAY_OF_MONTH];
        long timeOfDay = timeOfDay();
        long day = CalendarDays.first(year, 0) + fields[DAY_OF_YEAR] - 1;
        int weekStart = getFirstDayOfWeek();
        switch (field) {
            case ERA:
                // the other era for an odd amount
                era = (int) Math.floorMod(era + (long) amount, 2L);
                setTimeInMillis(read(era == AD ? ofEra : 1 - ofEra, month, dayOfMonth, timeOfDay));
                return;
            case YEAR:
                ofEra = Math.floorMod(ofEra - 1 + amount, 292278994L) + 1;
                setTimeInMillis(read(era == AD ? ofEra : 1 - ofEra, month, dayOfMonth, timeOfDay));
                return;
            case MONTH: {
                long to = Math.floorMod(fields[MONTH] + amount, 12);
                if (year != 1582) {
                    dayOfMonth = Math.min(dayOfMonth, CalendarDays.first(year, to + 1) - CalendarDays.first(year, to));
                }
                setTimeInMillis(read(year, to, dayOfMonth, timeOfDay));
                return;
            }
            case DAY_OF_MONTH:
            case DAY_OF_YEAR: {
                // over the days the month or the year has, in order
                long from = field == DAY_OF_MONTH ? CalendarDays.first(year, month) : CalendarDays.first(year, 0);
                long count = (field == DAY_OF_MONTH ? CalendarDays.first(year, month + 1) : CalendarDays.first(year + 1, 0))
                        - from;
                day = from + Math.floorMod(day - from + amount, count);
                break;
            }
            case DAY_OF_WEEK: {
                // over the seven days of the week the date is in
                long index = Math.floorMod(CalendarDays.dayOfWeek(day) - weekStart, 7);
                if (year == 1583 && day - index < CalendarDays.first(1583, 0)) {
                    throw refused("DAY_OF_WEEK of a day of 1583 in a week begun in 1582");
                }
                day = day - index + Math.floorMod(index + amount, 7L);
                break;
            }
            case DAY_OF_WEEK_IN_MONTH: {
                // over the month's days of that day of the week
                long from = CalendarDays.first(year, month);
                long to = CalendarDays.first(year, month + 1) - 1;
                long first = from + Math.floorMod(CalendarDays.dayOfWeek(day) - CalendarDays.dayOfWeek(from), 7);
                day = first + 7 * Math.floorMod((day - first) / 7 + amount, (to - first) / 7 + 1);
                break;
            }
            case WEEK_OF_MONTH: {
                // over the month's weeks 1 to the last day's, to the same day of the week, else
                // the month's first or last day
                if (year <= 1582) {
                    throw refused("WEEK_OF_MONTH before 1583");
                }
                long from = CalendarDays.first(year, month);
                long to = CalendarDays.first(year, month + 1) - 1;
                long one = CalendarDays.weekOne(from, weekStart, getMinimalDaysInFirstWeek());
                long week = day < one ? 0 : (day - one) / 7 + 1;
                long target = Math.floorMod(week - 1 + amount, (to - one) / 7 + 1) + 1;
                day = Math.max(from, Math.min(to, one + 7 * (target - 1) + Math.floorMod(CalendarDays.dayOfWeek(day) - weekStart, 7)));
                break;
            }
            case WEEK_OF_YEAR: {
                // over the year's days of that day of the week in a week the year numbers
                if (year <= 1581) {
                    throw refused("WEEK_OF_YEAR before 1582");
                }
                int least = getMinimalDaysInFirstWeek();
                long from = Math.max(CalendarDays.first(year, 0), CalendarDays.weekOne(CalendarDays.first(year, 0), weekStart, least));
                long to = Math.min(CalendarDays.first(year + 1, 0), CalendarDays.weekOne(CalendarDays.first(year + 1, 0), weekStart, least)) - 1;
                if (day < from || day > to) {
                    throw refused("WEEK_OF_YEAR of a day in a week another year numbers");
                }
                long first = from + Math.floorMod(CalendarDays.dayOfWeek(day) - CalendarDays.dayOfWeek(from), 7);
                day = first + 7 * Math.floorMod((day - first) / 7 + amount, (to - first) / 7 + 1);
                break;
            }
            default: {
                // the time fields over their ranges; for the hours a whole number of turns
                // rolls by one
                int range = field == AM_PM ? 2 : field == HOUR ? 12 : field == HOUR_OF_DAY ? 24 : field == MILLISECOND ? 1000 : 60;
                long unit = field == AM_PM ? DAY / 2
                        : field == HOUR || field == HOUR_OF_DAY ? 3600000 : field == MINUTE ? 60000 : field == SECOND ? 1000 : 1;
                long value = fields[field];
                long turn = (field == HOUR || field == HOUR_OF_DAY) && amount % range == 0 ? (amount > 0 ? 1 : -1) : amount;
                long moved = timeOfDay + (Math.floorMod(value + turn, range) - value) * unit;
                long[] change = new long[2];
                if (getTimeZone().skips(day * DAY + moved, change) && fields[ZONE_OFFSET] + fields[DST_OFFSET] == change[1]) {
                    throw refused("a time field to a time the clock skips, from after that change");
                }
                setTimeInMillis(read(year, month, dayOfMonth, moved));
                return;
            }
        }
        long[] date = new long[3];
        CalendarDays.date(day, date);
        setTimeInMillis(read(date[0], date[1], date[2], timeOfDay));
    }

    private static UnsupportedOperationException refused(String what) {
        return new UnsupportedOperationException(
                "GregorianCalendar.roll of " + what + " is not supported by the experimental Java subset yet");
    }

    // ---- the members of no rule here

    public int getMinimum(int field) {
        throw limits("getMinimum");
    }

    public int getMaximum(int field) {
        throw limits("getMaximum");
    }

    public int getGreatestMinimum(int field) {
        throw limits("getGreatestMinimum");
    }

    public int getLeastMaximum(int field) {
        throw limits("getLeastMaximum");
    }

    private static UnsupportedOperationException limits(String name) {
        return new UnsupportedOperationException(
                "GregorianCalendar." + name + "(int) is not supported by the experimental Java subset yet");
    }

    public boolean equals(Object obj) {
        return obj == this || obj instanceof GregorianCalendar that && same(that);
    }

}
