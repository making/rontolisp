// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A fixed distance from UTC, to the second and within 18 hours. The texts an offset is
// read from, the order its refusals come in and their words are a JDK's answers.
package java.time;

import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalQueries;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.time.zone.ZoneRules;

public final class ZoneOffset extends ZoneId implements TemporalAccessor, TemporalAdjuster, Comparable<ZoneOffset> {

    public static final ZoneOffset UTC = new ZoneOffset(0);

    public static final ZoneOffset MIN = new ZoneOffset(-64800);

    public static final ZoneOffset MAX = new ZoneOffset(64800);

    private final int totalSeconds;

    private String id;

    private ZoneOffset(int totalSeconds) {
        this.totalSeconds = totalSeconds;
    }

    public static ZoneOffset ofTotalSeconds(int totalSeconds) {
        if (totalSeconds < -64800 || totalSeconds > 64800) {
            throw new DateTimeException("Zone offset not in valid range: -18:00 to +18:00");
        }
        return totalSeconds == 0 ? UTC : new ZoneOffset(totalSeconds);
    }

    public static ZoneOffset ofHours(int hours) {
        return ofHoursMinutesSeconds(hours, 0, 0);
    }

    public static ZoneOffset ofHoursMinutes(int hours, int minutes) {
        return ofHoursMinutesSeconds(hours, minutes, 0);
    }

    private static void part(String what, int value, int most) {
        if (value < -most || value > most) {
            throw new DateTimeException("Zone offset " + what + " not in valid range: value " + value
                    + " is not in the range -" + most + " to " + most);
        }
    }

    public static ZoneOffset ofHoursMinutesSeconds(int hours, int minutes, int seconds) {
        part("hours", hours, 18);
        if (hours > 0) {
            if (minutes < 0 || seconds < 0) {
                throw new DateTimeException("Zone offset minutes and seconds must be positive because hours is positive");
            }
        }
        else if (hours < 0) {
            if (minutes > 0 || seconds > 0) {
                throw new DateTimeException("Zone offset minutes and seconds must be negative because hours is negative");
            }
        }
        else if ((minutes > 0 && seconds < 0) || (minutes < 0 && seconds > 0)) {
            throw new DateTimeException("Zone offset minutes and seconds must have the same sign");
        }
        part("minutes", minutes, 59);
        part("seconds", seconds, 59);
        if (Math.abs(hours) == 18 && (minutes | seconds) != 0) {
            throw new DateTimeException("Zone offset not in valid range: -18:00 to +18:00");
        }
        return ofTotalSeconds(hours * 3600 + minutes * 60 + seconds);
    }

    /** Two digits of an id, behind a colon where one is due. */
    private static int number(String shown, String id, int at, boolean colon) {
        if (colon && id.charAt(at - 1) != ':') {
            throw new DateTimeException("Invalid ID for ZoneOffset, colon not found when expected: " + shown);
        }
        char tens = id.charAt(at);
        char ones = id.charAt(at + 1);
        if (tens < '0' || tens > '9' || ones < '0' || ones > '9') {
            throw new DateTimeException("Invalid ID for ZoneOffset, non numeric characters found: " + shown);
        }
        return (tens - '0') * 10 + (ones - '0');
    }

    public static ZoneOffset of(String offsetId) {
        if (offsetId.equals("Z")) {
            return UTC;
        }
        // one digit of hours is read as two
        String id = offsetId.length() == 2 ? offsetId.charAt(0) + "0" + offsetId.charAt(1) : offsetId;
        int hours;
        int minutes = 0;
        int seconds = 0;
        switch (id.length()) {
            case 3:
                hours = number(id, id, 1, false);
                break;
            case 5:
                hours = number(id, id, 1, false);
                minutes = number(id, id, 3, false);
                break;
            case 6:
                hours = number(id, id, 1, false);
                minutes = number(id, id, 4, true);
                break;
            case 7:
                hours = number(id, id, 1, false);
                minutes = number(id, id, 3, false);
                seconds = number(id, id, 5, false);
                break;
            case 9:
                hours = number(id, id, 1, false);
                minutes = number(id, id, 4, true);
                seconds = number(id, id, 7, true);
                break;
            default:
                throw new DateTimeException("Invalid ID for ZoneOffset, invalid format: " + id);
        }
        char sign = id.charAt(0);
        if (sign != '+' && sign != '-') {
            throw new DateTimeException("Invalid ID for ZoneOffset, plus/minus not found when expected: " + id);
        }
        return sign == '-' ? ofHoursMinutesSeconds(-hours, -minutes, -seconds)
                : ofHoursMinutesSeconds(hours, minutes, seconds);
    }

    public static ZoneOffset from(TemporalAccessor temporal) {
        if (temporal instanceof ZoneOffset offset) {
            return offset;
        }
        if (temporal instanceof OffsetDateTime dateTime) {
            return dateTime.getOffset();
        }
        if (temporal instanceof ZonedDateTime dateTime) {
            return dateTime.getOffset();
        }
        if (temporal instanceof OffsetTime time) {
            return time.getOffset();
        }
        if (Iso.foreign()) {
            ZoneOffset offset = temporal.query(TemporalQueries.offset());
            if (offset != null) {
                return offset;
            }
        }
        throw new DateTimeException("Unable to obtain ZoneOffset from TemporalAccessor: " + temporal + " of type "
                + temporal.getClass().getName());
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public String getId() {
        if (id == null) {
            if (totalSeconds == 0) {
                id = "Z";
            }
            else {
                int size = Math.abs(totalSeconds);
                StringBuilder text = new StringBuilder().append(totalSeconds < 0 ? '-' : '+');
                Iso.two(text, size / 3600).append(':');
                Iso.two(text, size / 60 % 60);
                if (size % 60 != 0) {
                    text.append(':');
                    Iso.two(text, size % 60);
                }
                id = text.toString();
            }
        }
        return id;
    }

    public ZoneRules getRules() {
        return ZoneRules.of(this);
    }

    public ZoneId normalized() {
        return this;
    }

    ZoneOffset fixed() {
        return this;
    }

    public boolean isSupported(TemporalField field) {
        return field instanceof ChronoField ? field == ChronoField.OFFSET_SECONDS
                : field != null && field.isSupportedBy(this);
    }

    public long getLong(TemporalField field) {
        if (field == ChronoField.OFFSET_SECONDS) {
            return totalSeconds;
        }
        if (field instanceof ChronoField) {
            throw new UnsupportedTemporalTypeException("Unsupported field: " + field);
        }
        return field.getFrom(this);
    }

    public Temporal adjustInto(Temporal temporal) {
        return temporal.with(ChronoField.OFFSET_SECONDS, totalSeconds);
    }

    /** The offsets from the largest to the smallest. */
    public int compareTo(ZoneOffset other) {
        return other.totalSeconds - totalSeconds;
    }

    public boolean equals(Object other) {
        return other instanceof ZoneOffset that && that.totalSeconds == totalSeconds;
    }

    public int hashCode() {
        return totalSeconds;
    }

    public String toString() {
        return getId();
    }
}
