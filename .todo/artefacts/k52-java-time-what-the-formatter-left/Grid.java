// The probe k52 learned what the formatter left from: the calendar and its eras, a month
// and a day, a time with an offset, the sources of instants, the adjusters, the dates
// until another, the quarters, week-based years, Julian days and weeks of a locale, the
// pattern letters of weeks, quarters and the modified Julian day, the resolving of the
// fields that are no ChronoField (a program's own too), the runs of numbers read strictly
// and leniently, the display names. Every line is what a JDK 25 prints, and the
// interpreter, the JVM, wasm and the component print the same lines. Written here;
// nothing of a JDK is in it. What a JDK answers in an order that changes from run to run
// (the fields of a map, which of two fields that are no ChronoField resolves first) is
// printed sorted or not asked (README.md).
import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Grid {

    public static void main(String[] args) {
        Chrono.main(args);
        Smaller.main(args);
        Adjust.main(args);
        Fields.main(args);
        Fields2.main(args);
        Seeded.main(args);
        Runs.main(args);
        Letters.main(args);
        Resolve.main(args);
        Mixed.main(args);
        Widths.main(args);
        Owned.main(args);
        Forever.main(args);
        Names.main(args);
    }

    static class Chrono {
        static void a(String what, Supplier<Object> s) {
            try {
                System.out.println(what + " = " + s.get());
            } catch (DateTimeParseException e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage() + " @" + e.getErrorIndex());
            } catch (RuntimeException e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        public static void main(String[] args) {
            Chronology iso = IsoChronology.INSTANCE;
            a("iso", () -> iso);
            a("iso id", () -> iso.getId() + " " + iso.getCalendarType() + " " + iso.isIsoBased());
            a("iso eras", () -> iso.eras());
            a("iso eraOf 0", () -> iso.eraOf(0));
            a("iso eraOf 1", () -> iso.eraOf(1));
            a("iso eraOf 2", () -> iso.eraOf(2));
            a("isoera", () -> Arrays.toString(IsoEra.values()) + " " + IsoEra.of(0) + " " + IsoEra.of(1) + " " + IsoEra.CE.getValue() + " " + IsoEra.BCE.getValue());
            a("isoera of -1", () -> IsoEra.of(-1));
            for (TextStyle st : TextStyle.values()) {
                for (Locale l : new Locale[] {Locale.ROOT, Locale.ENGLISH, Locale.US, Locale.UK, Locale.CANADA}) {
                    a("era name " + st + " " + l, () -> IsoEra.CE.getDisplayName(st, l) + "/" + IsoEra.BCE.getDisplayName(st, l) + " chrono " + iso.getDisplayName(st, l));
                }
            }
            a("era get", () -> IsoEra.CE.get(ChronoField.ERA) + " " + IsoEra.CE.getLong(ChronoField.ERA) + " " + IsoEra.CE.range(ChronoField.ERA) + " " + IsoEra.CE.isSupported(ChronoField.ERA) + " " + IsoEra.CE.isSupported(ChronoField.YEAR) + " " + IsoEra.CE.isSupported(null));
            a("era get year", () -> IsoEra.CE.getLong(ChronoField.YEAR));
            a("era range year", () -> IsoEra.CE.range(ChronoField.YEAR));
            a("era query", () -> IsoEra.CE.query(TemporalQueries.precision()) + " " + IsoEra.CE.query(TemporalQueries.chronology()) + " " + IsoEra.CE.query(TemporalQueries.zoneId()) + " " + IsoEra.CE.query(TemporalQueries.localDate()));
            a("era adjust", () -> IsoEra.BCE.adjustInto(LocalDate.of(2020, 3, 4)) + " " + LocalDate.of(2020, 3, 4).with(IsoEra.BCE));
            a("iso date", () -> iso.date(2020, 2, 29) + " " + iso.date(IsoEra.BCE, 5, 1, 2) + " " + iso.dateYearDay(2020, 60) + " " + iso.dateYearDay(IsoEra.BCE, 3, 60) + " " + iso.dateEpochDay(100));
            a("iso date bad", () -> iso.date(2021, 2, 29));
            a("iso dateYearDay bad", () -> iso.dateYearDay(2021, 366));
            a("iso date from", () -> iso.date(LocalDateTime.of(2020, 1, 2, 3, 4)) + " " + iso.localDateTime(ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 6, ZoneOffset.UTC)) + " " + iso.zonedDateTime(OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 6, ZoneOffset.ofHours(2))));
            a("iso date from time", () -> iso.date(LocalTime.NOON));
            a("iso zdt instant", () -> iso.zonedDateTime(Instant.EPOCH, ZoneOffset.ofHours(9)));
            a("iso leap", () -> iso.isLeapYear(2000) + " " + iso.isLeapYear(1900) + " " + iso.isLeapYear(-4) + " " + iso.isLeapYear(2024));
            a("iso proleptic", () -> iso.prolepticYear(IsoEra.CE, 5) + " " + iso.prolepticYear(IsoEra.BCE, 5));
            for (ChronoField f : ChronoField.values()) {
                a("iso range " + f, () -> iso.range(f));
            }
            a("iso period", () -> iso.period(1, 2, 3) + " " + iso.period(0, 0, 0).getChronology());
            a("iso epochSecond", () -> iso.epochSecond(2020, 1, 2, 3, 4, 5, ZoneOffset.ofHours(1)) + " " + iso.epochSecond(IsoEra.BCE, 1, 1, 1, 0, 0, 0, ZoneOffset.UTC));
            int[][] cases = {{1000000000, 13, 32, 25, 61, 61}, {2021, 12, 32, 25, 61, 61}, {2021, 2, 29, 23, 59, 60}, {2021, 2, 29, 23, 59, 59}, {2020, 4, 31, 0, 0, 0}, {2020, 13, 1, 0, 0, 0}, {2020, 1, 1, 24, 0, 0}, {2020, 1, 1, 0, 60, 0}};
            for (int[] c : cases) {
                a("epochSecond " + Arrays.toString(c), () -> iso.epochSecond(c[0], c[1], c[2], c[3], c[4], c[5], ZoneOffset.UTC));
            }
            a("chrono of", () -> Chronology.of("ISO") + " " + Chronology.ofLocale(Locale.US) + " " + Chronology.ofLocale(Locale.ROOT) + " " + Chronology.from(LocalDate.EPOCH) + " " + (Chronology.of("iso8601") == iso));
            a("chrono of bad", () -> Chronology.of("Nope"));
            a("chrono from time", () -> Chronology.from(LocalTime.NOON));
            a("chrono compare", () -> iso.compareTo(IsoChronology.INSTANCE) + " " + iso.equals(IsoChronology.INSTANCE) + " " + iso.equals("ISO"));
            a("chrono dateNow", () -> iso.dateNow(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
            LocalDate d = LocalDate.of(2026, 3, 7);
            a("getChronology", () -> d.getChronology() + " " + d.getEra() + " " + LocalDate.of(0, 1, 1).getEra() + " " + LocalDateTime.of(d, LocalTime.NOON).getChronology() + " " + ZonedDateTime.of(d, LocalTime.NOON, ZoneOffset.UTC).getChronology());
            a("cld", () -> { ChronoLocalDate c = d; return c.getChronology() + " " + c.getEra() + " " + c.isSupported(ChronoUnit.DAYS) + " " + c.lengthOfMonth() + " " + c.until(LocalDate.of(2027, 1, 1)); });
            a("cld until", () -> { ChronoLocalDate c = d; ChronoPeriod p = c.until(LocalDate.of(2027, 1, 1)); return p.getChronology() + " " + p.getUnits() + " " + p.get(ChronoUnit.MONTHS); });
            a("cld from time", () -> ChronoLocalDate.from(LocalTime.NOON));
            a("cld from ldt", () -> ChronoLocalDate.from(LocalDateTime.of(2020, 1, 1, 1, 1)));
            a("cld order", () -> ChronoLocalDate.timeLineOrder().compare(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 5)));
            a("cld atTime", () -> { ChronoLocalDate c = LocalDate.of(2020, 1, 1); return c.atTime(LocalTime.NOON) + " " + c.format(DateTimeFormatter.ISO_LOCAL_DATE); });
            List<TemporalAccessor> ts = List.of(d, LocalTime.NOON, LocalDateTime.of(d, LocalTime.NOON), OffsetDateTime.of(d, LocalTime.NOON, ZoneOffset.UTC), ZonedDateTime.of(d, LocalTime.NOON, ZoneOffset.UTC), Instant.EPOCH, Year.of(2020), YearMonth.of(2020, 1), ZoneOffset.UTC, DayOfWeek.MONDAY, Month.MAY, IsoEra.CE);
            for (TemporalAccessor t : ts) {
                a("query " + t, () -> t.query(TemporalQueries.chronology()) + " precision " + t.query(TemporalQueries.precision()) + " zone " + t.query(TemporalQueries.zone()) + " zoneId " + t.query(TemporalQueries.zoneId()) + " offset " + t.query(TemporalQueries.offset()) + " date " + t.query(TemporalQueries.localDate()) + " time " + t.query(TemporalQueries.localTime()));
            }
            a("query names", () -> TemporalQueries.chronology() + " " + TemporalQueries.zone() + " " + TemporalQueries.precision());
            DateTimeFormatter f = DateTimeFormatter.ofPattern("uuuu-MM-dd");
            a("fmt chrono", () -> f.getChronology() + " " + DateTimeFormatter.ISO_LOCAL_DATE.getChronology() + " " + DateTimeFormatter.ISO_INSTANT.getChronology() + " " + DateTimeFormatter.BASIC_ISO_DATE.getChronology() + " " + DateTimeFormatter.RFC_1123_DATE_TIME.getChronology() + " " + DateTimeFormatter.ISO_LOCAL_TIME.getChronology());
            a("fmt with chrono", () -> f.withChronology(iso).getChronology() + " " + (f.withChronology(null) == f) + " " + f.withChronology(iso));
            a("fmt parse chrono", () -> f.parse("2020-01-02").query(TemporalQueries.chronology()) + " " + f.parse("2020-01-02"));
            a("fmt parse time chrono", () -> DateTimeFormatter.ofPattern("HH:mm").parse("10:20").query(TemporalQueries.chronology()));
            a("fmt format with chrono", () -> f.withChronology(iso).format(d));
            ChronoPeriod p = Period.of(1, 2, 3);
            a("cp", () -> p.getChronology() + " " + p.getUnits() + " " + p.get(ChronoUnit.DAYS) + " " + p.isZero() + " " + p.isNegative() + " " + p.plus(Period.ofDays(1)) + " " + p.minus(Period.ofMonths(1)) + " " + p.multipliedBy(2) + " " + p.negated() + " " + p.normalized() + " " + p.addTo(LocalDate.of(2020, 1, 1)) + " " + p.subtractFrom(LocalDate.of(2020, 1, 1)) + " " + p.equals(Period.of(1, 2, 3)) + " " + p.hashCode() + " " + p);
            a("cp between", () -> ChronoPeriod.between(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 3, 4)));
            a("cp plus dur", () -> p.plus(Duration.ofDays(1)));
        }
    }

    static class Smaller {
        static String sorted(String s) {
            int open = s.indexOf('{');
            int close = s.indexOf('}');
            if (open < 0 || close < open) return s;
            String inner = s.substring(open + 1, close);
            List<String> parts = new ArrayList<>(inner.isEmpty() ? List.of() : Arrays.asList(inner.split(", ")));
            Collections.sort(parts);
            return s.substring(0, open) + "{" + String.join(", ", parts) + "}" + sorted(s.substring(close + 1));
        }
        static void a(String what, Supplier<Object> s) {
            try {
                System.out.println(what + " = " + s.get());
            } catch (DateTimeParseException e) {
                System.out.println(sorted(what + " ! " + e.getClass().getName() + ": " + e.getMessage() + " @" + e.getErrorIndex() + (e.getCause() == null ? "" : " <- " + e.getCause())));
            } catch (Exception e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        public static void main(String[] args) {
            // MonthDay
            a("md", () -> MonthDay.of(2, 29) + " " + MonthDay.of(Month.DECEMBER, 3) + " " + MonthDay.of(1, 1).hashCode() + " " + MonthDay.of(12, 31).hashCode() + " " + MonthDay.of(2, 29).hashCode() + " " + MonthDay.of(7, 9).hashCode());
            for (int[] md : new int[][] {{0, 1}, {13, 1}, {1, 0}, {1, 32}, {2, 30}, {4, 31}, {2, 29}, {6, 31}, {11, 31}, {-1, 1}}) {
                a("md of " + md[0] + " " + md[1], () -> MonthDay.of(md[0], md[1]));
            }
            for (String t : new String[] {"--12-03", "--02-29", "--02-30", "--13-01", "--00-01", "--1-01", "--12-3", "12-03", "--12-03x", "--12/03", "-12-03", "--04-31", "", "--", "--12-", "--12-00", "--12-32", "--99-99", "--+1-01", "--12-03T"}) {
                a("md parse '" + t + "'", () -> MonthDay.parse(t));
            }
            a("md parse fmt", () -> MonthDay.parse("03/12", DateTimeFormatter.ofPattern("dd/MM")));
            a("md parse fmt2", () -> MonthDay.parse("2020-03-12", DateTimeFormatter.ISO_LOCAL_DATE));
            a("md parse fmt3", () -> MonthDay.parse("12", DateTimeFormatter.ofPattern("MM")));
            MonthDay m = MonthDay.of(2, 29);
            for (ChronoField f : ChronoField.values()) {
                a("md field " + f, () -> m.isSupported(f) + " " + (m.isSupported(f) ? m.range(f) + " " + m.get(f) + " " + m.getLong(f) : ""));
            }
            a("md unsupported get", () -> m.get(ChronoField.YEAR));
            a("md unsupported getLong", () -> m.getLong(ChronoField.HOUR_OF_DAY));
            a("md unsupported range", () -> m.range(ChronoField.YEAR));
            a("md isSupported null", () -> m.isSupported(null));
            a("md range dom", () -> MonthDay.of(4, 3).range(ChronoField.DAY_OF_MONTH) + " " + MonthDay.of(2, 3).range(ChronoField.DAY_OF_MONTH) + " " + MonthDay.of(1, 3).range(ChronoField.DAY_OF_MONTH) + " " + MonthDay.of(1, 3).range(ChronoField.MONTH_OF_YEAR));
            a("md getters", () -> m.getMonth() + " " + m.getMonthValue() + " " + m.getDayOfMonth() + " " + m.isValidYear(2020) + " " + m.isValidYear(2021) + " " + MonthDay.of(3, 1).isValidYear(2021));
            a("md with", () -> m.withMonth(4) + " " + m.withMonth(1) + " " + m.with(Month.JUNE) + " " + m.withDayOfMonth(1) + " " + MonthDay.of(1, 31).withMonth(2) + " " + MonthDay.of(1, 31).withMonth(4) + " " + MonthDay.of(3, 31).with(Month.FEBRUARY));
            a("md withMonth 13", () -> m.withMonth(13));
            a("md withDay 30", () -> m.withDayOfMonth(30));
            a("md withDay 0", () -> m.withDayOfMonth(0));
            a("md withDay 32", () -> m.withDayOfMonth(32));
            a("md same", () -> (m.withMonth(2) == m) + " " + (m.withDayOfMonth(29) == m) + " " + (m.with(Month.FEBRUARY) == m));
            a("md atYear", () -> m.atYear(2020) + " " + m.atYear(2021) + " " + MonthDay.of(3, 1).atYear(-5));
            a("md atYear bad", () -> m.atYear(1000000000));
            a("md query", () -> m.query(TemporalQueries.chronology()) + " " + m.query(TemporalQueries.precision()) + " " + m.query(TemporalQueries.localDate()));
            a("md adjust", () -> m.adjustInto(LocalDate.of(2021, 5, 5)) + " " + m.adjustInto(LocalDate.of(2020, 5, 5)) + " " + LocalDate.of(2021, 1, 1).with(m) + " " + LocalDateTime.of(2021, 1, 1, 5, 5).with(m) + " " + MonthDay.of(4, 30).adjustInto(LocalDate.of(2020, 1, 31)));
            a("md adjust time", () -> m.adjustInto(LocalTime.NOON));
            a("md compare", () -> m.compareTo(MonthDay.of(3, 1)) + " " + m.compareTo(MonthDay.of(2, 1)) + " " + m.compareTo(MonthDay.of(1, 30)) + " " + MonthDay.of(12, 31).compareTo(MonthDay.of(1, 1)) + " " + m.isAfter(MonthDay.of(1, 1)) + " " + m.isBefore(MonthDay.of(1, 1)) + " " + m.equals(MonthDay.of(2, 29)) + " " + m.equals(null) + " " + m.equals("--02-29"));
            a("md format", () -> m.format(DateTimeFormatter.ofPattern("MMM d")) + " " + DateTimeFormatter.ofPattern("MM-dd").format(m));
            a("md format year", () -> m.format(DateTimeFormatter.ofPattern("uuuu MMM d")));
            a("md from", () -> MonthDay.from(LocalDate.of(2020, 5, 6)) + " " + MonthDay.from(LocalDateTime.of(2020, 5, 6, 1, 1)) + " " + MonthDay.from(ZonedDateTime.of(2020, 5, 6, 1, 1, 0, 0, ZoneOffset.UTC)) + " " + MonthDay.from(MonthDay.of(1, 2)));
            a("md from time", () -> MonthDay.from(LocalTime.NOON));
            a("md from ym", () -> MonthDay.from(YearMonth.of(2020, 1)));
            a("md now", () -> MonthDay.now(Clock.fixed(Instant.parse("2020-02-29T23:00:00Z"), ZoneOffset.ofHours(2))));
            // Year with MonthDay
            a("year md", () -> Year.of(2020).isValidMonthDay(m) + " " + Year.of(2021).isValidMonthDay(m) + " " + Year.of(2021).isValidMonthDay(null) + " " + Year.of(2020).atMonthDay(m) + " " + Year.of(2021).atMonthDay(m) + " " + Year.of(2021).atMonthDay(MonthDay.of(12, 31)));
            // OffsetTime
            ZoneOffset p2 = ZoneOffset.ofHours(2);
            OffsetTime ot = OffsetTime.of(10, 15, 30, 500, p2);
            a("ot", () -> ot + " " + OffsetTime.MIN + " " + OffsetTime.MAX + " " + OffsetTime.of(LocalTime.NOON, ZoneOffset.UTC) + " " + LocalTime.of(1, 2).atOffset(p2) + " " + OffsetTime.of(LocalTime.of(10, 15, 30, 120000000), ZoneOffset.ofHoursMinutesSeconds(-1, -2, -3)));
            a("ot hash", () -> ot.hashCode() + " " + OffsetTime.MIN.hashCode() + " " + OffsetTime.MAX.hashCode() + " " + OffsetTime.of(LocalTime.NOON, ZoneOffset.UTC).hashCode() + " " + LocalTime.NOON.hashCode());
            a("ot of bad", () -> OffsetTime.of(24, 0, 0, 0, p2));
            for (String t : new String[] {"10:15+01:00", "10:15:30+01:00", "10:15:30.5-01:30", "10:15Z", "10:15z", "10:15", "10:15+1", "10:15+01", "10:15+0100", "10:15+01:00:30", "25:00+01:00", "10:61+01:00", "10:15+19:00", "10:15+24:00", "10:15+18:00", "T10:15Z", "10:15:30.123456789+01:00", "10:15:30.1234567891+01:00", "10:15Zx", "10:15:3Z", "24:00Z", "10:15 Z", "10:15+01:00[Europe/Paris]"}) {
                a("ot parse '" + t + "'", () -> OffsetTime.parse(t));
            }
            a("ot parse fmt", () -> OffsetTime.parse("10:15 +0100", DateTimeFormatter.ofPattern("HH:mm xx")));
            a("ot parse fmt iso", () -> OffsetTime.parse("10:15+01:00", DateTimeFormatter.ISO_OFFSET_TIME) + " " + DateTimeFormatter.ISO_OFFSET_TIME.parse("10:15+01:00", OffsetTime::from) + " " + DateTimeFormatter.ISO_TIME.format(ot) + " " + DateTimeFormatter.ISO_OFFSET_TIME.format(ot));
            a("ot from", () -> OffsetTime.from(OffsetDateTime.of(2020, 1, 1, 5, 6, 7, 8, p2)) + " " + OffsetTime.from(ZonedDateTime.of(2020, 1, 1, 5, 6, 7, 8, p2)) + " " + OffsetTime.from(ot));
            a("ot from ldt", () -> OffsetTime.from(LocalDateTime.of(2020, 1, 1, 5, 6)));
            a("ot from instant", () -> OffsetTime.from(Instant.EPOCH));
            a("ot ofInstant", () -> OffsetTime.ofInstant(Instant.ofEpochSecond(86400L * 365 + 3661, 5), ZoneOffset.ofHours(-5)) + " " + OffsetTime.ofInstant(Instant.MAX, ZoneOffset.UTC) + " " + OffsetTime.ofInstant(Instant.MIN, ZoneOffset.MAX));
            a("ot now", () -> OffsetTime.now(Clock.fixed(Instant.parse("2020-02-29T23:00:00Z"), ZoneOffset.ofHours(2))));
            for (ChronoField f : ChronoField.values()) {
                a("ot field " + f, () -> ot.isSupported(f) + " " + (ot.isSupported(f) ? ot.range(f) + " " + ot.getLong(f) : ""));
            }
            for (ChronoField f : new ChronoField[] {ChronoField.NANO_OF_DAY, ChronoField.MICRO_OF_DAY, ChronoField.OFFSET_SECONDS, ChronoField.YEAR}) {
                a("ot get " + f, () -> ot.get(f));
            }
            for (ChronoUnit u : ChronoUnit.values()) {
                a("ot unit " + u, () -> ot.isSupported(u) + " " + (ot.isSupported(u) ? ot.plus(5, u) + " " + ot.until(OffsetTime.of(23, 59, 59, 999999999, ZoneOffset.UTC), u) : ""));
            }
            a("ot plus days", () -> ot.plus(1, ChronoUnit.DAYS));
            a("ot until days", () -> ot.until(ot, ChronoUnit.DAYS));
            a("ot until ldt", () -> ot.until(OffsetDateTime.of(2020, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC), ChronoUnit.MINUTES));
            a("ot until lt", () -> ot.until(LocalTime.NOON, ChronoUnit.MINUTES));
            a("ot getters", () -> ot.getHour() + " " + ot.getMinute() + " " + ot.getSecond() + " " + ot.getNano() + " " + ot.getOffset() + " " + ot.toLocalTime());
            a("ot withOffset", () -> ot.withOffsetSameLocal(ZoneOffset.UTC) + " " + ot.withOffsetSameInstant(ZoneOffset.UTC) + " " + ot.withOffsetSameInstant(ZoneOffset.ofHours(-14 + 2)) + " " + OffsetTime.of(23, 0, 0, 0, ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(3)) + " " + (ot.withOffsetSameLocal(p2) == ot) + " " + (ot.withOffsetSameInstant(p2) == ot));
            a("ot with", () -> ot.withHour(1) + " " + ot.withMinute(2) + " " + ot.withSecond(3) + " " + ot.withNano(4) + " " + ot.with(LocalTime.MIDNIGHT) + " " + ot.with(ZoneOffset.UTC) + " " + ot.with(OffsetTime.MIN) + " " + ot.with(ChronoField.OFFSET_SECONDS, 3600) + " " + ot.with(ChronoField.HOUR_OF_DAY, 7) + " " + ot.with(ChronoField.NANO_OF_DAY, 5));
            a("ot with bad", () -> ot.withHour(24));
            a("ot with field bad", () -> ot.with(ChronoField.YEAR, 2020));
            a("ot with offset bad", () -> ot.with(ChronoField.OFFSET_SECONDS, 100000));
            a("ot with date", () -> ot.with(LocalDate.of(2020, 1, 1)));
            a("ot truncated", () -> ot.truncatedTo(ChronoUnit.MINUTES) + " " + ot.truncatedTo(ChronoUnit.HOURS) + " " + ot.truncatedTo(ChronoUnit.HALF_DAYS));
            a("ot truncated days", () -> ot.truncatedTo(ChronoUnit.DAYS));
            a("ot truncated months", () -> ot.truncatedTo(ChronoUnit.MONTHS));
            a("ot plus", () -> ot.plusHours(20) + " " + ot.plusMinutes(-30) + " " + ot.plusSeconds(100000) + " " + ot.plusNanos(-501) + " " + ot.minusHours(11) + " " + ot.minusMinutes(1) + " " + ot.minusSeconds(31) + " " + ot.minusNanos(500) + " " + ot.plus(Duration.ofMinutes(90)) + " " + ot.minus(Duration.ofDays(1)));
            a("ot plus period", () -> ot.plus(Period.ofDays(1)));
            a("ot plus period0", () -> ot.plus(Period.ZERO));
            a("ot adjust", () -> ot.adjustInto(OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)) + " " + OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).with(ot));
            a("ot adjust ldt", () -> ot.adjustInto(LocalDateTime.of(2020, 1, 1, 0, 0)));
            a("ot adjust zdt", () -> ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).with(ot));
            a("ot atDate", () -> ot.atDate(LocalDate.of(2020, 1, 1)) + " " + LocalDate.of(2020, 1, 1).atTime(ot) + " " + ot.toEpochSecond(LocalDate.of(2020, 1, 1)) + " " + OffsetDateTime.of(2020, 1, 1, 5, 6, 7, 8, p2).toOffsetTime());
            OffsetTime other = OffsetTime.of(9, 15, 30, 500, ZoneOffset.ofHours(1));
            a("ot compare", () -> ot.compareTo(other) + " " + other.compareTo(ot) + " " + ot.isEqual(other) + " " + ot.equals(other) + " " + ot.isAfter(other) + " " + ot.isBefore(other) + " " + ot.compareTo(OffsetTime.of(10, 15, 30, 501, p2)) + " " + ot.compareTo(OffsetTime.of(10, 15, 30, 499, p2)) + " " + OffsetTime.of(10, 0, 0, 0, ZoneOffset.UTC).compareTo(OffsetTime.of(11, 0, 0, 0, ZoneOffset.ofHours(1))) + " " + OffsetTime.of(11, 0, 0, 0, ZoneOffset.ofHours(1)).compareTo(OffsetTime.of(10, 0, 0, 0, ZoneOffset.UTC)));
            a("ot compare more", () -> OffsetTime.of(23, 0, 0, 0, ZoneOffset.MAX).compareTo(OffsetTime.of(0, 0, 0, 0, ZoneOffset.MIN)) + " " + OffsetTime.MIN.compareTo(OffsetTime.MAX) + " " + OffsetTime.MAX.compareTo(OffsetTime.MIN));
            a("ot query", () -> ot.query(TemporalQueries.precision()) + " " + ot.query(TemporalQueries.offset()) + " " + ot.query(TemporalQueries.zone()) + " " + ot.query(TemporalQueries.localTime()));
            a("ot format", () -> ot.format(DateTimeFormatter.ofPattern("HH:mm xxx")) + " " + DateTimeFormatter.ofPattern("hh:mm a O").format(ot));
            a("ot format date", () -> ot.format(DateTimeFormatter.ofPattern("uuuu HH:mm")));
            // InstantSource
            InstantSource fixed = InstantSource.fixed(Instant.ofEpochSecond(1000));
            a("is fixed", () -> fixed + " " + fixed.instant() + " " + fixed.millis() + " " + fixed.withZone(p2));
            a("is system", () -> InstantSource.system() + " " + InstantSource.system().withZone(p2) + " " + InstantSource.system().equals(InstantSource.system()));
            a("is offset", () -> InstantSource.offset(fixed, Duration.ofSeconds(5)) + " " + InstantSource.offset(fixed, Duration.ofSeconds(5)).instant() + " " + InstantSource.offset(fixed, Duration.ZERO));
            a("is tick", () -> InstantSource.tick(InstantSource.fixed(Instant.ofEpochSecond(1000, 123456789)), Duration.ofMillis(10)) + " " + InstantSource.tick(InstantSource.fixed(Instant.ofEpochSecond(1000, 123456789)), Duration.ofMillis(10)).instant());
            a("is tick bad", () -> InstantSource.tick(fixed, Duration.ofSeconds(-1)));
            a("is tick bad2", () -> InstantSource.tick(fixed, Duration.ofNanos(7)));
            a("is clock", () -> { InstantSource s = Clock.fixed(Instant.EPOCH, p2); return s.instant() + " " + s.withZone(ZoneOffset.UTC) + " " + s.millis(); });
            a("is clock offset", () -> InstantSource.offset(Clock.fixed(Instant.EPOCH, p2), Duration.ofSeconds(1)));
            a("is clock tick", () -> InstantSource.tick(Clock.fixed(Instant.EPOCH, p2), Duration.ofSeconds(1)));
            a("is fixed eq", () -> InstantSource.fixed(Instant.EPOCH).equals(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)) + " " + InstantSource.fixed(Instant.EPOCH).hashCode() + " " + Clock.fixed(Instant.EPOCH, ZoneOffset.UTC).hashCode());
        }
    }

    static class Adjust {
        static void a(String what, Supplier<Object> s) {
            try {
                System.out.println(what + " = " + s.get());
            } catch (DateTimeParseException e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage() + " @" + e.getErrorIndex() + (e.getCause() == null ? "" : " <- " + e.getCause()));
            } catch (Exception e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        public static void main(String[] args) {
            a("odt +19", () -> OffsetDateTime.parse("2020-01-01T10:15+19:00"));
            a("zdt +19", () -> ZonedDateTime.parse("2020-01-01T10:15+19:00"));
            // TemporalAdjusters
            List<LocalDate> days = List.of(LocalDate.of(2020, 2, 15), LocalDate.of(2021, 12, 31), LocalDate.of(2024, 1, 1), LocalDate.of(2026, 3, 7), LocalDate.of(-1, 2, 28), LocalDate.of(2026, 11, 30));
            for (LocalDate d : days) {
                a("adj " + d, () -> d.with(TemporalAdjusters.firstDayOfMonth()) + " " + d.with(TemporalAdjusters.lastDayOfMonth()) + " " + d.with(TemporalAdjusters.firstDayOfNextMonth()) + " " + d.with(TemporalAdjusters.firstDayOfYear()) + " " + d.with(TemporalAdjusters.lastDayOfYear()) + " " + d.with(TemporalAdjusters.firstDayOfNextYear()));
                for (DayOfWeek w : DayOfWeek.values()) {
                    a("adj " + d + " " + w, () -> d.with(TemporalAdjusters.firstInMonth(w)) + " " + d.with(TemporalAdjusters.lastInMonth(w)) + " " + d.with(TemporalAdjusters.next(w)) + " " + d.with(TemporalAdjusters.nextOrSame(w)) + " " + d.with(TemporalAdjusters.previous(w)) + " " + d.with(TemporalAdjusters.previousOrSame(w)) + " " + d.with(TemporalAdjusters.dayOfWeekInMonth(2, w)) + " " + d.with(TemporalAdjusters.dayOfWeekInMonth(-1, w)) + " " + d.with(TemporalAdjusters.dayOfWeekInMonth(0, w)) + " " + d.with(TemporalAdjusters.dayOfWeekInMonth(5, w)) + " " + d.with(TemporalAdjusters.dayOfWeekInMonth(-5, w)) + " " + d.with(TemporalAdjusters.dayOfWeekInMonth(-2, w)));
                }
            }
            a("adj ldt", () -> LocalDateTime.of(2020, 2, 15, 10, 30).with(TemporalAdjusters.lastDayOfMonth()) + " " + ZonedDateTime.of(2020, 2, 15, 10, 30, 0, 0, ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.MONDAY)) + " " + OffsetDateTime.of(2020, 2, 15, 10, 30, 0, 0, ZoneOffset.UTC).with(TemporalAdjusters.firstDayOfNextYear()));
            a("adj time", () -> LocalTime.NOON.with(TemporalAdjusters.firstDayOfMonth()));
            a("adj time next", () -> LocalTime.NOON.with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
            a("adj ym", () -> YearMonth.of(2020, 1).with(TemporalAdjusters.firstDayOfMonth()));
            a("adj ofDate", () -> LocalDate.of(2020, 1, 1).with(TemporalAdjusters.ofDateAdjuster(x -> x.plusDays(3))) + " " + LocalDateTime.of(2020, 1, 1, 5, 5).with(TemporalAdjusters.ofDateAdjuster(x -> x.plusDays(3))) + " " + ZonedDateTime.of(2020, 1, 1, 5, 5, 0, 0, ZoneOffset.UTC).with(TemporalAdjusters.ofDateAdjuster(x -> x.plusMonths(3))));
            a("adj ofDate time", () -> LocalTime.NOON.with(TemporalAdjusters.ofDateAdjuster(x -> x.plusDays(3))));
            a("adj max", () -> LocalDate.MAX.with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
            a("adj max2", () -> LocalDate.MAX.with(TemporalAdjusters.firstDayOfNextMonth()));
            a("adj max3", () -> LocalDate.MAX.with(TemporalAdjusters.firstDayOfNextYear()));
            a("adj min", () -> LocalDate.MIN.with(TemporalAdjusters.previous(DayOfWeek.MONDAY)));
            // datesUntil
            LocalDate s = LocalDate.of(2020, 1, 30);
            a("du", () -> s.datesUntil(LocalDate.of(2020, 2, 3)).collect(Collectors.toList()));
            a("du same", () -> s.datesUntil(s).count());
            a("du before", () -> s.datesUntil(LocalDate.of(2020, 1, 29)));
            a("du step", () -> s.datesUntil(LocalDate.of(2020, 6, 1), Period.ofMonths(1)).collect(Collectors.toList()));
            a("du step2", () -> LocalDate.of(2020, 1, 31).datesUntil(LocalDate.of(2020, 6, 1), Period.of(0, 1, 1)).collect(Collectors.toList()));
            a("du step neg", () -> s.datesUntil(LocalDate.of(2019, 6, 1), Period.ofMonths(-1)).collect(Collectors.toList()));
            a("du step neg wrong", () -> s.datesUntil(LocalDate.of(2021, 6, 1), Period.ofMonths(-1)).count());
            a("du step pos wrong", () -> s.datesUntil(LocalDate.of(2019, 6, 1), Period.ofMonths(1)).count());
            a("du step zero", () -> s.datesUntil(LocalDate.of(2021, 6, 1), Period.ZERO));
            a("du step mixed", () -> s.datesUntil(LocalDate.of(2021, 6, 1), Period.of(0, 1, -1)));
            a("du step weeks", () -> s.datesUntil(LocalDate.of(2020, 3, 1), Period.ofWeeks(2)).collect(Collectors.toList()));
            a("du step years", () -> LocalDate.of(2020, 2, 29).datesUntil(LocalDate.of(2030, 1, 1), Period.ofYears(3)).collect(Collectors.toList()));
            a("du step same", () -> s.datesUntil(s, Period.ofDays(1)).count());
            a("du count", () -> LocalDate.MIN.datesUntil(LocalDate.MAX).count());
            a("du count step", () -> LocalDate.MIN.datesUntil(LocalDate.MAX, Period.ofMonths(1)).count());
            a("du skip", () -> s.datesUntil(LocalDate.of(2021, 1, 1)).skip(300).findFirst().get());
            a("du limit", () -> s.datesUntil(LocalDate.of(2021, 1, 1), Period.ofDays(10)).limit(3).collect(Collectors.toList()));
            // timeLineOrder
            List<OffsetDateTime> odts = new ArrayList<>(List.of(OffsetDateTime.of(2020, 1, 1, 10, 0, 0, 0, ZoneOffset.ofHours(1)), OffsetDateTime.of(2020, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC), OffsetDateTime.of(2020, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC), OffsetDateTime.of(2020, 1, 1, 11, 0, 0, 0, ZoneOffset.ofHours(2))));
            odts.sort(OffsetDateTime.timeLineOrder());
            a("tlo", () -> odts);
            a("tlo cmp", () -> OffsetDateTime.timeLineOrder().compare(odts.get(0), odts.get(1)) + " " + OffsetDateTime.timeLineOrder().compare(odts.get(2), odts.get(0)) + " " + OffsetDateTime.timeLineOrder().compare(odts.get(0), odts.get(0)) + " " + odts.get(0).compareTo(odts.get(1)) + " " + (OffsetDateTime.timeLineOrder() == OffsetDateTime.timeLineOrder()));
            a("tlo cmp nanos", () -> OffsetDateTime.timeLineOrder().compare(OffsetDateTime.of(2020, 1, 1, 10, 0, 0, 5, ZoneOffset.ofHours(1)), OffsetDateTime.of(2020, 1, 1, 9, 0, 0, 3, ZoneOffset.UTC)) + " " + OffsetDateTime.timeLineOrder().compare(OffsetDateTime.of(2020, 1, 1, 10, 0, 1, 0, ZoneOffset.ofHours(1)), OffsetDateTime.of(2020, 1, 1, 9, 0, 0, 999, ZoneOffset.UTC)));
            // ChronoPeriod
            ChronoPeriod p = Period.of(1, 2, 3);
            a("cp", () -> p.getChronology() + " " + p.getUnits() + " " + p.get(ChronoUnit.DAYS) + " " + p.isZero() + " " + p.isNegative() + " " + p.plus(Period.ofDays(1)) + " " + p.minus(Period.ofMonths(1)) + " " + p.multipliedBy(2) + " " + p.negated() + " " + p.normalized() + " " + p.addTo(LocalDate.of(2020, 1, 1)) + " " + p.subtractFrom(LocalDate.of(2020, 1, 1)) + " " + p.equals(Period.of(1, 2, 3)) + " " + p.hashCode() + " " + p);
            a("cp between", () -> ChronoPeriod.between(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 3, 4)) + " " + ChronoPeriod.between(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 3, 4)).getClass().getName());
            a("cp plus dur", () -> p.plus(Duration.ofDays(1)));
            LocalDate s2 = LocalDate.of(2020, 1, 30);
            Object[][] cases = {{s2, Period.ZERO}, {LocalDate.of(2019, 1, 1), Period.ZERO}, {LocalDate.of(2019, 1, 1), Period.of(0, 1, -1)}, {LocalDate.of(2021, 1, 1), Period.of(0, -1, 1)}, {s2, Period.of(0, -1, 0)}, {LocalDate.of(2020, 1, 31), Period.ofDays(1)}, {LocalDate.of(2020, 1, 29), Period.ofDays(-2)}, {LocalDate.of(2019, 12, 30), Period.ofMonths(-1)}, {LocalDate.of(2019, 12, 29), Period.ofMonths(-1)}, {LocalDate.of(2020, 2, 29), Period.ofMonths(1)}, {LocalDate.of(2020, 3, 1), Period.ofMonths(1)}, {LocalDate.of(2020, 3, 31), Period.ofMonths(1)}};
            for (Object[] c : cases) {
                a("du " + c[0] + " " + c[1], () -> s2.datesUntil((LocalDate) c[0], (Period) c[1]).collect(Collectors.toList()));
            }
            a("du counts", () -> LocalDate.MIN.datesUntil(LocalDate.MAX, Period.of(1000, 7, 3)).count() + " " + LocalDate.MAX.datesUntil(LocalDate.MIN, Period.of(-1000, -7, -3)).count() + " " + LocalDate.MIN.datesUntil(LocalDate.MAX, Period.ofDays(3)).count() + " " + LocalDate.MAX.datesUntil(LocalDate.MIN, Period.ofDays(-1)).count() + " " + LocalDate.of(2020, 1, 31).datesUntil(LocalDate.of(2030, 1, 31), Period.ofMonths(1)).count());
            a("du skip", () -> LocalDate.of(2020, 1, 31).datesUntil(LocalDate.of(2030, 1, 31), Period.ofMonths(1)).skip(118).collect(Collectors.toList()) + " " + LocalDate.of(2030, 1, 31).datesUntil(LocalDate.of(2020, 1, 31), Period.ofMonths(-1)).skip(118).collect(Collectors.toList()));
            a("du far", () -> LocalDate.MAX.datesUntil(LocalDate.MIN, Period.ofYears(-100000000)).collect(Collectors.toList()) + " " + LocalDate.MIN.datesUntil(LocalDate.MAX, Period.ofYears(400000000)).collect(Collectors.toList()));
            a("cp isneg", () -> Period.of(1, -1, 0).isNegative() + " " + ((ChronoPeriod) Period.of(0, 0, 0)).isZero());
        }
    }

    static class Fields {
        static void a(String what, Supplier<Object> s) {
            try {
                System.out.println(what + " = " + s.get());
            } catch (DateTimeParseException e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage() + " @" + e.getErrorIndex() + (e.getCause() == null ? "" : " <- " + e.getCause()));
            } catch (Exception e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        static String desc(TemporalField f) {
            return f + " base=" + f.getBaseUnit() + " rangeUnit=" + f.getRangeUnit() + " range=" + f.range() + " date=" + f.isDateBased() + " time=" + f.isTimeBased() + " name=" + f.getDisplayName(Locale.US) + "/" + f.getDisplayName(Locale.ROOT) + "/" + f.getDisplayName(Locale.UK) ;
        }
        static String udesc(TemporalUnit u) {
            return u + " dur=" + u.getDuration() + " est=" + u.isDurationEstimated() + " date=" + u.isDateBased() + " time=" + u.isTimeBased();
        }
        public static void main(String[] args) {
            List<TemporalField> fields = new ArrayList<>(List.of(IsoFields.DAY_OF_QUARTER, IsoFields.QUARTER_OF_YEAR, IsoFields.WEEK_OF_WEEK_BASED_YEAR, IsoFields.WEEK_BASED_YEAR, JulianFields.JULIAN_DAY, JulianFields.MODIFIED_JULIAN_DAY, JulianFields.RATA_DIE));
            List<WeekFields> wfs = new ArrayList<>(List.of(WeekFields.ISO, WeekFields.SUNDAY_START, WeekFields.of(Locale.ROOT), WeekFields.of(Locale.ENGLISH), WeekFields.of(Locale.US), WeekFields.of(Locale.UK), WeekFields.of(Locale.CANADA), WeekFields.of(DayOfWeek.WEDNESDAY, 7), WeekFields.of(DayOfWeek.SATURDAY, 3)));
            for (WeekFields w : wfs) {
                a("wf " + w, () -> w.getFirstDayOfWeek() + " " + w.getMinimalDaysInFirstWeek() + " " + w.hashCode() + " " + w.equals(WeekFields.ISO) + " " + (w == WeekFields.of(w.getFirstDayOfWeek(), w.getMinimalDaysInFirstWeek())));
            }
            WeekFields us = WeekFields.of(Locale.US);
            fields.addAll(List.of(us.dayOfWeek(), us.weekOfMonth(), us.weekOfYear(), us.weekOfWeekBasedYear(), us.weekBasedYear(), WeekFields.ISO.weekOfYear()));
            for (TemporalField f : fields) {
                a("field " + f, () -> desc(f));
            }
            a("wf eq field", () -> us.weekOfYear().equals(WeekFields.SUNDAY_START.weekOfYear()) + " " + (us.weekOfYear() == WeekFields.SUNDAY_START.weekOfYear()));
            for (TemporalUnit u : List.of(IsoFields.WEEK_BASED_YEARS, IsoFields.QUARTER_YEARS, WeekFields.WEEK_BASED_YEARS)) {
                a("unit " + u, () -> udesc(u) + " " + (u == IsoFields.WEEK_BASED_YEARS));
            }
            a("wf bad", () -> WeekFields.of(DayOfWeek.MONDAY, 0));
            a("wf bad2", () -> WeekFields.of(DayOfWeek.MONDAY, 8));
            // values
            List<LocalDate> dates = List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), LocalDate.of(2021, 1, 1), LocalDate.of(2021, 1, 3), LocalDate.of(2021, 1, 4), LocalDate.of(2026, 3, 7), LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 1), LocalDate.of(2015, 12, 31), LocalDate.of(2016, 1, 1), LocalDate.of(2016, 1, 3), LocalDate.of(2009, 12, 31), LocalDate.of(2010, 1, 3), LocalDate.of(2024, 6, 30), LocalDate.of(2024, 9, 30), LocalDate.of(0, 1, 1), LocalDate.of(-1, 12, 31), LocalDate.of(1970, 1, 1), LocalDate.of(1858, 11, 17));
            for (LocalDate d : dates) {
                StringBuilder sb = new StringBuilder();
                for (TemporalField f : fields) {
                    try {
                        sb.append(' ').append(d.getLong(f)).append('/').append(d.range(f)).append('/').append(f.rangeRefinedBy(d));
                    } catch (Exception e) {
                        sb.append(" !").append(e.getMessage());
                    }
                }
                System.out.println("vals " + d + sb);
            }
            for (WeekFields w : wfs) {
                for (LocalDate d : dates) {
                    a("wvals " + w + " " + d, () -> d.get(w.dayOfWeek()) + " " + d.get(w.weekOfMonth()) + " " + d.get(w.weekOfYear()) + " " + d.get(w.weekOfWeekBasedYear()) + " " + d.get(w.weekBasedYear()) + " | " + d.range(w.dayOfWeek()) + " " + d.range(w.weekOfMonth()) + " " + d.range(w.weekOfYear()) + " " + d.range(w.weekOfWeekBasedYear()) + " " + d.range(w.weekBasedYear()));
                }
            }
            // support on other classes
            List<TemporalAccessor> ts = List.of(LocalDateTime.of(2020, 1, 1, 0, 0), ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), LocalTime.NOON, Instant.EPOCH, Year.of(2020), YearMonth.of(2020, 5), MonthDay.of(5, 6), DayOfWeek.MONDAY, Month.MAY);
            for (TemporalAccessor t : ts) {
                StringBuilder sb = new StringBuilder();
                for (TemporalField f : fields) {
                    sb.append(' ').append(t.isSupported(f));
                    try {
                        sb.append(':').append(t.getLong(f));
                    } catch (Exception e) {
                        sb.append(":!").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
                    }
                }
                System.out.println("support " + t + sb);
            }
            // with
            for (LocalDate d : List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 3), LocalDate.of(2026, 3, 7), LocalDate.of(2020, 12, 31), LocalDate.of(2026, 5, 31), LocalDate.of(2024, 2, 29))) {
                for (TemporalField f : fields) {
                    for (long v : new long[] {1, 2, 3, 4, 53, 90, 91, 92, 0, -1, 2015, 2026}) {
                        try {
                            System.out.println("with " + d + " " + f + " " + v + " = " + d.with(f, v));
                        } catch (Exception e) {
                            System.out.println("with " + d + " " + f + " " + v + " ! " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
            }
            a("with ldt", () -> LocalDateTime.of(2020, 1, 1, 5, 6).with(IsoFields.QUARTER_OF_YEAR, 3) + " " + LocalDateTime.of(2020, 1, 1, 5, 6).with(us.weekOfYear(), 10));
            a("with time", () -> LocalTime.NOON.with(IsoFields.QUARTER_OF_YEAR, 3));
            a("with time wf", () -> LocalTime.NOON.with(us.weekOfYear(), 3));
            a("with time jd", () -> LocalTime.NOON.with(JulianFields.JULIAN_DAY, 3));
            // units
            for (LocalDate d : List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 3), LocalDate.of(2020, 12, 31), LocalDate.of(2024, 2, 29))) {
                for (TemporalUnit u : List.of(IsoFields.WEEK_BASED_YEARS, IsoFields.QUARTER_YEARS, WeekFields.WEEK_BASED_YEARS)) {
                    for (long n : new long[] {1, -1, 5}) {
                        a("plus " + d + " " + n + " " + u, () -> d.plus(n, u) + " " + d.until(d.plus(n, u), u) + " " + u.between(d, LocalDate.of(2030, 6, 15)) + " " + d.isSupported(u));
                    }
                }
            }
            a("plus ldt", () -> LocalDateTime.of(2020, 1, 1, 5, 6).plus(1, IsoFields.QUARTER_YEARS) + " " + LocalDateTime.of(2020, 1, 1, 5, 6).until(LocalDateTime.of(2021, 6, 1, 0, 0), IsoFields.QUARTER_YEARS));
            a("plus time", () -> LocalTime.NOON.plus(1, IsoFields.QUARTER_YEARS));
            a("plus time2", () -> LocalTime.NOON.isSupported(IsoFields.QUARTER_YEARS));
            a("until time", () -> IsoFields.QUARTER_YEARS.between(LocalTime.NOON, LocalTime.MIDNIGHT));
            a("until mixed", () -> IsoFields.QUARTER_YEARS.between(LocalDate.of(2020, 1, 1), LocalDateTime.of(2021, 1, 1, 0, 0)));
            a("until mixed2", () -> LocalDate.of(2020, 1, 1).until(LocalDateTime.of(2021, 1, 1, 0, 0), IsoFields.QUARTER_YEARS));
            a("until ym", () -> YearMonth.of(2020, 1).until(YearMonth.of(2021, 1), IsoFields.QUARTER_YEARS));
            a("plus ym", () -> YearMonth.of(2020, 1).plus(1, IsoFields.QUARTER_YEARS));
            a("plus ym wby", () -> YearMonth.of(2020, 1).plus(1, IsoFields.WEEK_BASED_YEARS));
            a("plus year", () -> Year.of(2020).plus(1, IsoFields.QUARTER_YEARS));
        }
    }

    static class Fields2 {
        static void a(String what, Supplier<Object> s) {
            try { System.out.println(what + " = " + s.get()); } catch (Exception e) { System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage()); }
        }
        public static void main(String[] args) {
            a("jd with huge", () -> LocalDate.EPOCH.with(JulianFields.JULIAN_DAY, Long.MAX_VALUE));
            a("jd with big", () -> LocalDate.EPOCH.with(JulianFields.JULIAN_DAY, 999999999999999L));
            a("mjd with big", () -> LocalDate.EPOCH.with(JulianFields.MODIFIED_JULIAN_DAY, -999999999999999L));
            a("jd range time", () -> LocalTime.NOON.range(JulianFields.JULIAN_DAY));
            a("jd range ldt", () -> LocalDateTime.of(2020, 1, 1, 0, 0).range(JulianFields.JULIAN_DAY));
            a("dq range time", () -> LocalTime.NOON.range(IsoFields.DAY_OF_QUARTER));
            a("qoy range time", () -> LocalTime.NOON.range(IsoFields.QUARTER_OF_YEAR));
            a("wowby range time", () -> LocalTime.NOON.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            a("wby range time", () -> LocalTime.NOON.range(IsoFields.WEEK_BASED_YEAR));
            a("dq range ym", () -> YearMonth.of(2020, 1).range(IsoFields.DAY_OF_QUARTER));
            a("qoy range ym", () -> YearMonth.of(2020, 1).range(IsoFields.QUARTER_OF_YEAR));
            a("wf dow range time", () -> LocalTime.NOON.range(WeekFields.ISO.dayOfWeek()));
            a("wf wom range time", () -> LocalTime.NOON.range(WeekFields.ISO.weekOfMonth()));
            a("wf wom range dow", () -> DayOfWeek.MONDAY.range(WeekFields.ISO.weekOfMonth()));
            a("wf wby range dow", () -> DayOfWeek.MONDAY.range(WeekFields.ISO.weekBasedYear()));
            a("wf dow range dow", () -> DayOfWeek.MONDAY.range(WeekFields.ISO.dayOfWeek()));
            a("wf woy range ym", () -> YearMonth.of(2020, 1).range(WeekFields.ISO.weekOfYear()));
            for (TemporalUnit u : List.of(IsoFields.QUARTER_YEARS, IsoFields.WEEK_BASED_YEARS)) {
                for (Temporal t : List.<Temporal>of(LocalDate.EPOCH, LocalDateTime.of(2020, 1, 1, 0, 0), LocalTime.NOON, Year.of(2020), YearMonth.of(2020, 1), Instant.EPOCH, ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))) {
                    a("unit " + u + " " + t.getClass().getSimpleName(), () -> t.isSupported(u));
                }
            }
            a("wby between mixed", () -> IsoFields.WEEK_BASED_YEARS.between(LocalDate.of(2020, 1, 1), LocalDateTime.of(2025, 1, 1, 0, 0)));
            a("wby between time", () -> IsoFields.WEEK_BASED_YEARS.between(LocalTime.NOON, LocalTime.MIDNIGHT));
            a("qy between time", () -> IsoFields.QUARTER_YEARS.between(LocalTime.NOON, LocalTime.MIDNIGHT));
            a("qy between ym", () -> IsoFields.QUARTER_YEARS.between(YearMonth.of(2020, 1), YearMonth.of(2021, 3)));
            a("wby plus big", () -> LocalDate.EPOCH.plus(Long.MAX_VALUE, IsoFields.WEEK_BASED_YEARS));
            a("wby plus big2", () -> LocalDate.EPOCH.plus(1000000000, IsoFields.WEEK_BASED_YEARS));
            a("qy plus big", () -> LocalDate.EPOCH.plus(Long.MAX_VALUE, IsoFields.QUARTER_YEARS));
            a("wf wby with clamp", () -> LocalDate.of(2016, 12, 31).with(WeekFields.SUNDAY_START.weekBasedYear(), 2017) + " " + LocalDate.of(2016, 12, 31).get(WeekFields.SUNDAY_START.weekOfWeekBasedYear()) + " " + LocalDate.of(2020, 12, 31).with(WeekFields.ISO.weekBasedYear(), 2021) + " " + LocalDate.of(2026, 12, 31).with(WeekFields.of(DayOfWeek.WEDNESDAY, 3).weekBasedYear(), 2015));
            a("wf wby with big", () -> LocalDate.of(2016, 12, 31).with(WeekFields.SUNDAY_START.weekBasedYear(), 1000000000));
            a("iso wby with big", () -> LocalDate.of(2016, 12, 31).with(IsoFields.WEEK_BASED_YEAR, 1000000000));
            a("iso wby with min", () -> LocalDate.of(2016, 12, 31).with(IsoFields.WEEK_BASED_YEAR, -999999999));
            a("iso wby with max", () -> LocalDate.of(2016, 12, 31).with(IsoFields.WEEK_BASED_YEAR, 999999999));
            a("names", () -> IsoFields.WEEK_OF_WEEK_BASED_YEAR.getDisplayName(Locale.US) + "/" + IsoFields.WEEK_OF_WEEK_BASED_YEAR.getDisplayName(Locale.ROOT) + "/" + IsoFields.WEEK_OF_WEEK_BASED_YEAR.getDisplayName(Locale.CANADA) + "/" + IsoFields.WEEK_OF_WEEK_BASED_YEAR.getDisplayName(Locale.ENGLISH) + " " + WeekFields.ISO.weekOfYear().getDisplayName(Locale.CANADA) + " " + WeekFields.ISO.weekOfMonth().getDisplayName(Locale.ROOT) + " " + IsoFields.DAY_OF_QUARTER.getDisplayName(Locale.US));
            a("wf from dow names", () -> WeekFields.of(Locale.UK).dayOfWeek().getDisplayName(Locale.UK));
            a("wf range dom ldt", () -> LocalDateTime.of(2021, 1, 1, 0, 0).range(WeekFields.ISO.weekOfMonth()) + " " + ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).range(WeekFields.ISO.weekOfWeekBasedYear()));
            a("iso week of 12-31", () -> {
                StringBuilder sb = new StringBuilder();
                for (int y = 1995; y < 2030; y++) sb.append(LocalDate.of(y, 12, 31).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)).append(LocalDate.of(y, 12, 31).range(IsoFields.WEEK_OF_WEEK_BASED_YEAR)).append(' ');
                return sb;
            });
        }
    }

    static class Seeded {
        static long mix(long h, long v) {
            return h * 1000003L ^ v;
        }
        static long mix(long h, String s) {
            return mix(h, s.hashCode());
        }
        public static void main(String[] args) {
            Random random = new Random(52);
            List<LocalDate> dates = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                dates.add(LocalDate.ofEpochDay(random.nextInt(800000) - 400000));
            }
            for (int i = 0; i < 40; i++) {
                dates.add(LocalDate.of(random.nextInt(4000) - 2000, 12, 20 + random.nextInt(12)));
                dates.add(LocalDate.of(random.nextInt(4000) - 2000, 1, 1 + random.nextInt(10)));
            }
            dates.add(LocalDate.MIN.plusDays(10));
            dates.add(LocalDate.MAX.minusDays(10));
            List<TemporalField> iso = List.of(IsoFields.DAY_OF_QUARTER, IsoFields.QUARTER_OF_YEAR, IsoFields.WEEK_OF_WEEK_BASED_YEAR, IsoFields.WEEK_BASED_YEAR, JulianFields.JULIAN_DAY, JulianFields.MODIFIED_JULIAN_DAY, JulianFields.RATA_DIE);
            long h = 0;
            for (LocalDate d : dates) {
                for (TemporalField f : iso) {
                    try {
                        h = mix(h, d.getLong(f));
                        h = mix(h, d.range(f).toString());
                        long v = d.getLong(f);
                        h = mix(h, d.with(f, f == IsoFields.WEEK_BASED_YEAR ? v + 3 : f.range().getMinimum()).toEpochDay());
                    } catch (DateTimeException e) {
                        h = mix(h, e.getMessage());
                    }
                }
            }
            System.out.println("iso " + h);
            for (DayOfWeek first : DayOfWeek.values()) {
                for (int min = 1; min <= 7; min++) {
                    WeekFields w = WeekFields.of(first, min);
                    long g = 0;
                    for (LocalDate d : dates) {
                        for (TemporalField f : List.of(w.dayOfWeek(), w.weekOfMonth(), w.weekOfYear(), w.weekOfWeekBasedYear(), w.weekBasedYear())) {
                            try {
                                g = mix(g, d.getLong(f));
                                g = mix(g, d.range(f).toString());
                                long v = d.getLong(f);
                                g = mix(g, d.with(f, f == w.weekBasedYear() ? v - 7 : f == w.dayOfWeek() ? 1 + (v % 7) : f.range().getMinimum() + 1).toEpochDay());
                            } catch (DateTimeException e) {
                                g = mix(g, e.getMessage());
                            }
                        }
                    }
                    System.out.println(w + " " + g);
                }
            }
        }
    }

    static class Runs {
        static String sorted(String s) {
            int open = s.indexOf('{');
            int close = s.indexOf('}');
            if (open < 0 || close < open) return s;
            String inner = s.substring(open + 1, close);
            List<String> parts = new ArrayList<>(inner.isEmpty() ? List.of() : Arrays.asList(inner.split(", ")));
            Collections.sort(parts);
            return s.substring(0, open) + "{" + String.join(", ", parts) + "}" + s.substring(close + 1);
        }
        static void p(String label, DateTimeFormatter f, String t) {
            String r;
            try { r = sorted(f.parse(t).toString()); } catch (DateTimeParseException e) { r = "! @" + e.getErrorIndex() + " " + sorted(e.getMessage()); }
            System.out.println(label + " [" + t + "] " + r);
        }
        public static void main(String[] args) {
            String[] texts = {"123456789012", "1234567890123", "12345678901", "1234567890", "-123456789012", "+123456789012", "12345678", "12345", "123", "-12345", "Mar123", "2026123", "202612345", "12123", "1234567", "12"};
            String[] patterns = {"uuuu", "u", "uuuuu", "MM", "M", "D", "DDD", "yyyy", "A", "n", "N", "uuuu'x'", "uuuuMM", "MMuuuu", "MMdd", "MMddHH", "MMu", "Mu", "MuMM", "uD", "uuuuD", "uDD", "DDDu", "MM'x'uuuu", "MMddu", "MMuuuuHH", "Mdd", "MMM", "MMMuuuu", "uuuuMMM", "SSSuuuu", "uuuuSSS", "MMSSS", "uuuuSSSmm", "ssSSSmm", "uuuuSSSu", "MMSSSuuuu", "uuuuSSSSSS", "uSSS", "ss[SSS]mm", "ssSSS'x'", "uMMu", "uuuuMMuuuu", "uMMddu", "MMddMMu", "uMMDDD", "uuuuMMddHHmmssSSS", "HHmmssSSS", "HHmmssSSSuuuu", "SSSHH"};
            for (boolean lenient : new boolean[] {false, true}) {
                for (String pattern : patterns) {
                    DateTimeFormatterBuilder b = new DateTimeFormatterBuilder();
                    if (lenient) b.parseLenient();
                    DateTimeFormatter f = b.appendPattern(pattern).toFormatter(Locale.US);
                    for (String t : texts) p((lenient ? "L " : "S ") + pattern, f, t);
                }
                DateTimeFormatterBuilder b1 = new DateTimeFormatterBuilder();
                if (lenient) b1.parseLenient();
                DateTimeFormatter v = b1.appendValue(ChronoField.YEAR, 2, 4, SignStyle.NORMAL).appendValue(ChronoField.MONTH_OF_YEAR, 2).toFormatter();
                DateTimeFormatterBuilder b2 = new DateTimeFormatterBuilder();
                if (lenient) b2.parseLenient();
                DateTimeFormatter r = b2.appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter();
                DateTimeFormatterBuilder b3 = new DateTimeFormatterBuilder();
                if (lenient) b3.parseLenient();
                DateTimeFormatter fr = b3.appendValue(ChronoField.MONTH_OF_YEAR, 2).appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, false).toFormatter();
                DateTimeFormatterBuilder b4 = new DateTimeFormatterBuilder();
                if (lenient) b4.parseLenient();
                DateTimeFormatter pad = b4.appendValue(ChronoField.MONTH_OF_YEAR, 2).padNext(3).appendValue(ChronoField.YEAR).toFormatter();
                DateTimeFormatterBuilder b5 = new DateTimeFormatterBuilder();
                if (lenient) b5.parseLenient();
                DateTimeFormatter frac03 = b5.appendValue(ChronoField.SECOND_OF_MINUTE, 2).appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, false).appendValue(ChronoField.MINUTE_OF_HOUR, 2).toFormatter();
                for (String t : texts) {
                    p((lenient ? "L " : "S ") + "Year2-4 MM", v, t);
                    p((lenient ? "L " : "S ") + "reduced", r, t);
                    p((lenient ? "L " : "S ") + "MM fraction1-9", fr, t);
                    p((lenient ? "L " : "S ") + "MM pad Year", pad, t);
                    p((lenient ? "L " : "S ") + "ss fraction0-3 mm", frac03, t);
                }
            }
        }
    }

    static class Letters {
        static void a(String what, Supplier<Object> s) {
            try {
                System.out.println(what + " = " + s.get());
            } catch (DateTimeParseException e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage() + " @" + e.getErrorIndex() + (e.getCause() == null ? "" : " <- " + e.getCause()));
            } catch (Exception e) {
                System.out.println(what + " ! " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        public static void main(String[] args) {
            for (char c : "YwWecQqg".toCharArray()) {
                for (int n = 1; n <= 20; n++) {
                    String p = String.valueOf(c).repeat(n);
                    a("pattern " + p, () -> DateTimeFormatter.ofPattern(p));
                }
            }
            a("iso week", () -> DateTimeFormatter.ISO_WEEK_DATE);
            List<LocalDate> dates = List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31), LocalDate.of(2021, 1, 3), LocalDate.of(2026, 3, 7), LocalDate.of(-5, 6, 7), LocalDate.of(12345, 6, 7), LocalDate.of(2000, 1, 1), LocalDate.of(99, 12, 31));
            String[] pats = {"Y", "YY", "YYY", "YYYY", "YYYYY", "w", "ww", "W", "e", "ee", "eee", "eeee", "eeeee", "c", "ccc", "cccc", "ccccc", "Q", "QQ", "QQQ", "QQQQ", "QQQQQ", "q", "qq", "qqq", "qqqq", "qqqqq", "g", "gg", "ggggg"};
            for (Locale l : new Locale[] {Locale.ROOT, Locale.ENGLISH, Locale.US, Locale.UK, Locale.CANADA}) {
                for (LocalDate d : dates) {
                    StringBuilder sb = new StringBuilder();
                    for (String p : pats) {
                        try {
                            sb.append(' ').append(p).append('=').append(DateTimeFormatter.ofPattern(p, l).format(d));
                        } catch (Exception e) {
                            sb.append(' ').append(p).append("=!").append(e.getMessage());
                        }
                    }
                    System.out.println("fmt " + l.toLanguageTag() + " " + d + sb);
                }
            }
            a("iso week fmt", () -> DateTimeFormatter.ISO_WEEK_DATE.format(LocalDate.of(2021, 1, 3)) + " " + DateTimeFormatter.ISO_WEEK_DATE.format(ZonedDateTime.of(2020, 12, 31, 1, 2, 3, 0, ZoneOffset.ofHours(2))) + " " + DateTimeFormatter.ISO_WEEK_DATE.format(LocalDate.of(12345, 1, 1)) + " " + DateTimeFormatter.ISO_WEEK_DATE.format(LocalDate.of(-1, 1, 1)));
            a("fmt time Y", () -> DateTimeFormatter.ofPattern("Y").format(LocalTime.NOON));
            a("fmt time w", () -> DateTimeFormatter.ofPattern("w").format(LocalTime.NOON));
            a("fmt time Q", () -> DateTimeFormatter.ofPattern("Q").format(LocalTime.NOON));
            a("fmt time QQQ", () -> DateTimeFormatter.ofPattern("QQQ").format(LocalTime.NOON));
            a("fmt time g", () -> DateTimeFormatter.ofPattern("g").format(LocalTime.NOON));
            a("fmt time e", () -> DateTimeFormatter.ofPattern("e").format(LocalTime.NOON));
            a("fmt opt", () -> DateTimeFormatter.ofPattern("HH[ Y w e Q g]").format(LocalTime.NOON));
            a("fmt ym Q", () -> DateTimeFormatter.ofPattern("uuuu QQQ").format(YearMonth.of(2020, 5)));
            a("fmt dow e", () -> DateTimeFormatter.ofPattern("e c eee").format(DayOfWeek.MONDAY));
            a("fmt big g", () -> DateTimeFormatter.ofPattern("gg").format(LocalDate.of(1858, 11, 16)) + " " + DateTimeFormatter.ofPattern("g").format(LocalDate.MIN) + " " + DateTimeFormatter.ofPattern("ggggg").format(LocalDate.of(1858, 11, 18)));
            a("fmt Y max", () -> DateTimeFormatter.ofPattern("YYYY").format(LocalDate.MAX));
            a("fmt Y min", () -> DateTimeFormatter.ofPattern("YYYY").format(LocalDate.MIN));
            a("fmt YY max", () -> DateTimeFormatter.ofPattern("YY").format(LocalDate.MAX));
            a("builder", () -> new DateTimeFormatterBuilder().appendValue(IsoFields.WEEK_BASED_YEAR).appendValue(IsoFields.QUARTER_OF_YEAR, 2).appendText(IsoFields.QUARTER_OF_YEAR).appendValue(JulianFields.JULIAN_DAY).appendValue(WeekFields.ISO.weekOfYear()).appendText(WeekFields.ISO.dayOfWeek()).toFormatter());
            a("builder fmt", () -> new DateTimeFormatterBuilder().appendText(IsoFields.QUARTER_OF_YEAR).appendLiteral(' ').appendText(IsoFields.QUARTER_OF_YEAR, TextStyle.SHORT).appendLiteral(' ').appendText(IsoFields.DAY_OF_QUARTER).appendLiteral(' ').appendText(WeekFields.ISO.dayOfWeek()).appendLiteral(' ').appendText(IsoFields.QUARTER_OF_YEAR, TextStyle.NARROW).toFormatter(Locale.US).format(LocalDate.of(2026, 3, 7)));
            a("builder fmt root", () -> new DateTimeFormatterBuilder().appendText(IsoFields.QUARTER_OF_YEAR).appendLiteral(' ').appendText(IsoFields.QUARTER_OF_YEAR, TextStyle.SHORT).appendLiteral(' ').appendText(IsoFields.QUARTER_OF_YEAR, TextStyle.NARROW).appendLiteral(' ').appendText(IsoFields.QUARTER_OF_YEAR, TextStyle.SHORT_STANDALONE).appendLiteral(' ').appendText(IsoFields.QUARTER_OF_YEAR, TextStyle.FULL_STANDALONE).toFormatter(Locale.ROOT).format(LocalDate.of(2026, 3, 7)));
        }
    }

    static class Resolve {
        static String sorted(String s) {
            int open = s.indexOf('{');
            int close = s.indexOf('}');
            if (open < 0 || close < open) return s;
            String inner = s.substring(open + 1, close);
            List<String> parts = new ArrayList<>(inner.isEmpty() ? List.of() : Arrays.asList(inner.split(", ")));
            Collections.sort(parts);
            return s.substring(0, open) + "{" + String.join(", ", parts) + "}" + s.substring(close + 1);
        }
        static String parsed(DateTimeFormatter f, String text) {
            try {
                TemporalAccessor t = f.parse(text);
                return sorted(t.toString()) + " d=" + t.query(TemporalQueries.localDate());
            } catch (DateTimeParseException e) {
                Throwable c = e.getCause();
                return "! @" + e.getErrorIndex() + " " + sorted(e.getMessage()) + (c == null ? "" : " <- " + c.getClass().getSimpleName());
            } catch (Exception e) {
                return "! " + e.getClass().getName() + ": " + e.getMessage();
            }
        }
        static void p(String pattern, Locale l, String... texts) {
            for (ResolverStyle st : ResolverStyle.values()) {
                DateTimeFormatter f;
                try {
                    f = DateTimeFormatter.ofPattern(pattern, l).withResolverStyle(st);
                } catch (Exception e) {
                    System.out.println(pattern + " ! " + e);
                    return;
                }
                for (String t : texts) {
                    System.out.println(st + " " + l.toLanguageTag() + " " + pattern + " [" + t + "] " + parsed(f, t));
                }
            }
        }
        static void b(String label, DateTimeFormatter f0, String... texts) {
            for (ResolverStyle st : ResolverStyle.values()) {
                DateTimeFormatter f = f0.withResolverStyle(st);
                for (String t : texts) {
                    System.out.println(st + " " + label + " [" + t + "] " + parsed(f, t));
                }
            }
        }
        public static void main(String[] args) {
            b("ISO_WEEK_DATE", DateTimeFormatter.ISO_WEEK_DATE, "2020-W53-5", "2020-W53-5Z", "2020-W53-5+01:00", "2021-W53-1", "2021-W52-7", "2020-W00-1", "2020-W54-1", "2020-W53-8", "2020-W53-0", "2020-w53-5", "2020-W1-5", "+12345-W01-1", "12345-W01-1", "-0001-W01-1", "2020-W53-5x", "2020-W60-1", "2020-W99-9");
            for (Locale l : new Locale[] {Locale.US, Locale.UK}) {
                p("YYYY-'W'ww-e", l, "2020-W53-5", "2021-W01-1", "2021-W53-1", "2021-W52-7", "2020-W00-1", "2020-W54-1", "2020-W53-8", "2020-W01-0", "2021-W60-3");
                p("YYYY-ww", l, "2020-01", "2021-53");
                p("YYYY", l, "2020");
                p("ww", l, "05");
                p("e", l, "3", "8", "0");
                p("ee", l, "03");
                p("c", l, "3");
                p("uuuu-MM-dd e", l, "2026-03-07 7", "2026-03-07 6", "2026-03-07 9");
                p("uuuu-MM W e", l, "2026-03 1 7", "2026-03 2 1", "2026-03 0 1", "2026-03 6 1", "2026-02 5 7", "2026-03 9 1");
                p("uuuu W e", l, "2026 1 7");
                p("uuuu w e", l, "2026 10 7");
                p("YYYY w", l, "2026 10");
                p("YYYY w e uuuu", l, "2026 10 7 2026", "2026 10 7 2027");
                p("YYYY w e MM", l, "2026 10 7 03", "2026 10 7 04");
                p("YY w e", l, "26 10 7", "99 10 7");
                p("YYYY-ww-e HH:mm", l, "2026-10-7 10:30");
                p("Y-w-e", l, "2026-10-7", "-5-1-1", "+12345-1-1");
                p("YYYY-ww-EEE", l, "2026-10-Sat", "2026-10-Sun");
            }
            p("Q uuuu", Locale.US, "1 2026", "5 2026", "0 2026");
            p("QQQ uuuu", Locale.US, "Q1 2026", "Q5 2026");
            p("QQQQ uuuu", Locale.US, "1st quarter 2026", "2nd quarter 2026");
            p("QQQQQ uuuu", Locale.US, "1 2026");
            p("qqq", Locale.ROOT, "1", "Q1");
            p("QQQ", Locale.ROOT, "Q1", "1");
            p("uuuu-MM-dd Q", Locale.US, "2026-03-07 1", "2026-03-07 2");
            p("uuuu-Q", Locale.US, "2026-1");
            p("g", Locale.US, "58849", "-1", "0", "999999999999999");
            p("gggggg", Locale.US, "058849");
            p("g uuuu-MM-dd", Locale.US, "58849 2020-01-01", "58849 2020-01-02");
            b("dq", new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral('-').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter(), "2026-1-1", "2026-1-90", "2026-1-91", "2026-1-92", "2026-1-93", "2026-1-0", "2026-4-92", "2024-1-91", "2026-5-1", "2026-0-1", "2026-2-100");
            b("dq2", new DateTimeFormatterBuilder().appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter(), "1-1");
            b("wby iso", new DateTimeFormatterBuilder().appendValue(IsoFields.WEEK_BASED_YEAR).appendLiteral('-').appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR).appendLiteral('-').appendValue(ChronoField.DAY_OF_WEEK).toFormatter(), "2020-53-5", "2021-53-1", "2021-54-1", "2021-0-1", "2021-1-0", "2021-1-8", "2021-100-1", "2021--1-1");
            b("wby iso no dow", new DateTimeFormatterBuilder().appendValue(IsoFields.WEEK_BASED_YEAR).appendLiteral('-').appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toFormatter(), "2020-53");
            b("jd", new DateTimeFormatterBuilder().appendValue(JulianFields.JULIAN_DAY).toFormatter(), "2459215", "0");
            b("rd", new DateTimeFormatterBuilder().appendValue(JulianFields.RATA_DIE).toFormatter(), "1", "737790");
            b("jd big", new DateTimeFormatterBuilder().appendValue(JulianFields.JULIAN_DAY).toFormatter(), "999999999999999");
            WeekFields wf = WeekFields.of(DayOfWeek.WEDNESDAY, 3);
            b("wf wed3 woy", new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(wf.weekOfYear()).appendLiteral(' ').appendValue(wf.dayOfWeek()).toFormatter(), "2026 1 1", "2026 0 1", "2026 0 7", "2026 53 7", "2026 54 1", "2026 10 8");
            b("wf wed3 wom", new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(ChronoField.MONTH_OF_YEAR).appendLiteral(' ').appendValue(wf.weekOfMonth()).appendLiteral(' ').appendValue(wf.dayOfWeek()).toFormatter(), "2026 3 1 1", "2026 3 0 1", "2026 3 5 7", "2026 3 6 7", "2026 13 1 1");
            b("wf wed3 wbwoy", new DateTimeFormatterBuilder().appendValue(wf.weekBasedYear()).appendLiteral(' ').appendValue(wf.weekOfWeekBasedYear()).appendLiteral(' ').appendValue(wf.dayOfWeek()).toFormatter(), "2026 1 1", "2026 53 7", "2026 54 7", "2026 0 1");
            b("wf wed3 dow only", new DateTimeFormatterBuilder().appendValue(wf.dayOfWeek()).toFormatter(), "1", "7");
            b("wf wed3 dow + date", new DateTimeFormatterBuilder().appendValue(ChronoField.EPOCH_DAY).appendLiteral(' ').appendValue(wf.dayOfWeek()).toFormatter(), "0 2", "0 3");
            b("wf mixed", new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(WeekFields.ISO.weekOfYear()).appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK).toFormatter(), "2026 10 6");
            b("wf mixed2", new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(WeekFields.ISO.weekOfYear()).appendLiteral(' ').appendValue(WeekFields.SUNDAY_START.dayOfWeek()).toFormatter(), "2026 10 7");
        }
    }

    static class Mixed {
        static String sorted(String s) {
            int open = s.indexOf('{'); int close = s.indexOf('}');
            if (open < 0 || close < open) return s;
            String inner = s.substring(open + 1, close);
            List<String> parts = new ArrayList<>(inner.isEmpty() ? List.of() : Arrays.asList(inner.split(", ")));
            Collections.sort(parts);
            return s.substring(0, open) + "{" + String.join(", ", parts) + "}" + s.substring(close + 1);
        }
        static void p(String label, DateTimeFormatter f, String t) {
            String r;
            try { r = sorted(f.parse(t).toString()); } catch (DateTimeParseException e) { r = "! @" + e.getErrorIndex() + " " + sorted(e.getMessage()); }
            System.out.println(label + " [" + t + "] " + r);
        }
        public static void main(String[] args) {
            DateTimeFormatter iw = new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_WEEK_DATE).appendLiteral(' ').appendValue(ChronoField.YEAR).toFormatter();
            p("iso week + year", iw, "2020-W53-5 2021");
            p("iso week + year", iw, "2020-W53-5 2020");
            DateTimeFormatter wy = new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(WeekFields.ISO.weekBasedYear()).appendLiteral(' ').appendValue(WeekFields.ISO.weekOfWeekBasedYear()).appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK).toFormatter();
            p("year + wby", wy, "2026 2026 10 6");
            DateTimeFormatter qy = new DateTimeFormatterBuilder().appendValue(IsoFields.WEEK_BASED_YEAR).appendLiteral(' ').appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR).appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK).appendLiteral(' ').appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral(' ').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter();
            p("all iso", qy, "2026 10 6 2026 1 66");
            DateTimeFormatter woyAndWby = new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(WeekFields.ISO.weekOfYear()).appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK).appendLiteral(' ').appendValue(WeekFields.ISO.weekBasedYear()).appendLiteral(' ').appendValue(WeekFields.ISO.weekOfWeekBasedYear()).toFormatter();
            p("woy and wby", woyAndWby, "2026 10 6 2026 10");
            p("woy and wby conflict", woyAndWby, "2026 10 6 2026 11");
        }
    }

    static class Widths {
        static String sorted(String s) {
            int open = s.indexOf('{');
            int close = s.indexOf('}');
            if (open < 0 || close < open) return s;
            String inner = s.substring(open + 1, close);
            List<String> parts = new ArrayList<>(inner.isEmpty() ? List.of() : Arrays.asList(inner.split(", ")));
            Collections.sort(parts);
            return s.substring(0, open) + "{" + String.join(", ", parts) + "}" + s.substring(close + 1);
        }
        static void p(String pattern, String... texts) {
            for (boolean lenient : new boolean[] {false, true}) {
                DateTimeFormatter f;
                try {
                    DateTimeFormatterBuilder b = new DateTimeFormatterBuilder();
                    if (lenient) b.parseLenient();
                    f = b.appendPattern(pattern).toFormatter(Locale.US);
                } catch (Exception e) { System.out.println(pattern + " ! " + e); return; }
                for (String t : texts) {
                    String r;
                    try { r = sorted(f.parse(t).toString()); } catch (DateTimeParseException e) { r = "! @" + e.getErrorIndex() + " " + sorted(e.getMessage()); }
                    System.out.println((lenient ? "L " : "S ") + pattern + " [" + t + "] " + r);
                }
            }
        }
        public static void main(String[] args) {
            p("Y", "2026", "+2026", "-5", "12345678901234567890", "02026");
            p("YY", "26", "126", "-6", "+26", "6");
            p("YYY", "2026", "026", "26", "-026", "+2026");
            p("YYYY", "2026", "+2026", "+12345", "12345", "-2026", "026");
            p("YYYYY", "02026", "2026", "+12345", "123456");
            p("w", "1", "12", "123", "-1", "+1", "01");
            p("ww", "01", "1", "123", "-1");
            p("W", "1", "12", "-1");
            p("e", "1", "12", "-1");
            p("ee", "01", "1", "123");
            p("c", "1", "12");
            p("Q", "1", "12", "-1", "+1");
            p("QQ", "01", "1");
            p("g", "1", "-1", "+1", "123456789012");
            p("gg", "01", "1", "-01", "+01", "001");
            p("YYYYww", "202610", "20261");
            p("YYYYwwe", "2026107", "202610", "20261077");
            p("Ywwe", "2026107", "202610");
            p("YYYYMMdd", "20260307");
            p("uuuuww", "202610");
            p("YYYYQ", "20261");
            p("QQYYYY", "012026");
            p("eYYYY", "72026");
            p("gHH", "5884910");
            p("Wdd", "107");
            for (int n = 4; n <= 20; n += 8) {
                String pat = "Y".repeat(n);
                try { System.out.println(pat + " fmt " + DateTimeFormatter.ofPattern(pat, Locale.US).format(LocalDate.of(2026, 3, 7))); } catch (Exception e) { System.out.println(pat + " fmt ! " + e); }
            }
            try { System.out.println("ggggggggggggggggggg fmt " + DateTimeFormatter.ofPattern("ggggggggggggggggggg").format(LocalDate.of(2026, 3, 7))); } catch (Exception e) { System.out.println("g19 fmt ! " + e); }
            try { System.out.println("YY fmt min " + DateTimeFormatter.ofPattern("YY", Locale.US).format(LocalDate.MIN)); } catch (Exception e) { System.out.println("YY fmt min ! " + e); }
            try { System.out.println("YYYY fmt max " + DateTimeFormatter.ofPattern("YYYY", Locale.US).format(LocalDate.MAX)); } catch (Exception e) { System.out.println("YYYY fmt max ! " + e); }
            try { System.out.println("Y fmt max " + DateTimeFormatter.ofPattern("Y", Locale.US).format(LocalDate.MAX)); } catch (Exception e) { System.out.println("Y fmt max ! " + e); }
            try { System.out.println("pad " + DateTimeFormatter.ofPattern("ppw|pppY|pe", Locale.US).format(LocalDate.of(2026, 3, 7))); } catch (Exception e) { System.out.println("pad ! " + e); }
            try { System.out.println("pad parse " + sorted(DateTimeFormatter.ofPattern("ppw|pppY", Locale.US).parse("10| 2026").toString())); } catch (Exception e) { System.out.println("pad parse ! " + e); }
            try { System.out.println("opt " + DateTimeFormatter.ofPattern("[w][e]", Locale.US).format(LocalTime.NOON)); } catch (Exception e) { System.out.println("opt ! " + e); }
            try { System.out.println("toString " + new DateTimeFormatterBuilder().appendPattern("YYwe").appendLiteral('x').appendPattern("Q").toFormatter()); } catch (Exception e) { System.out.println("toString ! " + e); }
        }
    }

    static class Owned {
        static String sorted(String s) {
            int open = s.indexOf('{');
            int close = s.indexOf('}');
            if (open < 0 || close < open) return s;
            String inner = s.substring(open + 1, close);
            List<String> parts = new ArrayList<>(inner.isEmpty() ? List.of() : Arrays.asList(inner.split(", ")));
            Collections.sort(parts);
            return s.substring(0, open) + "{" + String.join(", ", parts) + "}" + s.substring(close + 1);
        }
        static String parsed(DateTimeFormatter f, String text) {
            try {
                TemporalAccessor t = f.parse(text);
                return sorted(t.toString()) + " d=" + t.query(TemporalQueries.localDate()) + " t=" + t.query(TemporalQueries.localTime());
            } catch (DateTimeParseException e) {
                Throwable c = e.getCause();
                return "! @" + e.getErrorIndex() + " " + sorted(e.getMessage()) + (c == null ? "" : " <- " + c.getClass().getSimpleName());
            } catch (Exception e) {
                return "! " + e.getClass().getName() + ": " + e.getMessage();
            }
        }
        // a field of the program's own: what resolve does is given by a function
        static class Own implements TemporalField {
            final String name;
            final int mode;
            Own(String name, int mode) { this.name = name; this.mode = mode; }
            public TemporalUnit getBaseUnit() { return ChronoUnit.DAYS; }
            public TemporalUnit getRangeUnit() { return ChronoUnit.FOREVER; }
            public ValueRange range() { return ValueRange.of(0, 1000000); }
            public boolean isDateBased() { return true; }
            public boolean isTimeBased() { return false; }
            public boolean isSupportedBy(TemporalAccessor t) { return t.isSupported(ChronoField.EPOCH_DAY); }
            public ValueRange rangeRefinedBy(TemporalAccessor t) { return range(); }
            public long getFrom(TemporalAccessor t) { return t.getLong(ChronoField.EPOCH_DAY); }
            public <R extends Temporal> R adjustInto(R t, long v) { return (R) t.with(ChronoField.EPOCH_DAY, v); }
            public String toString() { return name; }
            public TemporalAccessor resolve(Map<TemporalField, Long> map, TemporalAccessor partial, ResolverStyle style) {
                System.out.println("  resolve " + name + " " + sorted(new TreeMap<String, Long>(map.entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue))).toString()) + " partial=" + (partial == null ? null : partial.getClass().getSimpleName()) + " " + style + " partialDate=" + partial.query(TemporalQueries.localDate()) + " supportsYear=" + partial.isSupported(ChronoField.YEAR));
                long v = map.get(this);
                switch (mode) {
                    case 0: map.remove(this); map.put(ChronoField.EPOCH_DAY, v); return null;
                    case 1: map.remove(this); return LocalDate.ofEpochDay(v);
                    case 2: return null; // leaves itself
                    case 3: map.remove(this); return LocalTime.ofSecondOfDay(v);
                    case 4: map.remove(this); return LocalDateTime.of(LocalDate.ofEpochDay(v), LocalTime.NOON);
                    case 5: map.remove(this); return ZonedDateTime.of(LocalDate.ofEpochDay(v), LocalTime.NOON, ZoneOffset.UTC);
                    case 6: map.remove(this); return Instant.ofEpochSecond(v);
                    case 7: map.remove(this); map.put(ChronoField.YEAR, v); map.put(ChronoField.DAY_OF_YEAR, 1L); return null;
                    case 8: map.remove(this); map.put(ChronoField.HOUR_OF_DAY, v); return null;
                    case 9: map.remove(this); return YearMonth.of(2020, 1);
                    case 10: map.remove(this); map.put(new Own(name + "'", 0), v); return null;
                    case 11: map.remove(this); throw new DateTimeException("own refusal");
                    default: return null;
                }
            }
        }
        public static void main(String[] args) {
            for (int mode = 0; mode <= 11; mode++) {
                Own own = new Own("Own" + mode, mode);
                DateTimeFormatter f = new DateTimeFormatterBuilder().appendValue(own).toFormatter();
                System.out.println("mode " + mode + " [100] " + parsed(f, "100"));
                DateTimeFormatter g = new DateTimeFormatterBuilder().appendValue(own).appendLiteral(' ').appendPattern("uuuu-MM-dd HH:mm").toFormatter();
                System.out.println("mode " + mode + " [100 1970-04-11 12:01] " + parsed(g, "100 1970-04-11 12:01"));
                System.out.println("mode " + mode + " [100 2000-01-01 12:01] " + parsed(g, "100 2000-01-01 12:01"));
                DateTimeFormatter h = new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm").appendLiteral(' ').appendValue(own).toFormatter();
                System.out.println("mode " + mode + " [2000-01-01 12:01 100] " + parsed(h, "2000-01-01 12:01 100"));
            }
            // two own fields: which resolves first
            Own a = new Own("A", 0);
            Own b = new Own("B", 1);
            DateTimeFormatter ab = new DateTimeFormatterBuilder().appendValue(a).appendLiteral(' ').appendValue(b).toFormatter();
            DateTimeFormatter ba = new DateTimeFormatterBuilder().appendValue(b).appendLiteral(' ').appendValue(a).toFormatter();
            // with week fields mixed
            DateTimeFormatter m = new DateTimeFormatterBuilder().appendValue(JulianFields.MODIFIED_JULIAN_DAY).appendLiteral(' ').appendValue(a).toFormatter();
            // a default value of a non chrono field
            DateTimeFormatter d = new DateTimeFormatterBuilder().appendPattern("YYYY-ww").parseDefaulting(WeekFields.SUNDAY_START.dayOfWeek(), 1).toFormatter(Locale.US);
            System.out.println("default dow [2026-10] " + parsed(d, "2026-10"));
            DateTimeFormatter d2 = new DateTimeFormatterBuilder().appendPattern("YYYY-ww").parseDefaulting(ChronoField.DAY_OF_WEEK, 1).toFormatter(Locale.US);
            System.out.println("default iso dow [2026-10] " + parsed(d2, "2026-10"));
            // localized with year of era
            System.out.println("yyyy-ww-e [2026-10-7] " + parsed(DateTimeFormatter.ofPattern("yyyy-ww-e", Locale.US), "2026-10-7"));
            System.out.println("YYYY-MM-dd [2026-03-07] " + parsed(DateTimeFormatter.ofPattern("YYYY-MM-dd", Locale.US), "2026-03-07"));
            System.out.println("YYYY-MM-dd strict [2026-03-07] " + parsed(DateTimeFormatter.ofPattern("YYYY-MM-dd", Locale.US).withResolverStyle(ResolverStyle.STRICT), "2026-03-07"));
            System.out.println("uuuu-MM-dd ww [2026-03-07 10] " + parsed(DateTimeFormatter.ofPattern("uuuu-MM-dd ww", Locale.US), "2026-03-07 10"));
            System.out.println("uuuu-MM-dd ww [2026-03-07 11] " + parsed(DateTimeFormatter.ofPattern("uuuu-MM-dd ww", Locale.US), "2026-03-07 11"));
            System.out.println("uuuu-MM-dd YYYY [2026-03-07 2025] " + parsed(DateTimeFormatter.ofPattern("uuuu-MM-dd YYYY", Locale.US), "2026-03-07 2025"));
            System.out.println("uuuu-MM-dd W [2026-03-07 2] " + parsed(DateTimeFormatter.ofPattern("uuuu-MM-dd W", Locale.US), "2026-03-07 2"));
            System.out.println("uuuu-MM-dd g [2026-03-07 1] " + parsed(DateTimeFormatter.ofPattern("uuuu-MM-dd g", Locale.US), "2026-03-07 1"));
            System.out.println("uuuu-MM-dd QQQ [2026-03-07 Q2] " + parsed(DateTimeFormatter.ofPattern("uuuu-MM-dd QQQ", Locale.US), "2026-03-07 Q2"));
            System.out.println("uuuu-MM-dd HH ww [2026-03-07 10 11] " + parsed(DateTimeFormatter.ofPattern("uuuu-MM-dd HH ww", Locale.US), "2026-03-07 10 11"));
            System.out.println("iso week + dom [2020-W53-5 2] " + parsed(new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_WEEK_DATE).appendLiteral(' ').appendValue(ChronoField.DAY_OF_MONTH).toFormatter(), "2020-W53-5 2"));
            System.out.println("iso week + y-m-d [2020-W53-5 2021-01-02] " + parsed(new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_WEEK_DATE).appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_DATE).toFormatter(), "2020-W53-5 2021-01-02"));
            System.out.println("y-m-d + iso week [2021-01-02 2020-W53-5] " + parsed(new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(DateTimeFormatter.ISO_WEEK_DATE).toFormatter(), "2021-01-02 2020-W53-5"));
            System.out.println("y-m-d + Q-dq [2021-01-02 1-2] " + parsed(new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter(), "2021-01-02 1-2"));
            System.out.println("y-m-d + Q-dq [2021-01-02 1-3] " + parsed(new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter(), "2021-01-02 1-3"));
            System.out.println("y + Q-dq + dow [2021 1-3 7] " + parsed(new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK).toFormatter(), "2021 1-3 7"));
            System.out.println("y + Q-dq + dow [2021 1-3 6] " + parsed(new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK).toFormatter(), "2021 1-3 6"));
            System.out.println("yoe + Q-dq [2021 1-3] " + parsed(new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR_OF_ERA).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter(), "2021 1-3"));
            System.out.println("yoe strict + Q-dq [2021 1-3] " + parsed(new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR_OF_ERA).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter().withResolverStyle(ResolverStyle.STRICT), "2021 1-3"));
            System.out.println("pm + y-Q-dq [2021 1-3] " + parsed(new DateTimeFormatterBuilder().appendValue(ChronoField.PROLEPTIC_MONTH).appendLiteral(' ').appendValue(IsoFields.QUARTER_OF_YEAR).appendLiteral('-').appendValue(IsoFields.DAY_OF_QUARTER).toFormatter(), "24252 1-3"));
        }
    }

    static class Forever {
        static class Own implements TemporalField {
            final String name; final int mode;
            Own(String name, int mode) { this.name = name; this.mode = mode; }
            public TemporalUnit getBaseUnit() { return ChronoUnit.DAYS; }
            public TemporalUnit getRangeUnit() { return ChronoUnit.FOREVER; }
            public ValueRange range() { return ValueRange.of(0, 1000000); }
            public boolean isDateBased() { return true; }
            public boolean isTimeBased() { return false; }
            public boolean isSupportedBy(TemporalAccessor t) { return false; }
            public ValueRange rangeRefinedBy(TemporalAccessor t) { return range(); }
            public long getFrom(TemporalAccessor t) { return 0; }
            @SuppressWarnings("unchecked") public <R extends Temporal> R adjustInto(R t, long v) { return t; }
            public String toString() { return name; }
            public TemporalAccessor resolve(Map<TemporalField, Long> map, TemporalAccessor partial, ResolverStyle style) {
                long v = map.get(this);
                switch (mode) {
                    case 0: map.remove(this); return LocalTime.ofSecondOfDay(v);
                    case 1: map.put(this, v + 1); return null;            // changes itself for ever
                    case 2: map.remove(this); map.put(new Own(name + "+", 2), v + 1); return null;   // replaced for ever
                    case 3: map.remove(this); return LocalDateTime.of(2020, 1, 1, 0, 0).plusSeconds(v);
                    case 4: return LocalDate.ofEpochDay(v);   // answers a date and stays
                    default: return null;
                }
            }
        }
        static void p(String label, DateTimeFormatter f, String t) {
            String r;
            try { r = f.parse(t).toString(); } catch (DateTimeParseException e) { r = "! @" + e.getErrorIndex() + " " + e.getMessage(); }
            System.out.println(label + " [" + t + "] " + r);
        }
        public static void main(String[] args) {
            p("two times same", new DateTimeFormatterBuilder().appendValue(new Own("A", 0)).appendLiteral(' ').appendValue(new Own("B", 0)).toFormatter(), "100 100");
            p("time and fields", new DateTimeFormatterBuilder().appendValue(new Own("A", 0)).appendLiteral(' ').appendPattern("HH:mm:ss").toFormatter(), "100 00:01:40");
            p("for ever", new DateTimeFormatterBuilder().appendValue(new Own("A", 1)).toFormatter(), "100");
            p("replaced", new DateTimeFormatterBuilder().appendValue(new Own("A", 2)).toFormatter(), "100");
            p("stays", new DateTimeFormatterBuilder().appendValue(new Own("A", 4)).toFormatter(), "100");
            p("time and ampm", new DateTimeFormatterBuilder().appendValue(new Own("A", 0)).appendLiteral(' ').appendPattern("a").toFormatter(Locale.US), "100 PM");
        }
    }

    static class Names {
        public static void main(String[] args) {
            for (ChronoField f : ChronoField.values()) {
                StringBuilder sb = new StringBuilder(f.toString());
                for (Locale l : new Locale[] {Locale.ROOT, Locale.ENGLISH, Locale.US, Locale.UK, Locale.CANADA}) sb.append(" | ").append(f.getDisplayName(l));
                System.out.println(sb);
            }
            for (TextStyle st : TextStyle.values()) {
                StringBuilder sb = new StringBuilder(st.toString());
                for (Locale l : new Locale[] {Locale.ROOT, Locale.US, Locale.UK, Locale.CANADA}) sb.append(" | ").append(Month.SEPTEMBER.getDisplayName(st, l)).append(' ').append(DayOfWeek.TUESDAY.getDisplayName(st, l));
                System.out.println(sb);
            }
        }
    }
}
