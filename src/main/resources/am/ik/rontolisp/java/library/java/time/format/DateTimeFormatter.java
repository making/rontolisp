// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A formatter: parts that write a date or a time and read one back (Step), the locale
// whose words they write, how what is read is made a date and a time (Parsed), and a
// zone that overrides the value's. A text is read whole first, then its fields are made
// a date and a time; the three kinds of refusal and their words are a JDK's: where the
// text stops being the form, where it goes on past it, and why the fields make no value.
// The ISO constants are the forms of ISO-8601 the API documentation gives; each is made
// only in a program that reads it (wanted(), a question the compiler answers).
package java.time.format;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQuery;
import java.time.temporal.TemporalQueries;
import java.util.Locale;
import java.util.Objects;

public final class DateTimeFormatter {

    public static final DateTimeFormatter ISO_LOCAL_DATE = wanted() ? new DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
        .appendLiteral('-')
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_OFFSET_DATE = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_DATE)
        .appendOffsetId()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_DATE = wanted() ? new DateTimeFormatterBuilder().parseCaseInsensitive()
        .append(ISO_LOCAL_DATE)
        .optionalStart()
        .appendOffsetId()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_LOCAL_TIME = wanted() ? new DateTimeFormatterBuilder()
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, null) : null;

    public static final DateTimeFormatter ISO_OFFSET_TIME = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_TIME)
        .appendOffsetId()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, null) : null;

    public static final DateTimeFormatter ISO_TIME = wanted() ? new DateTimeFormatterBuilder().parseCaseInsensitive()
        .append(ISO_LOCAL_TIME)
        .optionalStart()
        .appendOffsetId()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, null) : null;

    public static final DateTimeFormatter ISO_LOCAL_DATE_TIME = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_DATE)
        .appendLiteral('T')
        .append(ISO_LOCAL_TIME)
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_OFFSET_DATE_TIME = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(ISO_LOCAL_DATE_TIME)
        .parseLenient()
        .appendOffsetId()
        .parseStrict()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_ZONED_DATE_TIME = wanted() ? new DateTimeFormatterBuilder()
        .append(ISO_OFFSET_DATE_TIME)
        .optionalStart()
        .appendLiteral('[')
        .parseCaseSensitive()
        .appendZoneRegionId()
        .appendLiteral(']')
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_DATE_TIME = wanted() ? new DateTimeFormatterBuilder()
        .append(ISO_LOCAL_DATE_TIME)
        .optionalStart()
        .appendOffsetId()
        .optionalStart()
        .appendLiteral('[')
        .parseCaseSensitive()
        .appendZoneRegionId()
        .appendLiteral(']')
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_ORDINAL_DATE = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
        .appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_YEAR, 3)
        .optionalStart()
        .appendOffsetId()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_WEEK_DATE = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendValue(IsoFields.WEEK_BASED_YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
        .appendLiteral("-W")
        .appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 2)
        .appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_WEEK, 1)
        .optionalStart()
        .appendOffsetId()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter ISO_INSTANT = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendInstant()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, null) : null;

    public static final DateTimeFormatter BASIC_ISO_DATE = wanted() ? new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendValue(ChronoField.YEAR, 4)
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .optionalStart()
        .parseLenient()
        .appendOffset("+HHMMss", "Z")
        .parseStrict()
        .formatter(Locale.getDefault(), ResolverStyle.STRICT, IsoChronology.INSTANCE) : null;

    public static final DateTimeFormatter RFC_1123_DATE_TIME = wanted() ? rfc1123() : null;

    final Step.Group root;

    private final Locale locale;

    private final ResolverStyle style;

    private final ZoneId zone;

    /** The calendar, or null: ISO's for the ISO forms of a date, any a program gave. */
    private final Chronology chronology;

    DateTimeFormatter(Step.Group root, Locale locale, ResolverStyle style, ZoneId zone, Chronology chronology) {
        this.root = root;
        this.locale = locale;
        this.style = style;
        this.zone = zone;
        this.chronology = chronology;
    }

    /**
     * Whether the program reads the constant being initialized: answered by the compiler,
     * so a constant no program reads is never made, nor the parts only it needs.
     */
    static native boolean wanted();

    private static DateTimeFormatter rfc1123() {
        return new DateTimeFormatterBuilder().parseCaseInsensitive()
            .parseLenient()
            .optionalStart()
            .appendWords(ChronoField.DAY_OF_WEEK, new String[] {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"})
            .appendLiteral(", ")
            .optionalEnd()
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral(' ')
            .appendWords(ChronoField.MONTH_OF_YEAR,
                    new String[] {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"})
            .appendLiteral(' ')
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalEnd()
            .appendLiteral(' ')
            .appendOffset("+HHMM", "GMT")
            .formatter(Locale.getDefault(), ResolverStyle.SMART, IsoChronology.INSTANCE);
    }

    /**
     * A formatter of a pattern's parts: the compiler notes the letters of a pattern it
     * sees, then calls patterned (Patterns).
     */
    public static native DateTimeFormatter ofPattern(String pattern);

    public static native DateTimeFormatter ofPattern(String pattern, Locale locale);

    static DateTimeFormatter patterned(String pattern) {
        return DateTimeFormatterBuilder.appended(new DateTimeFormatterBuilder(), pattern).toFormatter();
    }

    static DateTimeFormatter patterned(String pattern, Locale locale) {
        return DateTimeFormatterBuilder.appended(new DateTimeFormatterBuilder(), pattern).toFormatter(locale);
    }

    public static DateTimeFormatter ofLocalizedDate(FormatStyle dateStyle) {
        Objects.requireNonNull(dateStyle, "dateStyle");
        return localized(dateStyle, null);
    }

    /**
     * A localized form of a time: the compiler notes a zone's name in words where the
     * style can be full or long, then calls localizedTime.
     */
    public static native DateTimeFormatter ofLocalizedTime(FormatStyle timeStyle);

    public static native DateTimeFormatter ofLocalizedDateTime(FormatStyle dateTimeStyle);

    public static native DateTimeFormatter ofLocalizedDateTime(FormatStyle dateStyle, FormatStyle timeStyle);

    static DateTimeFormatter localizedTime(FormatStyle timeStyle) {
        Objects.requireNonNull(timeStyle, "timeStyle");
        return localized(null, timeStyle);
    }

    static DateTimeFormatter localizedDateTime(FormatStyle dateTimeStyle) {
        Objects.requireNonNull(dateTimeStyle, "dateTimeStyle");
        return localized(dateTimeStyle, dateTimeStyle);
    }

    static DateTimeFormatter localizedDateTime(FormatStyle dateStyle, FormatStyle timeStyle) {
        Objects.requireNonNull(dateStyle, "dateStyle");
        Objects.requireNonNull(timeStyle, "timeStyle");
        return localized(dateStyle, timeStyle);
    }

    private static DateTimeFormatter localized(FormatStyle dateStyle, FormatStyle timeStyle) {
        return DateTimeFormatterBuilder.localized(new DateTimeFormatterBuilder(), dateStyle, timeStyle)
            .formatter(Locale.getDefault(), ResolverStyle.SMART, IsoChronology.INSTANCE);
    }

    public static TemporalQuery<Period> parsedExcessDays() {
        return Parsed.EXCESS_DAYS;
    }

    public static TemporalQuery<Boolean> parsedLeapSecond() {
        return Parsed.LEAP_SECOND;
    }

    public Locale getLocale() {
        return locale;
    }

    public DateTimeFormatter withLocale(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return locale.equals(this.locale) ? this : new DateTimeFormatter(root, locale, style, zone, chronology);
    }

    /**
     * The formatter of a locale and of what the locale names: a calendar, a zone, a style
     * of digits. None of the locales the library has names one, so the calendar is the
     * locale's own, ISO's.
     */
    public DateTimeFormatter localizedBy(Locale locale) {
        if (locale == null) {
            throw new NullPointerException(
                    "Cannot invoke \"java.util.Locale.getUnicodeLocaleType(String)\" because \"locale\" is null");
        }
        if (locale.equals(this.locale) && chronology == IsoChronology.INSTANCE) {
            return this;
        }
        return new DateTimeFormatter(root, locale, style, zone, IsoChronology.INSTANCE);
    }

    public ResolverStyle getResolverStyle() {
        return style;
    }

    public DateTimeFormatter withResolverStyle(ResolverStyle resolverStyle) {
        Objects.requireNonNull(resolverStyle, "resolverStyle");
        return resolverStyle == style ? this : new DateTimeFormatter(root, locale, resolverStyle, zone, chronology);
    }

    public Chronology getChronology() {
        return chronology;
    }

    /** The calendar a text is read in and a value written in; the library has ISO's alone. */
    public DateTimeFormatter withChronology(Chronology chronology) {
        return Objects.equals(chronology, this.chronology) ? this
                : new DateTimeFormatter(root, locale, style, zone, chronology);
    }

    public ZoneId getZone() {
        return zone;
    }

    public DateTimeFormatter withZone(ZoneId zone) {
        return Objects.equals(zone, this.zone) ? this : new DateTimeFormatter(root, locale, style, zone, chronology);
    }

    public String format(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        Writing out = new Writing(zoned(temporal), Texts.kind(locale), chronology);
        root.print(out);
        return out.text.toString();
    }

    public void formatTo(TemporalAccessor temporal, Appendable appendable) {
        Objects.requireNonNull(temporal, "temporal");
        Objects.requireNonNull(appendable, "appendable");
        String text = format(temporal);
        try {
            appendable.append(text);
        }
        catch (IOException failed) {
            throw new DateTimeException(failed.getMessage(), failed);
        }
    }

    /** The value as the formatter's zone sees it: an instant moved there, any other told it. */
    private TemporalAccessor zoned(TemporalAccessor temporal) {
        if (zone == null || zone.equals(temporal.query(TemporalQueries.zoneId()))) {
            return temporal;
        }
        if (temporal.isSupported(ChronoField.INSTANT_SECONDS)) {
            return ZonedDateTime.ofInstant(Instant.from(temporal), zone);
        }
        if (zone.normalized() instanceof ZoneOffset offset && temporal.isSupported(ChronoField.OFFSET_SECONDS)
                && temporal.get(ChronoField.OFFSET_SECONDS) != offset.getTotalSeconds()) {
            throw new DateTimeException("Unable to apply override zone '" + zone.getId()
                    + "' because the temporal object being formatted has a different offset but"
                    + " does not represent an instant: " + temporal.toString());
        }
        return new Zoned(temporal, zone);
    }

    public TemporalAccessor parse(CharSequence text) {
        Objects.requireNonNull(text, "text");
        return parsed(text);
    }

    public <T> T parse(CharSequence text, TemporalQuery<T> query) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(query, "query");
        Parsed parsed = parsed(text);
        try {
            return query.queryFrom(parsed);
        }
        catch (DateTimeParseException refused) {
            throw refused;
        }
        catch (RuntimeException refused) {
            throw refusal(text, refused);
        }
    }

    public TemporalAccessor parseBest(CharSequence text, TemporalQuery<?>... queries) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(queries, "queries");
        if (queries.length < 2) {
            throw new IllegalArgumentException("At least two queries must be specified");
        }
        Parsed parsed = parsed(text);
        for (TemporalQuery<?> query : queries) {
            try {
                return (TemporalAccessor) query.queryFrom(parsed);
            }
            catch (RuntimeException next) {
                // the next query is asked
            }
        }
        throw new DateTimeException("Unable to convert parsed text using any of the specified queries");
    }

    private static String shown(CharSequence text) {
        return "Text '" + (text.length() > 64 ? text.subSequence(0, 64) + "..." : text.toString())
                + "' could not be parsed";
    }

    private static DateTimeParseException refusal(CharSequence text, RuntimeException cause) {
        return new DateTimeParseException(shown(text) + ": " + cause.getMessage(), text, 0, cause);
    }

    private Parsed parsed(CharSequence text) {
        Reading in = new Reading(text, Texts.kind(locale));
        int end;
        try {
            end = root.parse(in, 0);
        }
        catch (DateTimeParseException refused) {
            throw refused;
        }
        catch (RuntimeException refused) {
            throw refusal(text, refused);
        }
        if (end < 0) {
            throw new DateTimeParseException(shown(text) + " at index " + ~end, text, ~end);
        }
        if (end < text.length()) {
            throw new DateTimeParseException(shown(text) + ", unparsed text found at index " + end, text, end);
        }
        Parsed parsed = new Parsed(in);
        try {
            parsed.resolve(style, zone);
        }
        catch (RuntimeException refused) {
            throw refusal(text, refused);
        }
        return parsed;
    }

    public String toString() {
        return root.inner();
    }

    /** A value that has no zone of its own told the formatter's. */
    private static final class Zoned implements TemporalAccessor {

        private final TemporalAccessor temporal;

        private final ZoneId zone;

        Zoned(TemporalAccessor temporal, ZoneId zone) {
            this.temporal = temporal;
            this.zone = zone;
        }

        public boolean isSupported(TemporalField field) {
            return temporal.isSupported(field);
        }

        public long getLong(TemporalField field) {
            return temporal.getLong(field);
        }

        @SuppressWarnings("unchecked")
        public <R> R query(TemporalQuery<R> query) {
            return query == TemporalQueries.zoneId() ? (R) zone : temporal.query(query);
        }

        public String toString() {
            return temporal.toString();
        }
    }
}
