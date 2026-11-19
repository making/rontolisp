// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

import java.time.Duration;

// A unit amounts are counted in. ChronoUnit has the units of the library; a program may
// write its own.
public interface TemporalUnit {

    Duration getDuration();

    boolean isDurationEstimated();

    boolean isDateBased();

    boolean isTimeBased();

    default boolean isSupportedBy(Temporal temporal) {
        return temporal.isSupported(this);
    }

    <R extends Temporal> R addTo(R temporal, long amount);

    long between(Temporal temporal1Inclusive, Temporal temporal2Exclusive);

    String toString();
}
