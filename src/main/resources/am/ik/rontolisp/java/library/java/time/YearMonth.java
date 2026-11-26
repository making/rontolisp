// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A month of a year, with no day. Its text takes the year a date's does.
package java.time;

import java.io.Serializable;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Objects;

public final class YearMonth implements Temporal, TemporalAdjuster, Comparable<YearMonth>, Serializable {

    private final int year;

    private final int month;

    private YearMonth(int year, int month) {
        this.year = year;
        this.month = month;
    }

    public static YearMonth now() {
        return now(Clock.systemDefaultZone());
    }

    public static YearMonth now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static YearMonth now(Clock clock) {
        LocalDate today = LocalDate.now(clock);
        return new YearMonth(today.getYear(), today.getMonthValue());
    }

    public static YearMonth of(int year, Month month) {
        return of(year, month.getValue());
    }

    public static YearMonth of(int year, int month) {
        Iso.checkYear(year);
        return new YearMonth(year, Iso.checkMonth(month));
    }

    public static YearMonth from(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        if (temporal instanceof YearMonth yearMonth) {
            return yearMonth;
        }
        try {
            return of(temporal.get(ChronoField.YEAR), temporal.get(ChronoField.MONTH_OF_YEAR));
        }
        catch (DateTimeException refused) {
            throw new DateTimeException("Unable to obtain YearMonth from TemporalAccessor: " + temporal + " of type "
                    + temporal.getClass().getName(), refused);
        }
    }

