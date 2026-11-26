// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A region of the tz database whose offset changes, as getTimeZone and getDefault answer
// it: java.time's rules of the region (held by the program, java.time.ZoneId), read three
// ways. Before 1900-01-01T00:00Z the raw offset, java.time's standard offset when the zone
// was made; from then the rules; from 2100 in the raw offset's local time, the rules where
// they go on by yearly rules, else the raw offset -- and Asia/Gaza's and Asia/Hebron's
// yearly change that falls on a Saturday the 24th a week later. All of it is a JDK's
// answers, as is a local time read across a change: the offset before a change the clock
// skips, the one after where it shows the time twice. What a JDK answers in the long's
// last three years, or once setRawOffset gave the zone another raw offset, no rule here
// says, and it is refused.
//
// The rules are asked in numbers of their own class (java.time.zone.ZoneChanges), which
// is in another package: the native members below are its static methods.
package java.util;

import java.time.zone.ZoneRules;

final class RegionZone extends NamedZone {

    private static final long DAY = 86400000L;

    /** 1900-01-01T00:00Z. */
    private static final long Y1900 = -2208988800000L;

    /** 2100-01-01T00:00Z. */
    private static final long Y2100 = 4102444800000L;

    /** The long's last three years, of which no rule is made. */
    private static final long END = Long.MAX_VALUE - 3 * 366 * DAY;

    /** No offset is farther from UTC than eighteen hours. */
    private static final long REACH = 64800000L;

    private final ZoneRules rules;

    /** The raw offset the zone was made with. */
    private final int own;

    /** Whether a yearly change on a Saturday the 24th comes a week later from 2100. */
    private final boolean week;

    /** Whether the rules go on by yearly rules past their last listed change. */
    private final boolean yearly;

    private final int savings;

    RegionZone(String id, ZoneRules rules, boolean week) {
        super(id, 1000 * standard0(rules, Math.floorDiv(System.currentTimeMillis(), 1000L)));
        this.rules = rules;
        this.own = getRawOffset();
        this.week = week;
        this.yearly = yearly0(rules) > 0;
        this.savings = 1000 * savings0(rules);
    }

    /** Whether a region's yearly changes on a Saturday the 24th come a week later from 2100. */
    static boolean week(String name) {
        return name.equals("Asia/Gaza") || name.equals("Asia/Hebron");
    }

    private static native int wall0(ZoneRules rules, long second);

    private static native int standard0(ZoneRules rules, long second);

    private static native boolean previous0(ZoneRules rules, long second, long[] change);

    private static native boolean same0(ZoneRules one, ZoneRules other, long second);

    private static native boolean quiet0(ZoneRules rules, long second);

    private static native int yearly0(ZoneRules rules);

    private static native int savings0(ZoneRules rules);

    /** The offsets of the region are its own: not given another raw offset, and within the long's last three years. */
    private void kept(long instant) {
        if (getRawOffset() != own) {
            throw new UnsupportedOperationException("the offsets of a region of the tz database given another raw offset"
                    + " are not supported by the experimental Java subset yet");
        }
        if (instant > END) {
            throw new UnsupportedOperationException(
                    "the offsets of a region of the tz database in the last three years a long can hold"
                            + " are not supported by the experimental Java subset yet");
        }
    }

    public int getOffset(long date) {
        kept(date);
        if (date < Y1900) {
            return own;
        }
        if (date < Y2100 - own) {
            return 1000 * wall0(rules, Math.floorDiv(date, 1000L));
        }
        if (!yearly) {
            return own;
        }
        long[] change = new long[3];
        change(date, change);
        return (int) change[2];
    }

    /** The standard part of the offset at an instant: java.time's from 1900 to 2100, else the raw offset. */
    private int standard(long instant) {
        kept(instant);
        return instant >= Y1900 && instant < Y2100 - own ? 1000 * standard0(rules, Math.floorDiv(instant, 1000L)) : own;
    }

    public boolean useDaylightTime() {
        return yearly;
    }

    public int getDSTSavings() {
        return savings;
    }

    public boolean inDaylightTime(Date date) {
        long instant = date.getTime();
        return getOffset(instant) != standard(instant);
    }

    int first() {
        return 1000 * wall0(rules, Y1900 / 1000);
    }

