// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The zoned date-time of a calendar other than ISO's: its date-time, the offset and the
// zone. It has the name a JDK's has, which getClass() and a refused cast show. The offset
// a local date-time has in a zone is the one ZonedDateTime finds for ISO's date-time of
// the same day and time (in a gap the date-time moved later by the gap's length, in an
// overlap the offset asked for where it is one of the two, else the earlier). What the
// documentation leaves open is a JDK 25's answers: every unit, a time-based one too, moves
// the local date-time and keeps the offset where it can (where ZonedDateTime moves the
// instant); a ChronoField but the instant and the offset sets the local date-time; the
// instant's seconds are added as seconds to the local date-time; an offset given places
// the local date-time's instant in the zone; until counts between the local date-times at
// this offset; a cast of what a change made is refused in a JDK's words (Chronology
// mismatch, required). Its hash is a ZonedDateTime's.
package java.time.chrono;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.util.Objects;

final class ChronoZonedDateTimeImpl<D extends ChronoLocalDate> implements ChronoZonedDateTime<D>, Serializable {

    private final ChronoLocalDateTimeImpl<D> dateTime;

    private final ZoneOffset offset;

    private final ZoneId zone;

    private ChronoZonedDateTimeImpl(ChronoLocalDateTimeImpl<D> dateTime, ZoneOffset offset, ZoneId zone) {
        this.dateTime = dateTime;
        this.offset = offset;
        this.zone = zone;
    }

    /** ISO's date-time of a calendar's: the same epoch day and time. */
    private static LocalDateTime iso(ChronoLocalDateTime<?> dateTime) {
        return LocalDateTime.of(LocalDate.ofEpochDay(dateTime.toLocalDate().toEpochDay()), dateTime.toLocalTime());
    }

    /** A local date-time in a zone, at the offset it has there (preferred where it has two). */
    static <R extends ChronoLocalDate> ChronoZonedDateTimeImpl<R> ofBest(ChronoLocalDateTimeImpl<R> localDateTime,
            ZoneId zone, ZoneOffset preferredOffset) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        Objects.requireNonNull(zone, "zone");
        if (zone instanceof ZoneOffset fixed) {
            return new ChronoZonedDateTimeImpl<>(localDateTime, fixed, zone);
        }
        LocalDateTime iso = iso(localDateTime);
        ZonedDateTime placed = ZonedDateTime.ofLocal(iso, zone, preferredOffset);
        long moved = placed.toLocalDateTime().toEpochSecond(ZoneOffset.UTC) - iso.toEpochSecond(ZoneOffset.UTC);
        return new ChronoZonedDateTimeImpl<>(moved == 0 ? localDateTime : localDateTime.plusSeconds(moved),
                placed.getOffset(), zone);
    }

    /** The zoned date-time of an instant in a zone in a calendar, its date-time the calendar's. */
    static ChronoZonedDateTimeImpl<?> ofInstant(Chronology chrono, Instant instant, ZoneId zone) {
        if (zone == null) {
            throw new NullPointerException("Cannot invoke \"java.time.ZoneId.getRules()\" because \"zone\" is null");
        }
        if (instant == null) {
            throw new NullPointerException(
                    "Cannot invoke \"java.time.Instant.getEpochSecond()\" because \"instant\" is null");
        }
        ZonedDateTime iso = ZonedDateTime.ofInstant(instant, zone);
        return new ChronoZonedDateTimeImpl<>(ChronoLocalDateTimeImpl.cast(chrono.localDateTime(iso.toLocalDateTime())),
                iso.getOffset(), zone);
    }

    /** What a change made, checked to be a zoned date-time of the calendar. */
    @SuppressWarnings("unchecked")
    static <R extends ChronoLocalDate> ChronoZonedDateTimeImpl<R> ensured(Chronology chrono, Temporal temporal) {
        if (!(temporal instanceof ChronoZonedDateTimeImpl<?> dateTime)) {
            throw AbstractChronology.castRefused(temporal, "java.time.chrono.ChronoZonedDateTimeImpl");
        }
        if (!chrono.equals(dateTime.getChronology())) {
            throw new ClassCastException("Chronology mismatch, required: " + chrono.getId() + ", actual: "
                    + dateTime.getChronology().getId());
        }
        return (ChronoZonedDateTimeImpl<R>) dateTime;
    }

    @SuppressWarnings("unchecked")
    private ChronoZonedDateTimeImpl<D> create(Instant instant, ZoneId zone) {
        return (ChronoZonedDateTimeImpl<D>) ofInstant(getChronology(), instant, zone);
    }

    public ZoneOffset getOffset() {
        return offset;
    }

    public ZoneId getZone() {
        return zone;
    }

    public ChronoLocalDateTime<D> toLocalDateTime() {
        return dateTime;
    }

    /** The offset this local date-time has in the zone where the zone has two there. */
    private ZoneOffset overlap(boolean later) {
        ZonedDateTime placed = ZonedDateTime.ofLocal(iso(dateTime), zone, offset);
        return (later ? placed.withLaterOffsetAtOverlap() : placed.withEarlierOffsetAtOverlap()).getOffset();
    }

    public ChronoZonedDateTime<D> withEarlierOffsetAtOverlap() {
        ZoneOffset earlier = overlap(false);
        return earlier.equals(offset) ? this : new ChronoZonedDateTimeImpl<>(dateTime, earlier, zone);
    }

    public ChronoZonedDateTime<D> withLaterOffsetAtOverlap() {
        ZoneOffset later = overlap(true);
        return later.equals(offset) ? this : new ChronoZonedDateTimeImpl<>(dateTime, later, zone);
    }

    public ChronoZonedDateTime<D> withZoneSameLocal(ZoneId zone) {
        return ofBest(dateTime, zone, offset);
    }

    public ChronoZonedDateTime<D> withZoneSameInstant(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return this.zone.equals(zone) ? this : create(dateTime.toInstant(offset), zone);
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField || (field != null && field.isSupportedBy(this));
    }

    public ChronoZonedDateTime<D> with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chronoField) {
            if (chronoField == ChronoField.INSTANT_SECONDS) {
                return plus(newValue - toEpochSecond(), ChronoUnit.SECONDS);
            }
            if (chronoField == ChronoField.OFFSET_SECONDS) {
                ZoneOffset given = ZoneOffset.ofTotalSeconds(chronoField.checkValidIntValue(newValue));
                return create(dateTime.toInstant(given), zone);
            }
            return ofBest(dateTime.with(field, newValue), zone, offset);
        }
        return ensured(getChronology(), field.adjustInto(this, newValue));
    }

    public ChronoZonedDateTime<D> plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            return ofBest(dateTime.plus(amountToAdd, unit), zone, offset);
        }
        return ensured(getChronology(), unit.addTo(this, amountToAdd));
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        Objects.requireNonNull(endExclusive, "endExclusive");
        ChronoZonedDateTime<?> end = getChronology().zonedDateTime(endExclusive);
        if (unit instanceof ChronoUnit) {
            return dateTime.until(end.withZoneSameInstant(offset).toLocalDateTime(), unit);
        }
        Objects.requireNonNull(unit, "unit");
        return unit.between(this, end);
    }

    public boolean equals(Object obj) {
        return this == obj || (obj instanceof ChronoZonedDateTime<?> other && compareTo(other) == 0);
    }

    public int hashCode() {
        return dateTime.hashCode() ^ offset.hashCode() ^ Integer.rotateLeft(zone.hashCode(), 3);
    }

    public String toString() {
        String text = dateTime.toString() + offset.toString();
        return offset != zone ? text + '[' + zone.toString() + ']' : text;
    }
}
