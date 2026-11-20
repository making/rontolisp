// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.chrono;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalQueries;
import java.util.Comparator;
import java.util.Objects;

// A date of a calendar. The library has the ISO calendar alone, whose date is
// java.time.LocalDate; the order of two dates is the order of their epoch days.
public interface ChronoLocalDate extends Temporal, TemporalAdjuster, Comparable<ChronoLocalDate> {

    static Comparator<ChronoLocalDate> timeLineOrder() {
        return (date1, date2) -> Long.compare(date1.toEpochDay(), date2.toEpochDay());
    }

    static ChronoLocalDate from(TemporalAccessor temporal) {
        if (temporal instanceof ChronoLocalDate date) {
            return date;
        }
        Objects.requireNonNull(temporal, "temporal");
        Chronology chronology = temporal.query(TemporalQueries.chronology());
        if (chronology == null) {
            throw new DateTimeException(
                    "Unable to obtain ChronoLocalDate from TemporalAccessor: class " + temporal.getClass().getName());
        }
        return chronology.date(temporal);
    }

    Chronology getChronology();

    default Era getEra() {
        return getChronology().eraOf(get(ChronoField.ERA));
    }

    long toEpochDay();

    boolean isLeapYear();

    int lengthOfMonth();

    int lengthOfYear();

    ChronoPeriod until(ChronoLocalDate endDateExclusive);

    default ChronoLocalDateTime<?> atTime(LocalTime localTime) {
        return LocalDateTime.of(LocalDate.from(this), localTime);
    }

    default String format(DateTimeFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        return formatter.format(this);
    }

    int compareTo(ChronoLocalDate other);

    boolean isAfter(ChronoLocalDate other);

    boolean isBefore(ChronoLocalDate other);

    boolean isEqual(ChronoLocalDate other);
}
