// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.zone;

import java.time.LocalDate;

// A change of offset that comes every year, in numbers: what a ZoneOffsetTransitionRule
// states, and what the rules of a zone compute with. The month, a day of it counted from
// its start or (negative) from its end, a day of the week (0 for none) the change waits
// for from a day counted from the start and goes back to from one counted from the end,
// the second of the day (86400 for its end), the clock that time is read from (0 UTC, 1
// the wall clock before the change, 2 standard time), and three offsets in seconds.
final class Yearly {

    final int month;

    final int day;

    final int dayOfWeek;

    final int time;

    final int clock;

    final int standard;

    final int before;

    final int after;

    Yearly(int month, int day, int dayOfWeek, int time, int clock, int standard, int before, int after) {
        this.month = month;
        this.day = day;
        this.dayOfWeek = dayOfWeek;
        this.time = time;
        this.clock = clock;
        this.standard = standard;
        this.before = before;
        this.after = after;
    }

    /** The second of the epoch the offset changes at in a year. */
    long at(int year) {
        LocalDate first = LocalDate.of(year, month, 1);
        long date = first.toEpochDay() + (day < 0 ? first.lengthOfMonth() + day : day - 1);
        if (dayOfWeek != 0) {
            // the days back to that day of the week: 1970-01-01 was a Thursday
            long back = Math.floorMod(date + 4 - dayOfWeek, 7L);
            date += day < 0 ? -back : (7 - back) % 7;
        }
        return date * 86400 + time - (clock == 0 ? 0 : clock == 1 ? before : standard);
    }

    /** The change it makes at a second it changes the offset at. */
    ZoneOffsetTransition made(long second) {
        return ZoneOffsetTransition.at(second, before, after);
    }

    boolean same(Yearly that) {
        return that.month == month && that.day == day && that.dayOfWeek == dayOfWeek && that.time == time
                && that.clock == clock && that.standard == standard && that.before == before && that.after == after;
    }

    int hash() {
        return ((((month * 64 + day) * 8 + dayOfWeek) * 4 + clock) * 86401 + time) ^ standard
                ^ Integer.rotateLeft(before, 8) ^ Integer.rotateLeft(after, 16);
    }
}
