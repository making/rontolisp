// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date and a time in a zone, with the offset the zone has there. In a program that
// holds no region of the tz database every zone keeps one offset (ZoneId), a local
// date-time is in a zone at exactly one instant, and there is no gap and no overlap to
// settle; what a region needs is written under ZoneId.regional() and is in no other
// program. With a region: a local date-time in a gap is moved later by the gap's
// length, one in an overlap takes the offset it is given where that is one of the two
// and the earlier otherwise; a change by a date-based field or unit moves the local
// date-time and keeps the offset where it can, one by a time-based unit or a Duration
// moves the instant. What the text's zone in brackets may be -- an offset with its
// minutes, UTC, GMT or UT with one, a name -- is a JDK's answer; a zone it does not take
// leaves the bracket unread.
package java.time;

import java.io.Serializable;
import java.time.chrono.ChronoZonedDateTime;
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

public final class ZonedDateTime implements Temporal, ChronoZonedDateTime<LocalDate>, Serializable {

    private final LocalDateTime dateTime;

    private final ZoneOffset offset;

    private final ZoneId zone;

    private ZonedDateTime(LocalDateTime dateTime, ZoneOffset offset, ZoneId zone) {
        this.dateTime = dateTime;
        this.offset = offset;
        this.zone = zone;
    }

    public static ZonedDateTime now() {
        return now(Clock.systemDefaultZone());
    }

    public static ZonedDateTime now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static ZonedDateTime now(Clock clock) {
        return ofInstant(clock.instant(), clock.getZone());
    }

    public static ZonedDateTime of(LocalDate date, LocalTime time, ZoneId zone) {
        return of(LocalDateTime.of(date, time), zone);
    }

    public static ZonedDateTime of(LocalDateTime localDateTime, ZoneId zone) {
        return ofLocal(localDateTime, zone, null);
    }

