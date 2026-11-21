import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P5 {
    static void attempt(String what, Supplier<Object> made) {
        try {
            Object o = made.get();
            System.out.println(what + ": " + o + (o == null ? "" : " <" + o.getClass().getName() + ">"));
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e + " cause " + e.getCause());
        }
    }

    static final class Thin extends AbstractChronology {
        public String getId() { return "Thin"; }
        public String getCalendarType() { return "thin"; }
        public ChronoLocalDate date(int prolepticYear, int month, int dayOfMonth) { return LocalDate.of(prolepticYear, month, dayOfMonth); }
        public ChronoLocalDate dateYearDay(int prolepticYear, int dayOfYear) { return LocalDate.ofYearDay(prolepticYear, dayOfYear); }
        public ChronoLocalDate dateEpochDay(long epochDay) { return LocalDate.ofEpochDay(epochDay); }
        public ChronoLocalDate date(TemporalAccessor temporal) { return LocalDate.from(temporal); }
        public boolean isLeapYear(long prolepticYear) { return Year.isLeap(prolepticYear); }
        public int prolepticYear(Era era, int yearOfEra) { return era == IsoEra.CE ? yearOfEra : 1 - yearOfEra; }
        public Era eraOf(int eraValue) { return IsoEra.of(eraValue); }
        public List<Era> eras() { return List.of(IsoEra.BCE, IsoEra.CE); }
        public ValueRange range(ChronoField field) { return field.range(); }
    }

    enum Age implements Era { BEFORE, AFTER; public int getValue() { return ordinal(); } }

    static final class Shifted extends AbstractChronology {
        static final Shifted INSTANCE = new Shifted();
        public String getId() { return "Shifted"; }
        public String getCalendarType() { return "shifted"; }
        public ShiftedDate date(int prolepticYear, int month, int dayOfMonth) { return new ShiftedDate(LocalDate.of(prolepticYear - 100, month, dayOfMonth)); }
        public ShiftedDate dateYearDay(int prolepticYear, int dayOfYear) { return new ShiftedDate(LocalDate.ofYearDay(prolepticYear - 100, dayOfYear)); }
        public ShiftedDate dateEpochDay(long epochDay) { return new ShiftedDate(LocalDate.ofEpochDay(epochDay)); }
        public ShiftedDate date(TemporalAccessor temporal) {
            return temporal instanceof ShiftedDate d ? d : new ShiftedDate(LocalDate.ofEpochDay(temporal.getLong(ChronoField.EPOCH_DAY)));
        }
        public boolean isLeapYear(long prolepticYear) { return Year.isLeap(prolepticYear - 100); }
        public int prolepticYear(Era era, int yearOfEra) { return era == Age.AFTER ? yearOfEra : 1 - yearOfEra; }
        public Era eraOf(int eraValue) { return Age.values()[eraValue]; }
        public List<Era> eras() { return List.of(Age.values()); }
        public ValueRange range(ChronoField field) {
            return field == ChronoField.YEAR ? ValueRange.of(-999999899L, 1000000099L) : field.range();
        }
    }

    static final class ShiftedDate implements ChronoLocalDate {
        final LocalDate iso;
        ShiftedDate(LocalDate iso) { this.iso = iso; }
        public Chronology getChronology() { return Shifted.INSTANCE; }
        public int lengthOfMonth() { return iso.lengthOfMonth(); }
        public long getLong(TemporalField field) {
            if (field == ChronoField.YEAR) return iso.getYear() + 100L;
            if (field == ChronoField.YEAR_OF_ERA) { long y = iso.getYear() + 100L; return y >= 1 ? y : 1 - y; }
            if (field == ChronoField.ERA) return iso.getYear() + 100L >= 1 ? 1 : 0;
            if (field == ChronoField.PROLEPTIC_MONTH) return (iso.getYear() + 100L) * 12 + iso.getMonthValue() - 1;
            return iso.getLong(field);
        }
        public ShiftedDate with(TemporalField field, long value) {
            if (field == ChronoField.YEAR) return new ShiftedDate(iso.withYear((int) value - 100));
            if (field instanceof ChronoField) return new ShiftedDate(iso.with(field, value));
            return (ShiftedDate) ChronoLocalDate.super.with(field, value);
        }
        public ShiftedDate plus(long amount, TemporalUnit unit) {
            if (unit instanceof ChronoUnit) return new ShiftedDate(iso.plus(amount, unit));
            return (ShiftedDate) ChronoLocalDate.super.plus(amount, unit);
        }
        public long until(Temporal end, TemporalUnit unit) { return iso.until(Shifted.INSTANCE.date(end).iso, unit); }
        public ChronoPeriod until(ChronoLocalDate end) {
            Period p = iso.until(Shifted.INSTANCE.date(end).iso);
            return Shifted.INSTANCE.period(p.getYears(), p.getMonths(), p.getDays());
        }
        public boolean equals(Object o) { return o instanceof ShiftedDate d && d.iso.equals(iso); }
        public int hashCode() { return iso.hashCode() ^ 7; }
        public String toString() { return "Shifted " + getLong(ChronoField.YEAR) + "-" + iso.getMonthValue() + "-" + iso.getDayOfMonth(); }
    }

    // a date leaving every default to the interface
    static final class Bare implements ChronoLocalDate {
        final LocalDate iso;
        Bare(LocalDate iso) { this.iso = iso; }
        public Chronology getChronology() { return Shifted.INSTANCE; }
        public int lengthOfMonth() { return iso.lengthOfMonth(); }
        public long getLong(TemporalField field) { return iso.getLong(field); }
        public long until(Temporal end, TemporalUnit unit) { return 0; }
        public ChronoPeriod until(ChronoLocalDate end) { return null; }
        public String toString() { return "Bare " + iso; }
    }

    public static void main(String[] args) {
        Shifted shifted = Shifted.INSTANCE;
        ChronoLocalDate d = shifted.date(2126, 3, 7);
        ChronoLocalDate leap = shifted.date(2124, 2, 29);
        System.out.println("defaults " + d.toEpochDay() + " " + d.isLeapYear() + " " + leap.isLeapYear() + " " + d.lengthOfYear() + " "
            + leap.lengthOfYear() + " " + d.getEra() + " " + shifted.date(-5, 1, 1).getEra() + " " + d.lengthOfMonth());
        ChronoLocalDate e = shifted.date(2126, 3, 8);
        attempt("compare", () -> d.compareTo(e) + " " + e.compareTo(d) + " " + d.compareTo(d) + " " + d.compareTo(LocalDate.of(2026, 3, 7))
            + " " + LocalDate.of(2026, 3, 7).compareTo(d) + " " + d.compareTo(LocalDate.of(2026, 3, 9)) + " " + d.compareTo(LocalDate.of(1, 3, 9)));
        attempt("isAfter", () -> d.isAfter(e) + " " + e.isAfter(d) + " " + d.isBefore(e) + " " + d.isEqual(e) + " " + d.isEqual(LocalDate.of(2026, 3, 7))
            + " " + LocalDate.of(2026, 3, 7).isEqual(d) + " " + LocalDate.of(2026, 3, 7).isAfter(d) + " " + LocalDate.of(2026, 3, 8).isAfter(d));
        attempt("timeLineOrder", () -> ChronoLocalDate.timeLineOrder().compare(d, LocalDate.of(2026, 3, 7)) + " " + ChronoLocalDate.timeLineOrder().compare(d, e));
        for (TemporalQuery<?> q : List.<TemporalQuery<?>>of(TemporalQueries.chronology(), TemporalQueries.precision(),
                TemporalQueries.localDate(), TemporalQueries.localTime(), TemporalQueries.zone(), TemporalQueries.zoneId(),
                TemporalQueries.offset())) {
            attempt("query " + q, () -> d.query(q));
        }
        attempt("adjustInto", () -> d.adjustInto(LocalDate.of(1999, 1, 1)) + " " + d.adjustInto(LocalDateTime.of(1999, 1, 1, 10, 0)));
        attempt("LocalDate.from", () -> LocalDate.from(d) + " " + LocalDate.of(1999, 1, 1).with(d));
        attempt("with adjuster", () -> d.with(TemporalAdjusters.lastDayOfMonth()) + " " + d.with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
        attempt("with local date", () -> d.with(LocalDate.of(1999, 1, 1)));
        attempt("with julian", () -> d.with(JulianFields.JULIAN_DAY, 2461000));
        attempt("with quarter", () -> d.with(IsoFields.DAY_OF_QUARTER, 1));
        attempt("plus period", () -> d.plus(Period.ofDays(2)));
        attempt("plus duration", () -> d.plus(Duration.ofDays(2)));
        attempt("plus quarter", () -> d.plus(1, IsoFields.QUARTER_YEARS) + " " + d.minus(1, IsoFields.QUARTER_YEARS));
        attempt("minus", () -> d.minus(3, ChronoUnit.DAYS) + " " + d.minus(shifted.period(0, 0, 3)));
        attempt("isSupported", () -> d.isSupported(ChronoField.HOUR_OF_DAY) + " " + d.isSupported(ChronoField.EPOCH_DAY) + " "
            + d.isSupported(ChronoUnit.HOURS) + " " + d.isSupported(ChronoUnit.DAYS) + " " + d.isSupported(ChronoUnit.FOREVER) + " "
            + d.isSupported(IsoFields.QUARTER_OF_YEAR) + " " + d.isSupported(JulianFields.JULIAN_DAY) + " " + d.isSupported((TemporalField) null)
            + " " + d.isSupported((TemporalUnit) null) + " " + d.isSupported(IsoFields.QUARTER_YEARS));
        attempt("format", () -> d.format(DateTimeFormatter.ISO_LOCAL_DATE) + " " + d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd G")));
        attempt("atTime", () -> d.atTime(LocalTime.of(10, 0)));
        attempt("range", () -> d.range(ChronoField.DAY_OF_MONTH) + " " + d.range(ChronoField.YEAR));
        attempt("get", () -> d.get(ChronoField.YEAR) + " " + d.get(ChronoField.DAY_OF_MONTH));
        // the bare date: every default
        ChronoLocalDate b = new Bare(LocalDate.of(2026, 3, 7));
        attempt("bare with field", () -> b.with(ChronoField.DAY_OF_MONTH, 2));
        attempt("bare plus unit", () -> b.plus(2, ChronoUnit.DAYS));
        attempt("bare minus unit", () -> b.minus(2, ChronoUnit.DAYS));
        attempt("bare with julian", () -> b.with(JulianFields.JULIAN_DAY, 2461000));
        attempt("bare with adjuster", () -> b.with(TemporalAdjusters.lastDayOfMonth()));
        attempt("bare with local date", () -> b.with(LocalDate.of(1999, 1, 1)));
        attempt("bare plus period", () -> b.plus(Period.ofDays(2)));
        attempt("bare plus quarter", () -> b.plus(1, IsoFields.QUARTER_YEARS));
        attempt("bare minus quarter", () -> b.minus(1, IsoFields.QUARTER_YEARS));
        attempt("bare atTime", () -> b.atTime(LocalTime.NOON));
        attempt("bare hash eq", () -> b.equals(new Bare(LocalDate.of(2026, 3, 7))) + " " + b.compareTo(new Bare(LocalDate.of(2026, 3, 7))));
        attempt("bare query", () -> b.query(TemporalQueries.localDate()) + " " + b.query(TemporalQueries.chronology()) + " " + b.query(TemporalQueries.precision()));
        attempt("bare isLeapYear", () -> b.isLeapYear() + " " + b.lengthOfYear() + " " + b.toEpochDay() + " " + b.getEra());
        attempt("bare format", () -> b.format(DateTimeFormatter.ISO_LOCAL_DATE));
        attempt("bare plus minus overflow", () -> b.minus(Long.MIN_VALUE, ChronoUnit.DAYS));
        attempt("bare minus long min quarter", () -> b.minus(Long.MIN_VALUE, IsoFields.QUARTER_YEARS));
    }
}
