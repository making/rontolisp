// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.chrono;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjuster;

// A date and a time of a calendar; the ISO calendar's is java.time.LocalDateTime.
public interface ChronoLocalDateTime<D extends ChronoLocalDate>
        extends Temporal, TemporalAdjuster, Comparable<ChronoLocalDateTime<?>> {

    D toLocalDate();

    LocalTime toLocalTime();

    long toEpochSecond(ZoneOffset offset);

    Instant toInstant(ZoneOffset offset);

    int compareTo(ChronoLocalDateTime<?> other);

    boolean isAfter(ChronoLocalDateTime<?> other);

    boolean isBefore(ChronoLocalDateTime<?> other);

    boolean isEqual(ChronoLocalDateTime<?> other);
}
