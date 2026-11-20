// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A pattern's letters made the parts of a formatter, as the API documentation's table of
// letters and counts gives them. The refusals and their order (a count is refused before
// a letter the subset lacks) are a JDK 25's answers.
package java.time.format;

import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalField;

final class Patterns {

    private static final String[] X_PATTERNS = {"+HHmm", "+HHMM", "+HH:MM", "+HHMMss", "+HH:MM:ss"};

    private static final String[] X_NONE = {"+00", "+0000", "+00:00", "+0000", "+00:00"};

    private Patterns() {
    }

    static void read(DateTimeFormatterBuilder builder, String pattern) {
        for (int at = 0; at < pattern.length(); at++) {
            char c = pattern.charAt(at);
            if (letter(c)) {
                int end = at + 1;
                while (end < pattern.length() && pattern.charAt(end) == c) {
                    end++;
                }
                int count = end - at;
                if (c == 'p') {
                    if (end >= pattern.length() || !letter(pattern.charAt(end))) {
                        throw new IllegalArgumentException(
                                "Pad letter 'p' must be followed by valid pad pattern: " + pattern);
                    }
                    builder.padNext(count);
                    c = pattern.charAt(end);
                    at = end;
                    end = at + 1;
                    while (end < pattern.length() && pattern.charAt(end) == c) {
                        end++;
                    }
                    count = end - at;
                }
                letters(builder, c, count);
                at = end - 1;
            }
            else if (c == '\'') {
                int start = at++;
                StringBuilder literal = new StringBuilder();
                for (; at < pattern.length(); at++) {
                    if (pattern.charAt(at) == '\'') {
                        if (at + 1 < pattern.length() && pattern.charAt(at + 1) == '\'') {
                            at++;
                        }
                        else {
                            break;
                        }
                    }
                    literal.append(pattern.charAt(at));
                }
                if (at >= pattern.length()) {
                    throw new IllegalArgumentException("Pattern ends with an incomplete string literal: " + pattern);
                }
                if (at == start + 1) {
                    builder.appendLiteral('\'');
                }
                else {
                    builder.appendLiteral(literal.toString());
                }
            }
            else if (c == '[') {
                builder.optionalStart();
            }
            else if (c == ']') {
                try {
                    builder.optionalEnd();
                }
                catch (IllegalStateException none) {
                    throw new IllegalArgumentException("Pattern invalid as it contains ] without previous [");
                }
            }
            else if (c == '{' || c == '}' || c == '#') {
                throw new IllegalArgumentException("Pattern includes reserved character: '" + c + "'");
            }
            else {
                builder.appendLiteral(c);
            }
        }
    }

    private static boolean letter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static IllegalArgumentException tooMany(char c) {
        return new IllegalArgumentException("Too many pattern letters: " + c);
    }

    private static UnsupportedOperationException unsupported(char c) {
        return new UnsupportedOperationException(
                "the pattern letter " + c + " of DateTimeFormatter is not supported by the experimental Java subset yet");
    }

    private static TextStyle text(int count, boolean standalone) {
        TextStyle style = count == 4 ? TextStyle.FULL : count == 5 ? TextStyle.NARROW : TextStyle.SHORT;
        return standalone ? style.asStandalone() : style;
    }

    /** A number of at most two letters, one or two digits wide. */
    private static void small(DateTimeFormatterBuilder builder, char c, int count, TemporalField field) {
        if (count > 2) {
            throw tooMany(c);
        }
        if (count == 1) {
            builder.appendValue(field);
        }
        else {
            builder.appendValue(field, 2);
        }
    }

    private static void year(DateTimeFormatterBuilder builder, int count, TemporalField field) {
        if (count == 2) {
            builder.appendValueReduced(field, 2, 2, LocalDate.of(2000, 1, 1));
        }
        else if (count < 4) {
            builder.appendValue(field, count, 19, SignStyle.NORMAL);
        }
        else {
            builder.appendValue(field, count, 19, SignStyle.EXCEEDS_PAD);
        }
    }

