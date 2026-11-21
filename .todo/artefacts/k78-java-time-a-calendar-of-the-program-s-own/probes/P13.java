import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P13 {
    static void chain(String what, Supplier<Object> made) {
        try {
            System.out.println(what + ": " + made.get());
        }
        catch (RuntimeException e) {
            StringBuilder b = new StringBuilder(what + ": " + e);
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) b.append(" <- ").append(c);
            System.out.println(b);
        }
    }

    // a calendar whose years are ISO's shifted, of an id, a type and a range of months
    static final class Cal extends AbstractChronology {
        final String id;
        final String type;
        final int shift;
        final ValueRange months;
        Cal(String id, String type, int shift, ValueRange months) { this.id = id; this.type = type; this.shift = shift; this.months = months; }
        public String getId() { return id; }
        public String getCalendarType() { return type; }
        public Day date(int y, int m, int d) { return new Day(this, LocalDate.of(y - shift, m, d)); }
        public Day dateYearDay(int y, int d) { return new Day(this, LocalDate.ofYearDay(y - shift, d)); }
        public Day dateEpochDay(long e) { return new Day(this, LocalDate.ofEpochDay(e)); }
        public Day date(TemporalAccessor t) { return new Day(this, LocalDate.ofEpochDay(t.getLong(ChronoField.EPOCH_DAY))); }
        public boolean isLeapYear(long y) { return Year.isLeap(y - shift); }
        public int prolepticYear(Era era, int yoe) { return era == IsoEra.CE ? yoe : 1 - yoe; }
        public Era eraOf(int v) { return IsoEra.of(v); }
        public List<Era> eras() { return List.of(IsoEra.BCE, IsoEra.CE); }
        public ValueRange range(ChronoField f) { return f == ChronoField.MONTH_OF_YEAR ? months : f.range(); }
    }

    static final class Day implements ChronoLocalDate {
        final Cal cal;
        final LocalDate iso;
        Day(Cal cal, LocalDate iso) { this.cal = cal; this.iso = iso; }
        public Chronology getChronology() { return cal; }
        public int lengthOfMonth() { return iso.lengthOfMonth(); }
        public ValueRange range(TemporalField f) { return f instanceof ChronoField && f != ChronoField.YEAR ? iso.range(f) : ChronoLocalDate.super.range(f); }
        public long getLong(TemporalField f) {
            if (f == ChronoField.YEAR) return iso.getYear() + (long) cal.shift;
            if (f == ChronoField.YEAR_OF_ERA) { long y = iso.getYear() + (long) cal.shift; return y >= 1 ? y : 1 - y; }
            if (f == ChronoField.ERA) return iso.getYear() + (long) cal.shift >= 1 ? 1 : 0;
            if (f == ChronoField.PROLEPTIC_MONTH) return (iso.getYear() + (long) cal.shift) * 12 + iso.getMonthValue() - 1;
            if (f instanceof ChronoField) return iso.getLong(f);
            return f.getFrom(this);
        }
        public Day with(TemporalField f, long v) {
            if (f == ChronoField.YEAR) return new Day(cal, iso.withYear((int) (v - cal.shift)));
            if (f instanceof ChronoField) return new Day(cal, iso.with(f, v));
            return (Day) ChronoLocalDate.super.with(f, v);
        }
        public Day plus(long a, TemporalUnit u) {
            if (u instanceof ChronoUnit) return new Day(cal, iso.plus(a, u));
            return (Day) ChronoLocalDate.super.plus(a, u);
        }
        public long until(Temporal end, TemporalUnit u) { return iso.until(cal.date(end).iso, u); }
        public ChronoPeriod until(ChronoLocalDate end) {
            Period p = iso.until(cal.date(end).iso);
            return cal.period(p.getYears(), p.getMonths(), p.getDays());
        }
        public boolean equals(Object o) { return o instanceof Day d && d.cal.equals(cal) && d.iso.equals(iso); }
        public int hashCode() { return iso.hashCode() ^ cal.hashCode(); }
        public String toString() { return cal.getId() + " " + getLong(ChronoField.YEAR) + "-" + iso.getMonthValue() + "-" + iso.getDayOfMonth(); }
    }

    enum Age implements Era { BEFORE, AFTER; public int getValue() { return ordinal(); } }

    // a zoned value with no instant
    record Placed(LocalDate date, LocalTime time, ZoneId zone) implements TemporalAccessor {
        public boolean isSupported(TemporalField f) { return f instanceof ChronoField c && (c.isDateBased() ? true : c.isTimeBased()); }
        public long getLong(TemporalField f) { return ((ChronoField) f).isDateBased() ? date.getLong(f) : time.getLong(f); }
        @SuppressWarnings("unchecked")
        public <R> R query(TemporalQuery<R> q) { return q == TemporalQueries.zoneId() ? (R) zone : TemporalAccessor.super.query(q); }
    }

    public static void main(String[] args) {
        Thin thin = new Thin();
        Cal a = new Cal("Alpha", "alpha", 100, ChronoField.MONTH_OF_YEAR.range());
        DateTimeFormatter io = new DateTimeFormatterBuilder().appendInstant().appendLiteral(' ').appendOffsetId().toFormatter();
        chain("instant offset thin", () -> io.withChronology(thin).parse("2026-03-07T10:15:30Z +02:00"));
        chain("instant zone offset thin", () -> io.withZone(ZoneOffset.ofHours(3)).withChronology(thin).parse("2026-03-07T10:15:30Z +02:00"));
        chain("instant zone thin", () -> DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.ofHours(3)).withChronology(thin).parse("2026-03-07T10:15:30Z"));
        chain("instant and date alpha", () -> new DateTimeFormatterBuilder().appendInstant().appendLiteral(' ').appendPattern("uuuu-MM-dd").toFormatter().withZone(ZoneOffset.UTC).withChronology(a).parse("2026-03-07T10:15:30Z 2126-03-07"));
        chain("instant and other date alpha", () -> new DateTimeFormatterBuilder().appendInstant().appendLiteral(' ').appendPattern("uuuu-MM-dd").toFormatter().withZone(ZoneOffset.UTC).withChronology(a).parse("2026-03-07T10:15:30Z 2126-03-08"));
        chain("instant and other date iso", () -> new DateTimeFormatterBuilder().appendInstant().appendLiteral(' ').appendPattern("uuuu-MM-dd").toFormatter().withZone(ZoneOffset.UTC).parse("2026-03-07T10:15:30Z 2026-03-08"));
        chain("instant and time alpha", () -> new DateTimeFormatterBuilder().appendInstant().appendLiteral(' ').appendPattern("HH:mm").toFormatter().withZone(ZoneOffset.UTC).withChronology(a).parse("2026-03-07T10:15:30Z 11:15"));
        chain("excess alpha date only epoch", () -> new DateTimeFormatterBuilder().appendPattern("HH:mm ").appendValue(ChronoField.EPOCH_DAY).toFormatter().withChronology(a).parse("24:00 20519"));
        chain("excess thin", () -> DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withChronology(thin).parse("2026-03-07 24:00"));
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
}
