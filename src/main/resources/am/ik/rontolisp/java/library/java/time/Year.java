// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A year of the proleptic ISO calendar. Its text is read more freely than the year of
// a date: any sign, one digit to nine (a JDK's answers).
package java.time;

import java.io.Serializable;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;

public final class Year implements Temporal, TemporalAdjuster, Comparable<Year>, Serializable {

    public static final int MIN_VALUE = -999999999;

    public static final int MAX_VALUE = 999999999;

    private final int year;

    private Year(int year) {
        this.year = year;
    }

    public static Year now() {
        return now(Clock.systemDefaultZone());
    }

    public static Year now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static Year now(Clock clock) {
        return new Year(LocalDate.now(clock).getYear());
    }

    public static Year of(int isoYear) {
        return new Year(Iso.checkYear(isoYear));
    }

    public static Year from(TemporalAccessor temporal) {
        if (temporal instanceof Year year) {
            return year;
        }
        try {
            return of(temporal.get(ChronoField.YEAR));
        }
        catch (DateTimeException refused) {
            throw new DateTimeException("Unable to obtain Year from TemporalAccessor: " + temporal + " of type "
                    + temporal.getClass().getName(), refused);
        }
    }

    public static Year parse(CharSequence text) {
        IsoText in = new IsoText(text);
        boolean negative = in.next('-');
        if (negative || in.next('+')) {
            in.at++;
        }
        int value = 0;
        int from = in.at;
        while (in.at < from + 9 && in.more() && in.text.charAt(in.at) >= '0' && in.text.charAt(in.at) <= '9') {
            value = value * 10 + (in.text.charAt(in.at) - '0');
            in.at++;
        }
        if (in.at == from) {
            throw in.failed(from);
        }
        in.end();
        return new Year(negative ? -value : value);
    }

    public static boolean isLeap(long year) {
        return Iso.leap(year);
    }

    public int getValue() {
        return year;
    }

    public boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField) {
            return field == ChronoField.YEAR || field == ChronoField.YEAR_OF_ERA || field == ChronoField.ERA;
        }
        return field != null && field.isSupportedBy(this);
    }

    public boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            return unit == ChronoUnit.YEARS || unit == ChronoUnit.DECADES || unit == ChronoUnit.CENTURIES
                    || unit == ChronoUnit.MILLENNIA || unit == ChronoUnit.ERAS;
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

    public boolean isLeap() {
        return Iso.leap(year);
    }

    public int length() {
        return isLeap() ? 366 : 365;
    }

    public Year with(TemporalAdjuster adjuster) {
        return (Year) adjuster.adjustInto(this);
    }

    public Year with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            chrono.checkValidValue(newValue);
            switch (chrono) {
                case YEAR_OF_ERA:
                    return of((int) (year < 1 ? 1 - newValue : newValue));
                case YEAR:
                    return of((int) newValue);
                case ERA:
                    return getLong(ChronoField.ERA) == newValue ? this : of(1 - year);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.adjustInto(this, newValue);
    }

    public Year plus(TemporalAmount amountToAdd) {
        return (Year) amountToAdd.addTo(this);
    }

    public Year plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
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

    public Year plusYears(long yearsToAdd) {
        return yearsToAdd == 0 ? this : new Year(Iso.checkYear(year + yearsToAdd));
    }

    public Year minus(TemporalAmount amountToSubtract) {
        return (Year) amountToSubtract.subtractFrom(this);
    }

    public Year minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public Year minusYears(long yearsToSubtract) {
        return yearsToSubtract == Long.MIN_VALUE ? plusYears(Long.MAX_VALUE).plusYears(1)
                : plusYears(-yearsToSubtract);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.YEAR, year);
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        Year end = from(endExclusive);
        if (unit instanceof ChronoUnit chrono) {
            long years = (long) end.year - year;
            switch (chrono) {
                case YEARS:
                    return years;
                case DECADES:
                    return years / 10;
                case CENTURIES:
                    return years / 100;
                case MILLENNIA:
                    return years / 1000;
                case ERAS:
                    return end.getLong(ChronoField.ERA) - getLong(ChronoField.ERA);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.between(this, end);
    }

    public LocalDate atDay(int dayOfYear) {
        return LocalDate.ofYearDay(year, dayOfYear);
    }

    public YearMonth atMonth(Month month) {
        return YearMonth.of(year, month);
    }

    public YearMonth atMonth(int month) {
        return YearMonth.of(year, month);
    }

    public int compareTo(Year other) {
        return year - other.year;
    }

    public boolean isAfter(Year other) {
        return year > other.year;
    }

    public boolean isBefore(Year other) {
        return year < other.year;
    }

    public boolean equals(Object other) {
        return other instanceof Year that && that.year == year;
    }

    public int hashCode() {
        return year;
    }

    public String toString() {
        return Integer.toString(year);
    }
}
