// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date and a time of a calendar: java.time.LocalDateTime is ISO's, any other calendar's
// is ChronoLocalDateTimeImpl. What the documentation gives a default is a default here:
// an adjuster and an amount applied and the result checked to be the calendar's date-time
// (ChronoLocalDateTimeImpl.ensured), the instant at an offset, and the order -- the
// dates (by their epoch days, then their calendars), the times, the calendars --, where
// isAfter, isBefore and isEqual compare the epoch days and the times alone.
package java.time.chrono;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import java.util.Comparator;
import java.util.Objects;

public interface ChronoLocalDateTime<D extends ChronoLocalDate>
        extends Temporal, TemporalAdjuster, Comparable<ChronoLocalDateTime<?>> {

    static Comparator<ChronoLocalDateTime<?>> timeLineOrder() {
        return (dateTime1, dateTime2) -> {
            int order = Long.compare(dateTime1.toLocalDate().toEpochDay(), dateTime2.toLocalDate().toEpochDay());
            return order != 0 ? order
                    : Long.compare(dateTime1.toLocalTime().toNanoOfDay(), dateTime2.toLocalTime().toNanoOfDay());
        };
    }

    /** The date-time of a value in the calendar it names. */
    static ChronoLocalDateTime<?> from(TemporalAccessor temporal) {
        if (temporal instanceof ChronoLocalDateTime<?> dateTime) {
            return dateTime;
        }
        Objects.requireNonNull(temporal, "temporal");
        Chronology chronology = temporal.query(TemporalQueries.chronology());
        if (chronology == null) {
            throw new DateTimeException(
                    "Unable to obtain ChronoLocalDateTime from TemporalAccessor: class " + temporal.getClass().getName());
        }
        return chronology.localDateTime(temporal);
    }

    default Chronology getChronology() {
        return toLocalDate().getChronology();
    }

    D toLocalDate();

    LocalTime toLocalTime();

    boolean isSupported(TemporalField field);

    default boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            return unit != ChronoUnit.FOREVER;
        }
        return unit != null && unit.isSupportedBy(this);
    }

    default ChronoLocalDateTime<D> with(TemporalAdjuster adjuster) {
        return ChronoLocalDateTimeImpl.ensured(getChronology(), adjuster.adjustInto(this));
    }

    ChronoLocalDateTime<D> with(TemporalField field, long newValue);

    default ChronoLocalDateTime<D> plus(TemporalAmount amount) {
        return ChronoLocalDateTimeImpl.ensured(getChronology(), amount.addTo(this));
    }

    ChronoLocalDateTime<D> plus(long amountToAdd, TemporalUnit unit);

    default ChronoLocalDateTime<D> minus(TemporalAmount amount) {
        return ChronoLocalDateTimeImpl.ensured(getChronology(), amount.subtractFrom(this));
    }

    default ChronoLocalDateTime<D> minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    @SuppressWarnings("unchecked")
    default <R> R query(TemporalQuery<R> query) {
        if (query == TemporalQueries.zoneId() || query == TemporalQueries.zone() || query == TemporalQueries.offset()) {
            return null;
        }
        if (query == TemporalQueries.localTime()) {
            return (R) toLocalTime();
        }
        if (query == TemporalQueries.chronology()) {
            return (R) getChronology();
        }
        if (query == TemporalQueries.precision()) {
            return (R) ChronoUnit.NANOS;
        }
        return query.queryFrom(this);
    }

    default Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.EPOCH_DAY, toLocalDate().toEpochDay())
            .with(ChronoField.NANO_OF_DAY, toLocalTime().toNanoOfDay());
    }

    ChronoZonedDateTime<D> atZone(ZoneId zone);

    default String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    default Instant toInstant(ZoneOffset offset) {
        return Instant.ofEpochSecond(toEpochSecond(offset), toLocalTime().getNano());
    }

    default long toEpochSecond(ZoneOffset offset) {
        Objects.requireNonNull(offset, "offset");
        return toLocalDate().toEpochDay() * 86400L + toLocalTime().toSecondOfDay() - offset.getTotalSeconds();
    }

    default int compareTo(ChronoLocalDateTime<?> other) {
        int order = toLocalDate().compareTo(other.toLocalDate());
        if (order == 0) {
            order = toLocalTime().compareTo(other.toLocalTime());
            if (order == 0) {
                order = getChronology().compareTo(other.getChronology());
            }
        }
        return order;
    }

    default boolean isAfter(ChronoLocalDateTime<?> other) {
        long day = toLocalDate().toEpochDay();
        long otherDay = other.toLocalDate().toEpochDay();
        return day > otherDay || (day == otherDay && toLocalTime().toNanoOfDay() > other.toLocalTime().toNanoOfDay());
    }

    default boolean isBefore(ChronoLocalDateTime<?> other) {
        long day = toLocalDate().toEpochDay();
        long otherDay = other.toLocalDate().toEpochDay();
        return day < otherDay || (day == otherDay && toLocalTime().toNanoOfDay() < other.toLocalTime().toNanoOfDay());
    }

    default boolean isEqual(ChronoLocalDateTime<?> other) {
        return toLocalTime().toNanoOfDay() == other.toLocalTime().toNanoOfDay()
                && toLocalDate().toEpochDay() == other.toLocalDate().toEpochDay();
    }
}
