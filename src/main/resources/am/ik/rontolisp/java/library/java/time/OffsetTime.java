// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A time of day with the offset from UTC it is written at, of no date. Two of one
// instant at two offsets are not equal; the order is the instants', as nanoseconds of a
// day less the offset, then the local times'.
package java.time;

import java.io.Serializable;
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

public final class OffsetTime implements Temporal, TemporalAdjuster, Comparable<OffsetTime>, Serializable {

    public static final OffsetTime MIN = new OffsetTime(LocalTime.MIN, ZoneOffset.MAX);

    public static final OffsetTime MAX = new OffsetTime(LocalTime.MAX, ZoneOffset.MIN);

    private final LocalTime time;

    private final ZoneOffset offset;

    private OffsetTime(LocalTime time, ZoneOffset offset) {
        this.time = time;
        this.offset = offset;
    }

    public static OffsetTime now() {
        return now(Clock.systemDefaultZone());
    }

    public static OffsetTime now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static OffsetTime now(Clock clock) {
        return ofInstant(clock.instant(), clock.getZone());
    }

    public static OffsetTime of(LocalTime time, ZoneOffset offset) {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(offset, "offset");
        return new OffsetTime(time, offset);
    }

    public static OffsetTime of(int hour, int minute, int second, int nanoOfSecond, ZoneOffset offset) {
        return of(LocalTime.of(hour, minute, second, nanoOfSecond), offset);
    }

    public static OffsetTime ofInstant(Instant instant, ZoneId zone) {
        ZoneOffset offset = ZoneId.regional() ? zone.offsetAt(instant.getEpochSecond()) : zone.fixed();
        long second = Math.floorMod(instant.getEpochSecond() + offset.getTotalSeconds(), Iso.DAY);
        return new OffsetTime(LocalTime.ofNanoOfDay(second * Iso.SECOND + instant.getNano()), offset);
    }

    public static OffsetTime from(TemporalAccessor temporal) {
        if (temporal instanceof OffsetTime time) {
            return time;
        }
        try {
            return new OffsetTime(LocalTime.from(temporal), ZoneOffset.from(temporal));
        }
        catch (DateTimeException refused) {
            throw new DateTimeException("Unable to obtain OffsetTime from TemporalAccessor: " + temporal + " of type "
                    + temporal.getClass().getName(), refused);
        }
    }

