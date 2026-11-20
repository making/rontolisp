// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A date of the proleptic ISO calendar: a year, a month and a day, with no time and no
// zone. The arithmetic is over the epoch day (Iso); a month or a year added keeps the
// day where the month has it and takes the month's last day where it has not.
package java.time;

import java.io.Serializable;
import java.time.chrono.ChronoLocalDate;
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

public final class LocalDate implements Temporal, TemporalAdjuster, ChronoLocalDate, Serializable {

    public static final LocalDate MIN = new LocalDate(-999999999, 1, 1);

    public static final LocalDate MAX = new LocalDate(999999999, 12, 31);

    public static final LocalDate EPOCH = new LocalDate(1970, 1, 1);

    private final int year;

    private final int month;

    private final int day;

    private LocalDate(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    public static LocalDate now() {
        return now(Clock.systemDefaultZone());
    }

    public static LocalDate now(ZoneId zone) {
        return now(Clock.system(zone));
    }

    public static LocalDate now(Clock clock) {
        return ofInstant(clock.instant(), clock.getZone());
    }

    public static LocalDate of(int year, Month month, int dayOfMonth) {
        return of(year, month.getValue(), dayOfMonth);
    }

    public static LocalDate of(int year, int month, int dayOfMonth) {
        Iso.checkYear(year);
        Iso.checkMonth(month);
        Iso.checkDay(dayOfMonth);
        if (dayOfMonth > 28 && dayOfMonth > Iso.monthLength(year, month)) {
            if (dayOfMonth == 29) {
                throw new DateTimeException("Invalid date 'February 29' as '" + year + "' is not a leap year");
            }
            throw new DateTimeException("Invalid date '" + Month.of(month).name() + " " + dayOfMonth + "'");
        }
        return new LocalDate(year, month, dayOfMonth);
    }

    public static LocalDate ofYearDay(int year, int dayOfYear) {
        Iso.checkYear(year);
        if (dayOfYear < 1 || dayOfYear > 366) {
            throw Iso.invalid("DayOfYear (valid values 1 - 365/366)", dayOfYear);
        }
        if (dayOfYear == 366 && !Iso.leap(year)) {
            throw new DateTimeException("Invalid date 'DayOfYear 366' as '" + year + "' is not a leap year");
        }
        int month = Iso.monthOf(year, dayOfYear - 1);
        return new LocalDate(year, month, dayOfYear - Iso.monthStart(year, month));
    }

    public static LocalDate ofInstant(Instant instant, ZoneId zone) {
        return ofEpochDay(Math.floorDiv(instant.getEpochSecond()
                + (ZoneId.regional() ? zone.offsetAt(instant.getEpochSecond()) : zone.fixed()).getTotalSeconds(), Iso.DAY));
    }

    public static LocalDate ofEpochDay(long epochDay) {
        Iso.checkEpochDay(epochDay);
        long year = Iso.yearOf(epochDay);
        int ofYear = (int) (epochDay + Iso.EPOCH - Iso.yearStart(year));
        int month = Iso.monthOf(year, ofYear);
        return new LocalDate((int) year, month, ofYear - Iso.monthStart(year, month) + 1);
    }

    public static LocalDate from(TemporalAccessor temporal) {
        if (temporal instanceof LocalDate date) {
            return date;
        }
        if (temporal instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (temporal instanceof OffsetDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        throw new DateTimeException("Unable to obtain LocalDate from TemporalAccessor: " + temporal + " of type "
                + temporal.getClass().getName());
    }

    public static LocalDate parse(CharSequence text) {
        IsoText in = new IsoText(text);
        in.date();
        in.end();
        return in.localDate();
    }

    /** The date of a year and a month with a day, or with the month's last where it has none. */
    private static LocalDate clamped(int year, int month, int day) {
        return new LocalDate(year, month, Math.min(day, Iso.monthLength(year, month)));
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field.isDateBased() : field != null && field.isSupportedBy(this);
    }

    public boolean isSupported(TemporalUnit unit) {
        return unit instanceof ChronoUnit ? unit.isDateBased() : unit != null && unit.isSupportedBy(this);
    }

    public ValueRange range(TemporalField field) {
        if (field instanceof ChronoField chrono) {
            if (!chrono.isDateBased()) {
                throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
            switch (chrono) {
                case DAY_OF_MONTH:
                    return ValueRange.of(1, lengthOfMonth());
                case DAY_OF_YEAR:
                    return ValueRange.of(1, lengthOfYear());
                case ALIGNED_WEEK_OF_MONTH:
                    return ValueRange.of(1, month == 2 && !isLeapYear() ? 4 : 5);
                case YEAR_OF_ERA:
                    return year <= 0 ? ValueRange.of(1, 1000000000) : ValueRange.of(1, 999999999);
                default:
                    return field.range();
            }
        }
        return field.rangeRefinedBy(this);
    }

    public int get(TemporalField field) {
        if (field instanceof ChronoField) {
            if (field == ChronoField.EPOCH_DAY || field == ChronoField.PROLEPTIC_MONTH) {
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
                case DAY_OF_WEEK:
                    return Math.floorMod(toEpochDay() + 3, 7L) + 1;
                case ALIGNED_DAY_OF_WEEK_IN_MONTH:
                    return (day - 1) % 7 + 1;
                case ALIGNED_DAY_OF_WEEK_IN_YEAR:
                    return (getDayOfYear() - 1) % 7 + 1;
                case DAY_OF_MONTH:
                    return day;
                case DAY_OF_YEAR:
                    return getDayOfYear();
                case EPOCH_DAY:
                    return toEpochDay();
                case ALIGNED_WEEK_OF_MONTH:
                    return (day - 1) / 7 + 1;
                case ALIGNED_WEEK_OF_YEAR:
                    return (getDayOfYear() - 1) / 7 + 1;
                case MONTH_OF_YEAR:
                    return month;
                case PROLEPTIC_MONTH:
                    return prolepticMonth();
                case YEAR_OF_ERA:
                    return year >= 1 ? year : 1 - year;
                case YEAR:
                    return year;
                case ERA:
                    return year >= 1 ? 1 : 0;
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.getFrom(this);
    }

    private long prolepticMonth() {
        return year * 12L + month - 1;
    }

    public int getYear() {
        return year;
    }

    public int getMonthValue() {
        return month;
    }

    public Month getMonth() {
        return Month.of(month);
    }

    public int getDayOfMonth() {
        return day;
    }

    public int getDayOfYear() {
        return Iso.monthStart(year, month) + day;
    }

    public DayOfWeek getDayOfWeek() {
        return DayOfWeek.of((int) Math.floorMod(toEpochDay() + 3, 7L) + 1);
    }

    public boolean isLeapYear() {
        return Iso.leap(year);
    }

    public int lengthOfMonth() {
        return Iso.monthLength(year, month);
    }

    public int lengthOfYear() {
        return isLeapYear() ? 366 : 365;
    }

    public LocalDate with(TemporalAdjuster adjuster) {
        return adjuster instanceof LocalDate date ? date : (LocalDate) adjuster.adjustInto(this);
    }

    public LocalDate with(TemporalField field, long newValue) {
        if (field instanceof ChronoField chrono) {
            chrono.checkValidValue(newValue);
            switch (chrono) {
                case DAY_OF_WEEK:
                    return plusDays(newValue - getDayOfWeek().getValue());
                case ALIGNED_DAY_OF_WEEK_IN_MONTH:
                    return plusDays(newValue - getLong(ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH));
                case ALIGNED_DAY_OF_WEEK_IN_YEAR:
                    return plusDays(newValue - getLong(ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR));
                case DAY_OF_MONTH:
                    return withDayOfMonth((int) newValue);
                case DAY_OF_YEAR:
                    return withDayOfYear((int) newValue);
                case EPOCH_DAY:
                    return ofEpochDay(newValue);
                case ALIGNED_WEEK_OF_MONTH:
                    return plusWeeks(newValue - getLong(ChronoField.ALIGNED_WEEK_OF_MONTH));
                case ALIGNED_WEEK_OF_YEAR:
                    return plusWeeks(newValue - getLong(ChronoField.ALIGNED_WEEK_OF_YEAR));
                case MONTH_OF_YEAR:
                    return withMonth((int) newValue);
                case PROLEPTIC_MONTH:
                    return plusMonths(newValue - prolepticMonth());
                case YEAR_OF_ERA:
                    return withYear((int) (year >= 1 ? newValue : 1 - newValue));
                case YEAR:
                    return withYear((int) newValue);
                case ERA:
                    return getLong(ChronoField.ERA) == newValue ? this : withYear(1 - year);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
            }
        }
        return field.adjustInto(this, newValue);
    }

    public LocalDate withYear(int year) {
        return this.year == year ? this : clamped(Iso.checkYear(year), month, day);
    }

    public LocalDate withMonth(int month) {
        return this.month == month ? this : clamped(year, Iso.checkMonth(month), day);
    }

    public LocalDate withDayOfMonth(int dayOfMonth) {
        return day == dayOfMonth ? this : of(year, month, dayOfMonth);
    }

    public LocalDate withDayOfYear(int dayOfYear) {
        return getDayOfYear() == dayOfYear ? this : ofYearDay(year, dayOfYear);
    }

    public LocalDate plus(TemporalAmount amountToAdd) {
        if (amountToAdd instanceof Period period) {
            return plusMonths(period.toTotalMonths()).plusDays(period.getDays());
        }
        return (LocalDate) amountToAdd.addTo(this);
    }

    public LocalDate plus(long amountToAdd, TemporalUnit unit) {
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
                case DAYS:
                    return plusDays(amountToAdd);
                case WEEKS:
                    return plusWeeks(amountToAdd);
                case MONTHS:
                    return plusMonths(amountToAdd);
                case YEARS:
                    return plusYears(amountToAdd);
                case DECADES:
                    return plusYears(Math.multiplyExact(amountToAdd, 10L));
                case CENTURIES:
                    return plusYears(Math.multiplyExact(amountToAdd, 100L));
                case MILLENNIA:
                    return plusYears(Math.multiplyExact(amountToAdd, 1000L));
                case ERAS:
                    return with(ChronoField.ERA, Math.addExact(getLong(ChronoField.ERA), amountToAdd));
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.addTo(this, amountToAdd);
    }

    public LocalDate plusYears(long yearsToAdd) {
        return yearsToAdd == 0 ? this : clamped(Iso.checkYear(year + yearsToAdd), month, day);
    }

    public LocalDate plusMonths(long monthsToAdd) {
        if (monthsToAdd == 0) {
            return this;
        }
        long months = prolepticMonth() + monthsToAdd;
        return clamped(Iso.checkYear(Math.floorDiv(months, 12L)), (int) Math.floorMod(months, 12L) + 1, day);
    }

    public LocalDate plusWeeks(long weeksToAdd) {
        return plusDays(Math.multiplyExact(weeksToAdd, 7L));
    }

    public LocalDate plusDays(long daysToAdd) {
        if (daysToAdd == 0) {
            return this;
        }
        long moved = day + daysToAdd;
        if (month == 12 && moved > 31 && moved <= 59) {
            // a JDK's answer: a date that leaves the last year by less than two
            // months is refused for its year, any other for its epoch day
            Iso.checkYear(year + 1L);
        }
        return ofEpochDay(Math.addExact(toEpochDay(), daysToAdd));
    }

    public LocalDate minus(TemporalAmount amountToSubtract) {
        if (amountToSubtract instanceof Period period) {
            return minusMonths(period.toTotalMonths()).minusDays(period.getDays());
        }
        return (LocalDate) amountToSubtract.subtractFrom(this);
    }

    public LocalDate minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    public LocalDate minusYears(long yearsToSubtract) {
        return yearsToSubtract == Long.MIN_VALUE ? plusYears(Long.MAX_VALUE).plusYears(1)
                : plusYears(-yearsToSubtract);
    }

    public LocalDate minusMonths(long monthsToSubtract) {
        return monthsToSubtract == Long.MIN_VALUE ? plusMonths(Long.MAX_VALUE).plusMonths(1)
                : plusMonths(-monthsToSubtract);
    }

    public LocalDate minusWeeks(long weeksToSubtract) {
        return weeksToSubtract == Long.MIN_VALUE ? plusWeeks(Long.MAX_VALUE).plusWeeks(1)
                : plusWeeks(-weeksToSubtract);
    }

    public LocalDate minusDays(long daysToSubtract) {
        return daysToSubtract == Long.MIN_VALUE ? plusDays(Long.MAX_VALUE).plusDays(1) : plusDays(-daysToSubtract);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.EPOCH_DAY, toEpochDay());
    }

    /** The whole months to another date: a month is whole once its day is reached. */
    private long monthsUntil(LocalDate end) {
        return ((end.prolepticMonth() * 32L + end.day) - (prolepticMonth() * 32L + day)) / 32;
    }

    public long until(Temporal endExclusive, TemporalUnit unit) {
        LocalDate end = from(endExclusive);
        if (unit instanceof ChronoUnit chrono) {
            switch (chrono) {
                case DAYS:
                    return end.toEpochDay() - toEpochDay();
                case WEEKS:
                    return (end.toEpochDay() - toEpochDay()) / 7;
                case MONTHS:
                    return monthsUntil(end);
                case YEARS:
                    return monthsUntil(end) / 12;
                case DECADES:
                    return monthsUntil(end) / 120;
                case CENTURIES:
                    return monthsUntil(end) / 1200;
                case MILLENNIA:
                    return monthsUntil(end) / 12000;
                case ERAS:
                    return end.getLong(ChronoField.ERA) - getLong(ChronoField.ERA);
                default:
                    throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
            }
        }
        return unit.between(this, end);
    }

    /** The years, months and days to another date, all of one sign. */
    public Period until(ChronoLocalDate endDateExclusive) {
        LocalDate end = from(endDateExclusive);
        long months = end.prolepticMonth() - prolepticMonth();
        int days = end.day - day;
        if (months > 0 && days < 0) {
            months--;
            days = (int) (end.toEpochDay() - plusMonths(months).toEpochDay());
        }
        else if (months < 0 && days > 0) {
            months++;
            days -= end.lengthOfMonth();
        }
        return Period.of(Math.toIntExact(months / 12), (int) (months % 12), days);
    }

    public LocalDateTime atTime(LocalTime time) {
        return LocalDateTime.of(this, time);
    }

    public LocalDateTime atTime(int hour, int minute) {
        return atTime(LocalTime.of(hour, minute));
    }

    public LocalDateTime atTime(int hour, int minute, int second) {
        return atTime(LocalTime.of(hour, minute, second));
    }

    public LocalDateTime atTime(int hour, int minute, int second, int nanoOfSecond) {
        return atTime(LocalTime.of(hour, minute, second, nanoOfSecond));
    }

    public LocalDateTime atStartOfDay() {
        return LocalDateTime.of(this, LocalTime.MIDNIGHT);
    }

    public ZonedDateTime atStartOfDay(ZoneId zone) {
        if (ZoneId.regional()) {
            // a day whose midnight a zone skips starts where the gap ends
            long[] change = new long[3];
            if (zone.placed(toEpochDay() * Iso.DAY, change) == 1) {
                return ZonedDateTime.of(LocalDateTime.ofEpochSecond(change[0] + change[2], 0, ZoneOffset.UTC), zone);
            }
        }
        return ZonedDateTime.of(atStartOfDay(), zone);
    }

    public long toEpochDay() {
        return Iso.epochDay(year, month, day);
    }

    public long toEpochSecond(LocalTime time, ZoneOffset offset) {
        return toEpochDay() * Iso.DAY + time.toSecondOfDay() - offset.getTotalSeconds();
    }

    public int compareTo(ChronoLocalDate other) {
        if (other instanceof LocalDate date) {
            int order = year - date.year;
            if (order == 0) {
                order = month - date.month;
            }
            return order != 0 ? order : day - date.day;
        }
        return Long.compare(toEpochDay(), other.toEpochDay());
    }

    public boolean isAfter(ChronoLocalDate other) {
        return compareTo(other) > 0;
    }

    public boolean isBefore(ChronoLocalDate other) {
        return compareTo(other) < 0;
    }

    public boolean isEqual(ChronoLocalDate other) {
        return compareTo(other) == 0;
    }

    public boolean equals(Object other) {
        return other instanceof LocalDate that && that.year == year && that.month == month && that.day == day;
    }

    public int hashCode() {
        return (year & 0xFFFFF800) ^ ((year << 11) + (month << 6) + day);
    }

    public String toString() {
        return Iso.date(new StringBuilder(), year, month, day).toString();
    }
}
