import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P6 {
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

    static String read(DateTimeFormatter f, String text) {
        try {
            TemporalAccessor t = f.parse(text);
            StringBuilder b = new StringBuilder(String.valueOf(t));
            b.append(" | chrono ").append(t.query(TemporalQueries.chronology()));
            b.append(" | date ").append(t.query(TemporalQueries.localDate()));
            for (TemporalQuery<?> q : List.<TemporalQuery<?>>of(LocalDate::from, ChronoLocalDate::from, LocalDateTime::from,
                    ChronoLocalDateTime::from, ZonedDateTime::from, ChronoZonedDateTime::from, Instant::from)) {
                try {
                    Object o = q.queryFrom(t);
                    b.append(" | ").append(o).append(" <").append(o.getClass().getSimpleName()).append(">");
                }
                catch (RuntimeException e) {
                    b.append(" | ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
                }
            }
            return b.toString();
        }
        catch (RuntimeException e) {
            return e.toString() + " cause " + e.getCause();
        }
    }

    public static void main(String[] args) {
        Shifted shifted = Shifted.INSTANCE;
        Thin thin = new Thin();
        ZoneId paris = ZoneId.of("Europe/Paris");
        LocalDateTime ldt = LocalDateTime.of(2026, 3, 7, 10, 15, 30);
        Object[] values = {LocalDate.of(2026, 3, 7), ldt, ZonedDateTime.of(ldt, paris), shifted.date(2126, 3, 7),
            shifted.localDateTime(ldt), shifted.zonedDateTime(ZonedDateTime.of(ldt, paris)), Instant.parse("2026-03-07T10:15:30Z"),
            LocalTime.NOON, YearMonth.of(2026, 3), MonthDay.of(3, 7), Year.of(2026), thin.date(2026, 3, 7)};
        Chronology[] chronologies = {null, IsoChronology.INSTANCE, thin, shifted};
        for (Chronology c : chronologies) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("uuuu-MM-dd G yyyy GGGG MMMM").withLocale(Locale.US).withChronology(c);
            DateTimeFormatter z = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV").withLocale(Locale.US).withChronology(c).withZone(paris);
            for (Object v : values) {
                attempt("write " + c + " " + v, () -> f.format((TemporalAccessor) v));
                attempt("write zoned " + c + " " + v, () -> z.format((TemporalAccessor) v));
            }
            attempt("iso date " + c, () -> DateTimeFormatter.ISO_LOCAL_DATE.withChronology(c).format(shifted.date(2126, 3, 7)));
            attempt("iso date of thin " + c, () -> DateTimeFormatter.ISO_LOCAL_DATE.withChronology(c).format(thin.date(2026, 3, 7)));
            attempt("medium " + c, () -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US).withChronology(c).format(LocalDate.of(2026, 3, 7)));
            attempt("full " + c, () -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.US).withChronology(c).format(LocalDate.of(2026, 3, 7)));
            attempt("template " + c, () -> DateTimeFormatter.ofLocalizedPattern("yMMMd").withLocale(Locale.US).withChronology(c).format(LocalDate.of(2026, 3, 7)));
            attempt("week " + c, () -> DateTimeFormatter.ofPattern("YYYY-ww-e QQQ").withLocale(Locale.US).withChronology(c).format(LocalDate.of(2026, 3, 7)));
        }
        for (Chronology c : chronologies) {
            System.out.println("== read " + c);
            DateTimeFormatter date = DateTimeFormatter.ofPattern("uuuu-MM-dd").withChronology(c);
            System.out.println("date: " + read(date, "2126-03-07"));
            System.out.println("date 30: " + read(date, "2126-02-30"));
            System.out.println("date 30 strict: " + read(date.withResolverStyle(ResolverStyle.STRICT), "2126-02-30"));
            System.out.println("date 30 lenient: " + read(date.withResolverStyle(ResolverStyle.LENIENT), "2126-02-30"));
            System.out.println("yoe: " + read(DateTimeFormatter.ofPattern("yyyy-MM-dd").withChronology(c), "2126-03-07"));
            System.out.println("yoe strict: " + read(DateTimeFormatter.ofPattern("yyyy-MM-dd").withChronology(c).withResolverStyle(ResolverStyle.STRICT), "2126-03-07"));
            System.out.println("era: " + read(DateTimeFormatter.ofPattern("G yyyy-MM-dd", Locale.US).withChronology(c), "AD 2126-03-07"));
            System.out.println("era 1: " + read(DateTimeFormatter.ofPattern("G yyyy-MM-dd", Locale.US).withChronology(c), "1 2126-03-07"));
            System.out.println("time: " + read(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withChronology(c), "2126-03-07 10:15"));
            System.out.println("zoned: " + read(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV").withChronology(c), "2126-03-07 10:15 Europe/Paris"));
            System.out.println("offset: " + read(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm xxx").withChronology(c), "2126-03-07 10:15 +02:00"));
            System.out.println("override zone: " + read(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withChronology(c).withZone(paris), "2126-03-07 10:15"));
            System.out.println("epoch day: " + read(new DateTimeFormatterBuilder().appendValue(ChronoField.EPOCH_DAY).toFormatter().withChronology(c), "20519"));
            System.out.println("day of year: " + read(DateTimeFormatter.ofPattern("uuuu-DDD").withChronology(c), "2126-066"));
            System.out.println("instant: " + read(DateTimeFormatter.ISO_INSTANT.withChronology(c), "2026-03-07T10:15:30Z"));
            System.out.println("iso zoned: " + read(DateTimeFormatter.ISO_ZONED_DATE_TIME.withChronology(c), "2026-03-07T10:15:30+01:00[Europe/Paris]"));
            System.out.println("time only: " + read(DateTimeFormatter.ofPattern("HH:mm").withChronology(c), "10:15"));
            System.out.println("month: " + read(DateTimeFormatter.ofPattern("uuuu-MM").withChronology(c), "2126-03"));
            System.out.println("conflict: " + read(new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd ").appendValue(ChronoField.EPOCH_DAY).toFormatter().withChronology(c), "2126-03-07 1"));
            System.out.println("dow: " + read(DateTimeFormatter.ofPattern("uuuu-MM-dd e").withChronology(c), "2126-03-07 1"));
            System.out.println("dow ok: " + read(DateTimeFormatter.ofPattern("uuuu-MM-dd e").withChronology(c), "2126-03-07 7"));
        }
        attempt("unresolved", () -> DateTimeFormatter.ofPattern("uuuu-MM-dd").withChronology(shifted).parseUnresolved("2126-03-07", new java.text.ParsePosition(0)));
    }
}
