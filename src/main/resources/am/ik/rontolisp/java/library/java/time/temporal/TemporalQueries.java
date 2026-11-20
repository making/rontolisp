// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

// The questions the library asks of a date or a time. A class answers the ones it can
// tell by itself (its query); any other is answered from its fields. Each question is
// one object, compared by identity.
public final class TemporalQueries {

    private static final TemporalQuery<ZoneId> ZONE_ID = new Question<>(0, "ZoneId");

    private static final TemporalQuery<TemporalUnit> PRECISION = new Question<>(1, "Precision");

    private static final TemporalQuery<ZoneId> ZONE = new Question<>(2, "Zone");

    private static final TemporalQuery<ZoneOffset> OFFSET = new Question<>(3, "ZoneOffset");

    private static final TemporalQuery<LocalDate> LOCAL_DATE = new Question<>(4, "LocalDate");

    private static final TemporalQuery<LocalTime> LOCAL_TIME = new Question<>(5, "LocalTime");

    private TemporalQueries() {
    }

    public static TemporalQuery<ZoneId> zoneId() {
        return ZONE_ID;
    }

    public static TemporalQuery<TemporalUnit> precision() {
        return PRECISION;
    }

    public static TemporalQuery<ZoneId> zone() {
        return ZONE;
    }

    public static TemporalQuery<ZoneOffset> offset() {
        return OFFSET;
    }

    public static TemporalQuery<LocalDate> localDate() {
        return LOCAL_DATE;
    }

    public static TemporalQuery<LocalTime> localTime() {
        return LOCAL_TIME;
    }

    private static final class Question<R> implements TemporalQuery<R> {

        private final int kind;

        private final String name;

        Question(int kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        @SuppressWarnings("unchecked")
        public R queryFrom(TemporalAccessor temporal) {
            switch (kind) {
                case 0:
                case 1:
                    // only a class that knows its zone or its precision answers them
                    return temporal.query(this);
                case 2: {
                    ZoneId zone = temporal.query(ZONE_ID);
                    return (R) (zone != null ? zone : temporal.query(OFFSET));
                }
                case 3:
                    return temporal.isSupported(ChronoField.OFFSET_SECONDS)
                            ? (R) ZoneOffset.ofTotalSeconds(temporal.get(ChronoField.OFFSET_SECONDS)) : null;
                case 4:
                    return temporal.isSupported(ChronoField.EPOCH_DAY)
                            ? (R) LocalDate.ofEpochDay(temporal.getLong(ChronoField.EPOCH_DAY)) : null;
                default:
                    return temporal.isSupported(ChronoField.NANO_OF_DAY)
                            ? (R) LocalTime.ofNanoOfDay(temporal.getLong(ChronoField.NANO_OF_DAY)) : null;
            }
        }

        public String toString() {
            return name;
        }
    }
}
