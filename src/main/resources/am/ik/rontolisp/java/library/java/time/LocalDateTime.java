// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date and a time of day, with no zone: a LocalDate and a LocalTime. Time added past
// midnight moves the date.
package java.time;

import java.io.Serializable;
import java.time.chrono.ChronoLocalDateTime;
import java.time.chrono.IsoChronology;
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
import java.time.temporal.ValueRange;
import java.util.Objects;

public final class LocalDateTime
        implements Temporal, TemporalAdjuster, ChronoLocalDateTime<LocalDate>, Serializable {

    private static final long NANOS_OF_DAY = Iso.DAY * Iso.SECOND;

    public static final LocalDateTime MIN = new LocalDateTime(LocalDate.MIN, LocalTime.MIN);

    public static final LocalDateTime MAX = new LocalDateTime(LocalDate.MAX, LocalTime.MAX);

    private final LocalDate date;

    private final LocalTime time;

    private LocalDateTime(LocalDate date, LocalTime time) {
        this.date = date;
        this.time = time;
    }

    public static LocalDateTime now() {
        return now(Clock.systemDefaultZone());
    }

    public static LocalDateTime now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static LocalDateTime now(Clock clock) {
        return ofInstant(clock.instant(), clock.getZone());
    }

    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute) {
        return of(year, month.getValue(), dayOfMonth, hour, minute, 0, 0);
    }

    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute, int second) {
        return of(year, month.getValue(), dayOfMonth, hour, minute, second, 0);
    }

    public static LocalDateTime of(int year, Month month, int dayOfMonth, int hour, int minute, int second,
            int nanoOfSecond) {
        return of(year, month.getValue(), dayOfMonth, hour, minute, second, nanoOfSecond);
    }

    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute) {
        return of(year, month, dayOfMonth, hour, minute, 0, 0);
    }

    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second) {
        return of(year, month, dayOfMonth, hour, minute, second, 0);
    }

    public static LocalDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second,
            int nanoOfSecond) {
        LocalDate date = LocalDate.of(year, month, dayOfMonth);
        return new LocalDateTime(date, LocalTime.of(hour, minute, second, nanoOfSecond));
    }

    public static LocalDateTime of(LocalDate date, LocalTime time) {
        if (date == null) {
            throw new NullPointerException("date");
        }
        if (time == null) {
            throw new NullPointerException("time");
        }
        return new LocalDateTime(date, time);
    }

    public static LocalDateTime ofInstant(Instant instant, ZoneId zone) {
        return ofEpochSecond(instant.getEpochSecond(), instant.getNano(),
                ZoneId.regional() ? zone.offsetAt(instant.getEpochSecond()) : zone.fixed());
    }

    public static LocalDateTime ofEpochSecond(long epochSecond, int nanoOfSecond, ZoneOffset offset) {
        Iso.check(nanoOfSecond, 0, 999999999, "NanoOfSecond");
        long local = epochSecond + offset.getTotalSeconds();
        LocalDate date = LocalDate.ofEpochDay(Math.floorDiv(local, Iso.DAY));
        return new LocalDateTime(date,
                LocalTime.ofNanoOfDay(Math.floorMod(local, Iso.DAY) * Iso.SECOND + nanoOfSecond));
    }

    public static LocalDateTime from(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        if (temporal instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime.toLocalDateTime();
        }
        if (temporal instanceof OffsetDateTime dateTime) {
            return dateTime.toLocalDateTime();
        }
        if (Iso.foreign()) {
            try {
                return of(LocalDate.from(temporal), LocalTime.from(temporal));
            }
            catch (DateTimeException refused) {
                throw new DateTimeException("Unable to obtain LocalDateTime from TemporalAccessor: " + temporal
                        + " of type " + temporal.getClass().getName(), refused);
            }
        }
        throw new DateTimeException("Unable to obtain LocalDateTime from TemporalAccessor: " + temporal + " of type "
                + temporal.getClass().getName());
    }

    public static LocalDateTime parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.date();
        in.mark();
        in.time();
        in.end();
        return in.localDateTime();
    }

    public static LocalDateTime parse(CharSequence text, DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.parse(text, LocalDateTime::from);
    }

    private LocalDateTime with(LocalDate newDate, LocalTime newTime) {
        return date == newDate && time == newTime ? this : new LocalDateTime(newDate, newTime);
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field.isDateBased() || field.isTimeBased()
                : field != null && field.isSupportedBy(this);
    }

    public boolean isSupported(TemporalUnit unit) {
        return unit instanceof ChronoUnit ? unit != ChronoUnit.FOREVER : unit != null && unit.isSupportedBy(this);
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
        return range(field).checkValidIntValue(field.getFrom(this), field);
    }

    public long getLong(TemporalField field) {
        if (field instanceof ChronoField) {
            return field.isTimeBased() ? time.getLong(field) : date.getLong(field);
        }
        return field.getFrom(this);
    }

    public LocalDate toLocalDate() {
        return date;
    }

    public LocalTime toLocalTime() {
        return time;
    }

    public int getYear() {
        return date.getYear();
    }

    public int getMonthValue() {
        return date.getMonthValue();
    }

    public Month getMonth() {
        return date.getMonth();
    }

    public int getDayOfMonth() {
        return date.getDayOfMonth();
    }

    public int getDayOfYear() {
        return date.getDayOfYear();
    }

    public DayOfWeek getDayOfWeek() {
        return date.getDayOfWeek();
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

    public LocalDateTime with(TemporalAdjuster adjuster) {
        if (adjuster instanceof LocalDate newDate) {
            return with(newDate, time);
        }
        if (adjuster instanceof LocalTime newTime) {
            return with(date, newTime);
        }
        return adjuster instanceof LocalDateTime dateTime ? dateTime : (LocalDateTime) adjuster.adjustInto(this);
    }

    public LocalDateTime with(TemporalField field, long newValue) {
        if (field instanceof ChronoField) {
            return field.isTimeBased() ? with(date, time.with(field, newValue))
                    : with(date.with(field, newValue), time);
        }
        return field.adjustInto(this, newValue);
    }

    public LocalDateTime withYear(int year) {
        return with(date.withYear(year), time);
    }

    public LocalDateTime withMonth(int month) {
        return with(date.withMonth(month), time);
    }

    public LocalDateTime withDayOfMonth(int dayOfMonth) {
        return with(date.withDayOfMonth(dayOfMonth), time);
    }

    public LocalDateTime withDayOfYear(int dayOfYear) {
        return with(date.withDayOfYear(dayOfYear), time);
    }

    public LocalDateTime withHour(int hour) {
        return with(date, time.withHour(hour));
    }

    public LocalDateTime withMinute(int minute) {
        return with(date, time.withMinute(minute));
    }

    public LocalDateTime withSecond(int second) {
        return with(date, time.withSecond(second));
    }

    public LocalDateTime withNano(int nanoOfSecond) {
        return with(date, time.withNano(nanoOfSecond));
    }

    public LocalDateTime truncatedTo(TemporalUnit unit) {
        return with(date, time.truncatedTo(unit));
    }

    public LocalDateTime plus(TemporalAmount amountToAdd) {
        if (amountToAdd instanceof Period period) {
            return with(date.plus(period), time);
        }
        if (amountToAdd instanceof Duration duration) {
            return plusSeconds(duration.getSeconds()).plusNanos(duration.getNano());
        }
        return (LocalDateTime) amountToAdd.addTo(this);
    }

    public LocalDateTime plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
                case NANOS:
                    return plusNanos(amountToAdd);
                case MICROS:
                    return moved(amountToAdd / (NANOS_OF_DAY / 1000), amountToAdd % (NANOS_OF_DAY / 1000) * 1000);
                case MILLIS:
                    return moved(amountToAdd / (NANOS_OF_DAY / 1000000),
                            amountToAdd % (NANOS_OF_DAY / 1000000) * 1000000);
                case SECONDS:
                    return plusSeconds(amountToAdd);
                case MINUTES:
                    return plusMinutes(amountToAdd);
                case HOURS:
                    return plusHours(amountToAdd);
                case HALF_DAYS:
                    return moved(amountToAdd / 2, amountToAdd % 2 * 43200 * Iso.SECOND);
                default:
                    return with(date.plus(amountToAdd, unit), time);
            }
        }
        return unit.addTo(this, amountToAdd);
    }

    public LocalDateTime plusYears(long years) {
        return with(date.plusYears(years), time);
    }

    public LocalDateTime plusMonths(long months) {
        return with(date.plusMonths(months), time);
    }

    public LocalDateTime plusWeeks(long weeks) {
        return with(date.plusWeeks(weeks), time);
    }

    public LocalDateTime plusDays(long days) {
        return with(date.plusDays(days), time);
    }

    /** Whole days and less than a day of nanoseconds, of one sign, later. */
    private LocalDateTime moved(long days, long nanos) {
        if ((days | nanos) == 0) {
            return this;
        }
        long ofDay = time.toNanoOfDay() + nanos;
        long within = Math.floorMod(ofDay, NANOS_OF_DAY);
        return with(date.plusDays(days + Math.floorDiv(ofDay, NANOS_OF_DAY)),
                within == time.toNanoOfDay() ? time : LocalTime.ofNanoOfDay(within));
    }

    public LocalDateTime plusHours(long hours) {
        return moved(hours / 24, hours % 24 * 3600 * Iso.SECOND);
    }

    public LocalDateTime plusMinutes(long minutes) {
        return moved(minutes / 1440, minutes % 1440 * 60 * Iso.SECOND);
    }

    public LocalDateTime plusSeconds(long seconds) {
        return moved(seconds / Iso.DAY, seconds % Iso.DAY * Iso.SECOND);
    }

    public LocalDateTime plusNanos(long nanos) {
        return moved(nanos / NANOS_OF_DAY, nanos % NANOS_OF_DAY);
    }

    public LocalDateTime minus(TemporalAmount amountToSubtract) {
        if (amountToSubtract instanceof Period period) {
            return with(date.minus(period), time);
        }
        if (amountToSubtract instanceof Duration duration) {
            return minusSeconds(duration.getSeconds()).minusNanos(duration.getNano());
        }
        return (LocalDateTime) amountToSubtract.subtractFrom(this);
    }

    public LocalDateTime minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public LocalDateTime minusYears(long years) {
        return with(date.minusYears(years), time);
    }

    public LocalDateTime minusMonths(long months) {
        return with(date.minusMonths(months), time);
    }

    public LocalDateTime minusWeeks(long weeks) {
        return with(date.minusWeeks(weeks), time);
    }

    public LocalDateTime minusDays(long days) {
        return with(date.minusDays(days), time);
    }

    public LocalDateTime minusHours(long hours) {
        return moved(-(hours / 24), -(hours % 24) * 3600 * Iso.SECOND);
    }

    public LocalDateTime minusMinutes(long minutes) {
        return moved(-(minutes / 1440), -(minutes % 1440) * 60 * Iso.SECOND);
    }

    public LocalDateTime minusSeconds(long seconds) {
        return moved(-(seconds / Iso.DAY), -(seconds % Iso.DAY) * Iso.SECOND);
    }

    public LocalDateTime minusNanos(long nanos) {
        return moved(-(nanos / NANOS_OF_DAY), -(nanos % NANOS_OF_DAY));
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.EPOCH_DAY, date.toEpochDay())
            .with(ChronoField.NANO_OF_DAY, time.toNanoOfDay());
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        LocalDateTime end = from(endExclusive);
        if (!(unit instanceof ChronoUnit chrono)) {
            return unit.between(this, end);
        }
        if (!chrono.isTimeBased()) {
            // a day is whole once the time of day is reached
            LocalDate last = end.date;
            if (last.isAfter(date) && end.time.isBefore(time)) {
                last = last.minusDays(1);
            }
            else if (last.isBefore(date) && end.time.isAfter(time)) {
                last = last.plusDays(1);
            }
            return date.until(last, unit);
        }
        long days = end.date.toEpochDay() - date.toEpochDay();
        if (days == 0) {
            return time.until(end.time, unit);
        }
        // the days but one and the rest in nanoseconds, of one sign
        long nanos = end.time.toNanoOfDay() - time.toNanoOfDay();
        if (days > 0) {
            days--;
            nanos += NANOS_OF_DAY;
        }
        else {
            days++;
            nanos -= NANOS_OF_DAY;
        }
        long step = chrono.getDuration().toNanos();
        return Math.addExact(Math.multiplyExact(days, NANOS_OF_DAY / step), nanos / step);
    }

    public OffsetDateTime atOffset(ZoneOffset offset) {
        return OffsetDateTime.of(this, offset);
    }

    public ZonedDateTime atZone(ZoneId zone) {
        return ZonedDateTime.of(this, zone);
    }

    public long toEpochSecond(ZoneOffset offset) {
        return date.toEpochDay() * Iso.DAY + time.toSecondOfDay() - offset.getTotalSeconds();
    }

    public Instant toInstant(ZoneOffset offset) {
        return Instant.ofEpochSecond(toEpochSecond(offset), time.getNano());
    }

    public int compareTo(ChronoLocalDateTime<?> other) {
        if (other instanceof LocalDateTime dateTime) {
            int order = date.compareTo(dateTime.date);
            return order != 0 ? order : time.compareTo(dateTime.time);
        }
        int order = Long.compare(date.toEpochDay(), other.toLocalDate().toEpochDay());
        return order != 0 ? order : time.compareTo(other.toLocalTime());
    }

    public boolean isAfter(ChronoLocalDateTime<?> other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(ChronoLocalDateTime<?> other) {
        return compareTo(other) < 0;
    }

    public boolean isEqual(ChronoLocalDateTime<?> other) {
        return compareTo(other) == 0;
    }

    public boolean equals(Object other) {
        return other instanceof LocalDateTime that && that.date.equals(date) && that.time.equals(time);
    }

    public int hashCode() {
        return date.hashCode() ^ time.hashCode();
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        if (Iso.calendars()) {
            if (query == TemporalQueries.chronology()) {
                return (R) IsoChronology.INSTANCE;
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
        return date.toString() + 'T' + time.toString();
    }
}
