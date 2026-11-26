// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A formatter made part by part. A number appended right after another, with a fixed
// width and no sign, is read as one of a run (Step.Digits); any other part ends the run.
// A pattern's letters are the parts the API documentation gives for each letter and
// count; the refusals' words are a JDK 25's. A localized form is the pattern of its
// styles in the formatter's locale, as a JDK writes it (Texts), or of a requested template,
// as a JDK answers it (Skeletons).
package java.time.format;

import java.time.ZoneId;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.temporal.TemporalField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DateTimeFormatterBuilder {

    /** The parts of each open group, the outermost first; the last is being appended to. */
    private final List<List<Step>> groups = new ArrayList<>();

    /** The pad each open optional group is written to, packed as width and character. */
    private final List<int[]> pads = new ArrayList<>();

    /** Where the number that begins a run stands in the open group, or -1. */
    private int run = -1;

    private int padWidth;

    private char padChar;

    public DateTimeFormatterBuilder() {
        groups.add(new ArrayList<>());
    }

    private List<Step> open() {
        return groups.get(groups.size() - 1);
    }

    private DateTimeFormatterBuilder add(Step step) {
        run = -1;
        return append0(step);
    }

    private DateTimeFormatterBuilder append0(Step step) {
        if (padWidth > 0) {
            step = new Step.Padded(step, padWidth, padChar);
            padWidth = 0;
            run = -1;
        }
        open().add(step);
        return this;
    }

    public DateTimeFormatterBuilder parseCaseSensitive() {
        return add(new Step.Setting(0));
    }

    public DateTimeFormatterBuilder parseCaseInsensitive() {
        return add(new Step.Setting(1));
    }

    public DateTimeFormatterBuilder parseStrict() {
        return add(new Step.Setting(2));
    }

    public DateTimeFormatterBuilder parseLenient() {
        return add(new Step.Setting(3));
    }

    public DateTimeFormatterBuilder parseDefaulting(TemporalField field, long value) {
        Objects.requireNonNull(field, "field");
        return add(new Step.Default(field, value));
    }

    public DateTimeFormatterBuilder appendValue(TemporalField field) {
        Objects.requireNonNull(field, "field");
        return number(new Step.Digits(field, 1, 19, SignStyle.NORMAL, 0, false, false));
    }

    public DateTimeFormatterBuilder appendValue(TemporalField field, int width) {
        Objects.requireNonNull(field, "field");
        if (width < 1 || width > 19) {
            throw new IllegalArgumentException("The width must be from 1 to 19 inclusive but was " + width);
        }
        return number(new Step.Digits(field, width, width, SignStyle.NOT_NEGATIVE, 0, false, false));
    }

    public DateTimeFormatterBuilder appendValue(TemporalField field, int minWidth, int maxWidth,
            SignStyle signStyle) {
        if (minWidth == maxWidth && signStyle == SignStyle.NOT_NEGATIVE) {
            return appendValue(field, maxWidth);
        }
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(signStyle, "signStyle");
        if (minWidth < 1 || minWidth > 19) {
            throw new IllegalArgumentException("The minimum width must be from 1 to 19 inclusive but was " + minWidth);
        }
        if (maxWidth < 1 || maxWidth > 19) {
            throw new IllegalArgumentException("The maximum width must be from 1 to 19 inclusive but was " + maxWidth);
        }
        if (maxWidth < minWidth) {
            throw new IllegalArgumentException(
                    "The maximum width must exceed or equal the minimum width but " + maxWidth + " < " + minWidth);
        }
        return number(new Step.Digits(field, minWidth, maxWidth, signStyle, 0, false, false));
    }

    public DateTimeFormatterBuilder appendValueReduced(TemporalField field, int width, int maxWidth, int baseValue) {
        Objects.requireNonNull(field, "field");
        return number(reduced(field, width, maxWidth, baseValue, null));
    }

    public DateTimeFormatterBuilder appendValueReduced(TemporalField field, int width, int maxWidth,
            ChronoLocalDate baseDate) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(baseDate, "baseDate");
        java.time.LocalDate date = java.time.LocalDate.from(baseDate);
        return number(reduced(field, width, maxWidth, date.get(field), date));
    }

    private static Step.Reduced reduced(TemporalField field, int width, int maxWidth, long base,
            java.time.LocalDate date) {
        if (width < 1 || width > 10) {
            throw new IllegalArgumentException("The minWidth must be from 1 to 10 inclusive but was " + width);
        }
        if (maxWidth < 1 || maxWidth > 10) {
            // a JDK's words name the least width here
            throw new IllegalArgumentException("The maxWidth must be from 1 to 10 inclusive but was " + width);
        }
        if (maxWidth < width) {
            throw new IllegalArgumentException(
                    "Maximum width must exceed or equal the minimum width but " + maxWidth + " < " + width);
        }
        if (date == null && !field.range().isValidValue(base)) {
            throw new IllegalArgumentException("The base value must be within the range of the field");
        }
        long range = 1;
        for (int i = 0; i < width; i++) {
            range *= 10;
        }
        if (base + range > Integer.MAX_VALUE) {
            throw new java.time.DateTimeException("Unable to add printer-parser as the range exceeds the capacity of an int");
        }
        return new Step.Reduced(field, width, maxWidth, base, date, 0, false, false);
    }

    /**
     * A number appended: one of fixed width and no sign right after another number is
     * read as one of its run, the run's first keeping its digits; one of no fixed width
     * there starts a run of its own, and the run before ends (Step.Digits). Digits of a
     * fraction of a fixed count may begin a run too, keeping none.
     */
    private DateTimeFormatterBuilder number(Step.Digits digits) {
        List<Step> open = open();
        if (run >= 0 && padWidth == 0) {
            Step at = open.get(run);
            if (digits.fixed()) {
                if (at instanceof Step.Digits first) {
                    open.set(run, first.reserving(digits.most));
                }
                open.add(digits.following());
                return this;
            }
            if (at instanceof Step.Digits first) {
                open.set(run, first.ending());
            }
        }
        boolean padded = padWidth > 0;
        append0(digits);
        run = padded ? -1 : open.size() - 1;
        return this;
    }

    /** A number of the weeks of the formatter's locale, which a pattern's letter names. */
    DateTimeFormatterBuilder appendLocalized(Step.Localized localized) {
        return number(localized);
    }

    public DateTimeFormatterBuilder appendFraction(TemporalField field, int minWidth, int maxWidth,
            boolean decimalPoint) {
        if (field == null) {
            throw new NullPointerException(
                    "Cannot invoke \"java.time.temporal.TemporalField.range()\" because \"field\" is null");
        }
        if (!field.range().isFixed()) {
            throw new IllegalArgumentException("Field must have a fixed set of values: " + field.toString());
        }
        if (minWidth < 0 || minWidth > 9) {
            throw new IllegalArgumentException("Minimum width must be from 0 to 9 inclusive but was " + minWidth);
        }
        if (maxWidth < 1 || maxWidth > 9) {
            throw new IllegalArgumentException("Maximum width must be from 1 to 9 inclusive but was " + maxWidth);
        }
        if (maxWidth < minWidth) {
            throw new IllegalArgumentException(
                    "Maximum width must exceed or equal the minimum width but " + maxWidth + " < " + minWidth);
        }
        Step.Fraction fraction = new Step.Fraction(field, minWidth, maxWidth, decimalPoint);
        if (minWidth == maxWidth && !decimalPoint && padWidth == 0) {
            // digits of a fixed count right after a number are read as one of its run;
            // with no run before them, they begin one
            List<Step> open = open();
            if (run >= 0) {
                if (open.get(run) instanceof Step.Digits first) {
                    open.set(run, first.reserving(maxWidth));
                }
                open.add(fraction);
                return this;
            }
            open.add(fraction);
            run = open.size() - 1;
            return this;
        }
        return add(fraction);
    }

    public DateTimeFormatterBuilder appendText(TemporalField field) {
        return appendText(field, TextStyle.FULL);
    }

    public DateTimeFormatterBuilder appendText(TemporalField field, TextStyle textStyle) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(textStyle, "textStyle");
        return add(new Step.Words(field, textStyle, null, null));
    }

    public DateTimeFormatterBuilder appendText(TemporalField field, Map<Long, String> textLookup) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(textLookup, "textLookup");
        String[] words = new String[textLookup.size()];
        long[] values = new long[textLookup.size()];
        int i = 0;
        for (Map.Entry<Long, String> entry : textLookup.entrySet()) {
            values[i] = entry.getKey();
            words[i] = entry.getValue();
            i++;
        }
        return add(new Step.Words(field, TextStyle.FULL, words, values));
    }

    /** Words of the field's values from its least, the same in every style. */
    DateTimeFormatterBuilder appendWords(TemporalField field, String[] words) {
        long[] values = new long[words.length];
        for (int i = 0; i < words.length; i++) {
            values[i] = field.range().getMinimum() + i;
        }
        return add(new Step.Words(field, TextStyle.FULL, words, values));
    }

    public DateTimeFormatterBuilder appendInstant() {
        return add(new Step.Moment(-2));
    }

    public DateTimeFormatterBuilder appendInstant(int fractionalDigits) {
        if (fractionalDigits < -1 || fractionalDigits > 9) {
            throw new IllegalArgumentException(
                    "The fractional digits must be from -1 to 9 inclusive but was " + fractionalDigits);
        }
        return add(new Step.Moment(fractionalDigits));
    }

    public DateTimeFormatterBuilder appendOffsetId() {
        return add(new Step.Offset("+HH:MM:ss", "Z"));
    }

    public DateTimeFormatterBuilder appendOffset(String pattern, String noOffsetText) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(noOffsetText, "noOffsetText");
        return add(new Step.Offset(pattern, noOffsetText));
    }

    public DateTimeFormatterBuilder appendLocalizedOffset(TextStyle style) {
        Objects.requireNonNull(style, "style");
        if (style != TextStyle.FULL && style != TextStyle.SHORT) {
            throw new IllegalArgumentException("Style must be either full or short");
        }
        return add(new Step.GmtOffset(style == TextStyle.FULL));
    }

    public DateTimeFormatterBuilder appendZoneId() {
        return add(new Step.ZoneName(0));
    }

    public DateTimeFormatterBuilder appendZoneText(TextStyle textStyle) {
        Objects.requireNonNull(textStyle, "textStyle");
        return add(new Step.ZoneText(textStyle, false, null));
    }

    public DateTimeFormatterBuilder appendZoneText(TextStyle textStyle, Set<ZoneId> preferredZones) {
        Objects.requireNonNull(textStyle, "textStyle");
        Objects.requireNonNull(preferredZones, "preferredZones");
        return add(new Step.ZoneText(textStyle, false, Set.copyOf(preferredZones)));
    }

    public DateTimeFormatterBuilder appendGenericZoneText(TextStyle textStyle) {
        Objects.requireNonNull(textStyle, "textStyle");
        return add(new Step.ZoneText(textStyle, true, null));
    }

    /** A zone's name as a whole; null preferred zones are none (a JDK 25's answer). */
    public DateTimeFormatterBuilder appendGenericZoneText(TextStyle textStyle, Set<ZoneId> preferredZones) {
        Objects.requireNonNull(textStyle, "textStyle");
        return add(new Step.ZoneText(textStyle, true, preferredZones == null ? null : Set.copyOf(preferredZones)));
    }

    public DateTimeFormatterBuilder appendDayPeriodText(TextStyle style) {
        Objects.requireNonNull(style, "style");
        TextStyle normal = style.asNormal();
        return add(new Step.DayPeriod(normal == TextStyle.FULL ? 0 : normal == TextStyle.SHORT ? 1 : 2));
    }

    /**
     * A localized form, the pattern of its styles in the formatter's locale: the compiler
     * notes a zone's name in words where the time's style can be full or long, then
     * calls localized.
     */
    public native DateTimeFormatterBuilder appendLocalized(FormatStyle dateStyle, FormatStyle timeStyle);

    static DateTimeFormatterBuilder localized(DateTimeFormatterBuilder builder, FormatStyle dateStyle,
            FormatStyle timeStyle) {
        if (dateStyle == null && timeStyle == null) {
            throw new IllegalArgumentException("Either the date or time style must be non-null");
        }
        return builder.add(new Step.Styled(dateStyle, timeStyle));
    }

    public static String getLocalizedDateTimePattern(FormatStyle dateStyle, FormatStyle timeStyle, Chronology chrono,
            Locale locale) {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(chrono, "chrono");
        if (dateStyle == null && timeStyle == null) {
            throw new IllegalArgumentException("Either dateStyle or timeStyle must be non-null");
        }
        if (Texts.ownCalendars()) {
            Texts.typed(chrono, "requestID");
        }
        int kind = Texts.kind(locale);
        return Texts.pattern(rontolisp.internal.Locales.foreign() ? Texts.words(kind, locale) : kind, dateStyle,
                timeStyle);
    }

    /**
     * A localized form of a requested template, its pattern found in the formatter's
     * locale where it writes or reads (Skeletons): the compiler notes the letters its
     * patterns can hold, then calls templated.
     */
    public native DateTimeFormatterBuilder appendLocalized(String requestedTemplate);

    static DateTimeFormatterBuilder templated(DateTimeFormatterBuilder builder, String requestedTemplate) {
        Objects.requireNonNull(requestedTemplate, "requestedTemplate");
        if (!Skeletons.valid(requestedTemplate)) {
            throw new IllegalArgumentException("Requested template is invalid: " + requestedTemplate);
        }
        return builder.add(new Step.Templated(requestedTemplate));
    }

    /**
     * The pattern of a requested template in a locale (Skeletons). A refusal of an
     * invalid template shows it with its j and C made the locale's hour.
     */
    public static String getLocalizedDateTimePattern(String requestedTemplate, Chronology chrono, Locale locale) {
        Objects.requireNonNull(requestedTemplate, "requestedTemplate");
        Objects.requireNonNull(chrono, "chrono");
        Objects.requireNonNull(locale, "locale");
        int kind = Texts.kind(locale);
        if (rontolisp.internal.Locales.foreign()) {
            Texts.words(kind, locale);
        }
        if (!Skeletons.valid(requestedTemplate)) {
            throw new IllegalArgumentException("Requested template \"" + requestedTemplate + "\" is invalid"
                    + (requestedTemplate.indexOf('j') < 0 && requestedTemplate.indexOf('C') < 0 ? "."
                            : ", which translated into \"" + Skeletons.shown(requestedTemplate, Texts.hour(kind))
                                    + "\" after the 'j' or 'C' substitution."));
        }
        if (Texts.ownCalendars()) {
            Texts.typed(chrono, "calType");
        }
        String pattern = Skeletons.pattern(kind, requestedTemplate);
        if (pattern == null) {
            throw Skeletons.unresolved(requestedTemplate, locale);
        }
        return pattern;
    }

    /** A pattern's parts as one group, which a localized form writes and reads through. */
    static Step.Group parts(String pattern) {
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
        Patterns.read(builder, pattern);
        return new Step.Group(builder.open().toArray(new Step[0]), false);
    }

    public DateTimeFormatterBuilder appendZoneRegionId() {
        return add(new Step.ZoneName(2));
    }

    public DateTimeFormatterBuilder appendZoneOrOffsetId() {
        return add(new Step.ZoneName(1));
    }

    public DateTimeFormatterBuilder appendLiteral(char literal) {
        return add(new Step.Literal(String.valueOf(literal)));
    }

    public DateTimeFormatterBuilder appendLiteral(String literal) {
        Objects.requireNonNull(literal, "literal");
        if (literal.isEmpty()) {
            return this;
        }
        return add(new Step.Literal(literal));
    }

    public DateTimeFormatterBuilder append(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return add(new Step.Group(formatter.root.steps, false));
    }

    public DateTimeFormatterBuilder appendOptional(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return add(new Step.Group(formatter.root.steps, true));
    }

    public DateTimeFormatterBuilder padNext(int padWidth) {
        return padNext(padWidth, ' ');
    }

    public DateTimeFormatterBuilder padNext(int padWidth, char padChar) {
        if (padWidth < 1) {
            throw new IllegalArgumentException("The pad width must be at least one but was " + padWidth);
        }
        this.padWidth = padWidth;
        this.padChar = padChar;
        run = -1;
        return this;
    }

    public DateTimeFormatterBuilder optionalStart() {
        run = -1;
        groups.add(new ArrayList<>());
        pads.add(new int[] {padWidth, padChar});
        padWidth = 0;
        return this;
    }

    public DateTimeFormatterBuilder optionalEnd() {
        if (groups.size() == 1) {
            throw new IllegalStateException(
                    "Cannot call optionalEnd() as there was no previous call to optionalStart()");
        }
        List<Step> steps = groups.remove(groups.size() - 1);
        int[] pad = pads.remove(pads.size() - 1);
        run = -1;
        if (!steps.isEmpty()) {
            padWidth = pad[0];
            padChar = (char) pad[1];
            append0(new Step.Group(steps.toArray(new Step[0]), true));
        }
        return this;
    }

    /** A pattern's parts: the compiler notes the letters of a pattern it sees, then calls appended. */
    public native DateTimeFormatterBuilder appendPattern(String pattern);

    static DateTimeFormatterBuilder appended(DateTimeFormatterBuilder builder, String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        Patterns.read(builder, pattern);
        return builder;
    }

    public DateTimeFormatter toFormatter() {
        return toFormatter(Locale.getDefault());
    }

    public DateTimeFormatter toFormatter(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return formatter(locale, ResolverStyle.SMART, null);
    }

    /** The formatter of the parts appended, of a calendar or of none. */
    DateTimeFormatter formatter(Locale locale, ResolverStyle style, Chronology chronology) {
        while (groups.size() > 1) {
            optionalEnd();
        }
        return new DateTimeFormatter(new Step.Group(open().toArray(new Step[0]), false), locale, null, style, null,
                null, chronology);
    }
}
