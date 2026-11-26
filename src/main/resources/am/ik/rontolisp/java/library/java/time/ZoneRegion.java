// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time;

import java.time.zone.ZoneRules;

// A zone with a name: the name and the offset it stands for.
final class ZoneRegion extends ZoneId {

    private final String id;

    private final ZoneOffset offset;

    ZoneRegion(String id, ZoneOffset offset) {
        this.id = id;
        this.offset = offset;
    }

    public String getId() {
        return id;
    }

    public ZoneRules getRules() {
        return ZoneRules.of(offset);
    }

    ZoneOffset fixed() {
        return offset;
    }
}
