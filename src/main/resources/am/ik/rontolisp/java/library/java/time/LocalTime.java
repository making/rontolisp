// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A time of day to the nanosecond, with no date and no zone. An amount added goes
// round midnight.
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
import java.util.Objects;

public final class LocalTime implements Temporal, TemporalAdjuster, Comparable<LocalTime>, Serializable {

    private static final long NANOS_OF_DAY = Iso.DAY * Iso.SECOND;

    public static final LocalTime MIN = new LocalTime(0, 0, 0, 0);

    public static final LocalTime MAX = new LocalTime(23, 59, 59, 999999999);

    public static final LocalTime MIDNIGHT = MIN;

    public static final LocalTime NOON = new LocalTime(12, 0, 0, 0);

    private final int hour;

    private final int minute;

    private final int second;

    private final int nano;

    private LocalTime(int hour, int minute, int second, int nano) {
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.nano = nano;
    }

    public static LocalTime now() {
        return now(Clock.systemDefaultZone());
    }

    public static LocalTime now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static LocalTime now(Clock clock) {
        return ofInstant(clock.instant(), clock.getZone());
    }

    public static LocalTime of(int hour, int minute) {
        return of(hour, minute, 0, 0);
    }

    public static LocalTime of(int hour, int minute, int second) {
        return of(hour, minute, second, 0);
    }

    public static LocalTime of(int hour, int minute, int second, int nanoOfSecond) {
        Iso.check(hour, 0, 23, "HourOfDay");
        Iso.check(minute, 0, 59, "MinuteOfHour");
        Iso.check(second, 0, 59, "SecondOfMinute");
        Iso.check(nanoOfSecond, 0, 999999999, "NanoOfSecond");
        return new LocalTime(hour, minute, second, nanoOfSecond);
    }

    public static LocalTime ofInstant(Instant instant, ZoneId zone) {
        int ofDay = (int) Math.floorMod(instant.getEpochSecond()
                + (ZoneId.regional() ? zone.offsetAt(instant.getEpochSecond()) : zone.fixed()).getTotalSeconds(), Iso.DAY);
        return new LocalTime(ofDay / 3600, ofDay / 60 % 60, ofDay % 60, instant.getNano());
    }

    public static LocalTime ofSecondOfDay(long secondOfDay) {
        int ofDay = (int) Iso.check(secondOfDay, 0, Iso.DAY - 1, "SecondOfDay");
        return new LocalTime(ofDay / 3600, ofDay / 60 % 60, ofDay % 60, 0);
    }

    public static LocalTime ofNanoOfDay(long nanoOfDay) {
        Iso.check(nanoOfDay, 0, NANOS_OF_DAY - 1, "NanoOfDay");
        int ofDay = (int) (nanoOfDay / Iso.SECOND);
        return new LocalTime(ofDay / 3600, ofDay / 60 % 60, ofDay % 60, (int) (nanoOfDay % Iso.SECOND));
    }