    boolean quiet() {
        return quiet0(rules, Y1900 / 1000);
    }

    boolean sameChanges(NamedZone other) {
        return other instanceof RegionZone that ? same0(rules, that.rules, Y1900 / 1000) : quiet();
    }

    /**
     * The last change of the offset at or before an instant, into change: its instant, the
     * offsets before and after it, in milliseconds; false before 1900, where there is none.
     * 1900-01-01T00:00Z and the instant 2100 begins at are changes too, from the raw offset
     * to the rules' and from the rules' to the raw offset or the yearly rules'.
     */
    private boolean change(long instant, long[] change) {
        if (instant < Y1900) {
            return false;
        }
        kept(instant);
        long later = Y2100 - own;
        if (instant < later) {
            if (previous0(rules, Math.floorDiv(instant, 1000L), change) && change[0] * 1000 > Y1900) {
                scaled(change, change[0] * 1000);
                return true;
            }
            change[0] = Y1900;
            change[1] = own;
            change[2] = 1000L * wall0(rules, Y1900 / 1000);
            return true;
        }
        if (yearly) {
            long second = Math.floorDiv(instant, 1000L);
            while (previous0(rules, second, change) && change[0] * 1000 >= later) {
                long at = change[0] * 1000;
                if (week && saturday24(at + change[1] * 1000)) {
                    if (at + 7 * DAY <= instant) {
                        scaled(change, at + 7 * DAY);
                        return true;
                    }
                    // a week later: not come yet, so the change before it is the last
                    second = change[0] - 1;
                    continue;
                }
                scaled(change, at);
                return true;
            }
        }
        change[0] = later;
        change[1] = 1000L * wall0(rules, Math.floorDiv(later - 1, 1000L));
        change[2] = yearly ? 1000L * wall0(rules, Math.floorDiv(later, 1000L)) : own;
        return true;
    }

    /** A change of the rules' in seconds made one of milliseconds at an instant. */
    private static void scaled(long[] change, long at) {
        change[0] = at;
        change[1] *= 1000;
        change[2] *= 1000;
    }

    /** Whether a local time is on a Saturday the 24th. */
    private static boolean saturday24(long local) {
        long day = Math.floorDiv(local, DAY);
        long[] date = new long[3];
        CalendarDays.date(day, date);
        return date[2] == 24 && CalendarDays.dayOfWeek(day) == 7;
    }

    /**
     * What a local time is: 0 where one offset reads it, 1 a time the clock skips, 2 one
     * it shows twice; the change met into change, or the raw offset into change[2] before
     * any. A local time is read with the offset of the last change it is after, which is
     * at most eighteen hours past it.
     */
    private int placed(long local, long[] change) {
        long bound = local > Long.MAX_VALUE - REACH ? Long.MAX_VALUE : local + REACH;
        while (change(bound, change)) {
            long at = change[0];
            long before = change[1];
            long after = change[2];
            if (local >= at + Math.max(before, after)) {
                return 0;
            }
            if (local >= at + Math.min(before, after)) {
                return before < after ? 1 : 2;
            }
            bound = at - 1;
        }
        change[2] = own;
        return 0;
    }

    int offsets(long instant, int[] split) {
        int total = getOffset(instant);
        split[0] = standard(instant);
        split[1] = total - split[0];
        return total;
    }

    long instant(long local, int past, boolean zoneSet, int zone, boolean dstSet, int dst) {
        // past the long, a local time is before 1900 or in its last three years
        kept(past > 0 ? Long.MAX_VALUE : past < 0 ? Long.MIN_VALUE : local);
        long offset = own;
        long standard = own;
        if (past == 0) {
            long[] change = new long[3];
            int placed = placed(local, change);
            offset = placed == 1 ? change[1] : change[2];
            // the standard part where the offset is: before the change for a skipped time
            standard = standard(placed == 1 ? change[0] - 1 : local - offset);
        }
        return local - (zoneSet ? zone : standard) - (dstSet ? dst : offset - standard);
    }

    boolean skips(long local, long[] change) {
        kept(local);
        long[] met = new long[3];
        if (placed(local, met) != 1) {
            return false;
        }
        change[0] = met[1];
        change[1] = met[2];
        return true;
    }

}
