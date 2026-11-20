// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The words a formatter writes for the values of a field, for the locales the library
// has (java.util.Locale: the root and four English ones). The words are what a JDK 25
// writes for each locale, style and value; a field or a style with none here is written
// as a number.
// Third-party: Unicode CLDR data (Unicode-3.0), as a running JDK writes it, Copyright (c)
// 1991-2026 Unicode, Inc. Notice: THIRD-PARTY-NOTICES.txt, "Unicode".
package java.time.format;

import java.time.temporal.ChronoField;
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
