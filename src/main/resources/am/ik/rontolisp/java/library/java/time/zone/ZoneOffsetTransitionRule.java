// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A change of offset that comes every year: in a month, on a day counted from its start
// or (a negative number) from its end, moved to a day of the week where one is given --
// forward from a day counted from the start, back from one counted from the end -- at a
// time read from one of three clocks. It is held in numbers (Yearly), which is what the
// rules of a zone compute with. The words of the refusals and of the text are a JDK's
// answers; the hash code is a number of ours.
package java.time.zone;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;

public final class ZoneOffsetTransitionRule implements Serializable {

    /** The clock a rule's time is read from. */
    public enum TimeDefinition {

        UTC, WALL, STANDARD;

        /** A date-time of this clock as the wall clock shows it. */
        public LocalDateTime createDateTime(LocalDateTime dateTime, ZoneOffset standardOffset,
                ZoneOffset wallOffset) {
            int ahead = this == UTC ? wallOffset.getTotalSeconds()
                    : this == STANDARD ? wallOffset.getTotalSeconds() - standardOffset.getTotalSeconds() : 0;
            return dateTime.plusSeconds(ahead);
        }
    }

    final Yearly yearly;

    ZoneOffsetTransitionRule(Yearly yearly) {
        this.yearly = yearly;
    }

    private static void given(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }

    public static ZoneOffsetTransitionRule of(Month month, int dayOfMonthIndicator, DayOfWeek dayOfWeek,
            LocalTime time, boolean timeEndOfDay, TimeDefinition timeDefinition, ZoneOffset standardOffset,
            ZoneOffset offsetBefore, ZoneOffset offsetAfter) {
        given(month, "month");
        given(time, "time");
        given(timeDefinition, "timeDefinition");
        given(standardOffset, "standardOffset");
        given(offsetBefore, "offsetBefore");
        given(offsetAfter, "offsetAfter");
        if (dayOfMonthIndicator < -28 || dayOfMonthIndicator > 31 || dayOfMonthIndicator == 0) {
            throw new IllegalArgumentException(
                    "Day of month indicator must be between -28 and 31 inclusive excluding zero");
        }
        if (timeEndOfDay && !time.equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException("Time must be midnight when end of day flag is true");
        }
        if (time.getNano() != 0) {
            throw new IllegalArgumentException("Time's nano-of-second must be zero");
        }
        return new ZoneOffsetTransitionRule(new Yearly(month.getValue(), dayOfMonthIndicator,
                dayOfWeek == null ? 0 : dayOfWeek.getValue(), timeEndOfDay ? 86400 : time.toSecondOfDay(),
                timeDefinition.ordinal(), standardOffset.getTotalSeconds(), offsetBefore.getTotalSeconds(),
                offsetAfter.getTotalSeconds()));
    }

    public Month getMonth() {
        return Month.of(yearly.month);
    }

    public int getDayOfMonthIndicator() {
        return yearly.day;
    }

    public DayOfWeek getDayOfWeek() {
        return yearly.dayOfWeek == 0 ? null : DayOfWeek.of(yearly.dayOfWeek);
    }

    public LocalTime getLocalTime() {
        return LocalTime.ofSecondOfDay(yearly.time % 86400);
    }

    public boolean isMidnightEndOfDay() {
        return yearly.time == 86400;
    }

    public TimeDefinition getTimeDefinition() {
        return TimeDefinition.values()[yearly.clock];
    }

    public ZoneOffset getStandardOffset() {
        return ZoneOffset.ofTotalSeconds(yearly.standard);
    }

    public ZoneOffset getOffsetBefore() {
        return ZoneOffset.ofTotalSeconds(yearly.before);
    }

    public ZoneOffset getOffsetAfter() {
        return ZoneOffset.ofTotalSeconds(yearly.after);
    }

    public ZoneOffsetTransition createTransition(int year) {
        if (yearly.day > 28) {
            // a day the month does not have that year is refused as a date's is
            LocalDate.of(year, yearly.month, yearly.day);
        }
        return yearly.made(yearly.at(year));
    }

    public boolean equals(Object other) {
        return other instanceof ZoneOffsetTransitionRule that && that.yearly.same(yearly);
    }

    public int hashCode() {
        return yearly.hash();
    }

    public String toString() {
        StringBuilder text = new StringBuilder("TransitionRule[").append(yearly.after > yearly.before ? "Gap " : "Overlap ")
            .append(getOffsetBefore())
            .append(" to ")
            .append(getOffsetAfter())
            .append(", ");
        if (yearly.dayOfWeek == 0) {
            text.append(getMonth()).append(' ').append(yearly.day);
        }
        else if (yearly.day > 0) {
            text.append(getDayOfWeek()).append(" on or after ").append(getMonth()).append(' ').append(yearly.day);
        }
        else {
            text.append(getDayOfWeek()).append(" on or before last day ");
            if (yearly.day < -1) {
                text.append("minus ").append(-yearly.day - 1).append(' ');
            }
            text.append("of ").append(getMonth());
        }
        return text.append(" at ")
            .append(isMidnightEndOfDay() ? "24:00" : getLocalTime().toString())
            .append(' ')
            .append(getTimeDefinition())
            .append(", standard offset ")
            .append(getStandardOffset())
            .append(']')
            .toString();
    }
}
