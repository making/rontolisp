// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time;

import java.time.zone.ZoneRules;

// A region of the tz database whose offset changes: its name and its rules. Only a
// program that holds such a region makes one (ZoneId.region). What a date-time asks of
// it is answered in numbers by the rules' own class, which is in another package and is
// reached as the compiler reaches it.
final class TzRegion extends ZoneId {

    private final String id;

    private final ZoneRules rules;

    TzRegion(String id, ZoneRules rules) {
        this.id = id;
        this.rules = rules;
    }

    private static native int wall0(ZoneRules rules, long epochSecond);

    private static native int placed0(ZoneRules rules, long localSecond, long[] change);

    public String getId() {
        return id;
    }

    public ZoneRules getRules() {
        return rules;
    }

    /** No one offset: what asks for it asks the rules where a program holds a region. */
    ZoneOffset fixed() {
        return null;
    }

    ZoneOffset offsetAt(long epochSecond) {
        return ZoneOffset.ofTotalSeconds(wall0(rules, epochSecond));
    }

    int placed(long localSecond, long[] change) {
        return placed0(rules, localSecond, change);
    }

    public ZoneId normalized() {
        return this;
    }
}
