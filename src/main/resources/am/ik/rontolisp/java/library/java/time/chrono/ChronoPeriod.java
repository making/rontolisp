// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An amount of years, months and days of a calendar: java.time.Period is ISO's, any
// other calendar's is ChronoPeriodImpl.
package java.time.chrono;

import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Objects;

public interface ChronoPeriod extends TemporalAmount {

    static ChronoPeriod between(ChronoLocalDate startDateInclusive, ChronoLocalDate endDateExclusive) {
        Objects.requireNonNull(startDateInclusive, "startDateInclusive");
        Objects.requireNonNull(endDateExclusive, "endDateExclusive");
        return startDateInclusive.until(endDateExclusive);
    }

    long get(TemporalUnit unit);

    List<TemporalUnit> getUnits();

    Chronology getChronology();

    default boolean isZero() {
        for (TemporalUnit unit : getUnits()) {
            if (get(unit) != 0) {
                return false;
            }
        }
        return true;
    }

    default boolean isNegative() {
        for (TemporalUnit unit : getUnits()) {
            if (get(unit) < 0) {
                return true;
            }
        }
        return false;
    }

    ChronoPeriod plus(TemporalAmount amountToAdd);

    ChronoPeriod minus(TemporalAmount amountToSubtract);

    ChronoPeriod multipliedBy(int scalar);

    default ChronoPeriod negated() {
        return multipliedBy(-1);
    }

    ChronoPeriod normalized();

    Temporal addTo(Temporal temporal);

    Temporal subtractFrom(Temporal temporal);
}
