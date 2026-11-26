// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The quarters and the week-based years of ISO-8601. A quarter is three months from
// January, April, July or October. Week 1 of a week-based year is the week, Monday to
// Sunday, holding its January 4: so a year has 52 or 53 weeks and its first days may
// be the last week of the year before. How a formatter's fields of them are resolved in
// each style -- which range a value is checked against, what a value past it makes --
// is a JDK 25's answers to a grid (.kb/java-frontend.md, "java.time: the formatter").
// A value of another calendar has none of them, as a JDK's has not.
// Third-party: the name of a week is Unicode CLDR data (Unicode-3.0), as a running JDK
// writes it, Copyright (c) 1991-2026 Unicode, Inc.
// Notice: THIRD-PARTY-NOTICES.txt, "Unicode".
package java.time.temporal;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class IsoFields {

    public static final TemporalField DAY_OF_QUARTER = new Field(0);

    public static final TemporalField QUARTER_OF_YEAR = new Field(1);

    public static final TemporalField WEEK_OF_WEEK_BASED_YEAR = new Field(2);

    public static final TemporalField WEEK_BASED_YEAR = new Field(3);

    public static final TemporalUnit WEEK_BASED_YEARS = new Unit(0);

    public static final TemporalUnit QUARTER_YEARS = new Unit(1);

    private IsoFields() {
    }

    /**
     * Whether the program makes a calendar other than ISO's: answered by the compiler, so
     * that a program whose dates are all ISO's never asks a value for its calendar.
     */
    static native boolean ownCalendars();

    /** Whether a value is of ISO's calendar, or of none: the only one whose quarters and weeks these are. */
    private static boolean iso(TemporalAccessor temporal) {
        return Chronology.from(temporal).equals(IsoChronology.INSTANCE);
    }

    /** The epoch day of a year's January 1 (java.time's Iso counts days the same way). */
    private static long januaryFirst(long year) {
        return 365 * year + Math.floorDiv(year + 3, 4L) - Math.floorDiv(year + 99, 100L)
                + Math.floorDiv(year + 399, 400L) - 719528L;
    }

    /** The ISO day of the week of an epoch day: 1970-01-01 was a Thursday. */
    private static int dayOfWeek(long epochDay) {
        return (int) Math.floorMod(epochDay + 3, 7L) + 1;
    }

    /** The epoch day week 1 of a week-based year starts: the Monday on or before January 4. */
    private static long weekOne(long year) {
        long fourth = januaryFirst(year) + 3;
        return fourth - dayOfWeek(fourth) + 1;
    }

    private static long weekBasedYear(LocalDate date) {
        long day = date.toEpochDay();
        long year = date.getYear();
        return day < weekOne(year) ? year - 1 : day >= weekOne(year + 1) ? year + 1 : year;
    }

    private static int weeks(long weekBasedYear) {
        return (int) ((weekOne(weekBasedYear + 1) - weekOne(weekBasedYear)) / 7);
    }

    private static LocalDate dateOf(TemporalAccessor temporal) {
        return LocalDate.ofEpochDay(temporal.getLong(ChronoField.EPOCH_DAY));
    }

    /** The date of a week-based year, a week and a day of the week, counted on from week 1. */
    private static LocalDate weekDate(long weekBasedYear, long week, long dayOfWeek) {
        return LocalDate.ofEpochDay(Math.addExact(Math.addExact(weekOne(weekBasedYear),
                Math.multiplyExact(Math.subtractExact(week, 1), 7L)), Math.subtractExact(dayOfWeek, 1)));
    }

    private static final class Field implements TemporalField {

        /** 0 the day of the quarter, 1 the quarter, 2 the week of the week-based year, 3 that year. */
        private final int kind;

        Field(int kind) {
            this.kind = kind;
        }

        public TemporalUnit getBaseUnit() {
            return kind == 0 ? ChronoUnit.DAYS : kind == 1 ? QUARTER_YEARS : kind == 2 ? ChronoUnit.WEEKS
                    : WEEK_BASED_YEARS;
        }

        public TemporalUnit getRangeUnit() {
            return kind == 0 ? QUARTER_YEARS : kind == 1 ? ChronoUnit.YEARS : kind == 2 ? WEEK_BASED_YEARS
                    : ChronoUnit.FOREVER;
        }

        public ValueRange range() {
            return kind == 0 ? ValueRange.of(1, 90, 92) : kind == 1 ? ValueRange.of(1, 4)
                    : kind == 2 ? ValueRange.of(1, 52, 53) : ChronoField.YEAR.range();
        }

        public boolean isDateBased() {
            return true;
        }

        public boolean isTimeBased() {
            return false;
        }

        public boolean isSupportedBy(TemporalAccessor temporal) {
            if (IsoFields.ownCalendars()) {
                if (!iso(temporal)) {
                    return false;
                }
            }
            if (kind == 0) {
                return temporal.isSupported(ChronoField.DAY_OF_YEAR) && temporal.isSupported(ChronoField.MONTH_OF_YEAR)
                        && temporal.isSupported(ChronoField.YEAR);
            }
            return temporal.isSupported(kind == 1 ? ChronoField.MONTH_OF_YEAR : ChronoField.EPOCH_DAY);
        }

        private void supported(TemporalAccessor temporal) {
            if (!isSupportedBy(temporal)) {
                throw new UnsupportedTemporalTypeException("Unsupported field: " + this);
            }
        }

        public ValueRange rangeRefinedBy(TemporalAccessor temporal) {
            supported(temporal);
            if (kind == 0) {
                long quarter = QUARTER_OF_YEAR.getFrom(temporal);
                if (quarter == 1) {
                    return Year.isLeap(temporal.getLong(ChronoField.YEAR)) ? ValueRange.of(1, 91) : ValueRange.of(1, 90);
                }
                return ValueRange.of(1, quarter == 2 ? 91 : 92);
            }
            return kind == 2 ? ValueRange.of(1, weeks(weekBasedYear(dateOf(temporal)))) : range();
        }

        public long getFrom(TemporalAccessor temporal) {
            supported(temporal);
            switch (kind) {
                case 0: {
                    int month = temporal.get(ChronoField.MONTH_OF_YEAR);
                    boolean leap = Year.isLeap(temporal.getLong(ChronoField.YEAR));
                    return temporal.get(ChronoField.DAY_OF_YEAR) - Month.of(month).firstMonthOfQuarter().firstDayOfYear(leap)
                            + 1;
                }
                case 1:
                    return (temporal.getLong(ChronoField.MONTH_OF_YEAR) + 2) / 3;
                case 2: {
                    LocalDate date = dateOf(temporal);
                    return (date.toEpochDay() - weekOne(weekBasedYear(date))) / 7 + 1;
                }
                default:
                    return weekBasedYear(dateOf(temporal));
            }
        }

        /**
         * The day or the quarter moved by the days or the months between, the week by
         * weeks; a week-based year keeps the week (the last where the year has 52) and
         * the day of the week.
         */
        @SuppressWarnings("unchecked")
        public <R extends Temporal> R adjustInto(R temporal, long newValue) {
            switch (kind) {
                case 0: {
                    long current = getFrom(temporal);
                    range().checkValidValue(newValue, this);
                    return (R) temporal.with(ChronoField.DAY_OF_YEAR,
                            temporal.getLong(ChronoField.DAY_OF_YEAR) + (newValue - current));
                }
                case 1: {
                    long current = getFrom(temporal);
                    range().checkValidValue(newValue, this);
                    return (R) temporal.with(ChronoField.MONTH_OF_YEAR,
                            temporal.getLong(ChronoField.MONTH_OF_YEAR) + (newValue - current) * 3);
                }
                case 2:
                    range().checkValidValue(newValue, this);
                    return (R) temporal.plus(Math.subtractExact(newValue, getFrom(temporal)), ChronoUnit.WEEKS);
                default: {
                    supported(temporal);
                    int year = range().checkValidIntValue(newValue, this);
                    LocalDate date = dateOf(temporal);
                    long week = WEEK_OF_WEEK_BASED_YEAR.getFrom(date);
                    if (week == 53 && weeks(year) == 52) {
                        week = 52;
                    }
                    return (R) temporal.with(weekDate(year, week, date.getDayOfWeek().getValue()));
                }
            }
        }

        /**
         * A date of a year, a quarter and a day of it, or of a week-based year, a week and
         * a day of the week; the fields taken out. Strictly a value is checked against the
         * range of its year or quarter, smartly against the field's range, a day of a
         * quarter or a week past the last running on; leniently nothing is checked.
         */
        public TemporalAccessor resolve(Map<TemporalField, Long> fieldValues, TemporalAccessor partialTemporal,
                ResolverStyle resolverStyle) {
            boolean lenient = resolverStyle == ResolverStyle.LENIENT;
            if (kind == 0) {
                Long year = fieldValues.get(ChronoField.YEAR);
                Long quarter = fieldValues.get(QUARTER_OF_YEAR);
                if (year == null || quarter == null) {
                    return null;
                }
                int y = ChronoField.YEAR.checkValidIntValue(year);
                long day = fieldValues.get(this);
                LocalDate start;
                if (lenient) {
                    start = LocalDate.of(y, 1, 1).plusMonths(Math.multiplyExact(Math.subtractExact(quarter, 1), 3));
                }
                else {
                    int q = QUARTER_OF_YEAR.range().checkValidIntValue(quarter, QUARTER_OF_YEAR);
                    start = LocalDate.of(y, (q - 1) * 3 + 1, 1);
                    (resolverStyle == ResolverStyle.STRICT ? rangeRefinedBy(start) : range()).checkValidValue(day, this);
                }
                fieldValues.remove(this);
                fieldValues.remove(ChronoField.YEAR);
                fieldValues.remove(QUARTER_OF_YEAR);
                return start.plusDays(Math.subtractExact(day, 1));
            }
            if (kind < 2) {
                return null;
            }
            Long year = fieldValues.get(WEEK_BASED_YEAR);
            Long week = fieldValues.get(WEEK_OF_WEEK_BASED_YEAR);
            Long day = fieldValues.get(ChronoField.DAY_OF_WEEK);
            if (year == null || week == null || day == null) {
                return null;
            }
            int y = WEEK_BASED_YEAR.range().checkValidIntValue(year, WEEK_BASED_YEAR);
            if (!lenient) {
                ChronoField.DAY_OF_WEEK.checkValidValue(day);
                (resolverStyle == ResolverStyle.STRICT ? ValueRange.of(1, weeks(y))
                        : WEEK_OF_WEEK_BASED_YEAR.range()).checkValidValue(week, WEEK_OF_WEEK_BASED_YEAR);
            }
            LocalDate date = weekDate(y, week, day);
            fieldValues.remove(WEEK_BASED_YEAR);
            fieldValues.remove(WEEK_OF_WEEK_BASED_YEAR);
            fieldValues.remove(ChronoField.DAY_OF_WEEK);
            return date;
        }

        public String getDisplayName(Locale locale) {
            Objects.requireNonNull(locale, "locale");
            if (kind == 2) {
                // CLDR's word for a week, as a JDK writes it for each locale the library has
                return (rontolisp.internal.Locales.foreign() ? ChronoField.root(locale) : locale.getLanguage().isEmpty())
                        ? "Week" : "week";
            }
            return toString();
        }

        public String toString() {
            return kind == 0 ? "DayOfQuarter" : kind == 1 ? "QuarterOfYear" : kind == 2 ? "WeekOfWeekBasedYear"
                    : "WeekBasedYear";
        }
    }

    /** A week-based year (0) and a quarter of a year (1). */
    private static final class Unit implements TemporalUnit {

        private final int kind;

        Unit(int kind) {
            this.kind = kind;
        }

        public Duration getDuration() {
            return Duration.ofSeconds(kind == 0 ? 31556952L : 31556952L / 4);
        }

        public boolean isDurationEstimated() {
            return true;
        }

        public boolean isDateBased() {
            return true;
        }

        public boolean isTimeBased() {
            return false;
        }

        public boolean isSupportedBy(Temporal temporal) {
            if (IsoFields.ownCalendars()) {
                if (!iso(temporal)) {
                    return false;
                }
            }
            return temporal.isSupported(ChronoField.EPOCH_DAY);
        }

        @SuppressWarnings("unchecked")
        public <R extends Temporal> R addTo(R temporal, long amount) {
            if (kind == 0) {
                return (R) temporal.with(WEEK_BASED_YEAR, Math.addExact(temporal.get(WEEK_BASED_YEAR), amount));
            }
            return (R) temporal.plus(amount / 4, ChronoUnit.YEARS).plus(amount % 4 * 3, ChronoUnit.MONTHS);
        }

        public long between(Temporal temporal1Inclusive, Temporal temporal2Exclusive) {
            if (kind == 0) {
                return Math.subtractExact(temporal2Exclusive.getLong(WEEK_BASED_YEAR),
                        temporal1Inclusive.getLong(WEEK_BASED_YEAR));
            }
            return temporal1Inclusive.until(temporal2Exclusive, ChronoUnit.MONTHS) / 3;
        }

        public String toString() {
            return kind == 0 ? "WeekBasedYears" : "QuarterYears";
        }
    }
}
