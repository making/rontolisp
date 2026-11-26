// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A calendar's common part: its dates made of the date fields a text gave, as the API
// documentation lists the combinations and what each resolver style does with them, and
// two calendars equal by their class and id. What the documentation leaves open is a JDK
// 25's answers: the order the fields are taken out of the map and checked in (what is
// left of it after a refusal), and that leniently a day of the week in an aligned week is
// the day before the one asked. ISO's calendar takes an era of another value and a
// proleptic month out of range differently (resolve's iso). The checks of what a default
// of a calendar's date, date-time or zoned date-time made are here and in the two
// classes of those (dated, ChronoLocalDateTimeImpl.ensured, ChronoZonedDateTimeImpl.ensured).
package java.time.chrono;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.TemporalField;
import java.time.temporal.ValueRange;
import java.util.List;
import java.util.Map;

public abstract class AbstractChronology implements Chronology {

    protected AbstractChronology() {
    }

    public ChronoLocalDate resolveDate(Map<TemporalField, Long> fieldValues, ResolverStyle resolverStyle) {
        return resolve(this, fieldValues, resolverStyle, false);
    }

    public int compareTo(Chronology other) {
        if (other == null) {
            throw new NullPointerException(
                    "Cannot invoke \"java.time.chrono.Chronology.getId()\" because \"other\" is null");
        }
        return getId().compareTo(other.getId());
    }

    public boolean equals(Object obj) {
        return this == obj || (obj != null && obj.getClass() == getClass() && getId().equals(((Chronology) obj).getId()));
    }

    public int hashCode() {
        return getClass().hashCode() ^ getId().hashCode();
    }

    public String toString() {
        return getId();
    }

    /**
     * A date a default of ChronoLocalDate made, checked to be a date of the calendar: a
     * ClassCastException otherwise, in a JDK 25's words.
     */
    static ChronoLocalDate dated(Chronology chrono, Temporal temporal) {
        if (!(temporal instanceof ChronoLocalDate date)) {
            throw castRefused(temporal, "java.time.chrono.ChronoLocalDate");
        }
        if (!chrono.equals(date.getChronology())) {
            throw new ClassCastException("Chronology mismatch, expected: " + chrono.getId() + ", actual: "
                    + date.getChronology().getId());
        }
        return date;
    }

    /**
     * A cast of a value to a class of the library refused as a JDK 25 words it: both
     * classes in java.base, or the value's in the unnamed module of the application's
     * loader.
     */
    static ClassCastException castRefused(Object value, String target) {
        String name = value.getClass().getName();
        boolean library = name.length() > 5 && name.substring(0, 5).equals("java.");
        return new ClassCastException("class " + name + " cannot be cast to class " + target + " ("
                + (library ? name + " and " + target + " are in module java.base"
                        : name + " is in unnamed module of loader 'app'; " + target + " is in module java.base")
                + " of loader 'bootstrap')");
    }

    /**
     * The date the fields of a map make in a calendar, or null where they make none: what
     * is used is taken out of the map, what a date or a year is made of put in.
     */
    static ChronoLocalDate resolve(Chronology chrono, Map<TemporalField, Long> fields, ResolverStyle style,
            boolean iso) {
        if (fields == null) {
            throw new NullPointerException(
                    "Cannot invoke \"java.util.Map.containsKey(Object)\" because \"fieldValues\" is null");
        }
        if (fields.containsKey(ChronoField.EPOCH_DAY)) {
            // the other fields are left to be checked against it
            return chrono.dateEpochDay(fields.remove(ChronoField.EPOCH_DAY));
        }
        Long proleptic = fields.remove(ChronoField.PROLEPTIC_MONTH);
        if (proleptic != null) {
            if (!iso || style != ResolverStyle.LENIENT) {
                checked(chrono, ChronoField.PROLEPTIC_MONTH, proleptic);
            }
            if (iso) {
                put(fields, ChronoField.MONTH_OF_YEAR, Math.floorMod(proleptic, 12L) + 1);
                put(fields, ChronoField.YEAR, Math.floorDiv(proleptic, 12L));
            }
            else {
                ChronoLocalDate month = (ChronoLocalDate) chrono.dateEpochDay(0).with(ChronoField.DAY_OF_MONTH, 1)
                        .with(ChronoField.PROLEPTIC_MONTH, proleptic);
                put(fields, ChronoField.MONTH_OF_YEAR, month.get(ChronoField.MONTH_OF_YEAR));
                put(fields, ChronoField.YEAR, month.get(ChronoField.YEAR));
            }
        }
        yearOfEra(chrono, fields, style, iso);
        if (!fields.containsKey(ChronoField.YEAR)) {
            return null;
        }
        if (fields.containsKey(ChronoField.MONTH_OF_YEAR)) {
            if (fields.containsKey(ChronoField.DAY_OF_MONTH)) {
                return yearMonthDay(chrono, fields, style);
            }
            if (fields.containsKey(ChronoField.ALIGNED_WEEK_OF_MONTH)) {
                if (fields.containsKey(ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH)) {
                    return weekOfMonth(chrono, fields, style, ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH);
                }
                if (fields.containsKey(ChronoField.DAY_OF_WEEK)) {
                    return weekOfMonth(chrono, fields, style, ChronoField.DAY_OF_WEEK);
                }
            }
        }
        if (fields.containsKey(ChronoField.DAY_OF_YEAR)) {
            int year = year(chrono, fields);
            long day = fields.remove(ChronoField.DAY_OF_YEAR);
            if (style == ResolverStyle.LENIENT) {
                return (ChronoLocalDate) chrono.dateYearDay(year, 1).plus(Math.subtractExact(day, 1), ChronoUnit.DAYS);
            }
            return chrono.dateYearDay(year, checkedInt(chrono, ChronoField.DAY_OF_YEAR, day));
        }
        if (fields.containsKey(ChronoField.ALIGNED_WEEK_OF_YEAR)) {
            if (fields.containsKey(ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR)) {
                return weekOfYear(chrono, fields, style, ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR);
            }
            if (fields.containsKey(ChronoField.DAY_OF_WEEK)) {
                return weekOfYear(chrono, fields, style, ChronoField.DAY_OF_WEEK);
            }
        }
        return null;
    }

