import java.text.ParseException;
import java.text.ParsePosition;
import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

// The localized pattern of a requested template, asked of a JDK 25: the patterns of the
// templates of a bounded grid and of seeded random ones in the five locales Locale has,
// the refusals, the formatters of a template written and read, and a calendar of the
// probe's own.
public class Grid {

    static final Locale[] LOCALES = {Locale.ROOT, Locale.US, Locale.UK, Locale.CANADA, Locale.ENGLISH};

    // the narrow no-break space as a mark, and the fields of a map a JDK prints in an order
    // of its run sorted
    static String shown(Object o) {
        String text = String.valueOf(o).replace('\u202f', '_');
        int open = text.indexOf('{');
        int close = text.indexOf('}', open + 1);
        if (open < 0 || close < 0) {
            return text;
        }
        String[] fields = text.substring(open + 1, close).split(", ");
        Arrays.sort(fields);
        return text.substring(0, open + 1) + String.join(", ", fields) + text.substring(close);
    }

    static void attempt(String what, Supplier<Object> made) {
        System.out.println(what + ": " + tried(made));
    }

    static String tried(Supplier<Object> made) {
        try {
            return shown(made.get());
        }
        catch (DateTimeParseException e) {
            return shown(e.getMessage()) + " @" + e.getErrorIndex() + (e.getCause() == null ? "" : " < " + e.getCause());
        }
        catch (RuntimeException e) {
            return shown(e);
        }
    }

    /** The pattern of a template in a locale, or the refusal. */
    static String pattern(String template, Locale locale) {
        return tried(() -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(template, IsoChronology.INSTANCE, locale));
    }

    static boolean refused(String answer) {
        return answer.startsWith("java.");
    }

