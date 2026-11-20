// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Weeks as a locale counts them: the day a week starts on, and how many days of a month
// or a year its first week must hold; the days before that week are week 0 of a month
// or a year, and the last week of the week-based year before. The weeks of the locales
// the library has are CLDR's as a JDK answers them: Monday and 4 in the United Kingdom,
// Sunday and 1 in the others and in a locale of no country. One object per pair, so a
// field of it is one object too. How a formatter's fields of weeks are resolved in each
// style is a JDK 25's answers to a grid (.kb/java-frontend.md, "java.time: the
// formatter").
// Third-party: the weeks of the locales and the name of a week are Unicode CLDR data
// (Unicode-3.0), as a running JDK answers them, Copyright (c) 1991-2026 Unicode, Inc.
// Notice: THIRD-PARTY-NOTICES.txt, "Unicode".
package java.time.temporal;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class WeekFields implements Serializable {

    /** The pairs made so far, by day and count. */
    private static final WeekFields[] MADE = new WeekFields[49];

    public static final WeekFields ISO = of(DayOfWeek.MONDAY, 4);

    public static final WeekFields SUNDAY_START = of(DayOfWeek.SUNDAY, 1);

    public static final TemporalUnit WEEK_BASED_YEARS = IsoFields.WEEK_BASED_YEARS;

    private final DayOfWeek firstDayOfWeek;

    private final int minimalDays;

    private final TemporalField dayOfWeek = new Computed(this, 0);

    private final TemporalField weekOfMonth = new Computed(this, 1);

    private final TemporalField weekOfYear = new Computed(this, 2);

    private final TemporalField weekOfWeekBasedYear = new Computed(this, 3);

    private final TemporalField weekBasedYear = new Computed(this, 4);

    private WeekFields(DayOfWeek firstDayOfWeek, int minimalDays) {
        this.firstDayOfWeek = firstDayOfWeek;
        this.minimalDays = minimalDays;
    }

    public static WeekFields of(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return locale.getCountry().equals("GB") ? ISO : SUNDAY_START;
    }

    public static synchronized WeekFields of(DayOfWeek firstDayOfWeek, int minimalDaysInFirstWeek) {
        if (minimalDaysInFirstWeek < 1 || minimalDaysInFirstWeek > 7) {
            throw new IllegalArgumentException("Minimal number of days is invalid");
        }
        int key = firstDayOfWeek.ordinal() * 7 + minimalDaysInFirstWeek - 1;
        WeekFields made = MADE[key];
        if (made == null) {
            made = new WeekFields(firstDayOfWeek, minimalDaysInFirstWeek);
            MADE[key] = made;
        }
        return made;
    }

    public DayOfWeek getFirstDayOfWeek() {
        return firstDayOfWeek;
    }

    public int getMinimalDaysInFirstWeek() {
        return minimalDays;
    }

    public TemporalField dayOfWeek() {
        return dayOfWeek;
    }

    public TemporalField weekOfMonth() {
        return weekOfMonth;
    }

    public TemporalField weekOfYear() {
        return weekOfYear;
    }

    public TemporalField weekOfWeekBasedYear() {
        return weekOfWeekBasedYear;
    }

    public TemporalField weekBasedYear() {
        return weekBasedYear;
    }

    public boolean equals(Object other) {
        return other instanceof WeekFields that && that.firstDayOfWeek == firstDayOfWeek
                && that.minimalDays == minimalDays;
    }

    public int hashCode() {
        return firstDayOfWeek.ordinal() * 7 + minimalDays;
    }

    public String toString() {
        return "WeekFields[" + firstDayOfWeek + "," + minimalDays + "]";
    }

    /** The day of these weeks an ISO day of the week is, from 1. */
    int localized(int isoDayOfWeek) {
        return Math.floorMod(isoDayOfWeek - firstDayOfWeek.getValue(), 7) + 1;
    }

    /**
     * Where week 1 of a period starts, counted in days from the period's first day: the
     * first start of a week in it, or the start of the week before where the days
     * before the first start are enough. The period's first day is found from any day
     * of it, by its index in the period and its day of these weeks.
     */
    int weekOne(long index, int localizedDay) {
        int first = (int) Math.floorMod(localizedDay - 1 - index, 7L) + 1;
        int start = Math.floorMod(1 - first, 7);
        return start >= minimalDays ? start - 7 : start;
    }

    /** The week of a period the day at an index is in. */
    long weekIn(long index, int localizedDay) {
        return Math.floorDiv(index - weekOne(index, localizedDay), 7L) + 1;
    }

    private static int length(long year) {
        return Year.isLeap(year) ? 366 : 365;
    }

    /**
     * The week-based year of a day of a year, and its week there: the year before for a
     * day before week 1, the year after for a day of the year after's week 1.
     * @return the year in the first slot, the week in the second, the weeks of that year
     * in the third
     */
    long[] weekBased(long year, int dayOfYear, int localizedDay) {
        long index = dayOfYear - 1;
        int length = length(year);
        long one = weekOne(index, localizedDay);
        long next = length + weekOne(index - length, localizedDay);
        if (index < one) {
            int before = length(year - 1);
            long from = weekOne(index + before, localizedDay) - before;
            return new long[] {year - 1, Math.floorDiv(index - from, 7L) + 1, (one - from) / 7};
        }
        if (index >= next) {
            int after = length(year + 1);
            long end = length + after + weekOne(index - length - after, localizedDay);
            return new long[] {year + 1, Math.floorDiv(index - next, 7L) + 1, (end - next) / 7};
        }
        return new long[] {year, Math.floorDiv(index - one, 7L) + 1, (next - one) / 7};
    }

    /** How many weeks a week-based year of these weeks has: those of July 1, a day of its own. */
    long weeksOf(long year) {
        LocalDate july = LocalDate.of((int) year, 7, 1);
        return weekBased(year, july.getDayOfYear(), localized(july.getDayOfWeek().getValue()))[2];
    }

    /** The epoch day of a week of a year of these weeks and a day of it. */
    long weekDate(long year, long week, long localizedDay) {
        long first = LocalDate.of((int) year, 1, 1).toEpochDay();
        int day = localized((int) Math.floorMod(first + 3, 7L) + 1);
        return Math.addExact(Math.addExact(first + weekOne(0, day), Math.multiplyExact(Math.subtractExact(week, 1), 7L)),
                Math.subtractExact(localizedDay, 1));
    }

    /** A field of these weeks: 0 the day of the week, then the weeks of a month and of a year, then of a week-based year, and that year. */
    private static final class Computed implements TemporalField {

        private static final String[] NAMES = {"DayOfWeek", "WeekOfMonth", "WeekOfYear", "WeekOfWeekBasedYear",
                "WeekBasedYear"};

        private final WeekFields weeks;

        private final int kind;

        Computed(WeekFields weeks, int kind) {
            this.weeks = weeks;
            this.kind = kind;
        }

        public TemporalUnit getBaseUnit() {
            return kind == 0 ? ChronoUnit.DAYS : kind < 4 ? ChronoUnit.WEEKS : IsoFields.WEEK_BASED_YEARS;
        }

        public TemporalUnit getRangeUnit() {
            return kind == 0 ? ChronoUnit.WEEKS : kind == 1 ? ChronoUnit.MONTHS : kind == 2 ? ChronoUnit.YEARS
                    : kind == 3 ? IsoFields.WEEK_BASED_YEARS : ChronoUnit.FOREVER;
        }

        public ValueRange range() {
            return kind == 0 ? ValueRange.of(1, 7) : kind == 1 ? ValueRange.of(0, 1, 4, 6)
                    : kind == 2 ? ValueRange.of(0, 1, 52, 54) : kind == 3 ? ValueRange.of(1, 52, 53)
                    : ChronoField.YEAR.range();
        }

        public boolean isDateBased() {
            return true;
        }

        public boolean isTimeBased() {
            return false;
        }

        public boolean isSupportedBy(TemporalAccessor temporal) {
            if (!temporal.isSupported(ChronoField.DAY_OF_WEEK)) {
                return false;
            }
            return kind == 0 || temporal.isSupported(kind == 1 ? ChronoField.DAY_OF_MONTH : ChronoField.DAY_OF_YEAR);
        }

        /** The day of these weeks a value is on: the first field read of each. */
        private int day(TemporalAccessor temporal) {
            return weeks.localized(temporal.get(ChronoField.DAY_OF_WEEK));
        }

        public ValueRange rangeRefinedBy(TemporalAccessor temporal) {
            if (kind == 0 || kind == 4) {
                return range();
            }
            int day = day(temporal);
            if (kind == 3) {
                int dayOfYear = temporal.get(ChronoField.DAY_OF_YEAR);
                return ValueRange.of(1, weeks.weekBased(temporal.getLong(ChronoField.YEAR), dayOfYear, day)[2]);
            }
            ChronoField within = kind == 1 ? ChronoField.DAY_OF_MONTH : ChronoField.DAY_OF_YEAR;
            long index = temporal.get(within) - 1;
            long last = temporal.range(within).getMaximum() - 1;
            return ValueRange.of(weeks.weekIn(0, (int) Math.floorMod(day - 1 - index, 7L) + 1),
                    weeks.weekIn(last, (int) Math.floorMod(day - 1 + last - index, 7L) + 1));
        }

        public long getFrom(TemporalAccessor temporal) {
            int day = day(temporal);
            switch (kind) {
                case 0:
                    return day;
                case 1:
                    return weeks.weekIn(temporal.get(ChronoField.DAY_OF_MONTH) - 1, day);
                case 2:
                    return weeks.weekIn(temporal.get(ChronoField.DAY_OF_YEAR) - 1, day);
                case 3: {
                    int dayOfYear = temporal.get(ChronoField.DAY_OF_YEAR);
                    return weeks.weekBased(temporal.getLong(ChronoField.YEAR), dayOfYear, day)[1];
                }
                default: {
                    long year = temporal.getLong(ChronoField.YEAR);
                    return weeks.weekBased(year, temporal.get(ChronoField.DAY_OF_YEAR), day)[0];
                }
            }
        }

        /**
         * A day of the week moved by days, a week by weeks; a week-based year keeps the
         * week (the year's last where it has fewer) and the day of the week.
         */
        @SuppressWarnings("unchecked")
        public <R extends Temporal> R adjustInto(R temporal, long newValue) {
            if (kind == 4) {
                int year = range().checkValidIntValue(newValue, this);
                int day = day(temporal);
                long[] now = weeks.weekBased(temporal.getLong(ChronoField.YEAR), temporal.get(ChronoField.DAY_OF_YEAR), day);
                long week = Math.min(now[1], weeks.weeksOf(year));
                return (R) temporal.with(LocalDate.ofEpochDay(weeks.weekDate(year, week, day)));
            }
            long current = getFrom(temporal);
            range().checkValidValue(newValue, this);
            return (R) temporal.plus(newValue - current, kind == 0 ? ChronoUnit.DAYS : ChronoUnit.WEEKS);
        }

        /**
         * The day of these weeks is the ISO day of the week, checked in every style; a
         * year, a month or a week-based year with a week and a day of the week are a date,
         * those fields taken out. Strictly the date has to be in the month or the year
         * given; a week-based year's week is checked against that year's weeks but
         * leniently, a month's or a year's against the field's range.
         */
        public TemporalAccessor resolve(Map<TemporalField, Long> fieldValues, TemporalAccessor partialTemporal,
                ResolverStyle resolverStyle) {
            if (kind == 0) {
                int day = range().checkValidIntValue(fieldValues.remove(this), this);
                fieldValues.put(ChronoField.DAY_OF_WEEK,
                        (long) (Math.floorMod(weeks.firstDayOfWeek.getValue() - 1 + day - 1, 7) + 1));
                return null;
            }
            Long iso = fieldValues.get(ChronoField.DAY_OF_WEEK);
            if (iso == null) {
                return null;
            }
            boolean lenient = resolverStyle == ResolverStyle.LENIENT;
            boolean strict = resolverStyle == ResolverStyle.STRICT;
            if (kind >= 3) {
                // a year among the fields takes its weeks only (a JDK's answer)
                Long year = fieldValues.get(weeks.weekBasedYear);
                Long week = fieldValues.get(weeks.weekOfWeekBasedYear);
                if (year == null || week == null || fieldValues.containsKey(ChronoField.YEAR)) {
                    return null;
                }
                int y = weeks.weekBasedYear.range().checkValidIntValue(year, weeks.weekBasedYear);
                int day = weeks.localized(ChronoField.DAY_OF_WEEK.checkValidIntValue(iso));
                if (!lenient) {
                    ValueRange.of(1, weeks.weeksOf(y)).checkValidValue(week, weeks.weekOfWeekBasedYear);
                }
                LocalDate date = LocalDate.ofEpochDay(weeks.weekDate(y, week, day));
                fieldValues.remove(weeks.weekBasedYear);
                fieldValues.remove(weeks.weekOfWeekBasedYear);
                fieldValues.remove(ChronoField.DAY_OF_WEEK);
                return date;
            }
            Long year = fieldValues.get(ChronoField.YEAR);
            Long month = kind == 1 ? fieldValues.get(ChronoField.MONTH_OF_YEAR) : Long.valueOf(1);
            if (year == null || month == null) {
                return null;
            }
            int y = ChronoField.YEAR.checkValidIntValue(year);
            long week = fieldValues.get(this);
            int day = weeks.localized(ChronoField.DAY_OF_WEEK.checkValidIntValue(iso));
            LocalDate start;
            if (lenient) {
                start = LocalDate.of(y, 1, 1).plusMonths(Math.subtractExact(month, 1));
            }
            else {
                start = LocalDate.of(y, ChronoField.MONTH_OF_YEAR.checkValidIntValue(month), 1);
                range().checkValidValue(week, this);
            }
            long first = start.toEpochDay();
            int startDay = weeks.localized((int) Math.floorMod(first + 3, 7L) + 1);
            LocalDate date = LocalDate.ofEpochDay(Math.addExact(Math.addExact(first + weeks.weekOne(0, startDay),
                    Math.multiplyExact(Math.subtractExact(week, 1), 7L)), day - 1));
            if (strict && (kind == 1 ? date.getMonthValue() != start.getMonthValue() : date.getYear() != y)) {
                throw new DateTimeException("Strict mode rejected resolved date as it is in a different "
                        + (kind == 1 ? "month" : "year"));
            }
            fieldValues.remove(this);
            fieldValues.remove(ChronoField.YEAR);
            if (kind == 1) {
                fieldValues.remove(ChronoField.MONTH_OF_YEAR);
            }
            fieldValues.remove(ChronoField.DAY_OF_WEEK);
            return date;
        }

        public String getDisplayName(Locale locale) {
            Objects.requireNonNull(locale, "locale");
            if (kind == 2) {
                // CLDR's word for a week, as a JDK writes it for each locale the library has
                return locale.getLanguage().isEmpty() ? "Week" : "week";
            }
            return NAMES[kind];
        }

        public String toString() {
            return NAMES[kind] + "[" + weeks + "]";
        }
    }
}
