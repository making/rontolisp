// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import rontolisp.internal.Dates;
import rontolisp.internal.Exact;

// The conversions of a format the run time does not write itself (%s %d %x %o %f %e %c
// %b are its own): %h, %g, %a, %t, a Formattable's %s and a BigInteger's or a
// BigDecimal's own digits. Each is a branch behind a question the lowering answers while
// it lowers this class (JavaIntrinsics): whether a control of the program can name the
// conversion, whether the program makes a class the branch reads. A program holds the
// branches it can take and no other; one taking none is handed no function at all.
//
// What a specifier refuses, and in what order, is the Formatter documentation's flag
// table and a JDK's answers: a refusal of %g, %a and %t is made when the format reaches
// the specifier.
final class Conversions {

    private Conversions() {
    }

    /** Whether a control of the program can name %h or %H. */
    private static native boolean hashes();

    /** %g or %G. */
    private static native boolean generals();

    /** %a or %A. */
    private static native boolean hexes();

    /** %t or %T. */
    private static native boolean times();

    /** %tZ or %tc, which write a zone's name. */
    private static native boolean zoneTexts();

    /** Whether the program makes a BigInteger or a BigDecimal. */
    private static native boolean exacts();

    /** A java.util.Date. */
    private static native boolean dates();

    /** Whether a format of the program can be given a long or a Date: an argument a %t reads in the host's zone. */
    private static native boolean millis();

    /** A TemporalAccessor. */
    private static native boolean temporals();

    /** A Formattable. */
    private static native boolean formattables();

    /** The run time's conversion of a number by its own digits, null where it has none. */
    private static native String exact0(int conversion, String flags, int width, int precision, Object[] digits);

    /** The run time's %g of a number by its own digits. */
    private static native String exactGeneral0(int conversion, String flags, int width, int precision,
            Object[] digits);

    /**
     * Raises an exception of the format's as the run time raises its own: a refusal is
     * no object of the program's until a catch takes it, so a program that formats
     * holds no code throwing one (JavaLowering.RAISED).
     */
    private static native void refuse(String exception, String message);

    /** The run time's %g of a double, padded. */
    private static native String general0(double d, String flags, int width, int precision);

    /**
     * A conversion of the run time's format: its letter, the letter after a t or T (0
     * after any other), the flags as written, the index written (0 where none is), the
     * width (0 where none is given), the precision (-1 where none is given), the argument
     * and what the format is written to -- the builder of String.format or the
     * Formatter. Answers the text, or null where the conversion is the run time's own.
     */
    static String more(int conversion, int suffix, String flags, int index, int width, int precision,
            Object argument, Object out) {
        char c = (char) conversion;
        char kind = lower(c);
        if ((hashes() && kind == 'h') || (generals() && kind == 'g') || (hexes() && kind == 'a')
                || (times() && kind == 't')) {
            check(c, (char) suffix, flags, index, width, precision);
        }
        if (exacts() && argument instanceof Exact exact) {
            String text = generals() && kind == 'g'
                    ? exactGeneral0(conversion, flags, width, precision, exact.digits())
                    : exact0(conversion, flags, width, precision, exact.digits());
            if (text != null) {
                return text;
            }
        }
        if (hashes() && kind == 'h') {
            String text = argument == null ? "null" : Integer.toHexString(argument.hashCode());
            return pad(cased(cut(text, precision), c), width, flags);
        }
        if (generals() && kind == 'g') {
            if (argument == null) {
                return pad(cased(cut("null", precision), c), width, flags);
            }
            return cased(general0(real(argument, c), flags, width, precision), c);
        }
        if (hexes() && kind == 'a') {
            if (argument == null) {
                return pad(cased(cut("null", precision), c), width, flags);
            }
            return cased(hex(real(argument, c), flags, width, precision), c);
        }
        if (times() && kind == 't') {
            String text;
            if (argument == null) {
                text = "null";
            }
            else {
                StringBuilder written = new StringBuilder();
                moment(argument, (char) suffix).print(written, (char) suffix);
                text = written.toString();
            }
            return pad(cased(text, c), width, flags);
        }
        if (formattables() && kind == 's') {
            if (argument instanceof Formattable formattable) {
                Formatter formatter = out instanceof Formatter given ? given.written()
                        : new Formatter((StringBuilder) out);
                formattable.formatTo(formatter,
                        (flags.indexOf('-') >= 0 ? FormattableFlags.LEFT_JUSTIFY : 0)
                                | (c == 'S' ? FormattableFlags.UPPERCASE : 0)
                                | (flags.indexOf('#') >= 0 ? FormattableFlags.ALTERNATE : 0),
                        width == 0 ? -1 : width, precision);
                return "";
            }
            if (flags.indexOf('#') >= 0) {
                mismatched("#", 's');
            }
        }
        return null;
    }

