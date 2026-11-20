// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A text being read as one of the ISO-8601 forms: a place in it and the fields read so
// far. What a form takes and where a text that is none stops being one -- the index a
// refusal names -- are a JDK's answers to a grid of texts; a text is read first, its
// fields are checked after, the date ahead of the time and the offset last.
package java.time;

import java.time.format.DateTimeParseException;

final class IsoText {

    final String text;

    int at;

    long year;

    int month;

    int day;

    int hour;

    int minute;

    int second;

    int nano;

    /** The offset in seconds; what offset() read last. */
    int offset;

    IsoText(CharSequence text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        this.text = text.toString();
    }

    private String shown() {
        return "Text '" + (text.length() > 64 ? text.substring(0, 64) + "..." : text) + "' could not be parsed";
    }

    DateTimeParseException failed(int index) {
        return new DateTimeParseException(shown() + " at index " + index, text, index);
    }

    DateTimeParseException refused(RuntimeException cause) {
        return new DateTimeParseException(shown() + ": " + cause.getMessage(), text, 0, cause);
    }

    /** Nothing may follow what was read. */
    void end() {
        if (at != text.length()) {
            throw new DateTimeParseException(shown() + ", unparsed text found at index " + at, text, at);
        }
    }

    boolean more() {
        return at < text.length();
    }

    boolean next(char wanted) {
        return at < text.length() && text.charAt(at) == wanted;
    }

    /** How many digits stand at an index, counted as far as a limit. */
    private int digits(int from, int limit) {
        int count = 0;
        while (count < limit && from + count < text.length() && text.charAt(from + count) >= '0'
                && text.charAt(from + count) <= '9') {
            count++;
        }
        return count;
    }

    private long number(int from, int count) {
        long value = 0;
        for (int i = from; i < from + count; i++) {
            value = value * 10 + (text.charAt(i) - '0');
        }
        return value;
    }

    /** A field of two digits. */
    int two() {
        if (digits(at, 2) < 2) {
            throw failed(at);
        }
        at += 2;
        return (int) number(at - 2, 2);
    }

    void literal(char wanted) {
        if (!next(wanted)) {
            throw failed(at);
        }
        at++;
    }

    /** A literal of several characters, refused where it starts. */
    void literal(String wanted) {
        if (!text.startsWith(wanted, at)) {
            throw failed(at);
        }
        at += wanted.length();
    }

    /**
     * A year: four digits; more, as many as ten, behind a sign, which a plus has to
     * have; no year zero behind a minus.
     */
    void year() {
        int start = at;
        boolean signed = next('+') || next('-');
        int from = signed ? at + 1 : at;
        int count = digits(from, 10);
        if (count < 4) {
            throw failed(from);
        }
        year = number(from, count);
        if (signed ? (text.charAt(start) == '+' ? count == 4 : year == 0) : count > 4) {
            throw failed(start);
        }
        if (signed && text.charAt(start) == '-') {
            year = -year;
        }
        at = from + count;
    }

    void date() {
        year();
        literal('-');
        month = two();
        literal('-');
        day = two();
    }

    /** The mark between a date and a time, in either case. */
    void mark() {
        if (!next('T') && !next('t')) {
            throw failed(at);
        }
        at++;
    }

    /** A point and as many as nine digits after it, where there is a point. */
    private void fraction() {
        nano = 0;
        if (next('.')) {
            at++;
            int count = digits(at, 9);
            nano = (int) number(at, count);
            for (int i = count; i < 9; i++) {
                nano *= 10;
            }
            at += count;
        }
    }

    /** A time of day, whose seconds a text may leave out. */
    void time() {
        hour = two();
        literal(':');
        minute = two();
        second = 0;
        nano = 0;
        if (next(':') && digits(at + 1, 2) == 2) {
            at++;
            second = two();
            fraction();
        }
    }

    /** A time of day with its seconds, as an instant is written. */
    void timeToTheSecond() {
        hour = two();
        literal(':');
        minute = two();
        literal(':');
        second = two();
        fraction();
    }

    /** Two digits behind a colon, below sixty: their value, or -1 and nothing read. */
    private int part() {
        if (next(':') && digits(at + 1, 2) == 2) {
            int value = (int) number(at + 1, 2);
            if (value < 60) {
                at += 3;
                return value;
            }
        }
        return -1;
    }

    /**
     * An offset written with its sign at an index: where it ends, or -1 where the text
     * is none. The hours, and minutes and seconds each behind a colon; the hours alone
     * are an offset only where brief says so. Hours of 24 to 59 are read and refused
     * at once, whatever else the text holds.
     */
    int signed(int from, boolean brief) {
        int start = at;
        at = from;
        if ((!next('+') && !next('-')) || digits(from + 1, 2) < 2) {
            at = start;
            return -1;
        }
        int hours = (int) number(from + 1, 2);
        at = from + 3;
        int minutes = part();
        int seconds = minutes < 0 ? -1 : part();
        int end = at;
        at = start;
        if (hours > 59 || (minutes < 0 && !brief)) {
            return -1;
        }
        if (hours > 23) {
            throw refused(new DateTimeException("Value out of range: Hour[0-23], Minute[0-59], Second[0-59]"));
        }
        offset = hours * 3600 + Math.max(minutes, 0) * 60 + Math.max(seconds, 0);
        if (text.charAt(from) == '-') {
            offset = -offset;
        }
        return end;
    }

    /** An offset: Z in either case, or one with its sign. */
    void offset(boolean brief) {
        if (next('Z') || next('z')) {
            at++;
            offset = 0;
            return;
        }
        int end = signed(at, brief);
        if (end < 0) {
            throw failed(at);
        }
        at = end;
    }

    /** The date read, its fields checked: the year, the month, the day, the date. */
    LocalDate localDate() {
        try {
            return LocalDate.of(Iso.checkYear(year), month, day);
        }
        catch (DateTimeException invalid) {
            throw refused(invalid);
        }
    }

    LocalTime localTime() {
        try {
            // the order a JDK refuses them in: the minute, the hour, the second
            Iso.check(minute, 0, 59, "MinuteOfHour");
            Iso.check(hour, 0, 23, "HourOfDay");
            return LocalTime.of(hour, minute, second, nano);
        }
        catch (DateTimeException invalid) {
            throw refused(invalid);
        }
    }

    LocalDateTime localDateTime() {
        return LocalDateTime.of(localDate(), localTime());
    }

    ZoneOffset zoneOffset() {
        try {
            return ZoneOffset.ofTotalSeconds(offset);
        }
        catch (DateTimeException invalid) {
            throw refused(invalid);
        }
    }
}
