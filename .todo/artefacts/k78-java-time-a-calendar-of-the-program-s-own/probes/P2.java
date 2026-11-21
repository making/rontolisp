import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P2 {
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

    static String hashOf(Object o) { return o == null ? "null" : String.valueOf(o.hashCode()); }

    public static void main(String[] args) {
        Shifted shifted = Shifted.INSTANCE;
        Thin thin = new Thin();
        LocalDateTime ldt = LocalDateTime.of(2026, 3, 7, 10, 15, 30, 123000000);
        ChronoLocalDateTime<?> s = shifted.localDateTime(ldt);
        System.out.println("text " + s + " hash " + s.hashCode() + " cand " + (s.toLocalDate().hashCode() ^ s.toLocalTime().hashCode())
            + " ldt hash " + ldt.hashCode());
        System.out.println("parts " + s.toLocalDate() + " " + s.toLocalTime() + " " + s.getChronology());
        for (ChronoField f : ChronoField.values()) {
            attempt("field " + f + " " + s.isSupported(f), () -> s.getLong(f) + " " + s.range(f) + " " + s.get(f));
        }
        attempt("field null", () -> s.isSupported((TemporalField) null));
        attempt("field quarter", () -> s.isSupported(IsoFields.QUARTER_OF_YEAR) + " " + s.getLong(IsoFields.QUARTER_OF_YEAR));
        attempt("field julian", () -> s.isSupported(JulianFields.JULIAN_DAY) + " " + s.getLong(JulianFields.JULIAN_DAY));
        for (ChronoUnit u : ChronoUnit.values()) {
            attempt("unit " + u + " " + s.isSupported(u), () -> s.plus(5, u) + " | " + s.minus(5, u));
            attempt("until " + u, () -> s.until(shifted.localDateTime(LocalDateTime.of(2027, 5, 9, 1, 2, 3, 4)), u) + " "
                + s.until(LocalDateTime.of(2025, 1, 9, 1, 2, 3, 4), u));
        }
        attempt("unit null", () -> s.isSupported((TemporalUnit) null));
        for (ChronoField f : ChronoField.values()) {
            attempt("with " + f, () -> s.with(f, 2));
        }
        attempt("with year", () -> s.with(ChronoField.YEAR, 2200));
        attempt("with adjuster", () -> s.with(TemporalAdjusters.lastDayOfMonth()));
        attempt("with a time", () -> s.with(LocalTime.MIDNIGHT));
        attempt("with a local date", () -> s.with(LocalDate.of(1999, 1, 1)));
        attempt("with a shifted date", () -> s.with(shifted.date(2300, 1, 1)));
        attempt("plus period", () -> s.plus(Period.of(1, 2, 3)));
        attempt("plus duration", () -> s.plus(Duration.ofHours(30)));
        attempt("minus period", () -> s.minus(Period.of(1, 2, 3)));
        attempt("plus shifted period", () -> s.plus(shifted.period(1, 2, 3)));
        attempt("plus thin period", () -> s.plus(thin.period(1, 2, 3)));
        attempt("atZone utc", () -> s.atZone(ZoneOffset.UTC));
        attempt("atZone paris", () -> s.atZone(ZoneId.of("Europe/Paris")));
        attempt("toEpochSecond", () -> s.toEpochSecond(ZoneOffset.ofHours(1)) + " " + s.toInstant(ZoneOffset.ofHours(1)));
        ChronoLocalDateTime<?> other = shifted.localDateTime(LocalDateTime.of(2026, 3, 7, 10, 15, 30, 123000001));
        attempt("compare", () -> s.compareTo(other) + " " + other.compareTo(s) + " " + s.compareTo(ldt) + " " + ldt.compareTo(s)
            + " " + s.isBefore(other) + " " + s.isAfter(other) + " " + s.isEqual(ldt) + " " + s.equals(ldt) + " "
            + s.equals(shifted.localDateTime(ldt)));
        attempt("compare later date", () -> s.compareTo(LocalDateTime.of(2027, 1, 1, 0, 0)) + " " + s.compareTo(LocalDateTime.of(2020, 1, 1, 0, 0)));
        attempt("compare thin", () -> s.compareTo(thin.localDateTime(ldt)));
        attempt("timeLineOrder", () -> ChronoLocalDateTime.timeLineOrder().compare(s, ldt) + " " + ChronoLocalDateTime.timeLineOrder().compare(s, other));
        for (TemporalQuery<?> q : List.<TemporalQuery<?>>of(TemporalQueries.chronology(), TemporalQueries.precision(),
                TemporalQueries.localDate(), TemporalQueries.localTime(), TemporalQueries.zone(), TemporalQueries.zoneId(),
                TemporalQueries.offset())) {
            attempt("query " + q, () -> s.query(q));
        }
        attempt("adjustInto ldt", () -> s.adjustInto(LocalDateTime.of(1999, 1, 1, 0, 0)));
        attempt("adjustInto zdt", () -> s.adjustInto(ZonedDateTime.of(1999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
        attempt("from", () -> ChronoLocalDateTime.from(s) + " " + ChronoLocalDateTime.from(ldt) + " " + LocalDateTime.from(s));
        attempt("LocalDate.from", () -> LocalDate.from(s) + " " + LocalTime.from(s));
        attempt("format", () -> s.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        attempt("format pattern", () -> DateTimeFormatter.ofPattern("uuuu yyyy MM dd HH:mm G").format(s));
        attempt("formatter", () -> DateTimeFormatter.ISO_DATE_TIME.format(s));
        attempt("hash", () -> s.hashCode() + " " + shifted.localDateTime(LocalDateTime.of(1, 1, 1, 0, 0)).hashCode());
        attempt("thin atTime hash", () -> thin.date(2026, 3, 7).atTime(LocalTime.NOON).hashCode());
    }
}