    /** A conversion's letter in lower case: an ASCII letter, which needs no table of Unicode's. */
    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    /** Those of the flags that are in a set, in the order the flags are written in a refusal. */
    private static String among(String flags, String set) {
        StringBuilder given = new StringBuilder();
        String order = "-#+ 0,(";
        for (int i = 0; i < order.length(); i++) {
            char flag = order.charAt(i);
            if (flags.indexOf(flag) >= 0 && set.indexOf(flag) >= 0) {
                given.append(flag);
            }
        }
        return given.toString();
    }

    /**
     * The refusals of a specifier of %h, %g, %a and %t, in the order a JDK makes them: of
     * %h a #, - with no width, any other flag; of %t a precision, a letter it does not
     * know, a flag but -, - with no width; of %g and %a - or 0 with no width, + with a
     * space or - with 0, a flag the conversion does not take.
     */
    private static void check(char c, char suffix, String given, int index, int width, int precision) {
        String flags = among(given, "-#+ 0,(");
        char kind = lower(c);
        String written = "%" + flags + (index > 0 ? index + "$" : "") + (width > 0 ? width : "")
                + (precision >= 0 ? "." + precision : "") + c
                + (kind != 't' ? "" : c == 't' ? String.valueOf(suffix) : String.valueOf(suffix).toUpperCase());
        if (kind == 'h') {
            mismatched(among(flags, "#"), kind);
            missing(flags, "-", width, written);
            mismatched(among(flags, "+ 0,("), kind);
            return;
        }
        if (kind == 't') {
            if (precision >= 0) {
                refuse("java.util.IllegalFormatPrecisionException", String.valueOf(precision));
            }
            if ("HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc".indexOf(suffix) < 0) {
                refuse("java.util.UnknownFormatConversionException", "Conversion = 't" + suffix + "'");
            }
            mismatched(among(flags, "#+ 0,("), suffix);
            missing(flags, "-", width, written);
            return;
        }
        missing(flags, "-0", width, written);
        if ((flags.indexOf('+') >= 0 && flags.indexOf(' ') >= 0)
                || (flags.indexOf('-') >= 0 && flags.indexOf('0') >= 0)) {
            // an uppercase conversion is one more flag of a JDK's, after -
            int at = flags.startsWith("-") ? 1 : 0;
            refuse("java.util.IllegalFormatFlagsException",
                    "Flags = '" + (c == kind ? flags : flags.substring(0, at) + "^" + flags.substring(at)) + "'");
        }
        mismatched(among(flags, kind == 'g' ? "#" : ",("), kind);
    }

    private static void missing(String flags, String needing, int width, String written) {
        if (width == 0 && !among(flags, needing).isEmpty()) {
            refuse("java.util.MissingFormatWidthException", written);
        }
    }

    /** IllegalFormatConversionException: a conversion that does not take the argument's class. */
    private static void mismatched(char conversion, Object argument) {
        refuse("java.util.IllegalFormatConversionException", conversion + " != " + argument.getClass().getName());
    }

    private static void mismatched(String flags, char conversion) {
        if (!flags.isEmpty()) {
            refuse("java.util.FormatFlagsConversionMismatchException",
                    "Conversion = " + conversion + ", Flags = " + flags);
        }
    }

    private static String cut(String text, int precision) {
        return precision >= 0 && precision < text.length() ? text.substring(0, precision) : text;
    }

    private static String cased(String text, char conversion) {
        return conversion != lower(conversion) ? text.toUpperCase() : text;
    }

