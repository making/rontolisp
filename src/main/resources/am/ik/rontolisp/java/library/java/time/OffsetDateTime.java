// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date and a time with the offset from UTC they are written at. Two of one instant
// at two offsets are not equal; the order is the instants', then the local date-times'.
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

public final class OffsetDateTime implements Temporal, TemporalAdjuster, Comparable<OffsetDateTime>, Serializable {

    public static final OffsetDateTime MIN = new OffsetDateTime(LocalDateTime.MIN, ZoneOffset.MAX);

    public static final OffsetDateTime MAX = new OffsetDateTime(LocalDateTime.MAX, ZoneOffset.MIN);

    private final LocalDateTime dateTime;

    private final ZoneOffset offset;

    private OffsetDateTime(LocalDateTime dateTime, ZoneOffset offset) {
        this.dateTime = dateTime;
        this.offset = offset;
    }

    public static OffsetDateTime now() {
        return now(Clock.systemDefaultZone());
    }

    public static OffsetDateTime now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static OffsetDateTime now(Clock clock) {
        return ofInstant(clock.instant(), clock.getZone());
    }

    public static OffsetDateTime of(LocalDate date, LocalTime time, ZoneOffset offset) {
        return of(LocalDateTime.of(date, time), offset);
    }

    public static OffsetDateTime of(LocalDateTime dateTime, ZoneOffset offset) {
        if (dateTime == null) {
            throw new NullPointerException("dateTime");
        }
        if (offset == null) {
            throw new NullPointerException("offset");
        }
        return new OffsetDateTime(dateTime, offset);
    }

