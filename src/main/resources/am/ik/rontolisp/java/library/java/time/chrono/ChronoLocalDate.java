// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.chrono;

import java.time.temporal.Temporal;
import java.time.temporal.TemporalAdjuster;

// A date of a calendar. The library has the ISO calendar alone, whose date is
// java.time.LocalDate; the order of two dates is the order of their epoch days.
public interface ChronoLocalDate extends Temporal, TemporalAdjuster, Comparable<ChronoLocalDate> {

    long toEpochDay();

    boolean isLeapYear();

    int lengthOfMonth();

    int lengthOfYear();

    int compareTo(ChronoLocalDate other);

    boolean isAfter(ChronoLocalDate other);

    boolean isBefore(ChronoLocalDate other);

    boolean isEqual(ChronoLocalDate other);
}
