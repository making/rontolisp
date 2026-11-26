// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A zone getTimeZone or getDefault answers whose offset does not change: a custom id, GMT,
// UTC, a name of the tz database of one offset (Etc/GMT+5). RegionZone, under it, is a
// region whose offset changes. What a JDK answers of such a zone, beyond the
// documentation, is its answers: its offset given anew by setRawOffset at every instant,
// its rules compared from 1900-01-01T00:00Z on, its daylight saving none.
package java.util;

class NamedZone extends TimeZone {

    private int raw;

    NamedZone(String id, int raw) {
        setID(id);
        this.raw = raw;
    }

    /**
     * The zone of a custom id ({@code GMT}, a sign, hours of one or two ASCII digits, then
     * two of minutes, or minutes after a colon and seconds after another), its id written
     * with two digits each; null where the text is none or past 23:59:59.
     */
    static NamedZone custom(String id) {
        if (id.length() < 5 || !id.startsWith("GMT") || id.charAt(3) != '+' && id.charAt(3) != '-') {
            return null;
        }
        int[] parts = new int[3];
        int[] digits = new int[3];
        int count = 0;
        for (int at = 4; at < id.length(); at++) {
            char ch = id.charAt(at);
            if (ch == ':') {
                if (++count > 2) {
                    return null;
                }
            }
            else if (ch >= '0' && ch <= '9') {
                parts[count] = parts[count] * 10 + ch - '0';
                if (++digits[count] > 4) {
                    return null;
                }
            }
            else {
                return null;
            }
        }
        int hours = parts[0];
        int minutes = parts[1];
        if (count == 0) {
            // one or two digits of hours, or the last two of three or four minutes
            if (digits[0] > 2) {
                hours = parts[0] / 100;
                minutes = parts[0] % 100;
            }
        }
        else if (digits[0] == 0 || digits[0] > 2 || (count == 1 ? digits[1] != 2 : digits[1] == 0 || digits[1] > 2 || digits[2] != 2)) {
            return null;
        }
        if (hours > 23 || minutes > 59 || parts[2] > 59) {
            return null;
        }
        return custom(id.charAt(3) == '-', hours * 3600 + minutes * 60 + (count == 2 ? parts[2] : 0));
    }

    /** The zone of a custom id of an offset, a sign (kept at 0) and the seconds of it. */
    static NamedZone custom(boolean minus, int seconds) {
        String id = "GMT" + (minus ? "-" : "+") + two(seconds / 3600) + ":" + two(seconds / 60 % 60)
                + (seconds % 60 == 0 ? "" : ":" + two(seconds % 60));
        return new NamedZone(id, minus ? -seconds * 1000 : seconds * 1000);
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
        throw new UnsupportedOperationException(
                "TimeZone.getOffset(int, int, int, int, int, int) is not supported by the experimental Java subset yet");
    }

    public int getOffset(long date) {
        return raw;
    }

    public int getRawOffset() {
        return raw;
    }

    public void setRawOffset(int offsetMillis) {
        raw = offsetMillis;
    }

    public boolean useDaylightTime() {
        return false;
    }

    public boolean inDaylightTime(Date date) {
        return false;
    }

    /** The offset at 1900-01-01T00:00Z, from where two such zones' rules are compared. */
    int first() {
        return raw;
    }

    /** Whether the offset changes neither at 1900-01-01T00:00Z nor after. */
    boolean quiet() {
        return true;
    }

    /** Whether the offset changes from 1900-01-01T00:00Z on as another's does. */
    boolean sameChanges(NamedZone other) {
        return other.quiet();
    }

    public boolean hasSameRules(TimeZone other) {
        if (other instanceof NamedZone that) {
            return getRawOffset() == that.getRawOffset() && first() == that.first() && sameChanges(that);
        }
        // another kind of zone: the same where this one keeps its raw offset from 1900 and
        // the other has it and no daylight saving
        return other != null && quiet() && first() == getRawOffset() && other.getRawOffset() == getRawOffset()
                && !other.useDaylightTime();
    }

    public boolean equals(Object other) {
        return other instanceof NamedZone that && getID().equals(that.getID()) && hasSameRules(that);
    }

    public int hashCode() {
        return getID().hashCode() * 31 + getRawOffset();
    }

    int offsets(long instant, int[] split) {
        split[0] = raw;
        split[1] = 0;
        return raw;
    }

    long instant(long local, int past, boolean zoneSet, int zone, boolean dstSet, int dst) {
        return local - (zoneSet ? zone : raw) - (dstSet ? dst : 0);
    }

    boolean skips(long local, long[] change) {
        return false;
    }

}