    private static void letters(DateTimeFormatterBuilder builder, char c, int count) {
        switch (c) {
            case 'G':
                if (count > 5) {
                    throw tooMany(c);
                }
                builder.appendText(ChronoField.ERA, text(count, false));
                return;
            case 'u':
                year(builder, count, ChronoField.YEAR);
                return;
            case 'y':
                year(builder, count, ChronoField.YEAR_OF_ERA);
                return;
            case 'D':
                if (count > 3) {
                    throw tooMany(c);
                }
                if (count == 1) {
                    builder.appendValue(ChronoField.DAY_OF_YEAR);
                }
                else if (count == 2) {
                    builder.appendValue(ChronoField.DAY_OF_YEAR, 2, 3, SignStyle.NOT_NEGATIVE);
                }
                else {
                    builder.appendValue(ChronoField.DAY_OF_YEAR, 3);
                }
                return;
            case 'M':
            case 'L':
                if (count > 5) {
                    throw tooMany(c);
                }
                if (count < 3) {
                    small(builder, c, count, ChronoField.MONTH_OF_YEAR);
                }
                else {
                    builder.appendText(ChronoField.MONTH_OF_YEAR, text(count, c == 'L'));
                }
                return;
            case 'd':
                small(builder, c, count, ChronoField.DAY_OF_MONTH);
                return;
            case 'E':
                if (count > 5) {
                    throw tooMany(c);
                }
                builder.appendText(ChronoField.DAY_OF_WEEK, text(count, false));
                return;
            case 'e':
            case 'c':
                if (count > 5) {
                    throw tooMany(c);
                }
                if (c == 'c' && count == 2) {
                    throw new IllegalArgumentException("Invalid pattern \"cc\"");
                }
                if (count < 3) {
                    throw unsupported(c);
                }
                builder.appendText(ChronoField.DAY_OF_WEEK, text(count, c == 'c'));
                return;
            case 'F':
                if (count > 1) {
                    throw tooMany(c);
                }
                builder.appendValue(ChronoField.ALIGNED_WEEK_OF_MONTH);
                return;
            case 'a':
                if (count > 1) {
                    throw tooMany(c);
                }
                builder.appendText(ChronoField.AMPM_OF_DAY, TextStyle.SHORT);
                return;
            case 'h':
                small(builder, c, count, ChronoField.CLOCK_HOUR_OF_AMPM);
                return;
            case 'K':
                small(builder, c, count, ChronoField.HOUR_OF_AMPM);
                return;
            case 'k':
                small(builder, c, count, ChronoField.CLOCK_HOUR_OF_DAY);
                return;
            case 'H':
                small(builder, c, count, ChronoField.HOUR_OF_DAY);
                return;
            case 'm':
                small(builder, c, count, ChronoField.MINUTE_OF_HOUR);
                return;
            case 's':
                small(builder, c, count, ChronoField.SECOND_OF_MINUTE);
                return;
            case 'S':
                builder.appendFraction(ChronoField.NANO_OF_SECOND, count, count, false);
                return;
            case 'A':
                builder.appendValue(ChronoField.MILLI_OF_DAY, count, 19, SignStyle.NOT_NEGATIVE);
                return;
            case 'n':
                builder.appendValue(ChronoField.NANO_OF_SECOND, count, 19, SignStyle.NOT_NEGATIVE);
                return;
            case 'N':
                builder.appendValue(ChronoField.NANO_OF_DAY, count, 19, SignStyle.NOT_NEGATIVE);
                return;
            case 'V':
                if (count != 2) {
                    throw new IllegalArgumentException("Pattern letter count must be 2: " + c);
                }
                builder.appendZoneId();
                return;
            case 'O':
                if (count != 1 && count != 4) {
                    throw new IllegalArgumentException("Pattern letter count must be 1 or 4: " + c);
                }
                builder.appendLocalizedOffset(count == 4 ? TextStyle.FULL : TextStyle.SHORT);
                return;
            case 'X':
            case 'x':
                if (count > 5) {
                    throw tooMany(c);
                }
                builder.appendOffset(X_PATTERNS[count - 1], c == 'X' ? "Z" : X_NONE[count - 1]);
                return;
            case 'Z':
                if (count > 5) {
                    throw tooMany(c);
                }
                if (count == 4) {
                    builder.appendLocalizedOffset(TextStyle.FULL);
                }
                else if (count == 5) {
                    builder.appendOffset("+HH:MM:ss", "Z");
                }
                else {
                    builder.appendOffset("+HHMM", "+0000");
                }
                return;
            case 'Q':
            case 'q':
            case 'B':
            case 'Y':
            case 'w':
            case 'W':
            case 'g':
            case 'z':
            case 'v':
                refused(c, count);
                return;
            default:
                throw new IllegalArgumentException("Unknown pattern letter: " + c);
        }
    }

    /**
     * A letter the subset lacks: the counts a JDK refuses are refused as it refuses them,
     * any other by name.
     */
    private static void refused(char c, int count) {
        if (c == 'B' || c == 'v') {
            if (c == 'B' ? count != 1 && count != 4 && count != 5 : count != 1 && count != 4) {
                throw new IllegalArgumentException("Wrong number of pattern letters: " + c);
            }
        }
        else if (count > (c == 'w' ? 2 : c == 'W' ? 1 : c == 'z' ? 4 : c == 'Q' || c == 'q' ? 5 : 19)) {
            throw tooMany(c);
        }
        throw unsupported(c);
    }
}
