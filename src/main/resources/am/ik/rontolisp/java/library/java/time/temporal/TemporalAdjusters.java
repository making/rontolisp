// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The adjusters the API documentation names, each written over the fields it states:
// the day of the month or of the year set, then a month or a year added, or days added
// to reach a day of the week.
package java.time.temporal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class TemporalAdjusters {

    private TemporalAdjusters() {
    }

    public static TemporalAdjuster ofDateAdjuster(UnaryOperator<LocalDate> dateBasedAdjuster) {
        Objects.requireNonNull(dateBasedAdjuster, "dateBasedAdjuster");
        return temporal -> temporal.with(dateBasedAdjuster.apply(LocalDate.from(temporal)));
    }

    public static TemporalAdjuster firstDayOfMonth() {
        return temporal -> temporal.with(ChronoField.DAY_OF_MONTH, 1);
    }

    public static TemporalAdjuster lastDayOfMonth() {
        return temporal -> temporal.with(ChronoField.DAY_OF_MONTH,
                temporal.range(ChronoField.DAY_OF_MONTH).getMaximum());
    }

    public static TemporalAdjuster firstDayOfNextMonth() {
        return temporal -> temporal.with(ChronoField.DAY_OF_MONTH, 1).plus(1, ChronoUnit.MONTHS);
    }

    public static TemporalAdjuster firstDayOfYear() {
        return temporal -> temporal.with(ChronoField.DAY_OF_YEAR, 1);
    }

    public static TemporalAdjuster lastDayOfYear() {
        return temporal -> temporal.with(ChronoField.DAY_OF_YEAR, temporal.range(ChronoField.DAY_OF_YEAR).getMaximum());
    }

    public static TemporalAdjuster firstDayOfNextYear() {
        return temporal -> temporal.with(ChronoField.DAY_OF_YEAR, 1).plus(1, ChronoUnit.YEARS);
    }

    public static TemporalAdjuster firstInMonth(DayOfWeek dayOfWeek) {
        return dayOfWeekInMonth(1, dayOfWeek);
    }

    public static TemporalAdjuster lastInMonth(DayOfWeek dayOfWeek) {
        return dayOfWeekInMonth(-1, dayOfWeek);
    }

    /**
     * The ordinal day of a week in the month: from the month's first day forward, the
     * first such day being 1 and 0 the one a week before it; from its last day back for
     * a negative ordinal, the last such day being -1.
     */
    public static TemporalAdjuster dayOfWeekInMonth(int ordinal, DayOfWeek dayOfWeek) {
        Objects.requireNonNull(dayOfWeek, "dayOfWeek");
        int wanted = dayOfWeek.getValue();
        if (ordinal >= 0) {
            return temporal -> {
                Temporal first = temporal.with(ChronoField.DAY_OF_MONTH, 1);
                long days = Math.floorMod(wanted - first.get(ChronoField.DAY_OF_WEEK), 7) + (ordinal - 1L) * 7;
                return first.plus(days, ChronoUnit.DAYS);
            };
        }
        return temporal -> {
            Temporal last = temporal.with(ChronoField.DAY_OF_MONTH, temporal.range(ChronoField.DAY_OF_MONTH).getMaximum());
            long days = Math.floorMod(last.get(ChronoField.DAY_OF_WEEK) - wanted, 7) + (-1L - ordinal) * 7;
            return last.minus(days, ChronoUnit.DAYS);
        };
    }

    public static TemporalAdjuster next(DayOfWeek dayOfWeek) {
        return forward(dayOfWeek, false);
    }

    public static TemporalAdjuster nextOrSame(DayOfWeek dayOfWeek) {
        return forward(dayOfWeek, true);
    }

    public static TemporalAdjuster previous(DayOfWeek dayOfWeek) {
        return back(dayOfWeek, false);
    }

    public static TemporalAdjuster previousOrSame(DayOfWeek dayOfWeek) {
        return back(dayOfWeek, true);
    }

    /** The days forward to a day of the week: up to seven, none where same and it is that day. */
    private static TemporalAdjuster forward(DayOfWeek dayOfWeek, boolean same) {
        int wanted = dayOfWeek.getValue();
        return temporal -> {
            int days = Math.floorMod(wanted - temporal.get(ChronoField.DAY_OF_WEEK), 7);
            if (days == 0) {
                return same ? temporal : temporal.plus(7, ChronoUnit.DAYS);
            }
            return temporal.plus(days, ChronoUnit.DAYS);
        };
    }

    private static TemporalAdjuster back(DayOfWeek dayOfWeek, boolean same) {
        int wanted = dayOfWeek.getValue();
        return temporal -> {
            int days = Math.floorMod(temporal.get(ChronoField.DAY_OF_WEEK) - wanted, 7);
            if (days == 0) {
                return same ? temporal : temporal.minus(7, ChronoUnit.DAYS);
            }
            return temporal.minus(days, ChronoUnit.DAYS);
        };
    }
}
