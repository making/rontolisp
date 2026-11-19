// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The arithmetic of the proleptic ISO calendar and the texts of ISO-8601 that the
// classes of this package share. A day is counted from 0000-01-01, the first day of a
// leap year: a year starts 365 days after the one before it, and one more for each leap
// year passed (every fourth, but the hundreds that are no four hundred).
package java.time;

final class Iso {

    /** The days from 0000-01-01 to 1970-01-01, the day the epoch days count from. */
    static final long EPOCH = 719528L;

    static final long DAY = 86400L;

    static final long SECOND = 1000000000L;

    /** The day of a year of 365 days each month starts at, and where the year ends. */
    private static final int[] STARTS = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334, 365};

    private Iso() {
    }

    static boolean leap(long year) {
        return (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    /** The days from 0000-01-01 to the first day of a year. */
    static long yearStart(long year) {
        return 365 * year + Math.floorDiv(year + 3, 4L) - Math.floorDiv(year + 99, 100L)
                + Math.floorDiv(year + 399, 400L);
    }

    /** The day of its year, from 0, a month starts at; month 13 is where the year ends. */
    static int monthStart(long year, int month) {
        return STARTS[month - 1] + (month > 2 && leap(year) ? 1 : 0);
    }

    static int monthLength(long year, int month) {
        return month == 2 ? (leap(year) ? 29 : 28) : month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
    }

    static long epochDay(long year, int month, int day) {
        return yearStart(year) + monthStart(year, month) + day - 1 - EPOCH;
    }

    /** The year an epoch day is in: 400 years are 146,097 days, which is right within a year. */
    static long yearOf(long epochDay) {
        long days = epochDay + EPOCH;
        long year = Math.floorDiv(400 * days, 146097L);
        while (yearStart(year) > days) {
            year--;
        }
        while (yearStart(year + 1) <= days) {
            year++;
        }
        return year;
    }

    /** The month a day of a year, from 0, is in: no month is longer than 31 days. */
    static int monthOf(long year, int dayOfYear) {
        int month = dayOfYear / 31 + 1;
        return dayOfYear >= monthStart(year, month + 1) ? month + 1 : month;
    }

    static DateTimeException invalid(String field, long value) {
        return new DateTimeException("Invalid value for " + field + ": " + value);
    }

    /** A value of a field within its limits, or the refusal naming them. */
    static long check(long value, long least, long most, String field) {
        if (value < least || value > most) {
            throw invalid(field + " (valid values " + least + " - " + most + ")", value);
        }
        return value;
    }

    static int checkYear(long year) {
        return (int) check(year, -999999999L, 999999999L, "Year");
    }

    static int checkMonth(long month) {
        return (int) check(month, 1, 12, "MonthOfYear");
    }

    static int checkDay(long day) {
        if (day < 1 || day > 31) {
            throw invalid("DayOfMonth (valid values 1 - 28/31)", day);
        }
        return (int) day;
    }

    static void checkEpochDay(long epochDay) {
        check(epochDay, -365243219162L, 365241780471L, "EpochDay");
    }

    static StringBuilder two(StringBuilder text, int value) {
        if (value < 10) {
            text.append('0');
        }
        return text.append(value);
    }

    /** A year in four digits at least; past 9999 with its sign where plus says so. */
    static StringBuilder year(StringBuilder text, long year, boolean plus) {
        long size = Math.abs(year);
        if (year < 0) {
            text.append('-');
        }
        else if (plus && size > 9999) {
            text.append('+');
        }
        if (size < 1000) {
            text.append(size < 10 ? "000" : size < 100 ? "00" : "0");
        }
        return text.append(size);
    }

    static StringBuilder date(StringBuilder text, long year, int month, int day) {
        year(text, year, true).append('-');
        two(text, month).append('-');
        return two(text, day);
    }

    /**
     * A time of day: the seconds where there are any, or always; the fraction in
     * thousandths, millionths or all nine digits, whichever holds it.
     */
    static StringBuilder time(StringBuilder text, int hour, int minute, int second, int nano, boolean seconds) {
        two(text, hour).append(':');
        two(text, minute);
        if (seconds || second > 0 || nano > 0) {
            text.append(':');
            two(text, second);
            if (nano > 0) {
                String digits = String.valueOf(SECOND + nano);
                text.append('.').append(digits, 1, nano % 1000000 == 0 ? 4 : nano % 1000 == 0 ? 7 : 10);
            }
        }
        return text;
    }
}
