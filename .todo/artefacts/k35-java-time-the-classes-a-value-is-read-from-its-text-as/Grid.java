import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.ValueRange;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

// The probe k35 learned java.time from: every line is what a JDK 25 prints, and the
// interpreter, the JVM, wasm and the component print the same lines. Written here;
// nothing of a JDK is in it. A region with rules of its own (Europe/Paris) is not here:
// the library has none (Regions.java beside this file).
public class Grid {

    static long seed = 20261120L;

    static long next() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return seed >>> 16;
    }

    static long next(long from, long to) {
        return from + next() % (to - from + 1);
    }

    /** A message without the fields a JDK lists in an order that changes from run to run. */
    static String steady(String message) {
        if (message == null) {
            return "null";
        }
        int open = message.indexOf('{');
        int close = message.indexOf('}');
        return open >= 0 && close > open ? message.substring(0, open) + "{..}" + message.substring(close + 1) : message;
    }

    static void t(String label, Supplier<Object> made) {
        String text;
        try {
            text = String.valueOf(made.get());
        }
        catch (DateTimeParseException e) {
            Throwable cause = e.getCause();
            text = "! parse@" + e.getErrorIndex() + ": " + steady(e.getMessage())
                    + (cause == null ? "" : " <- " + cause.getClass().getName() + ": " + steady(cause.getMessage()));
        }
        catch (NullPointerException e) {
            text = "! NullPointerException";
        }
        catch (RuntimeException e) {
            text = "! " + e.getClass().getName() + ": " + steady(e.getMessage());
        }
        System.out.println(label + " = " + text);
    }

    static void read(String kind, Function<String, Object> parse, String... texts) {
        for (String text : texts) {
            t(kind + " [" + text + "]", () -> parse.apply(text));
        }
    }

    static void texts() {
        read("LocalDate", LocalDate::parse, "2020-01-01", "2020-1-01", "2020-13-01", "2021-02-29", "2020-02-29",
                "2021-02-30", "2021-04-31", "2021-00-10", "2021-01-00", "2021-01-32", "+2020-01-01", "-2020-01-01",
                "12020-01-01", "+12020-01-01", "-12020-01-01", "0000-01-01", "-0000-01-01", "+0000-01-01",
                "999999999-12-31", "+999999999-12-31", "-999999999-01-01", "+1000000000-01-01", "+9999999999-01-01",
                "+99999999999-01-01", "020-01-01", "2020-01-01x", " 2020-01-01", "", "2020-01", "2020/01/01",
                "2020-01-01T10:00", "2020-01-1", "2020-001-01", "20200101", "+02020-01-01", "-00001-01-01",
                "+00000-01-01", "2020-12-31", "1970-01-01", "2021-13-32", "2021-00-00", "2021-02-31", "-020-01-01",
                "-00000-01-01", "-0001-01-01", "+10000-01-01", "+1000000000-13-01", "-2020-01-01x", "+2020",
                "2020-01-01-", "2020-01-", "2020-", "2020", "202", "-", "+", "2020-0a-01", "2020-01-0a", "2021-02-29x",
                "-9999999999-01-01", "-99999999999-01-01", "+0000000001-01-01", "-0000000000-01-01", "2020-02-30",
                "2020-01-01".repeat(8), "1900-02-29", "2000-02-29", "2100-02-29", "-0004-02-29", "-0100-02-29");
        read("LocalTime", LocalTime::parse, "10:15", "10:15:30", "10:15:30.1", "10:15:30.123456789",
                "10:15:30.1234567890", "10:15:30.", "10:15:30,5", "24:00", "23:60", "23:59:60", "1:15", "10:5",
                "10:15:3", "10", "10:15:", "10:15:30.000", "10:15:00", "00:00", "T10:15", "10:15Z",
                "10:15:30.000000001", "10:15:30.0000000", "10-15", "99:99", "10:15:30+01:00", "+10:15", "10:15 ",
                "24:60", "25:61:61", "10:60:60", "10:15:60.5", "10:15:30.a", "10:15:30..", "10:15.5", "aa:15", "10:aa",
                "1", "", "10:15:30.123456789Z", "23:59:59.999999999", "24:00:00", "00:00:00.0", "10:15:30.5x",
                "25:00:61", "25:00:00", "23:00:61", "10:15:30.12", "10:15:30.1234", "10:15:30.12345678");
        read("LocalDateTime", LocalDateTime::parse, "2020-01-01T10:15", "2020-01-01t10:15", "2020-01-01 10:15",
                "2020-01-01T10:15:30.5", "2020-01-01T24:00", "2020-01-01T24:00:00", "2020-02-30T10:15",
                "2020-01-01T10:15Z", "2020-01-01", "2020-01-01T", "2020-01-01T10:15:30.123456789",
                "+12020-01-01T10:15", "2020-01-01T23:59:60", "2020-13-01T24:60", "2020-01-01T24:60",
                "2020-02-30T25:00", "2020-02-30T10:15x", "2020-13-01T10:15:30.5x", "+2020-01-01T10:15", "",
                "-999999999-01-01T00:00", "+999999999-12-31T23:59:59.999999999");
        read("OffsetDateTime", OffsetDateTime::parse, "2020-01-01T10:15+01:00", "2020-01-01T10:15Z",
                "2020-01-01T10:15z", "2020-01-01T10:15:30.5-08:00", "2020-01-01T10:15+01", "2020-01-01T10:15+0100",
                "2020-01-01T10:15+01:00:30", "2020-01-01T10:15+010030", "2020-01-01T10:15+18:00",
                "2020-01-01T10:15+18:01", "2020-01-01T10:15-18:00", "2020-01-01T10:15+19:00",
                "2020-01-01T10:15+00:00", "2020-01-01T10:15-00:00", "2020-01-01T10:15+1", "2020-01-01T10:15+1:00",
                "2020-01-01T10:15+01:0", "2020-01-01T10:15+01:60", "2020-01-01T10:15+01:00:60", "2020-01-01T10:15",
                "2020-01-01T10:15GMT", "2020-01-01T10:15 +01:00", "2020-01-01T10:15+01:00x", "2020-01-01T10:15+0",
                "2020-01-01T10:15+", "2020-01-01T10:15+01:", "2020-01-01T10:15+01:00:", "2020-01-01T10:15+01:00:3",
                "2020-01-01T10:15+01003", "2020-01-01T10:15+0100:30", "2020-01-01T10:15+01:0030",
                "2020-01-01T24:00+01:00", "-999999999-01-01T00:00+18:00",
                "+999999999-12-31T23:59:59.999999999-18:00", "2020-13-01T24:00+19:00", "2020-01-01T10:15+19:00:00",
                "2020-01-01T10:15+99:00", "2020-01-01T10:15+99:99", "2020-01-01T10:15+18:00:01",
                "2020-01-01T10:15-18:00:01", "2020-01-01T10:15+17:59:59", "2020-01-01T25:15+19:00",
                "2020-02-30T10:15+19:00", "2020-01-01T10:15ZZ", "2020-01-01T10:15+00", "2020-01-01T10:15-00",
                "2020-01-01T10:15+00:00:00", "2020-01-01T10:15-01:00:00", "2020-01-01T10:15+01:30",
                "2020-01-01T10:15-00:30", "2020-01-01T10:15-00:00:30", "2020-01-01T10:15+0a:00",
                "2020-01-01T10:15+01:a0", "2020-01-01T10:15+01:00:a0", "2020-01-01T10:15+2", "2020-01-01T10:15+200",
                "2020-01-01T10:15+20:0", "2020-01-01T10:15+18", "2020-01-01T10:15+19", "2020-01-01T10:15+18:00:00",
                "2020-01-01T10:15+23", "2020-01-01T10:15+24", "2020-01-01T10:15+29:00", "2020-01-01T10:15+30",
                "2020-01-01T10:15+59", "2020-01-01T10:15+60", "2020-01-01T10:15+23:59:59", "2020-01-01T10:15+24:00",
                "2020-01-01T10:15+18:60", "2020-01-01T10:15+18:00:60");
        read("ZonedDateTime", ZonedDateTime::parse, "2020-01-01T10:15+01:00", "2020-01-01T10:15Z",
                "2020-01-01T10:15Z[UTC]", "2020-01-01T10:15Z[GMT]", "2020-01-01T10:15Z[UT]", "2020-01-01T10:15Z[Z]",
                "2020-01-01T10:15+01:00[+01:00]", "2020-01-01T10:15+01:00[+02:00]",
                "2020-01-01T10:15+01:00[UTC+01:00]", "2020-01-01T10:15+01:00[GMT+1]",
                "2020-01-01T10:15+01:00[UT+0100]", "2020-01-01T10:15+01:00[UTC]", "2020-01-01T10:15+01:00[Nowhere/None]",
                "2020-01-01T10:15+01:00[]", "2020-01-01T10:15+01:00[UTC", "2020-01-01T10:15[UTC]",
                "2020-01-01T10:15+01:00[Etc/GMT+5]", "2020-01-01T10:15+01:00[Etc/UTC]", "2020-01-01T10:15+01:00[utc]",
                "2020-01-01T10:15+01:00[z]", "2020-01-01T10:15+01:00[UTC+1]", "2020-01-01T10:15+01:00[UTC+19]",
                "2020-01-01T10:15+01:00[GMT0]", "2020-01-01T10:15+01:00[+01]", "2020-01-01T10:15+01:00[+0100]",
                "2020-01-01T10:15+01:00[+1]", "2020-01-01T10:15+01:00[-01:30:15]",
                "2020-01-01T10:15+01:00[UTC-01:30:15]", "2020-01-01T10:15+01:00[UTC+00:00]",
                "2020-01-01T10:15+01:00[GMT-0]", "2020-01-01T10:15+01:00[+00:00]", "2020-01-01T10:15+01:00[X]",
                "2020-01-01T10:15+01:00[UTC]x", "2020-01-01T10:15+01:00[UTCx]", "2020-01-01T10:15+01:00[UTC+]",
                "2020-01-01T10:15+01:00[UTC+18:00]", "2020-01-01T10:15+01:00[UTC+18:01]",
                "2020-01-01T10:15+01:00[UTC+19:00]", "2020-01-01T10:15+01:00[+19:00]",
                "2020-01-01T10:15+01:00[GMT+01:00]", "2020-01-01T10:15+01:00[UT+01:00]",
                "2020-01-01T10:15+01:00[UT-01:00:01]", "2020-01-01T10:15+01:00[GMT+00:00]",
                "2020-01-01T10:15+01:00[UT-00:00]", "2020-01-01T10:15+01:00[UTC-00:00]",
                "2020-01-01T10:15+01:00[GMT-00:00:00]", "2020-01-01T10:15+01:00[UTC+01:00:00]",
                "2020-01-01T10:15+01:00[UTC+01:30:00]", "2020-01-01T10:15+01:00[Etc/GMT-14]",
                "2020-01-01T10:15+01:00[Etc/GMT+12]", "2020-01-01T10:15+01:00[Etc/GMT+13]",
                "2020-01-01T10:15+01:00[Etc/GMT-15]", "2020-01-01T10:15+01:00[Etc/GMT+01]",
                "2020-01-01T10:15+01:00[Etc/GMT]", "2020-01-01T10:15+01:00[Zulu]", "2020-01-01T10:15+01:00[UCT]",
                "2020-01-01T10:15+01:00[Universal]", "2020-01-01T10:15+01:00[Greenwich]",
                "2020-01-01T10:15+01:00[Etc/Zulu]", "2020-01-01T10:15+01:00[Etc/Universal]",
                "2020-01-01T10:15+01:00[Etc/Greenwich]", "2020-01-01T10:15+01:00[Etc/UCT]",
                "2020-01-01T10:15+01:00[GMT+0]", "2020-01-01T10:15+01:00[Etc/GMT+0]",
                "2020-01-01T10:15+01:00[Etc/GMT-0]", "2020-01-01T10:15+01:00[Etc/GMT0]",
                "2020-01-01T10:15+01:00[Etc/UT]", "2020-01-01T10:15+01:00[Etc/GMT+]",
                "2020-01-01T10:15+01:00[UTC][UTC]", "2020-01-01T10:15+01:00 [UTC]", "2020-01-01T10:15+19:00[UTC]",
                "2020-02-30T10:15+01:00[UTC]", "+999999999-12-31T23:59:59.999999999-18:00[+18:00]",
                "-999999999-01-01T00:00+18:00[-18:00]", "+999999999-12-31T23:59:59.999999999Z[+18:00]",
                "2020-01-01T10:15+01:00[UTC+01:00:30]", "2020-01-01T10:15+01:00[Z]x", "2020-01-01T10:15+01:00[UTC0]",
                "2020-01-01T10:15+01:00[UTCZ]", "2020-01-01T10:15z[Z]", "2020-01-01T10:15+01:00[UTC+01]",
                "2020-01-01T10:15+01:00[UTC+0100]", "2020-01-01T10:15+01:00[+01:60]",
                "2020-01-01T10:15+01:00[+01:00:60]", "2020-01-01T10:15+01[+02:00]", "2020-01-01T10:15:30.5Z[UTC]");
        read("Instant", Instant::parse, "2020-01-01T10:15:30Z", "2020-01-01T10:15Z", "2020-01-01T10:15:30.123456789Z",
                "2020-01-01T10:15:30z", "2020-01-01t10:15:30Z", "2020-01-01T10:15:30+01:00",
                "2020-01-01T10:15:30+01", "2020-01-01T10:15:30+0100", "2020-01-01T10:15:30+01:00:30",
                "2020-01-01T24:00:00Z", "2020-01-01T24:00:01Z", "2020-12-31T23:59:60Z", "2020-06-30T23:59:60Z",
                "2020-06-30T22:59:60Z", "2020-06-30T23:59:60.5Z", "2020-01-01T10:15:30", "2020-01-01T10:15:30.Z",
                "2020-01-01T10:15:30,5Z", "+1000000000-12-31T23:59:59.999999999Z", "-1000000000-01-01T00:00:00Z",
                "+1000000001-01-01T00:00:00Z", "-1000000001-12-31T23:59:59Z", "12020-01-01T00:00:00Z",
                "+12020-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "0000-01-01T00:00:00Z", "2020-02-30T00:00:00Z",
                "2020-01-01T10:15:30Z[UTC]", "2020-01-01T10:15:30-00:00", "2020-01-01T25:00:00Z",
                "2020-01-01T10:60:00Z", "2020-01-01T10:15:61Z", "2020-01-01T10:15:30+18:00",
                "2020-01-01T10:15:30+18:01", "2020-01-01T24:00Z", "+1000000000-12-31T23:59:59.999999999-01:00",
                "2020-01-01T10:15:30.1234567890Z", "2020-01-01T10:15:30 Z", "2020-01-01T23:59:60+01:00",
                "2020-01-01T00:59:60+01:00", "2020-13-01T25:00:00Z", "2020-01-01T10:15:30+19:00",
                "2020-01-01T10:15:30+01:60", "2020-01-01T10:15:30+01:00:60", "2020-01-01T10:15:30+1",
                "2020-01-01T10:15:30+01:0", "2020-01-01T10:15:30+01:00:3", "2020-01-01T10:15:30+01:00x",
                "2020-01-01T10:15:30Zx", "2020-01-01T10:15:30+00:00", "-1000000000-01-01T00:00:00+00:00:01",
                "+1000000000-12-31T23:59:60Z", "+1000000000-12-31T24:00:00Z",
                "-1000000000-01-01T00:00:00.000000001+00:00:01", "2020-1-01T10:15:30Z", "2020-01-01T1:15:30Z",
                "2020-01-01T10:15:3Z", "+2020-01-01T10:15:30Z", "-2020-01-01T10:15:30Z", "-0000-01-01T10:15:30Z",
                "+99999999999-01-01T00:00:00Z", "+9999999999-01-01T00:00:00Z", "2020-01-01T10:15:30.5",
                "2020-01-01T23:59:60.999999999Z", "2020-12-31T24:00:00.5Z", "2020-12-31T24:00:00.0Z",
                "2020-12-31T24:00:00.Z", "2020-01-01T10:15:30+23:00", "2020-01-01T10:15:30+24:00",
                "2020-01-01T10:15:30+99:00", "2021-02-29T00:00:00Z", "2020-02-29T24:00:00Z", "2020-00-10T00:00:00Z",
                "2020-01-00T00:00:00Z", "1969-12-31T23:59:59.999999999Z", "2020-01-01T10:15:30.5+05:30");
        read("Year", Year::parse, "2020", "+2020", "-2020", "12020", "+12020", "0", "0000", "-0000", "999999999",
                "+999999999", "-999999999", "+1000000000", "20", "020", "2020x", "", "+02020", "202O", "-0001",
                "+99999999999", "+0000000001", "1000000000", "00000000001", "+0999999999", "0999999999",
                "0000000000", "99999999999", "-1000000000", "-0999999999", "+", "-", "+a", "2 020", "+-1", "000000001");
        read("YearMonth", YearMonth::parse, "2020-01", "2020-13", "2020-1", "+12020-12", "-0001-06", "2020-00",
                "202001", "2020-01-01", "+2020-01", "12020-01", "2020-12x", "", "-999999999-01", "+999999999-12",
                "+1000000000-01", "-0000-01", "+00000-01", "2020-", "2020", "2020-0a", "+9999999999-01",
                "+99999999999-01", "2020-13x", "0000-12", "0999-01", "9999-12", "+10000-01");
        read("Period", Period::parse, "P1Y2M3D", "P1Y", "P2M", "P3D", "P4W", "P1Y2M3W4D", "p1y2m3d", "-P1Y2M3D",
                "+P1Y2M3D", "P-1Y2M-3D", "P+1Y", "P", "PT1H", "P1Y2M3DT", "P1D1Y", "P1.5Y", "P2147483647D",
                "P2147483648D", "P306783379W", "P306783378W", "-P2147483648D", "P-2147483648D", "-P-2147483648D",
                "P0D", "P0Y0M0D", "1Y", "P1Y ", "", "P1M1W", "P1W1D", "P1y1D", "P0000000000000001D", "P1Y2M3D4W",
                "P1Y1Y", "P1Y2M", "P1Y3D", "P1M3D", "P1Y2W", "P-1W", "-P-1W-1D", "P+1Y+2M+3D", "P++1Y", "P1Y2", "PY",
                "P-Y", "P1X", "P 1Y", "P1Y2M3D ", "--P1Y", "P12345678901Y", "P2147483647Y2147483647M2147483647D",
                "-P-2147483648Y", "P306783378W1D", "P306783378W2D", "P-306783378W-2D", "P-306783379W",
                "-P306783379W", "P-2147483649D", "P+2147483648M", "x".repeat(70));
        read("Duration", Duration::parse, "PT1H", "P1DT2H3M4.5S", "PT-0.5S", "-PT1H", "P1D", "PT", "P", "pt1m");
        t("null texts", () -> {
            StringBuilder all = new StringBuilder();
            List<Function<String, Object>> parsers = List.of(LocalDate::parse, LocalTime::parse, LocalDateTime::parse,
                    OffsetDateTime::parse, ZonedDateTime::parse, Instant::parse, Year::parse, YearMonth::parse,
                    Period::parse);
            for (Function<String, Object> parse : parsers) {
                try {
                    parse.apply(null);
                }
                catch (NullPointerException e) {
                    all.append(e.getMessage()).append(' ');
                }
            }
            return all;
        });
        t("a builder's text", () -> LocalDate.parse(new StringBuilder("2020-02-02")) + " "
                + Instant.parse(new StringBuilder("2020-02-02T00:00:00Z")));
    }

    static final String[] DATES = {"2020-02-29", "2021-02-28", "1970-01-01", "1969-12-31", "2000-01-01", "1999-12-31",
        "0000-01-01", "-0001-12-31", "0004-02-29", "1900-03-01", "2100-02-28", "2024-12-31", "2023-01-31",
        "9999-12-31", "+10000-01-01", "-9999-06-15", "+999999999-12-31", "-999999999-01-01", "1582-10-15",
        "2026-11-20", "2019-08-31", "2016-03-31", "0001-01-01", "-0400-03-01", "2021-12-31"};

    static final String[] TIMES = {"00:00", "12:00", "23:59:59.999999999", "10:15", "10:15:30", "10:15:30.5",
        "10:15:30.123456", "10:15:30.000000001", "00:00:01", "06:30:15.250"};

    static final String[] OFFSETS = {"Z", "+01:00", "-08:00", "+05:30", "+18:00", "-18:00", "+00:00:01", "-03:30:15"};

    static final String[] ZONES = {"Z", "+01:00", "UTC", "GMT+02:00", "UT-03:00", "Etc/GMT+5", "Etc/UTC", "GMT",
        "-18:00", "UTC+18:00"};

    static final long[] AMOUNTS = {0, 1, -1, 7, 28, 30, -31, 59, 60, 365, -366, 1000, -100000, 12, 13, -25, 400, 1200,
        146097, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, 999999999L, -999999999L, 3000000000L,
        86400L, 86399L, 1000000000L, 123456789012L, -1000000007L};

    /** Every field of a value: whether it has it, its range there, and its value both ways. */
    static void fields(String label, TemporalAccessor value) {
        StringBuilder text = new StringBuilder();
        for (ChronoField field : ChronoField.values()) {
            text.append(field).append(value.isSupported(field) ? "+" : "-");
            try {
                text.append(value.range(field));
            }
            catch (RuntimeException e) {
                text.append("!r ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
            }
            text.append(' ');
            try {
                text.append(value.getLong(field));
            }
            catch (RuntimeException e) {
                text.append("!l ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
            }
            text.append(' ');
            try {
                text.append(value.get(field));
            }
            catch (RuntimeException e) {
                text.append("!g ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
            }
            text.append("; ");
        }
        System.out.println(label + " fields = " + text);
    }

    /** Every unit of a value: whether it has it, and one and three of it added and taken. */
    static void units(String label, Temporal value) {
        for (ChronoUnit unit : ChronoUnit.values()) {
            t(label + " " + unit, () -> value.isSupported(unit) + " " + value.plus(1, unit) + " " + value.minus(3, unit)
                    + " " + value.plus(-1, unit).until(value, unit));
        }
    }

    /** A field set to its limits, around them and in between. */
    static void sets(String label, Temporal value) {
        for (ChronoField field : ChronoField.values()) {
            ValueRange range = field.range();
            long[] values = {range.getMinimum(), range.getMaximum(), range.getMinimum() - 1, range.getMaximum() + 1,
                range.getSmallestMaximum(), 1, 0, 12, 29, 366};
            StringBuilder text = new StringBuilder();
            for (long to : values) {
                try {
                    text.append(value.with(field, to));
                }
                catch (RuntimeException e) {
                    text.append("! ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage());
                }
                text.append("; ");
            }
            System.out.println(label + " with " + field + " = " + text);
        }
    }

    static void between(String label, Temporal from, Temporal to) {
        StringBuilder text = new StringBuilder();
        for (ChronoUnit unit : ChronoUnit.values()) {
            try {
                text.append(from.until(to, unit)).append('=').append(unit.between(from, to));
            }
            catch (RuntimeException e) {
                text.append('!').append(e.getClass().getSimpleName());
            }
            text.append(' ');
        }
        System.out.println(label + " until = " + text);
    }

    static void dates() {
        t("constants", () -> LocalDate.MIN + " " + LocalDate.MAX + " " + LocalDate.EPOCH + " " + LocalDate.MIN.toEpochDay()
                + " " + LocalDate.MAX.toEpochDay());
        List<LocalDate> dates = new ArrayList<>();
        for (String text : DATES) {
            LocalDate date = LocalDate.parse(text);
            dates.add(date);
            t(text + " parts", () -> date.getYear() + " " + date.getMonth() + " " + date.getMonthValue() + " "
                    + date.getDayOfMonth() + " " + date.getDayOfYear() + " " + date.getDayOfWeek() + " "
                    + date.isLeapYear() + " " + date.lengthOfMonth() + " " + date.lengthOfYear() + " "
                    + date.toEpochDay() + " " + date.hashCode() + " " + LocalDate.ofEpochDay(date.toEpochDay()) + " "
                    + LocalDate.ofYearDay(date.getYear(), date.getDayOfYear()) + " " + date.atStartOfDay() + " "
                    + date.atTime(10, 15) + " " + date.atTime(10, 15, 30) + " " + date.atTime(10, 15, 30, 5) + " "
                    + date.atTime(LocalTime.NOON) + " " + date.atStartOfDay(ZoneOffset.ofHours(2)) + " "
                    + date.toEpochSecond(LocalTime.NOON, ZoneOffset.ofHours(-5)) + " " + LocalDate.from(date));
            for (long amount : AMOUNTS) {
                t(text + " plus " + amount, () -> date.plusDays(amount) + " " + date.minusDays(amount));
                t(text + " weeks " + amount, () -> date.plusWeeks(amount) + " " + date.minusWeeks(amount));
                t(text + " months " + amount, () -> date.plusMonths(amount) + " " + date.minusMonths(amount));
                t(text + " years " + amount, () -> date.plusYears(amount) + " " + date.minusYears(amount));
            }
            for (int to : new int[] {1, 2, 12, 28, 29, 30, 31, 0, 32, 13, 366, 365, 2021, 2024, -1, 999999999,
                1000000000}) {
                t(text + " withYear " + to, () -> date.withYear(to));
                t(text + " withMonth " + to, () -> date.withMonth(to));
                t(text + " withDayOfMonth " + to, () -> date.withDayOfMonth(to));
                t(text + " withDayOfYear " + to, () -> date.withDayOfYear(to));
            }
            fields(text, date);
            units(text, date);
            t(text + " amounts", () -> date.plus(Period.of(1, 2, 3)) + " " + date.minus(Period.of(1, 2, 3)) + " "
                    + date.plus(Period.ofDays(-31)) + " " + date.plus(Period.ofMonths(-1)) + " "
                    + date.with(Month.FEBRUARY) + " " + date.with(DayOfWeek.MONDAY) + " " + date.with(LocalDate.EPOCH));
            t(text + " a duration", () -> date.plus(Duration.ofDays(1)));
        }
        sets("2020-02-29", dates.get(0));
        sets("2021-12-31", dates.get(24));
        sets("-0001-12-31", dates.get(7));
        for (LocalDate from : dates) {
            for (LocalDate to : dates) {
                t(from + " " + to, () -> from.compareTo(to) + " " + from.isBefore(to) + " " + from.isAfter(to) + " "
                        + from.isEqual(to) + " " + from.equals(to) + " " + from.until(to) + " "
                        + Period.between(from, to) + " " + from.until(to, ChronoUnit.DAYS) + " "
                        + from.until(to, ChronoUnit.WEEKS) + " " + from.until(to, ChronoUnit.MONTHS) + " "
                        + from.until(to, ChronoUnit.YEARS) + " " + from.until(to, ChronoUnit.DECADES) + " "
                        + from.until(to, ChronoUnit.CENTURIES) + " " + from.until(to, ChronoUnit.MILLENNIA) + " "
                        + from.until(to, ChronoUnit.ERAS) + " " + ChronoUnit.DAYS.between(from, to));
            }
        }
        between("a date and a time", dates.get(0), dates.get(1));
        t("of", () -> LocalDate.of(2020, 2, 29) + " " + LocalDate.of(2020, Month.MARCH, 1) + " "
                + LocalDate.ofYearDay(2020, 366) + " " + LocalDate.ofYearDay(2021, 365) + " " + LocalDate.ofEpochDay(0)
                + " " + LocalDate.ofEpochDay(-1) + " " + LocalDate.ofEpochDay(365241780471L) + " "
                + LocalDate.ofEpochDay(-365243219162L) + " " + LocalDate.ofInstant(Instant.EPOCH, ZoneOffset.ofHours(-1)));
        int[][] refused = {{2021, 2, 29}, {2021, 2, 30}, {2020, 2, 30}, {2021, 4, 31}, {2021, 13, 1}, {2021, 0, 1},
            {2021, 1, 0}, {2021, 1, 32}, {1000000000, 1, 1}, {-1000000000, 1, 1}, {1000000000, 13, 32}, {2021, 13, 32},
            {2021, 6, 31}, {2021, 9, 31}, {2021, 11, 31}, {1900, 2, 29}};
        for (int[] ymd : refused) {
            t("of " + ymd[0] + " " + ymd[1] + " " + ymd[2], () -> LocalDate.of(ymd[0], ymd[1], ymd[2]));
        }
        t("ofYearDay 2021 366", () -> LocalDate.ofYearDay(2021, 366));
        t("ofYearDay 2021 0", () -> LocalDate.ofYearDay(2021, 0));
        t("ofYearDay 2021 367", () -> LocalDate.ofYearDay(2021, 367));
        t("ofYearDay 1000000000 1", () -> LocalDate.ofYearDay(1000000000, 1));
        t("ofEpochDay above", () -> LocalDate.ofEpochDay(365241780472L));
        t("ofEpochDay below", () -> LocalDate.ofEpochDay(-365243219163L));
        t("from a time", () -> LocalDate.from(LocalTime.NOON));
        t("from a date-time", () -> LocalDate.from(LocalDateTime.parse("2020-01-01T10:15")) + " "
                + LocalDate.from(OffsetDateTime.parse("2020-01-01T10:15Z")) + " "
                + LocalDate.from(ZonedDateTime.parse("2020-01-01T10:15Z")));
        for (int days : new int[] {1, 27, 28, 29, 30, 31, 32, 58, 59, 60, 61, 100, -1, -30, -31, -32, -400}) {
            t("the last day plus " + days, () -> LocalDate.MAX.plusDays(days) + " " + LocalDate.of(999999999, 12, 1)
                .plusDays(days));
            t("the first day minus " + days, () -> LocalDate.MIN.minusDays(days) + " " + LocalDate.of(-999999999, 1, 31)
                .minusDays(days));
            t("a month's end plus " + days, () -> LocalDate.of(2021, 1, 31).plusDays(days) + " "
                    + LocalDate.of(2020, 12, 31).plusDays(days));
        }
    }

    static void times() {
        t("constants", () -> LocalTime.MIN + " " + LocalTime.MAX + " " + LocalTime.MIDNIGHT + " " + LocalTime.NOON);
        List<LocalTime> times = new ArrayList<>();
        for (String text : TIMES) {
            LocalTime time = LocalTime.parse(text);
            times.add(time);
            t(text + " parts", () -> time.getHour() + " " + time.getMinute() + " " + time.getSecond() + " "
                    + time.getNano() + " " + time.toSecondOfDay() + " " + time.toNanoOfDay() + " " + time.hashCode()
                    + " " + LocalTime.ofNanoOfDay(time.toNanoOfDay()) + " " + LocalTime.ofSecondOfDay(time.toSecondOfDay())
                    + " " + time.atDate(LocalDate.EPOCH) + " "
                    + time.toEpochSecond(LocalDate.of(2020, 1, 1), ZoneOffset.ofHours(1)) + " " + LocalTime.from(time));
            for (long amount : AMOUNTS) {
                t(text + " plus " + amount, () -> time.plusHours(amount) + " " + time.minusHours(amount) + " "
                        + time.plusMinutes(amount) + " " + time.minusMinutes(amount) + " " + time.plusSeconds(amount)
                        + " " + time.minusSeconds(amount) + " " + time.plusNanos(amount) + " " + time.minusNanos(amount));
            }
            for (int to : new int[] {0, 1, 12, 23, 24, 59, 60, -1, 999999999, 1000000000}) {
                t(text + " with " + to, () -> time.withHour(to));
                t(text + " withMinute " + to, () -> time.withMinute(to));
                t(text + " withSecond " + to, () -> time.withSecond(to));
                t(text + " withNano " + to, () -> time.withNano(to));
            }
            fields(text, time);
            units(text, time);
            for (ChronoUnit unit : ChronoUnit.values()) {
                t(text + " truncated to " + unit, () -> time.truncatedTo(unit));
            }
            t(text + " amounts", () -> time.plus(Duration.ofMinutes(90)) + " " + time.minus(Duration.ofNanos(1)) + " "
                    + time.with(LocalTime.NOON));
            t(text + " a period", () -> time.plus(Period.ofDays(1)));
        }
        sets("10:15:30.123456", times.get(6));
        sets("23:59:59.999999999", times.get(2));
        for (LocalTime from : times) {
            for (LocalTime to : times) {
                t(from + " " + to, () -> from.compareTo(to) + " " + from.isBefore(to) + " " + from.isAfter(to) + " "
                        + from.equals(to) + " " + Duration.between(from, to));
                between(from + " " + to, from, to);
            }
        }
        int[][] refused = {{24, 0, 0, 0}, {-1, 0, 0, 0}, {0, 60, 0, 0}, {0, -1, 0, 0}, {0, 0, 60, 0}, {0, 0, -1, 0},
            {0, 0, 0, 1000000000}, {0, 0, 0, -1}, {24, 60, 60, -1}, {23, 59, 59, 999999999}};
        for (int[] hmsn : refused) {
            t("of " + hmsn[0] + " " + hmsn[1] + " " + hmsn[2] + " " + hmsn[3],
                    () -> LocalTime.of(hmsn[0], hmsn[1], hmsn[2], hmsn[3]));
        }
        t("of", () -> LocalTime.of(1, 2) + " " + LocalTime.of(1, 2, 3) + " " + LocalTime.of(1, 2, 3, 4000) + " "
                + LocalTime.ofInstant(Instant.ofEpochSecond(-1, 5), ZoneOffset.ofHours(3)));
        t("ofSecondOfDay 86400", () -> LocalTime.ofSecondOfDay(86400));
        t("ofSecondOfDay -1", () -> LocalTime.ofSecondOfDay(-1));
        t("ofNanoOfDay above", () -> LocalTime.ofNanoOfDay(86400000000000L));
        t("ofNanoOfDay -1", () -> LocalTime.ofNanoOfDay(-1));
        t("from a date", () -> LocalTime.from(LocalDate.EPOCH));
    }

    static void dateTimes() {
        t("constants", () -> LocalDateTime.MIN + " " + LocalDateTime.MAX);
        List<LocalDateTime> all = new ArrayList<>();
        String[] dates = {"2020-02-29", "1969-12-31", "0000-01-01", "+999999999-12-31", "-999999999-01-01",
            "2021-12-31", "2023-01-31"};
        String[] times = {"00:00", "23:59:59.999999999", "10:15:30.5", "12:00"};
        for (String day : dates) {
            for (String clock : times) {
                String text = day + "T" + clock;
                LocalDateTime value = LocalDateTime.parse(text);
                all.add(value);
                t(text + " parts", () -> value.toLocalDate() + " " + value.toLocalTime() + " " + value.getYear() + " "
                        + value.getMonth() + " " + value.getMonthValue() + " " + value.getDayOfMonth() + " "
                        + value.getDayOfYear() + " " + value.getDayOfWeek() + " " + value.getHour() + " "
                        + value.getMinute() + " " + value.getSecond() + " " + value.getNano() + " " + value.hashCode()
                        + " " + value.toEpochSecond(ZoneOffset.ofHours(9)) + " " + LocalDateTime.from(value));
                t(text + " moved", () -> value.atOffset(ZoneOffset.ofHours(-3)) + " " + value.atZone(ZoneId.of("UTC"))
                        + " " + value.toInstant(ZoneOffset.UTC));
                for (long amount : AMOUNTS) {
                    t(text + " plus " + amount, () -> value.plusHours(amount) + " " + value.minusHours(amount));
                    t(text + " minutes " + amount, () -> value.plusMinutes(amount) + " " + value.minusMinutes(amount));
                    t(text + " seconds " + amount, () -> value.plusSeconds(amount) + " " + value.minusSeconds(amount));
                    t(text + " nanos " + amount, () -> value.plusNanos(amount) + " " + value.minusNanos(amount));
                    t(text + " days " + amount, () -> value.plusDays(amount) + " " + value.minusWeeks(amount) + " "
                            + value.plusMonths(amount) + " " + value.minusYears(amount));
                }
                units(text, value);
                for (ChronoUnit unit : ChronoUnit.values()) {
                    t(text + " truncated to " + unit, () -> value.truncatedTo(unit));
                }
                t(text + " amounts", () -> value.plus(Duration.ofHours(25)) + " " + value.minus(Duration.ofNanos(1))
                        + " " + value.plus(Period.of(0, 1, 1)) + " " + value.minus(Period.ofYears(4)) + " "
                        + value.with(LocalTime.NOON) + " " + value.with(LocalDate.EPOCH) + " " + value.withYear(2024)
                        + " " + value.withMonth(2) + " " + value.withDayOfMonth(28) + " " + value.withDayOfYear(60)
                        + " " + value.withHour(1) + " " + value.withMinute(2) + " " + value.withSecond(3) + " "
                        + value.withNano(4));
            }
        }
        fields("2020-02-29T10:15:30.5", all.get(2));
        sets("2020-02-29T10:15:30.5", all.get(2));
        for (LocalDateTime from : all) {
            for (LocalDateTime to : all) {
                t(from + " " + to, () -> Integer.signum(from.compareTo(to)) + " " + from.isBefore(to) + " "
                        + from.isAfter(to) + " " + from.isEqual(to) + " " + from.equals(to));
                between(from + " " + to, from, to);
                t(from + " " + to + " duration", () -> Duration.between(from, to));
            }
        }
        t("of", () -> LocalDateTime.of(2020, 2, 29, 1, 2) + " " + LocalDateTime.of(2020, 2, 29, 1, 2, 3) + " "
                + LocalDateTime.of(2020, 2, 29, 1, 2, 3, 4) + " " + LocalDateTime.of(2020, Month.MAY, 29, 1, 2) + " "
                + LocalDateTime.of(2020, Month.MAY, 29, 1, 2, 3) + " " + LocalDateTime.of(2020, Month.MAY, 29, 1, 2, 3, 4)
                + " " + LocalDateTime.of(LocalDate.EPOCH, LocalTime.NOON) + " "
                + LocalDateTime.ofInstant(Instant.ofEpochSecond(1, 2), ZoneOffset.ofHours(-1)) + " "
                + LocalDateTime.ofEpochSecond(-1, 999999999, ZoneOffset.UTC));
        t("of refused", () -> LocalDateTime.of(2021, 2, 29, 25, 0));
        t("of a time refused", () -> LocalDateTime.of(2021, 2, 28, 25, 61));
        t("ofEpochSecond nanos", () -> LocalDateTime.ofEpochSecond(0, 1000000000, ZoneOffset.UTC));
        t("ofEpochSecond far", () -> LocalDateTime.ofEpochSecond(Long.MAX_VALUE, 0, ZoneOffset.UTC));
        t("of null", () -> LocalDateTime.of(null, LocalTime.NOON));
        t("from a date", () -> LocalDateTime.from(LocalDate.EPOCH));
        t("the order's number", () -> LocalDateTime.parse("2020-01-01T10:15").compareTo(LocalDateTime.parse("2025-03-01T10:15"))
                + " " + LocalDateTime.parse("2020-01-01T10:15").compareTo(LocalDateTime.parse("2020-03-05T10:15")) + " "
                + LocalDateTime.parse("2020-01-01T10:15").compareTo(LocalDateTime.parse("2020-01-01T23:59:59")) + " "
                + LocalTime.parse("10:15").compareTo(LocalTime.parse("23:59:59")) + " "
                + LocalTime.parse("10:15:00.5").compareTo(LocalTime.parse("10:15")));
    }

    static void offsets() {
        t("constants", () -> ZoneOffset.UTC + " " + ZoneOffset.MIN + " " + ZoneOffset.MAX + " "
                + ZoneOffset.MIN.getTotalSeconds() + " " + (ZoneOffset.ofTotalSeconds(0) == ZoneOffset.UTC) + " "
                + (ZoneOffset.of("+00:00") == ZoneOffset.UTC));
        String[] ids = {"Z", "z", "UTC", "utc", "GMT", "UT", "UTC+1", "UTC+01", "UTC+01:00", "UTC+0100", "UTC+010000",
            "UTC+0", "UTC-00:00", "GMT+9", "UT-3", "UT+18:00:00", "UTC+18:00:01", "UTC+19", "+1", "+01", "+0100",
            "+01:00:30", "-18", "+18:01", "X", "", "Nowhere/None", "Etc/GMT+5", "Etc/GMT+13", "GMT0", "GMT+0", "GMT-0",
            "1abc", "A", "ab", "a b", "Foo/Bar_Baz", "A~b", "UTCx", "UTC0", "UTC+", "UTC+x", "GMT+1:00", "+1:00",
            "+1:0", "+01:0", "+01:00:0", "+010", "+01000", "01:00", "+19", "+18:00:00", "-18:00", "+00:60",
            "+00:00:60", "+a", "+0a", "+01:a0", "+01-00", "Zz", "UTC+01:00:00", "GMT+00:00", "UT+00", "x01", "001",
            "+0a-00", "+01:00-00", "+01:00:a0", "+010a", "+0100a0", "-0", "-00", "-00:00", "-000000", "+18:00:01",
            "-18:00:01", "+17:59:59", "-1759", "+99", "+00:99", "-01:30", "+010203", "Etc/GMT-14", "Etc/GMT-15",
            "Etc/GMT+12", "Etc/GMT+01", "Etc/GMT", "Etc/GMT0", "Etc/GMT+0", "Etc/GMT-0", "Etc/UTC", "Etc/UCT",
            "Etc/Universal", "Etc/Zulu", "Etc/Greenwich", "Etc/UT", "UCT", "Universal", "Zulu", "Greenwich",
            "Etc/GMT+", "Etc/GMT+1x", "Etc/GMT1", "GMT1"};
        for (String id : ids) {
            t("ZoneOffset.of[" + id + "]", () -> {
                ZoneOffset offset = ZoneOffset.of(id);
                return offset + " " + offset.getId() + " " + offset.getTotalSeconds() + " " + offset.hashCode();
            });
            t("ZoneId.of[" + id + "]", () -> {
                ZoneId zone = ZoneId.of(id);
                return zone + " " + zone.getId() + " " + (zone instanceof ZoneOffset) + " " + zone.normalized() + " "
                        + zone.getRules() + " " + zone.hashCode() + " " + zone.getRules().isFixedOffset() + " "
                        + zone.getRules().getOffset(Instant.EPOCH) + " " + zone.equals(zone.normalized());
            });
        }
        int[][] parts = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {18, 0, 0}, {-18, 0, 0}, {19, 0, 0}, {-19, 0, 0},
            {18, 1, 0}, {18, 0, 1}, {-18, -1, 0}, {-18, 0, -1}, {1, -1, 0}, {1, 0, -1}, {-1, 1, 0}, {-1, 0, 1},
            {0, 1, -1}, {0, -1, 1}, {0, 60, 0}, {0, 0, 60}, {0, -60, 0}, {0, 0, -60}, {5, 30, 0}, {-3, -30, -15},
            {0, -30, 0}, {0, 0, -1}, {19, 60, 60}, {1, 60, -1}, {17, 59, 59}};
        for (int[] hms : parts) {
            t("ofHoursMinutesSeconds " + hms[0] + " " + hms[1] + " " + hms[2],
                    () -> ZoneOffset.ofHoursMinutesSeconds(hms[0], hms[1], hms[2]));
        }
        t("ofHours", () -> ZoneOffset.ofHours(9) + " " + ZoneOffset.ofHoursMinutes(-9, -30) + " "
                + ZoneOffset.ofTotalSeconds(-1) + " " + ZoneOffset.ofTotalSeconds(64800));
        t("ofTotalSeconds above", () -> ZoneOffset.ofTotalSeconds(64801));
        t("ofTotalSeconds below", () -> ZoneOffset.ofTotalSeconds(-64801));
        t("the order", () -> ZoneOffset.ofHours(1).compareTo(ZoneOffset.ofHours(2)) + " "
                + ZoneOffset.ofHours(2).compareTo(ZoneOffset.ofHours(1)) + " " + ZoneOffset.UTC.compareTo(ZoneOffset.UTC)
                + " " + ZoneOffset.ofHours(1).equals(ZoneOffset.of("+01:00")) + " "
                + ZoneId.of("UTC").equals(ZoneId.of("UTC")) + " " + ZoneId.of("UTC").equals(ZoneOffset.UTC) + " "
                + ZoneId.of("UTC+1").equals(ZoneId.of("UTC+01:00")));
        t("ofOffset", () -> ZoneId.ofOffset("UTC", ZoneOffset.ofHours(2)) + " " + ZoneId.ofOffset("", ZoneOffset.ofHours(2))
                + " " + ZoneId.ofOffset("GMT", ZoneOffset.UTC) + " " + ZoneId.ofOffset("UT", ZoneOffset.ofHours(-1)));
        t("ofOffset X", () -> ZoneId.ofOffset("X", ZoneOffset.UTC));
        t("rules", () -> {
            java.time.zone.ZoneRules rules = ZoneId.of("UTC+03:00").getRules();
            LocalDateTime local = LocalDateTime.parse("2020-01-01T10:15");
            return rules + " " + rules.getOffset(local) + " " + rules.getValidOffsets(local) + " "
                    + rules.isValidOffset(local, ZoneOffset.ofHours(3)) + " " + rules.isValidOffset(local, ZoneOffset.UTC)
                    + " " + rules.getStandardOffset(Instant.EPOCH) + " " + rules.getDaylightSavings(Instant.EPOCH) + " "
                    + rules.isDaylightSavings(Instant.EPOCH) + " " + rules.equals(ZoneOffset.ofHours(3).getRules());
        });
        fields("+05:30", ZoneOffset.of("+05:30"));
        t("an offset from", () -> ZoneOffset.from(OffsetDateTime.parse("2020-01-01T10:15+05:00")) + " "
                + ZoneId.from(ZonedDateTime.parse("2020-01-01T10:15+05:00[UTC+05:00]")) + " "
                + ZoneId.from(OffsetDateTime.parse("2020-01-01T10:15+05:00")) + " " + ZoneOffset.from(ZoneOffset.UTC));
        t("an offset from a date", () -> ZoneOffset.from(LocalDate.EPOCH));
        t("a zone from a date", () -> ZoneId.from(LocalDate.EPOCH));
        t("days and months", () -> {
            StringBuilder text = new StringBuilder();
            for (DayOfWeek day : DayOfWeek.values()) {
                text.append(day).append(day.getValue()).append(day.plus(1)).append(day.minus(8)).append(day.plus(-15))
                    .append(' ');
            }
            for (Month month : Month.values()) {
                text.append(month).append(month.getValue()).append(month.plus(1)).append(month.minus(13))
                    .append(month.length(false)).append(month.length(true)).append(month.minLength())
                    .append(month.maxLength()).append(month.firstDayOfYear(false)).append(month.firstDayOfYear(true))
                    .append(month.firstMonthOfQuarter()).append(' ');
            }
            return text + " " + DayOfWeek.of(7) + " " + Month.of(12) + " " + DayOfWeek.from(LocalDate.EPOCH) + " "
                    + Month.from(LocalDate.EPOCH) + " " + DayOfWeek.MONDAY.plus(Long.MAX_VALUE) + " "
                    + Month.MAY.minus(Long.MIN_VALUE) + " " + DayOfWeek.FRIDAY.getLong(ChronoField.DAY_OF_WEEK) + " "
                    + Month.MAY.get(ChronoField.MONTH_OF_YEAR) + " " + Month.MAY.isSupported(ChronoField.YEAR);
        });
        t("day 0", () -> DayOfWeek.of(0));
        t("day 8", () -> DayOfWeek.of(8));
        t("month 0", () -> Month.of(0));
        t("month 13", () -> Month.of(13));
        t("a day's year", () -> DayOfWeek.MONDAY.getLong(ChronoField.YEAR));
        t("a month from a time", () -> Month.from(LocalTime.NOON));
    }

    static void offsetDateTimes() {
        t("constants", () -> OffsetDateTime.MIN + " " + OffsetDateTime.MAX);
        List<OffsetDateTime> all = new ArrayList<>();
        String[] locals = {"2020-02-29T10:15:30.5", "1969-12-31T23:59:59.999999999", "0000-01-01T00:00",
            "2021-12-31T12:00"};
        for (String local : locals) {
            for (String offset : OFFSETS) {
                String text = local + offset;
                OffsetDateTime value = OffsetDateTime.parse(text);
                all.add(value);
                t(text + " parts", () -> value.getOffset() + " " + value.toLocalDateTime() + " " + value.toLocalDate()
                        + " " + value.toLocalTime() + " " + value.getYear() + " " + value.getMonth() + " "
                        + value.getMonthValue() + " " + value.getDayOfMonth() + " " + value.getDayOfYear() + " "
                        + value.getDayOfWeek() + " " + value.getHour() + " " + value.getMinute() + " "
                        + value.getSecond() + " " + value.getNano() + " " + value.hashCode() + " "
                        + value.toEpochSecond() + " " + value.toInstant() + " " + value.toZonedDateTime() + " "
                        + OffsetDateTime.from(value));
                t(text + " moved", () -> value.withOffsetSameLocal(ZoneOffset.ofHours(2)) + " "
                        + value.withOffsetSameInstant(ZoneOffset.ofHours(2)) + " "
                        + value.atZoneSameInstant(ZoneId.of("UTC")) + " " + value.atZoneSimilarLocal(ZoneId.of("UTC"))
                        + " " + value.plusYears(1) + " " + value.plusMonths(1) + " " + value.plusWeeks(1) + " "
                        + value.plusDays(1) + " " + value.plusHours(25) + " " + value.plusMinutes(1) + " "
                        + value.plusSeconds(1) + " " + value.plusNanos(1) + " " + value.minusYears(1) + " "
                        + value.minusMonths(1) + " " + value.minusWeeks(1) + " " + value.minusDays(1) + " "
                        + value.minusHours(25) + " " + value.minusMinutes(1) + " " + value.minusSeconds(1) + " "
                        + value.minusNanos(1) + " " + value.withYear(2024) + " " + value.withMonth(2) + " "
                        + value.withDayOfMonth(28) + " " + value.withDayOfYear(60) + " " + value.withHour(1) + " "
                        + value.withMinute(2) + " " + value.withSecond(3) + " " + value.withNano(4) + " "
                        + value.truncatedTo(ChronoUnit.HOURS) + " " + value.plus(Duration.ofHours(1)) + " "
                        + value.minus(Period.ofDays(1)) + " " + value.with(LocalDate.EPOCH) + " "
                        + value.with(LocalTime.NOON) + " " + value.with(ZoneOffset.ofHours(7)) + " "
                        + value.with(Instant.EPOCH) + " " + value.with(LocalDateTime.parse("2001-02-03T04:05")));
                units(text, value);
            }
        }
        fields("2020-02-29T10:15:30.5+01:00", all.get(1));
        sets("2020-02-29T10:15:30.5+01:00", all.get(1));
        for (OffsetDateTime from : all) {
            for (OffsetDateTime to : all) {
                t(from + " " + to, () -> Integer.signum(from.compareTo(to)) + " " + from.isBefore(to) + " "
                        + from.isAfter(to) + " " + from.isEqual(to) + " " + from.equals(to) + " "
                        + from.until(to, ChronoUnit.HOURS) + " " + from.until(to, ChronoUnit.DAYS) + " "
                        + from.until(to, ChronoUnit.MONTHS) + " " + Duration.between(from, to));
            }
        }
        t("of", () -> OffsetDateTime.of(LocalDate.EPOCH, LocalTime.NOON, ZoneOffset.UTC) + " "
                + OffsetDateTime.of(LocalDateTime.parse("2020-01-01T10:15"), ZoneOffset.ofHours(1)) + " "
                + OffsetDateTime.of(2020, 2, 29, 1, 2, 3, 4, ZoneOffset.ofHours(-1)) + " "
                + OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.ofHours(9)) + " "
                + OffsetDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC-02:00")) + " "
                + OffsetDateTime.from(ZonedDateTime.parse("2020-01-01T10:15+01:00[UTC+01:00]")));
        t("from a date-time", () -> OffsetDateTime.from(LocalDateTime.parse("2020-01-01T10:15")));
        t("the edges", () -> OffsetDateTime.MAX.plusNanos(1));
        t("the edges below", () -> OffsetDateTime.MIN.minusNanos(1));
        t("an instant too far", () -> OffsetDateTime.MAX.toInstant() + " " + OffsetDateTime.MIN.toInstant() + " "
                + OffsetDateTime.MAX.toEpochSecond() + " " + OffsetDateTime.MIN.toEpochSecond());
        t("ofInstant the last", () -> OffsetDateTime.ofInstant(Instant.MAX, ZoneOffset.UTC));
        t("ofInstant the first", () -> OffsetDateTime.ofInstant(Instant.MIN, ZoneOffset.UTC));
    }

    static void zoned() {
        List<ZonedDateTime> all = new ArrayList<>();
        String[] locals = {"2020-02-29T10:15:30.5", "1969-12-31T23:59:59.999999999", "2021-12-31T12:00"};
        for (String local : locals) {
            for (String id : ZONES) {
                ZoneId zone = ZoneId.of(id);
                ZonedDateTime value = ZonedDateTime.of(LocalDateTime.parse(local), zone);
                String text = value.toString();
                all.add(value);
                t(text + " parts", () -> value.getOffset() + " " + value.getZone() + " " + value.toLocalDateTime() + " "
                        + value.toLocalDate() + " " + value.toLocalTime() + " " + value.getYear() + " "
                        + value.getMonth() + " " + value.getMonthValue() + " " + value.getDayOfMonth() + " "
                        + value.getDayOfYear() + " " + value.getDayOfWeek() + " " + value.getHour() + " "
                        + value.getMinute() + " " + value.getSecond() + " " + value.getNano() + " " + value.hashCode()
                        + " " + value.toEpochSecond() + " " + value.toInstant() + " " + value.toOffsetDateTime() + " "
                        + ZonedDateTime.from(value) + " " + ZonedDateTime.parse(text).equals(value));
                t(text + " moved", () -> value.withZoneSameLocal(ZoneId.of("UTC+02:00")) + " "
                        + value.withZoneSameInstant(ZoneId.of("UTC+02:00")) + " " + value.withFixedOffsetZone() + " "
                        + value.withEarlierOffsetAtOverlap() + " " + value.withLaterOffsetAtOverlap() + " "
                        + value.plusYears(1) + " " + value.plusMonths(1) + " " + value.plusWeeks(1) + " "
                        + value.plusDays(1) + " " + value.plusHours(25) + " " + value.plusMinutes(1) + " "
                        + value.plusSeconds(1) + " " + value.plusNanos(1) + " " + value.minusYears(1) + " "
                        + value.minusMonths(1) + " " + value.minusWeeks(1) + " " + value.minusDays(1) + " "
                        + value.minusHours(25) + " " + value.minusMinutes(1) + " " + value.minusSeconds(1) + " "
                        + value.minusNanos(1) + " " + value.withYear(2024) + " " + value.withMonth(2) + " "
                        + value.withDayOfMonth(28) + " " + value.withDayOfYear(60) + " " + value.withHour(1) + " "
                        + value.withMinute(2) + " " + value.withSecond(3) + " " + value.withNano(4) + " "
                        + value.truncatedTo(ChronoUnit.HOURS) + " " + value.plus(Duration.ofHours(1)) + " "
                        + value.minus(Period.ofDays(1)) + " " + value.with(LocalDate.EPOCH) + " "
                        + value.with(LocalTime.NOON) + " " + value.with(ZoneOffset.ofHours(7)) + " "
                        + value.with(Instant.EPOCH) + " " + value.with(LocalDateTime.parse("2001-02-03T04:05")) + " "
                        + value.with(OffsetDateTime.parse("2001-02-03T04:05+07:00")));
                units(text, value);
            }
        }
        fields("2020-02-29T10:15:30.5+02:00[GMT+02:00]", all.get(3));
        sets("2020-02-29T10:15:30.5+02:00[GMT+02:00]", all.get(3));
        for (ZonedDateTime from : all) {
            for (ZonedDateTime to : all) {
                t(from + " " + to, () -> Integer.signum(from.compareTo(to)) + " " + from.isBefore(to) + " "
                        + from.isAfter(to) + " " + from.isEqual(to) + " " + from.equals(to) + " "
                        + from.until(to, ChronoUnit.HOURS) + " " + from.until(to, ChronoUnit.DAYS) + " "
                        + from.until(to, ChronoUnit.MONTHS) + " " + Duration.between(from, to));
            }
        }
        LocalDateTime local = LocalDateTime.parse("2020-01-01T10:15");
        t("of", () -> ZonedDateTime.of(LocalDate.EPOCH, LocalTime.NOON, ZoneOffset.UTC) + " "
                + ZonedDateTime.of(local, ZoneId.of("UTC+01:00")) + " "
                + ZonedDateTime.of(2020, 2, 29, 1, 2, 3, 4, ZoneId.of("Etc/GMT-3")) + " "
                + ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("GMT")) + " "
                + ZonedDateTime.ofInstant(local, ZoneOffset.ofHours(5), ZoneId.of("UTC")) + " "
                + ZonedDateTime.ofLocal(local, ZoneId.of("UTC"), ZoneOffset.ofHours(5)) + " "
                + ZonedDateTime.ofLocal(local, ZoneOffset.ofHours(1), null) + " "
                + ZonedDateTime.ofStrict(local, ZoneOffset.UTC, ZoneId.of("UTC")) + " "
                + ZonedDateTime.from(OffsetDateTime.parse("2020-01-01T10:15+01:00")));
        t("ofStrict another offset", () -> ZonedDateTime.ofStrict(local, ZoneOffset.ofHours(1), ZoneId.of("UTC")));
        t("from a date-time", () -> ZonedDateTime.from(local));
        t("ofInstant the last", () -> ZonedDateTime.ofInstant(Instant.MAX, ZoneOffset.UTC));
    }

    static void instants() {
        t("constants", () -> Instant.EPOCH + " " + Instant.MIN + " " + Instant.MAX + " " + Instant.MIN.getEpochSecond()
                + " " + Instant.MAX.getEpochSecond() + " " + Instant.MAX.getNano());
        long[][] points = {{0, 0}, {0, 1}, {-1, 999999999}, {1, 500000000}, {1577873730L, 123456789},
            {-62167219200L, 0}, {-62167219201L, 0}, {253402300799L, 999000000}, {253402300800L, 0},
            {31556889864403199L, 999999999}, {-31557014167219200L, 0}, {951782400L, 0}, {-86400L, 250000},
            {1L << 32, 0}, {-(1L << 32), 1000}};
        List<Instant> all = new ArrayList<>();
        for (long[] point : points) {
            Instant value = Instant.ofEpochSecond(point[0], point[1]);
            String text = value.toString();
            all.add(value);
            t(text + " parts", () -> value.getEpochSecond() + " " + value.getNano() + " " + value.hashCode() + " "
                    + Instant.parse(text).equals(value) + " " + Instant.from(value));
            t(text + " millis", () -> value.toEpochMilli() + " " + Instant.ofEpochMilli(value.toEpochMilli()));
            t(text + " moved", () -> value.atOffset(ZoneOffset.ofHours(9)) + " " + value.atZone(ZoneId.of("UTC")));
            for (long amount : AMOUNTS) {
                t(text + " plus " + amount, () -> value.plusSeconds(amount) + " " + value.minusSeconds(amount));
                t(text + " millis " + amount, () -> value.plusMillis(amount) + " " + value.minusMillis(amount));
                t(text + " nanos " + amount, () -> value.plusNanos(amount) + " " + value.minusNanos(amount));
            }
            fields(text, value);
            units(text, value);
            for (ChronoUnit unit : ChronoUnit.values()) {
                t(text + " truncated to " + unit, () -> value.truncatedTo(unit));
            }
            t(text + " amounts", () -> value.plus(Duration.ofHours(25)) + " " + value.minus(Duration.ofNanos(1)));
            t(text + " a period", () -> value.plus(Period.ofDays(1)));
            t(text + " a month", () -> value.plus(Period.ofMonths(1)));
        }
        sets("2020-01-01T10:15:30.123456789Z", all.get(4));
        for (Instant from : all) {
            for (Instant to : all) {
                t(from + " " + to, () -> from.compareTo(to) + " " + from.isBefore(to) + " " + from.isAfter(to) + " "
                        + from.equals(to));
                between(from + " " + to, from, to);
                t(from + " " + to + " duration", () -> Duration.between(from, to));
            }
        }
        t("of", () -> Instant.ofEpochSecond(5) + " " + Instant.ofEpochSecond(5, -1) + " "
                + Instant.ofEpochSecond(5, 3000000000L) + " " + Instant.ofEpochMilli(-1) + " "
                + Instant.ofEpochMilli(Long.MAX_VALUE) + " " + Instant.ofEpochMilli(Long.MIN_VALUE) + " "
                + Instant.from(OffsetDateTime.parse("2020-01-01T10:15+01:00")) + " "
                + Instant.from(ZonedDateTime.parse("2020-01-01T10:15+01:00")));
        t("ofEpochSecond above", () -> Instant.ofEpochSecond(31556889864403200L));
        t("ofEpochSecond below", () -> Instant.ofEpochSecond(-31557014167219201L));
        t("ofEpochSecond over", () -> Instant.ofEpochSecond(Long.MAX_VALUE, 1000000000L));
        t("from a date-time", () -> Instant.from(LocalDateTime.parse("2020-01-01T10:15")));
        t("the last in millis", () -> Instant.MAX.toEpochMilli());
        t("the first in millis", () -> Instant.MIN.toEpochMilli());
        t("now", () -> {
            Instant before = Instant.now();
            Instant now = Instant.now(Clock.systemUTC());
            return !now.isBefore(before) && now.getEpochSecond() > 1700000000L;
        });
    }

    static void years() {
        int[] values = {2020, 2021, 1900, 2000, 0, -1, -4, 1, 999999999, -999999999, 1970, 100, -400};
        List<Year> all = new ArrayList<>();
        for (int number : values) {
            Year year = Year.of(number);
            all.add(year);
            String text = year.toString();
            t(text + " parts", () -> year.getValue() + " " + year.isLeap() + " " + year.length() + " " + year.hashCode()
                    + " " + Year.isLeap(number) + " " + year.atDay(1) + " " + year.atMonth(2) + " "
                    + year.atMonth(Month.DECEMBER) + " " + Year.from(year) + " " + Year.parse(text.startsWith("-")
                            || number > 9999 || number < 1000 ? "2020" : text));
            for (long amount : AMOUNTS) {
                t(text + " plus " + amount, () -> year.plusYears(amount) + " " + year.minusYears(amount));
            }
            fields(text, year);
            units(text, year);
            sets(text, year);
            t(text + " atDay 366", () -> year.atDay(366));
            t(text + " amounts", () -> year.plus(Period.ofYears(3)) + " " + year.minus(Period.ofYears(3)));
            t(text + " months", () -> year.plus(Period.ofMonths(12)));
        }
        for (Year from : all) {
            for (Year to : all) {
                t(from + " " + to, () -> from.compareTo(to) + " " + from.isBefore(to) + " " + from.isAfter(to) + " "
                        + from.equals(to));
                between(from + " " + to, from, to);
            }
        }
        t("of above", () -> Year.of(1000000000));
        t("of below", () -> Year.of(-1000000000));
        t("from a date", () -> Year.from(LocalDate.EPOCH) + " " + Year.from(YearMonth.of(2020, 5)) + " "
                + Year.from(LocalDateTime.parse("2020-01-01T10:15")));
        t("from a time", () -> Year.from(LocalTime.NOON));
        t("atMonth 13", () -> Year.of(2020).atMonth(13));
        String[] months = {"2020-02", "2021-02", "1900-02", "2000-12", "0000-01", "-0001-12", "+999999999-12",
            "-999999999-01", "1970-01", "2023-11", "9999-12", "+10000-01"};
        List<YearMonth> allMonths = new ArrayList<>();
        for (String text : months) {
            YearMonth month = YearMonth.parse(text);
            allMonths.add(month);
            t(text + " parts", () -> month + " " + month.getYear() + " " + month.getMonth() + " "
                    + month.getMonthValue() + " " + month.isLeapYear() + " " + month.lengthOfMonth() + " "
                    + month.lengthOfYear() + " " + month.isValidDay(29) + " " + month.isValidDay(0) + " "
                    + month.hashCode() + " " + month.atDay(1) + " " + month.atEndOfMonth() + " "
                    + YearMonth.from(month) + " " + month.withYear(2024) + " " + month.withMonth(2));
            for (long amount : AMOUNTS) {
                t(text + " plus " + amount, () -> month.plusMonths(amount) + " " + month.minusMonths(amount) + " "
                        + month.plusYears(amount) + " " + month.minusYears(amount));
            }
            fields(text, month);
            units(text, month);
            sets(text, month);
            t(text + " atDay 30", () -> month.atDay(30));
            t(text + " amounts", () -> month.plus(Period.of(1, 2, 0)) + " " + month.minus(Period.ofMonths(14)));
            t(text + " days", () -> month.plus(Period.ofDays(1)));
        }
        for (YearMonth from : allMonths) {
            for (YearMonth to : allMonths) {
                t(from + " " + to, () -> from.compareTo(to) + " " + from.isBefore(to) + " " + from.isAfter(to) + " "
                        + from.equals(to));
                between(from + " " + to, from, to);
            }
        }
        t("of", () -> YearMonth.of(2020, 2) + " " + YearMonth.of(2020, Month.MAY) + " " + YearMonth.from(LocalDate.EPOCH));
        t("of 13", () -> YearMonth.of(2020, 13));
        t("of a year above", () -> YearMonth.of(1000000000, 13));
        t("withMonth 0", () -> YearMonth.of(2020, 2).withMonth(0));
        t("withYear above", () -> YearMonth.of(2020, 2).withYear(1000000000));
        t("from a time", () -> YearMonth.from(LocalTime.NOON));
    }

    static void periods() {
        int[][] values = {{0, 0, 0}, {1, 2, 3}, {-1, -2, -3}, {1, -2, 3}, {0, 14, 0}, {0, -14, 0}, {1, 13, 40},
            {-1, 13, 0}, {1, -13, 0}, {0, 0, 366}, {2147483647, 2147483647, 2147483647},
            {-2147483648, -2147483648, -2147483648}, {178956970, 7, 0}, {178956970, 8, 0}, {0, 2147483647, 0},
            {5, 0, 0}, {0, 0, -1}};
        List<Period> all = new ArrayList<>();
        for (int[] ymd : values) {
            Period period = Period.of(ymd[0], ymd[1], ymd[2]);
            all.add(period);
            String text = period.toString();
            t(text + " parts", () -> period.getYears() + " " + period.getMonths() + " " + period.getDays() + " "
                    + period.isZero() + " " + period.isNegative() + " " + period.toTotalMonths() + " "
                    + period.hashCode() + " " + period.getUnits() + " " + period.get(ChronoUnit.YEARS) + " "
                    + period.get(ChronoUnit.MONTHS) + " " + period.get(ChronoUnit.DAYS) + " " + Period.parse(text)
                    + " " + Period.from(period) + " " + period.withYears(9) + " " + period.withMonths(9) + " "
                    + period.withDays(9));
            t(text + " normalized", () -> period.normalized());
            t(text + " negated", () -> period.negated());
            for (int by : new int[] {0, 1, -1, 2, 3, 1000000}) {
                t(text + " times " + by, () -> period.multipliedBy(by));
            }
            for (long amount : new long[] {0, 1, -1, 2147483647L, -2147483648L, Long.MAX_VALUE, Long.MIN_VALUE}) {
                t(text + " plus " + amount, () -> period.plusYears(amount) + " " + period.plusMonths(amount) + " "
                        + period.plusDays(amount));
                t(text + " minus " + amount, () -> period.minusYears(amount) + " " + period.minusMonths(amount) + " "
                        + period.minusDays(amount));
            }
            t(text + " a week", () -> period.get(ChronoUnit.WEEKS));
            t(text + " to a date", () -> LocalDate.of(2020, 2, 29).plus(period) + " " + LocalDate.of(2020, 2, 29).minus(period)
                    + " " + period.addTo(LocalDate.of(2021, 1, 31)) + " " + period.subtractFrom(LocalDate.of(2021, 3, 31)));
            t(text + " to a date-time", () -> period.addTo(LocalDateTime.parse("2020-01-31T10:15")) + " "
                    + period.subtractFrom(ZonedDateTime.parse("2020-03-31T10:15Z")));
        }
        for (Period one : all) {
            for (Period other : all) {
                t(one + " " + other, () -> one.equals(other) + " " + one.plus(other));
                t(one + " less " + other, () -> one.minus(other));
            }
        }
        t("of", () -> Period.ofYears(1) + " " + Period.ofMonths(2) + " " + Period.ofWeeks(3) + " " + Period.ofDays(4)
                + " " + Period.ZERO + " " + (Period.of(0, 0, 0) == Period.ZERO) + " " + Period.ofWeeks(-1));
        t("ofWeeks over", () -> Period.ofWeeks(306783379));
        t("from a duration", () -> Period.from(Duration.ofDays(1)));
        t("a period plus a duration", () -> Period.ofDays(1).plus(Duration.ofDays(1)));
        t("years to a year", () -> Period.ofYears(2).addTo(Year.of(2020)) + " " + Period.ofYears(2).subtractFrom(YearMonth.of(2020, 5)));
        t("months to a year", () -> Period.of(1, 12, 0).addTo(Year.of(2020)));
    }

    static void durations() {
        Duration[] values = {Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(-1, 500000000),
            Duration.ofDays(1), Duration.ofHours(25).plusNanos(1), Duration.ofSeconds(86399, 999999999),
            Duration.ofMillis(-1), Duration.ofSeconds(Long.MAX_VALUE, 999999999), Duration.ofSeconds(Long.MIN_VALUE),
            Duration.ofNanos(1500), Duration.ofMinutes(-90)};
        for (Duration value : values) {
            String text = value.toString();
            t(text + " units", () -> value.getUnits() + " " + value.get(ChronoUnit.SECONDS) + " "
                    + value.get(ChronoUnit.NANOS) + " " + Duration.from(value));
            for (ChronoUnit unit : ChronoUnit.values()) {
                t(text + " truncated to " + unit, () -> value.truncatedTo(unit));
                t(text + " plus " + unit, () -> value.plus(3, unit) + " " + value.minus(3, unit));
                t(text + " get " + unit, () -> value.get(unit));
            }
            t(text + " to an instant", () -> value.addTo(Instant.EPOCH) + " " + value.subtractFrom(Instant.EPOCH));
            t(text + " to a date-time", () -> value.addTo(LocalDateTime.parse("2020-02-29T10:15")) + " "
                    + value.subtractFrom(LocalTime.NOON));
            t(text + " to a date", () -> value.addTo(LocalDate.EPOCH));
        }
        for (ChronoUnit unit : ChronoUnit.values()) {
            for (long amount : new long[] {0, 1, -1, 1000, 1500000, -2500000000L, Long.MAX_VALUE, Long.MIN_VALUE}) {
                t("of " + amount + " " + unit, () -> Duration.of(amount, unit));
            }
            t(unit + " itself", () -> unit + " " + unit.name() + " " + unit.getDuration() + " " + unit.isDateBased()
                    + " " + unit.isTimeBased() + " " + unit.isDurationEstimated() + " "
                    + unit.isSupportedBy(LocalDate.EPOCH) + " " + unit.isSupportedBy(LocalTime.NOON) + " "
                    + unit.isSupportedBy(Instant.EPOCH));
        }
        for (ChronoField field : ChronoField.values()) {
            t(field + " itself", () -> field + " " + field.name() + " " + field.getBaseUnit() + " "
                    + field.getRangeUnit() + " " + field.range() + " " + field.isDateBased() + " "
                    + field.isTimeBased() + " " + field.isSupportedBy(LocalDate.EPOCH) + " "
                    + field.isSupportedBy(Instant.EPOCH) + " " + field.range().isFixed() + " "
                    + field.range().isIntValue() + " " + field.range().getLargestMinimum() + " "
                    + field.range().getSmallestMaximum());
            t(field + " checked", () -> field.checkValidValue(1) + " " + field.checkValidIntValue(1));
            t(field + " checked above", () -> field.checkValidValue(field.range().getMaximum() + 1));
            t(field + " checked int", () -> field.checkValidIntValue(field.range().getMaximum()));
            t(field + " through it", () -> field.getFrom(LocalDateTime.parse("2020-02-29T10:15:30.5")) + " "
                    + field.rangeRefinedBy(LocalDateTime.parse("2020-02-29T10:15:30.5")) + " "
                    + field.adjustInto(LocalDateTime.parse("2020-02-29T10:15:30.5"), 1));
        }
        t("between", () -> Duration.between(Instant.EPOCH, Instant.ofEpochSecond(1, 5)) + " "
                + Duration.between(LocalTime.NOON, LocalTime.MIDNIGHT) + " "
                + Duration.between(LocalDateTime.MIN, LocalDateTime.MAX) + " "
                + Duration.between(Instant.MIN, Instant.MAX) + " " + Duration.between(Instant.MAX, Instant.MIN));
        t("between dates", () -> Duration.between(LocalDate.EPOCH, LocalDate.EPOCH));
        t("between a date-time and a time", () -> Duration.between(LocalDateTime.parse("2020-01-01T10:15"), LocalTime.NOON));
        t("between a time and a date-time", () -> Duration.between(LocalTime.NOON, LocalDateTime.parse("2020-01-01T10:15")));
        t("from a period", () -> Duration.from(Period.ofDays(2)));
        t("from a period of months", () -> Duration.from(Period.ofMonths(2)));
        t("ranges", () -> ValueRange.of(1, 5) + " " + ValueRange.of(1, 28, 31) + " " + ValueRange.of(0, 1, 5, 9) + " "
                + ValueRange.of(1, 5).isValidValue(5) + " " + ValueRange.of(1, 5).isValidIntValue(6) + " "
                + ValueRange.of(1, 5).equals(ValueRange.of(1, 5)) + " " + ValueRange.of(1, 5).checkValidValue(3, null));
        t("a range backwards", () -> ValueRange.of(5, 1));
        t("a range checked", () -> ValueRange.of(1, 5).checkValidValue(9, ChronoField.DAY_OF_WEEK));
        t("a range checked for nothing", () -> ValueRange.of(1, 5).checkValidIntValue(9, null));
    }

    static void clocks() {
        Instant at = Instant.parse("2020-02-29T22:15:30.123456789Z");
        Clock fixed = Clock.fixed(at, ZoneId.of("UTC+03:00"));
        t("a fixed clock", () -> fixed + " " + fixed.instant() + " " + fixed.millis() + " " + fixed.getZone() + " "
                + fixed.withZone(ZoneOffset.UTC) + " " + fixed.equals(Clock.fixed(at, ZoneId.of("UTC+03:00"))) + " "
                + LocalDate.now(fixed) + " " + LocalTime.now(fixed) + " " + LocalDateTime.now(fixed) + " "
                + OffsetDateTime.now(fixed) + " " + ZonedDateTime.now(fixed) + " " + Instant.now(fixed) + " "
                + Year.now(fixed) + " " + YearMonth.now(fixed));
        t("clocks over it", () -> Clock.offset(fixed, Duration.ofHours(2)) + " "
                + Clock.offset(fixed, Duration.ofHours(2)).instant() + " " + (Clock.offset(fixed, Duration.ZERO) == fixed)
                + " " + Clock.tick(fixed, Duration.ofSeconds(1)) + " " + Clock.tick(fixed, Duration.ofSeconds(1)).instant()
                + " " + Clock.tick(fixed, Duration.ofMillis(250)).instant() + " "
                + Clock.tick(fixed, Duration.ofNanos(1000)).instant() + " " + Clock.tick(fixed, Duration.ofMinutes(7)).instant()
                + " " + (Clock.tick(fixed, Duration.ofNanos(1)) == fixed) + " "
                + Clock.tick(fixed, Duration.ofHours(1)).withZone(ZoneOffset.UTC) + " "
                + Clock.offset(fixed, Duration.ofNanos(-1)).millis());
        t("a tick backwards", () -> Clock.tick(fixed, Duration.ofSeconds(-1)));
        t("a tick of three nanoseconds", () -> Clock.tick(fixed, Duration.ofNanos(3)));
        t("the system's clocks", () -> Clock.systemUTC() + " " + Clock.system(ZoneId.of("UTC+01:00")) + " "
                + Clock.tickSeconds(ZoneOffset.UTC) + " " + Clock.tickMinutes(ZoneOffset.UTC) + " "
                + Clock.tickMillis(ZoneOffset.UTC) + " " + Clock.systemUTC().getZone() + " "
                + Clock.systemUTC().equals(Clock.systemUTC()) + " " + (Clock.systemUTC().millis() > 1700000000000L)
                + " " + Clock.systemUTC().withZone(ZoneOffset.ofHours(1)));
        t("a clock of nothing", () -> Clock.fixed(null, ZoneOffset.UTC));
        t("now in a zone", () -> {
            Clock clock = Clock.system(ZoneOffset.ofHours(5));
            LocalDateTime local = LocalDateTime.now(clock);
            ZonedDateTime zoned = ZonedDateTime.now(ZoneOffset.ofHours(5));
            return zoned.getOffset() + " " + (Math.abs(Duration.between(local, zoned.toLocalDateTime()).toSeconds()) < 60)
                    + " " + (LocalDate.now(ZoneOffset.UTC).getYear() > 2023) + " "
                    + (Year.now(ZoneOffset.UTC).getValue() > 2023) + " " + (YearMonth.now(ZoneOffset.UTC).getYear() > 2023)
                    + " " + (OffsetDateTime.now(ZoneOffset.UTC).getOffset() == ZoneOffset.UTC) + " "
                    + (LocalTime.now(ZoneOffset.UTC).getHour() < 24);
        });
        t("now where the host is", () -> {
            ZoneId here = ZoneId.systemDefault();
            ZonedDateTime now = ZonedDateTime.now();
            return (Math.abs(now.toEpochSecond() - Instant.now().getEpochSecond()) < 60) + " "
                    + (LocalDate.now().getYear() > 2023) + " " + (LocalDateTime.now().getYear() > 2023) + " "
                    + (LocalTime.now().getHour() < 24) + " " + (OffsetDateTime.now().getYear() > 2023) + " "
                    + (Year.now().getValue() > 2023) + " " + (YearMonth.now().getYear() > 2023) + " "
                    + Clock.systemDefaultZone().getZone().equals(here);
        });
    }

    static void seeded() {
        for (int i = 0; i < 400; i++) {
            long day = next(-365243219162L, 365241780471L) / (i % 4 == 0 ? 1 : i % 4 == 1 ? 1000 : 100000);
            LocalDate date = LocalDate.ofEpochDay(day);
            long months = next(-30000, 30000);
            long days = next(-800000, 800000);
            LocalDate other = LocalDate.ofEpochDay(day / 3 + days);
            t("day " + day, () -> date + " " + date.getDayOfWeek() + " " + date.getDayOfYear() + " " + date.toEpochDay()
                    + " " + date.hashCode() + " " + LocalDate.parse(date.toString()).equals(date) + " "
                    + date.plusMonths(months) + " " + date.plusDays(days) + " " + date.until(other) + " "
                    + other.until(date) + " " + date.until(other, ChronoUnit.MONTHS) + " "
                    + date.until(other, ChronoUnit.YEARS) + " " + date.get(ChronoField.ALIGNED_WEEK_OF_YEAR) + " "
                    + date.plus(other.until(date)) + " " + YearMonth.from(date).plusMonths(months));
        }
        for (int i = 0; i < 400; i++) {
            long second = next(-31557014167219200L, 31556889864403199L) / (i % 4 == 0 ? 1 : i % 4 == 1 ? 1000 : 3000000);
            int nano = i % 3 == 0 ? 0 : (int) next(0, 999999999) / (i % 3 == 1 ? 1 : 1000000) * (i % 3 == 1 ? 1 : 1000000);
            Instant instant = Instant.ofEpochSecond(second, nano);
            ZoneOffset offset = ZoneOffset.ofTotalSeconds((int) next(-64800, 64800) / (i % 2 == 0 ? 1 : 900) * (i % 2 == 0 ? 1 : 900));
            long amount = next(-4000000000L, 4000000000L);
            t("second " + second + " " + nano, () -> instant + " " + instant.hashCode() + " "
                    + Instant.parse(instant.toString()).equals(instant) + " " + instant.plusSeconds(amount) + " "
                    + instant.plusNanos(amount) + " " + instant.until(Instant.EPOCH, ChronoUnit.DAYS));
            t("second " + second + " at " + offset, () -> {
                OffsetDateTime dateTime = instant.atOffset(offset);
                LocalDateTime local = dateTime.toLocalDateTime();
                return dateTime + " " + dateTime.hashCode() + " " + local.hashCode() + " " + offset.hashCode() + " "
                        + OffsetDateTime.parse(dateTime.toString()).equals(dateTime) + " "
                        + local.plusSeconds(amount) + " " + local.plusNanos(amount * 1000) + " "
                        + local.until(LocalDateTime.of(2000, 1, 1, 0, 0), ChronoUnit.HOURS) + " "
                        + local.toLocalTime().toNanoOfDay() + " " + dateTime.toInstant().equals(instant) + " "
                        + ZonedDateTime.ofInstant(instant, ZoneId.ofOffset("UTC", offset)).hashCode();
            });
        }
    }

    public static void main(String[] args) {
        texts();
        dates();
        times();
        dateTimes();
        offsets();
        offsetDateTimes();
        zoned();
        instants();
        years();
        periods();
        durations();
        clocks();
        seeded();
    }
}
