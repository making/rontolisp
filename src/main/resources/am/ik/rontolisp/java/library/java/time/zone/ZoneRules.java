// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.zone;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

// The offsets of a zone over time. Every zone of the library keeps one offset for good
// (java.time.ZoneId), so the rules are that offset.
public final class ZoneRules implements Serializable {

    private final ZoneOffset offset;

    private ZoneRules(ZoneOffset offset) {
        this.offset = offset;
    }

    public static ZoneRules of(ZoneOffset offset) {
        if (offset == null) {
            throw new NullPointerException("offset");
        }
        return new ZoneRules(offset);
    }

    public boolean isFixedOffset() {
        return true;
    }

    public ZoneOffset getOffset(Instant instant) {
        return offset;
    }

    public ZoneOffset getOffset(LocalDateTime localDateTime) {
        return offset;
    }

    public List<ZoneOffset> getValidOffsets(LocalDateTime localDateTime) {
        return List.of(offset);
    }

    public boolean isValidOffset(LocalDateTime localDateTime, ZoneOffset offset) {
        return this.offset.equals(offset);
    }

    public ZoneOffset getStandardOffset(Instant instant) {
        return offset;
    }

    public Duration getDaylightSavings(Instant instant) {
        return Duration.ZERO;
    }

    public boolean isDaylightSavings(Instant instant) {
        return false;
    }

    public boolean equals(Object other) {
        return other instanceof ZoneRules that && that.offset.equals(offset);
    }

    public int hashCode() {
        return offset.hashCode();
    }

    public String toString() {
        return "ZoneRules[currentStandardOffset=" + offset + "]";
    }
}
