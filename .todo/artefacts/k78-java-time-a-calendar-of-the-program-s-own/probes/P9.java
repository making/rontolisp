import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P9 {
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

    static final class Mine extends AbstractChronology {
        final String type;
        Mine(String type) { this.type = type; }
        public String getId() { return "Mine"; }
        public String getCalendarType() { return type; }
        public ChronoLocalDate date(int y, int m, int d) { return Shifted.INSTANCE.date(y + 100, m, d); }
        public ChronoLocalDate dateYearDay(int y, int d) { return Shifted.INSTANCE.dateYearDay(y + 100, d); }
        public ChronoLocalDate dateEpochDay(long e) { return Shifted.INSTANCE.dateEpochDay(e); }
        public ChronoLocalDate date(TemporalAccessor t) { return Shifted.INSTANCE.date(t); }
        public boolean isLeapYear(long y) { return Year.isLeap(y); }
        public int prolepticYear(Era era, int yoe) { return era == IsoEra.CE ? yoe : 1 - yoe; }
        public Era eraOf(int v) { return IsoEra.of(v); }
        public List<Era> eras() { return List.of(IsoEra.BCE, IsoEra.CE); }
        public ValueRange range(ChronoField f) { return f == ChronoField.MONTH_OF_YEAR ? ValueRange.of(1, 12, 13) : f.range(); }
    }

    public static void main(String[] args) {
        Shifted shifted = Shifted.INSTANCE;
        Thin thin = new Thin();
        ChronoLocalDate d = shifted.date(2126, 3, 7);
        ChronoLocalDateTime<?> s = d.atTime(LocalTime.of(10, 15));
        ZoneId paris = ZoneId.of("Europe/Paris");
        ChronoZonedDateTime<?> z = s.atZone(paris);
        // (a) the checks of the defaults
        chain("date adjuster to iso", () -> d.with(t -> LocalDate.of(2000, 1, 1)));
        chain("date adjuster to time", () -> d.with(t -> LocalTime.NOON));
        chain("date amount to iso", () -> d.plus(new TemporalAmount() {
            public long get(TemporalUnit u) { return 0; }
            public List<TemporalUnit> getUnits() { return List.of(); }
            public Temporal addTo(Temporal t) { return LocalDate.of(2000, 1, 1); }
            public Temporal subtractFrom(Temporal t) { return LocalTime.NOON; }
        }));
        chain("date amount minus to time", () -> d.minus(new TemporalAmount() {
            public long get(TemporalUnit u) { return 0; }
            public List<TemporalUnit> getUnits() { return List.of(); }
            public Temporal addTo(Temporal t) { return LocalDate.of(2000, 1, 1); }
            public Temporal subtractFrom(Temporal t) { return LocalTime.NOON; }
        }));
        chain("ldt adjuster to iso ldt", () -> s.with(t -> LocalDateTime.of(2000, 1, 1, 0, 0)));
        chain("ldt adjuster to time", () -> s.with(t -> LocalTime.NOON));
        chain("ldt adjuster to date-time of shifted", () -> s.with(t -> shifted.localDateTime(LocalDateTime.of(2000, 1, 1, 0, 0))));
        chain("ldt amount to iso ldt", () -> s.plus(new TemporalAmount() {
            public long get(TemporalUnit u) { return 0; }
            public List<TemporalUnit> getUnits() { return List.of(); }
            public Temporal addTo(Temporal t) { return LocalDateTime.of(2000, 1, 1, 0, 0); }
            public Temporal subtractFrom(Temporal t) { return LocalDate.of(2000, 1, 1); }
        }));
        chain("ldt amount minus to date", () -> s.minus(new TemporalAmount() {
            public long get(TemporalUnit u) { return 0; }
            public List<TemporalUnit> getUnits() { return List.of(); }
            public Temporal addTo(Temporal t) { return LocalDateTime.of(2000, 1, 1, 0, 0); }
            public Temporal subtractFrom(Temporal t) { return LocalDate.of(2000, 1, 1); }
        }));
        chain("zdt adjuster to iso zdt", () -> z.with(t -> ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, paris)));
        chain("zdt adjuster to ldt", () -> z.with(t -> LocalDateTime.of(2000, 1, 1, 0, 0)));
        chain("zdt adjuster to iso date", () -> z.with(t -> LocalDate.of(2000, 1, 1)));
        chain("zdt amount to iso zdt", () -> z.plus(new TemporalAmount() {
            public long get(TemporalUnit u) { return 0; }
            public List<TemporalUnit> getUnits() { return List.of(); }
            public Temporal addTo(Temporal t) { return ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, paris); }
            public Temporal subtractFrom(Temporal t) { return LocalDate.of(2000, 1, 1); }
        }));
        chain("zdt with offset out of range", () -> z.with(ChronoField.OFFSET_SECONDS, 100000));
        chain("zdt with instant out of range", () -> z.with(ChronoField.INSTANT_SECONDS, Long.MAX_VALUE));
        chain("ldt with nano out of range", () -> s.with(ChronoField.NANO_OF_SECOND, -1));
        chain("ldt until a time", () -> s.until(LocalTime.NOON, ChronoUnit.HOURS));
        chain("ldt until a date", () -> s.until(LocalDate.of(2026, 3, 9), ChronoUnit.HOURS));
        chain("ldt until thin", () -> s.until(thin.date(2026, 3, 9).atTime(LocalTime.NOON), ChronoUnit.HOURS));
        chain("ldt until zdt", () -> s.until(ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, paris), ChronoUnit.HOURS));
        chain("zdt until ldt", () -> z.until(LocalDateTime.of(2026, 3, 9, 12, 0), ChronoUnit.HOURS));
        chain("zdt until thin", () -> z.until(ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, paris).toOffsetDateTime(), ChronoUnit.HOURS));
        chain("ldt until quarter", () -> s.until(shifted.localDateTime(LocalDateTime.of(2027, 3, 9, 12, 0)), IsoFields.QUARTER_YEARS));
        chain("ldt until null", () -> s.until(null, ChronoUnit.HOURS));
        // (b) the order of a comparison
        chain("compare date time chrono", () -> s.compareTo(LocalDateTime.of(2026, 3, 7, 12, 0)) + " " + s.compareTo(LocalDateTime.of(2026, 3, 7, 8, 0))
            + " " + LocalDateTime.of(2026, 3, 7, 12, 0).compareTo(s) + " " + s.compareTo(LocalDateTime.of(2026, 3, 7, 10, 15)));
        chain("compare zoned", () -> z.compareTo(ZonedDateTime.of(2026, 3, 7, 10, 15, 0, 0, ZoneId.of("Europe/Berlin"))) + " "
            + ZonedDateTime.of(2026, 3, 7, 10, 15, 0, 0, ZoneId.of("Europe/Berlin")).compareTo(z) + " "
            + z.compareTo(ZonedDateTime.of(2026, 3, 7, 10, 15, 0, 0, paris)) + " " + ZonedDateTime.of(2026, 3, 7, 10, 15, 0, 0, paris).compareTo(z)
            + " " + z.compareTo(ZonedDateTime.of(2026, 3, 7, 10, 15, 0, 7, paris)) + " " + z.compareTo(ZonedDateTime.of(2026, 3, 7, 9, 15, 0, 0, ZoneOffset.UTC)));
        chain("equals", () -> s.equals(LocalDateTime.of(2026, 3, 7, 10, 15)) + " " + LocalDateTime.of(2026, 3, 7, 10, 15).equals(s) + " "
            + z.equals(ZonedDateTime.of(2026, 3, 7, 10, 15, 0, 0, paris)) + " " + s.equals(null) + " " + s.equals("x") + " " + z.equals(null));
        chain("iso isEqual", () -> LocalDateTime.of(2026, 3, 7, 10, 15).isEqual(s) + " " + LocalDateTime.of(2026, 3, 7, 10, 16).isAfter(s)
            + " " + ZonedDateTime.of(2026, 3, 7, 10, 15, 0, 0, paris).isEqual(z) + " " + OffsetDateTime.of(2026, 3, 7, 10, 15, 0, 0, ZoneOffset.ofHours(1)).toInstant().equals(z.toInstant()));
        // (c) the words of an era, a month
        for (Chronology c : new Chronology[] {IsoChronology.INSTANCE, thin, shifted, new Mine("iso8601"), new Mine("thin"), new Mine(null)}) {
            chain("era words " + c + " " + c.getCalendarType(), () -> DateTimeFormatter.ofPattern("G GGGG GGGGG MMMM EEEE a", Locale.US).withChronology(c).format(LocalDateTime.of(2026, 3, 7, 10, 0)));
            chain("era read " + c, () -> DateTimeFormatter.ofPattern("G uuuu MMMM dd", Locale.US).withChronology(c).parse("AD 2126 March 07"));
            chain("era read number " + c, () -> DateTimeFormatter.ofPattern("G uuuu MMMM dd", Locale.US).withChronology(c).parse("1 2126 March 07"));
            chain("era read lenient " + c, () -> new DateTimeFormatterBuilder().parseLenient().appendPattern("G uuuu MMMM dd").toFormatter(Locale.US).withChronology(c).parse("AD 2126 March 07"));
        }
        chain("era of a value", () -> DateTimeFormatter.ofPattern("G", Locale.US).format(new Mine("iso8601").date(2026, 3, 7)));
        // (d) from
        chain("ldt from date", () -> ChronoLocalDateTime.from(d));
        chain("ldt from parsed date", () -> ChronoLocalDateTime.from(DateTimeFormatter.ofPattern("uuuu-MM-dd").withChronology(shifted).parse("2126-03-07")));
        chain("ldt from instant", () -> ChronoLocalDateTime.from(Instant.EPOCH));
        chain("ldt from null", () -> ChronoLocalDateTime.from(null));
        chain("zdt from ldt", () -> ChronoZonedDateTime.from(s));
        chain("zdt from instant", () -> ChronoZonedDateTime.from(Instant.EPOCH));
        chain("zdt from null", () -> ChronoZonedDateTime.from(null));
        chain("zdt from odt", () -> ChronoZonedDateTime.from(OffsetDateTime.of(2026, 3, 7, 10, 0, 0, 0, ZoneOffset.UTC)));
        chain("date from time", () -> ChronoLocalDate.from(LocalTime.NOON));
        chain("ldt timeLineOrder null", () -> ChronoLocalDateTime.timeLineOrder().compare(s, null));
        // (e) nulls
        chain("zonedDateTime null null", () -> shifted.zonedDateTime(null, null));
        chain("thin zonedDateTime null null", () -> thin.zonedDateTime(null, null));
        chain("atZone null", () -> s.atZone(null));
        chain("atTime null", () -> d.atTime(null));
        chain("period plus null", () -> shifted.period(1, 2, 3).minus(null));
        chain("period addTo null", () -> shifted.period(1, 2, 3).addTo(null));
        chain("withZoneSameInstant null", () -> z.withZoneSameInstant(null));
        chain("withZoneSameLocal null", () -> z.withZoneSameLocal(null));
        // (f) months of no fixed count
        Mine mine = new Mine("mine");
        chain("normalized", () -> mine.period(1, 14, 3).normalized() + " " + mine.period(1, 14, 3).addTo(mine.date(2026, 1, 31)));
        chain("normalized iso", () -> Period.of(1, 14, 3).normalized());
        chain("leap addTo", () -> shifted.period(1, -1, 0).addTo(shifted.date(2124, 2, 29)) + " " + mine.period(1, -1, 0).addTo(mine.date(2024, 2, 29)));
        // (g) a zone the value lacks
        chain("zone of a shifted date", () -> DateTimeFormatter.ofPattern("VV").format(d));
        chain("zone of a date by shifted", () -> DateTimeFormatter.ofPattern("VV").withChronology(shifted).format(LocalDate.of(2026, 3, 7)));
        chain("zone of a shifted date by iso", () -> DateTimeFormatter.ofPattern("VV").withChronology(IsoChronology.INSTANCE).format(d));
        chain("zone of a time by shifted", () -> DateTimeFormatter.ofPattern("VV").withChronology(shifted).format(LocalTime.NOON));
        chain("zone of a time by iso", () -> DateTimeFormatter.ofPattern("VV").withChronology(IsoChronology.INSTANCE).format(LocalTime.NOON));
        chain("zone of a ym by iso", () -> DateTimeFormatter.ofPattern("VV").withChronology(IsoChronology.INSTANCE).format(YearMonth.of(2026, 3)));
        chain("zone of a date by thin", () -> DateTimeFormatter.ofPattern("VV").withChronology(thin).format(LocalDate.of(2026, 3, 7)));
        chain("zone of an ldt by shifted", () -> DateTimeFormatter.ofPattern("VV").withChronology(shifted).format(LocalDateTime.of(2026, 3, 7, 10, 0)));
        chain("offset zone of a date", () -> DateTimeFormatter.ofPattern("VV").withChronology(shifted).withZone(ZoneOffset.UTC).format(LocalDate.of(2026, 3, 7)));
        chain("zone with zone", () -> DateTimeFormatter.ofPattern("uuuu VV").withChronology(shifted).withZone(paris).format(LocalDate.of(2026, 3, 7)));
        chain("zone with zone offset", () -> DateTimeFormatter.ofPattern("uuuu xxx").withChronology(shifted).withZone(paris).format(LocalDate.of(2026, 3, 7)));
        chain("odt zone override", () -> DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV").withChronology(shifted).withZone(paris).format(OffsetDateTime.of(2026, 3, 7, 10, 0, 0, 0, ZoneOffset.UTC)));
        // (i) an instant read with a zone
        chain("instant zone", () -> DateTimeFormatter.ISO_INSTANT.withZone(paris).withChronology(shifted).parse("2026-03-07T10:15:30Z"));
        chain("instant offset", () -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.withChronology(shifted).parse("2126-03-07T10:15:30+02:00"));
        // (j) hashes
        ChronoPeriod p = shifted.period(1, 2, 3);
        System.out.println("hash " + (p.hashCode() == ((1 + Integer.rotateLeft(2, 8) + Integer.rotateLeft(3, 16)) ^ shifted.hashCode()))
            + " " + (shifted.period(-7, 300, -40000).hashCode() == ((-7 + Integer.rotateLeft(300, 8) + Integer.rotateLeft(-40000, 16)) ^ shifted.hashCode())));
        System.out.println("chrono hash " + (shifted.hashCode() == (Shifted.class.hashCode() ^ "Shifted".hashCode())));
    }
}
