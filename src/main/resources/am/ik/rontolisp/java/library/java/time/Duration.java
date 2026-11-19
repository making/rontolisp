// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An amount of time: whole seconds and the nanoseconds past them, 0 to 999,999,999, so
// half a second before zero is -1 second and 500,000,000 nanoseconds. Where a member's
// answer needs the amount as one signed number, it is read as the seconds toward zero
// and a fraction of the same sign (whole(), fraction()).
package java.time;

import java.time.format.DateTimeParseException;

public final class Duration implements Comparable<Duration> {

    public static final Duration ZERO = new Duration(0, 0);

    private static final long SECOND = 1000000000L;

    private final long seconds;

    private final int nanos;

    private Duration(long seconds, int nanos) {
        this.seconds = seconds;
        this.nanos = nanos;
    }

    /** The duration of seconds and any count of nanoseconds beside them. */
    private static Duration make(long seconds, long nanoAdjustment) {
        long whole = Math.addExact(seconds, Math.floorDiv(nanoAdjustment, SECOND));
        int rest = (int) Math.floorMod(nanoAdjustment, SECOND);
        return whole == 0 && rest == 0 ? ZERO : new Duration(whole, rest);
    }

    public static Duration ofDays(long days) {
        return make(Math.multiplyExact(days, 86400L), 0);
    }

    public static Duration ofHours(long hours) {
        return make(Math.multiplyExact(hours, 3600L), 0);
    }

    public static Duration ofMinutes(long minutes) {
        return make(Math.multiplyExact(minutes, 60L), 0);
    }

    public static Duration ofSeconds(long seconds) {
        return make(seconds, 0);
    }

    public static Duration ofSeconds(long seconds, long nanoAdjustment) {
        return make(seconds, nanoAdjustment);
    }

    public static Duration ofMillis(long millis) {
        return make(Math.floorDiv(millis, 1000L), Math.floorMod(millis, 1000L) * 1000000L);
    }

    public static Duration ofNanos(long nanos) {
        return make(0, nanos);
    }

    /** The seconds counted toward zero. */
    private long whole() {
        return seconds < 0 && nanos > 0 ? seconds + 1 : seconds;
    }

    /** The nanoseconds past whole(), of the duration's sign. */
    private long fraction() {
        return seconds < 0 && nanos > 0 ? nanos - SECOND : nanos;
    }

    public long getSeconds() {
        return seconds;
    }

    public int getNano() {
        return nanos;
    }

    public boolean isZero() {
        return seconds == 0 && nanos == 0;
    }

    public boolean isNegative() {
        return seconds < 0;
    }

    public boolean isPositive() {
        return seconds > 0 || (seconds == 0 && nanos > 0);
    }

    public Duration withSeconds(long seconds) {
        return make(seconds, nanos);
    }

    public Duration withNanos(int nanoOfSecond) {
        if (nanoOfSecond < 0 || nanoOfSecond > 999999999) {
            throw new DateTimeException(
                    "Invalid value for NanoOfSecond (valid values 0 - 999999999): " + nanoOfSecond);
        }
        return make(seconds, nanoOfSecond);
    }

    public Duration plus(Duration duration) {
        return make(Math.addExact(seconds, duration.seconds), (long) nanos + duration.nanos);
    }

    public Duration plusDays(long daysToAdd) {
        return make(Math.addExact(seconds, Math.multiplyExact(daysToAdd, 86400L)), nanos);
    }

    public Duration plusHours(long hoursToAdd) {
        return make(Math.addExact(seconds, Math.multiplyExact(hoursToAdd, 3600L)), nanos);
    }

    public Duration plusMinutes(long minutesToAdd) {
        return make(Math.addExact(seconds, Math.multiplyExact(minutesToAdd, 60L)), nanos);
    }

    public Duration plusSeconds(long secondsToAdd) {
        return make(Math.addExact(seconds, secondsToAdd), nanos);
    }

    public Duration plusMillis(long millisToAdd) {
        return make(Math.addExact(seconds, millisToAdd / 1000L), nanos + millisToAdd % 1000L * 1000000L);
    }

    public Duration plusNanos(long nanosToAdd) {
        return make(Math.addExact(seconds, nanosToAdd / SECOND), nanos + nanosToAdd % SECOND);
    }

    public Duration minus(Duration duration) {
        return make(Math.subtractExact(seconds, duration.seconds), (long) nanos - duration.nanos);
    }

    public Duration minusDays(long daysToSubtract) {
        return make(Math.subtractExact(seconds, Math.multiplyExact(daysToSubtract, 86400L)), nanos);
    }

