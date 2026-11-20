// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A month and a day of it, of no year: February 29 is one, valid in a leap year only.
// Its text is --MM-dd. A text whose fields make no such day is refused as a JDK's
// formatter refuses it, with the fields it read (a JDK's answer).
package java.time;

import java.io.Serializable;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Objects;

public final class MonthDay implements TemporalAccessor, TemporalAdjuster, Comparable<MonthDay>, Serializable {

    private final int month;

    private final int day;

    private MonthDay(int month, int day) {
        this.month = month;
        this.day = day;
    }

    public static MonthDay now() {
        return now(Clock.systemDefaultZone());
    }

    public static MonthDay now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static MonthDay now(Clock clock) {
        LocalDate today = LocalDate.now(clock);
        return new MonthDay(today.getMonthValue(), today.getDayOfMonth());
    }

    public static MonthDay of(Month month, int dayOfMonth) {
        Objects.requireNonNull(month, "month");
        ChronoField.DAY_OF_MONTH.checkValidValue(dayOfMonth);
        if (dayOfMonth > month.maxLength()) {
            throw new DateTimeException(
                    "Illegal value for DayOfMonth field, value " + dayOfMonth + " is not valid for month " + month.name());
        }
        return new MonthDay(month.getValue(), dayOfMonth);
    }

    public static MonthDay of(int month, int dayOfMonth) {
        return of(Month.of(month), dayOfMonth);
    }

    public static MonthDay from(TemporalAccessor temporal) {
        if (temporal instanceof MonthDay monthDay) {
            return monthDay;
        }
        try {
            return of(temporal.get(ChronoField.MONTH_OF_YEAR), temporal.get(ChronoField.DAY_OF_MONTH));
        }
        catch (DateTimeException refused) {
            throw new DateTimeException("Unable to obtain MonthDay from TemporalAccessor: " + temporal + " of type "
                    + temporal.getClass().getName(), refused);
        }
    }

    public static MonthDay parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.literal("--");
        int month = in.two();
        in.literal('-');
        int day = in.two();
        in.end();
        try {
            return of(month, day);
        }
        catch (DateTimeException invalid) {
            // the words of a JDK, whose formatter read the two fields
            throw in.refused(new DateTimeException("Unable to obtain MonthDay from TemporalAccessor: {MonthOfYear="
                    + month + ", DayOfMonth=" + day + "},ISO of type java.time.format.Parsed", invalid));
        }
    }

    public static MonthDay parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, MonthDay::from);
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field == ChronoField.MONTH_OF_YEAR || field == ChronoField.DAY_OF_MONTH
                : field != null && field.isSupportedBy(this);
    }

    public ValueRange range(TemporalField field) {
        if (field == ChronoField.MONTH_OF_YEAR) {
            return field.range();
        }
        if (field == ChronoField.DAY_OF_MONTH) {
            Month of = getMonth();
            return ValueRange.of(1, of.minLength(), of.maxLength());
        }
        return TemporalAccessor.super.range(field);
    }

    public long getLong(TemporalField field) {
        if (field == ChronoField.MONTH_OF_YEAR) {
            return month;
        }
        if (field == ChronoField.DAY_OF_MONTH) {
            return day;
        }
        if (field instanceof ChronoField) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    public int getMonthValue() {
        return month;
    }

    public Month getMonth() {
        return Month.of(month);
    }

    public int getDayOfMonth() {
        return day;
    }

    /** Whether the day is one of a year: February 29 of a leap year only. */
    public boolean isValidYear(int year) {
        return day != 29 || month != 2 || Iso.leap(year);
    }

    public MonthDay withMonth(int month) {
        return with(Month.of(month));
    }

    /** The same day of another month, or that month's last where it has no such day. */
    public MonthDay with(Month month) {
        Objects.requireNonNull(month, "month");
        return month.getValue() == this.month ? this
                : new MonthDay(month.getValue(), Math.min(day, month.maxLength()));
    }

    public MonthDay withDayOfMonth(int dayOfMonth) {
        return dayOfMonth == day ? this : of(month, dayOfMonth);
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        if (Iso.calendars()) {
            if (query == TemporalQueries.chronology()) {
                return (R) IsoChronology.INSTANCE;
            }
        }
        return TemporalAccessor.super.query(query);
    }

    /** The month, then the day where the month has it, else the month's last. */
    public Temporal adjustInto(Temporal temporal) {
        Temporal moved = temporal.with(ChronoField.MONTH_OF_YEAR, month);
        return moved.with(ChronoField.DAY_OF_MONTH,
                Math.min(moved.range(ChronoField.DAY_OF_MONTH).getMaximum(), day));
    }

    public String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    /** The day in a year: February 29 of a year that is no leap year is February 28. */
    public LocalDate atYear(int year) {
        return LocalDate.of(year, month, isValidYear(year) ? day : 28);
    }

    public int compareTo(MonthDay other) {
        int order = month - other.month;
        return order != 0 ? order : day - other.day;
    }

    public boolean isAfter(MonthDay other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(MonthDay other) {
        return compareTo(other) < 0;
    }

    public boolean equals(Object other) {
        return other instanceof MonthDay that && that.month == month && that.day == day;
    }

    public int hashCode() {
        return (month << 6) + day;
    }

    public String toString() {
        StringBuilder text = new StringBuilder("--");
        Iso.two(text, month).append('-');
        return Iso.two(text, day).toString();
    }
}
