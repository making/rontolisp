// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// One part of a formatter: it writes its part of a date or a time and reads it back.
// Reading answers where the part ends, or the complement of the index where the text is
// no such part. How each part reads what the documentation leaves open -- the digits a
// number takes beside its neighbours, which sign it takes in which mode, the index a
// refusal names, which of two equal words wins -- are a JDK 25's answers to a grid
// (.kb/java-frontend.md, "java.time: the formatter"). A part's text is the one a JDK's
// formatter shows.
package java.time.format;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.ValueRange;
import java.time.temporal.WeekFields;
import java.time.zone.ZoneRules;
import java.util.Locale;
import java.util.Set;

abstract class Step {

    /** Writes this part of the value; false where an optional part has to write nothing. */
    abstract boolean print(Writing out);

    /** Reads this part at a position: where it ends, or the complement of where it failed. */
    abstract int parse(Reading in, int at);

    /** A zone by its name alone, or null: ZoneId's own lookup, which notes no name. */
    static native ZoneId region0(String id);

    /**
     * Whether the program holds a region of the tz database: answered by the compiler,
     * and the statement it decides is all that is compiled of it (ZoneId.regional()).
     */
    static native boolean regional();

    /** The names of the regions the program holds: the table's own, which notes none. */
    static native String[] held0();

    /**
     * Whether the program makes the part that writes and reads a zone's name in words:
     * answered by the compiler, so that a program reading zones by their ids alone has
     * nothing of the names.
     */
    static native boolean zoneTexts();

    /**
     * Whether the program makes the part of a requested template: answered by the
     * compiler, so that no other program hands its parts the formatter's locale.
     */
    static native boolean templated();

    /** The names of the zones every program knows, the regions aside: UT, UTC, GMT and their kin. */
    private static final String[] FIXED = {"UT", "UTC", "GMT", "UCT", "GMT0", "Greenwich", "Universal", "Zulu"};

