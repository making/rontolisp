// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A point of the time line: the seconds from 1970-01-01T00:00:00Z and the nanoseconds
// past them, within a thousand million years either way. What its text may be -- the
// seconds always there, an offset with its minutes, a day ending at 24:00:00, a second
// 60 at the end of a day read as 59 -- and that a text failing one of its fields is
// refused at index 0 are a JDK's answers.
package java.time;

import java.io.Serializable;
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

public final class Instant implements Temporal, TemporalAdjuster, Comparable<Instant>, Serializable {

    public static final Instant EPOCH = new Instant(0, 0);

    private static final long MIN_SECOND = Iso.epochDay(-1000000000L, 1, 1) * Iso.DAY;

    private static final long MAX_SECOND = Iso.epochDay(1000000000L, 12, 31) * Iso.DAY + 86399L;

    public static final Instant MIN = new Instant(MIN_SECOND, 0);

    public static final Instant MAX = new Instant(MAX_SECOND, 999999999);

    private final long seconds;

    private final int nanos;

    private Instant(long seconds, int nanos) {
        this.seconds = seconds;
        this.nanos = nanos;
    }

    /** The host's clock: the nanoseconds since the epoch. */
    private static native long wall0();

    public static Instant now() {
        long wall = wall0();
        return new Instant(Math.floorDiv(wall, Iso.SECOND), (int) Math.floorMod(wall, Iso.SECOND));
    }

    public static Instant now(Clock clock) {
        if (clock == null) {
            throw new NullPointerException("clock");
        }
        return clock.instant();
    }

    private static Instant create(long seconds, int nanos) {
        if ((seconds | nanos) == 0) {
            return EPOCH;
        }
        if (seconds < MIN_SECOND || seconds > MAX_SECOND) {
            throw new DateTimeException("Instant exceeds minimum or maximum instant");
        }
        return new Instant(seconds, nanos);
    }

    public static Instant ofEpochSecond(long epochSecond) {
        return create(epochSecond, 0);
    }

    public static Instant ofEpochSecond(long epochSecond, long nanoAdjustment) {
        return create(Math.addExact(epochSecond, Math.floorDiv(nanoAdjustment, Iso.SECOND)),
                (int) Math.floorMod(nanoAdjustment, Iso.SECOND));
    }

    public static Instant ofEpochMilli(long epochMilli) {
        return create(Math.floorDiv(epochMilli, 1000L), (int) Math.floorMod(epochMilli, 1000L) * 1000000);
    }

    public static Instant from(TemporalAccessor temporal) {
        if (temporal instanceof Instant instant) {
            return instant;
        }
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (temporal instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        try {
            return ofEpochSecond(temporal.getLong(ChronoField.INSTANT_SECONDS),
                    temporal.get(ChronoField.NANO_OF_SECOND));
        }
        catch (DateTimeException refused) {
            throw new DateTimeException("Unable to obtain Instant from TemporalAccessor: " + temporal + " of type "
                    + temporal.getClass().getName(), refused);
        }
    }

    public static Instant parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.date();
        in.mark();
        in.timeToTheSecond();
        in.offset(false);
        in.end();
        long days = 0;
        if (in.hour == 24 && in.minute == 0 && in.second == 0 && in.nano == 0) {
            // the end of a day is the start of the next
            in.hour = 0;
            days = 1;
        }
        else if (in.hour == 23 && in.minute == 59 && in.second == 60) {
            in.second = 59;
        }
        if (in.month < 1 || in.month > 12 || in.day < 1 || in.day > Iso.monthLength(in.year, in.month)
                || in.hour > 23 || in.minute > 59 || in.second > 59 || in.offset < -64800 || in.offset > 64800) {
            throw in.failed(0);
        }
        long second = (Iso.epochDay(in.year, in.month, in.day) + days) * Iso.DAY + in.hour * 3600 + in.minute * 60
                + in.second - in.offset;
        if (second < MIN_SECOND || second > MAX_SECOND) {
            throw in.refused(new DateTimeException("Unable to obtain Instant from TemporalAccessor: {InstantSeconds="
                    + second + ", NanoOfSecond=" + in.nano + ", MicroOfSecond=" + in.nano / 1000 + ", MilliOfSecond="
                    + in.nano / 1000000 + "},ISO of type java.time.format.Parsed"));
        }
        return create(second, in.nano);
    }

