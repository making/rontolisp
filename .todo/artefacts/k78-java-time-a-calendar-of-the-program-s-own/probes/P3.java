import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

public class P3 {
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
        ZoneId paris = ZoneId.of("Europe/Paris");
        LocalDateTime ldt = LocalDateTime.of(2026, 3, 7, 10, 15, 30, 123000000);
        ChronoZonedDateTime<?> z = shifted.zonedDateTime(ZonedDateTime.of(ldt, paris));
        ZonedDateTime iso = ZonedDateTime.of(ldt, paris);
        System.out.println("text " + z + " hash " + z.hashCode() + " cand " + (z.toLocalDateTime().hashCode() ^ z.getOffset().hashCode() ^ Integer.rotateLeft(z.getZone().hashCode(), 3))
            + " iso hash " + iso.hashCode());
        System.out.println("parts " + z.toLocalDate() + " | " + z.toLocalTime() + " | " + z.toLocalDateTime() + " | " + z.getChronology()
            + " | " + z.getOffset() + " | " + z.getZone() + " | " + z.toEpochSecond() + " | " + z.toInstant());
        for (ChronoField f : ChronoField.values()) {
            attempt("field " + f + " " + z.isSupported(f), () -> z.getLong(f) + " " + z.range(f));
            attempt("get " + f, () -> z.get(f));
        }
        attempt("field null", () -> z.isSupported((TemporalField) null));
        attempt("field julian", () -> z.isSupported(JulianFields.JULIAN_DAY) + " " + z.getLong(JulianFields.JULIAN_DAY) + " " + z.range(JulianFields.JULIAN_DAY));
        for (ChronoUnit u : ChronoUnit.values()) {
            attempt("unit " + u + " " + z.isSupported(u), () -> z.plus(5, u) + " | " + z.minus(5, u));
            attempt("until " + u, () -> z.until(shifted.zonedDateTime(ZonedDateTime.of(2027, 5, 9, 1, 2, 3, 4, ZoneOffset.UTC)), u) + " "
                + z.until(ZonedDateTime.of(2025, 1, 9, 1, 2, 3, 4, ZoneId.of("Asia/Tokyo")), u));
        }
        for (ChronoField f : ChronoField.values()) {
            attempt("with " + f, () -> z.with(f, 2));
        }
        attempt("with instant", () -> z.with(ChronoField.INSTANT_SECONDS, 1000000000L));
        attempt("with offset 3600", () -> z.with(ChronoField.OFFSET_SECONDS, 3600));
        attempt("with offset 7200", () -> z.with(ChronoField.OFFSET_SECONDS, 7200));
        attempt("with adjuster", () -> z.with(TemporalAdjusters.lastDayOfMonth()));
        attempt("with a local date", () -> z.with(LocalDate.of(1999, 1, 1)));
        attempt("with a time", () -> z.with(LocalTime.MIDNIGHT));
        attempt("plus period", () -> z.plus(Period.of(1, 2, 3)));
        attempt("plus duration", () -> z.plus(Duration.ofHours(30)));
        attempt("plus shifted period", () -> z.plus(shifted.period(1, 2, 3)));
        attempt("withZoneSameInstant", () -> z.withZoneSameInstant(ZoneId.of("Asia/Tokyo")) + " " + z.withZoneSameInstant(ZoneOffset.UTC));
        attempt("withZoneSameLocal", () -> z.withZoneSameLocal(ZoneId.of("Asia/Tokyo")) + " " + z.withZoneSameLocal(ZoneOffset.UTC)
            + " " + z.withZoneSameLocal(paris));
        // a gap and an overlap in Paris: 2026-03-29 02:30 skipped, 2026-10-25 02:30 twice
        ChronoZonedDateTime<?> gap = shifted.localDateTime(LocalDateTime.of(2026, 3, 29, 2, 30)).atZone(paris);
        ChronoZonedDateTime<?> overlap = shifted.localDateTime(LocalDateTime.of(2026, 10, 25, 2, 30)).atZone(paris);
        attempt("gap", () -> gap + " " + gap.withEarlierOffsetAtOverlap() + " " + gap.withLaterOffsetAtOverlap());
        attempt("overlap", () -> overlap + " " + overlap.withEarlierOffsetAtOverlap() + " " + overlap.withLaterOffsetAtOverlap()
            + " " + overlap.withLaterOffsetAtOverlap().withEarlierOffsetAtOverlap());
        attempt("overlap plus hour", () -> overlap.plus(1, ChronoUnit.HOURS) + " " + overlap.withLaterOffsetAtOverlap().plus(1, ChronoUnit.DAYS)
            + " " + overlap.withLaterOffsetAtOverlap().with(ChronoField.MINUTE_OF_HOUR, 45));
        attempt("instant paris", () -> shifted.zonedDateTime(Instant.parse("2026-10-25T00:30:00Z"), paris) + " "
            + shifted.zonedDateTime(Instant.parse("2026-10-25T01:30:00Z"), paris));
        ChronoZonedDateTime<?> other = shifted.zonedDateTime(ZonedDateTime.of(ldt, ZoneOffset.ofHours(1)));
        attempt("compare", () -> z.compareTo(other) + " " + other.compareTo(z) + " " + z.compareTo(iso) + " " + iso.compareTo(z)
            + " " + z.isBefore(other) + " " + z.isAfter(other) + " " + z.isEqual(other) + " " + z.equals(other) + " "
            + z.equals(shifted.zonedDateTime(iso)) + " " + z.isEqual(iso));
        attempt("compare instants", () -> z.compareTo(ZonedDateTime.of(ldt, ZoneOffset.UTC)) + " "
            + z.compareTo(shifted.zonedDateTime(ZonedDateTime.of(ldt.plusNanos(1), paris))) + " "
            + z.compareTo(thin.date(2026, 3, 7).atTime(LocalTime.NOON).atZone(paris)));
        attempt("timeLineOrder", () -> ChronoZonedDateTime.timeLineOrder().compare(z, iso) + " " + ChronoZonedDateTime.timeLineOrder().compare(z, other));
        for (TemporalQuery<?> q : List.<TemporalQuery<?>>of(TemporalQueries.chronology(), TemporalQueries.precision(),
                TemporalQueries.localDate(), TemporalQueries.localTime(), TemporalQueries.zone(), TemporalQueries.zoneId(),
                TemporalQueries.offset())) {
            attempt("query " + q, () -> z.query(q));
        }
        attempt("from", () -> ChronoZonedDateTime.from(z) + " " + ChronoZonedDateTime.from(iso) + " " + ZonedDateTime.from(z)
            + " " + Instant.from(z) + " " + OffsetDateTime.from(z));
        attempt("format", () -> z.format(DateTimeFormatter.ISO_ZONED_DATE_TIME) + " " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm VV xxx").format(z));
        attempt("hash", () -> z.hashCode() + " " + other.hashCode());
        attempt("atZone utc hash", () -> shifted.localDateTime(ldt).atZone(ZoneOffset.UTC).hashCode() + " " + ZonedDateTime.of(ldt, ZoneOffset.UTC).hashCode());
    }
}
