// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.zone;

import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

// The provider of the rules of the tz database's names: the one the program was compiled
// with. Asked by name, it is asked as ZoneId.of is, and holds what that holds: the
// compiler notes the names (java.time.ZoneId). No provider of a program's own is
// registered. Which names it has and the refusals' words are a JDK's answers.
public abstract class ZoneRulesProvider {

    protected ZoneRulesProvider() {
    }

    /** The version of the tz database the program was compiled with. */
    private static native String version0();

    public static native Set<String> getAvailableZoneIds();

    public static native ZoneRules getRules(String zoneId, boolean forCaching);

    public static native NavigableMap<String, ZoneRules> getVersions(String zoneId);

    static NavigableMap<String, ZoneRules> versions(String zoneId) {
        ZoneRules rules = getRules(zoneId, false);
        NavigableMap<String, ZoneRules> versions = new TreeMap<>();
        versions.put(version0(), rules);
        return versions;
    }

    /** The rules cannot change while a program runs. */
    public static boolean refresh() {
        return false;
    }
}
