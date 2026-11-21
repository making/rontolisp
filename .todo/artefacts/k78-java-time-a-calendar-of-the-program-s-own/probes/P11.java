import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P11 {
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
        Cal a = new Cal("Alpha", "alpha", 100, ChronoField.MONTH_OF_YEAR.range());
        Thin thin = new Thin();
        ZoneId paris = ZoneId.of("Europe/Paris");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withChronology(a);
        chain("excess smart", () -> f.parse("2126-03-07 24:00"));
        chain("excess lenient", () -> f.withResolverStyle(ResolverStyle.LENIENT).parse("2126-03-07 25:00"));
        chain("excess lenient days", () -> f.withResolverStyle(ResolverStyle.LENIENT).parse("2126-03-07 49:00").query(DateTimeFormatter.parsedExcessDays()));
        chain("excess no date", () -> DateTimeFormatter.ofPattern("HH:mm").withChronology(a).parse("24:00").query(DateTimeFormatter.parsedExcessDays()));
        chain("localizedBy", () -> f.localizedBy(Locale.UK).getChronology() + " " + f.withLocale(Locale.UK).getChronology() + " "
            + DateTimeFormatter.ofPattern("uuuu").localizedBy(Locale.UK).getChronology());
        chain("age names", () -> Age.AFTER.getDisplayName(TextStyle.FULL, Locale.US) + " " + Age.BEFORE.getDisplayName(TextStyle.SHORT, Locale.US)
            + " " + IsoEra.CE.getDisplayName(TextStyle.FULL, Locale.US));
        chain("chronology names", () -> a.getDisplayName(TextStyle.FULL, Locale.US) + " " + thin.getDisplayName(TextStyle.NARROW, Locale.ROOT));
        chain("instant zone thin", () -> DateTimeFormatter.ISO_INSTANT.withZone(paris).withChronology(thin).parse("2026-03-07T10:15:30Z"));
        chain("instant zone alpha", () -> DateTimeFormatter.ISO_INSTANT.withZone(paris).withChronology(a).parse("2026-03-07T10:15:30Z"));
        ChronoZonedDateTime<?> z = a.date(2126, 3, 7).atTime(LocalTime.of(10, 0)).atZone(paris);
        chain("until dst", () -> z.until(ZonedDateTime.of(2026, 7, 1, 10, 30, 0, 0, paris), ChronoUnit.DAYS) + " "
            + z.until(ZonedDateTime.of(2026, 7, 1, 10, 30, 0, 0, paris), ChronoUnit.HOURS) + " "
            + ZonedDateTime.of(2026, 3, 7, 10, 0, 0, 0, paris).until(ZonedDateTime.of(2026, 7, 1, 10, 30, 0, 0, paris), ChronoUnit.DAYS));
        chain("placed", () -> a.zonedDateTime(new Placed(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), paris)));
        chain("placed thin", () -> thin.zonedDateTime(new Placed(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), paris)));
        chain("placed offset", () -> a.zonedDateTime(new Placed(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), ZoneOffset.ofHours(3))));
        ChronoLocalDate d = a.date(2126, 3, 7);
        chain("plus null", () -> d.plus((TemporalAmount) null));
        chain("minus null", () -> d.minus((TemporalAmount) null));
        chain("with null", () -> d.with((TemporalAdjuster) null));
        chain("with null field", () -> d.with((TemporalField) null, 1));
        chain("plus null unit", () -> d.plus(1, (TemporalUnit) null));
        chain("compare null", () -> d.compareTo(null));
        chain("isAfter null", () -> d.isAfter(null));
        chain("query null", () -> d.query(null));
        chain("adjustInto null", () -> d.adjustInto(null));
        chain("format null", () -> d.format(null));
        ChronoLocalDateTime<?> s = d.atTime(LocalTime.NOON);
        chain("ldt plus null", () -> s.plus((TemporalAmount) null));
        chain("ldt with null", () -> s.with((TemporalAdjuster) null));
        chain("ldt with null field", () -> s.with((TemporalField) null, 1));
        chain("ldt plus null unit", () -> s.plus(1, (TemporalUnit) null));
        chain("ldt compare null", () -> s.compareTo(null));
        chain("ldt query null", () -> s.query(null));
        chain("ldt getLong null", () -> s.getLong(null));
        chain("ldt range null", () -> s.range(null));
        chain("ldt toEpochSecond null", () -> s.toEpochSecond(null));
        chain("ldt toInstant null", () -> s.toInstant(null));
        chain("ldt isAfter null", () -> s.isAfter(null));
        chain("ldt until null unit", () -> s.until(s, null));
        chain("ldt format null", () -> s.format(null));
        chain("zdt plus null", () -> z.plus((TemporalAmount) null));
        chain("zdt with null", () -> z.with((TemporalAdjuster) null));
        chain("zdt with null field", () -> z.with((TemporalField) null, 1));
        chain("zdt plus null unit", () -> z.plus(1, (TemporalUnit) null));
        chain("zdt getLong null", () -> z.getLong(null));
        chain("zdt range null", () -> z.range(null));
        chain("zdt get null", () -> z.get(null));
        chain("zdt compare null", () -> z.compareTo(null));
        chain("zdt isAfter null", () -> z.isAfter(null));
        chain("zdt until null", () -> z.until(null, ChronoUnit.DAYS));
        chain("zdt until null unit", () -> z.until(z, null));
        chain("period get null", () -> a.period(1, 2, 3).get(null));
        chain("period plus self chrono", () -> a.period(1, 2, 3).plus(new Cal("Alpha", "alpha", 100, ChronoField.MONTH_OF_YEAR.range()).period(1, 1, 1)));
        chain("period equals other cal", () -> a.period(1, 2, 3).equals(new Cal("Alpha", "x", 0, ChronoField.MONTH_OF_YEAR.range()).period(1, 2, 3)));
        chain("ldt of thin date atTime", () -> thin.date(2026, 3, 7).atTime(LocalTime.NOON).getClass().getName());
        chain("iso own date atTime", () -> new Day(new Cal("ISO", "iso8601", 0, ChronoField.MONTH_OF_YEAR.range()), LocalDate.of(2026, 3, 7)).atTime(LocalTime.NOON).getClass().getName());
        chain("plus big hours", () -> s.plus(Long.MAX_VALUE, ChronoUnit.HOURS));
        chain("plus big nanos", () -> s.plus(Long.MAX_VALUE, ChronoUnit.NANOS));
        chain("plus big half", () -> s.plus(Long.MAX_VALUE, ChronoUnit.HALF_DAYS));
        chain("plus min micros", () -> s.plus(Long.MIN_VALUE, ChronoUnit.MICROS));
        chain("plus half 3", () -> s.plus(3, ChronoUnit.HALF_DAYS) + " " + s.plus(-3, ChronoUnit.HALF_DAYS) + " " + s.plus(-1, ChronoUnit.NANOS) + " " + s.plus(-86400001, ChronoUnit.MILLIS));
        chain("until big", () -> s.until(a.date(-999999899 + 100, 1, 1).atTime(LocalTime.MIN), ChronoUnit.NANOS));
        chain("until big micros", () -> s.until(a.date(-999999899 + 100, 1, 1).atTime(LocalTime.MIN), ChronoUnit.MICROS));
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
