// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A zone: an offset from UTC, or a name with the offset it stands for. The library holds
// no region's rules (the tz database is larger than the programs it would travel in), so
// the names it knows are the ones whose offset never changes: UTC, GMT and UT, alone or
// with an offset behind them, and the fixed zones of the tz database (Etc/GMT+5). A
// region with rules of its own (Europe/Paris) is unknown here, as a name a JDK's
// database lacks is unknown there. Which text is which kind, and the refusals' words,
// are a JDK's answers.
package java.time;

import java.io.Serializable;
import java.time.temporal.TemporalAccessor;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;

public abstract class ZoneId implements Serializable {

    ZoneId() {
    }

    /** The offset the host's clock has now: the one zone the run time can tell. */
    private static native long local0(long epochSecond);

    public static ZoneId systemDefault() {
        // the host answers the seconds east of UTC, doubled, and one more in summer time
        return ZoneOffset.ofTotalSeconds((int) (local0(Instant.now().getEpochSecond()) >> 1));
    }

    /** The seconds a name of the tz database is from UTC for good, or one no offset has. */
    private static int named(String id) {
        boolean etc = id.startsWith("Etc/");
        String name = etc ? id.substring(4) : id;
        if (name.equals("UTC") || name.equals("GMT") || name.equals("UCT") || name.equals("GMT0")
                || name.equals("Greenwich") || name.equals("Universal") || name.equals("Zulu")) {
            return 0;
        }
        if (etc && (name.startsWith("GMT+") || name.startsWith("GMT-")) && name.length() <= 6) {
            // the sign is the one POSIX gives a zone: hours behind Greenwich
            boolean behind = name.charAt(3) == '+';
            int hours = 0;
            for (int i = 4; i < name.length(); i++) {
                char digit = name.charAt(i);
                if (digit < '0' || digit > '9' || (hours == 0 && i > 4)) {
                    return Integer.MIN_VALUE;
                }
                hours = hours * 10 + (digit - '0');
            }
            if (name.length() > 4 && hours <= (behind ? 12 : 14)) {
                return behind ? -hours * 3600 : hours * 3600;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** A zone by its name alone, or null where the text is none the library knows. */
    static ZoneId region(String id) {
        if (id.equals("UT")) {
            return new ZoneRegion(id, ZoneOffset.UTC);
        }
        int seconds = named(id);
        return seconds == Integer.MIN_VALUE ? null : new ZoneRegion(id, ZoneOffset.ofTotalSeconds(seconds));
    }

    /** How long the prefix of a zone written as UTC, GMT or UT and an offset is, or 0. */
    static int prefixed(String id) {
        int length = id.startsWith("UTC") || id.startsWith("GMT") ? 3 : id.startsWith("UT") ? 2 : 0;
        return length > 0 && id.length() > length && (id.charAt(length) == '+' || id.charAt(length) == '-') ? length
                : 0;
    }

    public static ZoneId of(String zoneId) {
        if (zoneId.length() <= 1 || zoneId.charAt(0) == '+' || zoneId.charAt(0) == '-') {
            return ZoneOffset.of(zoneId);
        }
        int prefix = prefixed(zoneId);
        if (prefix > 0) {
            try {
                return ofOffset(zoneId.substring(0, prefix), ZoneOffset.of(zoneId.substring(prefix)));
            }
            catch (DateTimeException invalid) {
                throw new DateTimeException("Invalid ID for offset-based ZoneId: " + zoneId, invalid);
            }
        }
        for (int i = 0; i < zoneId.length(); i++) {
            char c = zoneId.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!letter && (i == 0 || !((c >= '0' && c <= '9') || c == '/' || c == '~' || c == '.' || c == '_'
                    || c == '+' || c == '-'))) {
                throw new DateTimeException("Invalid ID for region-based ZoneId, invalid format: " + zoneId);
            }
        }
        ZoneId region = region(zoneId);
        if (region == null) {
            throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
        }
        return region;
    }

    public static ZoneId ofOffset(String prefix, ZoneOffset offset) {
        if (prefix.isEmpty()) {
            return offset;
        }
        if (!prefix.equals("GMT") && !prefix.equals("UTC") && !prefix.equals("UT")) {
            throw new IllegalArgumentException("prefix should be GMT, UTC or UT, is: " + prefix);
        }
        return new ZoneRegion(offset.getTotalSeconds() == 0 ? prefix : prefix + offset.getId(), offset);
    }

    public static ZoneId from(TemporalAccessor temporal) {
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime.getZone();
        }
        if (temporal instanceof ZoneOffset || temporal instanceof OffsetDateTime) {
            return ZoneOffset.from(temporal);
        }
        throw new DateTimeException("Unable to obtain ZoneId from TemporalAccessor: " + temporal + " of type "
                + temporal.getClass().getName());
    }

    public abstract String getId();

    public abstract ZoneRules getRules();

    /** The one offset the zone has: every zone of the library is fixed. */
    abstract ZoneOffset fixed();

    public ZoneId normalized() {
        return fixed();
    }

    public boolean equals(Object other) {
        return other instanceof ZoneId that && that.getId().equals(getId());
    }

    public int hashCode() {
        return getId().hashCode();
    }

    public String toString() {
        return getId();
    }
}