    public boolean isSupported(TemporalField field) {
        if (field instanceof ChronoField) {
            return field == ChronoField.INSTANT_SECONDS || field == ChronoField.NANO_OF_SECOND
                    || field == ChronoField.MICRO_OF_SECOND || field == ChronoField.MILLI_OF_SECOND;
        }
        return field != null && field.isSupportedBy(this);
    }

    public boolean isSupported(TemporalUnit unit) {
        if (unit instanceof ChronoUnit) {
            return unit.isTimeBased() || unit == ChronoUnit.DAYS;
        }
        return unit != null && unit.isSupportedBy(this);
    }

    public long getLong(TemporalField field) {
        if (field instanceof ChronoField chrono) {
            switch (chrono) {
                case NANO_OF_SECOND:
                    return nanos;
                case MICRO_OF_SECOND:
                    return nanos / 1000;
                case MILLI_OF_SECOND:
                    return nanos / 1000000;
                case INSTANT_SECONDS:
                    return seconds;
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.getFrom(this);
    }

    public int get(TemporalField field) {
        if (field == ChronoField.INSTANT_SECONDS) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        if (field instanceof ChronoField) {
            return (int) getLong(field);
        }
        return range(field).checkValidIntValue(field.getFrom(this), field);
    }

    public long getEpochSecond() {
        return seconds;
    }

    public int getNano() {
        return nanos;
    }

    public Instant with(TemporalAdjuster adjuster) {
        return (Instant) adjuster.adjustInto(this);
    }

    public Instant with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            chrono.checkValidValue(newValue);
            switch (chrono) {
                case NANO_OF_SECOND:
                    return create(seconds, (int) newValue);
                case MICRO_OF_SECOND:
                    return create(seconds, (int) newValue * 1000);
                case MILLI_OF_SECOND:
                    return create(seconds, (int) newValue * 1000000);
                case INSTANT_SECONDS:
                    return create(newValue, nanos);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.adjustInto(this, newValue);
    }

    public Instant truncatedTo(TemporalUnit unit) {
        if (unit == ChronoUnit.NANOS) {
            return this;
        }
        Duration length = unit.getDuration();
        if (length.getSeconds() > Iso.DAY) {
            throw new UnsupportedTemporalTypeException("Unit is too large to be used for truncation");
        }
        long step = length.toNanos();
        if (Iso.DAY * Iso.SECOND % step != 0) {
            throw new UnsupportedTemporalTypeException("Unit must divide into a standard day without remainder");
        }
        long ofDay = Math.floorMod(seconds, Iso.DAY) * Iso.SECOND + nanos;
        return plusNanos(ofDay / step * step - ofDay);
    }

    private Instant plus(long secondsToAdd, long nanosToAdd) {
        if ((secondsToAdd | nanosToAdd) == 0) {
            return this;
        }
        long second = Math.addExact(Math.addExact(seconds, secondsToAdd), nanosToAdd / Iso.SECOND);
        return ofEpochSecond(second, nanos + nanosToAdd % Iso.SECOND);
    }

    public Instant plus(TemporalAmount amountToAdd) {
        if (amountToAdd instanceof Duration duration) {
            return plus(duration.getSeconds(), duration.getNano());
        }
        return (Instant) amountToAdd.addTo(this);
    }

    public Instant plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
                case NANOS:
                    return plus(0, amountToAdd);
                case MICROS:
                    return plus(amountToAdd / 1000000, amountToAdd % 1000000 * 1000);
                case MILLIS:
                    return plusMillis(amountToAdd);
                case SECONDS:
                    return plus(amountToAdd, 0);
                case MINUTES:
                    return plus(Math.multiplyExact(amountToAdd, 60L), 0);
                case HOURS:
                    return plus(Math.multiplyExact(amountToAdd, 3600L), 0);
                case HALF_DAYS:
                    return plus(Math.multiplyExact(amountToAdd, 43200L), 0);
                case DAYS:
                    return plus(Math.multiplyExact(amountToAdd, Iso.DAY), 0);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.addTo(this, amountToAdd);
    }

    public Instant plusSeconds(long secondsToAdd) {
        return plus(secondsToAdd, 0);
    }

    public Instant plusMillis(long millisToAdd) {
        return plus(millisToAdd / 1000, millisToAdd % 1000 * 1000000);
    }

    public Instant plusNanos(long nanosToAdd) {
        return plus(0, nanosToAdd);
    }

    public Instant minus(TemporalAmount amountToSubtract) {
        if (amountToSubtract instanceof Duration duration) {
            return minusSeconds(duration.getSeconds()).minusNanos(duration.getNano());
        }
        return (Instant) amountToSubtract.subtractFrom(this);
    }

    public Instant minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public Instant minusSeconds(long secondsToSubtract) {
        return secondsToSubtract == Long.MIN_VALUE ? plusSeconds(Long.MAX_VALUE).plusSeconds(1)
                : plusSeconds(-secondsToSubtract);
    }

    public Instant minusMillis(long millisToSubtract) {
        return millisToSubtract == Long.MIN_VALUE ? plusMillis(Long.MAX_VALUE).plusMillis(1)
                : plusMillis(-millisToSubtract);
    }

    public Instant minusNanos(long nanosToSubtract) {
        return nanosToSubtract == Long.MIN_VALUE ? plusNanos(Long.MAX_VALUE).plusNanos(1)
                : plusNanos(-nanosToSubtract);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.INSTANT_SECONDS, seconds).with(ChronoField.NANO_OF_SECOND, nanos);
    }

