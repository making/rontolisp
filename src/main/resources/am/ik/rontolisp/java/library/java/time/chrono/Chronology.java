// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A calendar: how its dates are made and counted. The library has the ISO calendar
// alone (IsoChronology); a name of another is unknown. A program may write its own
// (AbstractChronology): the members a JDK gives one are here, from the documentation and
// a JDK 25's answers -- the order epochSecond refuses in; the date-time a value's date
// and time make, and the zoned date-time of its zone and its instant or, with none, its
// date-time, each wrapping a DateTimeException in its own (ChronoLocalDateTimeImpl,
// ChronoZonedDateTimeImpl); the period of the calendar (ChronoPeriodImpl).
package java.time.chrono;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
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

    /**
     * The calendar a locale names by its Unicode keyword ca: ISO's where it names none or
     * iso8601; the calendars a JDK has are refused by name, any other name is unknown.
     */
    static Chronology ofLocale(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        if (rontolisp.internal.Locales.foreign()) {
            String type = locale.getUnicodeLocaleType("ca");
            if (type != null && !type.equals("iso8601")) {
                if (type.equals("japanese") || type.equals("buddhist") || type.equals("roc")
                        || type.equals("islamic-umalqura")) {
                    throw rontolisp.internal.Locales.refused("the calendar " + type, locale);
                }
                throw new DateTimeException("Unknown calendar system: " + type);
            }
        }
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

    /** The date-time of a value's date in this calendar and its time. */
    default ChronoLocalDateTime<? extends ChronoLocalDate> localDateTime(TemporalAccessor temporal) {
        try {
            return date(temporal).atTime(LocalTime.from(temporal));
        }
        catch (DateTimeException failed) {
            throw new DateTimeException("Unable to obtain ChronoLocalDateTime from TemporalAccessor: class "
                    + temporal.getClass().getName(), failed);
        }
    }

    /**
     * The zoned date-time of a value: its zone (or its offset), then its instant there or,
     * where it has none, its date-time in this calendar placed in the zone.
     */
    default ChronoZonedDateTime<? extends ChronoLocalDate> zonedDateTime(TemporalAccessor temporal) {
        try {
            ZoneId zone = ZoneId.from(temporal);
            try {
                return zonedDateTime(Instant.from(temporal), zone);
            }
            catch (DateTimeException noInstant) {
                return ChronoZonedDateTimeImpl.ofBest(ChronoLocalDateTimeImpl.cast(localDateTime(temporal)), zone, null);
            }
        }
        catch (DateTimeException failed) {
            throw new DateTimeException("Unable to obtain ChronoZonedDateTime from TemporalAccessor: class "
                    + temporal.getClass().getName(), failed);
        }
    }

    default ChronoZonedDateTime<? extends ChronoLocalDate> zonedDateTime(Instant instant, ZoneId zone) {
        return ChronoZonedDateTimeImpl.ofInstant(this, instant, zone);
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
        if (rontolisp.internal.Locales.foreign()) {
            rontolisp.internal.Locales.words("the name of a calendar", locale);
        }
        return getId();
    }

    default ChronoPeriod period(int years, int months, int days) {
        return new ChronoPeriodImpl(this, years, months, days);
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