    static String quoted(String text) {
        StringBuilder quoted = new StringBuilder("'");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            quoted.append(c);
            if (c == '\'') {
                quoted.append('\'');
            }
        }
        return quoted.append('\'').toString();
    }

    /** The ASCII digits standing at a position, counted as far as a limit: an offset's. */
    static int digits(Reading in, int at, int limit) {
        int count = 0;
        while (count < limit && in.digit(at + count)) {
            count++;
        }
        return count;
    }

    static long number(Reading in, int from, int count) {
        long value = 0;
        for (int i = from; i < from + count; i++) {
            value = value * 10 + (in.text.charAt(i) - '0');
        }
        return value;
    }

    /** The digits of the decimal style standing at a position, counted as far as a limit: a number's. */
    static int numerals(Reading in, int at, int limit) {
        int count = 0;
        while (count < limit && in.numeral(at + count) >= 0) {
            count++;
        }
        return count;
    }

    /** A text written as it is. */
    static final class Literal extends Step {

        final String text;

        Literal(String text) {
            this.text = text;
        }

        boolean print(Writing out) {
            out.text.append(text);
            return true;
        }

        int parse(Reading in, int at) {
            return in.matches(at, text) ? at + text.length() : ~at;
        }

        public String toString() {
            return text.equals("'") ? "''" : quoted(text);
        }
    }

    /**
     * A field written as its number: as many digits as it has, at least the least
     * width and at most the most, with a sign as its style says. A run of numbers with
     * no text between them is read by keeping, for the fixed widths after its first
     * number, the digits they take (reserved); one of those reads its width exactly.
     * Leniently a number reads from one digit to nine and those it keeps, but within its
     * widths where it is a run's fixed-width first or follows, or where a number of no
     * fixed width came right after its run (ended: then it keeps none). JDK 25's answers.
     */
    static class Digits extends Step {

        final TemporalField field;

        final int least;

        final int most;

        final SignStyle sign;

        /** The digits the fixed-width numbers after this one take. */
        final int reserved;

        /** Whether this follows a number with no text between: its width, no sign. */
        final boolean follows;

        /** Whether a number of no fixed width came right after this one's run. */
        final boolean ended;

        Digits(TemporalField field, int least, int most, SignStyle sign, int reserved, boolean follows,
                boolean ended) {
            this.field = field;
            this.least = least;
            this.most = most;
            this.sign = sign;
            this.reserved = reserved;
            this.follows = follows;
            this.ended = ended;
        }

        /** The same number keeping more digits for the numbers after it. */
        Digits reserving(int width) {
            return new Digits(field, least, most, sign, reserved + width, follows, ended);
        }

        /** The same number once a number of no fixed width comes right after its run. */
        Digits ending() {
            return new Digits(field, least, most, sign, reserved, follows, true);
        }

        /** The same number as one following another in a run. */
        Digits following() {
            return new Digits(field, least, most, sign, 0, true, ended);
        }

        boolean fixed() {
            return least == most && sign == SignStyle.NOT_NEGATIVE;
        }

        boolean print(Writing out) {
            if (!out.has(field)) {
                return false;
            }
            long value = out.value(field);
            String digits = value == Long.MIN_VALUE ? "9223372036854775808" : Long.toString(Math.abs(value));
            if (digits.length() > most) {
                throw new DateTimeException("Field " + field.toString() + " cannot be printed as the value " + value
                        + " exceeds the maximum print width of " + most);
            }
            StringBuilder text = out.text;
            int mark = text.length();
            if (value >= 0) {
                if (sign == SignStyle.ALWAYS || (sign == SignStyle.EXCEEDS_PAD && digits.length() > least)) {
                    text.append('+');
                }
            }
            else if (sign == SignStyle.NOT_NEGATIVE) {
                throw new DateTimeException("Field " + field.toString() + " cannot be printed as the value " + value
                        + " cannot be negative according to the SignStyle");
            }
            else if (sign != SignStyle.NEVER) {
                text.append('-');
            }
            for (int i = digits.length(); i < least; i++) {
                text.append('0');
            }
            text.append(digits);
            if (DateTimeFormatter.decimals()) {
                out.localize(mark);
            }
            return true;
        }

        /** Whether a sign is read: strictly as the style says, leniently unless the width is fixed. */
        private boolean signed(boolean plus, boolean strict) {
            if (!strict) {
                return least != most || (sign != SignStyle.NOT_NEGATIVE && sign != SignStyle.NEVER);
            }
            return plus ? sign == SignStyle.ALWAYS || sign == SignStyle.EXCEEDS_PAD
                    : sign == SignStyle.NORMAL || sign == SignStyle.ALWAYS || sign == SignStyle.EXCEEDS_PAD;
        }

        /** The value of the digits read, signed: a reduced number makes it a whole one. */
        long value(long read, int count, boolean negative) {
            return read;
        }

        int parse(Reading in, int at) {
            int start = at;
            if (at >= in.length) {
                return ~at;
            }
            char c = in.text.charAt(at);
            char plusSign = '+';
            char minusSign = '-';
            if (DateTimeFormatter.decimals()) {
                if (in.decimals != null) {
                    plusSign = in.decimals.getPositiveSign();
                    minusSign = in.decimals.getNegativeSign();
                }
            }
            // a sign is the decimal style's, the positive one first where the two are one
            boolean plus = c == plusSign;
            boolean minus = !plus && c == minusSign;
            if (plus || minus) {
                if (follows || !signed(plus, in.strict)) {
                    return ~at;
                }
                at++;
            }
            else if (in.strict && !follows && sign == SignStyle.ALWAYS) {
                return ~at;
            }
            boolean exact = in.strict || follows || ended || (fixed() && reserved > 0);
            int low = exact ? least : 1;
            int high = exact ? most : 9;
            int kept = ended ? 0 : reserved;
            int available = numerals(in, at, high + kept);
            int count = Math.min(high, Math.max(low, available - kept));
            if (available < count) {
                return ~at;
            }
            // the digits, kept negative so that the least long is one: as many as fit
            long limit = minus ? Long.MIN_VALUE : -Long.MAX_VALUE;
            long read = 0;
            for (int i = 0; i < count; i++) {
                int digit = in.numeral(at + i);
                if (read < (limit + digit) / 10) {
                    count = i;
                    break;
                }
                read = read * 10 - digit;
            }
            if (in.strict && !follows) {
                if (sign == SignStyle.EXCEEDS_PAD && (plus ? count <= least : !minus && count > least)) {
                    return ~start;
                }
                if (minus && read == 0) {
                    return ~start;
                }
            }
            return in.put(field, value(minus ? read : -read, count, minus), start, at + count);
        }

        public String toString() {
            if (least == 1 && most == 19 && sign == SignStyle.NORMAL) {
                return "Value(" + field.toString() + ")";
            }
            if (least == most && sign == SignStyle.NOT_NEGATIVE) {
                return "Value(" + field.toString() + "," + least + ")";
            }
            return "Value(" + field.toString() + "," + least + "," + most + "," + sign.name() + ")";
        }
    }

    /**
     * A number written in its last digits: a value within the hundred years (or the
     * power of ten the width makes) from the base is written in the width, any other in
     * its last digits up to the most; read in the width, the digits are the value nearest
     * after the base.
     */
    static final class Reduced extends Digits {

        final long base;

        final LocalDate baseDate;

        Reduced(TemporalField field, int width, int most, long base, LocalDate baseDate, int reserved,
                boolean follows, boolean ended) {
            super(field, width, most, SignStyle.NOT_NEGATIVE, reserved, follows, ended);
            this.base = base;
            this.baseDate = baseDate;
        }

        Digits reserving(int width) {
            return new Reduced(field, least, most, base, baseDate, reserved + width, follows, ended);
        }

        Digits ending() {
            return new Reduced(field, least, most, base, baseDate, reserved, follows, true);
        }

        Digits following() {
            return new Reduced(field, least, most, base, baseDate, 0, true, ended);
        }

        private long range() {
            long range = 1;
            for (int i = 0; i < least; i++) {
                range *= 10;
            }
            return range;
        }

        boolean print(Writing out) {
            if (!out.has(field)) {
                return false;
            }
            long value = out.value(field);
            long range = range();
            long shown;
            if (value >= base && value < base + range) {
                shown = Math.floorMod(value, range);
            }
            else {
                long most = 1;
                for (int i = 0; i < this.most; i++) {
                    most *= 10;
                }
                shown = Math.abs(value % most);
            }
            String digits = Long.toString(shown);
            int mark = out.text.length();
            for (int i = digits.length(); i < least; i++) {
                out.text.append('0');
            }
            out.text.append(digits);
            if (DateTimeFormatter.decimals()) {
                out.localize(mark);
            }
            return true;
        }

        long value(long read, int count, boolean negative) {
            if (count != least || negative) {
                return read;
            }
            long range = range();
            long last = Math.floorMod(base, range);
            long value = base - last + read;
            return read < last ? value + range : value;
        }

        public String toString() {
            return "ReducedValue(" + field.toString() + "," + least + "," + most + ","
                    + (baseDate != null ? baseDate.toString() : Long.toString(base)) + ")";
        }
    }

    /**
     * A number of a field of the weeks of the formatter's locale (WeekFields.of): a
     * pattern's Y, w, W, e and c, made the number of that locale's field where it is
     * written or read. It is one of a run of numbers as any number is.
     */
    static final class Localized extends Digits {

        /** The pattern's letter and how many of it. */
        final char letter;

        final int count;

        Localized(char letter, int count, int least, int most, SignStyle sign, int reserved, boolean follows,
                boolean ended) {
            super(null, least, most, sign, reserved, follows, ended);
            this.letter = letter;
            this.count = count;
        }

        Digits reserving(int width) {
            return new Localized(letter, count, least, most, sign, reserved + width, follows, ended);
        }

        Digits ending() {
            return new Localized(letter, count, least, most, sign, reserved, follows, true);
        }

        Digits following() {
            return new Localized(letter, count, least, most, sign, 0, true, ended);
        }

        private static TemporalField field(WeekFields weeks, char letter) {
            return letter == 'Y' ? weeks.weekBasedYear() : letter == 'w' ? weeks.weekOfWeekBasedYear()
                    : letter == 'W' ? weeks.weekOfMonth() : weeks.dayOfWeek();
        }

        /** The number of the locale's field: the weeks of the United Kingdom's, or of the others. */
        private Digits of(int texts) {
            TemporalField field = field(texts == 2 ? WeekFields.ISO : WeekFields.SUNDAY_START, letter);
            if (letter == 'Y' && count == 2) {
                LocalDate base = LocalDate.of(2000, 1, 1);
                return new Reduced(field, 2, 2, base.getLong(field), base, reserved, follows, ended);
            }
            return new Digits(field, least, most, sign, reserved, follows, ended);
        }

        /** The number of the field of a locale's weeks: its region's, in a program that can make any locale. */
        private Digits of(Locale locale) {
            TemporalField field = field(WeekFields.of(locale), letter);
            if (letter == 'Y' && count == 2) {
                LocalDate base = LocalDate.of(2000, 1, 1);
                return new Reduced(field, 2, 2, base.getLong(field), base, reserved, follows, ended);
            }
            return new Digits(field, least, most, sign, reserved, follows, ended);
        }

        boolean print(Writing out) {
            return (rontolisp.internal.Locales.foreign() ? of(out.locale) : of(out.texts)).print(out);
        }

        int parse(Reading in, int at) {
            return (rontolisp.internal.Locales.foreign() ? of(in.locale) : of(in.texts)).parse(in, at);
        }

        public String toString() {
            String name = letter == 'Y' ? "WeekBasedYear" : letter == 'w' ? "WeekOfWeekBasedYear"
                    : letter == 'W' ? "WeekOfMonth" : "DayOfWeek";
            if (letter != 'Y') {
                return "Localized(" + name + "," + count + ")";
            }
            if (count == 2) {
                return "Localized(ReducedValue(" + name + ",2,2,2000-01-01))";
            }
            return "Localized(" + name + (count == 1 ? "" : "," + count + ",19," + sign.name()) + ")";
        }
    }

    /** A fraction of a field's range, in digits after a point. */
    static final class Fraction extends Step {

        final TemporalField field;

        final int least;

        final int most;

        final boolean point;

        Fraction(TemporalField field, int least, int most, boolean point) {
            this.field = field;
            this.least = least;
            this.most = most;
            this.point = point;
        }

        boolean print(Writing out) {
            if (!out.has(field)) {
                return false;
            }
            ValueRange range = field.range();
            long span = range.getMaximum() - range.getMinimum() + 1;
            long rest = range.checkValidValue(out.value(field), field) - range.getMinimum();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < most; i++) {
                rest *= 10;
                digits.append((char) ('0' + rest / span));
                rest %= span;
            }
            int length = most;
            while (length > least && digits.charAt(length - 1) == '0') {
                length--;
            }
            if (length > 0) {
                int mark = out.text.length();
                if (point) {
                    out.text.append('.');
                }
                out.text.append(digits, 0, length);
                if (DateTimeFormatter.decimals()) {
                    out.localize(mark);
                }
            }
            return true;
        }

        int parse(Reading in, int at) {
            int low = in.strict ? least : 0;
            int high = in.strict ? most : 9;
            int start = at;
            char separator = '.';
            if (DateTimeFormatter.decimals()) {
                if (in.decimals != null) {
                    separator = in.decimals.getDecimalSeparator();
                }
            }
            if (point ? at >= in.length || in.text.charAt(at) != separator : at >= in.length) {
                // no point, or the text's end: no field (a JDK's answer)
                return low > 0 ? ~at : at;
            }
            if (point) {
                at++;
            }
            int count = numerals(in, at, high);
            if (count < low) {
                return ~at;
            }
            ValueRange range = field.range();
            long scale = 1;
            long read = 0;
            for (int i = 0; i < count; i++) {
                scale *= 10;
                read = read * 10 + in.numeral(at + i);
            }
            long value = range.getMinimum() + read * (range.getMaximum() - range.getMinimum() + 1) / scale;
            return in.put(field, value, start, at + count);
        }

        public String toString() {
            return "Fraction(" + field.toString() + "," + least + "," + most + (point ? ",DecimalPoint" : "") + ")";
        }
    }

    /**
     * A field written as a word: the locale's words for its values in a style, or the
     * words a program gives. A value with no word is a number. Read, the longest word
     * standing there wins, of two equal ones the later value; leniently the words of
     * every style are read, then a number.
     */
    static final class Words extends Step {

        final TemporalField field;

        final TextStyle style;

        /** The words a program gave and the values they stand for, or null. */
        final String[] given;

        final long[] givenValues;

        Words(TemporalField field, TextStyle style, String[] given, long[] givenValues) {
            this.field = field;
            this.style = style;
            this.given = given;
            this.givenValues = givenValues;
        }

        private Digits number() {
            return new Digits(field, 1, 19, SignStyle.NORMAL, 0, false, false);
        }

        boolean print(Writing out) {
            if (!out.has(field)) {
                return false;
            }
            if (Texts.ownCalendars()) {
                if (given == null && field == ChronoField.ERA && !Texts.eraWords(out.calendar())) {
                    return number().print(out);
                }
            }
            long value = out.value(field);
            String word = null;
            if (given != null) {
                for (int i = 0; i < given.length; i++) {
                    if (givenValues[i] == value) {
                        word = given[i];
                    }
                }
            }
            else {
                String[] words = Texts.of(
                        rontolisp.internal.Locales.foreign() ? Texts.words(out.texts, out.locale) : out.texts, field,
                        style);
                long least = field.range().getMinimum();
                if (words != null && value >= least && value - least < words.length) {
                    word = words[(int) (value - least)];
                }
            }
            if (word == null) {
                return number().print(out);
            }
            out.text.append(word);
            return true;
        }

        /** The value of the longest word at a position and where it ends, packed; -1 for none. */
        private long[] best(Reading in, int at, String[] words, long first, long[] values, long[] found) {
            if (words == null) {
                return found;
            }
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                if (word.length() >= found[1] && in.matches(at, word)) {
                    found[0] = values != null ? values[i] : first + i;
                    found[1] = word.length();
                }
            }
            return found;
        }

        int parse(Reading in, int at) {
            if (at > in.length) {
                return ~at;
            }
            if (Texts.ownCalendars()) {
                if (given == null && field == ChronoField.ERA && !Texts.eraWords(in.chronology)) {
                    return number().parse(in, at);
                }
            }
            long[] found = {0, 0};
            boolean any;
            if (given != null) {
                best(in, at, given, 0, givenValues, found);
                any = true;
            }
            else {
                long first = field.range().getMinimum();
                if (in.strict) {
                    String[] words = Texts.of(
                            rontolisp.internal.Locales.foreign() ? Texts.words(in.texts, in.locale) : in.texts, field,
                            style);
                    best(in, at, words, first, null, found);
                    any = words != null;
                }
                else {
                    String[] full = Texts.of(
                            rontolisp.internal.Locales.foreign() ? Texts.words(in.texts, in.locale) : in.texts, field,
                            TextStyle.FULL);
                    String[] brief = Texts.of(in.texts, field, TextStyle.SHORT);
                    String[] narrow = Texts.of(in.texts, field, TextStyle.NARROW);
                    best(in, at, full, first, null, found);
                    best(in, at, brief, first, null, found);
                    best(in, at, narrow, first, null, found);
                    if (field == ChronoField.ERA && in.texts != 0 && found[1] <= 2 && in.matches(at, "CE")) {
                        // English reads the root's CE too, but not its BCE (a JDK 25's answer)
                        found[0] = 1;
                        found[1] = 2;
                    }
                    any = full != null && in.strict;
                }
            }
            if (found[1] > 0) {
                return in.put(field, found[0], at, at + (int) found[1]);
            }
            return any ? ~at : number().parse(in, at);
        }

        public String toString() {
            return "Text(" + field.toString() + (style == TextStyle.FULL ? "" : "," + style.name()) + ")";
        }
    }

    /**
     * An offset as hours, minutes and seconds from a pattern: +HH or +H for the hours
     * with two digits or one, then the minutes and seconds, each MM where it is always
     * written, mm where only when not zero, ss and SS likewise, with or without colons.
     */
    static final class Offset extends Step {

        static final String[] PATTERNS = {"+HH", "+HHmm", "+HH:mm", "+HHMM", "+HH:MM", "+HHMMss", "+HH:MM:ss",
                "+HHMMSS", "+HH:MM:SS", "+HHmmss", "+HH:mm:ss", "+H", "+Hmm", "+H:mm", "+HMM", "+H:MM", "+HMMss",
                "+H:MM:ss", "+HMMSS", "+H:MM:SS", "+Hmmss", "+H:mm:ss"};

        final String pattern;

        final String none;

        final int kind;

        Offset(String pattern, String none) {
            this.pattern = pattern;
            this.none = none;
            int kind = -1;
            for (int i = 0; i < PATTERNS.length; i++) {
                if (PATTERNS[i].equals(pattern)) {
                    kind = i;
                }
            }
            if (kind < 0) {
                throw new IllegalArgumentException("Invalid zone offset pattern: " + pattern);
            }
            this.kind = kind;
        }

        private boolean oneDigitHour() {
            return kind >= 11;
        }

        private boolean colons() {
            return pattern.indexOf(':') >= 0;
        }

        /** 0 no minutes, 1 when not zero, 2 always, 3 when they or the seconds are not zero. */
        private int minutes() {
            int k = kind % 11;
            return k == 0 ? 0 : k <= 2 ? 1 : k >= 9 ? 3 : 2;
        }

        /** 0 no seconds, 1 when not zero, 2 always. */
        private int seconds() {
            int k = kind % 11;
            return k == 5 || k == 6 || k >= 9 ? 1 : k == 7 || k == 8 ? 2 : 0;
        }

        boolean print(Writing out) {
            if (!out.has(ChronoField.OFFSET_SECONDS)) {
                return false;
            }
            int total = Math.toIntExact(out.value(ChronoField.OFFSET_SECONDS));
            int all = Math.abs(total);
            int hours = all / 3600;
            int minutes = all / 60 % 60;
            int seconds = all % 60;
            int m = minutes();
            int s = seconds();
            boolean showSeconds = s == 2 || (s == 1 && seconds > 0);
            boolean showMinutes = m == 2 || (m == 1 && minutes > 0) || (m == 3 && (minutes > 0 || seconds > 0));
            if (m == 3 && !showMinutes) {
                showSeconds = false;
            }
            if (hours == 0 && (!showMinutes || minutes == 0) && (!showSeconds || seconds == 0)) {
                out.text.append(none);
                return true;
            }
            StringBuilder text = out.text;
            text.append(total < 0 ? '-' : '+');
            if (hours < 10 && !oneDigitHour()) {
                text.append('0');
            }
            text.append(hours);
            String colon = colons() ? ":" : "";
            if (showMinutes) {
                text.append(colon).append(minutes < 10 ? "0" : "").append(minutes);
                if (showSeconds) {
                    text.append(colon).append(seconds < 10 ? "0" : "").append(seconds);
                }
            }
            return true;
        }

        /** Two digits below sixty at a position, behind a colon where there is one: their value, or -1. */
        private static int pair(Reading in, int at, boolean colon) {
            int from = colon ? at + 1 : at;
            if (colon && (at >= in.length || in.text.charAt(at) != ':')) {
                return -1;
            }
            if (digits(in, from, 2) < 2) {
                return -1;
            }
            int value = (int) number(in, from, 2);
            return value < 60 ? value : -1;
        }

        /** Two digits at a position, behind a colon where it has one: their value, or -1. */
        private static int digitPair(Reading in, int at, boolean colon) {
            int from = colon ? at + 1 : at;
            if ((colon && (at >= in.length || in.text.charAt(at) != ':')) || digits(in, from, 2) < 2) {
                return -1;
            }
            return (int) number(in, from, 2);
        }

        int parse(Reading in, int at) {
            if (!none.isEmpty() && in.matches(at, none)) {
                return in.put(ChronoField.OFFSET_SECONDS, 0, at, at + none.length());
            }
            int read = signed(in, at);
            // with no text for a zero offset, a text that is no offset is one, and takes nothing
            return read < 0 && none.isEmpty() ? in.put(ChronoField.OFFSET_SECONDS, 0, at, at) : read;
        }

        private int signed(Reading in, int at) {
            if (at >= in.length || (in.text.charAt(at) != '+' && in.text.charAt(at) != '-')) {
                return ~at;
            }
            boolean negative = in.text.charAt(at) == '-';
            int position = at + 1;
            boolean lenient = !in.strict;
            // leniently the minutes and the seconds may each be there or not, and a
            // pattern of the hours alone takes them with colons or without
            int m = lenient ? 1 : minutes();
            int s = lenient ? 1 : seconds();
            int run = digits(in, position, 6);
            boolean colon = colons() || (lenient && minutes() == 0 && run <= 2 && position + run < in.length
                    && in.text.charAt(position + run) == ':');
            int hours;
            int minutes = 0;
            int seconds = 0;
            if (oneDigitHour()) {
                // an hour of one digit or two: the digits after it say which
                if (run == 0) {
                    return ~at;
                }
                boolean colons = colon;
                // without colons the digits the pattern can take, odd or even, say it
                int taken = Math.min(run, 2 + (m > 0 ? 2 : 0) + (s > 0 ? 2 : 0));
                int hourDigits = colons ? Math.min(run, 2) : taken % 2 == 1 ? 1 : 2;
                hours = (int) number(in, position, hourDigits);
                position += hourDigits;
                if (m > 0) {
                    int value = colons ? pair(in, position, true) : digitPair(in, position, false);
                    if (value >= 0) {
                        minutes = value;
                        position += colons ? 3 : 2;
                        if (s > 0) {
                            int second = colons ? pair(in, position, true) : digitPair(in, position, false);
                            if (second >= 0) {
                                seconds = second;
                                position += colons ? 3 : 2;
                            }
                            else if (s == 2) {
                                return ~at;
                            }
                        }
                    }
                    else if (m == 2) {
                        return ~at;
                    }
                }
                if (hours > 23 || minutes > 59 || seconds > 59) {
                    throw new DateTimeException("Value out of range: Hour[0-23], Minute[0-59], Second[0-59]");
                }
            }
            else {
                if (digits(in, position, 2) < 2) {
                    return ~at;
                }
                hours = (int) number(in, position, 2);
                if (hours >= 60) {
                    return ~at;
                }
                position += 2;
                if (m > 0) {
                    int value = pair(in, position, colon);
                    if (value >= 0) {
                        minutes = value;
                        position += colon ? 3 : 2;
                        if (s > 0) {
                            int second = pair(in, position, colon);
                            if (second >= 0) {
                                seconds = second;
                                position += colon ? 3 : 2;
                            }
                            else if (s == 2) {
                                return ~at;
                            }
                        }
                    }
                    else if (m == 2) {
                        return ~at;
                    }
                }
                if (hours > 23) {
                    throw new DateTimeException("Value out of range: Hour[0-23], Minute[0-59], Second[0-59]");
                }
            }
            int total = hours * 3600 + minutes * 60 + seconds;
            return in.put(ChronoField.OFFSET_SECONDS, negative ? -total : total, at, position);
        }

        public String toString() {
            return "Offset(" + pattern + "," + quoted(none) + ")";
        }
    }

    /**
     * An offset as GMT and the hours, minutes and seconds from it, in full or short. Read
     * as a JDK 25 reads it: no part is checked against its range; in full the hours and
     * the minutes are two digits each, read a pair at a time where the text may already
     * have ended (an index error, as a JDK's), the seconds only where they stand whole;
     * short, an hour of one digit or two and the minutes and seconds where they stand.
     */
    static final class GmtOffset extends Step {

        final boolean full;

        GmtOffset(boolean full) {
            this.full = full;
        }

        boolean print(Writing out) {
            if (!out.has(ChronoField.OFFSET_SECONDS)) {
                return false;
            }
            int total = Math.toIntExact(out.value(ChronoField.OFFSET_SECONDS));
            StringBuilder text = out.text.append("GMT");
            if (total == 0) {
                return true;
            }
            int all = Math.abs(total);
            int hours = all / 3600;
            int minutes = all / 60 % 60;
            int seconds = all % 60;
            text.append(total < 0 ? '-' : '+');
            if (full && hours < 10) {
                text.append('0');
            }
            text.append(hours);
            if (full || minutes > 0 || seconds > 0) {
                text.append(':').append(minutes < 10 ? "0" : "").append(minutes);
                if (seconds > 0) {
                    text.append(':').append(seconds < 10 ? "0" : "").append(seconds);
                }
            }
            return true;
        }

        /** The character at an index, where the text may have ended: a JDK's index error. */
        private static char at(Reading in, int index) {
            if (index >= in.length) {
                throw new StringIndexOutOfBoundsException("Index " + index + " out of bounds for length " + in.length);
            }
            return in.text.charAt(index);
        }

        /** Two digits read at a position, the text's end an index error: their value, or -1. */
        private static int two(Reading in, int index) {
            char tens = at(in, index);
            char ones = at(in, index + 1);
            return tens >= '0' && tens <= '9' && ones >= '0' && ones <= '9' ? (tens - '0') * 10 + ones - '0' : -1;
        }

        /** A colon and two digits standing whole at a position: their value, or -1. */
        private static int part(Reading in, int index) {
            return index + 3 <= in.length && in.text.charAt(index) == ':' ? two(in, index + 1) : -1;
        }

        int parse(Reading in, int at) {
            if (!in.matches(at, "GMT")) {
                return ~at;
            }
            int position = at + 3;
            if (position >= in.length || (in.text.charAt(position) != '+' && in.text.charAt(position) != '-')) {
                return in.put(ChronoField.OFFSET_SECONDS, 0, at, position);
            }
            boolean negative = in.text.charAt(position) == '-';
            position++;
            int hours;
            int minutes = -1;
            if (full) {
                hours = two(in, position);
                if (hours < 0 || at(in, position + 2) != ':') {
                    return ~at;
                }
                minutes = two(in, position + 3);
                if (minutes < 0) {
                    return ~at;
                }
                position += 5;
            }
            else {
                char first = at(in, position);
                if (first < '0' || first > '9') {
                    return ~at;
                }
                hours = first - '0';
                position++;
                if (in.digit(position)) {
                    hours = hours * 10 + in.text.charAt(position++) - '0';
                }
                minutes = part(in, position);
                if (minutes >= 0) {
                    position += 3;
                }
            }
            int seconds = minutes >= 0 ? part(in, position) : -1;
            if (seconds >= 0) {
                position += 3;
            }
            int total = hours * 3600 + Math.max(minutes, 0) * 60 + Math.max(seconds, 0);
            return in.put(ChronoField.OFFSET_SECONDS, negative ? -total : total, at, position);
        }

        public String toString() {
            return "LocalizedOffset(" + (full ? "FULL" : "SHORT") + ")";
        }
    }

    /**
     * A zone by its name: ZoneId() writes any zone, ZoneOrOffsetId() an offset where the
     * value has no zone, ZoneRegionId() a zone that is no offset. All three read an
     * offset, UTC, GMT or UT with or without one behind it, or the longest name of a
     * zone the program knows.
     */
    static final class ZoneName extends Step {

        /** 0 ZoneId(), 1 ZoneOrOffsetId(), 2 ZoneRegionId(). */
        final int kind;

        ZoneName(int kind) {
            this.kind = kind;
        }

        boolean print(Writing out) {
            ZoneId zone = out.temporal.query(kind == 1 ? TemporalQueries.zone() : TemporalQueries.zoneId());
            if (kind == 2 && zone instanceof ZoneOffset) {
                zone = null;
            }
            if (zone == null) {
                if (out.optional > 0) {
                    return false;
                }
                throw new DateTimeException("Unable to extract ZoneId from temporal " + out.shown());
            }
            out.text.append(zone.getId());
            return true;
        }

        private static boolean nameChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '/' || c == '~'
                    || c == '.' || c == '_' || c == '+' || c == '-';
        }

        /**
         * An offset of hours and minutes, and seconds where they follow: where it ends, -1
         * where none stands there, -2 where it is past eighteen hours.
         */
        private static int offset(Reading in, int at, int[] seconds) {
            Reading probe = new Reading(in.text, in.texts);
            probe.length = in.length;
            int end = new Offset("+HH:MM:ss", "").parse(probe, at);
            if (end <= at) {
                return -1;
            }
            int total = (int) probe.values[probe.indexOf(ChronoField.OFFSET_SECONDS)];
            if (total < -64800 || total > 64800) {
                return -2;
            }
            seconds[0] = total;
            return end;
        }

        int parse(Reading in, int at) {
            return read(in, at, null);
        }

        /**
         * The longest name of a zone the program has standing at a position, its letters
         * compared as the reading compares them, longer than a length: a fixed one (UTC,
         * Etc/GMT+5) or a region it holds; or null. The regions are looked through once
         * per reading, where asking ZoneId for each length would read the program's table
         * at every name it lacks.
         */
        private static String longestId(Reading in, int at, int than) {
            String found = null;
            for (int etc = 0; etc < 2; etc++) {
                String area = etc == 0 ? "" : "Etc/";
                for (String name : FIXED) {
                    String id = area + name;
                    if (id.length() > than && (found == null || id.length() > found.length()) && !id.equals("Etc/UT")
                            && in.matches(at, id)) {
                        found = id;
                    }
                }
            }
            if (in.matches(at, "Etc/GMT")) {
                // Etc/GMT+5 and the like: the sign and the hours as the library has them
                for (int digits = 2; digits >= 1; digits--) {
                    String exact = at + 8 + digits <= in.length
                            ? "Etc/GMT" + in.text.subSequence(at + 7, at + 8 + digits).toString() : "";
                    if (exact.length() > than && (found == null || exact.length() > found.length())
                            && region0(exact) != null) {
                        found = exact;
                        break;
                    }
                }
            }
            // asked of the class by name: the question is answered where it is the
            // condition (JavaIntrinsics.answered)
            if (Step.regional()) {
                for (String id : held0()) {
                    if (id.length() > than && (found == null || id.length() > found.length()) && in.matches(at, id)) {
                        found = id;
                    }
                }
            }
            return found;
        }

        /**
         * Reads a zone: an offset, UTC, GMT or UT with or without an offset behind them, or
         * the longest name of a zone the program has; for a zone's name in words (text) the
         * longest of the names the program's zones are read by too, which beats a name of
         * a zone as long. A name read in words is noted with its time (Parsed resolves an
         * overlap by it), any other with none.
         */
        static int read(Reading in, int at, ZoneText text) {
            if (at >= in.length) {
                return ~at;
            }
            int[] seconds = {0};
            char c = in.text.charAt(at);
            if (c == '+' || c == '-') {
                int end = offset(in, at, seconds);
                if (end < 0) {
                    return ~at;
                }
                in.zone = ZoneOffset.ofTotalSeconds(seconds[0]);
                in.zoneTime = -1;
                return end;
            }
            int run = at;
            while (run < in.length && nameChar(in.text.charAt(run))) {
                run++;
            }
            String name = in.text.subSequence(at, run).toString();
            int prefix = name.startsWith("UTC") || name.startsWith("GMT") ? 3 : name.startsWith("UT") ? 2 : 0;
            if (prefix > 0 && name.length() > prefix
                    && (name.charAt(prefix) == '+' || name.charAt(prefix) == '-')) {
                int end = offset(in, at + prefix, seconds);
                if (end > 0) {
                    in.zone = ZoneId.ofOffset(name.substring(0, prefix), ZoneOffset.ofTotalSeconds(seconds[0]));
                    in.zoneTime = -1;
                    return end;
                }
                if (end == -2) {
                    // an offset past eighteen hours is no zone at all, not the prefix alone
                    return ~at;
                }
                in.zone = region0(name.substring(0, prefix));
                in.zoneTime = -1;
                return at + prefix;
            }
            String[] named = {null};
            int[] time = {-1};
            int words = 0;
            if (Step.zoneTexts()) {
                if (text != null && prefix == 0) {
                    boolean full = text.style.asNormal() == TextStyle.FULL;
                    words = ZoneNames.read(in, at, full, text.preferred, named, time);
                }
            }
            String found = longestId(in, at, words);
            if (found != null) {
                in.zone = region0(found);
                in.zoneTime = -1;
                return at + found.length();
            }
            if (words > 0) {
                in.zone = region0(named[0]);
                in.zoneTime = time[0];
                return at + words;
            }
            if (in.same(c, 'Z')) {
                in.zone = ZoneOffset.UTC;
                in.zoneTime = -1;
                return at + 1;
            }
            return ~at;
        }

        public String toString() {
            return kind == 0 ? "ZoneId()" : kind == 1 ? "ZoneOrOffsetId()" : "ZoneRegionId()";
        }
    }

    /**
     * An instant in ISO-8601's form at UTC: as an Instant writes itself, or with as many
     * digits of its fraction as asked (-1: as many as it has). Read with any offset, the
     * end of a day as the next one's start and a second 60 as 59.
     */
    static final class Moment extends Step {

        final int digits;

        Moment(int digits) {
            this.digits = digits;
        }

        boolean print(Writing out) {
            if (!out.has(ChronoField.INSTANT_SECONDS)) {
                return false;
            }
            long seconds = out.value(ChronoField.INSTANT_SECONDS);
            int nano = out.temporal.isSupported(ChronoField.NANO_OF_SECOND)
                    ? (int) out.value(ChronoField.NANO_OF_SECOND) : 0;
            String text = Instant.ofEpochSecond(seconds, nano).toString();
            if (digits == -2) {
                out.text.append(text);
                return true;
            }
            int point = text.indexOf('T') + 9;
            out.text.append(text, 0, point);
            String fraction = Long.toString(1000000000L + nano).substring(1);
            int length = digits >= 0 ? digits : 9;
            if (digits < 0) {
                while (length > 0 && fraction.charAt(length - 1) == '0') {
                    length--;
                }
            }
            if (length > 0) {
                out.text.append('.').append(fraction, 0, length);
            }
            out.text.append('Z');
            return true;
        }

        int parse(Reading in, int at) {
            // read in the formatter's decimal style, though it is written in the standard one
            Reading read = new Reading(in.text, in.texts);
            read.length = in.length;
            read.decimals = in.decimals;
            int end = form(digits).parse(read, at);
            if (end < 0) {
                return end;
            }
            long year = read.values[read.indexOf(ChronoField.YEAR)];
            int month = (int) read.values[read.indexOf(ChronoField.MONTH_OF_YEAR)];
            int day = (int) read.values[read.indexOf(ChronoField.DAY_OF_MONTH)];
            int hour = (int) read.values[read.indexOf(ChronoField.HOUR_OF_DAY)];
            int minute = (int) read.values[read.indexOf(ChronoField.MINUTE_OF_HOUR)];
            int second = (int) read.values[read.indexOf(ChronoField.SECOND_OF_MINUTE)];
            int nanoAt = read.indexOf(ChronoField.NANO_OF_SECOND);
            int nano = nanoAt < 0 ? 0 : (int) read.values[nanoAt];
            int offset = (int) read.values[read.indexOf(ChronoField.OFFSET_SECONDS)];
            long days = 0;
            boolean leap = false;
            if (hour == 24 && minute == 0 && second == 0 && nano == 0) {
                hour = 0;
                days = 1;
            }
            else if (hour == 23 && minute == 59 && second == 60) {
                second = 59;
                leap = true;
            }
            long epochSecond;
            try {
                // the calendar repeats every four hundred years, which keeps a year
                // past a date's in reach
                long day0 = LocalDate.of((int) Math.floorMod(year, 400L), month, day).toEpochDay()
                        + Math.floorDiv(year, 400L) * 146097L + days;
                ChronoField.HOUR_OF_DAY.checkValidValue(hour);
                ChronoField.MINUTE_OF_HOUR.checkValidValue(minute);
                ChronoField.SECOND_OF_MINUTE.checkValidValue(second);
                ChronoField.OFFSET_SECONDS.checkValidValue(offset);
                epochSecond = day0 * 86400L + hour * 3600L + minute * 60L + second - offset;
            }
            catch (DateTimeException invalid) {
                return ~at;
            }
            if (leap) {
                in.leapSecond = true;
            }
            int put = in.put(ChronoField.INSTANT_SECONDS, epochSecond, at, end);
            return put < 0 ? put : in.put(ChronoField.NANO_OF_SECOND, nano, at, end);
        }

        /** The form an instant is read in, a fraction of the digits asked. */
        private static Group form(int digits) {
            Step[] steps = {new Setting(1), new Digits(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD, 0, false, false),
                    new Literal("-"), new Digits(ChronoField.MONTH_OF_YEAR, 2, 2, SignStyle.NOT_NEGATIVE, 0, false, false),
                    new Literal("-"), new Digits(ChronoField.DAY_OF_MONTH, 2, 2, SignStyle.NOT_NEGATIVE, 0, false, false),
                    new Literal("T"), new Digits(ChronoField.HOUR_OF_DAY, 2, 2, SignStyle.NOT_NEGATIVE, 0, false, false),
                    new Literal(":"), new Digits(ChronoField.MINUTE_OF_HOUR, 2, 2, SignStyle.NOT_NEGATIVE, 0, false, false),
                    new Literal(":"),
                    new Digits(ChronoField.SECOND_OF_MINUTE, 2, 2, SignStyle.NOT_NEGATIVE, 0, false, false),
                    new Fraction(ChronoField.NANO_OF_SECOND, digits < 0 ? 0 : digits, digits < 0 ? 9 : digits,
                            true),
                    new Offset("+HH:MM:ss", "Z")};
            return new Group(steps, false);
        }

        public String toString() {
            return "Instant()";
        }
    }

    /** Parts written and read in order; an optional part is all of them or nothing. */
    static final class Group extends Step {

        final Step[] steps;

        final boolean optional;

        Group(Step[] steps, boolean optional) {
            this.steps = steps;
            this.optional = optional;
        }

        boolean print(Writing out) {
            if (!optional) {
                for (Step step : steps) {
                    if (!step.print(out)) {
                        return false;
                    }
                }
                return true;
            }
            int mark = out.text.length();
            out.optional++;
            try {
                for (Step step : steps) {
                    if (!step.print(out)) {
                        out.text.setLength(mark);
                        return true;
                    }
                }
            }
            finally {
                out.optional--;
            }
            return true;
        }

        int parse(Reading in, int at) {
            Reading saved = optional ? in.copy() : null;
            int position = at;
            for (Step step : steps) {
                position = step.parse(in, position);
                if (position < 0) {
                    if (saved == null) {
                        return position;
                    }
                    in.restore(saved);
                    return at;
                }
            }
            return position;
        }

        String inner() {
            StringBuilder text = new StringBuilder();
            for (Step step : steps) {
                text.append(step.toString());
            }
            return text.toString();
        }

        public String toString() {
            return (optional ? "[" : "(") + inner() + (optional ? "]" : ")");
        }
    }

    /** A setting of how the parts after it are read. */
    static final class Setting extends Step {

        /** 0 case sensitive, 1 case insensitive, 2 strict, 3 lenient. */
        final int kind;

        Setting(int kind) {
            this.kind = kind;
        }

        boolean print(Writing out) {
            return true;
        }

        int parse(Reading in, int at) {
            if (kind < 2) {
                in.caseSensitive = kind == 0;
            }
            else {
                in.strict = kind == 2;
            }
            return at;
        }

        public String toString() {
            return kind < 2 ? "ParseCaseSensitive(" + (kind == 0) + ")" : "ParseStrict(" + (kind == 2) + ")";
        }
    }

    /** A part written to a width, the pad character before it; read within the width. */
    static final class Padded extends Step {

        final Step step;

        final int width;

        final char pad;

        Padded(Step step, int width, char pad) {
            this.step = step;
            this.width = width;
            this.pad = pad;
        }

        boolean print(Writing out) {
            int mark = out.text.length();
            if (!step.print(out)) {
                return false;
            }
            String written = out.text.substring(mark);
            if (written.length() > width) {
                throw new DateTimeException(
                        "Cannot print as output of " + written.length() + " characters exceeds pad width of " + width);
            }
            out.text.setLength(mark);
            for (int i = written.length(); i < width; i++) {
                out.text.append(pad);
            }
            out.text.append(written);
            return true;
        }

        int parse(Reading in, int at) {
            boolean strict = in.strict;
            int end = at + width;
            if (end > in.length) {
                if (strict) {
                    return ~at;
                }
                end = in.length;
            }
            int position = at;
            while (position < end && in.same(in.text.charAt(position), pad)) {
                position++;
            }
            int saved = in.length;
            in.length = end;
            int read = step.parse(in, position);
            in.length = saved;
            if (read < 0 || (read != end && strict)) {
                return ~position;
            }
            return read;
        }

        public String toString() {
            return "Pad(" + step.toString() + "," + width + (pad == ' ' ? ")" : ",'" + pad + "')");
        }
    }

    /**
     * A localized form: the pattern its styles have in the formatter's kind of locale
     * (Texts), made into parts the first time a kind writes or reads one.
     */
    static final class Styled extends Step {

        final FormatStyle date;

        final FormatStyle time;

        private final Group[] parts = new Group[4];

        Styled(FormatStyle date, FormatStyle time) {
            this.date = date;
            this.time = time;
        }

        private Group parts(int kind) {
            Group made = parts[kind];
            if (made == null) {
                made = DateTimeFormatterBuilder.parts(Texts.pattern(kind, date, time));
                parts[kind] = made;
            }
            return made;
        }

        boolean print(Writing out) {
            if (Texts.ownCalendars()) {
                Texts.typed(out.calendar(), "requestID");
            }
            return parts(rontolisp.internal.Locales.foreign() ? Texts.words(out.texts, out.locale) : out.texts)
                .print(out);
        }

        int parse(Reading in, int at) {
            if (Texts.ownCalendars()) {
                Texts.typed(in.chronology, "requestID");
            }
            return parts(rontolisp.internal.Locales.foreign() ? Texts.words(in.texts, in.locale) : in.texts)
                .parse(in, at);
        }

        public String toString() {
            return "Localized(" + (date == null ? "" : date.name()) + "," + (time == null ? "" : time.name()) + ")";
        }
    }

    /**
     * A localized form of a requested template: the pattern of the skeleton its letters
     * find in the formatter's kind of locale (Skeletons), made into parts the first time
     * a kind writes or reads one; a kind with none refuses it, naming the locale.
     */
    static final class Templated extends Step {

        final String template;

        private final Group[] parts = new Group[4];

        Templated(String template) {
            this.template = template;
        }

        private Group parts(int kind, Locale locale) {
            Group made = parts[kind];
            if (made == null) {
                String pattern = Skeletons.pattern(kind, template);
                if (pattern == null) {
                    throw Skeletons.unresolved(template, locale);
                }
                made = DateTimeFormatterBuilder.parts(pattern);
                parts[kind] = made;
            }
            return made;
        }

        boolean print(Writing out) {
            if (Texts.ownCalendars()) {
                Texts.typed(out.calendar(), "calType");
            }
            return parts(rontolisp.internal.Locales.foreign() ? Texts.words(out.texts, out.locale) : out.texts,
                    out.locale).print(out);
        }

        int parse(Reading in, int at) {
            if (Texts.ownCalendars()) {
                Texts.typed(in.chronology, "calType");
            }
            return parts(rontolisp.internal.Locales.foreign() ? Texts.words(in.texts, in.locale) : in.texts,
                    in.locale).parse(in, at);
        }

        public String toString() {
            return "Localized(" + template + ")";
        }
    }

    /**
     * A period of the day in words (the letter B): written by the minute of the day, a
     * minute that is a period of its own (midnight, noon) before the span it lies in.
     * Read, the longest of the style's words and its halves of a day (AM, PM) standing
     * there, leniently of every style's; what it resolves to is Parsed's.
     */
    static final class DayPeriod extends Step {

        /** 0 full, 1 short, 2 narrow. */
        final int style;

        DayPeriod(int style) {
            this.style = style;
        }

        private static TextStyle textStyle(int style) {
            return style == 0 ? TextStyle.FULL : style == 1 ? TextStyle.SHORT : TextStyle.NARROW;
        }

        /** Whether a minute of the day is in a period, from its first minute to the one after its last. */
        static boolean contains(int from, int to, long minute) {
            return from == to ? minute == from || (from == 0 && minute == 1440) : minute >= from && minute < to;
        }

        static String shown(int from, int to) {
            return "DayPeriod(" + clock(from) + (from == to ? "" : "-" + clock(to)) + ")";
        }

        private static String clock(int minute) {
            int hour = minute / 60;
            int rest = minute % 60;
            return (hour < 10 ? "0" : "") + hour + (rest < 10 ? ":0" : ":") + rest;
        }

        boolean print(Writing out) {
            if (!out.has(ChronoField.HOUR_OF_DAY)) {
                return false;
            }
            long minute = out.value(ChronoField.HOUR_OF_DAY) * 60 + out.value(ChronoField.MINUTE_OF_HOUR);
            String spans = Texts.periods(
                    rontolisp.internal.Locales.foreign() ? Texts.words(out.texts, out.locale) : out.texts);
            int i = 0;
            while (!contains(hour(spans, 2 * i), hour(spans, 2 * i + 1), minute)) {
                i++;
            }
            out.text.append(Texts.piece(Texts.periodWords(out.texts, style), i));
            return true;
        }

        /** A minute of the day a period begins or ends at: its hour, a letter from A. */
        private static int hour(String spans, int at) {
            return (spans.charAt(at) - 'A') * 60;
        }

        int parse(Reading in, int at) {
            int kind = rontolisp.internal.Locales.foreign() ? Texts.words(in.texts, in.locale) : in.texts;
            String spans = Texts.periods(kind);
            int length = 0;
            int from = 0;
            int to = 0;
            for (int s = in.strict ? style : 0; s <= (in.strict ? style : 2); s++) {
                // the style's words, then its halves of a day: the morning and the afternoon
                String words = Texts.periodWords(kind, s);
                String[] halves = Texts.of(kind, ChronoField.AMPM_OF_DAY, textStyle(s));
                words = words + halves[0] + "|" + halves[1] + "|";
                int count = spans.length() / 2;
                for (int i = 0, start = 0; start < words.length(); i++) {
                    int end = words.indexOf('|', start);
                    if (end - start > length && in.matches(at, words.substring(start, end))) {
                        length = end - start;
                        from = i < count ? hour(spans, 2 * i) : (i - count) * 720;
                        to = i < count ? hour(spans, 2 * i + 1) : (i - count) * 720 + 720;
                    }
                    start = end + 1;
                }
            }
            if (length == 0) {
                return ~at;
            }
            in.period = new int[] {from, to};
            return at + length;
        }

        public String toString() {
            return "DayPeriod(" + textStyle(style).name() + ")";
        }
    }

    /**
     * A zone's name in words (the letters z and v): of the time it stands for at the
     * instant written -- standard or daylight -- or of the zone as a whole (generic), in
     * full or short, as a JDK writes them (ZoneNames); an offset, and a zone with no
     * names, by its id. Read as ZoneName reads, an offset or a name of a zone the
     * program holds, or as one of the names the program's zones are read by.
     */
    static final class ZoneText extends Step {

        final TextStyle style;

        final boolean generic;

        /** The zones a name several have is read as, first; or null. */
        final Set<ZoneId> preferred;

        ZoneText(TextStyle style, boolean generic, Set<ZoneId> preferred) {
            this.style = style;
            this.generic = generic;
            this.preferred = preferred;
        }

        /** 0 standard, 1 daylight, 2 the zone as a whole: what a value writes the name of. */
        private int time(Writing out, ZoneId zone) {
            if (generic) {
                return 2;
            }
            if (out.temporal.isSupported(ChronoField.INSTANT_SECONDS)) {
                return ZoneNames.daylight(zone, out.temporal.getLong(ChronoField.INSTANT_SECONDS)) ? 1 : 0;
            }
            if (out.temporal.isSupported(ChronoField.EPOCH_DAY) && out.temporal.isSupported(ChronoField.NANO_OF_DAY)) {
                // a local date-time: generic in a gap or an overlap, else the time of its offset
                LocalDate date = LocalDate.ofEpochDay(out.temporal.getLong(ChronoField.EPOCH_DAY));
                LocalDateTime local = LocalDateTime.of(date,
                        LocalTime.ofNanoOfDay(out.temporal.getLong(ChronoField.NANO_OF_DAY)));
                ZoneRules rules = zone.getRules();
                if (rules.getTransition(local) != null) {
                    return 2;
                }
                return rules.isDaylightSavings(local.toInstant(rules.getOffset(local))) ? 1 : 0;
            }
            return 2;
        }

        boolean print(Writing out) {
            ZoneId zone = out.temporal.query(TemporalQueries.zoneId());
            if (zone == null) {
                if (out.optional > 0) {
                    return false;
                }
                throw new DateTimeException("Unable to extract ZoneId from temporal " + out.shown());
            }
            String name = zone instanceof ZoneOffset || style == TextStyle.NARROW ? null
                    : ZoneNames.name(zone.getId(),
                            rontolisp.internal.Locales.foreign() ? Texts.words(out.texts, out.locale) : out.texts,
                            style.asNormal() == TextStyle.FULL, time(out, zone));
            out.text.append(name != null ? name : zone.getId());
            return true;
        }

        int parse(Reading in, int at) {
            return ZoneName.read(in, at, this);
        }

        public String toString() {
            return "ZoneText(" + style.name() + ")";
        }
    }

    /** A field's value where the text gave it none. */
    static final class Default extends Step {

        final TemporalField field;

        final long value;

        Default(TemporalField field, long value) {
            this.field = field;
            this.value = value;
        }

        boolean print(Writing out) {
            return true;
        }

        int parse(Reading in, int at) {
            if (in.indexOf(field) < 0) {
                in.put(field, value, at, at);
            }
            return at;
        }

        public String toString() {
            // a JDK shows the identity of an object here, which no program can rely on
            return "Default(" + field.toString() + "," + value + ")";
        }
    }
}
