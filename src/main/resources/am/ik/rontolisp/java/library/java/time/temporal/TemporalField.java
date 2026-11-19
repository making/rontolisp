// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

// A field of a date or a time. ChronoField has the fields of the library; a program may
// write its own.
public interface TemporalField {

    TemporalUnit getBaseUnit();

    TemporalUnit getRangeUnit();

    ValueRange range();

    boolean isDateBased();

    boolean isTimeBased();

    boolean isSupportedBy(TemporalAccessor temporal);

    ValueRange rangeRefinedBy(TemporalAccessor temporal);

    long getFrom(TemporalAccessor temporal);

    <R extends Temporal> R adjustInto(R temporal, long newValue);

    String toString();
}