    public Duration minusHours(long hoursToSubtract) {
        return make(Math.subtractExact(seconds, Math.multiplyExact(hoursToSubtract, 3600L)), nanos);
    }

    public Duration minusMinutes(long minutesToSubtract) {
        return make(Math.subtractExact(seconds, Math.multiplyExact(minutesToSubtract, 60L)), nanos);
    }

    public Duration minusSeconds(long secondsToSubtract) {
        return make(Math.subtractExact(seconds, secondsToSubtract), nanos);
    }

    public Duration minusMillis(long millisToSubtract) {
        return make(Math.subtractExact(seconds, millisToSubtract / 1000L),
                nanos - millisToSubtract % 1000L * 1000000L);
    }

    public Duration minusNanos(long nanosToSubtract) {
        return make(Math.subtractExact(seconds, nanosToSubtract / SECOND), nanos - nanosToSubtract % SECOND);
    }

    public Duration multipliedBy(long multiplicand) {
        if (multiplicand == 0) {
            return ZERO;
        }
        // The seconds and the fraction have one sign, so their products do: the sum
        // leaves a long only when the result does. The fraction's product is taken in
        // two parts, each within a long.
        long fraction = fraction();
        long high = fraction * (multiplicand / SECOND);
        long low = fraction * (multiplicand % SECOND);
        try {
            return make(Math.addExact(Math.addExact(Math.multiplyExact(whole(), multiplicand), high), low / SECOND),
                    low % SECOND);
        }
        catch (ArithmeticException overflow) {
            throw new ArithmeticException("Exceeds capacity of Duration: " + product(multiplicand));
        }
    }

    /** A number's size in three digits of a thousand million each, the lowest first. */
    private static long[] digits(long number) {
        long[] digits = new long[3];
        for (int i = 0; i < 3; i++) {
            digits[i] = Math.abs(number % SECOND);
            number /= SECOND;
        }
        return digits;
    }

