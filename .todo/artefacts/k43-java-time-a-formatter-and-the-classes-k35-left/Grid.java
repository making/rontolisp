import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.*;

// The probe k43 learned java.time.format from: every line is what a JDK 25 prints, and
// the interpreter, the JVM, wasm and the component print the same lines. Written here;
// nothing of a JDK is in it. P1 the patterns, P2 writing by each letter, P3 numbers, P4
// words, P5 and P6 offsets, P7 and P14 to P15 the resolver styles, P8 and P12 the
// locales, P9 the ISO forms, P10 the queries and from(), P11 the builder, P13 the
// settings and optional parts, P16 the texts of k35's grid through the ISO forms. A
// field map a JDK prints in an order that changes from run to run is printed sorted;
// what a JDK answers by an object's identity, a lambda's name or an index error of its
// own is not asked (README.md).
public class Grid {

    static class Show {

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
            return sorted(t.toString()) + " d=" + t.query(TemporalQueries.localDate()) + " t=" + t.query(TemporalQueries.localTime())
                + " z=" + t.query(TemporalQueries.zoneId()) + " o=" + t.query(TemporalQueries.offset())
                + " x=" + t.query(DateTimeFormatter.parsedExcessDays()) + " l=" + t.query(DateTimeFormatter.parsedLeapSecond());
        } catch (DateTimeParseException e) {
            Throwable c = e.getCause();
            return "! @" + e.getErrorIndex() + " " + sorted(e.getMessage()) + (c == null ? "" : " <- " + c.getClass().getSimpleName() + ": " + sorted(String.valueOf(c.getMessage())));
        } catch (Exception e) {
            return "! " + e.getClass().getName() + ": " + e.getMessage();
        }
    }
    static void p(String pattern, String... texts) {
        DateTimeFormatter f;
        try {
            f = DateTimeFormatter.ofPattern(pattern);
        } catch (Exception e) {
            System.out.println(pattern + " ! " + e);
            return;
        }
        for (String t : texts) {
            System.out.println(pattern + " [" + t + "] " + parsed(f, t));
        }
    }
    static void b(String label, DateTimeFormatter f, String... texts) {
        for (String t : texts) {
            System.out.println(label + " [" + t + "] " + parsed(f, t));
        }
    }

    }

    static class P1 extends Show {

    static void run() {
        String letters = "GuyDMLdEFahKkHmsSAnNVOXxZp";
        for (char c : letters.toCharArray()) {
            for (int n = 1; n <= 6; n++) {
                String p = String.valueOf(c).repeat(n);
                try {
                    DateTimeFormatter f = DateTimeFormatter.ofPattern(p);
                    System.out.println(p + " -> " + f);
                } catch (Exception e) {
                    System.out.println(p + " -> ! " + e.getClass().getName() + ": " + e.getMessage());
                }
            }
        }
        for (String p : new String[] {"'", "''", "'a''b'", "'abc", "[", "]", "[[a]]", "a]", "#", "{", "}", "p", "pp", "ppH", "pH", "p'x'", "p[H]", "ppp", "yyyy-MM-dd", "uuuu-MM-dd", "HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm", "@", "b", "C", "f", "i", "I", "j", "J", "l", "o", "P", "r", "R", "t", "T", "U", "Z5", "é", " ", "-", "..", "a-b", "MM dd, yyyy", "p5H", "pp[H]"}) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p);
                System.out.println("[" + p + "] -> " + f);
            } catch (Exception e) {
                System.out.println("[" + p + "] -> ! " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
        try { DateTimeFormatter.ofPattern(null); } catch (Exception e) { System.out.println("null -> " + e); }
        System.out.println(DateTimeFormatter.ISO_LOCAL_DATE);
        System.out.println(DateTimeFormatter.ISO_OFFSET_DATE);
        System.out.println(DateTimeFormatter.ISO_DATE);
        System.out.println(DateTimeFormatter.ISO_LOCAL_TIME);
        System.out.println(DateTimeFormatter.ISO_OFFSET_TIME);
        System.out.println(DateTimeFormatter.ISO_TIME);
        System.out.println(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        System.out.println(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        System.out.println(DateTimeFormatter.ISO_ZONED_DATE_TIME);
        System.out.println(DateTimeFormatter.ISO_DATE_TIME);
        System.out.println(DateTimeFormatter.ISO_ORDINAL_DATE);
        System.out.println(DateTimeFormatter.ISO_INSTANT);
        System.out.println(DateTimeFormatter.BASIC_ISO_DATE);
        System.out.println(DateTimeFormatter.RFC_1123_DATE_TIME);
        System.out.println(DateTimeFormatter.ISO_LOCAL_DATE.getResolverStyle() + " " + DateTimeFormatter.ofPattern("y").getResolverStyle());
        System.out.println(DateTimeFormatter.ISO_LOCAL_DATE.getZone() + " " + DateTimeFormatter.ISO_INSTANT.getZone());
        System.out.println(DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.LENIENT));
        System.out.println(DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC));
    }

    }

    static class P2 extends Show {

    static void f(String p, TemporalAccessor t) {
        String r;
        try {
            r = DateTimeFormatter.ofPattern(p).format(t);
        } catch (Exception e) {
            r = "! " + e.getClass().getName() + ": " + e.getMessage();
        }
        System.out.println(p + " | " + t + " -> " + r);
    }
    static void run() {
        List<TemporalAccessor> ts = List.of(
            LocalDateTime.of(2020, 1, 5, 8, 9, 10, 123456789),
            LocalDateTime.of(-44, 3, 15, 13, 0, 0, 0),
            LocalDateTime.of(0, 12, 31, 0, 0, 0, 120000000),
            LocalDateTime.of(12345, 7, 4, 23, 59, 59, 999999999),
            LocalDateTime.of(-12345, 7, 4, 12, 30, 0, 1000),
            LocalDateTime.of(999, 11, 30, 11, 5, 7, 5),
            LocalDateTime.of(2021, 10, 10, 0, 0));
        String[] ps = {"G","GGGG","GGGGG","u","uu","uuu","uuuu","uuuuu","y","yy","yyy","yyyy","yyyyy","D","DD","DDD","M","MM","MMM","MMMM","MMMMM","L","LL","LLL","LLLL","LLLLL","d","dd","E","EEEE","EEEEE","eee","eeee","eeeee","ccc","cccc","ccccc","F","a","h","hh","K","KK","k","kk","H","HH","m","mm","s","ss","S","SS","SSS","SSSS","SSSSSS","SSSSSSSSS","A","AAAAAAAAA","n","nnnnnnnnnn","N","NNNNNNNNNNNNNNN","ppH","pppH","pppHH","ppppMMM"};
        for (String p : ps) {
            for (TemporalAccessor t : ts) {
                f(p, t);
            }
        }
        List<TemporalAccessor> zs = List.of(
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.UTC),
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.ofHoursMinutesSeconds(1, 30, 15)),
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.ofHoursMinutes(-8, 0)),
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.ofHoursMinutes(-8, -30)),
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneId.of("UTC")),
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneId.of("GMT+01:00")),
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneId.of("Etc/GMT+5")),
            ZonedDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneId.of("Asia/Tokyo")),
            OffsetDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.ofHours(18)),
            OffsetDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.ofTotalSeconds(-1)),
            OffsetDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.ofTotalSeconds(60)),
            OffsetDateTime.of(2020, 1, 5, 8, 9, 10, 0, ZoneOffset.ofTotalSeconds(3600*10 + 5)));
        String[] zp = {"VV","O","OOOO","X","XX","XXX","XXXX","XXXXX","x","xx","xxx","xxxx","xxxxx","Z","ZZ","ZZZ","ZZZZ","ZZZZZ"};
        for (String p : zp) {
            for (TemporalAccessor t : zs) {
                f(p, t);
            }
        }
        // unsupported fields
        for (String p : new String[] {"yyyy-MM-dd", "HH:mm", "VV", "X", "G", "a", "E", "D", "SSS", "n", "A", "N"}) {
            for (TemporalAccessor t : List.of(LocalDate.of(2020, 1, 5), LocalTime.of(10, 15), Instant.ofEpochSecond(0), YearMonth.of(2020, 5), Year.of(2020), ZoneOffset.ofHours(1), DayOfWeek.MONDAY, Month.MAY)) {
                f(p, t);
            }
        }
    }

    }

    static class P3 extends Show {

    static void run() {
        String[] nums = {"", "0", "5", "05", "005", "12", "123", "1234", "12345", "+5", "-5", "+05", "-05", "+12345", "-0", "+0", "a", "5a", " 5", "99999999999999999999", "9223372036854775807", "9223372036854775808", "-9223372036854775808", "1234567890123456789", "12345678901234567890"};
        for (String pat : new String[] {"H", "HH", "m", "D", "DD", "DDD", "u", "uuu", "uuuu", "uuuuu", "y", "yyy", "yyyy", "A", "AAA", "n", "N"}) {
            p(pat, nums);
        }
        // adjacent values
        p("yyyyMMdd", "20200101", "2020011", "120200101", "202001011", "+20200101", "-20200101", "0200101", "2020-01-01");
        p("uuuuMMdd", "20200101", "120200101", "+120200101", "-120200101", "+20200101", "1234567890123456789012");
        p("uMMdd", "20200101", "200101", "0101", "-20200101", "+120200101");
        p("MMdd", "0101", "101", "01011");
        p("MdHH", "11210", "1110", "12210");
        p("HHmmss", "101530", "10153", "1015300");
        p("HHmmssSSS", "101530123", "10153012", "1015301234");
        p("yyyyMMddHHmm", "202001011015", "2020010110151");
        p("Hmm", "1015", "915", "15", "10155");
        p("ddMMMuuuu", "01Jan2020", "1Jan2020");
        p("dMMMuuuu", "01Jan2020", "1Jan2020", "011Jan2020");
        // reduced
        for (String pat : new String[] {"yy", "uu", "yy-MM-dd", "uuMM"}) {
            p(pat, "20", "99", "00", "5", "123", "-5", "+5", "20-01-01", "2001", "1999-01-01", "200101");
        }
        // fraction
        for (String pat : new String[] {"S", "SSS", "SSSSSSSSS", "ss.SSS", "HH:mm:ss.SSS", "ssSSS"}) {
            p(pat, "1", "12", "123", "1234", "123456789", "1234567890", "00.123", "10:15:30.123", "10:15:30.12", "10:15:30.1234", "10:15:30", "30123", "3012", "a");
        }
    }

    }

    static class P4 extends Show {

    static void run() {
        String[] months = {"Jan", "jan", "JAN", "January", "january", "Janu", "J", "Ja", "May", "Sep", "Sept", "September", "1", "01", "12", "13", "", "Ja1", "Mayx", "M", "Ju", "Jun", "June", "Junee"};
        for (String pat : new String[] {"MMM", "MMMM", "MMMMM", "LLL", "LLLL", "LLLLL"}) {
            p(pat, months);
        }
        String[] days = {"Mon", "mon", "Monday", "monday", "M", "T", "S", "1", "Tue", "Tues", "Tuesday", "Thu", "Thurs"};
        for (String pat : new String[] {"E", "EEEE", "EEEEE", "ccc", "cccc", "eee"}) {
            p(pat, days);
        }
        String[] eras = {"AD", "ad", "BC", "Anno Domini", "Before Christ", "A", "B", "BCE", "CE", "1", "0"};
        for (String pat : new String[] {"G", "GGGG", "GGGGG"}) {
            p(pat, eras);
        }
        String[] ampm = {"AM", "am", "PM", "pm", "a.m.", "A", "P", "0", "1"};
        p("a", ampm);
        p("h a", "10 AM", "10 PM", "12 AM", "12 PM", "0 AM", "13 PM", "10 am");
        p("hh:mm a", "10:15 AM", "10:15 PM", "12:00 AM", "12:00 PM");
        p("K a", "0 AM", "0 PM", "11 PM", "12 PM");
        p("h", "10", "12", "0", "13");
        p("K", "0", "11", "12");
        p("k", "24", "1", "0", "25");
        p("a h", "AM 10");
        // case insensitive & lenient through the builder
        DateTimeFormatter ci = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd MMM uuuu").toFormatter();
        b("ci", ci, "01 jan 2020", "01 JAN 2020", "01 Jan 2020", "01 january 2020");
        DateTimeFormatter len = new DateTimeFormatterBuilder().parseLenient().appendPattern("dd MMM uuuu").toFormatter();
        b("lenient", len, "01 jan 2020", "1 Jan 2020", "01 January 2020", "1 1 2020", "001 Jan 2020", "01 Jan 20", "01 Jan +2020", "01 Jan 12020", "-1 Jan 2020", "+1 Jan 2020");
        DateTimeFormatter len2 = new DateTimeFormatterBuilder().parseLenient().appendPattern("uuuu-MM-dd HH:mm:ss.SSS").toFormatter();
        b("lenient2", len2, "2020-1-1 1:2:3.4", "2020-01-01 10:15:30.123", "+2020-01-01 10:15:30.123", "20-01-01 10:15:30.123456", "2020-001-01 10:15:30.1", "2020-01-01 10:15:30.", "2020-01-01 10:15:30");
        DateTimeFormatter len3 = new DateTimeFormatterBuilder().parseLenient().appendPattern("uuuuMMdd").toFormatter();
        b("lenient3", len3, "20200101", "2020011", "202001011", "+20200101");
        DateTimeFormatter len4 = new DateTimeFormatterBuilder().parseLenient().appendPattern("yy").toFormatter();
        b("lenient4", len4, "20", "5", "123", "2020", "-5", "+5", "-2020", "+12345");
        DateTimeFormatter len5 = new DateTimeFormatterBuilder().parseLenient().appendPattern("SSS").toFormatter();
        b("lenient5", len5, "1", "12", "123", "1234", "123456789", "1234567890");
        // text in lenient mode
        DateTimeFormatter len6 = new DateTimeFormatterBuilder().parseLenient().appendPattern("MMM").toFormatter();
        b("lenient6", len6, months);
        DateTimeFormatter len7 = new DateTimeFormatterBuilder().parseLenient().appendPattern("E").toFormatter();
        b("lenient7", len7, days);
        DateTimeFormatter len8 = new DateTimeFormatterBuilder().parseLenient().appendPattern("a").toFormatter();
        b("lenient8", len8, ampm);
        DateTimeFormatter len9 = new DateTimeFormatterBuilder().parseLenient().appendPattern("G").toFormatter();
        b("lenient9", len9, "AD", "ad", "BC", "Anno Domini", "Before Christ", "A", "B", "BCE", "1", "0");
        DateTimeFormatter cil = new DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern("MMM").toFormatter();
        b("cilenient", cil, months);
        // literals and case
        p("'T'HH", "T10", "t10", "x10");
        DateTimeFormatter cit = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("'Tx'HH").toFormatter();
        b("citext", cit, "Tx10", "tX10", "TX10");
        DateTimeFormatter lenl = new DateTimeFormatterBuilder().parseLenient().appendPattern("HH'x'mm").toFormatter();
        b("lenlit", lenl, "10x15", "10X15", "1015", "10 x 15");
        // custom text maps
        Map<Long, String> map = new HashMap<>();
        map.put(1L, "One"); map.put(2L, "Two"); map.put(3L, "Three"); map.put(13L, "Thirteen");
        DateTimeFormatter cm = new DateTimeFormatterBuilder().appendText(ChronoField.MONTH_OF_YEAR, map).toFormatter();
        System.out.println(cm);
        b("custom", cm, "One", "Two", "Thr", "Three", "Thirteen", "1", "Four", "one");
        for (TemporalAccessor t : List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 3, 1), LocalDate.of(2020, 5, 1))) {
            try { System.out.println("custom format " + t + " -> " + cm.format(t)); } catch (Exception e) { System.out.println("custom format " + t + " -> ! " + e); }
        }
        DateTimeFormatter tx = new DateTimeFormatterBuilder().appendText(ChronoField.DAY_OF_MONTH).toFormatter();
        System.out.println(tx + " " + tx.format(LocalDate.of(2020, 1, 15)));
        b("textnum", tx, "15", "1", "40");
        DateTimeFormatter tx2 = new DateTimeFormatterBuilder().appendText(ChronoField.HOUR_OF_DAY, TextStyle.SHORT).toFormatter();
        System.out.println(tx2 + " " + tx2.format(LocalTime.of(15, 0)));
        DateTimeFormatter tx3 = new DateTimeFormatterBuilder().appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT).appendText(ChronoField.DAY_OF_MONTH).toFormatter();
        b("textadj", tx3, "Jan15", "Jan1", "Jan");
    }

    }

    static class P5 extends Show {

    static void run() {
        String[] offs = {"Z", "z", "+01", "+1", "-01", "+0130", "+01:30", "+013015", "+01:30:15", "+01:3", "+0", "+", "", "+00", "-00", "+0000", "+00:00", "+18", "+19", "+1800", "+18:01", "+24", "+99", "+0160", "+01:60", "+013060", "+01:30:60", "+01x", "+01:", "+0130:15", "+01:3015", "UTC", "GMT", "+1:30", "+130", "-8", "+010", "+01300", "+01:30:1", "+013"};
        for (String pat : new String[] {"X", "XX", "XXX", "XXXX", "XXXXX", "x", "xx", "xxx", "xxxx", "xxxxx", "Z", "ZZZZZ"}) {
            p(pat, offs);
        }
        for (String op : new String[] {"+H", "+Hmm", "+H:mm", "+HMM", "+H:MM", "+HMMss", "+H:MM:ss", "+HMMSS", "+H:MM:SS", "+Hmmss", "+H:mm:ss", "+HH", "+HHmm", "+HH:mm", "+HHMM", "+HH:MM", "+HHMMss", "+HH:MM:ss", "+HHMMSS", "+HH:MM:SS", "+HHmmss", "+HH:mm:ss"}) {
            DateTimeFormatter f = new DateTimeFormatterBuilder().appendOffset(op, "Z").toFormatter();
            System.out.println(op + " -> " + f);
            for (int secs : new int[] {0, 3600, -3600, 5400, 5415, 36000, -36015, 1, 60, 64800}) {
                System.out.println("  fmt " + secs + " -> " + f.format(ZoneOffset.ofTotalSeconds(secs)));
            }
            b(op, f, offs);
            DateTimeFormatter fl = new DateTimeFormatterBuilder().parseLenient().appendOffset(op, "Z").toFormatter();
            b(op + " lenient", fl, offs);
        }
        for (String bad : new String[] {"+h", "HH", "+HHH", "+HH:MM:", "", "+", "+HH:MM:ss:xx", "+HHmM"}) {
            try { System.out.println(bad + " -> " + new DateTimeFormatterBuilder().appendOffset(bad, "Z").toFormatter()); } catch (Exception e) { System.out.println(bad + " -> ! " + e); }
        }
        try { new DateTimeFormatterBuilder().appendOffset("+HH", null); } catch (Exception e) { System.out.println("null text -> ! " + e); }
        DateTimeFormatter oid = new DateTimeFormatterBuilder().appendOffsetId().toFormatter();
        System.out.println(oid);
        b("offsetid", oid, offs);
        String[] gmts = {"GMT", "GMT+1", "GMT+01", "GMT+1:30", "GMT+01:30", "GMT+01:30:15", "GMT+1:30:15", "GMT-8", "GMT+18", "GMT+19", "GMT+0", "gmt+1", "UTC+1", "GMT+130", "GMT+1:3", "GMT+01:3", "GMT+10:00:5", "GMTx", "GMT+1x"};
        p("O", gmts);
        p("OOOO", "GMT", "GMT+1:30", "GMT+01:30", "GMT+01:30:15", "GMT+1:30:15", "gmt+1", "UTC+1", "GMT+130", "GMT+1:3", "GMT+10:00:5", "GMTx", "GMT+1x");
        DateTimeFormatter lo = new DateTimeFormatterBuilder().parseLenient().appendPattern("O").toFormatter();
        b("O lenient", lo, gmts);
    }

    }

    static class P6 extends Show {

    static void run() {
        String[] t = {"+0030", "+00:30", "+0000", "+00:00", "+00", "+0001", "-0030", "+00x", "+000030", "+00:00:30", "+0030x", "Z", "Zx", "+01"};
        for (String pat : new String[] {"x", "xx", "xxx", "xxxx", "xxxxx", "X", "XX", "XXX", "Z"}) {
            p(pat, t);
        }
        for (String nt : new String[] {"+00", "UTC", "", "+00:00", "Z"}) {
            for (String op : new String[] {"+HH", "+HH:MM", "+HHmm", "+HH:mm:ss"}) {
                DateTimeFormatter f = new DateTimeFormatterBuilder().appendOffset(op, nt).toFormatter();
                b(op + "/" + nt, f, "+0030", "+00:30", "+00", "UTC", "UTC+01", "+01", "+01:30", "", "+00:00:30", "Ux");
                DateTimeFormatter fl = new DateTimeFormatterBuilder().parseLenient().appendOffset(op, nt).toFormatter();
                b(op + "/" + nt + " lenient", fl, "+0030", "+00:30", "+00", "UTC", "UTC+01", "+01", "+01:30", "", "+00:00:30", "+1", "+130", "+1:30", "+01:3", "+0130", "+013015", "+01:30:15", "+01:3015");
            }
        }
    }

    }

    static class P7 extends Show {

    static void r(String pat, String... texts) {
        for (ResolverStyle st : ResolverStyle.values()) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pat).withResolverStyle(st);
            for (String t : texts) {
                System.out.println(st.name().substring(0, 2) + " " + pat + " [" + t + "] " + parsed(f, t));
            }
        }
    }
    static void run() {
        r("uuuu-MM-dd", "2021-02-28", "2021-02-29", "2020-02-29", "2021-02-30", "2021-02-31", "2021-04-31", "2021-04-30", "2021-02-32", "2021-13-01", "2021-00-01", "2021-01-00", "2021-00-00", "2021-12-99", "2021-99-99", "1000000000-01-01", "-999999999-01-01", "999999999-12-31");
        r("yyyy-MM-dd", "2021-02-28", "2021-02-29", "2021-02-30", "2021-13-01", "0000-01-01", "-0001-01-01");
        r("yyyy-MM-dd G", "2021-02-28 AD", "2021-02-28 BC", "0000-01-01 AD", "0001-01-01 BC", "2021-02-29 BC");
        r("uuuu-MM-dd G", "2021-02-28 AD", "2021-02-28 BC", "0000-01-01 AD", "0000-01-01 BC", "-0001-01-01 BC");
        r("uuuu yyyy", "2021 2021", "2021 2020", "0 1", "-1 2");
        r("yyyy", "2021", "0", "0000");
        r("G", "AD");
        r("yyyy G", "2021 AD", "2021 BC");
        r("uuuu-DDD", "2021-001", "2021-365", "2021-366", "2020-366", "2021-367", "2021-000", "2021-999");
        r("uuuu-MM", "2021-02", "2021-13");
        r("MM-dd", "02-29", "02-30", "13-01");
        r("uuuu-MM-dd E", "2021-01-04 Mon", "2021-01-04 Tue");
        r("uuuu-MM-dd D", "2021-01-04 4", "2021-01-04 5", "2021-01-04 400");
        r("uuuu-MM-dd F", "2021-01-04 1", "2021-01-04 2");
        r("uuuu-MM F E", "2021-01 1 Mon", "2021-01 2 Mon", "2021-01 5 Sun", "2021-01 6 Mon", "2021-02 5 Mon");
        r("uuuu-MM-dd'T'HH:mm", "2021-01-04T10:15", "2021-01-04T24:00", "2021-01-04T24:01", "2021-12-31T24:00", "2021-01-04T25:00");
        r("HH:mm:ss", "10:15:30", "24:00:00", "23:59:60", "24:00:01", "23:60:00", "25:00:00");
        r("HH:mm", "24:00", "10:60");
        r("HH", "24", "23");
        r("kk:mm", "24:00", "24:30", "00:30", "01:30");
        r("hh:mm", "12:00", "11:30", "13:00", "00:00");
        r("hh:mm a", "12:00 AM", "12:00 PM", "00:00 AM", "13:00 PM");
        r("KK:mm a", "11:00 PM", "12:00 PM");
        r("a", "AM", "PM");
        r("HH a", "10 AM", "10 PM", "22 PM", "22 AM", "00 PM");
        r("mm", "15");
        r("ss", "30");
        r("HH ss", "10 30");
        r("HH:mm SSS", "10:15 123");
        r("SSS", "123");
        r("A", "0", "86399999", "86400000", "1000");
        r("N", "0", "86399999999999", "86400000000000");
        r("HH:mm A", "10:15 36900000", "10:15 0");
        r("HH:mm:ss n", "10:15:30 123", "10:15:30 1000000000");
        r("HH:mm:ss.SSS n", "10:15:30.123 123000000", "10:15:30.123 123");
        r("uuuu-MM-dd HH:mm XXX", "2021-01-04 10:15 +01:00", "2021-01-04 24:00 +01:00", "2021-01-04 10:15 Z");
        r("uuuu-MM-dd HH:mm VV", "2021-01-04 10:15 UTC", "2021-01-04 10:15 Z", "2021-01-04 10:15 +01:00", "2021-01-04 10:15 Asia/Tokyo", "2021-01-04 10:15 Europe/Paris", "2021-01-04 10:15 GMT+1", "2021-01-04 10:15 Nowhere");
        r("HH:mm XXX", "10:15 +01:00");
        r("uuuu-MM-dd XXX", "2021-01-04 +01:00");
        r("VV", "UTC", "Asia/Tokyo");
        r("XXX", "+01:00");
    }

    }

    static class P8 extends Show {

    static void run() {
        for (Locale l : new Locale[] {Locale.ROOT, Locale.ENGLISH, Locale.US, Locale.UK, Locale.CANADA}) {
            StringBuilder sb = new StringBuilder(l + ":");
            for (String p : new String[] {"MMM", "MMMM", "MMMMM", "LLL", "LLLL", "LLLLL", "E", "EEEE", "EEEEE", "ccc", "cccc", "ccccc", "a", "G", "GGGG", "GGGGG", "O", "OOOO", "ZZZZ"}) {
                sb.append("\n  ").append(p).append(":");
                for (int m = 1; m <= 12; m++) {
                    LocalDateTime t = LocalDateTime.of(m == 12 ? -5 : 2021, m, m, m * 2 - 1, 0);
                    sb.append(' ').append(DateTimeFormatter.ofPattern(p, l).format(t.atOffset(ZoneOffset.ofTotalSeconds((m - 6) * 3600 + (m % 3) * 900))));
                }
            }
            System.out.println(sb);
        }
        System.out.println(DateTimeFormatter.ofPattern("y", Locale.UK).getLocale());
        System.out.println(DateTimeFormatter.ISO_DATE.withLocale(Locale.UK).getLocale());
        System.out.println(DateTimeFormatter.ISO_DATE.withLocale(Locale.UK));
    }

    }

    static class P9 extends Show {

    static void run() {
        Map<String, DateTimeFormatter> iso = new LinkedHashMap<>();
        iso.put("ISO_LOCAL_DATE", DateTimeFormatter.ISO_LOCAL_DATE);
        iso.put("ISO_OFFSET_DATE", DateTimeFormatter.ISO_OFFSET_DATE);
        iso.put("ISO_DATE", DateTimeFormatter.ISO_DATE);
        iso.put("ISO_LOCAL_TIME", DateTimeFormatter.ISO_LOCAL_TIME);
        iso.put("ISO_OFFSET_TIME", DateTimeFormatter.ISO_OFFSET_TIME);
        iso.put("ISO_TIME", DateTimeFormatter.ISO_TIME);
        iso.put("ISO_LOCAL_DATE_TIME", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        iso.put("ISO_OFFSET_DATE_TIME", DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        iso.put("ISO_ZONED_DATE_TIME", DateTimeFormatter.ISO_ZONED_DATE_TIME);
        iso.put("ISO_DATE_TIME", DateTimeFormatter.ISO_DATE_TIME);
        iso.put("ISO_ORDINAL_DATE", DateTimeFormatter.ISO_ORDINAL_DATE);
        iso.put("ISO_INSTANT", DateTimeFormatter.ISO_INSTANT);
        iso.put("BASIC_ISO_DATE", DateTimeFormatter.BASIC_ISO_DATE);
        iso.put("RFC_1123_DATE_TIME", DateTimeFormatter.RFC_1123_DATE_TIME);
        List<TemporalAccessor> values = List.of(
            LocalDate.of(2020, 1, 5), LocalDate.of(-5, 12, 31), LocalDate.of(12345, 6, 7), LocalDate.of(999999999, 12, 31),
            LocalTime.of(10, 15), LocalTime.of(10, 15, 30), LocalTime.of(10, 15, 30, 500000000), LocalTime.of(0, 0, 0, 1), LocalTime.of(23, 59, 59, 123456000),
            LocalDateTime.of(2020, 1, 5, 10, 15), LocalDateTime.of(2020, 1, 5, 10, 15, 0, 120000000),
            OffsetDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneOffset.ofHours(1)), OffsetDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneOffset.ofTotalSeconds(5415)), OffsetDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneOffset.UTC),
            ZonedDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneId.of("UTC")), ZonedDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneOffset.ofHours(-8)), ZonedDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneId.of("Asia/Tokyo")), ZonedDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneId.of("GMT+01:00")), ZonedDateTime.of(2020, 7, 5, 10, 15, 30, 0, ZoneId.of("Europe/Paris")),
            Instant.ofEpochSecond(0), Instant.ofEpochSecond(1600000000, 123000000), Instant.ofEpochSecond(-1, 5), Instant.MAX, Instant.MIN, Instant.parse("+10000-01-01T00:00:00Z"), Instant.parse("-10000-01-01T00:00:00Z"),
            YearMonth.of(2020, 5), Year.of(2020), ZoneOffset.ofHours(2), DayOfWeek.FRIDAY);
        for (Map.Entry<String, DateTimeFormatter> e : iso.entrySet()) {
            for (TemporalAccessor v : values) {
                String r;
                try { r = e.getValue().format(v); } catch (Exception x) { r = "! " + x.getClass().getName() + ": " + x.getMessage(); }
                System.out.println(e.getKey() + " | " + v + " -> " + r);
            }
        }
        // withZone
        DateTimeFormatter z = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.ofHours(9));
        for (TemporalAccessor v : values) {
            String r;
            try { r = z.format(v); } catch (Exception x) { r = "! " + x.getClass().getName() + ": " + x.getMessage(); }
            System.out.println("withZone+9 | " + v + " -> " + r);
        }
        DateTimeFormatter zz = DateTimeFormatter.ISO_ZONED_DATE_TIME.withZone(ZoneId.of("UTC"));
        for (TemporalAccessor v : values) {
            String r;
            try { r = zz.format(v); } catch (Exception x) { r = "! " + x.getClass().getName() + ": " + x.getMessage(); }
            System.out.println("zonedWithUTC | " + v + " -> " + r);
        }
        // parse specials
        b("ISO_DATE", DateTimeFormatter.ISO_DATE, "2020-01-05", "2020-01-05+01:00", "2020-01-05Z", "2020-01-05+01", "2020-01-05x");
        b("ISO_TIME", DateTimeFormatter.ISO_TIME, "10:15", "10:15+01:00", "10:15:30.5Z", "10:15:30.123456789-08:00");
        b("ISO_DATE_TIME", DateTimeFormatter.ISO_DATE_TIME, "2020-01-05T10:15", "2020-01-05T10:15+01:00", "2020-01-05T10:15+01:00[Europe/Paris]", "2020-01-05T10:15Z[UTC]", "2020-01-05T10:15[UTC]", "2020-01-05T10:15+09:00[Asia/Tokyo]", "2020-01-05T10:15+01:00[Nowhere/X]");
        b("ISO_ZONED_DATE_TIME", DateTimeFormatter.ISO_ZONED_DATE_TIME, "2020-01-05T10:15+01:00", "2020-01-05T10:15+01:00[Europe/Paris]", "2020-01-05T10:15[UTC]", "2020-01-05T10:15+01:00[UTC]");
        b("ISO_ORDINAL_DATE", DateTimeFormatter.ISO_ORDINAL_DATE, "2020-005", "2020-366", "2021-366", "2020-5", "2020-005+01:00");
        b("BASIC_ISO_DATE", DateTimeFormatter.BASIC_ISO_DATE, "20200105", "20200105+0100", "20200105Z", "20200105+01", "2020010", "+20200105", "202001055", "20200105+010030", "20200105+01:00", "20200105-0000");
        b("RFC_1123", DateTimeFormatter.RFC_1123_DATE_TIME, "Sun, 5 Jan 2020 10:15:30 GMT", "5 Jan 2020 10:15:30 GMT", "Sun, 05 Jan 2020 10:15 +0100", "Mon, 5 Jan 2020 10:15:30 GMT", "sun, 5 jan 2020 10:15:30 gmt", "Sunday, 5 Jan 2020 10:15:30 GMT", "Sun, 5 January 2020 10:15:30 GMT", "Sun,  5 Jan 2020 10:15:30 GMT", "Sun, 5 Jan 20 10:15:30 GMT", "Sun, 5 Jan 2020 10:15:30 UTC", "Sun, 5 Jan 2020 10:15:30 Z");
        b("ISO_INSTANT", DateTimeFormatter.ISO_INSTANT, "2020-01-05T10:15:30Z", "2020-01-05T10:15:30.5Z", "2020-01-05T10:15Z", "2020-01-05T10:15:30+01:00", "2020-01-05T10:15:30", "2020-01-05T23:59:60Z", "2020-01-05T24:00:00Z", "+12020-01-05T10:15:30Z", "2020-01-05t10:15:30z");
        b("ISO_LOCAL_DATE", DateTimeFormatter.ISO_LOCAL_DATE, "2020-01-05", "2020-1-5", "+2020-01-05", "20200105");
        b("ISO_LOCAL_TIME", DateTimeFormatter.ISO_LOCAL_TIME, "10:15", "10:15:30", "10:15:30.", "10:15:3", "24:00", "23:59:60");
        // parse with query
        for (String t : new String[] {"2020-01-05", "2020-02-30", "x"}) {
            try { System.out.println("LocalDate.parse f " + t + " -> " + LocalDate.parse(t, DateTimeFormatter.ofPattern("uuuu-MM-dd"))); } catch (Exception x) { System.out.println("LocalDate.parse f " + t + " -> ! " + x.getClass().getName() + ": " + sorted(x.getMessage()) + " <- " + x.getCause()); }
            try { System.out.println("LocalDateTime.parse f " + t + " -> " + LocalDateTime.parse(t, DateTimeFormatter.ofPattern("uuuu-MM-dd"))); } catch (Exception x) { System.out.println("LocalDateTime.parse f " + t + " -> ! " + x.getClass().getName() + ": " + sorted(x.getMessage()) + " <- " + (x.getCause() == null ? null : x.getCause().getClass().getName() + ": " + sorted(x.getCause().getMessage())) + " @" + ((DateTimeParseException) x).getErrorIndex()); }
            try { System.out.println("LocalTime.parse f " + t + " -> " + LocalTime.parse(t, DateTimeFormatter.ofPattern("uuuu-MM-dd"))); } catch (Exception x) { System.out.println("LocalTime.parse f " + t + " -> ! " + x.getClass().getName() + ": " + sorted(x.getMessage()) + " <- " + (x.getCause() == null ? null : x.getCause().getClass().getName() + ": " + sorted(x.getCause().getMessage()))); }
            try { System.out.println("YearMonth.parse f " + t + " -> " + YearMonth.parse(t, DateTimeFormatter.ofPattern("uuuu-MM-dd"))); } catch (Exception x) { System.out.println("YearMonth.parse f " + t + " -> ! " + x.getClass().getName() + ": " + sorted(x.getMessage())); }
            try { System.out.println("Year.parse f " + t + " -> " + Year.parse(t, DateTimeFormatter.ofPattern("uuuu-MM-dd"))); } catch (Exception x) { System.out.println("Year.parse f " + t + " -> ! " + x.getClass().getName() + ": " + sorted(x.getMessage())); }
            try { System.out.println("ZonedDateTime.parse f " + t + " -> " + ZonedDateTime.parse(t, DateTimeFormatter.ofPattern("uuuu-MM-dd"))); } catch (Exception x) { System.out.println("ZonedDateTime.parse f " + t + " -> ! " + x.getClass().getName() + ": " + sorted(x.getMessage()) + " <- " + (x.getCause() == null ? null : x.getCause().getClass().getName() + ": " + sorted(x.getCause().getMessage()))); }
            try { System.out.println("OffsetDateTime.parse f " + t + " -> " + OffsetDateTime.parse(t, DateTimeFormatter.ofPattern("uuuu-MM-dd"))); } catch (Exception x) { System.out.println("OffsetDateTime.parse f " + t + " -> ! " + x.getClass().getName() + ": " + sorted(x.getMessage()) + " <- " + (x.getCause() == null ? null : x.getCause().getClass().getName() + ": " + sorted(x.getCause().getMessage()))); }
        }
        try { LocalDate.parse("2020-01-05", null); } catch (Exception x) { System.out.println("null formatter -> " + x); }
        try { LocalDate.parse(null, DateTimeFormatter.ISO_LOCAL_DATE); } catch (Exception x) { System.out.println("null text -> " + x); }
        try { DateTimeFormatter.ISO_LOCAL_DATE.parse(null); } catch (Exception x) { System.out.println("null text2 -> " + x); }
        try { DateTimeFormatter.ISO_LOCAL_DATE.format(null); } catch (Exception x) { System.out.println("null fmt -> " + x); }
        try { LocalDate.of(2020,1,1).format(null); } catch (Exception x) { System.out.println("null fmt2 -> " + x); }
        System.out.println(LocalDate.of(2020,1,1).format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    }

    static class P10 extends Show {

    static void run() {
        List<TemporalAccessor> values = List.of(LocalDate.of(2020, 1, 5), LocalTime.of(10, 15), LocalDateTime.of(2020, 1, 5, 10, 15),
            OffsetDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneOffset.ofHours(1)), ZonedDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneId.of("UTC")),
            ZonedDateTime.of(2020, 1, 5, 10, 15, 30, 0, ZoneOffset.ofHours(2)), Instant.ofEpochSecond(5), Year.of(2020), YearMonth.of(2020, 5),
            ZoneOffset.ofHours(3), DayOfWeek.MONDAY, Month.MAY);
        Map<String, TemporalQuery<?>> qs = new LinkedHashMap<>();
        qs.put("zoneId", TemporalQueries.zoneId());
        qs.put("zone", TemporalQueries.zone());
        qs.put("offset", TemporalQueries.offset());
        qs.put("localDate", TemporalQueries.localDate());
        qs.put("localTime", TemporalQueries.localTime());
        qs.put("precision", TemporalQueries.precision());
        for (TemporalAccessor v : values) {
            StringBuilder sb = new StringBuilder(v.getClass().getSimpleName() + " " + v + ":");
            for (Map.Entry<String, TemporalQuery<?>> q : qs.entrySet()) {
                Object r;
                try { r = v.query(q.getValue()); } catch (Exception e) { r = "!" + e; }
                sb.append(' ').append(q.getKey()).append('=').append(r);
            }
            System.out.println(sb);
        }
        for (TemporalQuery<?> q : qs.values()) System.out.println(q);
        TemporalAccessor mine = new TemporalAccessor() {
            public boolean isSupported(TemporalField f) { return f == ChronoField.EPOCH_DAY || f == ChronoField.NANO_OF_DAY || f == ChronoField.OFFSET_SECONDS; }
            public long getLong(TemporalField f) { return f == ChronoField.EPOCH_DAY ? 18000 : f == ChronoField.NANO_OF_DAY ? 3600000000000L : 7200; }
            public String toString() { return "mine"; }
        };
        StringBuilder sb = new StringBuilder("mine:");
        for (Map.Entry<String, TemporalQuery<?>> q : qs.entrySet()) {
            sb.append(' ').append(q.getKey()).append('=').append(mine.query(q.getValue()));
        }
        System.out.println(sb);
        for (java.util.function.Function<TemporalAccessor, Object> f : List.<java.util.function.Function<TemporalAccessor, Object>>of(LocalDate::from, LocalTime::from, LocalDateTime::from, OffsetDateTime::from, ZonedDateTime::from, Instant::from, ZoneOffset::from, ZoneId::from, Year::from, YearMonth::from)) {
            try { System.out.println("from mine -> " + f.apply(mine)); } catch (Exception e) { System.out.println("from mine -> ! " + e.getClass().getName() + ": " + e.getMessage() + " <- " + e.getCause()); }
        }
        // from Parsed of various kinds
        String[][] cases = {{"uuuu-MM-dd", "2020-01-05"}, {"HH:mm", "10:15"}, {"uuuu-MM-dd HH:mm", "2020-01-05 10:15"}, {"uuuu-MM-dd HH:mm XXX", "2020-01-05 10:15 +01:00"}, {"uuuu-MM-dd HH:mm VV", "2020-01-05 10:15 UTC"}, {"uuuu-MM-dd HH:mm XXX VV", "2020-01-05 10:15 +01:00 UTC"}, {"uuuu-MM-dd HH:mm VV", "2020-01-05 10:15 +02:00"}, {"uuuu-MM", "2020-05"}, {"uuuu", "2020"}, {"XXX", "+01:00"}, {"VV", "UTC"}, {"HH:mm XXX", "10:15 +01:00"}, {"uuuu-MM-dd XXX", "2020-01-05 +01:00"}};
        for (String[] c : cases) {
            TemporalAccessor p = DateTimeFormatter.ofPattern(c[0]).parse(c[1]);
            for (java.util.function.Function<TemporalAccessor, Object> f : List.<java.util.function.Function<TemporalAccessor, Object>>of(LocalDate::from, LocalTime::from, LocalDateTime::from, OffsetDateTime::from, ZonedDateTime::from, Instant::from, ZoneOffset::from, ZoneId::from, Year::from, YearMonth::from, DayOfWeek::from, Month::from)) {
                try { System.out.println(c[0] + " [" + c[1] + "] from -> " + f.apply(p)); } catch (Exception e) { System.out.println(c[0] + " [" + c[1] + "] from -> ! " + e.getClass().getName() + ": " + sorted(e.getMessage()) + " <- " + (e.getCause() == null ? null : e.getCause().getClass().getName() + ": " + sorted(e.getCause().getMessage()))); }
            }
            StringBuilder fs = new StringBuilder(c[0] + " [" + c[1] + "] fields:");
            for (ChronoField fld : ChronoField.values()) {
                if (p.isSupported(fld)) {
                    fs.append(' ').append(fld).append('=');
                    try { fs.append(p.getLong(fld)); } catch (Exception e) { fs.append("!").append(e.getMessage()); }
                }
            }
            System.out.println(fs);
            try { p.getLong(ChronoField.ERA); } catch (Exception e) { System.out.println("  getLong era -> " + e); }
            try { System.out.println("  get year " + p.get(ChronoField.YEAR)); } catch (Exception e) { System.out.println("  get year -> " + e); }
            System.out.println("  precision " + p.query(TemporalQueries.precision()) + " range year " + (p.isSupported(ChronoField.YEAR) ? p.range(ChronoField.YEAR) : "-"));
        }
    }

    }

    static class P11 extends Show {

    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + " -> " + s.get()); } catch (Exception e) { System.out.println(label + " -> ! " + e.getClass().getName() + ": " + e.getMessage()); }
    }
    static DateTimeFormatterBuilder b() { return new DateTimeFormatterBuilder(); }
    static void run() {
        t("instant", () -> b().appendInstant().toFormatter());
        for (int n = -2; n <= 10; n++) { int k = n; t("instant " + n, () -> b().appendInstant(k).toFormatter()); }
        t("pad", () -> b().padNext(3).appendValue(ChronoField.HOUR_OF_DAY).toFormatter());
        t("pad x", () -> b().padNext(3, 'x').appendValue(ChronoField.HOUR_OF_DAY).toFormatter());
        t("pad 0", () -> b().padNext(0).appendValue(ChronoField.HOUR_OF_DAY).toFormatter());
        t("pad end", () -> b().padNext(3).toFormatter());
        t("pad optional", () -> b().padNext(3).optionalStart().appendValue(ChronoField.HOUR_OF_DAY).optionalEnd().toFormatter());
        t("zoneOrOffset", () -> b().appendZoneOrOffsetId().toFormatter());
        t("zoneRegion", () -> b().appendZoneRegionId().toFormatter());
        t("zoneId", () -> b().appendZoneId().toFormatter());
        t("append", () -> b().appendLiteral('x').append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral('y').toFormatter());
        t("appendOptional", () -> b().appendOptional(DateTimeFormatter.ISO_LOCAL_TIME).toFormatter());
        t("reduced int", () -> b().appendValueReduced(ChronoField.YEAR, 2, 4, 1950).toFormatter());
        t("reduced date", () -> b().appendValueReduced(ChronoField.YEAR, 2, 2, LocalDate.of(1950, 6, 1)).toFormatter());
        t("literal str", () -> b().appendLiteral("ab'c").appendLiteral("").appendLiteral("d").toFormatter());
        t("literal quote", () -> b().appendLiteral('\'').toFormatter());
        t("text full", () -> b().appendText(ChronoField.MONTH_OF_YEAR, TextStyle.FULL).toFormatter());
        t("text fullsa", () -> b().appendText(ChronoField.MONTH_OF_YEAR, TextStyle.FULL_STANDALONE).toFormatter());
        t("unclosed optional", () -> b().appendLiteral('a').optionalStart().appendLiteral('b').optionalStart().appendLiteral('c').toFormatter());
        t("optionalEnd none", () -> b().optionalEnd());
        t("empty optional", () -> b().optionalStart().optionalEnd().toFormatter());
        t("ci", () -> b().parseCaseInsensitive().appendLiteral('a').parseCaseSensitive().toFormatter());
        t("strict", () -> b().parseLenient().appendLiteral('a').parseStrict().toFormatter());
        t("value 0", () -> b().appendValue(ChronoField.YEAR, 0));
        t("value 20", () -> b().appendValue(ChronoField.YEAR, 20));
        t("value 1 20", () -> b().appendValue(ChronoField.YEAR, 1, 20, SignStyle.NORMAL));
        t("value 5 2", () -> b().appendValue(ChronoField.YEAR, 5, 2, SignStyle.NORMAL));
        t("value 0 2", () -> b().appendValue(ChronoField.YEAR, 0, 2, SignStyle.NORMAL));
        t("value 2 2 normal", () -> b().appendValue(ChronoField.YEAR, 2, 2, SignStyle.NORMAL).toFormatter());
        t("value 2 2 nn", () -> b().appendValue(ChronoField.YEAR, 2, 2, SignStyle.NOT_NEGATIVE).toFormatter());
        t("value 1 19 normal", () -> b().appendValue(ChronoField.YEAR, 1, 19, SignStyle.NORMAL).toFormatter());
        t("value 1 19 nn", () -> b().appendValue(ChronoField.YEAR, 1, 19, SignStyle.NOT_NEGATIVE).toFormatter());
        t("value 3 3 normal", () -> b().appendValue(ChronoField.YEAR, 3, 3, SignStyle.NORMAL).toFormatter());
        t("value null", () -> b().appendValue(null));
        t("fraction 0 9", () -> b().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, false).toFormatter());
        t("fraction 10", () -> b().appendFraction(ChronoField.NANO_OF_SECOND, 0, 10, false));
        t("fraction -1", () -> b().appendFraction(ChronoField.NANO_OF_SECOND, -1, 9, false));
        t("fraction 5 3", () -> b().appendFraction(ChronoField.NANO_OF_SECOND, 5, 3, false));
        t("fraction dom", () -> b().appendFraction(ChronoField.DAY_OF_MONTH, 0, 9, false));
        t("fraction milli", () -> b().appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true).toFormatter());
        t("reduced bad", () -> b().appendValueReduced(ChronoField.YEAR, 0, 2, 2000));
        t("reduced bad2", () -> b().appendValueReduced(ChronoField.YEAR, 3, 2, 2000));
        t("reduced bad3", () -> b().appendValueReduced(ChronoField.YEAR, 2, 11, 2000));
        t("reduced bad4", () -> b().appendValueReduced(ChronoField.YEAR, 10, 10, 2000));
        t("pad -1", () -> b().padNext(-1));
        t("text map null", () -> b().appendText(ChronoField.MONTH_OF_YEAR, (Map<Long, String>) null));
        t("toFormatter locale", () -> b().appendPattern("MMM").toFormatter(Locale.UK).getLocale());
        t("toFormatter null", () -> b().appendPattern("MMM").toFormatter(null));
        t("withLocale null", () -> DateTimeFormatter.ISO_DATE.withLocale(null));
        t("withResolverStyle null", () -> DateTimeFormatter.ISO_DATE.withResolverStyle(null));
        t("withZone null", () -> DateTimeFormatter.ISO_DATE.withZone(null).getZone());
        t("withZone same", () -> DateTimeFormatter.ISO_DATE.withZone(null) == DateTimeFormatter.ISO_DATE);
        t("withRS same", () -> DateTimeFormatter.ISO_DATE.withResolverStyle(ResolverStyle.STRICT) == DateTimeFormatter.ISO_DATE);
        t("ofPattern locale null", () -> DateTimeFormatter.ofPattern("y", null));
        // formats of the paddings and defaults
        t("pad fmt", () -> b().padNext(3).appendValue(ChronoField.HOUR_OF_DAY).toFormatter().format(LocalTime.of(5, 0)));
        t("pad fmt x", () -> b().padNext(3, 'x').appendValue(ChronoField.HOUR_OF_DAY).toFormatter().format(LocalTime.of(5, 0)));
        t("pad fmt over", () -> b().padNext(1).appendValue(ChronoField.HOUR_OF_DAY).toFormatter().format(LocalTime.of(15, 0)));
        DateTimeFormatter pad = b().padNext(3).appendValue(ChronoField.HOUR_OF_DAY).appendLiteral(':').toFormatter();
        b("pad parse", pad, "  5:", "005:", "5:", " 5:", "  5", "   5:", " 15:", "115:", "x 5:", "  -5:", "123:");
        DateTimeFormatter padl = b().parseLenient().padNext(3).appendValue(ChronoField.HOUR_OF_DAY).appendLiteral(':').toFormatter();
        b("pad parse lenient", padl, "  5:", "005:", "5:", " 5:", "  5", "   5:", " 15:", "115:", "x 5:");
        DateTimeFormatter padx = b().padNext(3, 'x').appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT).toFormatter();
        b("padx parse", padx, "Jan", "xJan", "xxJan", "xJa");
        DateTimeFormatter def = b().appendValue(ChronoField.YEAR, 4).parseDefaulting(ChronoField.MONTH_OF_YEAR, 1).parseDefaulting(ChronoField.DAY_OF_MONTH, 1).toFormatter();
        b("default parse", def, "2020");
        DateTimeFormatter def2 = b().appendValue(ChronoField.YEAR, 4).parseDefaulting(ChronoField.YEAR, 1999).optionalStart().appendLiteral('-').appendValue(ChronoField.MONTH_OF_YEAR, 2).optionalEnd().parseDefaulting(ChronoField.MONTH_OF_YEAR, 3).parseDefaulting(ChronoField.DAY_OF_MONTH, 1).toFormatter();
        b("default parse2", def2, "2020", "2020-05");
        t("default fmt", () -> def2.format(LocalDate.of(2020, 5, 5)));
        DateTimeFormatter opt = b().appendValue(ChronoField.HOUR_OF_DAY, 2).optionalStart().appendLiteral(':').appendValue(ChronoField.MINUTE_OF_HOUR, 2).optionalEnd().toFormatter();
        b("opt", opt, "10", "10:15", "10:", "10:1", "10:15:");
        DateTimeFormatter cio = b().parseCaseInsensitive().optionalStart().parseCaseSensitive().appendLiteral('x').optionalEnd().appendLiteral('y').toFormatter();
        System.out.println(cio);
        b("cio", cio, "xy", "xY", "XY", "y", "Y");
        DateTimeFormatter cio2 = b().optionalStart().parseCaseInsensitive().appendLiteral('x').optionalEnd().appendLiteral('y').toFormatter();
        b("cio2", cio2, "xy", "Xy", "XY", "xY");
        DateTimeFormatter dup = DateTimeFormatter.ofPattern("HH HH");
        b("dup", dup, "10 10", "10 11");
        DateTimeFormatter dup2 = DateTimeFormatter.ofPattern("uuuu-MM-dd uuuu");
        b("dup2", dup2, "2020-01-01 2020", "2020-01-01 2021");
        DateTimeFormatter dup3 = DateTimeFormatter.ofPattern("XXX XXX");
        b("dup3", dup3, "+01:00 +01:00", "+01:00 +02:00");
        DateTimeFormatter dup4 = DateTimeFormatter.ofPattern("VV VV");
        b("dup4", dup4, "UTC UTC", "UTC GMT");
        DateTimeFormatter ap = b().appendLiteral('<').append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral('>').toFormatter();
        b("appended", ap, "<2020-01-05>", "<2020-02-30>");
        t("appended fmt", () -> ap.format(LocalDate.of(2020, 1, 5)));
        DateTimeFormatter ao = b().appendValue(ChronoField.HOUR_OF_DAY, 2).appendOptional(b().appendLiteral(':').appendValue(ChronoField.MINUTE_OF_HOUR, 2).toFormatter()).toFormatter();
        b("appendOptional", ao, "10", "10:15", "10:x");
        t("appendOptional fmt", () -> ao.format(LocalTime.of(10, 15)));
        DateTimeFormatter red = b().appendValueReduced(ChronoField.YEAR, 2, 4, 1950).toFormatter();
        b("reduced 2 4 1950", red, "50", "49", "99", "00", "1949", "123", "12345");
        for (int y : new int[] {1949, 1950, 2049, 2050, 1, 99999, -5}) {
            t("reduced fmt " + y, () -> red.format(Year.of(y)));
        }
        DateTimeFormatter redl = b().parseLenient().appendValueReduced(ChronoField.YEAR, 2, 4, 1950).toFormatter();
        b("reduced lenient", redl, "50", "49", "5", "123", "12345", "+50", "-50");
        // appendText with map tie-breaks
        Map<Long, String> m = new LinkedHashMap<>();
        m.put(1L, "T"); m.put(2L, "Tw"); m.put(3L, "Two"); m.put(4L, "Tw");
        DateTimeFormatter tm = b().appendText(ChronoField.MONTH_OF_YEAR, m).toFormatter();
        b("textmap", tm, "T", "Tw", "Two", "Twx", "Tx");
        // instant
        b("instant parse", b().appendInstant().toFormatter(), "2020-01-05T10:15:30Z", "2020-01-05T10:15:30.123456789Z");
        b("instant3 parse", b().appendInstant(3).toFormatter(), "2020-01-05T10:15:30Z", "2020-01-05T10:15:30.123Z", "2020-01-05T10:15:30.1234Z", "2020-01-05T10:15:30.12Z");
        for (int n : new int[] {-1, 0, 1, 3, 9}) {
            DateTimeFormatter f = b().appendInstant(n).toFormatter();
            for (Instant i : new Instant[] {Instant.ofEpochSecond(0), Instant.ofEpochSecond(1, 500000000), Instant.ofEpochSecond(1, 123456789), Instant.ofEpochSecond(-1, 100)}) {
                t("instant fmt " + n + " " + i, () -> f.format(i));
            }
        }
        t("zoneOrOffset fmt offset", () -> b().appendZoneOrOffsetId().toFormatter().format(OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(1))));
        t("zoneRegion fmt z utc", () -> b().appendZoneRegionId().toFormatter().format(ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"))));
        b("zoneRegion parse", b().appendZoneRegionId().toFormatter(), "UTC", "+01:00", "Z", "Asia/Tokyo", "GMT+01:00");
        b("zoneOrOffset parse", b().appendZoneOrOffsetId().toFormatter(), "UTC", "+01:00", "Z", "Asia/Tokyo", "GMT+01:00");
    }

    }

    static class P12 extends Show {

    static void run() {
        for (Locale l : new Locale[] {Locale.ROOT, Locale.ENGLISH, Locale.US, Locale.UK, Locale.CANADA}) {
            for (ChronoField f : new ChronoField[] {ChronoField.MONTH_OF_YEAR, ChronoField.DAY_OF_WEEK, ChronoField.ERA, ChronoField.AMPM_OF_DAY}) {
                for (TextStyle st : TextStyle.values()) {
                    DateTimeFormatter fm = new DateTimeFormatterBuilder().appendText(f, st).toFormatter(l);
                    StringBuilder sb = new StringBuilder("[" + l + "] " + f + " " + st + ":");
                    long lo = f.range().getMinimum(), hi = f.range().getMaximum();
                    for (long v = lo; v <= hi; v++) {
                        TemporalAccessor t = f == ChronoField.MONTH_OF_YEAR ? LocalDate.of(2020, (int) v, 1)
                            : f == ChronoField.DAY_OF_WEEK ? LocalDate.of(2020, 1, 5 + (int) v)
                            : f == ChronoField.ERA ? LocalDate.of(v == 0 ? -5 : 5, 1, 1) : LocalTime.of(v == 0 ? 1 : 13, 0);
                        sb.append(" |").append(fm.format(t));
                    }
                    System.out.println(sb);
                }
            }
        }
    }

    }

    static class P13 extends Show {

    static DateTimeFormatterBuilder b() { return new DateTimeFormatterBuilder(); }
    static void run() {
        DateTimeFormatter inner = b().parseCaseInsensitive().appendLiteral('x').toFormatter();
        DateTimeFormatter f1 = b().append(inner).appendLiteral('y').toFormatter();
        System.out.println(f1);
        b("leak append", f1, "xy", "XY", "Xy", "xY");
        DateTimeFormatter f2 = b().optionalStart().parseCaseInsensitive().appendLiteral('x').appendLiteral('z').optionalEnd().appendLiteral('y').toFormatter();
        b("failed optional", f2, "xzy", "XZy", "XZY", "XY", "Y", "y");
        DateTimeFormatter f3 = b().appendOptional(inner).appendLiteral('y').toFormatter();
        b("leak appendOptional", f3, "xy", "XY", "Y");
        DateTimeFormatter f4 = b().parseLenient().append(b().parseStrict().appendValue(ChronoField.HOUR_OF_DAY, 2).toFormatter()).appendValue(ChronoField.MINUTE_OF_HOUR, 2).toFormatter();
        b("strict leak", f4, "1015", "10155", "101");
        // optional restores fields
        DateTimeFormatter f5 = b().optionalStart().appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral('x').optionalEnd().appendValue(ChronoField.HOUR_OF_DAY, 2).toFormatter();
        b("restore fields", f5, "10x10", "10x11", "11");
        // nested optional
        DateTimeFormatter f6 = DateTimeFormatter.ofPattern("HH[:mm[:ss]][ VV]");
        b("nested", f6, "10", "10:15", "10:15:30", "10 UTC", "10:15 UTC", "10:", "10:15:", "10 X");
        // format with optional when a field is missing
        for (TemporalAccessor t : List.of(LocalTime.of(10, 15), LocalDateTime.of(2020, 1, 1, 10, 15), ZonedDateTime.of(2020, 1, 1, 10, 15, 0, 0, ZoneId.of("UTC")))) {
            System.out.println("fmt nested " + t + " -> " + f6.format(t));
        }
        DateTimeFormatter f7 = DateTimeFormatter.ofPattern("[uuuu-MM-dd][HH:mm]");
        for (TemporalAccessor t : List.of(LocalTime.of(10, 15), LocalDate.of(2020, 1, 1), LocalDateTime.of(2020, 1, 1, 10, 15), Instant.EPOCH)) {
            System.out.println("fmt opt " + t + " -> " + f7.format(t));
        }
        b("opt both", f7, "2020-01-01", "10:15", "2020-01-0110:15", "", "2020-01-01x");
        // padding inside optional and padding of optional
        DateTimeFormatter f8 = b().padNext(5).optionalStart().appendValue(ChronoField.HOUR_OF_DAY).optionalEnd().toFormatter();
        for (TemporalAccessor t : List.of(LocalTime.of(10, 15), LocalDate.of(2020, 1, 1))) {
            System.out.println("pad opt " + t + " -> [" + f8.format(t) + "]");
        }
        b("pad opt", f8, "   10", "     ", "10   ", "  10x");
        // text with case-insensitive and longest match against texts of different case
        DateTimeFormatter f9 = b().parseCaseInsensitive().appendPattern("MMMM").toFormatter();
        b("ci full", f9, "MAY", "may", "JUNE", "june", "Junee");
        // a value parse in lenient with signs
        DateTimeFormatter f10 = b().parseLenient().appendValue(ChronoField.YEAR).toFormatter();
        b("lenient u", f10, "+5", "-5", "+0", "-0", "5", "+", "-");
        DateTimeFormatter f11 = b().parseLenient().appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD).toFormatter();
        b("lenient uuuu", f11, "+5", "-5", "+0", "-0", "5", "+2020", "12345", "+12345");
        DateTimeFormatter f12 = b().parseLenient().appendValue(ChronoField.YEAR, 1, 10, SignStyle.NOT_NEGATIVE).toFormatter();
        b("lenient nn", f12, "+5", "-5", "5");
        DateTimeFormatter f13 = b().parseLenient().appendValue(ChronoField.YEAR, 1, 10, SignStyle.NEVER).toFormatter();
        b("lenient never", f13, "+5", "-5", "5");
        DateTimeFormatter f14 = b().appendValue(ChronoField.YEAR, 1, 10, SignStyle.NEVER).toFormatter();
        b("strict never", f14, "+5", "-5", "5");
        DateTimeFormatter f15 = b().appendValue(ChronoField.YEAR, 1, 10, SignStyle.ALWAYS).toFormatter();
        b("strict always", f15, "+5", "-5", "5", "+0", "-0");
        DateTimeFormatter f16 = b().parseLenient().appendValue(ChronoField.YEAR, 1, 10, SignStyle.ALWAYS).toFormatter();
        b("lenient always", f16, "+5", "-5", "5", "+0", "-0");
        DateTimeFormatter f17 = b().appendValue(ChronoField.YEAR, 2, 10, SignStyle.EXCEEDS_PAD).toFormatter();
        b("strict ep2", f17, "+5", "+55", "+555", "555", "55", "-5", "-55", "-555", "5");
        for (long y : new long[] {5, 55, 555, -5, -555, 0}) {
            for (SignStyle ss : SignStyle.values()) {
                DateTimeFormatter f = b().appendValue(ChronoField.YEAR, 2, 10, ss).toFormatter();
                try { System.out.println("fmt " + ss + " " + y + " -> " + f.format(Year.of((int) y))); } catch (Exception e) { System.out.println("fmt " + ss + " " + y + " -> ! " + e.getMessage()); }
            }
        }
    }

    }

    static class P14 extends Show {

    static void run() {
        ChronoField[] fs = {ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND, ChronoField.MILLI_OF_SECOND, ChronoField.MICRO_OF_SECOND, ChronoField.INSTANT_SECONDS};
        long[] vs = {10, 15, 30, 123456789, 123, 123456, 1000};
        for (int mask = 1; mask < (1 << fs.length); mask++) {
            DateTimeFormatterBuilder bld = new DateTimeFormatterBuilder();
            StringBuilder text = new StringBuilder();
            StringBuilder label = new StringBuilder();
            for (int i = 0; i < fs.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    if (text.length() > 0) { bld.appendLiteral(' '); text.append(' '); }
                    bld.appendValue(fs[i]);
                    text.append(vs[i]);
                    label.append(fs[i].toString().charAt(0)).append(fs[i].toString().charAt(fs[i].toString().length() - 1));
                }
            }
            for (ResolverStyle st : ResolverStyle.values()) {
                b(st.name().substring(0, 2) + " " + label, bld.toFormatter().withResolverStyle(st), text.toString());
            }
        }
        // the milli / micro / nano agreements
        DateTimeFormatter f = new DateTimeFormatterBuilder().appendValue(ChronoField.HOUR_OF_DAY).appendLiteral(' ').appendValue(ChronoField.MILLI_OF_SECOND).appendLiteral(' ').appendValue(ChronoField.NANO_OF_SECOND).toFormatter();
        b("HMN", f, "10 123 123000000", "10 123 123456789", "10 124 123456789", "10 1000 0");
        DateTimeFormatter f2 = new DateTimeFormatterBuilder().appendValue(ChronoField.MILLI_OF_SECOND).appendLiteral(' ').appendValue(ChronoField.MICRO_OF_SECOND).toFormatter();
        b("Mi Mc", f2, "123 123456", "123 124000", "1000 5");
        DateTimeFormatter f3 = new DateTimeFormatterBuilder().appendValue(ChronoField.SECOND_OF_DAY).appendLiteral(' ').appendValue(ChronoField.MINUTE_OF_HOUR).toFormatter();
        b("SoD MoH", f3, "36930 15", "36930 16", "86400 0", "-1 0");
        DateTimeFormatter f4 = new DateTimeFormatterBuilder().appendValue(ChronoField.MINUTE_OF_DAY).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("MoD " + st, f4.withResolverStyle(st), "615", "1440", "-1");
        DateTimeFormatter f5 = new DateTimeFormatterBuilder().appendValue(ChronoField.SECOND_OF_DAY).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("SoD " + st, f5.withResolverStyle(st), "36930", "86400", "-1");
        DateTimeFormatter f6 = new DateTimeFormatterBuilder().appendValue(ChronoField.MICRO_OF_DAY).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("McoD " + st, f6.withResolverStyle(st), "36930000001", "86400000000");
        DateTimeFormatter f7 = new DateTimeFormatterBuilder().appendValue(ChronoField.HOUR_OF_AMPM).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("HoA " + st, f7.withResolverStyle(st), "5", "12");
        DateTimeFormatter f8 = new DateTimeFormatterBuilder().appendValue(ChronoField.AMPM_OF_DAY).appendLiteral(' ').appendValue(ChronoField.HOUR_OF_AMPM).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("A HoA " + st, f8.withResolverStyle(st), "1 5", "2 5", "1 12", "0 13", "-1 5");
        DateTimeFormatter f9 = new DateTimeFormatterBuilder().appendValue(ChronoField.AMPM_OF_DAY).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("A " + st, f9.withResolverStyle(st), "0", "1", "2", "-1");
        DateTimeFormatter f10 = new DateTimeFormatterBuilder().appendValue(ChronoField.CLOCK_HOUR_OF_AMPM).appendLiteral(' ').appendValue(ChronoField.AMPM_OF_DAY).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("CHoA A " + st, f10.withResolverStyle(st), "12 0", "12 1", "0 0", "13 1");
        DateTimeFormatter f11 = new DateTimeFormatterBuilder().appendValue(ChronoField.HOUR_OF_DAY).appendLiteral(' ').appendValue(ChronoField.CLOCK_HOUR_OF_DAY).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("H CH " + st, f11.withResolverStyle(st), "0 24", "1 1", "0 0", "1 2");
        DateTimeFormatter f12 = new DateTimeFormatterBuilder().appendValue(ChronoField.HOUR_OF_DAY).appendLiteral(' ').appendValue(ChronoField.AMPM_OF_DAY).appendLiteral(' ').appendValue(ChronoField.HOUR_OF_AMPM).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("H A HoA " + st, f12.withResolverStyle(st), "13 1 1", "13 1 2", "13 0 1");
        DateTimeFormatter f13 = new DateTimeFormatterBuilder().appendValue(ChronoField.NANO_OF_DAY).appendLiteral(' ').appendValue(ChronoField.HOUR_OF_DAY).toFormatter();
        for (ResolverStyle st : ResolverStyle.values()) b("NoD H " + st, f13.withResolverStyle(st), "36000000000000 10", "36000000000000 11");
    }

    }

    static class P15 extends Show {

    static void run() {
        ChronoField[] fs = ChronoField.values();
        for (ChronoField f : fs) {
            if (f == ChronoField.INSTANT_SECONDS || f == ChronoField.OFFSET_SECONDS) continue;
            DateTimeFormatter fm = new DateTimeFormatterBuilder().appendValue(f, 1, 19, SignStyle.NORMAL).toFormatter();
            long hi = f.range().getMaximum() + 1, lo = f.range().getMinimum() - 1;
            for (ResolverStyle st : ResolverStyle.values()) {
                b(st.name().substring(0, 2) + " " + f, fm.withResolverStyle(st), Long.toString(hi), Long.toString(lo), Long.toString(f.range().getMinimum()));
            }
        }
        for (ResolverStyle st : ResolverStyle.values()) {
            String s = st.name().substring(0, 2);
            b(s + " HH:mm:ss", DateTimeFormatter.ofPattern("HH:mm:ss").withResolverStyle(st), "25:61:61", "23:61:61", "25:00:61", "24:00:00", "24:00:61");
            b(s + " HH mm", DateTimeFormatter.ofPattern("HH mm").withResolverStyle(st), "25 61", "24 61");
            b(s + " mm ss", DateTimeFormatter.ofPattern("mm ss").withResolverStyle(st), "59 61");
            b(s + " HH ss", DateTimeFormatter.ofPattern("HH ss").withResolverStyle(st), "25 61", "23 61", "24 00");
            b(s + " ss n", DateTimeFormatter.ofPattern("ss n").withResolverStyle(st), "59 1000000000");
            b(s + " a mm", DateTimeFormatter.ofPattern("a mm").withResolverStyle(st), "AM 15", "PM 61");
            b(s + " a ss", DateTimeFormatter.ofPattern("a ss").withResolverStyle(st), "AM 15");
            b(s + " uuuu-MM", DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(st), "2020-13", "2020-00");
            b(s + " uuuu dd", DateTimeFormatter.ofPattern("uuuu dd").withResolverStyle(st), "2020 32", "2020 00");
            b(s + " MM", DateTimeFormatter.ofPattern("MM").withResolverStyle(st), "13", "00");
            b(s + " E", new DateTimeFormatterBuilder().appendValue(ChronoField.DAY_OF_WEEK).toFormatter().withResolverStyle(st), "8", "0");
            b(s + " uuuu-MM-dd HH", DateTimeFormatter.ofPattern("uuuu-MM-dd HH").withResolverStyle(st), "2020-13-01 25", "2020-01-32 25", "2020-02-30 24");
            b(s + " G", new DateTimeFormatterBuilder().appendValue(ChronoField.ERA).toFormatter().withResolverStyle(st), "2", "-1");
            b(s + " G yyyy", new DateTimeFormatterBuilder().appendValue(ChronoField.ERA).appendLiteral(' ').appendPattern("yyyy").toFormatter().withResolverStyle(st), "2 2020", "-1 2020");
            b(s + " PM", new DateTimeFormatterBuilder().appendValue(ChronoField.PROLEPTIC_MONTH).toFormatter().withResolverStyle(st), "24240", "-1", "99999999999999");
            b(s + " ED", new DateTimeFormatterBuilder().appendValue(ChronoField.EPOCH_DAY).toFormatter().withResolverStyle(st), "18266", "-1", "99999999999999");
            b(s + " ED uuuu-MM-dd", new DateTimeFormatterBuilder().appendValue(ChronoField.EPOCH_DAY).appendLiteral(' ').appendPattern("uuuu-MM-dd").toFormatter().withResolverStyle(st), "18266 2020-01-05", "18266 2020-01-06");
            b(s + " PM dd", new DateTimeFormatterBuilder().appendValue(ChronoField.PROLEPTIC_MONTH).appendLiteral(' ').appendPattern("dd").toFormatter().withResolverStyle(st), "24240 05", "24240 32");
            b(s + " PM uuuu", new DateTimeFormatterBuilder().appendValue(ChronoField.PROLEPTIC_MONTH).appendLiteral(' ').appendPattern("uuuu").toFormatter().withResolverStyle(st), "24240 2020", "24240 2021");
            b(s + " uuuu-MM AWoM ADoWiM", new DateTimeFormatterBuilder().appendPattern("uuuu-MM ").appendValue(ChronoField.ALIGNED_WEEK_OF_MONTH).appendLiteral(' ').appendValue(ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH).toFormatter().withResolverStyle(st), "2021-01 1 1", "2021-01 5 7", "2021-02 5 1", "2021-01 6 1", "2021-01 1 8", "2021-01 0 0");
            b(s + " uuuu AWoY ADoWiY", new DateTimeFormatterBuilder().appendPattern("uuuu ").appendValue(ChronoField.ALIGNED_WEEK_OF_YEAR).appendLiteral(' ').appendValue(ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR).toFormatter().withResolverStyle(st), "2021 1 1", "2021 53 7", "2021 52 7", "2021 54 1", "2021 0 0");
            // leniently a JDK's day of a week of the year is not one rule the probe found
            b(s + " uuuu AWoY E", new DateTimeFormatterBuilder().appendPattern("uuuu ").appendValue(ChronoField.ALIGNED_WEEK_OF_YEAR).appendPattern(" E").toFormatter().withResolverStyle(st), st == ResolverStyle.LENIENT ? new String[] {"2021 1 Mon", "2021 53 Sun"} : new String[] {"2021 1 Mon", "2021 53 Sun", "2021 52 Fri", "2021 1 Fri"});
            b(s + " uuuu-MM-dd INST", new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm XXX ").appendValue(ChronoField.INSTANT_SECONDS).toFormatter().withResolverStyle(st), "2021-01-04 10:15 +01:00 1609751700", "2021-01-04 10:15 +01:00 1609751701");
            b(s + " instant zone", DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).withResolverStyle(st), "2021-01-04T10:15:30.5Z");
            b(s + " instant zone2", DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("Asia/Tokyo")).withResolverStyle(st), "2021-01-04T10:15:30Z");
            b(s + " local with zone", DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).withResolverStyle(st), "2021-01-04T10:15:30");
            b(s + " offset with zone", DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC")).withResolverStyle(st), "2021-01-04T10:15:30+01:00");
            b(s + " INST alone", new DateTimeFormatterBuilder().appendValue(ChronoField.INSTANT_SECONDS).toFormatter().withResolverStyle(st), "1609751700");
            b(s + " INST XXX", new DateTimeFormatterBuilder().appendValue(ChronoField.INSTANT_SECONDS).appendPattern(" XXX").toFormatter().withResolverStyle(st), "1609751700 +01:00");
            b(s + " INST VV", new DateTimeFormatterBuilder().appendValue(ChronoField.INSTANT_SECONDS).appendPattern(" VV").toFormatter().withResolverStyle(st), "1609751700 UTC");
        }
        try { System.out.println(LocalDateTime.parse("2021-01-04T10:15:30Z", DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC))); } catch (Exception e) { System.out.println("! " + e); }
        try { System.out.println(ZonedDateTime.parse("2021-01-04T10:15:30Z", DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC")))); } catch (Exception e) { System.out.println("! " + e); }
        try { System.out.println(Instant.from(DateTimeFormatter.ISO_INSTANT.parse("2021-01-04T10:15:30.123Z"))); } catch (Exception e) { System.out.println("! " + e); }
        b("lenient era", new DateTimeFormatterBuilder().parseLenient().appendPattern("G").toFormatter(), "BCE", "Common Era", "Before Common Era", "C", "AD", "c", "ce", "B", "BC");
        b("lenient era ci", new DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern("G").toFormatter(), "bce", "ad", "bc");
        b("lenient ampm", new DateTimeFormatterBuilder().parseLenient().appendPattern("a").toFormatter(), "a", "p", "am", "pm", "AM", "a.m.", "noon", "midnight");
        b("lenient month", new DateTimeFormatterBuilder().parseLenient().appendPattern("MMM").toFormatter(), "Sept", "M01", "1", "J");
        b("lenient day", new DateTimeFormatterBuilder().parseLenient().appendPattern("EEE").toFormatter(), "Tues", "Thur", "Th", "W");
        b("lenient era UK", new DateTimeFormatterBuilder().parseLenient().appendPattern("G").toFormatter(Locale.UK), "BCE", "AD");
        b("lenient era root", new DateTimeFormatterBuilder().parseLenient().appendPattern("G").toFormatter(Locale.ROOT), "CE", "BCE", "AD", "BC");
        b("strict era root", new DateTimeFormatterBuilder().appendPattern("G").toFormatter(Locale.ROOT), "CE", "BCE", "AD", "BC");
        b("strict month root", new DateTimeFormatterBuilder().appendPattern("MMMMM").toFormatter(Locale.ROOT), "1", "12", "13");
        b("strict ampm uk", new DateTimeFormatterBuilder().appendPattern("a").toFormatter(Locale.UK), "am", "AM", "pm");
        b("strict ampm ca", new DateTimeFormatterBuilder().appendPattern("a").toFormatter(Locale.CANADA), "a.m.", "am", "AM", "p.m.");
    }

    }

    static class P16 {

    /** A message without the fields a JDK lists in an order that changes from run to run. */
    static String steady(String message) {
        if (message == null) {
            return "null";
        }
        int open = message.indexOf('{');
        int close = message.indexOf('}');
        return open >= 0 && close > open ? message.substring(0, open) + "{..}" + message.substring(close + 1) : message;
    }

    static void t16(String label, Supplier<Object> made) {
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
            t16(kind + " [" + text + "]", () -> parse.apply(text));
        }
    }

    static void run() {
        read("LocalDate", t -> LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE), "2020-01-01", "2020-1-01", "2020-13-01", "2021-02-29", "2020-02-29",
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
        read("LocalTime", t -> LocalTime.parse(t, DateTimeFormatter.ISO_LOCAL_TIME), "10:15", "10:15:30", "10:15:30.1", "10:15:30.123456789",
                "10:15:30.1234567890", "10:15:30.", "10:15:30,5", "24:00", "23:60", "23:59:60", "1:15", "10:5",
                "10:15:3", "10", "10:15:", "10:15:30.000", "10:15:00", "00:00", "T10:15", "10:15Z",
                "10:15:30.000000001", "10:15:30.0000000", "10-15", "99:99", "10:15:30+01:00", "+10:15", "10:15 ",
                "24:60", "25:61:61", "10:60:60", "10:15:60.5", "10:15:30.a", "10:15:30..", "10:15.5", "aa:15", "10:aa",
                "1", "", "10:15:30.123456789Z", "23:59:59.999999999", "24:00:00", "00:00:00.0", "10:15:30.5x",
                "25:00:61", "25:00:00", "23:00:61", "10:15:30.12", "10:15:30.1234", "10:15:30.12345678");
        read("LocalDateTime", t -> LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME), "2020-01-01T10:15", "2020-01-01t10:15", "2020-01-01 10:15",
                "2020-01-01T10:15:30.5", "2020-01-01T24:00", "2020-01-01T24:00:00", "2020-02-30T10:15",
                "2020-01-01T10:15Z", "2020-01-01", "2020-01-01T", "2020-01-01T10:15:30.123456789",
                "+12020-01-01T10:15", "2020-01-01T23:59:60", "2020-13-01T24:60", "2020-01-01T24:60",
                "2020-02-30T25:00", "2020-02-30T10:15x", "2020-13-01T10:15:30.5x", "+2020-01-01T10:15", "",
                "-999999999-01-01T00:00", "+999999999-12-31T23:59:59.999999999");
        read("OffsetDateTime", t -> OffsetDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME), "2020-01-01T10:15+01:00", "2020-01-01T10:15Z",
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
        read("ZonedDateTime", t -> ZonedDateTime.parse(t, DateTimeFormatter.ISO_ZONED_DATE_TIME), "2020-01-01T10:15+01:00", "2020-01-01T10:15Z",
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
        read("Instant", t -> DateTimeFormatter.ISO_INSTANT.parse(t, Instant::from), "2020-01-01T10:15:30Z", "2020-01-01T10:15Z", "2020-01-01T10:15:30.123456789Z",
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
        }
    }

    public static void main(String[] args) {
        P1.run();
        P2.run();
        P3.run();
        P4.run();
        P5.run();
        P6.run();
        P7.run();
        P8.run();
        P9.run();
        P10.run();
        P11.run();
        P12.run();
        P13.run();
        P14.run();
        P15.run();
        P16.run();
    }
}
