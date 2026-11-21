// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A text being read by a formatter: the fields read so far, in the order they were read,
// the zone, and how letters and numbers are read at this point. An optional part that
// fails is undone by restoring a copy; a setting changed inside any part stays changed
// after it (a JDK's answer). A number, a fraction and an instant are read in the
// formatter's decimal style, the digits from its zero and no other; an offset in the
// standard one (a JDK 25's answers).
package java.time.format;

import java.time.ZoneId;
import java.time.chrono.Chronology;
import java.time.temporal.TemporalField;
import java.util.Locale;

final class Reading {

    final CharSequence text;

    /** Where the text ends for the step being read: a padded step sees its width only. */
    int length;

    /** Which words the locale has (Texts.kind). */
    final int texts;

    /**
     * The formatter's locale, which a refusal of a requested template names: set only in a
     * program that makes such a part (Step.templated()).
     */
    Locale locale;

    /**
     * The formatter's calendar a locale's patterns are looked up by, or null: set only in
     * a program with a calendar of its own (Texts.ownCalendars()).
     */
    Chronology chronology;

    boolean caseSensitive = true;

    boolean strict = true;

    TemporalField[] fields = new TemporalField[8];

    long[] values = new long[8];

    int count;

    ZoneId zone;

    /** The time a zone's name in words was of: 0 standard, 1 daylight, 2 generic; -1 none. */
    int zoneTime = -1;

    /** A period of the day read: its first minute and the one after its last; or null. */
    int[] period;

    boolean leapSecond;

    /** The formatter's decimal style where it has one of its own, or null for the standard one. */
    DecimalStyle decimals;

    Reading(CharSequence text, int texts) {
        this.text = text;
        this.length = text.length();
        this.texts = texts;
    }

    Reading copy() {
        Reading copy = new Reading(text, texts);
        copy.length = length;
        copy.decimals = decimals;
        copy.caseSensitive = caseSensitive;
        copy.strict = strict;
        copy.fields = fields.clone();
        copy.values = values.clone();
        copy.count = count;
        copy.zone = zone;
        copy.zoneTime = zoneTime;
        copy.period = period;
        copy.leapSecond = leapSecond;
        return copy;
    }

    /** Undoes what an optional part read; how letters and numbers are read stays as it was set. */
    void restore(Reading saved) {
        fields = saved.fields;
        values = saved.values;
        count = saved.count;
        zone = saved.zone;
        zoneTime = saved.zoneTime;
        period = saved.period;
        leapSecond = saved.leapSecond;
    }

    int indexOf(TemporalField field) {
        for (int i = 0; i < count; i++) {
            if (fields[i] == field) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A field's value read from start to end: where reading goes on, or the complement
     * of start where the field was read before with another value.
     */
    int put(TemporalField field, long value, int start, int end) {
        int index = indexOf(field);
        if (index >= 0) {
            return values[index] == value ? end : ~start;
        }
        if (count == fields.length) {
            fields = java.util.Arrays.copyOf(fields, count * 2);
            values = java.util.Arrays.copyOf(values, count * 2);
        }
        fields[count] = field;
        values[count] = value;
        count++;
        return end;
    }

    boolean same(char a, char b) {
        return a == b || (!caseSensitive && (Character.toUpperCase(a) == Character.toUpperCase(b)
                || Character.toLowerCase(a) == Character.toLowerCase(b)));
    }

    /** Whether a text stands at a position, its letters compared as the reading says. */
    boolean matches(int at, String what) {
        if (at + what.length() > length) {
            return false;
        }
        for (int i = 0; i < what.length(); i++) {
            if (!same(text.charAt(at + i), what.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether an ASCII digit stands at a position: what an offset is written in. */
    boolean digit(int at) {
        if (at >= length) {
            return false;
        }
        char c = text.charAt(at);
        return c >= '0' && c <= '9';
    }

    /** The digit of the decimal style standing at a position, or -1. */
    int numeral(int at) {
        if (at >= length) {
            return -1;
        }
        int digit = text.charAt(at) - '0';
        if (DateTimeFormatter.decimals()) {
            if (decimals != null) {
                digit = text.charAt(at) - decimals.getZeroDigit();
            }
        }
        return digit >= 0 && digit <= 9 ? digit : -1;
    }
}