    /** The nanoseconds of this duration times a number, in decimal: what leaves a duration. */
    private String product(long multiplicand) {
        long[] left = digits(whole());
        long[] right = digits(multiplicand);
        long[] sum = new long[7];
        sum[0] = Math.abs(fraction());
        for (int i = 0; i < 3; i++) {
            sum[i + 1] = left[i];
        }
        long[] digits = new long[10];
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 3; j++) {
                // a product is below 10^18, and what is carried stays within a digit
                long part = digits[i + j] + sum[i] * right[j];
                digits[i + j] = part % SECOND;
                digits[i + j + 1] += part / SECOND;
            }
        }
        StringBuilder text = new StringBuilder();
        boolean negative = (seconds < 0) != (multiplicand < 0);
        for (int i = 9; i >= 0; i--) {
            if (text.length() > 0) {
                text.append(String.valueOf(SECOND + digits[i]).substring(1));
            }
            else if (digits[i] != 0) {
                text.append(negative ? "-" : "").append(digits[i]);
            }
        }
        return text.toString();
    }

    public Duration negated() {
        return multipliedBy(-1);
    }

    public Duration abs() {
        return isNegative() ? negated() : this;
    }

    public long toDays() {
        return seconds / 86400L;
    }

    public long toHours() {
        return seconds / 3600L;
    }

    public long toMinutes() {
        return seconds / 60L;
    }

    public long toSeconds() {
        return seconds;
    }

    public long toMillis() {
        return Math.addExact(Math.multiplyExact(whole(), 1000L), fraction() / 1000000L);
    }

    public long toNanos() {
        return Math.addExact(Math.multiplyExact(whole(), SECOND), fraction());
    }

    public long toDaysPart() {
        return seconds / 86400L;
    }

    public int toHoursPart() {
        return (int) (toHours() % 24);
    }

    public int toMinutesPart() {
        return (int) (toMinutes() % 60);
    }

    public int toSecondsPart() {
        return (int) (seconds % 60);
    }

    public int toMillisPart() {
        return nanos / 1000000;
    }

    public int toNanosPart() {
        return nanos;
    }

    public int compareTo(Duration otherDuration) {
        int order = Long.compare(seconds, otherDuration.seconds);
        return order != 0 ? order : nanos - otherDuration.nanos;
    }

    public boolean equals(Object other) {
        return other instanceof Duration that && that.seconds == seconds && that.nanos == nanos;
    }

    public int hashCode() {
        return Long.hashCode(seconds) + 51 * nanos;
    }

    public String toString() {
        if (isZero()) {
            return "PT0S";
        }
        long whole = whole();
        long fraction = fraction();
        long hours = whole / 3600;
        int minutes = (int) (whole % 3600 / 60);
        int secs = (int) (whole % 60);
        StringBuilder text = new StringBuilder("PT");
        if (hours != 0) {
            text.append(hours).append('H');
        }
        if (minutes != 0) {
            text.append(minutes).append('M');
        }
        if (secs == 0 && fraction == 0) {
            return text.toString();
        }
        if (secs == 0 && fraction < 0) {
            text.append("-0");
        }
        else {
            text.append(secs);
        }
        if (fraction != 0) {
            String digits = String.valueOf(SECOND + Math.abs(fraction)).substring(1);
            int end = digits.length();
            while (digits.charAt(end - 1) == '0') {
                end--;
            }
            text.append('.').append(digits, 0, end);
        }
        return text.append('S').toString();
    }

    /** Where a number of a text ends: the index after its sign and digits, -1 with no digit. */
    private static int number(String text, int from) {
        int at = from;
        if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            at++;
        }
        int digits = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
            at++;
        }
        return at == digits ? -1 : at;
    }

    private static DateTimeParseException unparsed(String text, String what, Throwable cause) {
        String message = "Text cannot be parsed to a Duration" + (what == null ? "" : ": " + what);
        return cause == null ? new DateTimeParseException(message, text, 0)
                : new DateTimeParseException(message, text, 0, cause);
    }

    /** One section's number times the seconds of its unit. */
    private static long section(String text, int from, int to, long unit, String what) {
        String number = text.substring(from, to);
        boolean negative = number.charAt(0) == '-';
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        // counted below zero, where a long has one number more
        long count = 0;
        for (int i = negative || number.charAt(0) == '+' ? 1 : 0; i < number.length(); i++) {
            int digit = number.charAt(i) - '0';
            if (count < limit / 10 || count * 10 < limit + digit) {
                throw unparsed(text, what,
                        new NumberFormatException("Error at index " + i + " in: \"" + number + "\""));
            }
            count = count * 10 - digit;
        }
        try {
            return Math.multiplyExact(negative ? count : -count, unit);
        }
        catch (ArithmeticException failed) {
            throw unparsed(text, what, failed);
        }
    }

    public static Duration parse(CharSequence text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        String s = text.toString();
        int at = 0;
        boolean negate = false;
        if (at < s.length() && (s.charAt(at) == '+' || s.charAt(at) == '-')) {
            negate = s.charAt(at) == '-';
            at++;
        }
        if (at >= s.length() || (s.charAt(at) != 'P' && s.charAt(at) != 'p')) {
            throw unparsed(s, null, null);
        }
        at++;
        long days = 0;
        long hours = 0;
        long minutes = 0;
        long seconds = 0;
        long fraction = 0;
        boolean any = false;
        int end = number(s, at);
        if (end >= 0 && end < s.length() && (s.charAt(end) == 'D' || s.charAt(end) == 'd')) {
            days = section(s, at, end, 86400L, "days");
            at = end + 1;
            any = true;
        }
        if (at < s.length() && (s.charAt(at) == 'T' || s.charAt(at) == 't')) {
            at++;
            boolean timed = false;
            end = number(s, at);
            if (end >= 0 && end < s.length() && (s.charAt(end) == 'H' || s.charAt(end) == 'h')) {
                hours = section(s, at, end, 3600L, "hours");
                at = end + 1;
                timed = true;
                end = number(s, at);
            }
            if (end >= 0 && end < s.length() && (s.charAt(end) == 'M' || s.charAt(end) == 'm')) {
                minutes = section(s, at, end, 60L, "minutes");
                at = end + 1;
                timed = true;
                end = number(s, at);
            }
            if (end >= 0) {
                int point = end;
                if (end < s.length() && (s.charAt(end) == '.' || s.charAt(end) == ',')) {
                    end++;
                    while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') {
                        end++;
                    }
                }
                if (end < s.length() && (s.charAt(end) == 'S' || s.charAt(end) == 's') && end - point <= 10) {
                    seconds = section(s, at, point, 1L, "seconds");
                    for (int i = point + 1; i < point + 10; i++) {
                        fraction = fraction * 10 + (i < end ? s.charAt(i) - '0' : 0);
                    }
                    if (s.charAt(at) == '-') {
                        fraction = -fraction;
                    }
                    at = end + 1;
                    timed = true;
                }
            }
            if (!timed) {
                throw unparsed(s, null, null);
            }
            any = true;
        }
        if (!any || at != s.length()) {
            throw unparsed(s, null, null);
        }
        try {
            Duration parsed = make(Math.addExact(days, Math.addExact(hours, Math.addExact(minutes, seconds))),
                    fraction);
            return negate ? parsed.negated() : parsed;
        }
        catch (ArithmeticException overflow) {
            throw unparsed(s, "overflow", overflow);
        }
    }
}