    public static ZonedDateTime of(int year, int month, int dayOfMonth, int hour, int minute, int second,
            int nanoOfSecond, ZoneId zone) {
        return of(LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, nanoOfSecond), zone);
    }

    public static ZonedDateTime ofLocal(LocalDateTime localDateTime, ZoneId zone, ZoneOffset preferredOffset) {
        if (localDateTime == null) {
            throw new NullPointerException("localDateTime");
        }
        if (zone == null) {
            throw new NullPointerException("zone");
        }
        if (ZoneId.regional()) {
            return settled(localDateTime, zone, preferredOffset);
        }
        return new ZonedDateTime(localDateTime, zone.fixed(), zone);
    }

    /** A local date-time in a zone whose offset may change. */
    private static ZonedDateTime settled(LocalDateTime local, ZoneId zone, ZoneOffset preferred) {
        long[] change = new long[3];
        int placed = zone.placed(local.toEpochSecond(ZoneOffset.UTC), change);
        if (placed == 1) {
            // a time the clock skipped: as much later as it skipped
            return new ZonedDateTime(local.plusSeconds(change[2] - change[1]),
                    ZoneOffset.ofTotalSeconds((int) change[2]), zone);
        }
        // a time the clock showed twice: at the offset asked for where it is the later
        return new ZonedDateTime(local,
                placed == 2 && (preferred == null || preferred.getTotalSeconds() != change[2])
                        ? ZoneOffset.ofTotalSeconds((int) change[1])
                        : preferred != null && preferred.getTotalSeconds() == change[2] ? preferred
                                : ZoneOffset.ofTotalSeconds((int) change[2]),
                zone);
    }

    /** Whether a zone has an offset at a local date-time. */
    private static boolean valid(LocalDateTime local, ZoneOffset offset, ZoneId zone) {
        long[] change = new long[3];
        int placed = zone.placed(local.toEpochSecond(ZoneOffset.UTC), change);
        return placed != 1
                && (offset.getTotalSeconds() == change[2] || (placed == 2 && offset.getTotalSeconds() == change[1]));
    }

    public static ZonedDateTime ofInstant(Instant instant, ZoneId zone) {
        return create(instant.getEpochSecond(), instant.getNano(), zone);
    }

    public static ZonedDateTime ofInstant(LocalDateTime localDateTime, ZoneOffset offset, ZoneId zone) {
        if (ZoneId.regional()) {
            return valid(localDateTime, offset, zone) ? new ZonedDateTime(localDateTime, offset, zone)
                    : create(localDateTime.toEpochSecond(offset), localDateTime.getNano(), zone);
        }
        return zone.fixed().equals(offset) ? new ZonedDateTime(localDateTime, offset, zone)
                : create(localDateTime.toEpochSecond(offset), localDateTime.getNano(), zone);
    }

    public static ZonedDateTime ofStrict(LocalDateTime localDateTime, ZoneOffset offset, ZoneId zone) {
        if (ZoneId.regional()) {
            if (zone.placed(localDateTime.toEpochSecond(ZoneOffset.UTC), new long[3]) == 1) {
                throw new DateTimeException("LocalDateTime '" + localDateTime + "' does not exist in zone '" + zone
                        + "' due to a gap in the local time-line, typically caused by daylight savings");
            }
            if (valid(localDateTime, offset, zone)) {
                return new ZonedDateTime(localDateTime, offset, zone);
            }
            throw new DateTimeException("ZoneOffset '" + offset + "' is not valid for LocalDateTime '"
                    + localDateTime + "' in zone '" + zone + "'");
        }
        if (!zone.fixed().equals(offset)) {
            throw new DateTimeException("ZoneOffset '" + offset + "' is not valid for LocalDateTime '"
                    + localDateTime + "' in zone '" + zone + "'");
        }
        return new ZonedDateTime(localDateTime, offset, zone);
    }

    private static ZonedDateTime create(long epochSecond, int nanoOfSecond, ZoneId zone) {
        if (ZoneId.regional()) {
            return moment(epochSecond, nanoOfSecond, zone);
        }
        ZoneOffset offset = zone.fixed();
        return new ZonedDateTime(LocalDateTime.ofEpochSecond(epochSecond, nanoOfSecond, offset), offset, zone);
    }

    /** An instant in a zone whose offset may change. */
    private static ZonedDateTime moment(long epochSecond, int nanoOfSecond, ZoneId zone) {
        ZoneOffset offset = zone.offsetAt(epochSecond);
        return new ZonedDateTime(LocalDateTime.ofEpochSecond(epochSecond, nanoOfSecond, offset), offset, zone);
    }

    public static ZonedDateTime from(TemporalAccessor temporal) {
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime;
        }
        if (temporal instanceof OffsetDateTime dateTime) {
            return dateTime.toZonedDateTime();
        }
        throw new DateTimeException("Unable to obtain ZonedDateTime from TemporalAccessor: " + temporal
                + " of type " + temporal.getClass().getName());
    }

    /**
     * The zone a text names between brackets, the first of which is at its place; null,
     * and nothing read, where it names none.
     */
    private static ZoneId bracketed(IsoText in) {
        int close = in.text.indexOf(']', in.at);
        if (close < 0) {
            return null;
        }
        String id = in.text.substring(in.at + 1, close);
        ZoneId zone;
        int prefix = ZoneId.prefixed(id);
        if (id.equals("Z")) {
            zone = ZoneOffset.UTC;
        }
        else if (prefix > 0 || id.startsWith("+") || id.startsWith("-")) {
            // an offset here has its minutes and its colons, and is within 18 hours
            int saved = in.offset;
            int end = in.signed(in.at + 1 + prefix, false);
            int seconds = in.offset;
            in.offset = saved;
            if (end != close || seconds < -64800 || seconds > 64800) {
                return null;
            }
            zone = ZoneId.ofOffset(id.substring(0, prefix), ZoneOffset.ofTotalSeconds(seconds));
        }
        else {
            zone = ZoneId.region(id);
        }
        if (zone != null) {
            in.at = close + 1;
        }
        return zone;
    }

    public static ZonedDateTime parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.date();
        in.mark();
        in.time();
        in.offset(true);
        ZoneId zone = in.next('[') ? bracketed(in) : null;
        in.end();
        LocalDateTime local = in.localDateTime();
        ZoneOffset offset = in.zoneOffset();
        if (zone == null) {
            return new ZonedDateTime(local, offset, offset);
        }
        try {
            return ofInstant(local, offset, zone);
        }
        catch (DateTimeException beyond) {
            throw in.refused(new DateTimeException("Unable to obtain ZonedDateTime from TemporalAccessor: "
                    + "{InstantSeconds=" + local.toEpochSecond(offset) + ", OffsetSeconds=" + offset.getTotalSeconds()
                    + "},ISO," + zone + ",-1 resolved to " + local + " of type java.time.format.Parsed"));
        }
    }

    /** The local date-time moved to, in this zone. */
    private ZonedDateTime local(LocalDateTime newDateTime) {
        if (ZoneId.regional()) {
            return settled(newDateTime, zone, offset);
        }
        return newDateTime == dateTime ? this : new ZonedDateTime(newDateTime, offset, zone);
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

    public ZoneId getZone() {
        return zone;
    }

    public ZonedDateTime withEarlierOffsetAtOverlap() {
        if (ZoneId.regional()) {
            return overlapped(true);
        }
        return this;
    }

    public ZonedDateTime withLaterOffsetAtOverlap() {
        if (ZoneId.regional()) {
            return overlapped(false);
        }
        return this;
    }

    /** This date-time at the earlier or the later of the two offsets an overlap has. */
    private ZonedDateTime overlapped(boolean earlier) {
        long[] change = new long[3];
        if (zone.placed(dateTime.toEpochSecond(ZoneOffset.UTC), change) != 2) {
            return this;
        }
        int to = (int) change[earlier ? 1 : 2];
        return to == offset.getTotalSeconds() ? this : new ZonedDateTime(dateTime, ZoneOffset.ofTotalSeconds(to), zone);
    }

    /** This date-time at another offset, where its zone has that one there too. */
    private ZonedDateTime offered(ZoneOffset to) {
        return !to.equals(offset) && valid(dateTime, to, zone) ? new ZonedDateTime(dateTime, to, zone) : this;
    }

    /** A local date-time reached along the instants from this one, where it is in the zone. */
    private ZonedDateTime moved(LocalDateTime newDateTime) {
        return moment(newDateTime.toEpochSecond(offset), newDateTime.getNano(), zone);
    }

    public ZonedDateTime withZoneSameLocal(ZoneId zone) {
        return this.zone.equals(zone) ? this : ofLocal(dateTime, zone, offset);
    }

    public ZonedDateTime withZoneSameInstant(ZoneId zone) {
        return this.zone.equals(zone) ? this : create(dateTime.toEpochSecond(offset), dateTime.getNano(), zone);
    }

    public ZonedDateTime withFixedOffsetZone() {
        return zone.equals(offset) ? this : new ZonedDateTime(dateTime, offset, offset);
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

    public ZonedDateTime with(TemporalAdjuster adjuster) {
        if (ZoneId.regional()) {
            if (adjuster instanceof ZoneOffset to) {
                return offered(to);
            }
        }
        if (adjuster instanceof LocalDate || adjuster instanceof LocalTime || adjuster instanceof LocalDateTime) {
            return local(dateTime.with(adjuster));
        }
        if (adjuster instanceof OffsetDateTime other) {
            return ofLocal(other.toLocalDateTime(), zone, other.getOffset());
        }
        if (adjuster instanceof Instant instant) {
            return create(instant.getEpochSecond(), instant.getNano(), zone);
        }
        if (adjuster instanceof ZoneOffset) {
            // an offset the zone does not have there changes nothing
            return this;
        }
        return (ZonedDateTime) adjuster.adjustInto(this);
    }

    public ZonedDateTime with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            if (field == ChronoField.INSTANT_SECONDS) {
                return ofInstant(Instant.ofEpochSecond(newValue, getNano()), zone);
            }
            if (field == ChronoField.OFFSET_SECONDS) {
                if (ZoneId.regional()) {
                    return offered(ZoneOffset.ofTotalSeconds(chrono.checkValidIntValue(newValue)));
                }
                ZoneOffset.ofTotalSeconds(chrono.checkValidIntValue(newValue));
                return this;
            }
            return local(dateTime.with(field, newValue));
        }
        return field.adjustInto(this, newValue);
    }

    public ZonedDateTime withYear(int year) {
        return local(dateTime.withYear(year));
    }

    public ZonedDateTime withMonth(int month) {
        return local(dateTime.withMonth(month));
    }

    public ZonedDateTime withDayOfMonth(int dayOfMonth) {
        return local(dateTime.withDayOfMonth(dayOfMonth));
    }

    public ZonedDateTime withDayOfYear(int dayOfYear) {
        return local(dateTime.withDayOfYear(dayOfYear));
    }

    public ZonedDateTime withHour(int hour) {
        return local(dateTime.withHour(hour));
    }

    public ZonedDateTime withMinute(int minute) {
        return local(dateTime.withMinute(minute));
    }

    public ZonedDateTime withSecond(int second) {
        return local(dateTime.withSecond(second));
    }

    public ZonedDateTime withNano(int nanoOfSecond) {
        return local(dateTime.withNano(nanoOfSecond));
    }

    public ZonedDateTime truncatedTo(TemporalUnit unit) {
        return local(dateTime.truncatedTo(unit));
    }

    public ZonedDateTime plus(TemporalAmount amountToAdd) {
        if (ZoneId.regional()) {
            if (amountToAdd instanceof Duration) {
                return moved(dateTime.plus(amountToAdd));
            }
        }
        if (amountToAdd instanceof Period || amountToAdd instanceof Duration) {
            return local(dateTime.plus(amountToAdd));
        }
        return (ZonedDateTime) amountToAdd.addTo(this);
    }

    public ZonedDateTime plus(long amountToAdd, TemporalUnit unit) {
        if (ZoneId.regional()) {
            if (unit instanceof ChronoUnit && unit.isTimeBased()) {
                return moved(dateTime.plus(amountToAdd, unit));
            }
        }
        return unit instanceof ChronoUnit ? local(dateTime.plus(amountToAdd, unit)) : unit.addTo(this, amountToAdd);
    }

    public ZonedDateTime plusYears(long years) {
        return local(dateTime.plusYears(years));
    }

    public ZonedDateTime plusMonths(long months) {
        return local(dateTime.plusMonths(months));
    }

    public ZonedDateTime plusWeeks(long weeks) {
        return local(dateTime.plusWeeks(weeks));
    }

    public ZonedDateTime plusDays(long days) {
        return local(dateTime.plusDays(days));
    }

    public ZonedDateTime plusHours(long hours) {
        return ZoneId.regional() ? moved(dateTime.plusHours(hours)) : local(dateTime.plusHours(hours));
    }

    public ZonedDateTime plusMinutes(long minutes) {
        return ZoneId.regional() ? moved(dateTime.plusMinutes(minutes)) : local(dateTime.plusMinutes(minutes));
    }

    public ZonedDateTime plusSeconds(long seconds) {
        return ZoneId.regional() ? moved(dateTime.plusSeconds(seconds)) : local(dateTime.plusSeconds(seconds));
    }

    public ZonedDateTime plusNanos(long nanos) {
        return ZoneId.regional() ? moved(dateTime.plusNanos(nanos)) : local(dateTime.plusNanos(nanos));
    }

    public ZonedDateTime minus(TemporalAmount amountToSubtract) {
        if (ZoneId.regional()) {
            if (amountToSubtract instanceof Duration) {
                return moved(dateTime.minus(amountToSubtract));
            }
        }
        if (amountToSubtract instanceof Period || amountToSubtract instanceof Duration) {
            return local(dateTime.minus(amountToSubtract));
        }
        return (ZonedDateTime) amountToSubtract.subtractFrom(this);
    }

    public ZonedDateTime minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public ZonedDateTime minusYears(long years) {
        return local(dateTime.minusYears(years));
    }

    public ZonedDateTime minusMonths(long months) {
        return local(dateTime.minusMonths(months));
    }

    public ZonedDateTime minusWeeks(long weeks) {
        return local(dateTime.minusWeeks(weeks));
    }

    public ZonedDateTime minusDays(long days) {
        return local(dateTime.minusDays(days));
    }

    public ZonedDateTime minusHours(long hours) {
        return ZoneId.regional() ? moved(dateTime.minusHours(hours)) : local(dateTime.minusHours(hours));
    }

    public ZonedDateTime minusMinutes(long minutes) {
        return ZoneId.regional() ? moved(dateTime.minusMinutes(minutes)) : local(dateTime.minusMinutes(minutes));
    }

    public ZonedDateTime minusSeconds(long seconds) {
        return ZoneId.regional() ? moved(dateTime.minusSeconds(seconds)) : local(dateTime.minusSeconds(seconds));
    }

    public ZonedDateTime minusNanos(long nanos) {
        return ZoneId.regional() ? moved(dateTime.minusNanos(nanos)) : local(dateTime.minusNanos(nanos));
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        ZonedDateTime end = from(endExclusive);
        if (ZoneId.regional()) {
            if (unit instanceof ChronoUnit && unit.isTimeBased()) {
                return toOffsetDateTime().until(end.toOffsetDateTime(), unit);
            }
        }
        return unit instanceof ChronoUnit ? dateTime.until(end.withZoneSameInstant(zone).dateTime, unit)
                : unit.between(this, end);
    }

    public OffsetDateTime toOffsetDateTime() {
        return OffsetDateTime.of(dateTime, offset);
    }

    public Instant toInstant() {
        return dateTime.toInstant(offset);
    }

    public long toEpochSecond() {
        return dateTime.toEpochSecond(offset);
    }

    /** By the instant, then the local date-time, then the zone's name. */
    public int compareTo(ChronoZonedDateTime<?> other) {
        int order = Long.compare(toEpochSecond(), other.toEpochSecond());
        if (order == 0) {
            order = getNano() - other.toLocalTime().getNano();
        }
        if (order == 0 && other instanceof ZonedDateTime that) {
            order = dateTime.compareTo(that.dateTime);
        }
        return order != 0 ? order : zone.getId().compareTo(other.getZone().getId());
    }

    public boolean isAfter(ChronoZonedDateTime<?> other) {
        long mine = toEpochSecond();
        long theirs = other.toEpochSecond();
        return mine > theirs || (mine == theirs && getNano() > other.toLocalTime().getNano());
    }

    public boolean isBefore(ChronoZonedDateTime<?> other) {
        long mine = toEpochSecond();
        long theirs = other.toEpochSecond();
        return mine < theirs || (mine == theirs && getNano() < other.toLocalTime().getNano());
    }

    public boolean isEqual(ChronoZonedDateTime<?> other) {
        return toEpochSecond() == other.toEpochSecond() && getNano() == other.toLocalTime().getNano();
    }

    public boolean equals(Object other) {
        return other instanceof ZonedDateTime that && that.dateTime.equals(dateTime) && that.offset.equals(offset)
                && that.zone.equals(zone);
    }

    public int hashCode() {
        return dateTime.hashCode() ^ offset.hashCode() ^ Integer.rotateLeft(zone.hashCode(), 3);
    }

    public String toString() {
        String text = dateTime.toString() + offset.toString();
        return zone instanceof ZoneOffset ? text : text + '[' + zone.toString() + ']';
    }
}
