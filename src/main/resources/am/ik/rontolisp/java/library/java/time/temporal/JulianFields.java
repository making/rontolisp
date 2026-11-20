// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Three counts of days from a day of their own, each the epoch day plus a number: the
// Julian day (from -4713-11-24, noon to noon read as midnight to midnight), the
// modified Julian day (from 1858-11-17) and the rata die (from 0001-01-01 as day 1).
package java.time.temporal;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.Objects;

public final class JulianFields {

    public static final TemporalField JULIAN_DAY = new DayCount("JulianDay", 2440588L);

    public static final TemporalField MODIFIED_JULIAN_DAY = new DayCount("ModifiedJulianDay", 40587L);

    public static final TemporalField RATA_DIE = new DayCount("RataDie", 719163L);

    private JulianFields() {
    }

    /** A count of days: the epoch day plus its offset. */
    private static final class DayCount implements TemporalField {

        private final String name;

        private final long offset;

        private final ValueRange range;

        DayCount(String name, long offset) {
            this.name = name;
            this.offset = offset;
            ValueRange days = ChronoField.EPOCH_DAY.range();
            this.range = ValueRange.of(days.getMinimum() + offset, days.getMaximum() + offset);
        }

        public TemporalUnit getBaseUnit() {
            return ChronoUnit.DAYS;
        }

        public TemporalUnit getRangeUnit() {
            return ChronoUnit.FOREVER;
        }

        public ValueRange range() {
            return range;
        }

        public boolean isDateBased() {
            return true;
        }

        public boolean isTimeBased() {
            return false;
        }

        public boolean isSupportedBy(TemporalAccessor temporal) {
            return temporal.isSupported(ChronoField.EPOCH_DAY);
        }

        public ValueRange rangeRefinedBy(TemporalAccessor temporal) {
            if (!isSupportedBy(temporal)) {
                throw new DateTimeException("Unsupported field: " + name);
            }
            return range;
        }

        public long getFrom(TemporalAccessor temporal) {
            return temporal.getLong(ChronoField.EPOCH_DAY) + offset;
        }

        @SuppressWarnings("unchecked")
        public <R extends Temporal> R adjustInto(R temporal, long newValue) {
            if (!range.isValidValue(newValue)) {
                throw new DateTimeException("Invalid value: " + name + " " + newValue);
            }
            return (R) temporal.with(ChronoField.EPOCH_DAY, Math.subtractExact(newValue, offset));
        }

        /** The day the count names, checked against the count's range but leniently. */
        public TemporalAccessor resolve(Map<TemporalField, Long> fieldValues, TemporalAccessor partialTemporal,
                ResolverStyle resolverStyle) {
            long value = fieldValues.remove(this);
            if (resolverStyle != ResolverStyle.LENIENT) {
                range.checkValidValue(value, this);
            }
            return LocalDate.ofEpochDay(Math.subtractExact(value, offset));
        }

        public String toString() {
            return name;
        }
    }
}
