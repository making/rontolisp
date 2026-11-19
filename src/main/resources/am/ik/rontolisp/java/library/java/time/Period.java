// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An amount of time in years, months and days, each with a sign of its own: a month is
// not a number of days until it is added to a date. Its text is a P, then any of a
// number of years, months, weeks and days in that order, in either case.
package java.time;

import java.io.Serializable;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;

public final class Period implements TemporalAmount, Serializable {

    public static final Period ZERO = new Period(0, 0, 0);

    private final int years;

    private final int months;

    private final int days;

    private Period(int years, int months, int days) {
        this.years = years;
        this.months = months;
        this.days = days;
    }

    public static Period ofYears(int years) {
        return of(years, 0, 0);
    }

    public static Period ofMonths(int months) {
        return of(0, months, 0);
    }

    public static Period ofWeeks(int weeks) {
        return of(0, 0, Math.multiplyExact(weeks, 7));
    }

    public static Period ofDays(int days) {
        return of(0, 0, days);
    }

    public static Period of(int years, int months, int days) {
        return (years | months | days) == 0 ? ZERO : new Period(years, months, days);
    }

    public static Period from(TemporalAmount amount) {
        if (amount instanceof Period period) {
            return period;
        }
        int years = 0;
        int months = 0;
        int days = 0;
        for (TemporalUnit unit : amount.getUnits()) {
            long count = amount.get(unit);
            if (unit == ChronoUnit.YEARS) {
                years = Math.toIntExact(count);
            }
            else if (unit == ChronoUnit.MONTHS) {
                months = Math.toIntExact(count);
            }
            else if (unit == ChronoUnit.DAYS) {
                days = Math.toIntExact(count);
            }
            else {
                throw new DateTimeException("Unit must be Years, Months or Days, but was " + unit);
            }
        }
        return of(years, months, days);
    }

    public static Period between(LocalDate startDateInclusive, LocalDate endDateExclusive) {
        return startDateInclusive.until(endDateExclusive);
    }

