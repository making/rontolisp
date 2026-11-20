import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.Supplier;

// What k52 left of java.time's formatter, asked of a JDK 25: the localized forms, a
// zone's name in words and a day's period, written and read in the five locales Locale
// has, and localizedBy.
public class Grid {

    // ENGLISH last: a JDK that has written a zone's names for ENGLISH writes some of its
    // British and Canadian ones as ENGLISH has them from then on
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
        try {
            System.out.println(what + ": " + shown(made.get()));
        }
        catch (DateTimeParseException e) {
            System.out.println(what + ": " + shown(e.getMessage()) + " @" + e.getErrorIndex());
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + shown(e));
        }
    }

    public static void main(String[] args) {
        Styles.run();
        Names.run();
        Periods.run();
        By.run();
    }

    // The localized forms: patterns, writing, reading.
    static final class Styles {

        static final FormatStyle[] NONE_AND_ALL = {null, FormatStyle.FULL, FormatStyle.LONG, FormatStyle.MEDIUM,
                FormatStyle.SHORT};

        static void run() {
            System.out.println(Arrays.toString(FormatStyle.values()) + " " + FormatStyle.valueOf("MEDIUM").ordinal());
            for (Locale locale : LOCALES) {
                for (FormatStyle date : NONE_AND_ALL) {
                    for (FormatStyle time : NONE_AND_ALL) {
                        if (date == null && time == null) {
                            continue;
                        }
                        attempt("pattern " + locale.toLanguageTag() + " " + date + " " + time,
                                () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, time,
                                        IsoChronology.INSTANCE, locale));
                    }
                }
            }
            attempt("pattern none", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, null,
                    IsoChronology.INSTANCE, Locale.US));
            attempt("pattern no chronology", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT,
                    null, null, Locale.US));
            attempt("pattern no locale", () -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT,
                    null, IsoChronology.INSTANCE, null));
            attempt("ofLocalizedDate null", () -> DateTimeFormatter.ofLocalizedDate(null));
            attempt("ofLocalizedTime null", () -> DateTimeFormatter.ofLocalizedTime(null));
            attempt("ofLocalizedDateTime null", () -> DateTimeFormatter.ofLocalizedDateTime(null));
            attempt("ofLocalizedDateTime null short", () -> DateTimeFormatter.ofLocalizedDateTime(null, FormatStyle.SHORT));
            attempt("ofLocalizedDateTime short null", () -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, null));
            attempt("appendLocalized none", () -> new DateTimeFormatterBuilder().appendLocalized(null, null));
            attempt("built", () -> new DateTimeFormatterBuilder().appendLocalized(FormatStyle.SHORT, null)
                .appendLiteral('|')
                .appendLocalized(null, FormatStyle.FULL)
                .appendLiteral('|')
                .appendLocalized(FormatStyle.LONG, FormatStyle.MEDIUM)
                .toFormatter());
            DateTimeFormatter medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
            // the locale is the host's default under a JDK, the United States' here
            System.out.println(medium + " " + medium.getChronology() + " " + medium.getResolverStyle() + " "
                    + medium.getZone());
            System.out.println(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) + " "
                    + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG) + " "
                    + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT));
            LocalDate date = LocalDate.of(2026, 3, 7);
            LocalTime morning = LocalTime.of(9, 5, 4, 120000000);
            LocalTime afternoon = LocalTime.of(15, 0);
            LocalTime midnight = LocalTime.MIDNIGHT;
            LocalTime noon = LocalTime.NOON;
            ZoneId york = ZoneId.of("America/New_York");
            ZoneId tokyo = ZoneId.of("Asia/Tokyo");
            ZoneId paris = ZoneId.of("Europe/Paris");
            Object[] values = {date, LocalDate.of(-5, 12, 31), LocalDate.of(12026, 1, 1), morning, afternoon, midnight, noon,
                    LocalDateTime.of(date, morning), ZonedDateTime.of(date, morning, york),
                    ZonedDateTime.of(LocalDate.of(2026, 7, 4), afternoon, york), ZonedDateTime.of(date, midnight, tokyo),
                    ZonedDateTime.of(LocalDate.of(2026, 8, 1), noon, paris),
                    ZonedDateTime.of(date, morning, ZoneId.of("UTC")), ZonedDateTime.of(date, morning, ZoneOffset.ofHours(9)),
                    OffsetDateTime.of(date, morning, ZoneOffset.ofHours(-3)), OffsetTime.of(morning, ZoneOffset.UTC),
                    YearMonth.of(2026, 3), Instant.ofEpochSecond(1772870704L)};
            for (Locale locale : LOCALES) {
                for (FormatStyle date0 : NONE_AND_ALL) {
                    for (FormatStyle time0 : NONE_AND_ALL) {
                        if (date0 == null && time0 == null) {
                            continue;
                        }
                        DateTimeFormatter f = new DateTimeFormatterBuilder().appendLocalized(date0, time0)
                            .toFormatter(locale);
                        StringBuilder line = new StringBuilder(locale.toLanguageTag() + " " + f + ":");
                        for (Object value : values) {
                            try {
                                line.append(" [").append(f.format((TemporalAccessor) value)).append("]");
                            }
                            catch (RuntimeException e) {
                                line.append(" <").append(e.getMessage()).append(">");
                            }
                        }
                        System.out.println(shown(line));
                    }
                }
            }
            // a zone the formatter overrides, an instant
            for (FormatStyle style : new FormatStyle[] {FormatStyle.FULL, FormatStyle.LONG, FormatStyle.SHORT}) {
                DateTimeFormatter f = DateTimeFormatter.ofLocalizedDateTime(style).withZone(tokyo);
                attempt("zoned instant " + style, () -> f.format(Instant.ofEpochSecond(0)));
                attempt("zoned local " + style, () -> f.format(LocalDateTime.of(date, morning)));
                attempt("zoned time " + style, () -> DateTimeFormatter.ofLocalizedTime(style).withZone(tokyo).format(morning));
                attempt("time of a local time " + style, () -> DateTimeFormatter.ofLocalizedTime(style).format(morning));
                attempt("offset time " + style, () -> DateTimeFormatter.ofLocalizedTime(style)
                    .format(OffsetTime.of(morning, ZoneOffset.ofHours(2))));
            }
            // reading: each text written, read back by the same formatter in each resolver style
            for (Locale locale : LOCALES) {
                for (FormatStyle date0 : NONE_AND_ALL) {
                    for (FormatStyle time0 : NONE_AND_ALL) {
                        if (date0 == null && time0 == null) {
                            continue;
                        }
                        Object value = time0 == null ? date
                                : date0 == null ? (time0 == FormatStyle.FULL || time0 == FormatStyle.LONG
                                        ? ZonedDateTime.of(date, morning, york) : morning)
                                        : ZonedDateTime.of(date, afternoon, tokyo);
                        DateTimeFormatter f = new DateTimeFormatterBuilder().appendLocalized(date0, time0)
                            .toFormatter(locale);
                        String text = f.format((TemporalAccessor) value);
                        for (ResolverStyle resolver : ResolverStyle.values()) {
                            attempt("read " + locale.toLanguageTag() + " " + date0 + " " + time0 + " " + resolver + " ["
                                    + shown(text) + "]", () -> f.withResolverStyle(resolver).parse(text));
                        }
                    }
                }
            }
            DateTimeFormatter us = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);
            for (String text : new String[] {"3/7/26", "03/07/26", "3/7/2026", "13/7/26", "2/30/26", "3/7/-1", "3/7", "3-7-26"}) {
                for (ResolverStyle resolver : ResolverStyle.values()) {
                    attempt("short date " + resolver + " " + text, () -> LocalDate.parse(text, us.withResolverStyle(resolver)));
                }
            }
            DateTimeFormatter mediumUs = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
            for (String text : new String[] {"Mar 7, 2026", "MAR 7, 2026", "March 7, 2026", "Mar 07, 2026", "Mar 7 2026", "Sept 7, 2026"}) {
                attempt("medium date " + text, () -> LocalDate.parse(text, mediumUs));
                attempt("medium date insensitive " + text, () -> LocalDate.parse(text, new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendLocalized(FormatStyle.MEDIUM, null)
                    .toFormatter(Locale.US)));
                attempt("medium date lenient " + text, () -> LocalDate.parse(text, new DateTimeFormatterBuilder()
                    .parseLenient()
                    .appendLocalized(FormatStyle.MEDIUM, null)
                    .toFormatter(Locale.US)));
            }
            DateTimeFormatter shortTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
            for (String text : new String[] {"9:05 AM", "9:05 AM", "09:05 AM", "12:00 PM", "12:00 AM",
                    "13:00 PM", "0:00 AM", "9:05 am", "9:05 a.m."}) {
                for (ResolverStyle resolver : ResolverStyle.values()) {
                    attempt("short time " + resolver + " " + shown(text),
                            () -> LocalTime.parse(text, shortTime.withResolverStyle(resolver)));
                }
            }
        }
    }

    // A zone's name in words, written and read; ZoneId.getDisplayName.
    static final class Names {

        static final String[] REGIONS = {"America/New_York", "Asia/Tokyo", "Europe/Paris", "Europe/London",
                "Asia/Kolkata", "Australia/Lord_Howe", "Africa/Casablanca", "Europe/Dublin", "America/Vancouver",
                "America/Inuvik", "America/Edmonton", "America/Sao_Paulo", "Asia/Dubai", "Antarctica/Troll",
                "Pacific/Chatham", "America/St_Johns", "Africa/Abidjan", "Africa/Accra", "Africa/Nairobi",
                "America/Toronto", "Asia/Shanghai", "America/Chicago", "America/Los_Angeles", "Australia/Sydney",
                "Pacific/Honolulu", "America/Halifax", "Asia/Famagusta", "Europe/Bucharest", "EST5EDT", "CET",
                "Asia/Jerusalem", "Africa/Maputo", "America/Juneau", "Australia/Adelaide", "Australia/Perth",
                "Asia/Hong_Kong", "Pacific/Guam", "America/Indiana/Knox", "Japan", "Europe/Moscow"};

        static final String[] FIXED = {"UTC", "GMT", "UT", "Z", "+09:00", "-03:30", "UTC+09:00", "GMT-05:00", "UT+01:00",
                "UTC+00:00", "Etc/UTC", "Etc/GMT+5", "Etc/GMT-14", "Zulu", "Greenwich", "UCT", "Universal", "Etc/GMT0",
                "GMT0", "Etc/Greenwich", "Etc/UCT", "Etc/Universal", "Etc/Zulu", "Etc/GMT", "Etc/GMT-0", "Etc/GMT+0"};

        static final Instant[] INSTANTS = {Instant.parse("2026-01-15T12:00:00Z"), Instant.parse("2026-07-15T12:00:00Z"),
                Instant.parse("1949-07-01T00:00:00Z"), Instant.parse("1975-06-01T00:00:00Z"),
                Instant.parse("2033-01-15T00:00:00Z"), Instant.parse("1900-01-01T00:00:00Z"),
                Instant.parse("1942-09-01T00:00:00Z")};

        static void run() {
            String[] patterns = {"z", "zzzz", "v", "vvvv"};
            List<String> all = new ArrayList<>(Arrays.asList(REGIONS));
            all.addAll(Arrays.asList(FIXED));
            for (String id : all) {
                ZoneId zone = ZoneId.of(id);
                for (Locale locale : LOCALES) {
                    StringBuilder line = new StringBuilder(id + " " + locale.toLanguageTag() + ":");
                    for (String pattern : patterns) {
                        DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, locale);
                        line.append(' ').append(pattern).append('=');
                        String last = null;
                        for (Instant instant : INSTANTS) {
                            String text = f.format(instant.atZone(zone));
                            line.append(text.equals(last) ? "," : (last == null ? "" : ",") + text);
                            last = text;
                        }
                    }
                    for (TextStyle style : TextStyle.values()) {
                        line.append(' ').append(style).append('=').append(zone.getDisplayName(style, locale));
                    }
                    System.out.println(shown(line));
                }
            }
            // the styles of appendZoneText and appendGenericZoneText
            ZonedDateTime york = ZonedDateTime.of(2026, 7, 4, 15, 0, 0, 0, ZoneId.of("America/New_York"));
            for (TextStyle style : TextStyle.values()) {
                DateTimeFormatter text = new DateTimeFormatterBuilder().appendZoneText(style).toFormatter(Locale.US);
                DateTimeFormatter generic = new DateTimeFormatterBuilder().appendGenericZoneText(style)
                    .toFormatter(Locale.US);
                attempt("zone text " + style, () -> text + " " + text.format(york) + " " + generic + " " + generic.format(york));
            }
            attempt("preferred", () -> new DateTimeFormatterBuilder()
                .appendZoneText(TextStyle.SHORT, Set.of(ZoneId.of("America/Toronto")))
                .appendGenericZoneText(TextStyle.FULL, Set.of(ZoneId.of("America/Toronto")))
                .toFormatter());
            attempt("patterns", () -> DateTimeFormatter.ofPattern("z zz zzz zzzz v vvvv"));
            for (String pattern : new String[] {"zzzzz", "vv", "vvv", "vvvvv"}) {
                attempt("pattern " + pattern, () -> DateTimeFormatter.ofPattern(pattern));
            }
            attempt("null style", () -> new DateTimeFormatterBuilder().appendZoneText(null));
            attempt("null generic style", () -> new DateTimeFormatterBuilder().appendGenericZoneText(null));
            attempt("null preferred", () -> new DateTimeFormatterBuilder().appendZoneText(TextStyle.SHORT, null));
            attempt("null style preferred", () -> new DateTimeFormatterBuilder().appendZoneText(null, Set.of()));
            attempt("null both", () -> new DateTimeFormatterBuilder().appendGenericZoneText(null, null));
            attempt("display null style", () -> ZoneId.of("Asia/Tokyo").getDisplayName(null, Locale.US));
            attempt("display null locale", () -> ZoneId.of("Asia/Tokyo").getDisplayName(TextStyle.FULL, null));
            // values with no instant, no zone
            DateTimeFormatter z = DateTimeFormatter.ofPattern("z zzzz v vvvv", Locale.US);
            for (String id : new String[] {"America/New_York", "Europe/London", "America/Edmonton", "America/Inuvik", "UTC", "Asia/Tokyo"}) {
                for (LocalDateTime local : new LocalDateTime[] {LocalDateTime.of(2026, 1, 15, 12, 0),
                        LocalDateTime.of(2026, 7, 15, 12, 0), LocalDateTime.of(2026, 3, 8, 2, 30), LocalDateTime.of(2026, 11, 1, 1, 30),
                        LocalDateTime.of(2026, 3, 29, 1, 30), LocalDateTime.of(2026, 10, 25, 1, 30), LocalDateTime.of(1975, 6, 1, 12, 0),
                        LocalDateTime.of(2033, 1, 15, 12, 0)}) {
                    attempt("local " + id + " " + local, () -> z.withZone(ZoneId.of(id)).format(local));
                }
            }
            attempt("offset date-time", () -> z.format(OffsetDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.ofHours(9))));
            attempt("local date-time", () -> z.format(LocalDateTime.of(2026, 1, 15, 12, 0)));
            attempt("optional", () -> DateTimeFormatter.ofPattern("[z]").format(LocalDateTime.of(2026, 1, 15, 12, 0)));
            attempt("zone alone", () -> z.format(DateTimeFormatter.ofPattern("VV").parse("Asia/Tokyo")));
            attempt("zone alone with a date", () -> z.format(DateTimeFormatter.ofPattern("uuuu-MM-dd VV")
                .parse("2026-07-15 America/New_York")));
            attempt("instant withZone", () -> z.withZone(ZoneId.of("Europe/Paris")).format(Instant.parse("2026-07-15T12:00:00Z")));
            // reading
            List<String> texts = new ArrayList<>();
            for (String id : all) {
                ZoneId zone = ZoneId.of(id);
                for (int l = 0; l < LOCALES.length; l++) {
                    for (String pattern : patterns) {
                        for (Instant instant : INSTANTS) {
                            String text = DateTimeFormatter.ofPattern(pattern, LOCALES[l]).format(instant.atZone(zone));
                            String key = l + "|" + pattern.length() + "|" + text;
                            if (!texts.contains(key)) {
                                texts.add(key);
                            }
                        }
                    }
                }
            }
            for (String key : texts) {
                String[] parts = key.split("[|]");
                Locale locale = LOCALES[Integer.parseInt(parts[0])];
                String pattern = parts[1].equals("1") ? "z" : "zzzz";
                String generic = parts[1].equals("1") ? "v" : "vvvv";
                attempt("read " + locale.toLanguageTag() + " " + pattern + " " + shown(parts[2]),
                        () -> readZone(pattern, locale, parts[2]) + " " + readZone(generic, locale, parts[2]));
            }
            String[] odd = {"America/New_York", "Asia/Tokyo", "GMT+09:00", "GMT+9", "GMT+19:00", "GMT+18:00", "GMT+09",
                    "GMT+0900", "UTC", "UTCx", "GMT", "UT", "UT+09:00", "UTC+09:00", "+09:00", "-05:00", "+19:00", "Z", "z",
                    "EST", "est", "Est", "ESTX", "EST5EDT", "JST.", "JSTJST", "Japan Standard Time", "japan standard time",
                    "JAPAN STANDARD TIME", "Japan Time", "Japan", "Tokyo", "Tokyo (+0)", "New York", "CET", "Etc/GMT+5",
                    "GMT0", "Coordinated Universal Time", "Greenwich Mean Time", "Europe/London", "Zulu", "UCT",
                    "Asia/Tokyo/x", "Asia/Tokyox", "Eastern Standard Time Zone", "Eastern Standard", "PST", "PDT",
                    "Pacific Standard Time", "ET", "BST", "British Summer Time", "IST", "Irish Standard Time", "CEST",
                    "Central European Summer Time", "MSK", "Moscow Standard Time", "", "x"};
            for (Locale locale : new Locale[] {Locale.ROOT, Locale.US, Locale.UK, Locale.CANADA}) {
                for (String pattern : new String[] {"z", "zzzz", "VV"}) {
                    for (boolean insensitive : new boolean[] {false, true}) {
                        StringBuilder line = new StringBuilder(locale.toLanguageTag() + " " + pattern
                                + (insensitive ? " insensitive" : "") + ":");
                        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
                        if (insensitive) {
                            builder.parseCaseInsensitive();
                        }
                        DateTimeFormatter f = builder.appendPattern(pattern).toFormatter(locale);
                        for (String text : odd) {
                            line.append(" [").append(text).append("]=");
                            try {
                                TemporalAccessor read = f.parse(text);
                                line.append(read.query(TemporalQueries.zoneId()));
                            }
                            catch (DateTimeParseException e) {
                                line.append("@").append(e.getErrorIndex());
                            }
                        }
                        System.out.println(line);
                    }
                }
            }
            // a name read in a text, then the date-time it makes
            DateTimeFormatter dated = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z", Locale.US);
            for (String text : new String[] {"2026-07-04 10:00 EDT", "2026-07-04 10:00 EST", "2026-01-04 10:00 EDT",
                    "2026-07-04 10:00 BST", "2026-07-04 10:00 GMT", "2026-07-04 10:00 JST", "2026-07-04 10:00 GMT+09:00",
                    "2026-11-01 01:30 EST", "2026-11-01 01:30 EDT", "2026-11-01 01:30 ET"}) {
                attempt("dated " + text, () -> ZonedDateTime.parse(text, dated) + " " + dated.parse(text));
            }
            DateTimeFormatter moscow = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm zzzz", Locale.US);
            for (String text : new String[] {"2014-10-26 01:30 Moscow Standard Time", "2014-10-26 00:30 Moscow Standard Time",
                    "2011-03-27 02:30 Moscow Standard Time", "2026-10-25 02:30 Central European Summer Time",
                    "2026-10-25 02:30 Central European Standard Time", "2026-10-25 02:30 Central European Time",
                    "2026-03-29 02:30 Central European Time"}) {
                attempt("full " + text, () -> ZonedDateTime.parse(text, moscow));
            }
            DateTimeFormatter preferred = new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm ")
                .appendZoneText(TextStyle.SHORT, Set.of(ZoneId.of("America/Toronto")))
                .toFormatter(Locale.US);
            for (String text : new String[] {"2026-07-04 10:00 EDT", "2026-07-04 10:00 CST", "2026-07-04 10:00 ET"}) {
                attempt("preferred " + text, () -> ZonedDateTime.parse(text, preferred));
            }
        }

        static String readZone(String pattern, Locale locale, String text) {
            try {
                TemporalAccessor read = DateTimeFormatter.ofPattern(pattern, locale).parse(text);
                return String.valueOf(read.query(TemporalQueries.zoneId()));
            }
            catch (DateTimeParseException e) {
                return "@" + e.getErrorIndex();
            }
        }
    }

    // A day's period, written and read.
    static final class Periods {

        static void run() {
            for (Locale locale : LOCALES) {
                for (String pattern : new String[] {"B", "BBBB", "BBBBB"}) {
                    DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, locale);
                    StringBuilder line = new StringBuilder(locale.toLanguageTag() + " " + pattern + " " + f + ":");
                    String last = null;
                    for (int minute = 0; minute < 24 * 60; minute++) {
                        LocalTime time = LocalTime.of(minute / 60, minute % 60);
                        String text = f.format(time);
                        if (!text.equals(last)) {
                            line.append(' ').append(time).append('=').append(text);
                            last = text;
                        }
                    }
                    for (LocalTime time : new LocalTime[] {LocalTime.of(0, 0, 0, 1), LocalTime.of(0, 0, 59),
                            LocalTime.of(11, 59, 59, 999999999), LocalTime.of(12, 0, 0, 1), LocalTime.of(12, 0, 59),
                            LocalTime.of(23, 59, 59, 999999999)}) {
                        line.append(' ').append(time).append('=').append(f.format(time));
                    }
                    System.out.println(line);
                }
            }
            for (String pattern : new String[] {"BB", "BBB", "BBBBBB"}) {
                attempt("pattern " + pattern, () -> DateTimeFormatter.ofPattern(pattern));
            }
            for (TextStyle style : TextStyle.values()) {
                attempt("style " + style, () -> {
                    DateTimeFormatter f = new DateTimeFormatterBuilder().appendDayPeriodText(style).toFormatter(Locale.US);
                    return f + " " + f.format(LocalTime.MIDNIGHT) + " " + f.format(LocalTime.of(8, 0));
                });
            }
            attempt("null style", () -> new DateTimeFormatterBuilder().appendDayPeriodText(null));
            attempt("date", () -> DateTimeFormatter.ofPattern("B").format(LocalDate.of(2026, 1, 1)));
            attempt("optional date", () -> DateTimeFormatter.ofPattern("[B]").format(LocalDate.of(2026, 1, 1)));
            attempt("date-time", () -> DateTimeFormatter.ofPattern("uuuu-MM-dd h B").format(LocalDateTime.of(2026, 1, 1, 22, 15)));
            attempt("zoned", () -> DateTimeFormatter.ofPattern("h:mm B").format(ZonedDateTime.of(2026, 1, 1, 22, 15, 0, 0, ZoneOffset.UTC)));
            String[] words = {"midnight", "noon", "in the morning", "in the afternoon", "in the evening", "at night", "mi",
                    "n", "mid", "mor", "aft", "eve", "night", "AM", "PM", "am", "pm", "a.m.", "p.m.", "a", "p", "MIDNIGHT",
                    "In The Morning", "morning", "x", ""};
            for (Locale locale : new Locale[] {Locale.ROOT, Locale.US, Locale.UK, Locale.CANADA}) {
                for (String pattern : new String[] {"B", "BBBB", "BBBBB"}) {
                    for (int mode = 0; mode < 3; mode++) {
                        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
                        if (mode == 1) {
                            builder.parseCaseInsensitive();
                        }
                        if (mode == 2) {
                            builder.parseLenient();
                        }
                        DateTimeFormatter f = builder.appendPattern(pattern).toFormatter(locale);
                        StringBuilder line = new StringBuilder(locale.toLanguageTag() + " " + pattern + " "
                                + (mode == 0 ? "strict" : mode == 1 ? "insensitive" : "lenient") + ":");
                        for (String word : words) {
                            line.append(" [").append(word).append("]=");
                            try {
                                line.append(shown(f.parse(word)));
                            }
                            catch (DateTimeParseException e) {
                                line.append("@").append(e.getErrorIndex());
                            }
                        }
                        System.out.println(line);
                    }
                }
            }
            // with the fields of an hour, in each resolver style
            String[] periods = {"midnight", "in the morning", "noon", "in the afternoon", "in the evening", "at night", "AM", "PM"};
            for (ResolverStyle resolver : ResolverStyle.values()) {
                for (String pattern : new String[] {"B", "h B", "K B", "H B", "k B", "h:mm B", "H:mm B", "hh:mm a B",
                        "H:mm a B", "mm B", "h:mm:ss B", "H:mm:ss B"}) {
                    DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(resolver);
                    for (String period : periods) {
                        StringBuilder line = new StringBuilder(resolver + " [" + pattern + "] " + period + ":");
                        List<String> texts = new ArrayList<>();
                        if (pattern.equals("B")) {
                            texts.add(period);
                        }
                        else if (pattern.equals("mm B")) {
                            texts.add("30 " + period);
                        }
                        else if (pattern.startsWith("hh:mm a")) {
                            texts.add("03:00 AM " + period);
                            texts.add("03:00 PM " + period);
                            texts.add("12:00 AM " + period);
                            texts.add("12:00 PM " + period);
                        }
                        else if (pattern.startsWith("H:mm a")) {
                            texts.add("3:00 AM " + period);
                            texts.add("15:00 PM " + period);
                            texts.add("15:00 AM " + period);
                            texts.add("0:00 PM " + period);
                        }
                        else {
                            for (int hour = 0; hour <= 24; hour++) {
                                texts.add(hour + (pattern.contains(":ss") ? ":00:30" : pattern.contains(":") ? ":01" : "") + " " + period);
                            }
                            if (pattern.startsWith("H:mm")) {
                                texts.add("7:75" + (pattern.contains(":ss") ? ":00" : "") + " " + period);
                                texts.add("123:05" + (pattern.contains(":ss") ? ":61" : "") + " " + period);
                            }
                        }
                        for (String text : texts) {
                            line.append(" [").append(text.length() > period.length() ? text.substring(0, text.length() - period.length() - 1) : "").append("]=");
                            try {
                                TemporalAccessor read = f.parse(text);
                                LocalTime time = read.query(TemporalQueries.localTime());
                                line.append(time != null ? time.toString() : shown(read));
                                Period excess = read.query(DateTimeFormatter.parsedExcessDays());
                                if (!excess.isZero()) {
                                    line.append('+').append(excess);
                                }
                            }
                            catch (DateTimeParseException e) {
                                String message = shown(e.getMessage());
                                int colon = message.indexOf(": ");
                                line.append(colon < 0 ? "@" + e.getErrorIndex() : message.substring(colon + 2));
                            }
                        }
                        System.out.println(line);
                    }
                }
            }
            // a period with the other fields of a time, a date, and a resolved date-time
            for (ResolverStyle resolver : ResolverStyle.values()) {
                for (String text : new String[] {"2026-03-07 in the afternoon", "2026-03-07 midnight", "2026-03-07 at night"}) {
                    attempt(resolver + " date " + text, () -> DateTimeFormatter.ofPattern("uuuu-MM-dd B", Locale.US)
                        .withResolverStyle(resolver)
                        .parse(text));
                }
                attempt(resolver + " with seconds", () -> DateTimeFormatter.ofPattern("ss B", Locale.US)
                    .withResolverStyle(resolver)
                    .parse("30 in the evening"));
                attempt(resolver + " period twice", () -> DateTimeFormatter.ofPattern("B B", Locale.US)
                    .withResolverStyle(resolver)
                    .parse("noon noon"));
                attempt(resolver + " two periods", () -> DateTimeFormatter.ofPattern("B B", Locale.US)
                    .withResolverStyle(resolver)
                    .parse("noon midnight"));
                attempt(resolver + " ampm field", () -> DateTimeFormatter.ofPattern("a B", Locale.US)
                    .withResolverStyle(resolver)
                    .parse("PM in the morning"));
                attempt(resolver + " ampm field night", () -> DateTimeFormatter.ofPattern("a B", Locale.US)
                    .withResolverStyle(resolver)
                    .parse("AM at night"));
                attempt(resolver + " query", () -> DateTimeFormatter.ofPattern("B", Locale.US)
                    .withResolverStyle(resolver)
                    .parse("in the evening")
                    .isSupported(ChronoField.HOUR_OF_DAY));
                attempt(resolver + " with time", () -> LocalTime.parse("in the evening",
                        DateTimeFormatter.ofPattern("B", Locale.US).withResolverStyle(resolver)));
            }
        }
    }

    // localizedBy
    static final class By {

        static void run() {
            DateTimeFormatter medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US);
            DateTimeFormatter pattern = DateTimeFormatter.ofPattern("uuuu-MM-dd EEE", Locale.US);
            attempt("chronology wrapped", () -> DateTimeFormatter.ofPattern("VV").withChronology(IsoChronology.INSTANCE)
                .format(LocalTime.of(9, 5)));
            attempt("chronology and zone wrapped", () -> DateTimeFormatter.ofPattern("xxx")
                .withChronology(IsoChronology.INSTANCE)
                .withZone(ZoneId.of("Asia/Tokyo"))
                .format(LocalTime.of(9, 5)));
            attempt("zone not wrapped", () -> DateTimeFormatter.ofPattern("VV xxx").withZone(ZoneId.of("Asia/Tokyo"))
                .format(LocalTime.of(9, 5)));
            attempt("instant chronology", () -> DateTimeFormatter.ofPattern("VV").withChronology(IsoChronology.INSTANCE)
                .format(Instant.EPOCH));
            for (DateTimeFormatter f : new DateTimeFormatter[] {medium, pattern,
                    DateTimeFormatter.ISO_LOCAL_DATE.withLocale(Locale.US),
                    pattern.withZone(ZoneId.of("Asia/Tokyo")), pattern.withChronology(IsoChronology.INSTANCE),
                    pattern.withResolverStyle(ResolverStyle.STRICT)}) {
                for (Locale locale : LOCALES) {
                    DateTimeFormatter by = f.localizedBy(locale);
                    System.out.println(f + " by " + locale.toLanguageTag() + ": " + by.getLocale() + " " + by.getChronology() + " "
                            + by.getZone() + " " + by.getResolverStyle() + " " + (by == f) + " "
                            + (by == f.withLocale(locale)) + " " + by.format(LocalDate.of(2026, 3, 7)));
                }
            }
            attempt("null", () -> medium.localizedBy(null));
            attempt("withLocale null", () -> medium.withLocale(null));
        }
    }
}
