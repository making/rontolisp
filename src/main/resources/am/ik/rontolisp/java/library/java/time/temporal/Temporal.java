// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

// A date, a time or both that a field can be set in and an amount added to.
public interface Temporal extends TemporalAccessor {

    boolean isSupported(TemporalUnit unit);

    default Temporal with(TemporalAdjuster adjuster) {
        return adjuster.adjustInto(this);
    }

    Temporal with(TemporalField field, long newValue);

    default Temporal plus(TemporalAmount amount) {
        return amount.addTo(this);
    }

    Temporal plus(long amountToAdd, TemporalUnit unit);

    default Temporal minus(TemporalAmount amount) {
        return amount.subtractFrom(this);
    }

    default Temporal minus(long amountToSubtract, TemporalUnit unit) {
        return amountToSubtract == Long.MIN_VALUE ? plus(Long.MAX_VALUE, unit).plus(1, unit)
                : plus(-amountToSubtract, unit);
    }

    long until(Temporal endExclusive, TemporalUnit unit);
}