    private static DateTimeParseException unparsed(String text, Throwable cause) {
        String message = "Text cannot be parsed to a Period";
        return cause == null ? new DateTimeParseException(message, text, 0)
                : new DateTimeParseException(message, text, 0, cause);
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

    /** One section's number as an int, of the text's sign. */
    private static int section(String text, int from, int to, boolean negate) {
        String number = text.substring(from, to);
        boolean negative = number.charAt(0) == '-';
        int limit = negative ? Integer.MIN_VALUE : -Integer.MAX_VALUE;
        // counted below zero, where an int has one number more
        int count = 0;
        for (int i = negative || number.charAt(0) == '+' ? 1 : 0; i < number.length(); i++) {
            int digit = number.charAt(i) - '0';
            if (count < limit / 10 || count * 10 < limit + digit) {
                throw unparsed(text, new NumberFormatException("Error at index " + i + " in: \"" + number + "\""));
            }
            count = count * 10 - digit;
        }
        try {
            return Math.multiplyExact(negative ? count : -count, negate ? -1 : 1);
        }
        catch (ArithmeticException failed) {
            throw unparsed(text, failed);
        }
    }

    public static Period parse(CharSequence text) {
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
            throw unparsed(s, null);
        }
        at++;
        // the four sections in their order: years, months, weeks, days
        int[] counts = new int[4];
        String marks = "YMWD";
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            int end = number(s, at);
            if (end >= 0 && end < s.length() && Character.toUpperCase(s.charAt(end)) == marks.charAt(i)) {
                counts[i] = section(s, at, end, negate);
                at = end + 1;
                any = true;
            }
        }
        if (!any || at != s.length()) {
            throw unparsed(s, null);
        }
        return of(counts[0], counts[1], Math.addExact(counts[3], Math.multiplyExact(counts[2], 7)));
    }

    public long get(TemporalUnit unit) {
        if (unit == ChronoUnit.YEARS) {
            return years;
        }
        if (unit == ChronoUnit.MONTHS) {
            return months;
        }
        if (unit == ChronoUnit.DAYS) {
            return days;
        }
        throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
    }

    public List<TemporalUnit> getUnits() {
        return List.of(ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.DAYS);
    }

    public boolean isZero() {
        return this == ZERO;
    }

    public boolean isNegative() {
        return years < 0 || months < 0 || days < 0;
    }

    public int getYears() {
        return years;
    }

    public int getMonths() {
        return months;
    }

    public int getDays() {
        return days;
    }

    public Period withYears(int years) {
        return years == this.years ? this : of(years, months, days);
    }

    public Period withMonths(int months) {
        return months == this.months ? this : of(years, months, days);
    }

    public Period withDays(int days) {
        return days == this.days ? this : of(years, months, days);
    }

    public Period plus(TemporalAmount amountToAdd) {
        Period other = from(amountToAdd);
        return of(Math.addExact(years, other.years), Math.addExact(months, other.months),
                Math.addExact(days, other.days));
    }

    public Period plusYears(long yearsToAdd) {
        return yearsToAdd == 0 ? this : of(Math.toIntExact(Math.addExact((long) years, yearsToAdd)), months, days);
    }

    public Period plusMonths(long monthsToAdd) {
        return monthsToAdd == 0 ? this
                : of(years, Math.toIntExact(Math.addExact((long) months, monthsToAdd)), days);
    }

    public Period plusDays(long daysToAdd) {
        return daysToAdd == 0 ? this : of(years, months, Math.toIntExact(Math.addExact((long) days, daysToAdd)));
    }

    public Period minus(TemporalAmount amountToSubtract) {
        Period other = from(amountToSubtract);
        return of(Math.subtractExact(years, other.years), Math.subtractExact(months, other.months),
                Math.subtractExact(days, other.days));
    }

    public Period minusYears(long yearsToSubtract) {
        return yearsToSubtract == Long.MIN_VALUE ? plusYears(Long.MAX_VALUE).plusYears(1)
                : plusYears(-yearsToSubtract);
    }

    public Period minusMonths(long monthsToSubtract) {
        return monthsToSubtract == Long.MIN_VALUE ? plusMonths(Long.MAX_VALUE).plusMonths(1)
                : plusMonths(-monthsToSubtract);
    }

    public Period minusDays(long daysToSubtract) {
        return daysToSubtract == Long.MIN_VALUE ? plusDays(Long.MAX_VALUE).plusDays(1) : plusDays(-daysToSubtract);
    }

    public Period multipliedBy(int scalar) {
        if (this == ZERO || scalar == 1) {
            return this;
        }
        return of(Math.multiplyExact(years, scalar), Math.multiplyExact(months, scalar),
                Math.multiplyExact(days, scalar));
    }

    public Period negated() {
        return multipliedBy(-1);
    }

    /** The years and months as twelve months a year, of one sign; the days as they are. */
    public Period normalized() {
        long total = toTotalMonths();
        long wholeYears = total / 12;
        int rest = (int) (total % 12);
        return wholeYears == years && rest == months ? this : of(Math.toIntExact(wholeYears), rest, days);
    }

    public long toTotalMonths() {
        return years * 12L + months;
    }

    public Temporal addTo(Temporal temporal) {
        if (months == 0) {
            if (years != 0) {
                temporal = temporal.plus(years, ChronoUnit.YEARS);
            }
        }
        else {
            long total = toTotalMonths();
            if (total != 0) {
                temporal = temporal.plus(total, ChronoUnit.MONTHS);
            }
        }
        return days == 0 ? temporal : temporal.plus(days, ChronoUnit.DAYS);
    }

    public Temporal subtractFrom(Temporal temporal) {
        if (months == 0) {
            if (years != 0) {
                temporal = temporal.minus(years, ChronoUnit.YEARS);
            }
        }
        else {
            long total = toTotalMonths();
            if (total != 0) {
                temporal = temporal.minus(total, ChronoUnit.MONTHS);
            }
        }
        return days == 0 ? temporal : temporal.minus(days, ChronoUnit.DAYS);
    }

    public boolean equals(Object other) {
        return other instanceof Period that && that.years == years && that.months == months && that.days == days;
    }

    public int hashCode() {
        return years + Integer.rotateLeft(months, 8) + Integer.rotateLeft(days, 16);
    }

    public String toString() {
        if (this == ZERO) {
            return "P0D";
        }
        StringBuilder text = new StringBuilder("P");
        if (years != 0) {
            text.append(years).append('Y');
        }
        if (months != 0) {
            text.append(months).append('M');
        }
        if (days != 0) {
            text.append(days).append('D');
        }
        return text.toString();
    }
}
