// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The words a formatter writes for the values of a field, for the locales the library
// has (java.util.Locale: the root and four English ones), and the patterns of their
// localized forms and of the skeletons a requested template is matched to. The words and
// the patterns are what a JDK 25 writes and answers for each locale, style, value and
// template; a field or a style with none here is written as a number.
// Third-party: Unicode CLDR data (Unicode-3.0), as a running JDK writes it, Copyright (c)
// 1991-2026 Unicode, Inc. Notice: THIRD-PARTY-NOTICES.txt, "Unicode".
package java.time.format;

import java.time.chrono.Chronology;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalField;
import java.util.Locale;

final class Texts {

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};

    private static final String[] SHORT_MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep",
            "Oct", "Nov", "Dec"};

    private static final String[] BRITISH_MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept",
            "Oct", "Nov", "Dec"};

    private static final String[] NARROW_MONTHS = {"J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"};

    private static final String[] NUMBERED_MONTHS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};

    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
            "Sunday"};

    private static final String[] SHORT_DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    private static final String[] NARROW_DAYS = {"M", "T", "W", "T", "F", "S", "S"};

    private static final String[] ERAS = {"Before Christ", "Anno Domini"};

    private static final String[] SHORT_ERAS = {"BC", "AD"};

    private static final String[] NARROW_ERAS = {"B", "A"};

    private static final String[] COMMON_ERAS = {"BCE", "CE"};

    private static final String[] HALVES = {"AM", "PM"};

    private static final String[] NARROW_HALVES = {"a", "p"};

    private static final String[] LOWER_HALVES = {"am", "pm"};

    private static final String[] DOTTED_HALVES = {"a.m.", "p.m."};

    private Texts() {
    }

    /**
     * Whether the program makes the fields of IsoFields, so that a quarter can be written
     * in words: answered by the compiler.
     */
    static native boolean quarters();

    /** The words of a quarter, made in a program that writes one. */
    private static final class Quarters {

        static final String[] FULL = {"1st quarter", "2nd quarter", "3rd quarter", "4th quarter"};

        static final String[] SHORT = {"Q1", "Q2", "Q3", "Q4"};

        static final String[] NARROW = {"1", "2", "3", "4"};
    }

    /**
     * The patterns of the localized forms (ofLocalizedDate and its kind) of a kind of
     * locale: the dates in full, long, medium and short, then the times, each ended by
     * '|'. A date and a time stand side by side with a space between in the root's,
     * with a comma and a space in English.
     */
    private static String styles(int kind) {
        return kind == 0 ? "y MMMM d, EEEE|y MMMM d|y MMM d|y-MM-dd|HH:mm:ss zzzz|HH:mm:ss z|HH:mm:ss|HH:mm|"
                : kind == 2 ? "EEEE, d MMMM y|d MMMM y|d MMM y|dd/MM/y|HH:mm:ss zzzz|HH:mm:ss z|HH:mm:ss|HH:mm|"
                        : "EEEE, MMMM d, y|MMMM d, y|MMM d, y|" + (kind == 1 ? "M/d/yy" : "y-MM-dd")
                                + "|h:mm:ss\u202fa zzzz|h:mm:ss\u202fa z|h:mm:ss\u202fa|h:mm\u202fa|";
    }

    /**
     * The skeletons of a set of locales a requested template is matched to (Skeletons):
     * each the letters of a template, '=', and its pattern, ended by '|', those of one
     * first letter together. A set holds what it has otherwise than the set it adds to
     * (parent): 0 the root's, 1 English, 2 British, 3 Canadian, 4 international English.
     */
    static String skeletons(int set) {
        if (set == 0) {
            return "Gy=G y|GyMd=GGGGG y-MM-dd|GyMMM=G y MMM|GyMMMEd=G y MMM d, E|GyMMMd=G y MMM d|y=y|"
                    + "yQQQ=y QQQ|yQQQQ=y QQQQ|yM=y-MM|yMEd=y-MM-dd, E|yMd=y-MM-dd|yMMM=y MMM|"
                    + "yMMMEd=y MMM d, E|yMMMd=y MMM d|yMMMM=y MMMM|yw='week' w 'of' Y|yyyy=G y|"
                    + "yyyyQQQ=G y QQQ|yyyyQQQQ=G y QQQQ|yyyyM=GGGGG y-MM|yyyyMEd=GGGGG y-MM-dd, E|"
                    + "yyyyMd=GGGGG y-MM-dd|yyyyMMM=G y MMM|yyyyMMMEd=G y MMM d, E|yyyyMMMd=G y MMM d|"
                    + "yyyyMMMM=G y MMMM|M=L|MEd=MM-dd, E|Md=MM-dd|MMM=LLL|MMMEd=MMM d, E|MMMd=MMM d|"
                    + "MMMMd=MMMM d|E=ccc|Ed=d, E|EBhm=E h:mm B|EBhms=E h:mm:ss B|Ehm=E h:mm a|"
                    + "Ehms=E h:mm:ss a|EHm=E HH:mm|EHms=E HH:mm:ss|d=d|Bh=h B|Bhm=h:mm B|Bhms=h:mm:ss B|"
                    + "h=h\u202fa|hm=h:mm a|hms=h:mm:ss a|hmsv=h:mm:ss a v|hmv=h:mm a v|H=HH|Hm=HH:mm|"
                    + "Hms=HH:mm:ss|Hmsv=HH:mm:ss v|Hmv=HH:mm v|ms=mm:ss|";
        }
        if (set == 1) {
            return "Gy=y G|GyMd=M/d/y G|GyMMM=MMM y G|GyMMMEd=E, MMM d, y G|GyMMMd=MMM d, y G|yQQQ=QQQ y|"
                    + "yQQQQ=QQQQ y|yM=M/y|yMEd=E, M/d/y|yMd=M/d/y|yMMM=MMM y|yMMMEd=E, MMM d, y|"
                    + "yMMMd=MMM d, y|yMMMM=MMMM y|yyyy=y G|yyyyQQQ=QQQ y G|yyyyQQQQ=QQQQ y G|yyyyM=M/y GGGGG|"
                    + "yyyyMEd=E, M/d/y GGGGG|yyyyMd=M/d/y GGGGG|yyyyMMM=MMM y G|yyyyMMMEd=E, MMM d, y G|"
                    + "yyyyMMMd=MMM d, y G|yyyyMMMM=MMMM y G|MEd=E, M/d|Md=M/d|MMMEd=E, MMM d|Ed=d E|"
                    + "Ehm=E h:mm\u202fa|Ehms=E h:mm:ss\u202fa|hm=h:mm\u202fa|hms=h:mm:ss\u202fa|"
                    + "hmsv=h:mm:ss\u202fa v|hmv=h:mm\u202fa v|";
        }
        if (set == 2) {
            return "GyMd=dd/MM/y G|GyMMMEEEEd=EEEE, d MMM y G|yMMMEEEEd=EEEE, d MMM y|"
                    + "yMMMMEEEEd=EEEE, d MMMM y|yyyyMMMEEEEd=EEEE, d MMM y G|yyyyMMMMEEEEd=EEEE, d MMMM y G|"
                    + "MEd=E dd/MM|MMMEd=E d MMM|MMMEEEEd=EEEE d MMM|MMMMEEEEd=EEEE d MMMM|";
        }
        if (set == 3) {
            return "yM=y-MM|yMEd=E, y-MM-dd|yMd=y-MM-dd|MEd=E, MM-dd|Md=MM-dd|Mdd=MM-dd|Ed=E d|";
        }
        return "GyMd=d/M/y G|GyMMMEd=E, d MMM y G|GyMMMd=d MMM y G|yM=MM/y|yMEd=E, dd/MM/y|yMd=dd/MM/y|"
                + "yMMMEd=E, d MMM y|yMMMd=d MMM y|yyyyM=MM/y GGGGG|yyyyMEd=E, dd/MM/y GGGGG|"
                + "yyyyMd=dd/MM/y GGGGG|yyyyMMMEd=E, d MMM y G|yyyyMMMd=d MMM y G|M=LL|MEd=E, dd/MM|"
                + "Md=dd/MM|Mdd=dd/MM|MMMEd=E, d MMM|MMMd=d MMM|MMMMd=d MMMM|Ed=E d|";
    }

    /** The set a set of skeletons adds to; -1 for the root's. */
    static int parent(int set) {
        return set == 0 ? -1 : set == 1 ? 0 : set == 2 ? 4 : 1;
    }

    /** The hour a requested template's j and C stand for in a set's locales: of twenty-four hours or of twelve. */
    static char hour(int set) {
        return set == 0 || set == 2 ? 'H' : 'h';
    }

    /**
     * The set a template asking for a period of the day (B) and the locale's own hour (j,
     * C) is matched in: for the British, international English, whose hours are of twelve.
     */
    static int twelve(int set) {
        return set == 2 ? 4 : set;
    }

    /**
     * Whether the program makes a calendar of its own, which may have no type: answered by
     * the compiler, so that a program whose calendar is ISO's alone never asks.
     */
    static native boolean ownCalendars();

    /**
     * Refuses a calendar of no type where a locale's patterns are looked up, with a JDK
     * 25's words: of a localized form's styles (requestID), of a requested template
     * (calType).
     */
    static void typed(Chronology chronology, String looked) {
        if (chronology != null && chronology.getCalendarType() == null) {
            throw new NullPointerException("Cannot invoke \"String.equals(Object)\" because \"" + looked + "\" is null");
        }
    }

    /** The piece of a text of pieces each ended by '|' at an index. */
    static String piece(String text, int index) {
        int at = 0;
        for (int i = 0; i < index; i++) {
            at = text.indexOf('|', at) + 1;
        }
        return text.substring(at, text.indexOf('|', at));
    }

    /** The pattern of a localized form in a kind of locale; a style may be null, not both. */
    static String pattern(int kind, FormatStyle date, FormatStyle time) {
        String styles = styles(kind);
        if (time == null) {
            return piece(styles, date.ordinal());
        }
        String written = piece(styles, 4 + time.ordinal());
        return date == null ? written : piece(styles, date.ordinal()) + (kind == 0 ? " " : ", ") + written;
    }

    /**
     * The periods of a day a kind of locale writes (the pattern letter B), as hours of the
     * day two by two, each a letter from A: each one's first and the one after its last,
     * equal for midnight and noon, which are a minute and are written before the span
     * they lie in.
     */
    static String periods(int kind) {
        return kind == 0 ? "AMMY" : "AAMMAMMSSVVY";
    }

    /**
     * The words of the periods of a day of a kind of locale, in full (0), short (1) or
     * narrow (2), each ended by '|'.
     */
    static String periodWords(int kind, int style) {
        if (kind == 0) {
            return "AM|PM|";
        }
        if (style < 2) {
            return "midnight|noon|in the morning|in the afternoon|in the evening|at night|";
        }
        return kind == 3 ? "mid|n|mor|aft|eve|night|" : "mi|n|in the morning|in the afternoon|in the evening|at night|";
    }

    /** Which words a locale has: 0 the root's, 1 English, 2 British, 3 Canadian. */
    static int kind(Locale locale) {
        if (locale.getLanguage().isEmpty()) {
            return 0;
        }
        String country = locale.getCountry();
        return country.equals("GB") ? 2 : country.equals("CA") ? 3 : 1;
    }

    /**
     * The words of a field's values in a style, the field's least value first; null where
     * the field is written as a number.
     */
    static String[] of(int kind, TemporalField field, TextStyle style) {
        boolean full = style == TextStyle.FULL || style == TextStyle.FULL_STANDALONE;
        boolean narrow = style == TextStyle.NARROW || style == TextStyle.NARROW_STANDALONE;
        if (quarters()) {
            if (field == IsoFields.QUARTER_OF_YEAR) {
                // the root writes Q1 for the full word too, and its short standalone one as a number
                if (narrow || (kind == 0 && style == TextStyle.SHORT_STANDALONE)) {
                    return Quarters.NARROW;
                }
                return full && kind != 0 ? Quarters.FULL : Quarters.SHORT;
            }
        }
        if (field == ChronoField.MONTH_OF_YEAR) {
            if (kind == 0) {
                return narrow ? NUMBERED_MONTHS : SHORT_MONTHS;
            }
            return full ? MONTHS : narrow ? NARROW_MONTHS : kind == 2 ? BRITISH_MONTHS : SHORT_MONTHS;
        }
        if (field == ChronoField.DAY_OF_WEEK) {
            return narrow ? NARROW_DAYS : full && kind != 0 ? DAYS : SHORT_DAYS;
        }
        if (style.isStandalone()) {
            return null;
        }
        if (field == ChronoField.ERA) {
            return kind == 0 ? COMMON_ERAS : full ? ERAS : narrow ? NARROW_ERAS : SHORT_ERAS;
        }
        if (field == ChronoField.AMPM_OF_DAY) {
            if (kind == 2 || (kind == 3 && narrow)) {
                return LOWER_HALVES;
            }
            return kind == 3 ? DOTTED_HALVES : narrow && kind == 1 ? NARROW_HALVES : HALVES;
        }
        return null;
    }
}
