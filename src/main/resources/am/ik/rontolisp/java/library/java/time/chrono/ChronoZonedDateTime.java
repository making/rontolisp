// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date and a time of a calendar in a zone: java.time.ZonedDateTime is ISO's, any other
// calendar's is ChronoZonedDateTimeImpl. What the documentation gives a default is a
// default here: the fields of the instant and the offset, the rest the local
// date-time's; an adjuster and an amount applied and the result checked
// (ChronoZonedDateTimeImpl.ensured); the order -- the instants, the local date-times, the
// zones' ids, the calendars --, where isAfter, isBefore and isEqual compare the instants
// alone.
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
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.Comparator;
import java.util.Objects;

public interface ChronoZonedDateTime<D extends ChronoLocalDate>
        extends Temporal, Comparable<ChronoZonedDateTime<?>> {

    static Comparator<ChronoZonedDateTime<?>> timeLineOrder() {
        return (dateTime1, dateTime2) -> {
            int order = Long.compare(dateTime1.toEpochSecond(), dateTime2.toEpochSecond());
            return order != 0 ? order
                    : Long.compare(dateTime1.toLocalTime().getNano(), dateTime2.toLocalTime().getNano());
        };
    }

    /** The zoned date-time of a value in the calendar it names. */
    static ChronoZonedDateTime<?> from(TemporalAccessor temporal) {
        if (temporal instanceof ChronoZonedDateTime<?> dateTime) {
            return dateTime;
        }
        Objects.requireNonNull(temporal, "temporal");
        Chronology chronology = temporal.query(TemporalQueries.chronology());
        if (chronology == null) {
            throw new DateTimeException(
                    "Unable to obtain ChronoZonedDateTime from TemporalAccessor: class " + temporal.getClass().getName());
        }
        return chronology.zonedDateTime(temporal);
    }

    default ValueRange range(TemporalField field) {
        if (field instanceof ChronoField) {
            if (field == ChronoField.INSTANT_SECONDS || field == ChronoField.OFFSET_SECONDS) {
                return field.range();
            }
            return toLocalDateTime().range(field);
        }
        return field.rangeRefinedBy(this);
    }

    default int get(TemporalField field) {
        if (field instanceof ChronoField) {
            if (field == ChronoField.INSTANT_SECONDS) {
                throw new UnsupportedTemporalTypeException(
                        "Invalid field 'InstantSeconds' for get() method, use getLong() instead");
            }
            if (field == ChronoField.OFFSET_SECONDS) {
                return getOffset().getTotalSeconds();
            }
            return toLocalDateTime().get(field);
        }
        ValueRange range = range(field);
        if (!range.isIntValue()) {
            throw new UnsupportedTemporalTypeException("Invalid field " + field + " for get() method, use getLong() instead");
        }
        return range.checkValidIntValue(getLong(field), field);
    }

    default long getLong(TemporalField field) {
        if (field instanceof ChronoField) {
            if (field == ChronoField.INSTANT_SECONDS) {
                return toEpochSecond();
            }
            if (field == ChronoField.OFFSET_SECONDS) {
                return getOffset().getTotalSeconds();
            }
            return toLocalDateTime().getLong(field);
        }
        return field.getFrom(this);
    }

    default D toLocalDate() {
        return toLocalDateTime().toLocalDate();
    }

    default LocalTime toLocalTime() {
        return toLocalDateTime().toLocalTime();
    }

    ChronoLocalDateTime<D> toLocalDateTime();

    default Chronology getChronology() {
        return toLocalDate().getChronology();
    }

    ZoneOffset getOffset();

    ZoneId getZone();

    ChronoZonedDateTime<D> withEarlierOffsetAtOverlap();

    ChronoZonedDateTime<D> withLaterOffsetAtOverlap();

    ChronoZonedDateTime<D> withZoneSameLocal(ZoneId zone);

    ChronoZonedDateTime<D> withZoneSameInstant(ZoneId zone);

    boolean isSupported(TemporalField field);

    default boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            return unit != ChronoUnit.FOREVER;
        }
        return unit != null && unit.isSupportedBy(this);
    }

    default ChronoZonedDateTime<D> with(TemporalAdjuster adjuster) {
        return ChronoZonedDateTimeImpl.ensured(getChronology(), adjuster.adjustInto(this));
    }

    ChronoZonedDateTime<D> with(TemporalField field, long newValue);

    default ChronoZonedDateTime<D> plus(TemporalAmount amount) {
        return ChronoZonedDateTimeImpl.ensured(getChronology(), amount.addTo(this));
    }

    ChronoZonedDateTime<D> plus(long amountToAdd, TemporalUnit unit);

    default ChronoZonedDateTime<D> minus(TemporalAmount amount) {
        return ChronoZonedDateTimeImpl.ensured(getChronology(), amount.subtractFrom(this));
    }

    default ChronoZonedDateTime<D> minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    @SuppressWarnings("unchecked")
    default <R> R query(TemporalQuery<R> query) {
        if (query == TemporalQueries.zone() || query == TemporalQueries.zoneId()) {
            return (R) getZone();
        }
        if (query == TemporalQueries.offset()) {
            return (R) getOffset();
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

    default String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    default Instant toInstant() {
        return Instant.ofEpochSecond(toEpochSecond(), toLocalTime().getNano());
    }

    default long toEpochSecond() {
        return toLocalDate().toEpochDay() * 86400L + toLocalTime().toSecondOfDay() - getOffset().getTotalSeconds();
    }

    default int compareTo(ChronoZonedDateTime<?> other) {
        int order = Long.compare(toEpochSecond(), other.toEpochSecond());
        if (order == 0) {
            order = toLocalTime().getNano() - other.toLocalTime().getNano();
            if (order == 0) {
                order = toLocalDateTime().compareTo(other.toLocalDateTime());
                if (order == 0) {
                    order = getZone().getId().compareTo(other.getZone().getId());
                    if (order == 0) {
                        order = getChronology().compareTo(other.getChronology());
                    }
                }
            }
        }
        return order;
    }

    default boolean isAfter(ChronoZonedDateTime<?> other) {
        long second = toEpochSecond();
        long otherSecond = other.toEpochSecond();
        return second > otherSecond || (second == otherSecond && toLocalTime().getNano() > other.toLocalTime().getNano());
    }

    default boolean isBefore(ChronoZonedDateTime<?> other) {
        long second = toEpochSecond();
        long otherSecond = other.toEpochSecond();
        return second < otherSecond || (second == otherSecond && toLocalTime().getNano() < other.toLocalTime().getNano());
    }

    default boolean isEqual(ChronoZonedDateTime<?> other) {
        return toEpochSecond() == other.toEpochSecond() && toLocalTime().getNano() == other.toLocalTime().getNano();
    }
}
