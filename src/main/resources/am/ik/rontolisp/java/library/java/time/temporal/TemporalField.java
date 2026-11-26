// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    /** The field's name in a locale: its text, unless the field knows a word for it. */
    default String getDisplayName(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return toString();
    }

    /**
     * What a formatter's fields become with this one: called while a text's fields are
     * resolved, it may take fields out of the map, put simpler ones in, or answer a date,
     * a time or both. None here.
     */
    default TemporalAccessor resolve(Map<TemporalField, Long> fieldValues, TemporalAccessor partialTemporal,
            ResolverStyle resolverStyle) {
        return null;
    }

    String toString();
}
