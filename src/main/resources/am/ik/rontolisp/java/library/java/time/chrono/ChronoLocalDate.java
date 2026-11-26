// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date of a calendar: java.time.LocalDate is ISO's, a calendar of the program's own
// has a class of its own. What the documentation gives a default is a default here, for a
// date of the program's own: its era, its year, its epoch day and its order (the epoch
// days, then the calendars); a field that is no ChronoField, a unit that is no ChronoUnit,
// an adjuster and an amount applied and the result checked to be a date of the calendar
// (AbstractChronology.dated, a JDK 25's words); a ChronoField or a ChronoUnit refused, which
// the program's class answers itself; a time making the calendar's date-time
// (ChronoLocalDateTimeImpl).
package java.time.chrono;

import java.time.DateTimeException;
import java.time.LocalTime;
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
import java.util.Comparator;
import java.util.Objects;

public interface ChronoLocalDate extends Temporal, TemporalAdjuster, Comparable<ChronoLocalDate> {

    static Comparator<ChronoLocalDate> timeLineOrder() {
        return (date1, date2) -> Long.compare(date1.toEpochDay(), date2.toEpochDay());
    }

    static ChronoLocalDate from(TemporalAccessor temporal) {
        if (temporal instanceof ChronoLocalDate date) {
            return date;
        }
        Objects.requireNonNull(temporal, "temporal");
        Chronology chronology = temporal.query(TemporalQueries.chronology());
        if (chronology == null) {
            throw new DateTimeException(
                    "Unable to obtain ChronoLocalDate from TemporalAccessor: class " + temporal.getClass().getName());
        }
        return chronology.date(temporal);
    }

    Chronology getChronology();

    default Era getEra() {
        return getChronology().eraOf(get(ChronoField.ERA));
    }

    default boolean isLeapYear() {
        return getChronology().isLeapYear(getLong(ChronoField.YEAR));
    }

    int lengthOfMonth();

    default int lengthOfYear() {
        return isLeapYear() ? 366 : 365;
    }

    default boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField) {
            return field.isDateBased();
        }
        return field != null && field.isSupportedBy(this);
    }

    default boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            return unit.isDateBased();
        }
        return unit != null && unit.isSupportedBy(this);
    }

    default ChronoLocalDate with(TemporalAdjuster adjuster) {
        return AbstractChronology.dated(getChronology(), adjuster.adjustInto(this));
    }

    default ChronoLocalDate with(TemporalField field, long newValue) {
        if (field instanceof ChronoField) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return AbstractChronology.dated(getChronology(), field.adjustInto(this, newValue));
    }

    default ChronoLocalDate plus(TemporalAmount amount) {
        return AbstractChronology.dated(getChronology(), amount.addTo(this));
    }

    default ChronoLocalDate plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
        }
        return AbstractChronology.dated(getChronology(), unit.addTo(this, amountToAdd));
    }

    default ChronoLocalDate minus(TemporalAmount amount) {
        return AbstractChronology.dated(getChronology(), amount.subtractFrom(this));
    }

    default ChronoLocalDate minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    @SuppressWarnings("unchecked")
    default <R> R query(TemporalQuery<R> query) {
        if (query == TemporalQueries.zoneId() || query == TemporalQueries.zone() || query == TemporalQueries.offset()
                || query == TemporalQueries.localTime()) {
            return null;
        }
        if (query == TemporalQueries.chronology()) {
            return (R) getChronology();
        }
        if (query == TemporalQueries.precision()) {
            return (R) ChronoUnit.DAYS;
        }
        return query.queryFrom(this);
    }

    default Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.EPOCH_DAY, toEpochDay());
    }

    ChronoPeriod until(ChronoLocalDate endDateExclusive);

    default String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    /** The calendar's date-time of this date and a time. */
    default ChronoLocalDateTime<?> atTime(LocalTime localTime) {
        return ChronoLocalDateTimeImpl.of(this, localTime);
    }

    default long toEpochDay() {
        return getLong(ChronoField.EPOCH_DAY);
    }

    default int compareTo(ChronoLocalDate other) {
        int order = Long.compare(toEpochDay(), other.toEpochDay());
        return order != 0 ? order : getChronology().compareTo(other.getChronology());
    }

    default boolean isAfter(ChronoLocalDate other) {
        return toEpochDay() > other.toEpochDay();
    }

    default boolean isBefore(ChronoLocalDate other) {
        return toEpochDay() < other.toEpochDay();
    }

    default boolean isEqual(ChronoLocalDate other) {
        return toEpochDay() == other.toEpochDay();
    }
}
