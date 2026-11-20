// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time;

import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;

// The seven days of a week, Monday the first, as ISO-8601 counts them.
public enum DayOfWeek implements TemporalAccessor, TemporalAdjuster {

    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

    public static DayOfWeek of(int dayOfWeek) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new DateTimeException("Invalid value for DayOfWeek: " + dayOfWeek);
        }
        return values()[dayOfWeek - 1];
    }

    public static DayOfWeek from(TemporalAccessor temporal) {
        if (temporal instanceof DayOfWeek day) {
            return day;
        }
        try {
            return of(temporal.get(ChronoField.DAY_OF_WEEK));
        }
        catch (DateTimeException refused) {
            throw new DateTimeException("Unable to obtain DayOfWeek from TemporalAccessor: " + temporal + " of type "
                    + temporal.getClass().getName(), refused);
        }
    }

    public int getValue() {
        return ordinal() + 1;
    }

    public DayOfWeek plus(long days) {
        return values()[(int) Math.floorMod(ordinal() + days % 7, 7L)];
    }

    public DayOfWeek minus(long days) {
        return plus(-(days % 7));
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field == ChronoField.DAY_OF_WEEK
                : field != null && field.isSupportedBy(this);
    }

    public long getLong(TemporalField field) {
        if (field == ChronoField.DAY_OF_WEEK) {
            return getValue();
        }
        if (field instanceof ChronoField) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.DAY_OF_WEEK, getValue());
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        return query == TemporalQueries.precision() ? (R) ChronoUnit.DAYS
                : query == TemporalQueries.zoneId() ? null : query.queryFrom(this);
    }
}
