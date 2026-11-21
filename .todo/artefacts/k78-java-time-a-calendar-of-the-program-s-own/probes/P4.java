import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P4 {
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
        Thin thin = new Thin();
        ChronoPeriod p = shifted.period(1, 2, 3);
        ChronoPeriod t = thin.period(1, 2, 3);
        System.out.println("text " + p + " hash " + p.hashCode() + " " + t.hashCode() + " " + shifted.period(0, 0, 0).hashCode()
            + " " + shifted.period(1, 0, 0).hashCode() + " " + shifted.period(0, 1, 0).hashCode() + " " + shifted.period(0, 0, 1).hashCode()
            + " iso " + Period.of(1, 2, 3).hashCode() + " shiftedhash " + shifted.hashCode() + " thinhash " + thin.hashCode());
        System.out.println("units " + p.getUnits() + " " + p.getChronology() + " " + p.isZero() + " " + p.isNegative() + " "
            + shifted.period(0, 0, 0).isZero() + " " + shifted.period(0, -1, 0).isNegative() + " " + shifted.period(0, -1, 0).isZero());
        for (ChronoUnit u : ChronoUnit.values()) {
            attempt("get " + u, () -> p.get(u));
        }
        attempt("get null", () -> p.get(null));
        attempt("equals", () -> p.equals(shifted.period(1, 2, 3)) + " " + p.equals(t) + " " + p.equals(Period.of(1, 2, 3)) + " "
            + Period.of(1, 2, 3).equals(p) + " " + p.equals(null));
        attempt("plus", () -> p.plus(shifted.period(10, 20, 30)));
        attempt("plus iso", () -> p.plus(Period.of(10, 20, 30)));
        attempt("plus thin", () -> p.plus(t));
        attempt("plus duration", () -> p.plus(Duration.ofDays(2)));
        attempt("plus null", () -> p.plus(null));
        attempt("minus", () -> p.minus(shifted.period(10, 20, 30)));
        attempt("minus iso", () -> p.minus(Period.of(10, 20, 30)));
        attempt("iso plus", () -> Period.of(1, 2, 3).plus(p));
        attempt("multipliedBy", () -> p.multipliedBy(3) + " " + p.multipliedBy(1) + " " + p.multipliedBy(0) + " " + p.multipliedBy(-1));
        attempt("multipliedBy overflow", () -> p.multipliedBy(Integer.MAX_VALUE));
        attempt("negated", () -> p.negated() + " " + shifted.period(Integer.MIN_VALUE, 0, 0));
        attempt("negated overflow", () -> shifted.period(Integer.MIN_VALUE, 0, 0).negated());
        attempt("normalized", () -> shifted.period(1, 14, 3).normalized() + " " + shifted.period(1, -14, 3).normalized() + " "
            + shifted.period(-1, 14, 3).normalized() + " " + shifted.period(0, 25, 0).normalized() + " " + shifted.period(0, 0, 40).normalized());
        attempt("normalized overflow", () -> shifted.period(Integer.MAX_VALUE, 12, 0).normalized());
        ChronoLocalDate d = shifted.date(2126, 3, 7);
        attempt("addTo shifted date", () -> p.addTo(d) + " " + p.subtractFrom(d));
        attempt("addTo iso date", () -> p.addTo(LocalDate.of(2026, 3, 7)));
        attempt("subtractFrom iso date", () -> p.subtractFrom(LocalDate.of(2026, 3, 7)));
        attempt("thin addTo iso date", () -> t.addTo(LocalDate.of(2026, 3, 7)));
        attempt("addTo time", () -> p.addTo(LocalTime.NOON));
        attempt("addTo shifted ldt", () -> p.addTo(shifted.localDateTime(LocalDateTime.of(2026, 3, 7, 10, 0))));
        attempt("date plus", () -> d.plus(p) + " " + d.minus(p));
        attempt("date plus iso", () -> d.plus(Period.of(1, 2, 3)));
        attempt("date until", () -> d.until(shifted.date(2127, 5, 9)) + " " + ChronoPeriod.between(d, shifted.date(2120, 1, 1)));
        attempt("between iso", () -> ChronoPeriod.between(d, LocalDate.of(2027, 1, 1)));
        attempt("zero months", () -> shifted.period(0, 0, 0) + " " + shifted.period(0, 0, 5) + " " + shifted.period(-1, 0, 0) + " " + shifted.period(0, 3, 0));
        // with a period whose months are many: addTo of months
        attempt("addTo months", () -> shifted.period(0, 14, 0).addTo(d) + " " + shifted.period(2, -3, 40).addTo(d) + " " + shifted.period(0, 0, -400).addTo(d));
    }
}