    static String repeated(String letter, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(letter);
        }
        return text.toString();
    }

    static long seed = 77;

    static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    public static void main(String[] args) {
        List<String> dates = Templates.dates();
        Templates.times();
        Templates.joined(dates);
        Templates.refusals();
        Templates.random();
        Formatters.run();
        Calendars.run();
    }

    // The patterns of templates and their refusals.
    static final class Templates {

        /** Prints the templates some locale has a pattern for, with each locale's answer; a hash of all. */
        static List<String> asked(String what, List<String> templates) {
            List<String> resolved = new ArrayList<>();
            int[] hashes = new int[LOCALES.length];
            int[] counts = new int[LOCALES.length];
            for (String template : templates) {
                StringBuilder line = new StringBuilder(template);
                boolean any = false;
                for (int i = 0; i < LOCALES.length; i++) {
                    String answer = pattern(template, LOCALES[i]);
                    hashes[i] = hashes[i] * 31 + answer.hashCode();
                    if (!refused(answer)) {
                        counts[i]++;
                        any = true;
                    }
                    line.append(" | ").append(refused(answer) ? "-" : answer);
                }
                if (any) {
                    resolved.add(template);
                    System.out.println(line);
                }
            }
            StringBuilder sum = new StringBuilder(what + " " + templates.size() + ":");
            for (int i = 0; i < LOCALES.length; i++) {
                sum.append(' ').append(counts[i]).append('/').append(hashes[i]);
            }
            System.out.println(sum);
            return resolved;
        }

        /** The dates: each field's counts about the ones a skeleton has. */
        static List<String> dates() {
            List<String> templates = new ArrayList<>();
            for (int g : new int[] {0, 1, 2, 5}) {
                for (int y : new int[] {0, 1, 2, 3, 4, 5, 9}) {
                    for (int q : new int[] {0, 2, 3, 4, 5}) {
                        for (int m = 0; m <= 5; m++) {
                            for (int w : new int[] {0, 1, 2}) {
                                for (int e = 0; e <= 5; e++) {
                                    for (int d = 0; d <= 2; d++) {
                                        if (q + w > 0 && (m > 0 && m != 3 || e > 0 && e != 4)) {
                                            // beside a quarter or a week, a month and a
                                            // day of the week of one count each
                                            continue;
                                        }
                                        templates.add(repeated("G", g) + repeated("y", y) + repeated("Q", q) + repeated("M", m)
                                                + repeated("w", w) + repeated("E", e) + repeated("d", d));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return asked("dates", templates);
        }

        /** The times: a period, every hour letter alone and in pairs, minutes, seconds, zones. */
        static void times() {
            List<String> templates = new ArrayList<>();
            String[] hours = {"", "h", "hh", "H", "HH", "j", "jj", "C", "CC", "hH", "jh", "Cj", "jC", "hC"};
            String[] zones = {"", "v", "vv", "vvvv", "z", "zz", "zzzz", "vz", "zv"};
            for (int b : new int[] {0, 1, 2, 4, 5}) {
                for (String hour : hours) {
                    for (int m = 0; m <= 2; m++) {
                        for (int s = 0; s <= 2; s++) {
                            for (String zone : zones) {
                                templates.add(repeated("B", b) + hour + repeated("m", m) + repeated("s", s) + zone);
                            }
                        }
                    }
                }
            }
            asked("times", templates);
        }

        /** Every date some locale resolves before times: a hash of each date's answers per locale. */
        static void joined(List<String> dates) {
            String[] times = {"H", "Hm", "Hms", "Hmsv", "hm", "hmsv", "j", "jm", "jms", "jmv", "C", "Cm", "Bh", "Bhm",
                    "Bjm", "BCms", "BBjm", "ms", "Hmz", "jmvvvv", "m"};
            int[] all = new int[LOCALES.length];
            for (String date : dates) {
                StringBuilder line = new StringBuilder(date + " +:");
                for (int i = 0; i < LOCALES.length; i++) {
                    int hash = 0;
                    int count = 0;
                    for (String time : times) {
                        String answer = pattern(date + time, LOCALES[i]);
                        hash = hash * 31 + answer.hashCode();
                        count += refused(answer) ? 0 : 1;
                    }
                    all[i] = all[i] * 31 + hash;
                    line.append(' ').append(count).append('/').append(hash);
                }
                System.out.println(line);
            }
            for (String template : new String[] {"Ehm", "EEEEEHms", "EBjm", "EEBCms", "EdHm", "MEdBj", "GyMdBC",
                    "yMMMMEdBjms", "MMMMEdBhm", "yMMMMEEEEdj", "Mj", "dBj", "yyyyMMMMEdBC", "MMMEEEEdBjm"}) {
                StringBuilder line = new StringBuilder(template);
                for (Locale locale : LOCALES) {
                    line.append(" | ").append(pattern(template, locale));
                }
                System.out.println(line);
            }
            System.out.println("joined " + Arrays.toString(all));
        }

        /** Templates out of the order of the fields, past their counts, of other letters, of j and C. */
        static void refusals() {
            String[] templates = {"dM", "Yw", "L", "ddd", "GGGGGG", "yQy", "MMMMMM", "EEEEEE", "BBBBBB", "hhh", "hHh",
                    "mmm", "sss", "vvvvv", "zzzzz", "vzvzv", "jjj", "CCC", "jjH", "jCh", "CjH", "hjj", "dddj", "jddd",
                    "GGGGGGj", "jM", "dMj", "CdC", "CdCC", "CCdC", "CdCdC", "jdjdj", "jCdjC", "CjdCj", "Bjd", "BCd",
                    "jBd", "a", "u", "yyyyx", " ", "y-M", "'y'", "Cy", "MCMCMMEEEEEdBhms"};
            for (String template : templates) {
                StringBuilder line = new StringBuilder("'" + template + "'");
                for (Locale locale : LOCALES) {
                    line.append(" | ").append(pattern(template, locale));
                }
                System.out.println(line);
            }
            String letters = "GyQMwEdBhHjCmsvzLcaxu";
            List<String> random = new ArrayList<>();
            for (int i = 0; i < 1500; i++) {
                StringBuilder template = new StringBuilder();
                int length = 1 + next(9);
                for (int k = 0; k < length; k++) {
                    template.append(letters.charAt(next(letters.length())));
                }
                random.add(template.toString());
            }
            asked("letters", random);
        }

        /** Templates of the documented order, of seeded counts. */
        static void random() {
            String order = "GyQMwEdB";
            int[] most = {5, 12, 5, 5, 4, 5, 2, 5};
            List<String> templates = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                StringBuilder template = new StringBuilder();
                for (int f = 0; f < order.length(); f++) {
                    if (next(3) == 0) {
                        template.append(repeated(order.substring(f, f + 1), 1 + next(most[f])));
                    }
                }
                int hours = next(4);
                for (int k = 0; k < hours && k < 2; k++) {
                    template.append("hHjC".charAt(next(4)));
                }
                template.append(repeated("m", next(3))).append(repeated("s", next(3)));
                int zones = next(6);
                for (int k = 0; k < zones && k < 4; k++) {
                    template.append("vvz".charAt(next(3)));
                }
                templates.add(template.toString());
            }
            asked("random", templates);
        }
    }

    // A formatter of a template: written, read, refused, built.
    static final class Formatters {

        static void run() {
            LocalDate date = LocalDate.of(2026, 3, 7);
            LocalTime time = LocalTime.of(15, 4, 5);
            ZoneId york = ZoneId.of("America/New_York");
            // the zone a JDK reads a Canadian ET as, held
            ZoneId.of("America/Toronto");
            Object[] values = {date, LocalDate.of(-5, 12, 31), time, LocalTime.MIDNIGHT, LocalDateTime.of(date, time),
                    ZonedDateTime.of(date, time, york), ZonedDateTime.of(LocalDate.of(2026, 7, 4), LocalTime.NOON, york),
                    OffsetDateTime.of(date, time, ZoneOffset.ofHours(9)), YearMonth.of(2026, 3)};
            String[] templates = {"yMMMd", "yMMMMEEEEd", "yMd", "MMMMd", "MEd", "Ed", "d", "E", "M", "MMM", "y", "yyyy",
                    "GyMd", "yyyyMMMEd", "yQQQ", "yQQQQ", "yw", "jm", "jms", "Hmsv", "hmv", "Bhm", "EBjm", "Ehms", "ms",
                    "yMMMdjm", "yMdHms", "MEdBjm", "GyMMMEEEEdjmsv", "Mj", "s"};
            for (String template : templates) {
                DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedPattern(template);
                for (Locale locale : LOCALES) {
                    DateTimeFormatter local = formatter.withLocale(locale);
                    StringBuilder line = new StringBuilder(template + " " + locale.toLanguageTag() + ":");
                    for (Object value : values) {
                        String text = tried(() -> local.format((TemporalAccessor) value));
                        line.append(" [").append(text);
                        if (!refused(text) && value == values[5]) {
                            line.append(" < ").append(tried(() -> local.parse(text.replace('_', '\u202f'))));
                        }
                        line.append(']');
                    }
                    System.out.println(line);
                }
            }
            DateTimeFormatter medium = DateTimeFormatter.ofLocalizedPattern("yMMMd");
            System.out.println(medium + " " + medium.getLocale() + " " + medium.getChronology() + " "
                    + medium.getResolverStyle() + " " + medium.getZone() + " " + medium.getDecimalStyle() + " "
                    + medium.withLocale(Locale.US).equals(medium) + " " + (medium.withLocale(medium.getLocale()) == medium));
            attempt("read", () -> LocalDate.parse("7 Mar 2026", medium.withLocale(Locale.UK)));
            attempt("read the US's", () -> LocalDate.parse("7 Mar 2026", medium));
            attempt("read leniently", () -> medium.withLocale(Locale.ROOT).withResolverStyle(ResolverStyle.LENIENT)
                .parse("2026 Feb 30"));
            attempt("read strictly", () -> medium.withLocale(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
                .parse("2026 Feb 30"));
            attempt("read a period", () -> LocalTime.parse("3:04 in the afternoon",
                    DateTimeFormatter.ofLocalizedPattern("Bjm").withLocale(Locale.UK)));
            attempt("null", () -> DateTimeFormatter.ofLocalizedPattern(null));
            attempt("invalid", () -> DateTimeFormatter.ofLocalizedPattern("dM"));
            attempt("invalid j", () -> DateTimeFormatter.ofLocalizedPattern("jjj"));
            attempt("invalid C", () -> DateTimeFormatter.ofLocalizedPattern("CdC"));
            DateTimeFormatter none = DateTimeFormatter.ofLocalizedPattern("s");
            attempt("none", () -> none);
            for (Locale locale : LOCALES) {
                attempt("none written " + locale.toLanguageTag(), () -> none.withLocale(locale).format(time));
            }
            attempt("none read", () -> none.withLocale(Locale.UK).parse("12"));
            attempt("none unresolved", () -> none.withLocale(Locale.UK).parseUnresolved("12", new ParsePosition(0)));
            attempt("none at a position", () -> none.withLocale(Locale.UK).parse("12", new ParsePosition(0)));
            attempt("none as a format", () -> none.withLocale(Locale.UK).toFormat().format(time));
            attempt("none read as a format", () -> {
                try {
                    return none.withLocale(Locale.UK).toFormat().parseObject("12");
                }
                catch (ParseException e) {
                    return e.getMessage() + " @" + e.getErrorOffset() + " < " + e.getCause();
                }
            });
            attempt("none read as a format at a position",
                    () -> none.withLocale(Locale.UK).toFormat().parseObject("12", new ParsePosition(0)));
            attempt("none optional", () -> new DateTimeFormatterBuilder().optionalStart()
                .appendLocalized("s")
                .optionalEnd()
                .toFormatter(Locale.UK)
                .format(time));
            attempt("none optional read", () -> new DateTimeFormatterBuilder().appendLiteral('x')
                .optionalStart()
                .appendLocalized("s")
                .optionalEnd()
                .toFormatter(Locale.UK)
                .parse("x12"));
            attempt("a field missing, optional", () -> new DateTimeFormatterBuilder().optionalStart()
                .appendLocalized("yMd")
                .optionalEnd()
                .toFormatter(Locale.UK)
                .format(time));
            attempt("a field missing", () -> medium.format(time));
            attempt("built", () -> new DateTimeFormatterBuilder().appendLiteral('<')
                .appendLocalized("yMMMd")
                .appendLiteral('>')
                .appendLocalized(FormatStyle.SHORT, null)
                .appendLocalized("")
                .toFormatter());
            attempt("built null", () -> new DateTimeFormatterBuilder().appendLocalized((String) null));
            attempt("built invalid", () -> new DateTimeFormatterBuilder().appendLocalized("jM"));
            attempt("built empty", () -> new DateTimeFormatterBuilder().appendLocalized("").toFormatter(Locale.UK).format(date));
            attempt("built of no calendar", () -> new DateTimeFormatterBuilder().appendLocalized("yMMMd")
                .toFormatter(Locale.UK)
                .getChronology());
            attempt("padded", () -> new DateTimeFormatterBuilder().padNext(12, '*')
                .appendLocalized("yMd")
                .toFormatter(Locale.UK)
                .format(date));
            attempt("padded read", () -> new DateTimeFormatterBuilder().padNext(12, '*')
                .appendLocalized("yMd")
                .toFormatter(Locale.UK)
                .parse("**07/03/2026"));
            attempt("in a decimal style", () -> DateTimeFormatter.ofLocalizedPattern("yMd")
                .withLocale(Locale.UK)
                .withDecimalStyle(DecimalStyle.STANDARD.withZeroDigit('A'))
                .format(date));
            attempt("localized by", () -> medium.localizedBy(Locale.UK).format(date) + " "
                    + none.localizedBy(Locale.CANADA).getLocale());
            attempt("a calendar's query", () -> medium.withLocale(Locale.UK).parse("7 Mar 2026")
                .query(TemporalQueries.chronology()));
            attempt("of a zone", () -> DateTimeFormatter.ofLocalizedPattern("yMMMdjmv")
                .withLocale(Locale.US)
                .withZone(ZoneId.of("Asia/Tokyo"))
                .format(Instant.ofEpochSecond(1772870704L)));
        }
    }

    // A calendar of the probe's own: of no type, of another.
    static final class Calendars {

        static final class Mine extends AbstractChronology {

            final String type;

            Mine(String type) {
                this.type = type;
            }

            public String getId() {
                return "Mine";
            }

            public String getCalendarType() {
                return type;
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
                return IsoChronology.INSTANCE.isLeapYear(prolepticYear);
            }

            public int prolepticYear(Era era, int yearOfEra) {
                return IsoChronology.INSTANCE.prolepticYear(era, yearOfEra);
            }

            public Era eraOf(int eraValue) {
                return IsoEra.of(eraValue);
            }

            public List<Era> eras() {
                return List.of(IsoEra.values());
            }

            public ValueRange range(ChronoField field) {
                return field.range();
            }
        }

        // a date whose calendar is the probe's own
        static final class Dated implements TemporalAccessor {

            final LocalDate date;

            final Chronology chronology;

            Dated(LocalDate date, Chronology chronology) {
                this.date = date;
                this.chronology = chronology;
            }

            public boolean isSupported(TemporalField field) {
                return date.isSupported(field);
            }

            public long getLong(TemporalField field) {
                return date.getLong(field);
            }

            @SuppressWarnings("unchecked")
            public <R> R query(TemporalQuery<R> query) {
                return query == TemporalQueries.chronology() ? (R) chronology : TemporalAccessor.super.query(query);
            }
        }

        static void run() {
            LocalDate date = LocalDate.of(2026, 3, 7);
            for (String type : new String[] {null, "foo", "iso8601"}) {
                Chronology mine = new Mine(type);
                attempt("pattern " + type, () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern("yMMMd", mine, Locale.UK));
                attempt("pattern of none " + type,
                        () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern("s", mine, Locale.UK));
                attempt("pattern invalid " + type,
                        () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern("dM", mine, Locale.UK));
                attempt("style " + type, () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.MEDIUM,
                        null, mine, Locale.UK));
                attempt("style of none " + type,
                        () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, null, mine, Locale.UK));
                DateTimeFormatter templated = DateTimeFormatter.ofLocalizedPattern("yMMMd").withLocale(Locale.UK)
                    .withChronology(mine);
                DateTimeFormatter styled = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.UK)
                    .withChronology(mine);
                attempt("written " + type, () -> templated.format(date) + " " + styled.format(date));
                if (type == null) {
                    // a calendar of a type is read in ISO's here (.kb/java-frontend.md)
                    attempt("read " + type, () -> templated.parse("7 Mar 2026"));
                    attempt("read a style " + type, () -> styled.parse("7 Mar 2026"));
                }
                attempt("a value's " + type, () -> new DateTimeFormatterBuilder().appendLocalized("yMMMd")
                    .toFormatter(Locale.UK)
                    .format(new Dated(date, mine)));
                attempt("a value's of a style " + type, () -> new DateTimeFormatterBuilder()
                    .appendLocalized(FormatStyle.MEDIUM, null)
                    .toFormatter(Locale.UK)
                    .format(new Dated(date, mine)));
                attempt("a value's in ISO's " + type, () -> DateTimeFormatter.ofLocalizedPattern("yMMMd")
                    .withLocale(Locale.UK)
                    .format(new Dated(date, mine)));
            }
            attempt("pattern no template", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(null,
                    IsoChronology.INSTANCE, Locale.UK));
            attempt("pattern no calendar", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern("yMd", null, Locale.UK));
            attempt("pattern no locale", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern("yMd",
                    IsoChronology.INSTANCE, null));
            attempt("pattern nothing", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, null, null));
            attempt("pattern invalid of nothing", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern("dM", null,
                    null));
        }
    }
}