    public static OffsetTime parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.time();
        in.offset(false);
        in.end();
        LocalTime time = in.localTime();
        try {
            return new OffsetTime(time, ZoneOffset.ofTotalSeconds(in.offset));
        }
        catch (DateTimeException invalid) {
            // the words of a JDK, whose formatter kept an offset it could make no value of
            throw in.refused(new DateTimeException("Unable to obtain OffsetTime from TemporalAccessor: {OffsetSeconds="
                    + in.offset + "},ISO resolved to " + time + " of type java.time.format.Parsed", invalid));
        }
    }

    public static OffsetTime parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, OffsetTime::from);
    }

    private OffsetTime with(LocalTime newTime, ZoneOffset newOffset) {
        return time == newTime && offset.equals(newOffset) ? this : new OffsetTime(newTime, newOffset);
    }

    /** The instant within a day, in nanoseconds: the time less its offset. */
    private long epochNano() {
        return time.toNanoOfDay() - offset.getTotalSeconds() * Iso.SECOND;
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field.isTimeBased() || field == ChronoField.OFFSET_SECONDS
                : field != null && field.isSupportedBy(this);
    }

    public boolean isSupported(TemporalUnit unit) {
        return unit instanceof ChronoUnit ? unit.isTimeBased() : unit != null && unit.isSupportedBy(this);
    }

    public ValueRange range(TemporalField field) {
        if (field instanceof ChronoField) {
            return field == ChronoField.OFFSET_SECONDS ? field.range() : time.range(field);
        }
        return field.rangeRefinedBy(this);
    }

    public long getLong(TemporalField field) {
        if (field == ChronoField.OFFSET_SECONDS) {
            return offset.getTotalSeconds();
        }
        return field instanceof ChronoField ? time.getLong(field) : field.getFrom(this);
    }

    public ZoneOffset getOffset() {
        return offset;
    }

    public OffsetTime withOffsetSameLocal(ZoneOffset offset) {
        return with(time, offset);
    }

    public OffsetTime withOffsetSameInstant(ZoneOffset offset) {
        if (offset.equals(this.offset)) {
            return this;
        }
        return new OffsetTime(time.plusSeconds(offset.getTotalSeconds() - this.offset.getTotalSeconds()), offset);
    }

    public LocalTime toLocalTime() {
        return time;
    }

    public int getHour() {
        return time.getHour();
    }

    public int getMinute() {
        return time.getMinute();
    }

    public int getSecond() {
        return time.getSecond();
    }

    public int getNano() {
        return time.getNano();
    }

    public OffsetTime with(TemporalAdjuster adjuster) {
        if (adjuster instanceof LocalTime newTime) {
            return with(newTime, offset);
        }
        if (adjuster instanceof ZoneOffset newOffset) {
            return with(time, newOffset);
        }
        return adjuster instanceof OffsetTime other ? other : (OffsetTime) adjuster.adjustInto(this);
    }

    public OffsetTime with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            if (field == ChronoField.OFFSET_SECONDS) {
                return with(time, ZoneOffset.ofTotalSeconds(chrono.checkValidIntValue(newValue)));
            }
            return with(time.with(field, newValue), offset);
        }
        return field.adjustInto(this, newValue);
    }

    public OffsetTime withHour(int hour) {
        return with(time.withHour(hour), offset);
    }

    public OffsetTime withMinute(int minute) {
        return with(time.withMinute(minute), offset);
    }

    public OffsetTime withSecond(int second) {
        return with(time.withSecond(second), offset);
    }

    public OffsetTime withNano(int nanoOfSecond) {
        return with(time.withNano(nanoOfSecond), offset);
    }

    public OffsetTime truncatedTo(TemporalUnit unit) {
        return with(time.truncatedTo(unit), offset);
    }

    public OffsetTime plus(TemporalAmount amountToAdd) {
        return (OffsetTime) amountToAdd.addTo(this);
    }

    public OffsetTime plus(long amountToAdd, TemporalUnit unit) {
        return unit instanceof ChronoUnit ? with(time.plus(amountToAdd, unit), offset) : unit.addTo(this, amountToAdd);
    }

    public OffsetTime plusHours(long hours) {
        return with(time.plusHours(hours), offset);
    }

    public OffsetTime plusMinutes(long minutes) {
        return with(time.plusMinutes(minutes), offset);
    }

    public OffsetTime plusSeconds(long seconds) {
        return with(time.plusSeconds(seconds), offset);
    }

    public OffsetTime plusNanos(long nanos) {
        return with(time.plusNanos(nanos), offset);
    }

    public OffsetTime minus(TemporalAmount amountToSubtract) {
        return (OffsetTime) amountToSubtract.subtractFrom(this);
    }

    public OffsetTime minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public OffsetTime minusHours(long hours) {
        return with(time.minusHours(hours), offset);
    }

    public OffsetTime minusMinutes(long minutes) {
        return with(time.minusMinutes(minutes), offset);
    }

    public OffsetTime minusSeconds(long seconds) {
        return with(time.minusSeconds(seconds), offset);
    }

    public OffsetTime minusNanos(long nanos) {
        return with(time.minusNanos(nanos), offset);
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        if (Iso.calendars()) {
            if (query == TemporalQueries.chronology()) {
                return null;
            }
        }
        return query == TemporalQueries.precision() ? (R) ChronoUnit.NANOS
                : query == TemporalQueries.zoneId() ? null : query.queryFrom(this);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.NANO_OF_DAY, time.toNanoOfDay())
            .with(ChronoField.OFFSET_SECONDS, offset.getTotalSeconds());
    }

    /** The whole units from this instant of a day to another's, the other read as an OffsetTime. */
    public long until(Temporal endExclusive, TemporalUnit unit) {
        OffsetTime end = from(endExclusive);
        if (unit instanceof ChronoUnit chrono) {
            long nanos = end.epochNano() - epochNano();
            switch (chrono) {
                case NANOS:
                    return nanos;
                case MICROS:
                    return nanos / 1000;
                case MILLIS:
                    return nanos / 1000000;
                case SECONDS:
                    return nanos / Iso.SECOND;
                case MINUTES:
                    return nanos / (60 * Iso.SECOND);
                case HOURS:
                    return nanos / (3600 * Iso.SECOND);
                case HALF_DAYS:
                    return nanos / (43200 * Iso.SECOND);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.between(this, end);
    }

    public String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    public OffsetDateTime atDate(LocalDate date) {
        return OffsetDateTime.of(date, time, offset);
    }

    public long toEpochSecond(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return date.toEpochDay() * Iso.DAY + time.toSecondOfDay() - offset.getTotalSeconds();
    }

    public int compareTo(OffsetTime other) {
        if (offset.equals(other.offset)) {
            return time.compareTo(other.time);
        }
        int order = Long.compare(epochNano(), other.epochNano());
        return order != 0 ? order : time.compareTo(other.time);
    }

    public boolean isAfter(OffsetTime other) {
        return epochNano() > other.epochNano();
    }

    public boolean isBefore(OffsetTime other) {
        return epochNano() < other.epochNano();
    }

    public boolean isEqual(OffsetTime other) {
        return epochNano() == other.epochNano();
    }

    public boolean equals(Object other) {
        return other instanceof OffsetTime that && that.time.equals(time) && that.offset.equals(offset);
    }

    public int hashCode() {
        return time.hashCode() ^ offset.hashCode();
    }

    public String toString() {
        return time.toString() + offset.toString();
    }
}
