// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The date-time of a calendar other than ISO's: a date of the calendar and a time of day.
// It has the name a JDK's has, which getClass() and a refused cast show. What the
// documentation leaves open is a JDK 25's answers: a ChronoField of a date is the date's
// and one of a time the time's; a date given as an adjuster is checked to be one of the
// calendar (Chronology mismatch, expected), any other result to be the calendar's
// date-time (required); a time-based unit moves the time of day and carries whole days
// into the date in one step, but half days, which move the date by 256ths of the amount
// first; until a time-based unit counts the days in the unit and adds what the times of
// day differ by, cut by itself (so not always what ISO's date-times count), a date-based
// one counts the dates', the end's a day earlier where its time is earlier.
// Its hash is the date's xor the time's.
package java.time.chrono;

import java.io.Serializable;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.time.temporal.ValueRange;
import java.util.Objects;

final class ChronoLocalDateTimeImpl<D extends ChronoLocalDate>
        implements ChronoLocalDateTime<D>, Temporal, TemporalAdjuster, Serializable {

    private static final long NANOS_PER_DAY = 86400000000000L;

    private static final String NAME = "java.time.chrono.ChronoLocalDateTimeImpl";

    private final D date;

    private final LocalTime time;

    private ChronoLocalDateTimeImpl(D date, LocalTime time) {
        this.date = date;
        this.time = time;
    }

    static <R extends ChronoLocalDate> ChronoLocalDateTimeImpl<R> of(R date, LocalTime time) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(time, "time");
        return new ChronoLocalDateTimeImpl<>(date, time);
    }

    /** A value cast to a calendar's date-time, refused in a JDK 25's words. */
    @SuppressWarnings("unchecked")
    static <R extends ChronoLocalDate> ChronoLocalDateTimeImpl<R> cast(Object value) {
        if (!(value instanceof ChronoLocalDateTimeImpl<?> dateTime)) {
            throw AbstractChronology.castRefused(value, NAME);
        }
        return (ChronoLocalDateTimeImpl<R>) dateTime;
    }

    /** What a change made, checked to be a date-time of the calendar. */
    static <R extends ChronoLocalDate> ChronoLocalDateTimeImpl<R> ensured(Chronology chrono, Temporal temporal) {
        ChronoLocalDateTimeImpl<R> dateTime = cast(temporal);
        if (!chrono.equals(dateTime.getChronology())) {
            throw new ClassCastException("Chronology mismatch, required: " + chrono.getId() + ", actual: "
                    + dateTime.getChronology().getId());
        }
        return dateTime;
    }

    @SuppressWarnings("unchecked")
    private ChronoLocalDateTimeImpl<D> with(Temporal newDate, LocalTime newTime) {
        if (date == newDate && time == newTime) {
            return this;
        }
        return new ChronoLocalDateTimeImpl<>((D) AbstractChronology.dated(date.getChronology(), newDate), newTime);
    }

    public D toLocalDate() {
        return date;
    }

    public LocalTime toLocalTime() {
        return time;
    }

    public boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField) {
            return field.isDateBased() || field.isTimeBased();
        }
        return field != null && field.isSupportedBy(this);
    }

    public ValueRange range(TemporalField field) {
        if (field instanceof ChronoField) {
            return field.isTimeBased() ? time.range(field) : date.range(field);
        }
        return field.rangeRefinedBy(this);
    }

    public int get(TemporalField field) {
        if (field instanceof ChronoField) {
            return field.isTimeBased() ? time.get(field) : date.get(field);
        }
        return range(field).checkValidIntValue(getLong(field), field);
    }

    public long getLong(TemporalField field) {
        if (field instanceof ChronoField) {
            return field.isTimeBased() ? time.getLong(field) : date.getLong(field);
        }
        return field.getFrom(this);
    }

    public ChronoLocalDateTimeImpl<D> with(TemporalAdjuster adjuster) {
        if (adjuster instanceof ChronoLocalDate newDate) {
            return with(newDate, time);
        }
        if (adjuster instanceof LocalTime newTime) {
            return with(date, newTime);
        }
        if (adjuster instanceof ChronoLocalDateTimeImpl<?> dateTime) {
            return ensured(date.getChronology(), dateTime);
        }
        return ensured(date.getChronology(), adjuster.adjustInto(this));
    }

    public ChronoLocalDateTimeImpl<D> with(TemporalField field, long newValue) {
        if (field instanceof ChronoField) {
            return field.isTimeBased() ? with(date, time.with(field, newValue)) : with(date.with(field, newValue), time);
        }
        return ensured(date.getChronology(), field.adjustInto(this, newValue));
    }

    public ChronoLocalDateTimeImpl<D> plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chronoUnit) {
            switch (chronoUnit) {
                case NANOS:
                    return moved(amountToAdd / NANOS_PER_DAY, amountToAdd % NANOS_PER_DAY);
                case MICROS:
                    return moved(amountToAdd / (NANOS_PER_DAY / 1000), amountToAdd % (NANOS_PER_DAY / 1000) * 1000);
                case MILLIS:
                    return moved(amountToAdd / (NANOS_PER_DAY / 1000000),
                            amountToAdd % (NANOS_PER_DAY / 1000000) * 1000000);
                case SECONDS:
                    return plusSeconds(amountToAdd);
                case MINUTES:
                    return moved(amountToAdd / 1440, amountToAdd % 1440 * 60000000000L);
                case HOURS:
                    return moved(amountToAdd / 24, amountToAdd % 24 * 3600000000000L);
                case HALF_DAYS: {
                    long hours = amountToAdd % 256 * 12;
                    return moved(amountToAdd / 256, 0).moved(hours / 24, hours % 24 * 3600000000000L);
                }
                default:
                    return with(date.plus(amountToAdd, unit), time);
            }
        }
        return ensured(date.getChronology(), unit.addTo(this, amountToAdd));
    }

    ChronoLocalDateTimeImpl<D> plusSeconds(long seconds) {
        return moved(seconds / 86400, seconds % 86400 * 1000000000L);
    }

    /** Days and nanoseconds added: the time of day moved, the whole days it passes added to the date with the others. */
    private ChronoLocalDateTimeImpl<D> moved(long days, long nanos) {
        if ((days | nanos) == 0) {
            return this;
        }
        long ofDay = time.toNanoOfDay() + nanos;
        long within = Math.floorMod(ofDay, NANOS_PER_DAY);
        long whole = days + Math.floorDiv(ofDay, NANOS_PER_DAY);
        return with(whole == 0 ? date : date.plus(whole, ChronoUnit.DAYS),
                within == time.toNanoOfDay() ? time : LocalTime.ofNanoOfDay(within));
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        Objects.requireNonNull(endExclusive, "endExclusive");
        ChronoLocalDateTime<?> end = getChronology().localDateTime(endExclusive);
        if (unit instanceof ChronoUnit) {
            if (unit.isTimeBased()) {
                // the days in the unit, then what the times of day differ by, each cut by itself
                long days = end.toLocalDate().toEpochDay() - date.toEpochDay();
                return Math.addExact(Math.multiplyExact(days, NANOS_PER_DAY / unit.getDuration().toNanos()),
                        time.until(end.toLocalTime(), unit));
            }
            ChronoLocalDate endDate = end.toLocalDate();
            if (end.toLocalTime().isBefore(time)) {
                endDate = endDate.minus(1, ChronoUnit.DAYS);
            }
            return date.until(endDate, unit);
        }
        Objects.requireNonNull(unit, "unit");
        return unit.between(this, end);
    }

    public ChronoZonedDateTime<D> atZone(ZoneId zone) {
        return ChronoZonedDateTimeImpl.ofBest(this, zone, null);
    }

    public boolean equals(Object obj) {
        return this == obj || (obj instanceof ChronoLocalDateTime<?> other && compareTo(other) == 0);
    }

    public int hashCode() {
        return date.hashCode() ^ time.hashCode();
    }

    public String toString() {
        return date.toString() + 'T' + time.toString();
    }
}