    public static YearMonth parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.year();
        in.literal('-');
        in.month = in.two();
        in.end();
        if (in.year < Year.MIN_VALUE || in.year > Year.MAX_VALUE || in.month < 1 || in.month > 12) {
            throw in.refused(new DateTimeException("Unable to obtain YearMonth from TemporalAccessor: {Year=" + in.year
                    + ", MonthOfYear=" + in.month + "},ISO of type java.time.format.Parsed"));
        }
        return new YearMonth((int) in.year, in.month);
    }

    public static YearMonth parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, YearMonth::from);
    }

    private long prolepticMonth() {
        return year * 12L + month - 1;
    }

    public boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField) {
            return field == ChronoField.YEAR || field == ChronoField.MONTH_OF_YEAR
                    || field == ChronoField.PROLEPTIC_MONTH || field == ChronoField.YEAR_OF_ERA
                    || field == ChronoField.ERA;
        }
        return field != null && field.isSupportedBy(this);
    }

    public boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            return unit == ChronoUnit.MONTHS || unit == ChronoUnit.YEARS || unit == ChronoUnit.DECADES
                    || unit == ChronoUnit.CENTURIES || unit == ChronoUnit.MILLENNIA || unit == ChronoUnit.ERAS;
        }
        return unit != null && unit.isSupportedBy(this);
    }

    public ValueRange range(TemporalField field) {
        if (field == ChronoField.YEAR_OF_ERA) {
            return year <= 0 ? ValueRange.of(1, 1000000000) : ValueRange.of(1, 999999999);
        }
        if (field instanceof ChronoField) {
            if (isSupported(field)) {
                return field.range();
            }
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.rangeRefinedBy(this);
    }

    public int get(TemporalField field) {
        return range(field).checkValidIntValue(getLong(field), field);
    }

    public long getLong(TemporalField field) {
        if (field instanceof ChronoField chrono) {
            switch (chrono) {
                case MONTH_OF_YEAR:
                    return month;
                case PROLEPTIC_MONTH:
                    return prolepticMonth();
                case YEAR_OF_ERA:
                    return year < 1 ? 1 - year : year;
                case YEAR:
                    return year;
                case ERA:
                    return year < 1 ? 0 : 1;
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.getFrom(this);
    }

    public int getYear() {
        return year;
    }

    public int getMonthValue() {
        return month;
    }

    public Month getMonth() {
        return Month.of(month);
    }

    public boolean isLeapYear() {
        return Iso.leap(year);
    }

    public boolean isValidDay(int dayOfMonth) {
        return dayOfMonth >= 1 && dayOfMonth <= lengthOfMonth();
    }

    public int lengthOfMonth() {
        return Iso.monthLength(year, month);
    }

    public int lengthOfYear() {
        return isLeapYear() ? 366 : 365;
    }

    public YearMonth with(TemporalAdjuster adjuster) {
        return (YearMonth) adjuster.adjustInto(this);
    }

    public YearMonth with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            chrono.checkValidValue(newValue);
            switch (chrono) {
                case MONTH_OF_YEAR:
                    return withMonth((int) newValue);
                case PROLEPTIC_MONTH:
                    return plusMonths(newValue - prolepticMonth());
                case YEAR_OF_ERA:
                    return withYear((int) (year < 1 ? 1 - newValue : newValue));
                case YEAR:
                    return withYear((int) newValue);
                case ERA:
                    return getLong(ChronoField.ERA) == newValue ? this : withYear(1 - year);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.adjustInto(this, newValue);
    }

    public YearMonth withYear(int year) {
        return this.year == year ? this : new YearMonth(Iso.checkYear(year), month);
    }

    public YearMonth withMonth(int month) {
        return this.month == month ? this : new YearMonth(year, Iso.checkMonth(month));
    }

    public YearMonth plus(TemporalAmount amountToAdd) {
        return (YearMonth) amountToAdd.addTo(this);
    }

    public YearMonth plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
                case MONTHS:
                    return plusMonths(amountToAdd);
                case YEARS:
                    return plusYears(amountToAdd);
                case DECADES:
                    return plusYears(Math.multiplyExact(amountToAdd, 10L));
                case CENTURIES:
                    return plusYears(Math.multiplyExact(amountToAdd, 100L));
                case MILLENNIA:
                    return plusYears(Math.multiplyExact(amountToAdd, 1000L));
                case ERAS:
                    return with(ChronoField.ERA, Math.addExact(getLong(ChronoField.ERA), amountToAdd));
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.addTo(this, amountToAdd);
    }

    public YearMonth plusYears(long yearsToAdd) {
        return yearsToAdd == 0 ? this : new YearMonth(Iso.checkYear(year + yearsToAdd), month);
    }

    public YearMonth plusMonths(long monthsToAdd) {
        if (monthsToAdd == 0) {
            return this;
        }
        long months = prolepticMonth() + monthsToAdd;
        return new YearMonth(Iso.checkYear(Math.floorDiv(months, 12L)), (int) Math.floorMod(months, 12L) + 1);
    }

    public YearMonth minus(TemporalAmount amountToSubtract) {
        return (YearMonth) amountToSubtract.subtractFrom(this);
    }

    public YearMonth minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public YearMonth minusYears(long yearsToSubtract) {
        return yearsToSubtract == Long.MIN_VALUE ? plusYears(Long.MAX_VALUE).plusYears(1)
                : plusYears(-yearsToSubtract);
    }

    public YearMonth minusMonths(long monthsToSubtract) {
        return monthsToSubtract == Long.MIN_VALUE ? plusMonths(Long.MAX_VALUE).plusMonths(1)
                : plusMonths(-monthsToSubtract);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.PROLEPTIC_MONTH, prolepticMonth());
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        YearMonth end = from(endExclusive);
        if (unit instanceof ChronoUnit chrono) {
            long months = end.prolepticMonth() - prolepticMonth();
            switch (chrono) {
                case MONTHS:
                    return months;
                case YEARS:
                    return months / 12;
                case DECADES:
                    return months / 120;
                case CENTURIES:
                    return months / 1200;
                case MILLENNIA:
                    return months / 12000;
                case ERAS:
                    return end.getLong(ChronoField.ERA) - getLong(ChronoField.ERA);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.between(this, end);
    }

    public LocalDate atDay(int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    public LocalDate atEndOfMonth() {
        return LocalDate.of(year, month, lengthOfMonth());
    }

    public int compareTo(YearMonth other) {
        int order = year - other.year;
        return order != 0 ? order : month - other.month;
    }

    public boolean isAfter(YearMonth other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(YearMonth other) {
        return compareTo(other) < 0;
    }

    public boolean equals(Object other) {
        return other instanceof YearMonth that && that.year == year && that.month == month;
    }

    public int hashCode() {
        return year ^ (month << 27);
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        if (Iso.calendars()) {
            if (query == TemporalQueries.chronology()) {
                return (R) IsoChronology.INSTANCE;
            }
        }
        return query == TemporalQueries.precision() ? (R) ChronoUnit.MONTHS
                : query == TemporalQueries.zoneId() ? null : query.queryFrom(this);
    }

    public String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    public String toString() {
        StringBuilder text = Iso.year(new StringBuilder(), year, false).append('-');
        return Iso.two(text, month).toString();
    }
}
