// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A calendar: how its dates are made and counted. The library has the ISO calendar
// alone (IsoChronology); a name of another is unknown. A program may write its own
// (AbstractChronology): the members a JDK gives one are here, from the documentation and
// a JDK 25's answers (the order epochSecond refuses in), but the date-times, the zoned
// date-times and the periods of a calendar other than ISO's.
package java.time.chrono;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.ResolverStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.ValueRange;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface Chronology extends Comparable<Chronology> {

    /** The calendar a value is of; a value that names none is of the ISO calendar. */
    static Chronology from(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        Chronology chronology = temporal.query(TemporalQueries.chronology());
        return chronology != null ? chronology : IsoChronology.INSTANCE;
    }

    static Chronology ofLocale(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return IsoChronology.INSTANCE;
    }

    /** A calendar by its id or its calendar type. */
    static Chronology of(String id) {
        Objects.requireNonNull(id, "id");
        if (id.equals("ISO") || id.equals("iso8601")) {
            return IsoChronology.INSTANCE;
        }
        throw new DateTimeException("Unknown chronology: " + id);
    }

    static Set<Chronology> getAvailableChronologies() {
        return Set.of(IsoChronology.INSTANCE);
    }

    String getId();

    String getCalendarType();

    default ChronoLocalDate date(Era era, int yearOfEra, int month, int dayOfMonth) {
        return date(prolepticYear(era, yearOfEra), month, dayOfMonth);
    }

    ChronoLocalDate date(int prolepticYear, int month, int dayOfMonth);

    default ChronoLocalDate dateYearDay(Era era, int yearOfEra, int dayOfYear) {
        return dateYearDay(prolepticYear(era, yearOfEra), dayOfYear);
    }

    ChronoLocalDate dateYearDay(int prolepticYear, int dayOfYear);

    ChronoLocalDate dateEpochDay(long epochDay);

    default ChronoLocalDate dateNow() {
        return dateNow(Clock.systemDefaultZone());
    }

    default ChronoLocalDate dateNow(ZoneId zone) {
        return dateNow(Clock.system(zone));
    }

    default ChronoLocalDate dateNow(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return date(java.time.LocalDate.now(clock));
    }

    ChronoLocalDate date(TemporalAccessor temporal);

    default ChronoLocalDateTime<? extends ChronoLocalDate> localDateTime(TemporalAccessor temporal) {
        throw AbstractChronology.otherCalendar("localDateTime");
    }

    default ChronoZonedDateTime<? extends ChronoLocalDate> zonedDateTime(TemporalAccessor temporal) {
        throw AbstractChronology.otherCalendar("zonedDateTime");
    }

    default ChronoZonedDateTime<? extends ChronoLocalDate> zonedDateTime(Instant instant, ZoneId zone) {
        throw AbstractChronology.otherCalendar("zonedDateTime");
    }

    boolean isLeapYear(long prolepticYear);

    int prolepticYear(Era era, int yearOfEra);

    Era eraOf(int eraValue);

    List<Era> eras();

    ValueRange range(ChronoField field);

    /** The date the fields of a map make, or null: what is used is taken out of the map. */
    ChronoLocalDate resolveDate(Map<TemporalField, Long> fieldValues, ResolverStyle resolverStyle);

    /** The calendar's name: its id in each locale the library has. */
    default String getDisplayName(TextStyle style, Locale locale) {
        Objects.requireNonNull(style, "textStyle");
        Objects.requireNonNull(locale, "locale");
        return getId();
    }

    default ChronoPeriod period(int years, int months, int days) {
        throw AbstractChronology.otherCalendar("period");
    }

    /** The second of the epoch of a local date-time at an offset: the offset, the time, then the date checked. */
    default long epochSecond(int prolepticYear, int month, int dayOfMonth, int hour, int minute, int second,
            ZoneOffset zoneOffset) {
        Objects.requireNonNull(zoneOffset, "zoneOffset");
        ChronoField.HOUR_OF_DAY.checkValidValue(hour);
        ChronoField.MINUTE_OF_HOUR.checkValidValue(minute);
        ChronoField.SECOND_OF_MINUTE.checkValidValue(second);
        long days = date(prolepticYear, month, dayOfMonth).toEpochDay();
        return Math.addExact(Math.multiplyExact(days, 86400L), hour * 3600L + minute * 60L + second)
                - zoneOffset.getTotalSeconds();
    }

    default long epochSecond(Era era, int yearOfEra, int month, int dayOfMonth, int hour, int minute, int second,
            ZoneOffset zoneOffset) {
        Objects.requireNonNull(era, "era");
        return epochSecond(prolepticYear(era, yearOfEra), month, dayOfMonth, hour, minute, second, zoneOffset);
    }

    default boolean isIsoBased() {
        return false;
    }

    int compareTo(Chronology other);
}
