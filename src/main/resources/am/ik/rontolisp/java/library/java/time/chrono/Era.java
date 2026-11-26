// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// An era of a calendar: a value with the one field ERA, written as its value's words.
package java.time.chrono;

import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.TemporalQuery;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.Locale;

public interface Era extends TemporalAccessor, TemporalAdjuster {

    int getValue();

    default boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field == ChronoField.ERA : field != null && field.isSupportedBy(this);
    }

    default long getLong(TemporalField field) {
        if (field == ChronoField.ERA) {
            return getValue();
        }
        if (field instanceof ChronoField) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    @SuppressWarnings("unchecked")
    default <R> R query(TemporalQuery<R> query) {
        return query == TemporalQueries.precision() ? (R) ChronoUnit.ERAS : TemporalAccessor.super.query(query);
    }

    default Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.ERA, getValue());
    }

    /** The era's word in a style and a locale, or its value where the locale has none. */
    default String getDisplayName(TextStyle style, Locale locale) {
        return new DateTimeFormatterBuilder().appendText(ChronoField.ERA, style).toFormatter(locale).format(this);
    }
}
