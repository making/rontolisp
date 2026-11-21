import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P7 {
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

    public static void main(String[] args) {
        Shifted shifted = Shifted.INSTANCE;
        ChronoLocalDate d = shifted.date(2126, 3, 7);
        WeekFields us = WeekFields.of(Locale.US);
        attempt("wby", () -> us.weekBasedYear().isSupportedBy(d) + " " + us.weekBasedYear().getFrom(d) + " " + us.weekOfWeekBasedYear().getFrom(d)
            + " " + us.dayOfWeek().getFrom(d) + " " + us.weekOfYear().getFrom(d) + " " + us.weekOfMonth().getFrom(d));
        attempt("wby range", () -> us.weekBasedYear().rangeRefinedBy(d) + " " + us.weekOfWeekBasedYear().rangeRefinedBy(d));
        attempt("iso quarter", () -> IsoFields.QUARTER_OF_YEAR.isSupportedBy(d) + " " + IsoFields.WEEK_BASED_YEAR.isSupportedBy(d));
        attempt("iso quarter get", () -> IsoFields.QUARTER_OF_YEAR.getFrom(d));
        attempt("iso wby get", () -> IsoFields.WEEK_BASED_YEAR.getFrom(d));
        attempt("julian", () -> JulianFields.JULIAN_DAY.isSupportedBy(d) + " " + JulianFields.JULIAN_DAY.getFrom(d) + " " + JulianFields.MODIFIED_JULIAN_DAY.getFrom(d));
        attempt("format Q", () -> DateTimeFormatter.ofPattern("QQQ").format(d));
        attempt("format Q shifted", () -> DateTimeFormatter.ofPattern("QQQ").withChronology(shifted).format(LocalDate.of(2026, 3, 7)));
        attempt("format Y shifted", () -> DateTimeFormatter.ofPattern("YYYY").withChronology(shifted).format(LocalDate.of(2026, 3, 7)));
        attempt("format Y", () -> DateTimeFormatter.ofPattern("YYYY").format(d));
        attempt("format g", () -> DateTimeFormatter.ofPattern("g").format(d) + " " + DateTimeFormatter.ofPattern("g").withChronology(shifted).format(LocalDate.of(2026, 3, 7)));
        attempt("with wby", () -> d.with(us.weekBasedYear(), 2130));
        attempt("with dow", () -> d.with(us.dayOfWeek(), 1));
        attempt("adjust", () -> d.with(TemporalAdjusters.firstDayOfNextYear()) + " " + d.with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)));
        attempt("plus weeks of wf", () -> d.plus(2, IsoFields.WEEK_BASED_YEARS));
        attempt("iso week date", () -> DateTimeFormatter.ISO_WEEK_DATE.format(d));
        attempt("iso week date shifted", () -> DateTimeFormatter.ISO_WEEK_DATE.withChronology(shifted).format(LocalDate.of(2026, 3, 7)));
        attempt("iso ordinal", () -> DateTimeFormatter.ISO_ORDINAL_DATE.format(d) + " " + DateTimeFormatter.ISO_DATE.format(d) + " " + DateTimeFormatter.BASIC_ISO_DATE.format(d));
        attempt("rfc", () -> DateTimeFormatter.RFC_1123_DATE_TIME.format(shifted.zonedDateTime(ZonedDateTime.of(2026, 3, 7, 10, 0, 0, 0, ZoneOffset.UTC))));
        attempt("chrono text", () -> new DateTimeFormatterBuilder().appendChronologyId().toFormatter().format(d));
        attempt("chrono text iso", () -> new DateTimeFormatterBuilder().appendChronologyId().toFormatter().format(LocalDate.of(2026, 3, 7)));
        attempt("chrono text full", () -> new DateTimeFormatterBuilder().appendChronologyText(TextStyle.FULL).toFormatter(Locale.US).format(d));
    }
}