    public static LocalTime from(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        if (temporal instanceof LocalTime time) {
            return time;
        }
        if (temporal instanceof LocalDateTime dateTime) {
            return dateTime.toLocalTime();
        }
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime.toLocalTime();
        }
        if (temporal instanceof OffsetDateTime dateTime) {
            return dateTime.toLocalTime();
        }
        if (temporal instanceof OffsetTime time) {
            return time.toLocalTime();
        }
        if (Iso.foreign()) {
            LocalTime time = temporal.query(TemporalQueries.localTime());
            if (time != null) {
                return time;
            }
        }
        throw new DateTimeException("Unable to obtain LocalTime from TemporalAccessor: " + temporal + " of type "
                + temporal.getClass().getName());
    }

    public static LocalTime parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.time();
        in.end();
        return in.localTime();
    }

    public static LocalTime parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, LocalTime::from);
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field.isTimeBased() : field != null && field.isSupportedBy(this);
    }

    public boolean isSupported(TemporalUnit unit) {
        return unit instanceof ChronoUnit ? unit.isTimeBased() : unit != null && unit.isSupportedBy(this);
    }

    public int get(TemporalField field) {
        if (field instanceof ChronoField) {
            if (field == ChronoField.NANO_OF_DAY || field == ChronoField.MICRO_OF_DAY) {
                throw new UnsupportedTemporalTypeException(
                        "Invalid field '" + field + "' for get() method, use getLong() instead");
            }
            return (int) getLong(field);
        }
        return range(field).checkValidIntValue(field.getFrom(this), field);
    }

    public long getLong(TemporalField field) {
        if (field instanceof ChronoField chrono) {
            switch (chrono) {
                case NANO_OF_SECOND:
                    return nano;
                case NANO_OF_DAY:
                    return toNanoOfDay();
                case MICRO_OF_SECOND:
                    return nano / 1000;
                case MICRO_OF_DAY:
                    return toNanoOfDay() / 1000;
                case MILLI_OF_SECOND:
                    return nano / 1000000;
                case MILLI_OF_DAY:
                    return toNanoOfDay() / 1000000;
                case SECOND_OF_MINUTE:
                    return second;
                case SECOND_OF_DAY:
                    return toSecondOfDay();
                case MINUTE_OF_HOUR:
                    return minute;
                case MINUTE_OF_DAY:
                    return hour * 60 + minute;
                case HOUR_OF_AMPM:
                    return hour % 12;
                case CLOCK_HOUR_OF_AMPM:
                    return hour % 12 == 0 ? 12 : hour % 12;
                case HOUR_OF_DAY:
                    return hour;
                case CLOCK_HOUR_OF_DAY:
                    return hour == 0 ? 24 : hour;
                case AMPM_OF_DAY:
                    return hour / 12;
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.getFrom(this);
    }

    public int getHour() {
        return hour;
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }

    public int getNano() {
        return nano;
    }

    public LocalTime with(TemporalAdjuster adjuster) {
        return adjuster instanceof LocalTime time ? time : (LocalTime) adjuster.adjustInto(this);
    }

    public LocalTime with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            chrono.checkValidValue(newValue);
            switch (chrono) {
                case NANO_OF_SECOND:
                    return withNano((int) newValue);
                case NANO_OF_DAY:
                    return ofNanoOfDay(newValue);
                case MICRO_OF_SECOND:
                    return withNano((int) newValue * 1000);
                case MICRO_OF_DAY:
                    return ofNanoOfDay(newValue * 1000);
                case MILLI_OF_SECOND:
                    return withNano((int) newValue * 1000000);
                case MILLI_OF_DAY:
                    return ofNanoOfDay(newValue * 1000000);
                case SECOND_OF_MINUTE:
                    return withSecond((int) newValue);
                case SECOND_OF_DAY:
                    return plusSeconds(newValue - toSecondOfDay());
                case MINUTE_OF_HOUR:
                    return withMinute((int) newValue);
                case MINUTE_OF_DAY:
                    return plusMinutes(newValue - (hour * 60 + minute));
                case HOUR_OF_AMPM:
                    return plusHours(newValue - hour % 12);
                case CLOCK_HOUR_OF_AMPM:
                    return plusHours((newValue == 12 ? 0 : newValue) - hour % 12);
                case HOUR_OF_DAY:
                    return withHour((int) newValue);
                case CLOCK_HOUR_OF_DAY:
                    return withHour((int) (newValue == 24 ? 0 : newValue));
                case AMPM_OF_DAY:
                    return plusHours((newValue - hour / 12) * 12);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.adjustInto(this, newValue);
    }

    public LocalTime withHour(int hour) {
        return this.hour == hour ? this : of(hour, minute, second, nano);
    }

    public LocalTime withMinute(int minute) {
        return this.minute == minute ? this : of(hour, minute, second, nano);
    }

    public LocalTime withSecond(int second) {
        return this.second == second ? this : of(hour, minute, second, nano);
    }

    public LocalTime withNano(int nanoOfSecond) {
        return nano == nanoOfSecond ? this : of(hour, minute, second, nanoOfSecond);
    }

    /** The time with nothing below a unit, which has to divide a day. */
    public LocalTime truncatedTo(TemporalUnit unit) {
        if (unit == ChronoUnit.NANOS) {
            return this;
        }
        Duration length = unit.getDuration();
        if (length.getSeconds() > Iso.DAY) {
            throw new UnsupportedTemporalTypeException("Unit is too large to be used for truncation");
        }
        long step = length.toNanos();
        if (NANOS_OF_DAY % step != 0) {
            throw new UnsupportedTemporalTypeException("Unit must divide into a standard day without remainder");
        }
        return ofNanoOfDay(toNanoOfDay() / step * step);
    }

    public LocalTime plus(TemporalAmount amountToAdd) {
        return (LocalTime) amountToAdd.addTo(this);
    }

    public LocalTime plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
                case NANOS:
                    return plusNanos(amountToAdd);
                case MICROS:
                    return plusNanos(amountToAdd % (NANOS_OF_DAY / 1000) * 1000);
                case MILLIS:
                    return plusNanos(amountToAdd % (NANOS_OF_DAY / 1000000) * 1000000);
                case SECONDS:
                    return plusSeconds(amountToAdd);
                case MINUTES:
                    return plusMinutes(amountToAdd);
                case HOURS:
                    return plusHours(amountToAdd);
                case HALF_DAYS:
                    return plusHours(amountToAdd % 2 * 12);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.addTo(this, amountToAdd);
    }

    public LocalTime plusHours(long hoursToAdd) {
        return plusNanos(hoursToAdd % 24 * 3600 * Iso.SECOND);
    }

    public LocalTime plusMinutes(long minutesToAdd) {
        return plusNanos(minutesToAdd % 1440 * 60 * Iso.SECOND);
    }

    public LocalTime plusSeconds(long secondsToAdd) {
        return plusNanos(secondsToAdd % Iso.DAY * Iso.SECOND);
    }

    public LocalTime plusNanos(long nanosToAdd) {
        long moved = nanosToAdd % NANOS_OF_DAY;
        return moved == 0 ? this : ofNanoOfDay(Math.floorMod(toNanoOfDay() + moved, NANOS_OF_DAY));
    }

    public LocalTime minus(TemporalAmount amountToSubtract) {
        return (LocalTime) amountToSubtract.subtractFrom(this);
    }

    public LocalTime minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public LocalTime minusHours(long hoursToSubtract) {
        return plusHours(-(hoursToSubtract % 24));
    }

    public LocalTime minusMinutes(long minutesToSubtract) {
        return plusMinutes(-(minutesToSubtract % 1440));
    }

    public LocalTime minusSeconds(long secondsToSubtract) {
        return plusSeconds(-(secondsToSubtract % Iso.DAY));
    }

    public LocalTime minusNanos(long nanosToSubtract) {
        return plusNanos(-(nanosToSubtract % NANOS_OF_DAY));
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.NANO_OF_DAY, toNanoOfDay());
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        LocalTime end = from(endExclusive);
        if (unit instanceof ChronoUnit chrono) {
            long nanos = end.toNanoOfDay() - toNanoOfDay();
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

    public OffsetTime atOffset(ZoneOffset offset) {
        return OffsetTime.of(this, offset);
    }

    public LocalDateTime atDate(LocalDate date) {
        return LocalDateTime.of(date, this);
    }

    public int toSecondOfDay() {
        return hour * 3600 + minute * 60 + second;
    }

    public long toNanoOfDay() {
        return toSecondOfDay() * Iso.SECOND + nano;
    }

    public long toEpochSecond(LocalDate date, ZoneOffset offset) {
        return date.toEpochDay() * Iso.DAY + toSecondOfDay() - offset.getTotalSeconds();
    }

    public int compareTo(LocalTime other) {
        return Long.compare(toNanoOfDay(), other.toNanoOfDay());
    }

    public boolean isAfter(LocalTime other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(LocalTime other) {
        return compareTo(other) < 0;
    }

    public boolean equals(Object other) {
        return other instanceof LocalTime that && that.hour == hour && that.minute == minute
                && that.second == second && that.nano == nano;
    }

    public int hashCode() {
        return Long.hashCode(toNanoOfDay());
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

    public String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    public String toString() {
        return Iso.time(new StringBuilder(), hour, minute, second, nano, false).toString();
    }
}
