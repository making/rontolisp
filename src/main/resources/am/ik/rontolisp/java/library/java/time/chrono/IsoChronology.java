// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The proleptic ISO calendar, whose dates are java.time.LocalDate. There is one, so it
// is equal to itself alone and hashes as an object does: a JDK's hash code is made of
// the identity of a class too. Its dates are made of a map's fields as every calendar's
// are, but for an era of another value and, leniently, a proleptic month out of range
// (AbstractChronology.resolve).
package java.time.chrono;

import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.time.temporal.ValueRange;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class IsoChronology extends AbstractChronology implements Serializable {

    public static final IsoChronology INSTANCE = new IsoChronology();

    private IsoChronology() {
    }

    public String getId() {
        return "ISO";
    }

    public String getCalendarType() {
        return "iso8601";
    }

    public LocalDate date(Era era, int yearOfEra, int month, int dayOfMonth) {
        return date(prolepticYear(era, yearOfEra), month, dayOfMonth);
    }

    public LocalDate date(int prolepticYear, int month, int dayOfMonth) {
        return LocalDate.of(prolepticYear, month, dayOfMonth);
    }

    public LocalDate dateYearDay(Era era, int yearOfEra, int dayOfYear) {
        return dateYearDay(prolepticYear(era, yearOfEra), dayOfYear);
    }

    public LocalDate dateYearDay(int prolepticYear, int dayOfYear) {
        return LocalDate.ofYearDay(prolepticYear, dayOfYear);
    }

    public LocalDate dateEpochDay(long epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }

    public LocalDate dateNow() {
        return LocalDate.now();
    }

    public LocalDate dateNow(ZoneId zone) {
        return LocalDate.now(zone);
    }

    public LocalDate dateNow(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return LocalDate.now(clock);
    }

    public LocalDate date(TemporalAccessor temporal) {
        return LocalDate.from(temporal);
    }

    public LocalDateTime localDateTime(TemporalAccessor temporal) {
        return LocalDateTime.from(temporal);
    }

    public ZonedDateTime zonedDateTime(TemporalAccessor temporal) {
        return ZonedDateTime.from(temporal);
    }

    public ZonedDateTime zonedDateTime(Instant instant, ZoneId zone) {
        return ZonedDateTime.ofInstant(instant, zone);
    }

    public boolean isLeapYear(long prolepticYear) {
        return Year.isLeap(prolepticYear);
    }

    public int prolepticYear(Era era, int yearOfEra) {
        if (!(era instanceof IsoEra)) {
            throw new ClassCastException("Era must be IsoEra");
        }
        return era == IsoEra.CE ? yearOfEra : 1 - yearOfEra;
    }

    public IsoEra eraOf(int eraValue) {
        return IsoEra.of(eraValue);
    }

    public List<Era> eras() {
        return List.of(IsoEra.BCE, IsoEra.CE);
    }

    public ValueRange range(ChronoField field) {
        if (field == null) {
            throw new NullPointerException(
                    "Cannot invoke \"java.time.temporal.ChronoField.range()\" because \"field\" is null");
        }
        return field.range();
    }

    public Period period(int years, int months, int days) {
        return Period.of(years, months, days);
    }

    public long epochSecond(int prolepticYear, int month, int dayOfMonth, int hour, int minute, int second,
            ZoneOffset zoneOffset) {
        // the order a JDK refuses them in: the fields one by one, then the date
        Objects.requireNonNull(zoneOffset, "zoneOffset");
        ChronoField.YEAR.checkValidValue(prolepticYear);
        ChronoField.MONTH_OF_YEAR.checkValidValue(month);
        ChronoField.DAY_OF_MONTH.checkValidValue(dayOfMonth);
        ChronoField.HOUR_OF_DAY.checkValidValue(hour);
        ChronoField.MINUTE_OF_HOUR.checkValidValue(minute);
        ChronoField.SECOND_OF_MINUTE.checkValidValue(second);
        return LocalDate.of(prolepticYear, month, dayOfMonth).toEpochDay() * 86400L + hour * 3600 + minute * 60 + second
                - zoneOffset.getTotalSeconds();
    }


    public boolean isIsoBased() {
        return true;
    }

    public LocalDate resolveDate(Map<TemporalField, Long> fieldValues, ResolverStyle resolverStyle) {
        return (LocalDate) resolve(this, fieldValues, resolverStyle, true);
    }

    public boolean equals(Object obj) {
        return obj == this;
    }

    public int hashCode() {
        return System.identityHashCode(this);
    }
}
