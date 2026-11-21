// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A requested template made a pattern of a locale's (ofLocalizedPattern and its kind).
// The template is the documented letters of its fields in their documented order, j and
// C the locale's hour. Its pattern is that of the skeleton nearest it among the locale's
// (Texts.skeletons): of the same letters, each as many but the month's and the day of the
// week's, which are compared by their widths, the month's first; where none is, the
// patterns of its date and of its time joined as a localized form joins them. No width
// is adjusted. All of it is what a JDK 25 answers, read off its answers to every template
// of bounded counts in the five locales (.kb/java-frontend.md, "java.time: the localized
// pattern of a requested template"), which is not what UTS #35 describes; so are the
// words of the refusals and what a refusal shows of the template.
package java.time.format;

import java.time.DateTimeException;
import java.util.Locale;

final class Skeletons {

    /** The fields of a template in their order: a letter each, h for the hour's, v for a zone's. */
    private static final String FIELDS = "GyQMwEdBhmsv";

    /** The most letters of each field; -1 for as many as there are. */
    private static final int[] MOST = {5, -1, 5, 5, -1, 5, 2, 5, 2, 2, 2, 4};

    /** The first field of a time: B. */
    private static final int TIME = 7;

    private Skeletons() {
    }

    /** The field of a template's letter, or -1. */
    private static int field(char c) {
        return c == 'H' || c == 'j' || c == 'C' ? 8 : c == 'z' ? 11 : FIELDS.indexOf(c);
    }

    /** Whether a template's letters stand in the order of their fields, each field at most as many as it may. */
    static boolean valid(String template) {
        int at = 0;
        for (int f = 0; f < MOST.length; f++) {
            int start = at;
            while (at < template.length() && field(template.charAt(at)) == f) {
                at++;
            }
            if (MOST[f] >= 0 && at - start > MOST[f]) {
                return false;
            }
        }
        return at == template.length();
    }

    /**
     * The template a refusal shows: every j the locale's hour, and the first run of C as
     * many of the hour as the template has C.
     */
    static String shown(String template, char hour) {
        int count = 0;
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) == 'C') {
                count++;
            }
        }
        StringBuilder shown = new StringBuilder(template.length() + count);
        // 0 before the first run of C, 1 in it, 2 after it
        int run = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == 'C' && run < 2) {
                for (int k = run == 0 ? 0 : count; k < count; k++) {
                    shown.append(hour);
                }
                run = 1;
            }
            else {
                run = run == 1 ? 2 : run;
                shown.append(c == 'j' ? hour : c);
            }
        }
        return shown.toString();
    }

    static DateTimeException unresolved(String template, Locale locale) {
        return new DateTimeException(
                "Requested template \"" + template + "\" cannot be resolved in the locale \"" + locale + "\"");
    }

    /**
     * The pattern of a valid template in a kind of locale (Texts.kind), or null where it
     * has none. A template asking for a period of the day (B) and the locale's own hour
     * is matched in the set whose hours are of twelve (Texts.twelve).
     */
    static String pattern(int kind, String template) {
        int set = kind;
        if (template.indexOf('B') >= 0 && (template.indexOf('j') >= 0 || template.indexOf('C') >= 0)) {
            set = Texts.twelve(kind);
        }
        char hour = Texts.hour(set);
        StringBuilder hours = new StringBuilder(template.length());
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            hours.append(c == 'j' || c == 'C' ? hour : c);
        }
        String text = hours.toString();
        String whole = nearest(set, text);
        if (whole != null) {
            return whole;
        }
        int split = 0;
        while (split < text.length() && field(text.charAt(split)) < TIME) {
            split++;
        }
        if (split == 0 || split == text.length()) {
            return null;
        }
        String date = nearest(set, text.substring(0, split));
        String time = nearest(set, text.substring(split));
        return date == null || time == null ? null : date + (set == 0 ? " " : ", ") + time;
    }

    /**
     * The pattern of the skeleton nearest a template in a set and the sets it adds to:
     * one of its letters, by the width of its month, then of its day of the week. A set's
     * skeletons are read before its parent's, and of two as near the first is taken, so
     * that a set's own hides its parent's of the same letters and widths.
     */
    private static String nearest(int set, String template) {
        if (template.isEmpty()) {
            return null;
        }
        int month = width(template, 0, template.length(), 'M', 2);
        int day = width(template, 0, template.length(), 'E', 3);
        char first = template.charAt(0);
        String found = null;
        int byMonth = 0;
        int byDay = 0;
        for (int at = set; at >= 0; at = Texts.parent(at)) {
            // a set's skeletons of one first letter stand together
            String skeletons = Texts.skeletons(at);
            int start = 0;
            while (start < skeletons.length() && skeletons.charAt(start) != first) {
                start = skeletons.indexOf('|', start) + 1;
            }
            while (start < skeletons.length() && skeletons.charAt(start) == first) {
                int is = skeletons.indexOf('=', start);
                int end = skeletons.indexOf('|', is);
                if (alike(skeletons, start, is, template)) {
                    int months = Math.abs(width(skeletons, start, is, 'M', 2) - month);
                    int days = Math.abs(width(skeletons, start, is, 'E', 3) - day);
                    if (found == null || months < byMonth || (months == byMonth && days < byDay)) {
                        found = skeletons.substring(is + 1, end);
                        byMonth = months;
                        byDay = days;
                    }
                }
                start = end + 1;
            }
        }
        return found;
    }

    /**
     * Whether a skeleton, from an index to another of a text, has a template's letters:
     * the same, a run of M or of E counting as one.
     */
    private static boolean alike(String text, int from, int to, String template) {
        int i = from;
        int j = 0;
        while (i < to && j < template.length()) {
            char c = text.charAt(i);
            if (c != template.charAt(j)) {
                return false;
            }
            i++;
            j++;
            if (c == 'M' || c == 'E') {
                while (i < to && text.charAt(i) == c) {
                    i++;
                }
                while (j < template.length() && template.charAt(j) == c) {
                    j++;
                }
            }
        }
        return i == to && j == template.length();
    }

    /**
     * The width a template, from an index to another of a text, asks of a letter,
     * counting the narrowest as one: a month's number of one digit or two, a day's
     * abbreviation of one to three letters; 0 where it has none.
     */
    private static int width(String text, int from, int to, char letter, int least) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == letter) {
                count++;
            }
        }
        return count == 0 ? 0 : Math.max(count, least);
    }
}
