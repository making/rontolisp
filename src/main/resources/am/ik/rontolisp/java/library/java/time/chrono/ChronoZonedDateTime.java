// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.chrono;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;

// A date and a time of a calendar in a zone; the ISO calendar's is
// java.time.ZonedDateTime.
public interface ChronoZonedDateTime<D extends ChronoLocalDate>
        extends Temporal, Comparable<ChronoZonedDateTime<?>> {

    D toLocalDate();

    default Chronology getChronology() {
        return toLocalDate().getChronology();
    }

    LocalTime toLocalTime();

    ChronoLocalDateTime<D> toLocalDateTime();

    ZoneOffset getOffset();

    ZoneId getZone();

    long toEpochSecond();

    Instant toInstant();

    int compareTo(ChronoZonedDateTime<?> other);

    boolean isAfter(ChronoZonedDateTime<?> other);

    boolean isBefore(ChronoZonedDateTime<?> other);

    boolean isEqual(ChronoZonedDateTime<?> other);
}