    public static OffsetDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second,
            int nanoOfSecond, ZoneOffset offset) {
        return of(LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nanoOfSecond), offset);
    }

    public static OffsetDateTime ofInstant(Instant instant, ZoneId zone) {
        ZoneOffset offset = ZoneId.regional() ? zone.offsetAt(instant.getEpochSecond()) : zone.fixed();
        return new OffsetDateTime(LocalDateTime.ofEpochSecond(instant.getEpochSecond(), instant.getNano(), offset),
                offset);
    }

    public static OffsetDateTime from(TemporalAccessor temporal) {
        if (temporal instanceof OffsetDateTime dateTime) {
            return dateTime;
        }
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime.toOffsetDateTime();
        }
        throw new DateTimeException("Unable to obtain OffsetDateTime from TemporalAccessor: " + temporal
                + " of type " + temporal.getClass().getName());
    }

    public static OffsetDateTime parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.date();
        in.mark();
        in.time();
        in.offset(true);
        in.end();
        LocalDateTime local = in.localDateTime();
        return new OffsetDateTime(local, in.zoneOffset());
    }

    private OffsetDateTime with(LocalDateTime newDateTime, ZoneOffset newOffset) {
        return dateTime == newDateTime && offset.equals(newOffset) ? this
                : new OffsetDateTime(newDateTime, newOffset);
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField || (field != null && field.isSupportedBy(this));
    }

    public boolean isSupported(TemporalUnit unit) {
        return unit instanceof ChronoUnit ? unit != ChronoUnit.FOREVER : unit != null && unit.isSupportedBy(this);
    }

    public ValueRange range(TemporalField field) {
        if (field instanceof ChronoField) {
            return field == ChronoField.INSTANT_SECONDS || field == ChronoField.OFFSET_SECONDS ? field.range()
                    : dateTime.range(field);
        }
        return field.rangeRefinedBy(this);
    }

    public int get(TemporalField field) {
        if (field == ChronoField.INSTANT_SECONDS) {
            throw new UnsupportedTemporalTypeException(
                    "Invalid field 'InstantSeconds' for get() method, use getLong() instead");
        }
        if (field == ChronoField.OFFSET_SECONDS) {
            return offset.getTotalSeconds();
        }
        return field instanceof ChronoField ? dateTime.get(field)
                : range(field).checkValidIntValue(field.getFrom(this), field);
    }

    public long getLong(TemporalField field) {
        if (field == ChronoField.INSTANT_SECONDS) {
            return toEpochSecond();
        }
        if (field == ChronoField.OFFSET_SECONDS) {
            return offset.getTotalSeconds();
        }
        return field instanceof ChronoField ? dateTime.getLong(field) : field.getFrom(this);
    }

    public ZoneOffset getOffset() {
        return offset;
    }

    public OffsetDateTime withOffsetSameLocal(ZoneOffset offset) {
        return with(dateTime, offset);
    }

    public OffsetDateTime withOffsetSameInstant(ZoneOffset offset) {
        if (offset.equals(this.offset)) {
            return this;
        }
        return new OffsetDateTime(dateTime.plusSeconds(offset.getTotalSeconds() - this.offset.getTotalSeconds()),
                offset);
    }

    public LocalDateTime toLocalDateTime() {
        return dateTime;
    }

    public LocalDate toLocalDate() {
        return dateTime.toLocalDate();
    }

    public LocalTime toLocalTime() {
        return dateTime.toLocalTime();
    }

    public int getYear() {
        return dateTime.getYear();
    }

    public int getMonthValue() {
        return dateTime.getMonthValue();
    }

    public Month getMonth() {
        return dateTime.getMonth();
    }

    public int getDayOfMonth() {
        return dateTime.getDayOfMonth();
    }

    public int getDayOfYear() {
        return dateTime.getDayOfYear();
    }

    public DayOfWeek getDayOfWeek() {
        return dateTime.getDayOfWeek();
    }

    public int getHour() {
        return dateTime.getHour();
    }

    public int getMinute() {
        return dateTime.getMinute();
    }

    public int getSecond() {
        return dateTime.getSecond();
    }

    public int getNano() {
        return dateTime.getNano();
    }

    public OffsetDateTime with(TemporalAdjuster adjuster) {
        if (adjuster instanceof LocalDate || adjuster instanceof LocalTime || adjuster instanceof LocalDateTime) {
            return with(dateTime.with(adjuster), offset);
        }
        if (adjuster instanceof Instant instant) {
            return ofInstant(instant, offset);
        }
        if (adjuster instanceof ZoneOffset newOffset) {
            return with(dateTime, newOffset);
        }
        return adjuster instanceof OffsetDateTime other ? other : (OffsetDateTime) adjuster.adjustInto(this);
    }

    public OffsetDateTime with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            if (field == ChronoField.INSTANT_SECONDS) {
                return ofInstant(Instant.ofEpochSecond(newValue, getNano()), offset);
            }
            if (field == ChronoField.OFFSET_SECONDS) {
                return with(dateTime, ZoneOffset.ofTotalSeconds(chrono.checkValidIntValue(newValue)));
            }
            return with(dateTime.with(field, newValue), offset);
        }
        return field.adjustInto(this, newValue);
    }

    public OffsetDateTime withYear(int year) {
        return with(dateTime.withYear(year), offset);
    }

    public OffsetDateTime withMonth(int month) {
        return with(dateTime.withMonth(month), offset);
    }

    public OffsetDateTime withDayOfMonth(int dayOfMonth) {
        return with(dateTime.withDayOfMonth(dayOfMonth), offset);
    }

    public OffsetDateTime withDayOfYear(int dayOfYear) {
        return with(dateTime.withDayOfYear(dayOfYear), offset);
    }

    public OffsetDateTime withHour(int hour) {
        return with(dateTime.withHour(hour), offset);
    }

    public OffsetDateTime withMinute(int minute) {
        return with(dateTime.withMinute(minute), offset);
    }

    public OffsetDateTime withSecond(int second) {
        return with(dateTime.withSecond(second), offset);
    }

    public OffsetDateTime withNano(int nanoOfSecond) {
        return with(dateTime.withNano(nanoOfSecond), offset);
    }

    public OffsetDateTime truncatedTo(TemporalUnit unit) {
        return with(dateTime.truncatedTo(unit), offset);
    }

    public OffsetDateTime plus(TemporalAmount amountToAdd) {
        return with(dateTime.plus(amountToAdd), offset);
    }

    public OffsetDateTime plus(long amountToAdd, TemporalUnit unit) {
        return unit instanceof ChronoUnit ? with(dateTime.plus(amountToAdd, unit), offset)
                : unit.addTo(this, amountToAdd);
    }

    public OffsetDateTime plusYears(long years) {
        return with(dateTime.plusYears(years), offset);
    }

    public OffsetDateTime plusMonths(long months) {
        return with(dateTime.plusMonths(months), offset);
    }

    public OffsetDateTime plusWeeks(long weeks) {
        return with(dateTime.plusWeeks(weeks), offset);
    }

    public OffsetDateTime plusDays(long days) {
        return with(dateTime.plusDays(days), offset);
    }

    public OffsetDateTime plusHours(long hours) {
        return with(dateTime.plusHours(hours), offset);
    }

    public OffsetDateTime plusMinutes(long minutes) {
        return with(dateTime.plusMinutes(minutes), offset);
    }

    public OffsetDateTime plusSeconds(long seconds) {
        return with(dateTime.plusSeconds(seconds), offset);
    }

    public OffsetDateTime plusNanos(long nanos) {
        return with(dateTime.plusNanos(nanos), offset);
    }

    public OffsetDateTime minus(TemporalAmount amountToSubtract) {
        return with(dateTime.minus(amountToSubtract), offset);
    }

    public OffsetDateTime minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public OffsetDateTime minusYears(long years) {
        return with(dateTime.minusYears(years), offset);
    }

    public OffsetDateTime minusMonths(long months) {
        return with(dateTime.minusMonths(months), offset);
    }

    public OffsetDateTime minusWeeks(long weeks) {
        return with(dateTime.minusWeeks(weeks), offset);
    }

    public OffsetDateTime minusDays(long days) {
        return with(dateTime.minusDays(days), offset);
    }

    public OffsetDateTime minusHours(long hours) {
        return with(dateTime.minusHours(hours), offset);
    }

    public OffsetDateTime minusMinutes(long minutes) {
        return with(dateTime.minusMinutes(minutes), offset);
    }

    public OffsetDateTime minusSeconds(long seconds) {
        return with(dateTime.minusSeconds(seconds), offset);
    }

    public OffsetDateTime minusNanos(long nanos) {
        return with(dateTime.minusNanos(nanos), offset);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.EPOCH_DAY, toLocalDate().toEpochDay())
            .with(ChronoField.NANO_OF_DAY, toLocalTime().toNanoOfDay())
            .with(ChronoField.OFFSET_SECONDS, offset.getTotalSeconds());
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        OffsetDateTime end = from(endExclusive);
        return unit instanceof ChronoUnit ? dateTime.until(end.withOffsetSameInstant(offset).dateTime, unit)
                : unit.between(this, end);
    }

    public ZonedDateTime atZoneSameInstant(ZoneId zone) {
        return ZonedDateTime.ofInstant(dateTime, offset, zone);
    }

    public ZonedDateTime atZoneSimilarLocal(ZoneId zone) {
        return ZonedDateTime.ofLocal(dateTime, zone, offset);
    }

    public ZonedDateTime toZonedDateTime() {
        return ZonedDateTime.of(dateTime, offset);
    }

    public Instant toInstant() {
        return dateTime.toInstant(offset);
    }

    public long toEpochSecond() {
        return dateTime.toEpochSecond(offset);
    }

    public int compareTo(OffsetDateTime other) {
        if (offset.equals(other.offset)) {
            return dateTime.compareTo(other.dateTime);
        }
        int order = Long.compare(toEpochSecond(), other.toEpochSecond());
        if (order == 0) {
            order = getNano() - other.getNano();
        }
        return order != 0 ? order : dateTime.compareTo(other.dateTime);
    }

    public boolean isAfter(OffsetDateTime other) {
        long mine = toEpochSecond();
        long theirs = other.toEpochSecond();
        return mine > theirs || (mine == theirs && getNano() > other.getNano());
    }

    public boolean isBefore(OffsetDateTime other) {
        long mine = toEpochSecond();
        long theirs = other.toEpochSecond();
        return mine < theirs || (mine == theirs && getNano() < other.getNano());
    }

    public boolean isEqual(OffsetDateTime other) {
        return toEpochSecond() == other.toEpochSecond() && getNano() == other.getNano();
    }

    public boolean equals(Object other) {
        return other instanceof OffsetDateTime that && that.dateTime.equals(dateTime) && that.offset.equals(offset);
    }

    public int hashCode() {
        return dateTime.hashCode() ^ offset.hashCode();
    }

    public String toString() {
        return dateTime.toString() + offset.toString();
    }
}
