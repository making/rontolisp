// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A zone's offsets from UTC over time. The zones getTimeZone and getDefault answer are
// NamedZone (one offset) and RegionZone (a region of the tz database whose offset changes,
// as java.time holds it); a SimpleTimeZone and a zone of the program's own answer through
// the members a subclass gives. What each member does where the documentation leaves it
// open -- which texts are ids, the words, the host's zone, the offsets a calendar takes --
// is a JDK's answers.
package java.util;

import java.io.Serializable;
import java.time.ZoneId;
import java.time.ZoneOffset;

public abstract class TimeZone implements Serializable, Cloneable {

    public static final int SHORT = 0;

    public static final int LONG = 1;

    private String id;

    /** The default zone, read from the host once; each answer is a copy of it. */
    private static TimeZone host;

    public TimeZone() {
    }

    public abstract int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds);

    public int getOffset(long date) {
        return inDaylightTime(new Date(date)) ? getRawOffset() + getDSTSavings() : getRawOffset();
    }

    public abstract void setRawOffset(int offsetMillis);

    public abstract int getRawOffset();

    public String getID() {
        return id;
    }

    public void setID(String ID) {
        if (ID == null) {
            throw new NullPointerException();
        }
        id = ID;
    }

    public int getDSTSavings() {
        return useDaylightTime() ? 3600000 : 0;
    }

    public abstract boolean useDaylightTime();

    public boolean observesDaylightTime() {
        return useDaylightTime() || inDaylightTime(new Date());
    }

    public abstract boolean inDaylightTime(Date date);

    /**
     * A zone by its id: a region, a three-letter id, a custom id, else GMT. A program naming
     * a region holds it, so the compiler notes the id where the call is (all the regions
     * for an id computed where the program runs), then this is the library's named.
     */
    public static native TimeZone getTimeZone(String ID);

    public static TimeZone getTimeZone(ZoneId zoneId) {
        String id = zoneId.getId();
        if (zoneId instanceof ZoneOffset offset) {
            int seconds = offset.getTotalSeconds();
            return seconds == 0 ? new NamedZone("UTC", 0) : NamedZone.custom(seconds < 0, Math.abs(seconds));
        }
        if (id.equals("UTC")) {
            return new NamedZone("UTC", 0);
        }
        if (id.equals("GMT") || id.equals("UT")) {
            return new NamedZone("GMT", 0);
        }
        if (zoneId.normalized() instanceof ZoneOffset offset) {
            // UTC, GMT or UT and an offset is the custom id of the offset
            boolean prefixed = (id.startsWith("UTC") || id.startsWith("GMT")) && id.length() > 3
                    && (id.charAt(3) == '+' || id.charAt(3) == '-')
                    || id.startsWith("UT") && id.length() > 2 && (id.charAt(2) == '+' || id.charAt(2) == '-');
            int seconds = offset.getTotalSeconds();
            return prefixed ? NamedZone.custom(seconds < 0, Math.abs(seconds)) : new NamedZone(id, seconds * 1000);
        }
        // a region whose offset changes, which only a program holding regions has
        return regional() ? new RegionZone(id, zoneId.getRules(), RegionZone.week(id)) : null;
    }

    public static TimeZone getDefault() {
        return (TimeZone) host().clone();
    }

    public boolean hasSameRules(TimeZone other) {
        return other != null && getRawOffset() == other.getRawOffset() && useDaylightTime() == other.useDaylightTime();
    }

    public Object clone() {
        return rontolisp.internal.Support.copy(this);
    }

    // ---- the zones getTimeZone and getDefault answer

    /** Whether the program holds a region of the tz database: answered by the compiler. */
    static native boolean regional();

    /** A region or a fixed name of the tz database by its name, as java.time knows it, or null. */
    private static native ZoneId region0(String name);

    /**
     * The name of the host's zone, or null: what java.time reads the host's zone by. The
     * host names it where the program runs, so a program asking for it holds every region
     * (answered by the compiler).
     */
    private static native String host0();

    /** The three-letter ids and the regions they stand for, each id=region, separated by spaces. */
    private static native String short0();

    /** The zone getTimeZone(String) answers. */
    static TimeZone named(String ID) {
        TimeZone zone = found(ID);
        return zone != null ? zone : new NamedZone("GMT", 0);
    }

    /**
     * The zone an id names, or null: a name of java.time's (as written), a three-letter id
     * (with a warning to the standard error, at every call), a custom id (normalized).
     */
    private static TimeZone found(String id) {
        if (!id.equals("UT")) {
            TimeZone zone = region(id, id);
            if (zone != null) {
                return zone;
            }
        }
        String region = id.length() == 3 ? shortId(id) : null;
        if (region != null) {
            System.err.println("WARNING: Use of the three-letter time zone ID \"" + id
                    + "\" is deprecated and it will be removed in a future release");
            return region(region, id);
        }
        return NamedZone.custom(id);
    }

    /** The zone of a name of java.time's under an id, or null where java.time knows none. */
    private static TimeZone region(String name, String id) {
        ZoneId zone = region0(name);
        if (zone == null) {
            return null;
        }
        if (zone.normalized() instanceof ZoneOffset offset) {
            return new NamedZone(id, offset.getTotalSeconds() * 1000);
        }
        return regional() ? new RegionZone(id, zone.getRules(), RegionZone.week(name)) : null;
    }

    /** The region a three-letter id stands for, or null. */
    private static String shortId(String id) {
        String ids = short0();
        for (int at = 0; at < ids.length();) {
            int end = ids.indexOf(' ', at);
            end = end < 0 ? ids.length() : end;
            if (ids.startsWith(id, at) && ids.charAt(at + id.length()) == '=') {
                return ids.substring(at + id.length() + 1, end);
            }
            at = end + 1;
        }
        return null;
    }

    /**
     * The default zone: the zone the host's name names, else the custom id of a POSIX
     * string's offset, else GMT.
     */
    private static synchronized TimeZone host() {
        if (host == null) {
            String name = host0();
            TimeZone zone = name == null ? null : found(name);
            if (zone == null && name != null) {
                zone = posix(name);
            }
            host = zone != null ? zone : new NamedZone("GMT", 0);
        }
        return host;
    }

    /**
     * The zone of a POSIX string, or null where the text is none: a name of three ASCII
     * letters or more, or any in angle brackets, then the hours west of UTC (a sign, one
     * or two digits, minutes and seconds of one or two after colons), then anything. Its
     * offset, the sign turned and the seconds dropped, is GMT at 0 and a custom id else;
     * past 23 hours the text is none.
     */
    private static TimeZone posix(String text) {
        int at = 0;
        if (text.startsWith("<")) {
            at = text.indexOf('>') + 1;
            if (at == 0) {
                return null;
            }
        }
        else {
            while (at < text.length() && (text.charAt(at) >= 'a' && text.charAt(at) <= 'z'
                    || text.charAt(at) >= 'A' && text.charAt(at) <= 'Z')) {
                at++;
            }
            if (at < 3) {
                return null;
            }
        }
        boolean east = at < text.length() && text.charAt(at) == '-';
        if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) {
            at++;
        }
        int[] parts = new int[3];
        for (int part = 0; part < 3; part++) {
            if (part > 0) {
                if (at >= text.length() || text.charAt(at) != ':') {
                    break;
                }
                at++;
            }
            int digits = 0;
            while (digits < 2 && at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                parts[part] = parts[part] * 10 + text.charAt(at++) - '0';
                digits++;
            }
            if (digits == 0) {
                if (part == 0) {
                    return null;
                }
                break;
            }
        }
        if (parts[0] > 23 || parts[1] > 59) {
            return null;
        }
        int seconds = parts[0] * 3600 + parts[1] * 60;
        return seconds == 0 ? new NamedZone("GMT", 0) : NamedZone.custom(!east, seconds);
    }

    // ---- what a calendar asks of a zone; NamedZone answers for the zones above

    /**
     * The offset at an instant, and the two parts a calendar's ZONE_OFFSET and DST_OFFSET
     * take into split: the raw offset and the rest.
     */
    int offsets(long instant, int[] split) {
        int total = getOffset(instant);
        int raw = getRawOffset();
        split[0] = raw;
        split[1] = total - raw;
        return total;
    }

    /**
     * The instant a local time names, read with the raw offset or a ZONE_OFFSET the program
     * set, and with the zone's savings where it is in daylight saving at the local time less
     * that offset, or a DST_OFFSET the program set. Past is 0 where the local time is within
     * a long, else -1 or 1: the local time is then the exact one less a multiple of 2^64.
     */
    long instant(long local, int past, boolean zoneSet, int zone, boolean dstSet, int dst) {
        int raw = zoneSet ? zone : getRawOffset();
        int daylight = dstSet ? dst : inDaylightTime(new Date(local - raw)) ? getDSTSavings() : 0;
        return local - raw - daylight;
    }

    /**
     * Whether the clock skips a local time: the instant it is read as shows another; where
     * it does, the offsets before and after the change into change.
     */
    boolean skips(long local, long[] change) {
        long instant = instant(local, 0, false, 0, false, 0);
        if (instant + getOffset(instant) == local) {
            return false;
        }
        int raw = getRawOffset();
        change[0] = raw;
        change[1] = (long) raw + getDSTSavings();
        return true;
    }

}