    private static long checked(Chronology chrono, ChronoField field, long value) {
        return chrono.range(field).checkValidValue(value, field);
    }

    private static int checkedInt(Chronology chrono, ChronoField field, long value) {
        return chrono.range(field).checkValidIntValue(value, field);
    }

    /** A field made of others: one the map holds with another value is a conflict. */
    private static void put(Map<TemporalField, Long> fields, ChronoField field, long value) {
        Long old = fields.get(field);
        if (old != null && old.longValue() != value) {
            throw new DateTimeException(
                    "Conflict found: " + field + " " + old + " differs from " + field + " " + value);
        }
        fields.put(field, value);
    }

    private static int year(Chronology chrono, Map<TemporalField, Long> fields) {
        return checkedInt(chrono, ChronoField.YEAR, fields.remove(ChronoField.YEAR));
    }

    /**
     * A year of an era made a year: with its era, or with the year's own; with neither the
     * last era but strictly none, the field left as it was. An era alone is checked.
     */
    private static void yearOfEra(Chronology chrono, Map<TemporalField, Long> fields, ResolverStyle style,
            boolean iso) {
        Long yearOfEra = fields.remove(ChronoField.YEAR_OF_ERA);
        if (yearOfEra == null) {
            if (fields.containsKey(ChronoField.ERA)) {
                checked(chrono, ChronoField.ERA, fields.get(ChronoField.ERA));
            }
            return;
        }
        if (style != ResolverStyle.LENIENT) {
            checked(chrono, ChronoField.YEAR_OF_ERA, yearOfEra);
        }
        Long era = fields.remove(ChronoField.ERA);
        if (era != null) {
            if (iso) {
                if (era != 0 && era != 1) {
                    throw new DateTimeException("Invalid value for era: " + era);
                }
            }
            else {
                checked(chrono, ChronoField.ERA, era);
            }
            put(fields, ChronoField.YEAR, chrono.prolepticYear(chrono.eraOf(Math.toIntExact(era)),
                    Math.toIntExact(yearOfEra)));
            return;
        }
        if (fields.containsKey(ChronoField.YEAR)) {
            // the era of the year given: ISO's by its sign, unchecked
            long year = fields.get(ChronoField.YEAR);
            if (iso) {
                put(fields, ChronoField.YEAR, year > 0 ? yearOfEra : Math.subtractExact(1, yearOfEra));
                return;
            }
            Era of = chrono.dateYearDay(checkedInt(chrono, ChronoField.YEAR, year), 1).getEra();
            put(fields, ChronoField.YEAR, chrono.prolepticYear(of, Math.toIntExact(yearOfEra)));
            return;
        }
        if (style == ResolverStyle.STRICT) {
            fields.put(ChronoField.YEAR_OF_ERA, yearOfEra);
            return;
        }
        List<Era> eras = chrono.eras();
        Era last = eras.isEmpty() ? chrono.eraOf(1) : eras.get(eras.size() - 1);
        put(fields, ChronoField.YEAR, chrono.prolepticYear(last, Math.toIntExact(yearOfEra)));
    }

    private static ChronoLocalDate yearMonthDay(Chronology chrono, Map<TemporalField, Long> fields,
            ResolverStyle style) {
        int year = year(chrono, fields);
        if (style == ResolverStyle.LENIENT) {
            long months = Math.subtractExact(fields.remove(ChronoField.MONTH_OF_YEAR), 1);
            long days = Math.subtractExact(fields.remove(ChronoField.DAY_OF_MONTH), 1);
            return (ChronoLocalDate) chrono.date(year, 1, 1).plus(months, ChronoUnit.MONTHS).plus(days, ChronoUnit.DAYS);
        }
        int month = checkedInt(chrono, ChronoField.MONTH_OF_YEAR, fields.remove(ChronoField.MONTH_OF_YEAR));
        int day = checkedInt(chrono, ChronoField.DAY_OF_MONTH, fields.remove(ChronoField.DAY_OF_MONTH));
        if (style == ResolverStyle.SMART) {
            // a day past the month's last is its last
            ChronoLocalDate first = chrono.date(year, month, 1);
            if (day > first.lengthOfMonth()) {
                return (ChronoLocalDate) first.with(TemporalAdjusters.lastDayOfMonth());
            }
        }
        return chrono.date(year, month, day);
    }