    /**
     * The time to another instant in parts of a second, counted toward zero: the
     * seconds and the nanoseconds between are brought to one sign first.
     */
    private long until(Instant end, long perSecond) {
        long between = Math.subtractExact(end.seconds, seconds);
        long nanosBetween = end.nanos - nanos;
        if (between > 0 && nanosBetween < 0) {
            between--;
            nanosBetween += Iso.SECOND;
        }
        else if (between < 0 && nanosBetween > 0) {
            between++;
            nanosBetween -= Iso.SECOND;
        }
        return Math.addExact(Math.multiplyExact(between, perSecond), nanosBetween / (Iso.SECOND / perSecond));
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        Instant end = from(endExclusive);
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
                case NANOS:
                    return until(end, Iso.SECOND);
                case MICROS:
                    return until(end, 1000000L);
                case MILLIS:
                    return until(end, 1000L);
                case SECONDS:
                    return until(end, 1L);
                case MINUTES:
                    return until(end, 1L) / 60;
                case HOURS:
                    return until(end, 1L) / 3600;
                case HALF_DAYS:
                    return until(end, 1L) / 43200;
                case DAYS:
                    return until(end, 1L) / Iso.DAY;
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.between(this, end);
    }

    public OffsetDateTime atOffset(ZoneOffset offset) {
        return OffsetDateTime.ofInstant(this, offset);
    }

    public ZonedDateTime atZone(ZoneId zone) {
        return ZonedDateTime.ofInstant(this, zone);
    }

    public long toEpochMilli() {
        if (seconds < 0 && nanos > 0) {
            // counted from the second after, so that the product stays in a long
            return Math.addExact(Math.multiplyExact(seconds + 1, 1000L), nanos / 1000000 - 1000L);
        }
        return Math.addExact(Math.multiplyExact(seconds, 1000L), (long) (nanos / 1000000));
    }

    public int compareTo(Instant otherInstant) {
        int order = Long.compare(seconds, otherInstant.seconds);
        return order != 0 ? order : nanos - otherInstant.nanos;
    }

    public boolean isAfter(Instant otherInstant) {
        return compareTo(otherInstant) > 0;
    }

    public boolean isBefore(Instant otherInstant) {
        return compareTo(otherInstant) < 0;
    }

    public boolean equals(Object other) {
        return other instanceof Instant that && that.seconds == seconds && that.nanos == nanos;
    }

    public int hashCode() {
        return Long.hashCode(seconds) + 51 * nanos;
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        return query == TemporalQueries.precision() ? (R) ChronoUnit.NANOS
                : query == TemporalQueries.zoneId() ? null : query.queryFrom(this);
    }

    public String toString() {
        long days = Math.floorDiv(seconds, Iso.DAY);
        int ofDay = (int) Math.floorMod(seconds, Iso.DAY);
        long year = Iso.yearOf(days);
        int ofYear = (int) (days + Iso.EPOCH - Iso.yearStart(year));
        int month = Iso.monthOf(year, ofYear);
        StringBuilder text = new StringBuilder();
        Iso.date(text, year, month, ofYear - Iso.monthStart(year, month) + 1).append('T');
        return Iso.time(text, ofDay / 3600, ofDay / 60 % 60, ofDay % 60, nanos, true).append('Z').toString();
    }
}
