// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time;

import java.time.chrono.IsoChronology;
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
import java.util.Objects;

// The twelve months of a year.
public enum Month implements TemporalAccessor, TemporalAdjuster {

    JANUARY, FEBRUARY, MARCH, APRIL, MAY, JUNE, JULY, AUGUST, SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER;

    public static Month of(int month) {
        if (month < 1 || month > 12) {
            throw new DateTimeException("Invalid value for MonthOfYear: " + month);
        }
        return values()[month - 1];
    }

    public static Month from(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        if (temporal instanceof Month month) {
            return month;
        }
        try {
            return of(temporal.get(ChronoField.MONTH_OF_YEAR));
        }
        catch (DateTimeException refused) {
            throw new DateTimeException("Unable to obtain Month from TemporalAccessor: " + temporal + " of type "
                    + temporal.getClass().getName(), refused);
        }
    }

    public int getValue() {
        return ordinal() + 1;
    }

    /** The word of the locale in a style, or the value where the locale has none. */
    public String getDisplayName(TextStyle style, Locale locale) {
        return new DateTimeFormatterBuilder().appendText(ChronoField.MONTH_OF_YEAR, style).toFormatter(locale).format(this);
    }

    public Month plus(long months) {
        return values()[(int) Math.floorMod(ordinal() + months % 12, 12L)];
    }

    public Month minus(long months) {
        return plus(-(months % 12));
    }

    public int length(boolean leapYear) {
        return Iso.monthLength(leapYear ? 4 : 1, getValue());
    }

    public int minLength() {
        return length(false);
    }

    public int maxLength() {
        return length(true);
    }

    public int firstDayOfYear(boolean leapYear) {
        return Iso.monthStart(leapYear ? 4 : 1, getValue()) + 1;
    }

    public Month firstMonthOfQuarter() {
        return values()[ordinal() / 3 * 3];
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field == ChronoField.MONTH_OF_YEAR
                : field != null && field.isSupportedBy(this);
    }

    public long getLong(TemporalField field) {
        if (field == ChronoField.MONTH_OF_YEAR) {
            return getValue();
        }
        if (field instanceof ChronoField) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.MONTH_OF_YEAR, getValue());
    }

    @SuppressWarnings("unchecked")
    public <R> R query(TemporalQuery<R> query) {
        if (Iso.calendars()) {
            if (query == TemporalQueries.chronology()) {
                return (R) IsoChronology.INSTANCE;
            }
        }
        return query == TemporalQueries.precision() ? (R) ChronoUnit.MONTHS
                : query == TemporalQueries.zoneId() ? null : query.queryFrom(this);
    }
}
