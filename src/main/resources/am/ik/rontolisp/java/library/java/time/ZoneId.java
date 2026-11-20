// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A zone: an offset from UTC, or a name with the offset it stands for. The names every
// program knows are the ones whose offset never changes: UTC, GMT and UT, alone or with
// an offset behind them, and the fixed zones of the tz database (Etc/GMT+5), computed.
// A region whose offset changes (Europe/Paris) is known to a program that HOLDS it: the
// tz database is larger than most programs it would travel in, so the compiler gives a
// program the regions it names -- each one a constant names, all of them where a name is
// computed or the names are asked for -- and regional() answers whether it gave any.
// What is written under it is in no program that holds none, and to such a program a
// region is unknown, as a name a JDK's database lacks is unknown there. Which text is
// which kind, and the refusals' words, are a JDK's answers.
package java.time;

import java.io.Serializable;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public abstract class ZoneId implements Serializable {

    /** The short names a JDK gives zones, made only in a program that reads them. */
    public static final Map<String, String> SHORT_IDS = wanted() ? shortIds() : null;

    ZoneId() {
    }

    /** Whether a constant of the class is read: answered by the compiler. */
    private static native boolean wanted();

    /** The short names and their zones, each name=zone, separated by spaces. */
    private static native String short0();

    private static Map<String, String> shortIds() {
        String text = short0();
        Map<String, String> ids = new HashMap<>();
        for (int at = 0; at < text.length();) {
            int equals = text.indexOf('=', at);
            int end = text.indexOf(' ', equals);
            end = end < 0 ? text.length() : end;
            ids.put(text.substring(at, equals), text.substring(equals + 1, end));
            at = end + 1;
        }
        return Map.copyOf(ids);
    }

    /** The offset the host's clock has now: the one zone the run time can tell. */
    private static native long local0(long epochSecond);

    /**
     * Whether the program holds a region of the tz database: answered by the compiler,
     * and the statement or the operand it decides is all that is compiled of the two.
     */
    static native boolean regional();

    /** The target a symbolic link of the host holds, or null. */
    private static native String link0(String path);

    /**
     * The name of the host's zone, or null: TZ without a leading colon, or where that is
     * unset or empty the file of the zone database the link /etc/localtime leads to.
     */
    private static String hostZone() {
        String named = System.getenv("TZ");
        if (named != null && named.startsWith(":")) {
            named = named.substring(1);
        }
        if (named == null || named.isEmpty()) {
            String link = link0("/etc/localtime");
            int at = link == null ? -1 : link.indexOf("zoneinfo/");
            named = at < 0 ? null : link.substring(at + 9);
        }
        return named;
    }

    public static ZoneId systemDefault() {
        if (regional()) {
            // the zone the host names, where the program knows it
            String named = hostZone();
            ZoneId region = named == null ? null : region(named);
            if (region != null) {
                return region;
            }
        }
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

    /** The rules of a region the program holds, or null: read in their own package. */
    private static native ZoneRules held0(String id);

    private static native void names0(Set<String> names);

    /** A region the program holds by its name, or null. */
    private static ZoneId held(String id) {
        ZoneRules rules = held0(id);
        return rules == null ? null
                // the one offset of rules that change nothing is theirs at any instant
                : rules.isFixedOffset() ? new ZoneRegion(id, rules.getStandardOffset(null)) : new TzRegion(id, rules);
    }

    /** A zone by its name alone, or null where the text is none the library knows. */
    static ZoneId region(String id) {
        if (regional()) {
            ZoneId held = held(id);
            if (held != null) {
                return held;
            }
        }
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

    /** A program naming a zone holds it: the compiler notes the name, then this is called. */
    public static native ZoneId of(String zoneId);

    /**
     * A zone by its name or the one a map gives it: the compiler notes the name the map
     * gives a constant where the map is SHORT_IDS, every region otherwise.
     */
    public static native ZoneId of(String zoneId, Map<String, String> aliasMap);

    static ZoneId aliased(String zoneId, Map<String, String> aliasMap) {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(aliasMap, "aliasMap");
        String alias = aliasMap.get(zoneId);
        return ofId(alias != null ? alias : zoneId);
    }

    /** The names of the zones, which only a program holding every region can ask for. */
    public static native Set<String> getAvailableZoneIds();

    /** The names ZoneRulesProvider has: those of the zones, not to be changed. */
    static Set<String> providedIds() {
        return Collections.unmodifiableSet(ids());
    }

    /**
     * The rules of a name of the tz database, as ZoneRulesProvider gives them: no offset,
     * alone or after a prefix, and not UT, is such a name.
     */
    static ZoneRules provided(String zoneId, boolean forCaching) {
        Objects.requireNonNull(zoneId, "zoneId");
        ZoneId zone = zoneId.equals("UT") || prefixed(zoneId) > 0 ? null : region(zoneId);
        if (zone == null) {
            throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
        }
        return zone.getRules();
    }

    static Set<String> ids() {
        Set<String> ids = new HashSet<>();
        String[] greenwich = {"UTC", "GMT", "UCT", "GMT0", "Greenwich", "Universal", "Zulu"};
        for (String name : greenwich) {
            ids.add(name);
            ids.add("Etc/" + name);
        }
        for (int hours = 0; hours <= 14; hours++) {
            ids.add("Etc/GMT-" + hours);
            if (hours <= 12) {
                ids.add("Etc/GMT+" + hours);
            }
        }
        names0(ids);
        return ids;
    }

    static ZoneId ofId(String zoneId) {
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
        if (Iso.foreign()) {
            ZoneId zone = temporal.query(TemporalQueries.zone());
            if (zone != null) {
                return zone;
            }
        }
        throw new DateTimeException("Unable to obtain ZoneId from TemporalAccessor: " + temporal + " of type "
                + temporal.getClass().getName());
    }

    public abstract String getId();

    public abstract ZoneRules getRules();

    /** The one offset the zone has; a region whose offset changes has none. */
    abstract ZoneOffset fixed();

    /** The offset the zone has at a second, asked where a program holds a region. */
    ZoneOffset offsetAt(long epochSecond) {
        return fixed();
    }

    /**
     * What a local second is in the zone, asked where a program holds a region: 0 where
     * it has one offset, the third of the numbers; 1 in the gap and 2 in the overlap of a
     * change, whose second and offsets before and after are the three numbers.
     */
    int placed(long localSecond, long[] change) {
        change[2] = fixed().getTotalSeconds();
        return 0;
    }

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
