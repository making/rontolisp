// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The period of a calendar other than ISO's: years, months and days of the calendar. It
// has the name a JDK's has, which getClass() shows. What the documentation leaves open is
// a JDK 25's answers: another amount is added only where it is such a period of the same
// calendar (Unable to obtain ChronoPeriod, Chronology mismatch), and is added to a value
// whose calendar is none or this one; where the calendar's months of a year are a fixed
// count, months are added as months and years as that many months, otherwise years and
// months each by themselves, then the days; normalized makes years of a fixed count of
// months and leaves the others. The text is the calendar's and an ISO period's; the hash
// an ISO period's xor the calendar's.
package java.time.chrono;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.temporal.ValueRange;
import java.util.List;
import java.util.Objects;

final class ChronoPeriodImpl implements ChronoPeriod, Serializable {

    private static final List<TemporalUnit> UNITS = List.of(ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.DAYS);

    private final Chronology chrono;

    private final int years;

    private final int months;

    private final int days;

    ChronoPeriodImpl(Chronology chrono, int years, int months, int days) {
        Objects.requireNonNull(chrono, "chrono");
        this.chrono = chrono;
        this.years = years;
        this.months = months;
        this.days = days;
    }

    public long get(TemporalUnit unit) {
        if (unit == ChronoUnit.YEARS) {
            return years;
        }
        if (unit == ChronoUnit.MONTHS) {
            return months;
        }
        if (unit == ChronoUnit.DAYS) {
            return days;
        }
        throw new UnsupportedTemporalTypeException("Unsupported unit: " + unit);
    }

    public List<TemporalUnit> getUnits() {
        return UNITS;
    }

    public Chronology getChronology() {
        return chrono;
    }

    public boolean isZero() {
        return years == 0 && months == 0 && days == 0;
    }

    public boolean isNegative() {
        return years < 0 || months < 0 || days < 0;
    }

    /** Another amount as a period of this calendar, or its refusal. */
    private ChronoPeriodImpl of(TemporalAmount amount) {
        Objects.requireNonNull(amount, "amount");
        if (!(amount instanceof ChronoPeriodImpl period)) {
            throw new DateTimeException(
                    "Unable to obtain ChronoPeriod from TemporalAmount: class " + amount.getClass().getName());
        }
        if (!chrono.equals(period.getChronology())) {
            throw new ClassCastException("Chronology mismatch, expected: " + chrono.getId() + ", actual: "
                    + period.getChronology().getId());
        }
        return period;
    }

    public ChronoPeriod plus(TemporalAmount amountToAdd) {
        ChronoPeriodImpl amount = of(amountToAdd);
        return new ChronoPeriodImpl(chrono, Math.addExact(years, amount.years), Math.addExact(months, amount.months),
                Math.addExact(days, amount.days));
    }

    public ChronoPeriod minus(TemporalAmount amountToSubtract) {
        ChronoPeriodImpl amount = of(amountToSubtract);
        return new ChronoPeriodImpl(chrono, Math.subtractExact(years, amount.years),
                Math.subtractExact(months, amount.months), Math.subtractExact(days, amount.days));
    }

    public ChronoPeriod multipliedBy(int scalar) {
        if (isZero() || scalar == 1) {
            return this;
        }
        return new ChronoPeriodImpl(chrono, Math.multiplyExact(years, scalar), Math.multiplyExact(months, scalar),
                Math.multiplyExact(days, scalar));
    }

    /** The months of the calendar's year where they are a fixed count, else 0. */
    private long monthsOfYear() {
        ValueRange range = chrono.range(ChronoField.MONTH_OF_YEAR);
        return range.isFixed() && range.isIntValue() ? range.getMaximum() - range.getMinimum() + 1 : 0;
    }

    public ChronoPeriod normalized() {
        long count = monthsOfYear();
        if (count <= 0) {
            return this;
        }
        long total = years * count + months;
        long wholeYears = total / count;
        int rest = (int) (total % count);
        if (wholeYears == years && rest == months) {
            return this;
        }
        return new ChronoPeriodImpl(chrono, Math.toIntExact(wholeYears), rest, days);
    }

    /** A value's calendar checked to be none or this period's. */
    private void calendarOf(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        Chronology other = temporal.query(TemporalQueries.chronology());
        if (other != null && !chrono.equals(other)) {
            throw new DateTimeException("Chronology mismatch, expected: " + chrono.getId() + ", actual: " + other.getId());
        }
    }

    public Temporal addTo(Temporal temporal) {
        calendarOf(temporal);
        long count = monthsOfYear();
        if (months == 0) {
            if (years != 0) {
                temporal = count > 0 ? temporal.plus(years * count, ChronoUnit.MONTHS) : temporal.plus(years, ChronoUnit.YEARS);
            }
        }
        else if (count > 0) {
            temporal = temporal.plus(years * count + months, ChronoUnit.MONTHS);
        }
        else {
            if (years != 0) {
                temporal = temporal.plus(years, ChronoUnit.YEARS);
            }
            temporal = temporal.plus(months, ChronoUnit.MONTHS);
        }
        return days == 0 ? temporal : temporal.plus(days, ChronoUnit.DAYS);
    }

    public Temporal subtractFrom(Temporal temporal) {
        calendarOf(temporal);
        long count = monthsOfYear();
        if (months == 0) {
            if (years != 0) {
                temporal = count > 0 ? temporal.minus(years * count, ChronoUnit.MONTHS)
                        : temporal.minus(years, ChronoUnit.YEARS);
            }
        }
        else if (count > 0) {
            temporal = temporal.minus(years * count + months, ChronoUnit.MONTHS);
        }
        else {
            if (years != 0) {
                temporal = temporal.minus(years, ChronoUnit.YEARS);
            }
            temporal = temporal.minus(months, ChronoUnit.MONTHS);
        }
        return days == 0 ? temporal : temporal.minus(days, ChronoUnit.DAYS);
    }

    public boolean equals(Object obj) {
        return this == obj || (obj instanceof ChronoPeriodImpl other && years == other.years && months == other.months
                && days == other.days && chrono.equals(other.chrono));
    }

    public int hashCode() {
        return (years + Integer.rotateLeft(months, 8) + Integer.rotateLeft(days, 16)) ^ chrono.hashCode();
    }

    public String toString() {
        if (isZero()) {
            return chrono.toString() + " P0D";
        }
        StringBuilder text = new StringBuilder(chrono.toString()).append(" P");
        if (years != 0) {
            text.append(years).append('Y');
        }
        if (months != 0) {
            text.append(months).append('M');
        }
        if (days != 0) {
            text.append(days).append('D');
        }
        return text.toString();
    }
}
