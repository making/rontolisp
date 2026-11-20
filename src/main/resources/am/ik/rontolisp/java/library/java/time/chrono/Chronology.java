// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A calendar: how its dates are made and counted. The library has the ISO calendar
// alone (IsoChronology); a name of another is unknown.
package java.time.chrono;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.time.temporal.ValueRange;
import java.util.List;
import java.util.Locale;
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

    ChronoLocalDate date(Era era, int yearOfEra, int month, int dayOfMonth);

    ChronoLocalDate date(int prolepticYear, int month, int dayOfMonth);

    ChronoLocalDate dateYearDay(Era era, int yearOfEra, int dayOfYear);

    ChronoLocalDate dateYearDay(int prolepticYear, int dayOfYear);

    ChronoLocalDate dateEpochDay(long epochDay);

    ChronoLocalDate dateNow();

    ChronoLocalDate dateNow(ZoneId zone);

    ChronoLocalDate dateNow(Clock clock);

    ChronoLocalDate date(TemporalAccessor temporal);

    ChronoLocalDateTime<? extends ChronoLocalDate> localDateTime(TemporalAccessor temporal);

    ChronoZonedDateTime<? extends ChronoLocalDate> zonedDateTime(TemporalAccessor temporal);

    ChronoZonedDateTime<? extends ChronoLocalDate> zonedDateTime(Instant instant, ZoneId zone);

    boolean isLeapYear(long prolepticYear);

    int prolepticYear(Era era, int yearOfEra);

    Era eraOf(int eraValue);

    List<Era> eras();

    ValueRange range(ChronoField field);

    /** The calendar's name: its id in each locale the library has. */
    default String getDisplayName(TextStyle style, Locale locale) {
        return getId();
    }

    ChronoPeriod period(int years, int months, int days);

    long epochSecond(int prolepticYear, int month, int dayOfMonth, int hour, int minute, int second,
            ZoneOffset zoneOffset);

    long epochSecond(Era era, int yearOfEra, int month, int dayOfMonth, int hour, int minute, int second,
            ZoneOffset zoneOffset);

    default boolean isIsoBased() {
        return false;
    }

    int compareTo(Chronology other);
}