    private static String pad(String text, int width, String flags) {
        if (text.length() >= width) {
            return text;
        }
        String fill = " ".repeat(width - text.length());
        return flags.indexOf('-') >= 0 ? text + fill : fill + text;
    }

    /** The double a %g or %a writes of its argument. */
    private static double real(Object argument, char conversion) {
        if (argument instanceof Double d) {
            return d;
        }
        if (argument instanceof Float f) {
            return f.doubleValue();
        }
        mismatched(lower(conversion), argument);
        return 0;
    }

    /**
     * %a: the sign, 0x, the leading bit, the fraction's hexadecimal digits and the
     * exponent of two. The digits are all the fraction's where no precision is given, a
     * subnormal's as they are after 0x0. and with p-1022 where none or 13 or more are
     * given, and otherwise the precision's count of them (at least one, a subnormal's
     * after its first bit) rounded half to even; their last zeros are dropped and those a
     * precision asks for written after the zeros that fill the width, which a JDK counts
     * without them.
     */
    private static String hex(double d, String flags, int width, int precision) {
        String plus = flags.indexOf('+') >= 0 ? "+" : flags.indexOf(' ') >= 0 ? " " : "";
        if (d != d) {
            return pad("NaN", width, flags);
        }
        if (d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY) {
            return pad((d < 0 ? "-" : plus) + "Infinity", width, flags);
        }
        long bits = Double.doubleToLongBits(d);
        String sign = bits < 0 ? "-" : plus;
        int exponent = (int) ((bits >>> 52) & 0x7ff);
        long fraction = bits & 0xfffffffffffffL;
        int count = Math.max(precision, 1);
        String lead = "1";
        int power = exponent - 1023;
        String digits;
        if (precision < 0 || count >= 13 || fraction == 0) {
            digits = hexDigits(fraction, 13);
            if (exponent == 0) {
                lead = "0";
                power = fraction == 0 ? 0 : -1022;
            }
        }
        else {
            if (exponent == 0) {
                // a subnormal from its first bit
                int top = 63 - Long.numberOfLeadingZeros(fraction);
                power = top - 1074;
                fraction = (fraction << (52 - top)) & 0xfffffffffffffL;
            }
            int dropped = 52 - 4 * count;
            long kept = fraction >>> dropped;
            long rest = fraction & ((1L << dropped) - 1);
            long half = 1L << (dropped - 1);
            if (rest > half || (rest == half && (kept & 1) != 0)) {
                kept++;
            }
            if (kept >>> (4 * count) != 0) {
                kept = 0;
                power++;
            }
            digits = hexDigits(kept, count);
        }
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        String tail = "p" + power;
        int missing = width - sign.length() - 3 - lead.length() - digits.length() - tail.length();
        String zeros = flags.indexOf('0') >= 0 && missing > 0 ? "0".repeat(missing) : "";
        String asked = precision > digits.length() ? "0".repeat(precision - digits.length()) : "";
        return pad(sign + "0x" + zeros + lead + "." + digits + asked + tail, width, flags);
    }

    private static String hexDigits(long value, int count) {
        String text = Long.toHexString(value);
        return "0".repeat(Math.max(0, count - text.length())) + text;
    }

    /** What a %t reads its fields from. */
    private static Moment moment(Object argument, char suffix) {
        if (millis() && argument instanceof Long millis) {
            return new Millis(millis);
        }
        if (millis() && dates() && argument instanceof Date date) {
            return new Millis(date.getTime());
        }
        if (temporals() && argument instanceof TemporalAccessor temporal) {
            return new Accessor(temporal);
        }
        mismatched(suffix, argument);
        return null;
    }

    // The fields a %t writes, each a number of Moment.get.
    private static final int HOUR = 0;

    private static final int CLOCK_HOUR = 1;

    private static final int MINUTE = 2;

    private static final int SECOND = 3;

    private static final int MILLI = 4;

    private static final int NANO = 5;

    private static final int AFTERNOON = 6;

    private static final int OFFSET = 7;

    private static final int SECONDS = 8;

    private static final int MILLIS = 9;

    private static final int MONTH = 10;

    /** The day of the week, 0 Sunday. */
    private static final int WEEKDAY = 11;

    private static final int YEAR_OF_ERA = 12;

    private static final int DAY_OF_YEAR = 13;