    /** A week of a month and a day in it: the aligned day of the week, or the day of the week. */
    private static ChronoLocalDate weekOfMonth(Chronology chrono, Map<TemporalField, Long> fields,
            ResolverStyle style, ChronoField dayField) {
        int year = year(chrono, fields);
        if (style == ResolverStyle.LENIENT) {
            long months = Math.subtractExact(fields.remove(ChronoField.MONTH_OF_YEAR), 1);
            long weeks = Math.subtractExact(fields.remove(ChronoField.ALIGNED_WEEK_OF_MONTH), 1);
            ChronoLocalDate start = (ChronoLocalDate) chrono.date(year, 1, 1).plus(months, ChronoUnit.MONTHS);
            return leniently(start, weeks, fields.remove(dayField), dayField);
        }
        int month = checkedInt(chrono, ChronoField.MONTH_OF_YEAR, fields.remove(ChronoField.MONTH_OF_YEAR));
        int week = checkedInt(chrono, ChronoField.ALIGNED_WEEK_OF_MONTH, fields.remove(ChronoField.ALIGNED_WEEK_OF_MONTH));
        int day = checkedInt(chrono, dayField, fields.remove(dayField));
        ChronoLocalDate date = aligned(chrono.date(year, month, 1), week, day, dayField);
        if (style == ResolverStyle.STRICT && date.get(ChronoField.MONTH_OF_YEAR) != month) {
            throw new DateTimeException("Strict mode rejected resolved date as it is in a different month");
        }
        return date;
    }

    /** A week of a year and a day in it: the aligned day of the week, or the day of the week. */
    private static ChronoLocalDate weekOfYear(Chronology chrono, Map<TemporalField, Long> fields,
            ResolverStyle style, ChronoField dayField) {
        int year = year(chrono, fields);
        if (style == ResolverStyle.LENIENT) {
            long weeks = Math.subtractExact(fields.remove(ChronoField.ALIGNED_WEEK_OF_YEAR), 1);
            return leniently(chrono.dateYearDay(year, 1), weeks, fields.remove(dayField), dayField);
        }
        int week = checkedInt(chrono, ChronoField.ALIGNED_WEEK_OF_YEAR, fields.remove(ChronoField.ALIGNED_WEEK_OF_YEAR));
        int day = checkedInt(chrono, dayField, fields.remove(dayField));
        ChronoLocalDate date = aligned(chrono.dateYearDay(year, 1), week, day, dayField);
        if (style == ResolverStyle.STRICT && date.get(ChronoField.YEAR) != year) {
            throw new DateTimeException("Strict mode rejected resolved date as it is in a different year");
        }
        return date;
    }

    /** A day of a week counted from a start: the aligned day, or the day of the week on or after it. */
    private static ChronoLocalDate aligned(ChronoLocalDate start, int week, int day, ChronoField dayField) {
        if (dayField != ChronoField.DAY_OF_WEEK) {
            return (ChronoLocalDate) start.plus((week - 1) * 7L + (day - 1), ChronoUnit.DAYS);
        }
        return (ChronoLocalDate) start.plus((week - 1) * 7L, ChronoUnit.DAYS)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)));
    }

    /**
     * A day of a week counted from a start, leniently: the aligned day added as days, or
     * the day of the week on or after the week's start -- the day before the one asked,
     * a whole week further for each seven past the last, a JDK 25's answers (136,518 of
     * them over the years, months, weeks and days asked).
     */
    static ChronoLocalDate leniently(ChronoLocalDate start, long weeks, long day, ChronoField dayField) {
        if (dayField != ChronoField.DAY_OF_WEEK) {
            return (ChronoLocalDate) start.plus(weeks, ChronoUnit.WEEKS).plus(Math.subtractExact(day, 1),
                    ChronoUnit.DAYS);
        }
        Temporal date = start.plus(weeks, ChronoUnit.WEEKS);
        long asked = Math.subtractExact(day, 1);
        if (asked > 7) {
            date = date.plus((asked - 1) / 7, ChronoUnit.WEEKS);
            asked = (asked - 1) % 7 + 1;
        }
        else if (asked < 1) {
            date = date.plus(Math.subtractExact(asked, 7) / 7, ChronoUnit.WEEKS);
            asked = (asked + 6) % 7 + 1;
        }
        return (ChronoLocalDate) date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of((int) asked)));
    }
}
