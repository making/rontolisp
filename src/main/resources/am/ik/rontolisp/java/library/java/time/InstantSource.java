// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What tells the present instant, of no zone. A Clock is one; the sources made here
// are clocks in UTC, as a JDK's are (their texts show it), and a program's own source
// is given a zone by a clock over it.
package java.time;

import java.util.Objects;

@FunctionalInterface
public interface InstantSource {

    static InstantSource system() {
        return Clock.SystemInstantSource.INSTANCE;
    }

    static InstantSource tick(InstantSource baseSource, Duration tickDuration) {
        Objects.requireNonNull(baseSource, "baseSource");
        return Clock.tick(baseSource.withZone(ZoneOffset.UTC), tickDuration);
    }

    static InstantSource fixed(Instant fixedInstant) {
        return Clock.fixed(fixedInstant, ZoneOffset.UTC);
    }

    static InstantSource offset(InstantSource baseSource, Duration offsetDuration) {
        Objects.requireNonNull(baseSource, "baseSource");
        return Clock.offset(baseSource.withZone(ZoneOffset.UTC), offsetDuration);
    }

    Instant instant();

    default long millis() {
        return instant().toEpochMilli();
    }

    default Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return new Clock.SourceClock(this, zone);
    }
}