    private static final int DAY = 14;

    /**
     * An instant's or a temporal's fields as %t writes them. A letter that writes several
     * asks for each under its own letter, which is the one a refusal names, but F's year,
     * which F's refusal names.
     */
    private abstract static class Moment {

        private static final String[] MONTHS = { "January", "February", "March", "April", "May", "June", "July",
                "August", "September", "October", "November", "December" };

        private static final String[] DAYS = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
                "Saturday" };

        /** A field, refused under a letter where the value has none. */
        abstract long get(int field, char letter);

        abstract String zone(char letter);

        /** F's year. */
        abstract String year(char letter);

        static void digits(StringBuilder into, long value, int count) {
            String text = String.valueOf(value);
            for (int i = text.length(); i < count; i++) {
                into.append('0');
            }
            into.append(text);
        }

        void print(StringBuilder into, char letter) {
            switch (letter) {
                case 'H' -> digits(into, get(HOUR, letter), 2);
                case 'I' -> digits(into, get(CLOCK_HOUR, letter), 2);
                case 'k' -> into.append(get(HOUR, letter));
                case 'l' -> into.append(get(CLOCK_HOUR, letter));
                case 'M' -> digits(into, get(MINUTE, letter), 2);
                case 'S' -> digits(into, get(SECOND, letter), 2);
                case 'L' -> digits(into, get(MILLI, letter), 3);
                case 'N' -> digits(into, get(NANO, letter), 9);
                case 'p' -> into.append(get(AFTERNOON, letter) == 0 ? "am" : "pm");
                case 'z' -> {
                    long offset = get(OFFSET, letter);
                    long minutes = Math.abs(offset) / 60;
                    into.append(offset < 0 ? '-' : '+');
                    digits(into, minutes / 60, 2);
                    digits(into, minutes % 60, 2);
                }
                case 'Z' -> {
                    // named only where a control can name it
                    if (zoneTexts()) {
                        into.append(zone(letter));
                    }
                }
                case 's' -> into.append(get(SECONDS, letter));
                case 'Q' -> into.append(get(MILLIS, letter));
                case 'B' -> into.append(MONTHS[(int) get(MONTH, letter) - 1]);
                case 'b', 'h' -> into.append(MONTHS[(int) get(MONTH, letter) - 1], 0, 3);
                case 'A' -> into.append(DAYS[(int) get(WEEKDAY, letter)]);
                case 'a' -> into.append(DAYS[(int) get(WEEKDAY, letter)], 0, 3);
                case 'C' -> digits(into, get(YEAR_OF_ERA, letter) / 100, 2);
                case 'Y' -> digits(into, get(YEAR_OF_ERA, letter), 4);
                case 'y' -> digits(into, get(YEAR_OF_ERA, letter) % 100, 2);
                case 'j' -> digits(into, get(DAY_OF_YEAR, letter), 3);
                case 'm' -> digits(into, get(MONTH, letter), 2);
                case 'd' -> digits(into, get(DAY, letter), 2);
                case 'e' -> into.append(get(DAY, letter));
                case 'R' -> {
                    print(into, 'H');
                    into.append(':');
                    print(into, 'M');
                }
                case 'T' -> {
                    print(into, 'R');
                    into.append(':');
                    print(into, 'S');
                }
                case 'r' -> {
                    print(into, 'I');
                    into.append(':');
                    print(into, 'M');
                    into.append(':');
                    print(into, 'S');
                    into.append(get(AFTERNOON, 'p') == 0 ? " AM" : " PM");
                }
                case 'D' -> {
                    print(into, 'm');
                    into.append('/');
                    print(into, 'd');
                    into.append('/');
                    print(into, 'y');
                }
                case 'F' -> {
                    into.append(year(letter)).append('-');
                    print(into, 'm');
                    into.append('-');
                    print(into, 'd');
                }
                default -> {
                    // c
                    print(into, 'a');
                    into.append(' ');
                    print(into, 'b');
                    into.append(' ');
                    print(into, 'd');
                    into.append(' ');
                    print(into, 'T');
                    into.append(' ');
                    print(into, 'Z');
                    into.append(' ');
                    print(into, 'Y');
                }
            }
        }
    }

    /**
     * The milliseconds of a long or a Date, read as a Date reads them: in the host's zone
     * at that instant and the calendar of rontolisp.internal.Dates.
     */
    private static final class Millis extends Moment {

        private final long millis;

        private final long[] fields;

        Millis(long millis) {
            this.millis = millis;
            this.fields = Dates.fields(millis);
        }

        long get(int field, char letter) {
            long hour = fields[4];
            return switch (field) {
                case HOUR -> hour;
                case CLOCK_HOUR -> hour % 12 == 0 ? 12 : hour % 12;
                case MINUTE -> fields[5];
                case SECOND -> fields[6];
                case MILLI -> fields[7];
                case NANO -> fields[7] * 1000000;
                case AFTERNOON -> hour < 12 ? 0 : 1;
                case OFFSET -> fields[8];
                case SECONDS -> millis / 1000;
                case MILLIS -> millis;
                case MONTH -> fields[1];
                case WEEKDAY -> fields[3];
                case YEAR_OF_ERA -> fields[0];
                case DAY_OF_YEAR -> fields[9];
                default -> fields[2];
            };
        }

        String zone(char letter) {
            return Dates.zoneName(fields);
        }

        String year(char letter) {
            StringBuilder year = new StringBuilder();
            digits(year, fields[0], 4);
            return year.toString();
        }
    }

    /** A TemporalAccessor's fields, a field it does not support refused under the letter. */
    private static final class Accessor extends Moment {

        private final TemporalAccessor temporal;

        Accessor(TemporalAccessor temporal) {
            this.temporal = temporal;
        }

        long get(int field, char letter) {
            try {
                return switch (field) {
                    case HOUR -> temporal.getLong(ChronoField.HOUR_OF_DAY);
                    case CLOCK_HOUR -> temporal.getLong(ChronoField.CLOCK_HOUR_OF_AMPM);
                    case MINUTE -> temporal.getLong(ChronoField.MINUTE_OF_HOUR);
                    case SECOND -> temporal.getLong(ChronoField.SECOND_OF_MINUTE);
                    case MILLI -> temporal.getLong(ChronoField.MILLI_OF_SECOND);
                    case NANO -> temporal.getLong(ChronoField.NANO_OF_SECOND);
                    case AFTERNOON -> temporal.getLong(ChronoField.AMPM_OF_DAY);
                    case OFFSET -> temporal.getLong(ChronoField.OFFSET_SECONDS);
                    case SECONDS -> temporal.getLong(ChronoField.INSTANT_SECONDS);
                    case MILLIS -> temporal.getLong(ChronoField.INSTANT_SECONDS) * 1000
                            + temporal.getLong(ChronoField.MILLI_OF_SECOND);
                    case MONTH -> temporal.getLong(ChronoField.MONTH_OF_YEAR);
                    case WEEKDAY -> temporal.getLong(ChronoField.DAY_OF_WEEK) % 7;
                    case YEAR_OF_ERA -> temporal.getLong(ChronoField.YEAR_OF_ERA);
                    case DAY_OF_YEAR -> temporal.getLong(ChronoField.DAY_OF_YEAR);
                    default -> temporal.getLong(ChronoField.DAY_OF_MONTH);
                };
            }
            catch (DateTimeException ex) {
                mismatched(letter, temporal);
                return 0;
            }
        }

        /** The offset's id, or a region's short name as a formatter's z writes it. */
        String zone(char letter) {
            ZoneId zone = temporal.query(TemporalQueries.zone());
            if (zone == null) {
                mismatched(letter, temporal);
            }
            if (zone instanceof ZoneOffset) {
                return zone.getId();
            }
            return new DateTimeFormatterBuilder().appendZoneText(TextStyle.SHORT).toFormatter().format(temporal);
        }

        /** The proleptic year as ISO writes it: four digits at least, a sign past 9999. */
        String year(char letter) {
            long year;
            try {
                year = temporal.getLong(ChronoField.YEAR);
            }
            catch (DateTimeException ex) {
                mismatched(letter, temporal);
                return null;
            }
            StringBuilder text = new StringBuilder(year < 0 ? "-" : year > 9999 ? "+" : "");
            digits(text, Math.abs(year), 4);
            return text.toString();
        }
    }
}
