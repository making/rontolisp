import java.text.FieldPosition;
import java.text.Format;
import java.text.ParseException;
import java.text.ParsePosition;
import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

// What k57 left of java.time's formatter, asked of a JDK 25: the characters of a
// formatter's numbers, its members of a position and a java.text.Format, the fields it
// resolves, the date fields over a map and a calendar of the program's own, and where k43
// and k52 did not follow a JDK (an era read leniently, a day of the week in an aligned week
// read leniently, a full localized offset's index errors, a fraction after a run, the null
// refusals, two clocks).
public class Grid {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    // the fields of each map a JDK prints in an order of its run, sorted
    static String shown(Object o) {
        String text = String.valueOf(o);
        StringBuilder sorted = new StringBuilder();
        int at = 0;
        while (true) {
            int open = text.indexOf('{', at);
            int close = open < 0 ? -1 : text.indexOf('}', open + 1);
            if (close < 0) {
                return sorted.append(text.substring(at)).toString();
            }
            String[] fields = text.substring(open + 1, close).split(", ");
            Arrays.sort(fields);
            sorted.append(text, at, open + 1).append(String.join(", ", fields)).append('}');
            at = close + 1;
        }
    }

    static void attempt(String what, Supplier<Object> made) {
        try {
            System.out.println(what + ": " + shown(made.get()));
        }
        catch (DateTimeParseException e) {
            System.out.println(what + ": " + shown(e.getMessage()) + " @" + e.getErrorIndex() + " cause " + e.getCause());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + shown(e) + " cause " + e.getCause());
        }
    }

    interface Call {
        Object call() throws Exception;
    }

    static void checked(String what, Call made) {
        try {
            System.out.println(what + ": " + shown(made.call()));
        }
        catch (ParseException e) {
            System.out.println(what + ": " + shown(e) + " offset " + e.getErrorOffset() + " cause " + e.getCause());
        }
        catch (Exception e) {
            System.out.println(what + ": " + shown(e) + " cause " + e.getCause());
        }
    }

    public static void main(String[] args) {
        Decimals.run();
        Positions.run();
        Resolving.run();
        Calendars.run();
        Left.run();
    }

    // The characters of the numbers: DecimalStyle, written and read.
    static final class Decimals {

        static void run() {
            DecimalStyle s = DecimalStyle.STANDARD;
            System.out.println(s + " " + s.hashCode() + " " + s.getZeroDigit() + s.getPositiveSign() + s.getNegativeSign()
                    + s.getDecimalSeparator());
            for (Locale l : new Locale[] {Locale.ROOT, Locale.ENGLISH, Locale.US, Locale.UK, Locale.CANADA}) {
                DecimalStyle d = DecimalStyle.of(l);
                System.out.println(l.toLanguageTag() + " " + d + " " + (d == s) + " "
                        + DecimalStyle.getAvailableLocales().contains(l));
            }
            System.out.println(DecimalStyle.ofDefaultLocale() + " " + (DecimalStyle.ofDefaultLocale() == s));
            for (char z : new char[] {'0', 'a', '9'}) {
                for (char p : new char[] {'+', 'P'}) {
                    for (char n : new char[] {'-', 'N'}) {
                        for (char d : new char[] {'.', ','}) {
                            DecimalStyle x = s.withZeroDigit(z).withPositiveSign(p).withNegativeSign(n).withDecimalSeparator(d);
                            System.out.println(x + " " + x.hashCode() + " " + x.equals(s) + " "
                                    + x.equals(DecimalStyle.STANDARD.withZeroDigit(z).withPositiveSign(p).withNegativeSign(n)
                                            .withDecimalSeparator(d)));
                        }
                    }
                }
            }
            System.out.println((s.withZeroDigit('0') == s) + " " + (s.withPositiveSign('+') == s) + " "
                    + (s.withNegativeSign('-') == s) + " " + (s.withDecimalSeparator('.') == s) + " " + s.equals(null));
            attempt("of null", () -> DecimalStyle.of(null));
            DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
            attempt("withDecimalStyle null", () -> iso.withDecimalStyle(null));
            DecimalStyle weird = s.withZeroDigit('a').withPositiveSign('p').withNegativeSign('n').withDecimalSeparator(',');
            DecimalStyle arabic = s.withZeroDigit('٠');
            DateTimeFormatter w = iso.withDecimalStyle(weird);
            System.out.println(iso.getDecimalStyle() + " " + (iso.withDecimalStyle(DecimalStyle.of(Locale.US)) == iso) + " "
                    + w.getDecimalStyle() + " " + (w.withDecimalStyle(s.withZeroDigit('a').withPositiveSign('p')
                            .withNegativeSign('n').withDecimalSeparator(',')) == w) + " " + w);
            System.out.println(w.withLocale(Locale.UK).getDecimalStyle() + " " + w.localizedBy(Locale.US).getDecimalStyle()
                    + " " + (w.localizedBy(Locale.US) == w));
            DateTimeFormatter iso2 = DateTimeFormatter.ofPattern("uuuu", Locale.US).withChronology(IsoChronology.INSTANCE);
            System.out.println((iso2.localizedBy(Locale.US) == iso2) + " "
                    + (iso2.withZone(ZoneOffset.UTC).localizedBy(Locale.US).getZone()));
            ZonedDateTime z1 = ZonedDateTime.of(-12345, 3, 7, 9, 5, 4, 120000000, ZoneOffset.ofHoursMinutes(-5, -30));
            ZonedDateTime z2 = ZonedDateTime.of(12345, 3, 7, 9, 5, 4, 120000000, ZoneOffset.ofHoursMinutes(5, 30));
            ZonedDateTime z3 = ZonedDateTime.of(2026, 3, 7, 9, 5, 4, 120000000, PARIS);
            String[] patterns = {"uuuu-MM-dd", "yyyy-MM-dd G", "yy-M-d", "u", "uuuuu", "HH:mm:ss.SSS", "H:m:s.n", "A N",
                "D DD DDD", "e c ee E", "Y YY YYYY w ww W", "Q QQ QQQ q", "g", "F", "a h K k",
                "xxx XXX ZZZZ OOOO O xxxxx", "VV z", "B", "MMM MMMM", "'quoted 123'"};
            for (DecimalStyle d : new DecimalStyle[] {weird, arabic}) {
                for (String p : patterns) {
                    DateTimeFormatter f = DateTimeFormatter.ofPattern(p, Locale.US).withDecimalStyle(d);
                    attempt(d + " " + p, () -> f.format(z1) + " | " + f.format(z2) + " | " + f.format(z3));
                }
                DateTimeFormatter[] forms = {DateTimeFormatter.ISO_INSTANT, DateTimeFormatter.ISO_OFFSET_DATE_TIME,
                    DateTimeFormatter.ISO_ZONED_DATE_TIME, DateTimeFormatter.ISO_WEEK_DATE, DateTimeFormatter.ISO_ORDINAL_DATE};
                for (DateTimeFormatter form : forms) {
                    attempt(d + " " + form, () -> form.withDecimalStyle(d).format(z2));
                }
                attempt(d + " instant 3", () -> new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                        .withDecimalStyle(d).format(z2));
                attempt(d + " fractions", () -> new DateTimeFormatterBuilder()
                        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).appendLiteral(' ')
                        .appendFraction(ChronoField.NANO_OF_SECOND, 2, 9, false).toFormatter().withDecimalStyle(d).format(z2));
                attempt(d + " signed", () -> new DateTimeFormatterBuilder()
                        .appendValue(ChronoField.YEAR, 2, 10, SignStyle.ALWAYS).toFormatter().withDecimalStyle(d)
                        .format(z2) + " " + new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR, 2, 10, SignStyle.ALWAYS)
                        .toFormatter().withDecimalStyle(d).format(z1));
                attempt(d + " reduced and padded", () -> new DateTimeFormatterBuilder()
                        .appendValueReduced(ChronoField.YEAR, 2, 4, 2000).appendLiteral(' ').padNext(8, '0')
                        .appendValue(ChronoField.YEAR).appendLiteral(' ')
                        .appendText(ChronoField.MONTH_OF_YEAR, Map.of(1L, "one")).toFormatter().withDecimalStyle(d).format(z2));
                for (FormatStyle style : new FormatStyle[] {FormatStyle.MEDIUM, FormatStyle.SHORT}) {
                    attempt(d + " localized " + style, () -> DateTimeFormatter.ofLocalizedDateTime(style).withDecimalStyle(d)
                            .format(z2).replace(' ', '_'));
                }
            }
            String[][] read = {
                {"uuuu-MM-dd", "cacg-ad-ah", "2026-03-07", "cacg-ad-a7", "CACG-AD-AH", "pbcdef-ad-ah", "nbcdef-ad-ah",
                    "+12345-03-07", "pbcde-ad-ah", "n-cacg-ad-ah"},
                {"HH:mm:ss.SSS", "aj:af:ae,bca", "aj:af:ae.bca", "09:05:04.120"},
                {"HH:mm:ss[.SSS]", "aj:af:ae,bca", "aj:af:ae.bca"},
                {"u", "cacg", "pcacg", "ncacg", "+cacg", "2026"},
                {"yy", "cg", "26"},
                {"MMMM d, uuuu", "March h, cacg", "March 7, 2026"},
                {"xxx", "+05:30", "pfa:da"},
                {"OOOO", "GMT+05:30", "GMTp05:30"},
                {"uuuuMMdd", "cacgadah", "20260307"},
            };
            for (String[] c : read) {
                DateTimeFormatter strict = DateTimeFormatter.ofPattern(c[0], Locale.US).withDecimalStyle(weird);
                DateTimeFormatter lenient = new DateTimeFormatterBuilder().parseLenient().appendPattern(c[0])
                        .toFormatter(Locale.US).withDecimalStyle(weird);
                DateTimeFormatter insensitive = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(c[0])
                        .toFormatter(Locale.US).withDecimalStyle(weird);
                for (int i = 1; i < c.length; i++) {
                    String text = c[i];
                    attempt(c[0] + " " + text, () -> strict.parseUnresolved(text, new ParsePosition(0)) + " | "
                            + lenient.parseUnresolved(text, new ParsePosition(0)) + " | "
                            + insensitive.parseUnresolved(text, new ParsePosition(0)));
                    attempt(c[0] + " " + text + " resolved", () -> strict.parse(text));
                }
            }
            DateTimeFormatter fraction = new DateTimeFormatterBuilder().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .toFormatter().withDecimalStyle(weird);
            for (String t : new String[] {",bc", ".bc", ",12", "", ","}) {
                attempt("fraction " + t, () -> fraction.parseUnresolved(t, new ParsePosition(0)));
            }
            DateTimeFormatter instant = DateTimeFormatter.ISO_INSTANT.withDecimalStyle(weird);
            attempt("instant", () -> instant.parse("2026-03-07T09:05:04.120Z"));
            attempt("instant weird", () -> instant.parse("cacg-ad-ahTaj:af:ae,bcaZ"));
            DateTimeFormatter ar = DateTimeFormatter.ofPattern("uuuu-MM-dd").withDecimalStyle(arabic);
            attempt("arabic", () -> ar.parse("٢٠٢٦-٠٣-٠٧"));
            attempt("arabic ascii", () -> ar.parse("2026-03-07"));
            DateTimeFormatter nine = DateTimeFormatter.ofPattern("uuuu-MM-dd").withDecimalStyle(s.withZeroDigit('9'));
            attempt("nine", () -> nine.format(LocalDate.of(2026, 3, 7)) + " " + nine.parse(nine.format(LocalDate.of(2026, 3, 7))));
            DateTimeFormatter same = new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR, 1, 10, SignStyle.ALWAYS)
                    .toFormatter().withDecimalStyle(s.withPositiveSign('-').withNegativeSign('-'));
            attempt("one sign", () -> same.format(LocalDate.of(2026, 3, 7)) + " "
                    + same.parseUnresolved("-2026", new ParsePosition(0)));
            DateTimeFormatter digit = new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR, 1, 10, SignStyle.ALWAYS)
                    .toFormatter().withDecimalStyle(s.withPositiveSign('1'));
            attempt("a digit's sign", () -> digit.format(LocalDate.of(2026, 3, 7)) + " "
                    + digit.parseUnresolved("12026", new ParsePosition(0)));
        }
    }

    // A reading from a position, the java.text classes and a formatter as a Format.
    static final class Positions {

        static final class Upper extends Format {

            public StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos) {
                return toAppendTo.append(String.valueOf(obj).toUpperCase());
            }

            public Object parseObject(String source, ParsePosition pos) {
                if (source.startsWith("ok", pos.getIndex())) {
                    pos.setIndex(pos.getIndex() + 2);
                    return "OK";
                }
                pos.setErrorIndex(pos.getIndex() + 1);
                return null;
            }
        }

        static final class Named extends Format.Field {

            Named(String name) {
                super(name);
            }
        }

        static final class Here extends ParsePosition {

            Here() {
                super(1);
            }
        }

        static void run() {
            ParsePosition p = new ParsePosition(3);
            System.out.println(p + " " + p.getIndex() + " " + p.getErrorIndex() + " " + p.hashCode());
            p.setErrorIndex(7);
            p.setIndex(-4);
            ParsePosition q = new ParsePosition(-4);
            System.out.println(p + " " + p.hashCode() + " " + p.equals(q) + " " + p.equals(null));
            q.setErrorIndex(7);
            System.out.println(p.equals(q) + " " + new Here() + " " + new Here().equals(new ParsePosition(1)));
            for (int i : new int[] {0, 1, 65535, 65536, -1, 123456}) {
                StringBuilder line = new StringBuilder("position " + i + ":");
                for (int e : new int[] {-1, 0, 1, 5, 65536}) {
                    ParsePosition x = new ParsePosition(i);
                    x.setErrorIndex(e);
                    line.append(' ').append(x.hashCode());
                }
                System.out.println(line);
            }
            Named era = new Named("era");
            System.out.println(new FieldPosition(0) + " " + new FieldPosition(era) + " " + new FieldPosition(era, 3) + " "
                    + new FieldPosition(null) + " " + era);
            System.out.println(new FieldPosition(era, 3).equals(new FieldPosition(era, 3)) + " "
                    + new FieldPosition(era, 3).equals(new FieldPosition(3)) + " "
                    + (new FieldPosition(era, 3).hashCode() == new FieldPosition(3).hashCode()));
            for (int f : new int[] {0, 1, 7, -3}) {
                StringBuilder line = new StringBuilder("field " + f + ":");
                for (int b : new int[] {0, 3, 100, -5}) {
                    for (int e : new int[] {0, 9, -1}) {
                        FieldPosition x = new FieldPosition(f);
                        x.setBeginIndex(b);
                        x.setEndIndex(e);
                        line.append(' ').append(x.hashCode());
                    }
                }
                System.out.println(line);
            }
            System.out.println(new ParseException("why", 5) + " " + new ParseException("why", 5).getErrorOffset());
            Format upper = new Upper();
            checked("upper", () -> upper.format("abc") + " " + upper.parseObject("ok"));
            checked("upper refused", () -> upper.parseObject("no"));
            DateTimeFormatter f = DateTimeFormatter.ofPattern("uuuu-MM-dd");
            Format format = f.toFormat();
            checked("class", () -> format.getClass().getName() + " " + format.equals(format) + " " + format.equals(f.toFormat()));
            checked("format", () -> format.format(LocalDate.of(2026, 3, 7)));
            checked("format a string", () -> format.format("x"));
            checked("format null", () -> format.format(null));
            checked("format a time", () -> format.format(LocalTime.NOON));
            FieldPosition at = new FieldPosition(0);
            at.setBeginIndex(3);
            at.setEndIndex(9);
            StringBuffer sb = new StringBuffer("pre:");
            checked("format to a buffer", () -> format.format(LocalDate.of(2026, 3, 7), sb, at) + " " + at);
            checked("no position", () -> format.format(LocalDate.of(2026, 3, 7), new StringBuffer(), null));
            checked("no buffer", () -> format.format(LocalDate.of(2026, 3, 7), null, new FieldPosition(0)));
            for (String text : new String[] {"2026-03-07", "2026-13-07", "2026-03", "2026-03-07xyz"}) {
                checked("parseObject " + text, () -> format.parseObject(text));
            }
            checked("parseObject null", () -> format.parseObject(null));
            String[] texts = {"abc 2026-03-07 tail", "abc 2026-13-07 tail", "abc 2026-03"};
            for (String text : texts) {
                for (int start : new int[] {4, 40, -1}) {
                    ParsePosition pos = new ParsePosition(start);
                    checked("parseObject at " + start + " " + text, () -> format.parseObject(text, pos) + " " + pos);
                    ParsePosition preset = new ParsePosition(start);
                    preset.setErrorIndex(5);
                    checked("parseObject preset at " + start + " " + text, () -> format.parseObject(text, preset) + " " + preset);
                }
            }
            checked("parseObject null text", () -> format.parseObject(null, new ParsePosition(0)));
            checked("parseObject null position", () -> format.parseObject("2026-03-07", null));
            Format dates = f.toFormat(LocalDate::from);
            Format times = f.toFormat(LocalTime::from);
            checked("query", () -> dates.format(LocalDate.of(2026, 3, 7)) + " " + dates.parseObject("2026-03-07") + " "
                    + dates.parseObject("2026-03-07").getClass().getName());
            checked("query refused", () -> dates.parseObject("2026-13-07"));
            checked("query of another", () -> times.parseObject("2026-03-07"));
            ParsePosition other = new ParsePosition(0);
            checked("query of another at", () -> times.parseObject("2026-03-07", other) + " " + other);
            checked("toFormat null", () -> f.toFormat(null));
            DateTimeFormatter offset = DateTimeFormatter.ofPattern("xxx");
            DateTimeFormatter localized = DateTimeFormatter.ofPattern("OOOO");
            checked("offset 25", () -> offset.toFormat().parseObject("+25:00"));
            checked("offset 25 queried", () -> offset.toFormat(ZoneOffset::from).parseObject("+25:00"));
            ParsePosition p25 = new ParsePosition(0);
            checked("offset 25 at", () -> offset.toFormat().parseObject("+25:00", p25) + " " + p25);
            checked("GMT+1", () -> localized.toFormat().parseObject("GMT+1"));
            ParsePosition gmt = new ParsePosition(0);
            checked("GMT+1 at", () -> localized.toFormat().parseObject("GMT+1", gmt) + " " + gmt);
            // a reading from a position, resolved or not
            String[] read = {"2026-03-07", "abc 2026-03-07 tail", "2026-13-07", "2026-02-30", "2026-03", "2026-03-07xyz", "",
                "x"};
            for (String t : read) {
                for (int start : new int[] {0, 4, 10, -1, 30}) {
                    ParsePosition pos = new ParsePosition(start);
                    attempt("unresolved '" + t + "' " + start, () -> f.parseUnresolved(t, pos) + " " + pos);
                    ParsePosition pos2 = new ParsePosition(start);
                    attempt("at '" + t + "' " + start, () -> f.parse(t, pos2) + " " + pos2);
                }
            }
            attempt("unresolved null text", () -> f.parseUnresolved(null, new ParsePosition(0)));
            attempt("unresolved null position", () -> f.parseUnresolved("2026", null));
            attempt("at null text", () -> f.parse(null, new ParsePosition(0)));
            attempt("at null position", () -> f.parse("2026", (ParsePosition) null));
            ParsePosition preset = new ParsePosition(0);
            preset.setErrorIndex(5);
            attempt("unresolved preset", () -> f.parseUnresolved("2026-03-07", preset) + " " + preset);
            ParsePosition preset2 = new ParsePosition(0);
            preset2.setErrorIndex(5);
            attempt("at preset", () -> f.parse("2026-03-07", preset2) + " " + preset2);
            ParsePosition preset3 = new ParsePosition(0);
            preset3.setErrorIndex(3);
            attempt("at preset short", () -> f.parse("2026-03", preset3) + " " + preset3);
            TemporalAccessor u = f.parseUnresolved("2026-03-07", new ParsePosition(0));
            System.out.println(u.getClass().getName() + " " + u.isSupported(ChronoField.YEAR) + " "
                    + u.isSupported(ChronoField.EPOCH_DAY) + " " + u.getLong(ChronoField.YEAR) + " "
                    + u.query(TemporalQueries.localDate()) + " " + u.query(TemporalQueries.chronology()) + " "
                    + u.query(TemporalQueries.zoneId()) + " " + u.query(TemporalQueries.precision()) + " "
                    + u.query(DateTimeFormatter.parsedExcessDays()) + " " + u.query(DateTimeFormatter.parsedLeapSecond()));
            attempt("unresolved get", () -> u.get(ChronoField.MONTH_OF_YEAR) + " " + u.range(ChronoField.YEAR));
            attempt("unresolved getLong", () -> u.getLong(ChronoField.EPOCH_DAY));
            attempt("unresolved from", () -> LocalDate.from(u));
            DateTimeFormatter z = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV");
            attempt("zoned", () -> z.parseUnresolved("2026-03-07 10:00 Europe/Paris", new ParsePosition(0)) + " | "
                    + z.parse("2026-03-07 10:00 Europe/Paris", new ParsePosition(0)));
            attempt("zoned offset", () -> z.parseUnresolved("2026-03-07 10:00 +01:00", new ParsePosition(0)));
            DateTimeFormatter zt = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm zzzz", Locale.US);
            attempt("zone text", () -> zt.parseUnresolved("2026-03-07 10:00 Central European Standard Time",
                    new ParsePosition(0)) + " | " + zt.parse("2026-03-07 10:00 Central European Standard Time",
                            new ParsePosition(0)));
            attempt("leap", () -> DateTimeFormatter.ISO_INSTANT.parseUnresolved("2026-06-30T23:59:60Z", new ParsePosition(0))
                    + " " + DateTimeFormatter.ISO_INSTANT.parseUnresolved("2026-06-30T23:59:60Z", new ParsePosition(0))
                            .query(DateTimeFormatter.parsedLeapSecond()));
            DateTimeFormatter b = DateTimeFormatter.ofPattern("h B", Locale.US);
            attempt("period", () -> b.parseUnresolved("3 in the afternoon", new ParsePosition(0)) + " | "
                    + b.parse("3 in the afternoon", new ParsePosition(0)));
            DateTimeFormatter o = DateTimeFormatter.ofPattern("HH:mm[ xxx]").withZone(ZoneId.of("Asia/Tokyo"));
            attempt("override", () -> o.parseUnresolved("10:00", new ParsePosition(0)) + " | "
                    + o.parse("10:00", new ParsePosition(0)));
            DateTimeFormatter twice = DateTimeFormatter.ofPattern("uuuu uuuu");
            ParsePosition tp = new ParsePosition(0);
            attempt("twice", () -> twice.parseUnresolved("2026 2027", tp) + " " + tp);
            attempt("twice at", () -> twice.parse("2026 2027", new ParsePosition(0)));
            ParsePosition op = new ParsePosition(0);
            attempt("offset 25 unresolved", () -> offset.parseUnresolved("+25:00", op) + " " + op);
            attempt("offset 25 at", () -> offset.parse("+25:00", new ParsePosition(0)));
            String longText = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz ";
            attempt("long text at", () -> f.parse(longText + "2026-13-07", new ParsePosition(79)));
            attempt("long text short at", () -> f.parse(longText + "2026-1", new ParsePosition(79)));
            // the index error of a full localized offset: thrown as it is by a reading from a
            // position, wrapped by the others
            attempt("GMT+1 parse", () -> localized.parse("GMT+1"));
            attempt("GMT+1 at", () -> localized.parse("GMT+1", new ParsePosition(0)));
            attempt("GMT+1 query", () -> localized.parse("GMT+1", ZoneOffset::from));
            attempt("GMT+1 best", () -> localized.parseBest("GMT+1", ZoneOffset::from, ZoneId::from));
            attempt("GMT+1 unresolved", () -> localized.parseUnresolved("GMT+1", new ParsePosition(0)));
            attempt("GMT+1 OffsetTime", () -> OffsetTime.parse("GMT+1", localized));
            attempt("best of none", () -> f.parseBest("2026-03-07", LocalTime::from, Instant::from));
            attempt("best of a null", () -> f.parseBest("2026-03-07", null, LocalDate::from));
        }
    }

    // The fields a formatter resolves.
    static final class Resolving {

        static void run() {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("uuuu-MM-dd D HH:mm");
            System.out.println(f.getResolverFields());
            DateTimeFormatter a = f.withResolverFields(ChronoField.YEAR, ChronoField.DAY_OF_YEAR);
            System.out.println(new TreeSet<>(a.getResolverFields().stream().map(String::valueOf).toList()) + " " + a);
            attempt("unmodifiable", () -> a.getResolverFields().add(ChronoField.ERA));
            attempt("a year and its day", () -> a.parse("2026-03-07 066 10:30") + " " + a.parse("2026-03-07 067 10:30"));
            attempt("a date of them", () -> LocalDate.parse("2026-03-07 067 10:30", a));
            attempt("a date-time of them", () -> LocalDateTime.parse("2026-03-07 067 10:30", a));
            attempt("the date", () -> f.withResolverFields(ChronoField.YEAR, ChronoField.MONTH_OF_YEAR,
                    ChronoField.DAY_OF_MONTH).parse("2026-03-07 067 10:30"));
            attempt("the date and time", () -> f.withResolverFields(ChronoField.YEAR, ChronoField.MONTH_OF_YEAR,
                    ChronoField.DAY_OF_MONTH, ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR).parse("2026-03-07 067 10:30"));
            DateTimeFormatter none = f.withResolverFields();
            attempt("none", () -> none.parse("2026-03-07 067 10:30") + " " + none.getResolverFields());
            attempt("null fields", () -> f.withResolverFields((TemporalField[]) null).getResolverFields() + " "
                    + (f.withResolverFields((TemporalField[]) null) == f) + " "
                    + (f.withResolverFields((Set<TemporalField>) null) == f));
            attempt("a null field", () -> f.withResolverFields(ChronoField.YEAR, null).getResolverFields().contains(null));
            Set<TemporalField> withNull = new HashSet<>();
            withNull.add(null);
            attempt("a set of a null", () -> f.withResolverFields(withNull).getResolverFields());
            System.out.println((a.withResolverFields(ChronoField.DAY_OF_YEAR, ChronoField.YEAR) == a) + " "
                    + (a.withResolverFields(new HashSet<>(List.of(ChronoField.DAY_OF_YEAR, ChronoField.YEAR))) == a) + " "
                    + (f.withResolverFields(Set.of()) == f) + " " + (none.withResolverFields() == none) + " "
                    + (a.withResolverFields((Set<TemporalField>) null) == a));
            DateTimeFormatter epoch = new DateTimeFormatterBuilder().appendValue(ChronoField.EPOCH_DAY).toFormatter();
            attempt("epoch day", () -> epoch.withResolverFields(ChronoField.YEAR).parse("20000") + " "
                    + epoch.withResolverFields(ChronoField.EPOCH_DAY).parse("20000"));
            DateTimeFormatter clock = DateTimeFormatter.ofPattern("h a", Locale.US);
            attempt("hours", () -> clock.withResolverFields(ChronoField.HOUR_OF_DAY).parse("3 PM") + " | "
                    + clock.withResolverFields(ChronoField.CLOCK_HOUR_OF_AMPM, ChronoField.AMPM_OF_DAY).parse("3 PM") + " | "
                    + clock.withResolverFields(ChronoField.CLOCK_HOUR_OF_AMPM).parse("3 PM"));
            DateTimeFormatter zoned = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm xxx");
            attempt("offset", () -> zoned.withResolverFields(ChronoField.YEAR, ChronoField.MONTH_OF_YEAR,
                    ChronoField.DAY_OF_MONTH, ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR).parse("2026-03-07 10:30 +01:00")
                    + " | " + zoned.withResolverFields(ChronoField.YEAR, ChronoField.MONTH_OF_YEAR, ChronoField.DAY_OF_MONTH,
                            ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR, ChronoField.OFFSET_SECONDS)
                            .parse("2026-03-07 10:30 +01:00"));
            attempt("zone", () -> DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV").withResolverFields(ChronoField.YEAR)
                    .parse("2026-03-07 10:30 Europe/Paris"));
            attempt("weeks", () -> DateTimeFormatter.ISO_WEEK_DATE.withResolverFields(IsoFields.WEEK_BASED_YEAR,
                    IsoFields.WEEK_OF_WEEK_BASED_YEAR, ChronoField.DAY_OF_WEEK).parse("2026-W10-6") + " | "
                    + DateTimeFormatter.ISO_WEEK_DATE.withResolverFields(IsoFields.WEEK_BASED_YEAR,
                            IsoFields.WEEK_OF_WEEK_BASED_YEAR).parse("2026-W10-6"));
            DateTimeFormatter all = a.withZone(ZoneOffset.UTC).withDecimalStyle(DecimalStyle.STANDARD.withZeroDigit('a'))
                    .withResolverStyle(ResolverStyle.LENIENT);
            System.out.println(new TreeSet<>(all.withLocale(Locale.UK).getResolverFields().stream().map(String::valueOf).toList())
                    + " " + new TreeSet<>(all.localizedBy(Locale.UK).getResolverFields().stream().map(String::valueOf).toList())
                    + " " + all.withResolverFields(ChronoField.YEAR).getZone() + " "
                    + all.withResolverFields(ChronoField.YEAR).getDecimalStyle() + " "
                    + all.withResolverFields(ChronoField.YEAR).getResolverStyle());
        }
    }

    // The date fields over a map, ISO's calendar and one of the program's own.
    static final class Calendars {

        static final class Thin extends AbstractChronology {

            public String getId() {
                return "Thin";
            }

            public String getCalendarType() {
                return "thin";
            }

            public ChronoLocalDate date(int prolepticYear, int month, int dayOfMonth) {
                return LocalDate.of(prolepticYear, month, dayOfMonth);
            }

            public ChronoLocalDate dateYearDay(int prolepticYear, int dayOfYear) {
                return LocalDate.ofYearDay(prolepticYear, dayOfYear);
            }

            public ChronoLocalDate dateEpochDay(long epochDay) {
                return LocalDate.ofEpochDay(epochDay);
            }

            public ChronoLocalDate date(TemporalAccessor temporal) {
                return LocalDate.from(temporal);
            }

            public boolean isLeapYear(long prolepticYear) {
                return Year.isLeap(prolepticYear);
            }

            public int prolepticYear(Era era, int yearOfEra) {
                return era == IsoEra.CE ? yearOfEra : 1 - yearOfEra;
            }

            public Era eraOf(int eraValue) {
                return IsoEra.of(eraValue);
            }

            public List<Era> eras() {
                return List.of(IsoEra.BCE, IsoEra.CE);
            }

            public ValueRange range(ChronoField field) {
                return field.range();
            }
        }

        static String sorted(Map<TemporalField, Long> map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<TemporalField, Long> e : map.entrySet()) {
                parts.add(e.getKey() + "=" + e.getValue());
            }
            Collections.sort(parts);
            return "{" + String.join(", ", parts) + "}";
        }

        static String resolve(Chronology chronology, ResolverStyle style, Object... pairs) {
            Map<TemporalField, Long> map = new HashMap<>();
            for (int i = 0; i < pairs.length; i += 2) {
                map.put((TemporalField) pairs[i], ((Number) pairs[i + 1]).longValue());
            }
            try {
                return chronology.resolveDate(map, style) + " " + sorted(map);
            }
            catch (RuntimeException e) {
                return e + " " + sorted(map);
            }
        }

        static final Object[][] CASES = {
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 2, ChronoField.DAY_OF_MONTH, 30},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 13, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 0},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7, ChronoField.DAY_OF_WEEK, 1},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7, ChronoField.HOUR_OF_DAY, 10},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3},
            {ChronoField.YEAR, 2026},
            {},
            {ChronoField.EPOCH_DAY, 20000},
            {ChronoField.EPOCH_DAY, 20000, ChronoField.YEAR, 2020},
            {ChronoField.EPOCH_DAY, 20000, ChronoField.YEAR_OF_ERA, 0},
            {ChronoField.PROLEPTIC_MONTH, 24314, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.PROLEPTIC_MONTH, -144000000088L},
            {ChronoField.PROLEPTIC_MONTH, 24314, ChronoField.YEAR, 2020},
            {ChronoField.YEAR_OF_ERA, 2026, ChronoField.ERA, 1, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR_OF_ERA, 2026, ChronoField.ERA, 0, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR_OF_ERA, 2026, ChronoField.ERA, 2},
            {ChronoField.YEAR_OF_ERA, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR_OF_ERA, 0, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR_OF_ERA, 2026, ChronoField.YEAR, -5, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR_OF_ERA, 2026, ChronoField.YEAR, 2026},
            {ChronoField.YEAR_OF_ERA, 2026, ChronoField.YEAR, -2025},
            {ChronoField.YEAR_OF_ERA, 5, ChronoField.YEAR, 2000000000L},
            {ChronoField.ERA, 7},
            {ChronoField.ERA, 1},
            {ChronoField.YEAR, 2026, ChronoField.DAY_OF_YEAR, 66},
            {ChronoField.YEAR, 2026, ChronoField.DAY_OF_YEAR, 366},
            {ChronoField.YEAR, 2026, ChronoField.DAY_OF_YEAR, 400},
            {ChronoField.YEAR, 2026, ChronoField.DAY_OF_YEAR, 0},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.ALIGNED_WEEK_OF_MONTH, 2,
                ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH, 3},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.ALIGNED_WEEK_OF_MONTH, 5,
                ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH, 7},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.ALIGNED_WEEK_OF_MONTH, 5,
                ChronoField.DAY_OF_WEEK, 3},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.ALIGNED_WEEK_OF_MONTH, 9,
                ChronoField.DAY_OF_WEEK, 3},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.ALIGNED_WEEK_OF_MONTH, 2},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 10, ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR, 3},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 53, ChronoField.DAY_OF_WEEK, 7},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 53, ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR, 7},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 0, ChronoField.DAY_OF_WEEK, 3},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 1, ChronoField.DAY_OF_WEEK, -6},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 1, ChronoField.DAY_OF_WEEK, 15},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 1, ChronoField.DAY_OF_WEEK, Long.MAX_VALUE},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 1, ChronoField.DAY_OF_WEEK, Long.MIN_VALUE},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, Long.MIN_VALUE, ChronoField.DAY_OF_WEEK, 3},
            {ChronoField.YEAR, 2026, ChronoField.ALIGNED_WEEK_OF_YEAR, 3, ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR, -9},
            {ChronoField.YEAR, 2000000000L, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, 7},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, 3, ChronoField.DAY_OF_MONTH, Long.MAX_VALUE},
            {ChronoField.YEAR, 2026, ChronoField.MONTH_OF_YEAR, Long.MIN_VALUE, ChronoField.DAY_OF_MONTH, 1},
            {IsoFields.QUARTER_OF_YEAR, 1, ChronoField.YEAR, 2026, IsoFields.DAY_OF_QUARTER, 66},
        };

        static void run() {
            Thin thin = new Thin();
            for (ResolverStyle style : ResolverStyle.values()) {
                for (Object[] c : CASES) {
                    System.out.println(style + " iso " + resolve(IsoChronology.INSTANCE, style, c) + " | thin "
                            + resolve(thin, style, c));
                }
            }
            attempt("null map", () -> IsoChronology.INSTANCE.resolveDate(null, ResolverStyle.SMART));
            attempt("null style", () -> IsoChronology.INSTANCE.resolveDate(new HashMap<>(Map.of(ChronoField.YEAR, 2026L)), null)
                    + " " + IsoChronology.INSTANCE.resolveDate(new HashMap<>(Map.of(ChronoField.YEAR, 2026L,
                            ChronoField.MONTH_OF_YEAR, 3L, ChronoField.DAY_OF_MONTH, 7L)), null));
            attempt("an immutable map", () -> IsoChronology.INSTANCE.resolveDate(Map.of(ChronoField.YEAR, 2026L,
                    ChronoField.MONTH_OF_YEAR, 3L, ChronoField.DAY_OF_MONTH, 7L), ResolverStyle.SMART));
            // leniently, a day of the week in an aligned week: the rule over a grid, through the
            // map and through a formatter; a hash of each line
            DateTimeFormatter year = new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ')
                    .appendValue(ChronoField.ALIGNED_WEEK_OF_YEAR).appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK)
                    .toFormatter().withResolverStyle(ResolverStyle.LENIENT);
            DateTimeFormatter month = new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR).appendLiteral(' ')
                    .appendValue(ChronoField.MONTH_OF_YEAR).appendLiteral(' ').appendValue(ChronoField.ALIGNED_WEEK_OF_MONTH)
                    .appendLiteral(' ').appendValue(ChronoField.DAY_OF_WEEK).toFormatter().withResolverStyle(ResolverStyle.LENIENT);
            for (int y : new int[] {2026, 2024, 2023, 2000, 1999, -5}) {
                StringBuilder map = new StringBuilder();
                StringBuilder text = new StringBuilder();
                for (int m = -13; m <= 26; m += 3) {
                    for (long w = -60; w <= 60; w += 7) {
                        for (long d = -30; d <= 30; d++) {
                            map.append(resolve(IsoChronology.INSTANCE, ResolverStyle.LENIENT, ChronoField.YEAR, y,
                                    ChronoField.MONTH_OF_YEAR, m, ChronoField.ALIGNED_WEEK_OF_MONTH, w, ChronoField.DAY_OF_WEEK, d));
                        }
                    }
                }
                for (long w = -60; w <= 60; w++) {
                    for (long d = -30; d <= 30; d++) {
                        map.append(resolve(IsoChronology.INSTANCE, ResolverStyle.LENIENT, ChronoField.YEAR, y,
                                ChronoField.ALIGNED_WEEK_OF_YEAR, w, ChronoField.DAY_OF_WEEK, d));
                    }
                }
                for (int w = -2; w <= 55; w += 3) {
                    for (int d = -9; d <= 16; d++) {
                        String t = y + " " + w + " " + d;
                        try {
                            text.append(LocalDate.parse(t, year));
                        }
                        catch (DateTimeException e) {
                            text.append(e.getMessage());
                        }
                    }
                }
                for (int m = -1; m <= 14; m += 5) {
                    for (int w = -1; w <= 6; w += 2) {
                        for (int d = -7; d <= 9; d += 2) {
                            String t = y + " " + m + " " + w + " " + d;
                            try {
                                text.append(LocalDate.parse(t, month));
                            }
                            catch (DateTimeException e) {
                                text.append(e.getMessage());
                            }
                        }
                    }
                }
                System.out.println("lenient weeks " + y + ": " + map.toString().hashCode() + " " + text.toString().hashCode());
            }
            attempt("a week's day before", () -> LocalDate.parse("2026 53 7", year) + " " + LocalDate.parse("2026 0 3", year)
                    + " " + LocalDate.parse("2026 3 5 3", month));
            attempt("a week's day before refused", () -> LocalDate.parse("2026 1 -6", year));
            // a calendar of the program's own
            attempt("epochSecond", () -> thin.epochSecond(2026, 3, 7, 10, 15, 30, ZoneOffset.ofHours(1)));
            attempt("epochSecond hour", () -> thin.epochSecond(2026, 13, 7, 24, 15, 30, ZoneOffset.UTC));
            attempt("epochSecond minute", () -> thin.epochSecond(2026, 13, 7, 10, 60, 30, ZoneOffset.UTC));
            attempt("epochSecond second", () -> thin.epochSecond(2026, 13, 7, 10, 15, 60, ZoneOffset.UTC));
            attempt("epochSecond date", () -> thin.epochSecond(2026, 13, 7, 10, 15, 30, ZoneOffset.UTC));
            attempt("epochSecond null offset", () -> thin.epochSecond(2026, 3, 7, 10, 15, 30, null));
            attempt("epochSecond era", () -> thin.epochSecond(IsoEra.BCE, 5, 3, 7, 10, 15, 30, ZoneOffset.UTC));
            attempt("ISO's epochSecond", () -> IsoChronology.INSTANCE.epochSecond(2026, 13, 7, 24, 15, 30, ZoneOffset.UTC));
            attempt("date of an era", () -> thin.date(IsoEra.BCE, 5, 3, 7) + " " + thin.dateYearDay(IsoEra.CE, 2026, 66));
            attempt("dateNow", () -> thin.dateNow(Clock.fixed(Instant.parse("2026-03-07T10:00:00Z"), ZoneOffset.UTC)));
            attempt("dateNow null clock", () -> thin.dateNow((Clock) null));
            attempt("dateNow null zone", () -> thin.dateNow((ZoneId) null));
            attempt("names", () -> thin.getDisplayName(TextStyle.FULL, Locale.US) + " " + thin.getDisplayName(TextStyle.SHORT,
                    Locale.ROOT) + " " + IsoChronology.INSTANCE.getDisplayName(TextStyle.FULL, Locale.US) + " "
                    + thin.isIsoBased() + " " + thin + " " + thin.equals(new Thin()) + " " + thin.equals(IsoChronology.INSTANCE)
                    + " " + IsoChronology.INSTANCE.equals(thin) + " " + thin.compareTo(IsoChronology.INSTANCE) + " "
                    + IsoChronology.INSTANCE.compareTo(thin) + " " + (thin.hashCode() == new Thin().hashCode()));
            attempt("not a calendar a program knows by name", () -> Chronology.of("Thin"));
            attempt("of no list", () -> Chronology.getAvailableChronologies().contains(thin));
            attempt("ISO's is one", () -> (IsoChronology.INSTANCE instanceof AbstractChronology) + " "
                    + IsoChronology.INSTANCE.getClass().getSuperclass().getName());
            attempt("a formatter's", () -> DateTimeFormatter.ofPattern("uuuu-MM-dd").withChronology(thin).getChronology());
        }
    }

    // Where k43 and k52 did not follow a JDK, and what they did not ask.
    static final class Left {

        static String read(DateTimeFormatter f, String text, TemporalField field) {
            ParsePosition p = new ParsePosition(0);
            try {
                TemporalAccessor a = f.parseUnresolved(text, p);
                return a == null ? "x" + p.getErrorIndex() : a.getLong(field) + "@" + p.getIndex();
            }
            catch (RuntimeException e) {
                return e.toString();
            }
        }

        static void run() {
            // an era leniently
            String[] eras = {"CE", "BCE", "AD", "BC", "Anno Domini", "Before Christ", "A", "B", "C", "ce", "bce",
                "Common Era", "Before Common Era", "CEx", "B.C.", "Ce", "BCe", "ANNO DOMINI", "anno domini"};
            for (Locale l : new Locale[] {Locale.ROOT, Locale.US, Locale.UK, Locale.CANADA, Locale.ENGLISH}) {
                for (String style : new String[] {"G", "GGGG", "GGGGG"}) {
                    for (int mode = 0; mode < 3; mode++) {
                        DateTimeFormatterBuilder b = new DateTimeFormatterBuilder();
                        if (mode > 0) {
                            b.parseLenient();
                        }
                        if (mode > 1) {
                            b.parseCaseInsensitive();
                        }
                        DateTimeFormatter f = b.appendPattern(style).toFormatter(l);
                        StringBuilder line = new StringBuilder(l.toLanguageTag() + " " + style + " " + mode + ":");
                        for (String w : eras) {
                            line.append(" [").append(w).append("]=").append(read(f, w, ChronoField.ERA));
                        }
                        System.out.println(line);
                    }
                }
            }
            // an offset as GMT and its hours, read
            String[] texts = {"GMT", "GMT+1", "GMT+01", "GMT+1:", "GMT+01:", "GMT+01:0", "GMT+01:00", "GMT+01:00:",
                "GMT+01:00:0", "GMT+01:00:00", "GMT+1:00", "GMT+010", "GMT+0100", "GMT-", "GMT+", "GMT+x", "GMT+1x",
                "GMT+01x", "GMT+01:x", "GMT+01:0x", "GMT+01:00x", "GMT+01:00:x", "GMT+25:00", "GMT+01:60", "GMT+01:00:60",
                "GMT+1:5", "GMT+123", "GMT+12:3", "GMT+12:345", "GMT+12:34:5", "gmt+01:00", "Gmt+1", "GMT 01:00"};
            for (String style : new String[] {"OOOO", "O", "ZZZZ"}) {
                for (int mode = 0; mode < 3; mode++) {
                    DateTimeFormatterBuilder b = new DateTimeFormatterBuilder();
                    if (mode == 1) {
                        b.parseLenient();
                    }
                    if (mode == 2) {
                        b.parseCaseInsensitive();
                    }
                    DateTimeFormatter f = b.appendPattern(style).toFormatter(Locale.US);
                    for (String t : texts) {
                        attempt(style + " " + mode + " [" + t + "]", () -> read(f, t, ChronoField.OFFSET_SECONDS) + " | "
                                + f.parse(t).getLong(ChronoField.OFFSET_SECONDS));
                    }
                }
            }
            DateTimeFormatter pad = new DateTimeFormatterBuilder().padNext(8).appendPattern("OOOO").appendLiteral("|")
                    .toFormatter(Locale.US);
            DateTimeFormatter tail = new DateTimeFormatterBuilder().appendPattern("OOOO").appendLiteral("|")
                    .toFormatter(Locale.US);
            DateTimeFormatter optional = new DateTimeFormatterBuilder().optionalStart().appendPattern("OOOO").optionalEnd()
                    .toFormatter(Locale.US);
            for (String t : new String[] {"   GMT+0|", "  GMT+01|", "GMT+01:00|", "GMT+1|", "GMT+01:|", "GMT+01:0|", "GMT+0",
                "GMT+01:"}) {
                System.out.println("[" + t + "] " + read(pad, t, ChronoField.OFFSET_SECONDS) + " "
                        + read(tail, t, ChronoField.OFFSET_SECONDS) + " " + read(optional, t, ChronoField.OFFSET_SECONDS));
            }
            // a fraction of no fixed width right after a run of numbers
            for (boolean lenient : new boolean[] {false, true}) {
                DateTimeFormatter[] formatters = new DateTimeFormatter[7];
                for (int i = 0; i < formatters.length; i++) {
                    DateTimeFormatterBuilder b = new DateTimeFormatterBuilder();
                    if (lenient) {
                        b.parseLenient();
                    }
                    switch (i) {
                        case 0 -> b.appendValue(ChronoField.HOUR_OF_DAY).appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, false);
                        case 1 -> b.appendValue(ChronoField.HOUR_OF_DAY).appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                                .appendValue(ChronoField.SECOND_OF_MINUTE);
                        case 2 -> b.appendValue(ChronoField.HOUR_OF_DAY).appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true);
                        case 3 -> b.appendValue(ChronoField.HOUR_OF_DAY).appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                                .appendFraction(ChronoField.NANO_OF_SECOND, 1, 3, false);
                        case 4 -> b.appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE)
                                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, false);
                        case 5 -> b.appendValue(ChronoField.HOUR_OF_DAY, 2).appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, false);
                        default -> b.appendValue(ChronoField.HOUR_OF_DAY).appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, false).appendValue(ChronoField.SECOND_OF_MINUTE, 2);
                    }
                    formatters[i] = b.toFormatter();
                }
                for (String t : new String[] {"1234", "12345", "123456", "1234567", "1", "12", "123", "1234567890123", "0930"}) {
                    StringBuilder line = new StringBuilder((lenient ? "lenient " : "") + "run [" + t + "]:");
                    for (DateTimeFormatter f : formatters) {
                        ParsePosition p = new ParsePosition(0);
                        TemporalAccessor a = f.parseUnresolved(t, p);
                        line.append(' ').append(a == null ? "x" + p.getErrorIndex() : shown(a) + "@" + p.getIndex());
                    }
                    System.out.println(line);
                }
            }
            // the null refusals
            DateTimeFormatter f = DateTimeFormatter.ofPattern("uuuu-MM-dd");
            DateTimeFormatterBuilder b = new DateTimeFormatterBuilder();
            attempt("ofPattern", () -> DateTimeFormatter.ofPattern(null));
            attempt("ofPattern locale", () -> DateTimeFormatter.ofPattern("uuuu", null));
            attempt("withLocale", () -> f.withLocale(null));
            attempt("withChronology withZone", () -> f.withChronology(null).getChronology() + " " + f.withZone(null).getZone());
            attempt("withResolverStyle", () -> f.withResolverStyle(null));
            attempt("format", () -> f.format(null));
            attempt("formatTo", () -> {
                f.formatTo(null, null);
                return "";
            });
            attempt("formatTo appendable", () -> {
                f.formatTo(LocalDate.of(2026, 3, 7), null);
                return "";
            });
            attempt("parse", () -> f.parse((CharSequence) null));
            attempt("parse query", () -> f.parse("2026-03-07", (TemporalQuery<Object>) null));
            attempt("parseBest", () -> f.parseBest(null, LocalDate::from, YearMonth::from));
            attempt("parseBest queries", () -> f.parseBest("2026-03-07", (TemporalQuery<?>[]) null));
            attempt("appendValue", () -> b.appendValue(null));
            attempt("appendValue width", () -> b.appendValue(null, 2));
            attempt("appendValue range", () -> b.appendValue(null, 1, 2, SignStyle.NORMAL));
            attempt("appendValue sign", () -> b.appendValue(ChronoField.YEAR, 1, 2, null));
            attempt("appendValueReduced", () -> b.appendValueReduced(null, 2, 2, 2000));
            attempt("appendValueReduced date", () -> b.appendValueReduced(ChronoField.YEAR, 2, 2, null));
            attempt("appendFraction", () -> b.appendFraction(null, 0, 9, true));
            attempt("appendText", () -> b.appendText(null));
            attempt("appendText style", () -> b.appendText(ChronoField.YEAR, (TextStyle) null));
            attempt("appendText map", () -> b.appendText(ChronoField.YEAR, (Map<Long, String>) null));
            attempt("appendOffset", () -> b.appendOffset(null, "Z"));
            attempt("appendOffset none", () -> b.appendOffset("+HH", null));
            attempt("appendLocalizedOffset", () -> b.appendLocalizedOffset(null));
            attempt("appendZoneText", () -> b.appendZoneText(null));
            attempt("appendZoneText preferred", () -> b.appendZoneText(TextStyle.FULL, null));
            attempt("appendGenericZoneText", () -> b.appendGenericZoneText(null));
            attempt("appendGenericZoneText preferred", () -> b.appendGenericZoneText(TextStyle.FULL, null) == b);
            attempt("appendDayPeriodText", () -> b.appendDayPeriodText(null));
            attempt("appendLiteral", () -> b.appendLiteral(null));
            attempt("append", () -> b.append(null));
            attempt("appendOptional", () -> b.appendOptional(null));
            attempt("appendPattern", () -> b.appendPattern(null));
            attempt("parseDefaulting", () -> b.parseDefaulting(null, 1));
            attempt("toFormatter", () -> new DateTimeFormatterBuilder().toFormatter(null));
            attempt("TemporalAdjusters", () -> TemporalAdjusters.firstInMonth(null));
            attempt("next", () -> TemporalAdjusters.next(null));
            attempt("nextOrSame", () -> TemporalAdjusters.nextOrSame(null));
            attempt("previous", () -> TemporalAdjusters.previous(null));
            attempt("previousOrSame", () -> TemporalAdjusters.previousOrSame(null));
            attempt("WeekFields.of", () -> WeekFields.of(null, 1));
            attempt("WeekFields.of locale", () -> WeekFields.of((Locale) null));
            List<Supplier<Object>> froms = List.of(() -> LocalDate.from(null), () -> LocalTime.from(null),
                    () -> LocalDateTime.from(null), () -> ZonedDateTime.from(null), () -> OffsetDateTime.from(null),
                    () -> Instant.from(null), () -> ZoneOffset.from(null), () -> ZoneId.from(null), () -> Year.from(null),
                    () -> YearMonth.from(null), () -> MonthDay.from(null), () -> OffsetTime.from(null),
                    () -> DayOfWeek.from(null), () -> Month.from(null));
            for (Supplier<Object> from : froms) {
                attempt("from", from);
            }
            Chronology iso = IsoChronology.INSTANCE;
            attempt("date", () -> iso.date((TemporalAccessor) null));
            attempt("localDateTime", () -> iso.localDateTime(null));
            attempt("zonedDateTime", () -> iso.zonedDateTime(null));
            attempt("zonedDateTime instant", () -> iso.zonedDateTime(null, ZoneOffset.UTC));
            attempt("zonedDateTime zone", () -> iso.zonedDateTime(Instant.EPOCH, null));
            attempt("range", () -> iso.range(null));
            attempt("epochSecond", () -> iso.epochSecond(null, 2026, 1, 1, 0, 0, 0, ZoneOffset.UTC));
            attempt("compareTo", () -> iso.compareTo(null));
            attempt("getDisplayName", () -> iso.getDisplayName(null, Locale.US));
            attempt("getDisplayName locale", () -> iso.getDisplayName(TextStyle.FULL, null));
            attempt("period", () -> iso.period(1, 2, 3));
            // two clocks
            Instant i = Instant.parse("2026-03-07T10:15:30.123456789Z");
            ZoneId utc = ZoneOffset.UTC;
            Clock[] clocks = {Clock.systemUTC(), Clock.systemUTC(), Clock.system(utc), Clock.system(ZoneId.of("UTC")),
                Clock.system(PARIS), Clock.system(ZoneOffset.ofHours(9)), Clock.fixed(i, utc), Clock.fixed(i, utc),
                Clock.fixed(i, PARIS), Clock.fixed(Instant.EPOCH, utc), Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")),
                Clock.offset(Clock.systemUTC(), Duration.ofHours(1)), Clock.offset(Clock.systemUTC(), Duration.ofHours(1)),
                Clock.offset(Clock.fixed(i, utc), Duration.ofSeconds(1)), Clock.offset(Clock.systemUTC(), Duration.ZERO),
                Clock.tick(Clock.systemUTC(), Duration.ofSeconds(1)), Clock.tick(Clock.systemUTC(), Duration.ofMillis(1)),
                Clock.tickSeconds(utc), Clock.tickMinutes(utc), Clock.tickMillis(utc), Clock.tickMillis(PARIS),
                Clock.tick(Clock.systemUTC(), Duration.ofNanos(1)), Clock.systemUTC().withZone(PARIS),
                Clock.fixed(i, utc).withZone(PARIS), Clock.offset(Clock.systemUTC(), Duration.ofHours(1)).withZone(PARIS),
                Clock.tickSeconds(utc).withZone(PARIS)};
            for (int a = 0; a < clocks.length; a++) {
                StringBuilder line = new StringBuilder(a + " " + clocks[a] + " " + clocks[a].hashCode() + ":");
                for (int c = 0; c < clocks.length; c++) {
                    if (clocks[a].equals(clocks[c])) {
                        line.append(' ').append(c);
                    }
                }
                System.out.println(line);
            }
            InstantSource system = InstantSource.system();
            System.out.println(system + " " + system.equals(InstantSource.system()) + " " + system.equals(Clock.systemUTC())
                    + " " + Clock.systemUTC().equals(system) + " " + InstantSource.fixed(i) + " "
                    + InstantSource.fixed(i).hashCode() + " " + InstantSource.fixed(i).equals(Clock.fixed(i, utc)) + " "
                    + InstantSource.tick(system, Duration.ofSeconds(1)).hashCode() + " "
                    + InstantSource.offset(system, Duration.ofSeconds(1)).hashCode());
        }
    }
}
