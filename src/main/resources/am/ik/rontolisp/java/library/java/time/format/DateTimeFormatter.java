// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A formatter: parts that write a date or a time and read one back (Step), the locale
// whose words they write, the characters of its numbers (DecimalStyle), how what is read
// is made a date and a time (Parsed) and of which fields, and a zone that overrides the
// value's. A text is read first, then its fields are made a date and a time; the three
// kinds of refusal and their words are a JDK's: where the text stops being the form,
// where it goes on past it, and why the fields make no value. Which of them a reading
// from a position and a java.text.Format throw, wrap or note in the position are a JDK
// 25's answers. The ISO constants are the forms of ISO-8601 the API documentation gives;
// each is made only in a program that reads it (wanted(), a question the compiler
// answers); a program that never names a decimal style or the fields to resolve has
// nothing of them (decimals(), Parsed.filtered()). A formatter of a calendar writes a
// value in that calendar and reads one in it (calendared, Parsed), in a program with a
// calendar of its own; any other program's dates are all ISO's.
package java.time.format;

import java.io.IOException;
import java.text.FieldPosition;
import java.text.Format;
import java.text.ParseException;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQuery;
import java.time.temporal.TemporalQueries;
import java.time.temporal.ValueRange;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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

    /** The characters of the numbers, or null for the standard ones. */
    private final DecimalStyle decimals;

    private final ResolverStyle style;

    /**
     * The fields a text's fields are cut to before they are resolved, each once, or null
     * for all: an array, so that a program naming them hashes no field (a set of them
     * made only where it is asked for).
     */
    private final TemporalField[] resolverFields;

    /** The set getResolverFields answers, made the first time it is asked. */
    private Set<TemporalField> resolverSet;

    private final ZoneId zone;

    /** The calendar, or null: ISO's for the ISO forms of a date, any a program gave. */
    private final Chronology chronology;

    DateTimeFormatter(Step.Group root, Locale locale, DecimalStyle decimals, ResolverStyle style,
            TemporalField[] resolverFields, ZoneId zone, Chronology chronology) {
        this.root = root;
        this.locale = locale;
        this.decimals = decimals;
        this.style = style;
        this.resolverFields = resolverFields;
        this.zone = zone;
        this.chronology = chronology;
    }

    /**
     * Whether the program reads the constant being initialized: answered by the compiler,
     * so a constant no program reads is never made, nor the parts only it needs.
     */
    static native boolean wanted();

    /**
     * Whether the program can give a formatter a decimal style of its own: answered by
     * whether it reaches withDecimalStyle, so a program that cannot writes and reads the
     * standard characters with nothing of the others.
     */
    static native boolean decimals();

    /**
     * Whether the program can give a formatter a zone: answered by whether it reaches
     * withZone, so a program with a calendar of its own that cannot has nothing of a zoned
     * date-time of its calendar where it writes (calendared).
     */
    static native boolean zones();

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

    /**
     * A localized form of a requested template, its pattern found in the formatter's
     * locale where it writes or reads (Skeletons): the compiler notes the letters its
     * patterns can hold, then calls localizedPattern.
     */
    public static native DateTimeFormatter ofLocalizedPattern(String requestedTemplate);

    static DateTimeFormatter localizedPattern(String requestedTemplate) {
        return DateTimeFormatterBuilder.templated(new DateTimeFormatterBuilder(), requestedTemplate)
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
        return locale.equals(this.locale) ? this
                : new DateTimeFormatter(root, locale, decimals, style, resolverFields, zone, chronology);
    }

    /**
     * The formatter of a locale and of what the locale names: a calendar, a zone, a style
     * of digits. None of the locales the library has names one, so the calendar is the
     * locale's own, ISO's, and the digits are the standard ones.
     */
    public DateTimeFormatter localizedBy(Locale locale) {
        if (locale == null) {
            throw new NullPointerException(
                    "Cannot invoke \"java.util.Locale.getUnicodeLocaleType(String)\" because \"locale\" is null");
        }
        if (locale.equals(this.locale) && chronology == IsoChronology.INSTANCE && decimals == null) {
            return this;
        }
        return new DateTimeFormatter(root, locale, null, style, resolverFields, zone, IsoChronology.INSTANCE);
    }

    public DecimalStyle getDecimalStyle() {
        return decimals != null ? decimals : DecimalStyle.STANDARD;
    }

    public DateTimeFormatter withDecimalStyle(DecimalStyle decimalStyle) {
        Objects.requireNonNull(decimalStyle, "decimalStyle");
        if (decimalStyle.equals(getDecimalStyle())) {
            return this;
        }
        return new DateTimeFormatter(root, locale, decimalStyle.equals(DecimalStyle.STANDARD) ? null : decimalStyle,
                style, resolverFields, zone, chronology);
    }

    public ResolverStyle getResolverStyle() {
        return style;
    }

    public DateTimeFormatter withResolverStyle(ResolverStyle resolverStyle) {
        Objects.requireNonNull(resolverStyle, "resolverStyle");
        return resolverStyle == style ? this
                : new DateTimeFormatter(root, locale, decimals, resolverStyle, resolverFields, zone, chronology);
    }

    public Set<TemporalField> getResolverFields() {
        if (resolverSet == null && resolverFields != null) {
            resolverSet = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(resolverFields)));
        }
        return resolverSet;
    }

    /** The fields a text's fields are cut to before they are resolved; null for all of them. */
    public DateTimeFormatter withResolverFields(TemporalField... resolverFields) {
        return resolving(resolverFields);
    }

    public DateTimeFormatter withResolverFields(Set<TemporalField> resolverFields) {
        return resolving(resolverFields == null ? null : resolverFields.toArray(new TemporalField[0]));
    }

    /** The formatter of these fields to resolve, each once; this one where they are the same. */
    private DateTimeFormatter resolving(TemporalField[] given) {
        TemporalField[] fields = null;
        if (given != null) {
            fields = new TemporalField[given.length];
            int count = 0;
            for (TemporalField field : given) {
                if (Parsed.among(field, fields, count) < 0) {
                    fields[count++] = field;
                }
            }
            fields = Arrays.copyOf(fields, count);
        }
        if (fields == null ? resolverFields == null : resolverFields != null && same(fields, resolverFields)) {
            return this;
        }
        return new DateTimeFormatter(root, locale, decimals, style, fields, zone, chronology);
    }

    /** Whether two arrays of fields, each holding a field once, hold the same ones. */
    private static boolean same(TemporalField[] one, TemporalField[] other) {
        if (one.length != other.length) {
            return false;
        }
        for (TemporalField field : one) {
            if (Parsed.among(field, other, other.length) < 0) {
                return false;
            }
        }
        return true;
    }

    public Chronology getChronology() {
        return chronology;
    }

    /** The calendar a text is read in and a value written in: ISO's or one of the program's own. */
    public DateTimeFormatter withChronology(Chronology chronology) {
        return Objects.equals(chronology, this.chronology) ? this
                : new DateTimeFormatter(root, locale, decimals, style, resolverFields, zone, chronology);
    }

    public ZoneId getZone() {
        return zone;
    }

    public DateTimeFormatter withZone(ZoneId zone) {
        return Objects.equals(zone, this.zone) ? this
                : new DateTimeFormatter(root, locale, decimals, style, resolverFields, zone, chronology);
    }

    public String format(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        Writing out = new Writing(Texts.ownCalendars() ? calendared(temporal) : zoned(temporal), Texts.kind(locale),
                chronology);
        if (Step.templated()) {
            out.locale = locale;
        }
        if (decimals()) {
            out.decimals = decimals;
        }
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

    /**
     * The value as the formatter's calendar and zone see it, in a program with a calendar of
     * its own (a JDK 25's answers): a calendar or a zone the value has already is none; a
     * value with an instant is the zone's zoned date-time of it in the calendar; a value
     * with a date has it in the calendar, and one with fields of a date but no date is
     * refused; any other is read as it is, the calendar and the zone told.
     */
    private TemporalAccessor calendared(TemporalAccessor temporal) {
        Chronology own = temporal.query(TemporalQueries.chronology());
        ZoneId ownZone = temporal.query(TemporalQueries.zoneId());
        Chronology calendar = chronology == null || chronology.equals(own) ? null : chronology;
        ZoneId place = zone == null || zone.equals(ownZone) ? null : zone;
        if (calendar == null && place == null) {
            return temporal;
        }
        Chronology effective = calendar != null ? calendar : own;
        if (zones()) {
            if (place != null) {
                if (temporal.isSupported(ChronoField.INSTANT_SECONDS)) {
                    return (effective != null ? effective : IsoChronology.INSTANCE)
                        .zonedDateTime(Instant.from(temporal), place);
                }
                if (place.normalized() instanceof ZoneOffset offset && temporal.isSupported(ChronoField.OFFSET_SECONDS)
                        && temporal.get(ChronoField.OFFSET_SECONDS) != offset.getTotalSeconds()) {
                    throw new DateTimeException("Unable to apply override zone '" + place.getId()
                            + "' because the temporal object being formatted has a different offset but"
                            + " does not represent an instant: " + temporal.toString());
                }
            }
        }
        ChronoLocalDate date = null;
        if (calendar != null) {
            if (temporal.isSupported(ChronoField.EPOCH_DAY)) {
                date = calendar.date(temporal);
            }
            else if (calendar != IsoChronology.INSTANCE || own != null) {
                // the fields of a date but the epoch day: a value with one is a part of a date
                for (ChronoField field : new ChronoField[] {ChronoField.DAY_OF_WEEK,
                    ChronoField.ALIGNED_DAY_OF_WEEK_IN_MONTH, ChronoField.ALIGNED_DAY_OF_WEEK_IN_YEAR,
                    ChronoField.DAY_OF_MONTH, ChronoField.DAY_OF_YEAR, ChronoField.ALIGNED_WEEK_OF_MONTH,
                    ChronoField.ALIGNED_WEEK_OF_YEAR, ChronoField.MONTH_OF_YEAR, ChronoField.PROLEPTIC_MONTH,
                    ChronoField.YEAR_OF_ERA, ChronoField.YEAR, ChronoField.ERA}) {
                    if (temporal.isSupported(field)) {
                        throw new DateTimeException("Unable to apply override chronology '" + calendar
                                + "' because the temporal object being formatted contains date fields but does not"
                                + " represent a whole date: " + temporal);
                    }
                }
            }
        }
        return new Calendared(temporal, date, effective, place != null ? place : ownZone, calendar);
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
        throw refusal(text, new DateTimeException("Unable to convert parsed text using any of the specified queries"));
    }

    /**
     * The text read from a position and resolved, the position moved past what was read:
     * an index error is thrown as it is, any other refusal as a parse's.
     */
    public TemporalAccessor parse(CharSequence text, ParsePosition position) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(position, "position");
        try {
            Parsed parsed = unresolved(text, position);
            if (parsed == null || position.getErrorIndex() >= 0) {
                // an error index the position already held is a failure too (a JDK's answer)
                throw new DateTimeParseException(shown(text) + " at index " + position.getErrorIndex(), text,
                        position.getErrorIndex());
            }
            return resolved(parsed);
        }
        catch (DateTimeParseException | IndexOutOfBoundsException refused) {
            throw refused;
        }
        catch (RuntimeException refused) {
            throw refusal(text, refused);
        }
    }

    /**
     * The fields read from a position, not resolved, the position moved past them; null
     * where the text is no such form, the index it failed at noted in the position.
     */
    public TemporalAccessor parseUnresolved(CharSequence text, ParsePosition position) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(position, "position");
        return unresolved(text, position);
    }

    Parsed unresolved(CharSequence text, ParsePosition position) {
        int index = position.getIndex();
        if (index < 0 || index > text.length()) {
            throw new StringIndexOutOfBoundsException("Index " + index + " out of bounds for length " + text.length());
        }
        Reading in = reading(text);
        int end = root.parse(in, index);
        if (end < 0) {
            position.setErrorIndex(~end);
            return null;
        }
        position.setIndex(end);
        return new Parsed(in);
    }

    /** A java.text.Format writing and reading through this formatter; one reading a query's value. */
    public Format toFormat() {
        return new ClassicFormat(this, null);
    }

    public Format toFormat(TemporalQuery<?> parseQuery) {
        Objects.requireNonNull(parseQuery, "parseQuery");
        return new ClassicFormat(this, parseQuery);
    }

    private static String shown(CharSequence text) {
        return "Text '" + (text.length() > 64 ? text.subSequence(0, 64) + "..." : text.toString())
                + "' could not be parsed";
    }

    private static DateTimeParseException refusal(CharSequence text, RuntimeException cause) {
        return new DateTimeParseException(shown(text) + ": " + cause.getMessage(), text, 0, cause);
    }

    private Reading reading(CharSequence text) {
        Reading in = new Reading(text, Texts.kind(locale));
        if (Step.templated()) {
            in.locale = locale;
        }
        if (Texts.ownCalendars()) {
            in.chronology = chronology;
        }
        if (decimals()) {
            in.decimals = decimals;
        }
        return in;
    }

    private Parsed parsed(CharSequence text) {
        try {
            return whole(text);
        }
        catch (DateTimeParseException refused) {
            throw refused;
        }
        catch (RuntimeException refused) {
            throw refusal(text, refused);
        }
    }

    /**
     * The whole text read and resolved: a refusal where the text stops being the form or
     * goes on past it, what a part or the resolving throws as it is.
     */
    Parsed whole(CharSequence text) {
        Reading in = reading(text);
        int end = root.parse(in, 0);
        if (end < 0) {
            throw new DateTimeParseException(shown(text) + " at index " + ~end, text, ~end);
        }
        if (end < text.length()) {
            throw new DateTimeParseException(shown(text) + ", unparsed text found at index " + end, text, end);
        }
        return resolved(new Parsed(in));
    }

    Parsed resolved(Parsed parsed) {
        parsed.resolve(style, zone, resolverFields);
        return parsed;
    }

    public String toString() {
        return root.inner();
    }

    /**
     * A formatter as a java.text.Format: what it writes, what it reads resolved or a
     * query's value. Which refusal becomes which ParseException, and what a reading from
     * a position notes, are a JDK 25's answers.
     */
    static final class ClassicFormat extends Format {

        private final DateTimeFormatter formatter;

        private final TemporalQuery<?> query;

        ClassicFormat(DateTimeFormatter formatter, TemporalQuery<?> query) {
            this.formatter = formatter;
            this.query = query;
        }

        public StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos) {
            Objects.requireNonNull(obj, "obj");
            Objects.requireNonNull(toAppendTo, "toAppendTo");
            Objects.requireNonNull(pos, "pos");
            if (!(obj instanceof TemporalAccessor temporal)) {
                throw new IllegalArgumentException("Format target must implement TemporalAccessor");
            }
            pos.setBeginIndex(0);
            pos.setEndIndex(0);
            try {
                toAppendTo.append(formatter.format(temporal));
            }
            catch (RuntimeException refused) {
                throw new IllegalArgumentException(refused.getMessage(), refused);
            }
            return toAppendTo;
        }

        public Object parseObject(String text) throws ParseException {
            Objects.requireNonNull(text, "text");
            try {
                return query == null ? formatter.whole(text) : formatter.parse(text, query);
            }
            catch (DateTimeParseException refused) {
                throw new ParseException(refused.getMessage(), refused.getErrorIndex());
            }
            catch (RuntimeException refused) {
                ParseException failed = new ParseException(refused.getMessage(), 0);
                failed.initCause(refused);
                throw failed;
            }
        }

        public Object parseObject(String text, ParsePosition pos) {
            Objects.requireNonNull(text, "text");
            if (pos == null) {
                throw new NullPointerException(
                        "Cannot invoke \"java.text.ParsePosition.getErrorIndex()\" because \"pos\" is null");
            }
            Parsed unresolved;
            try {
                unresolved = formatter.unresolved(text, pos);
            }
            catch (RuntimeException refused) {
                return failed(pos);
            }
            if (unresolved == null) {
                return failed(pos);
            }
            try {
                Parsed resolved = formatter.resolved(unresolved);
                return query == null ? resolved : query.queryFrom(resolved);
            }
            catch (RuntimeException refused) {
                return failed(pos);
            }
        }

        /** No value: the position's error index made 0 where it is none. */
        private static Object failed(ParsePosition pos) {
            if (pos.getErrorIndex() < 0) {
                pos.setErrorIndex(0);
            }
            return null;
        }
    }

    /**
     * A value as a formatter of another calendar or zone sees it: the fields of a date the
     * calendar's date's, the calendar and the zone the formatter's where it gave them; in a
     * refusal's text the calendar given after the value, where it is not the value's own.
     */
    private static final class Calendared implements TemporalAccessor {

        private final TemporalAccessor temporal;

        private final ChronoLocalDate date;

        private final Chronology chronology;

        private final ZoneId zone;

        /** The formatter's calendar where it is not the value's own, or null. */
        private final Chronology given;

        Calendared(TemporalAccessor temporal, ChronoLocalDate date, Chronology chronology, ZoneId zone,
                Chronology given) {
            this.temporal = temporal;
            this.date = date;
            this.chronology = chronology;
            this.zone = zone;
            this.given = given;
        }

        public boolean isSupported(TemporalField field) {
            return date != null && field.isDateBased() ? date.isSupported(field) : temporal.isSupported(field);
        }

        public ValueRange range(TemporalField field) {
            return date != null && field.isDateBased() ? date.range(field) : temporal.range(field);
        }

        public long getLong(TemporalField field) {
            return date != null && field.isDateBased() ? date.getLong(field) : temporal.getLong(field);
        }

        @SuppressWarnings("unchecked")
        public <R> R query(TemporalQuery<R> query) {
            if (query == TemporalQueries.zoneId()) {
                return (R) zone;
            }
            if (query == TemporalQueries.chronology()) {
                return (R) chronology;
            }
            if (query == TemporalQueries.precision()) {
                return temporal.query(query);
            }
            return query.queryFrom(this);
        }

        public String toString() {
            return given == null ? temporal.toString() : temporal + " with chronology " + given;
        }
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
