import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P10 {
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

    public static void main(String[] args) {
        Cal a = new Cal("Alpha", "alpha", 100, ChronoField.MONTH_OF_YEAR.range());
        Cal b = new Cal("Beta", "beta", 0, ValueRange.of(1, 12, 13));
        Cal c = new Cal("Gamma", "gamma", 0, ValueRange.of(1, 13));
        chain("normalized b", () -> b.period(1, 14, 3).normalized() + " " + b.period(0, 26, 3).normalized());
        chain("normalized c", () -> c.period(1, 14, 3).normalized() + " " + c.period(0, 26, 3).normalized() + " " + c.period(1, -14, 0).normalized());
        chain("addTo b", () -> b.period(1, -1, 0).addTo(b.date(2024, 2, 29)) + " " + b.period(1, -1, 0).subtractFrom(b.date(2024, 2, 29)));
        chain("addTo a", () -> a.period(1, -1, 0).addTo(a.date(2124, 2, 29)) + " " + a.period(1, -1, 0).subtractFrom(a.date(2124, 2, 29)));
        chain("addTo c", () -> c.period(1, -1, 0).addTo(c.date(2024, 2, 29)) + " " + c.period(1, 1, 0).addTo(c.date(2024, 1, 31)));
        chain("addTo b time", () -> b.period(1, 2, 0).addTo(LocalTime.NOON));
        chain("addTo a ldt of b", () -> a.period(1, 2, 0).addTo(b.date(2024, 1, 1).atTime(LocalTime.NOON)));
        chain("ldt adjuster to b", () -> a.date(2124, 1, 1).atTime(LocalTime.NOON).with(t -> b.date(2024, 1, 1).atTime(LocalTime.NOON)));
        chain("ldt with b date", () -> a.date(2124, 1, 1).atTime(LocalTime.NOON).with(b.date(2024, 1, 1)));
        chain("zdt adjuster to b", () -> a.date(2124, 1, 1).atTime(LocalTime.NOON).atZone(ZoneOffset.UTC).with(t -> b.date(2024, 1, 1).atTime(LocalTime.NOON).atZone(ZoneOffset.UTC)));
        chain("date adjuster to b", () -> a.date(2124, 1, 1).with(t -> b.date(2024, 1, 1)));
        chain("period plus b", () -> a.period(1, 2, 3).plus(b.period(1, 1, 1)));
        chain("compare a b", () -> a.date(2124, 1, 1).compareTo(b.date(2024, 1, 1)) + " " + a.date(2124, 1, 1).atTime(LocalTime.NOON).compareTo(b.date(2024, 1, 1).atTime(LocalTime.NOON)));
        for (String type : new String[] {"iso8601", "gregory", "buddhist", "japanese", "roc", "islamic-umalqura", "ISO8601", "", "thin"}) {
            Cal t = new Cal("T", type, 0, ChronoField.MONTH_OF_YEAR.range());
            chain("words " + type, () -> DateTimeFormatter.ofPattern("G GGGG MMMM EEEE a", Locale.US).withChronology(t).format(LocalDateTime.of(2026, 3, 7, 10, 0))
                + " | " + DateTimeFormatter.ofPattern("G GGGG MMMM EEEE a", Locale.US).format(t.date(2026, 3, 7).atTime(LocalTime.NOON))
                + " | " + DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.US).withChronology(t).format(LocalDate.of(2026, 3, 7))
                + " | " + DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.US).withChronology(t).format(LocalDate.of(2026, 3, 7)));
            chain("read words " + type, () -> DateTimeFormatter.ofPattern("G uuuu MMMM dd", Locale.US).withChronology(t).parse("AD 2026 March 07"));
        }
        chain("leniently era of b", () -> new DateTimeFormatterBuilder().parseLenient().appendPattern("G uuuu-MM-dd").toFormatter(Locale.US).withChronology(b).parse("CE 2026-03-07"));
        chain("root era of a", () -> DateTimeFormatter.ofPattern("G", Locale.ROOT).withChronology(a).format(LocalDate.of(2026, 3, 7)));
        chain("text of era b", () -> new DateTimeFormatterBuilder().appendText(ChronoField.ERA, Map.of(1L, "Up", 0L, "Down")).toFormatter().withChronology(b).format(LocalDate.of(2026, 3, 7)));
        chain("months of b", () -> DateTimeFormatter.ofPattern("MMMM", Locale.UK).withChronology(b).parse("March"));
    }
}
