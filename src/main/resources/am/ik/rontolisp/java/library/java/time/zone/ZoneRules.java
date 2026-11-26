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

// The offsets of a zone over time. This class is the rules of a zone that keeps one
// offset for good; a zone whose offset changes has ZoneChanges, the class under it, which
// a program holds only when it holds such a zone (java.time.ZoneId).
public class ZoneRules implements Serializable {

    private final ZoneOffset offset;

    ZoneRules(ZoneOffset offset) {
        this.offset = offset;
    }

    public static ZoneRules of(ZoneOffset offset) {
        if (offset == null) {
            throw new NullPointerException("offset");
        }
        return new ZoneRules(offset);
    }

    private static void given(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }

    public static ZoneRules of(ZoneOffset baseStandardOffset, ZoneOffset baseWallOffset,
            List<ZoneOffsetTransition> standardOffsetTransitionList, List<ZoneOffsetTransition> transitionList,
            List<ZoneOffsetTransitionRule> lastRules) {
        given(baseStandardOffset, "baseStandardOffset");
        given(baseWallOffset, "baseWallOffset");
        given(standardOffsetTransitionList, "standardOffsetTransitionList");
        given(transitionList, "transitionList");
        given(lastRules, "lastRules");
        if (lastRules.size() > 16) {
            throw new IllegalArgumentException("Too many transition rules");
        }
        if (baseStandardOffset.equals(baseWallOffset) && standardOffsetTransitionList.isEmpty()
                && transitionList.isEmpty() && lastRules.isEmpty()) {
            return new ZoneRules(baseWallOffset);
        }
        return ZoneChanges.stated(baseStandardOffset, baseWallOffset, standardOffsetTransitionList, transitionList,
                lastRules);
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

    public ZoneOffsetTransition getTransition(LocalDateTime localDateTime) {
        return null;
    }

    public ZoneOffsetTransition nextTransition(Instant instant) {
        return null;
    }

    public ZoneOffsetTransition previousTransition(Instant instant) {
        return null;
    }

    public List<ZoneOffsetTransition> getTransitions() {
        return List.of();
    }

    public List<ZoneOffsetTransitionRule> getTransitionRules() {
        return List.of();
    }

    public boolean equals(Object other) {
        // the rules of a zone whose offset changes hold no offset here
        return other instanceof ZoneRules that && offset.equals(that.offset);
    }

    public int hashCode() {
        return offset.hashCode();
    }

    public String toString() {
        return "ZoneRules[currentStandardOffset=" + offset.getId() + "]";
    }
}
