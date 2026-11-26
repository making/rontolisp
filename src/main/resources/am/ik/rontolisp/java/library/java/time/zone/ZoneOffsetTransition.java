// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A change of a zone's offset: the local date-time it happens at, as the clock showed
// it before, and the offsets before and after. A clock set forward leaves a gap of local
// times that never were, one set back an overlap of times that were twice. The words of
// the refusals and of the text are a JDK's answers; the hash code is a number of ours.
package java.time.zone;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class ZoneOffsetTransition implements Comparable<ZoneOffsetTransition>, Serializable {

    private final long epochSecond;

    private final LocalDateTime transition;

    private final ZoneOffset offsetBefore;

    private final ZoneOffset offsetAfter;

    private ZoneOffsetTransition(LocalDateTime transition, ZoneOffset offsetBefore, ZoneOffset offsetAfter) {
        this.epochSecond = transition.toEpochSecond(offsetBefore);
        this.transition = transition;
        this.offsetBefore = offsetBefore;
        this.offsetAfter = offsetAfter;
    }

    public static ZoneOffsetTransition of(LocalDateTime transition, ZoneOffset offsetBefore, ZoneOffset offsetAfter) {
        if (transition == null) {
            throw new NullPointerException("transition");
        }
        if (offsetBefore == null) {
            throw new NullPointerException("offsetBefore");
        }
        if (offsetAfter == null) {
            throw new NullPointerException("offsetAfter");
        }
        if (offsetBefore.equals(offsetAfter)) {
            throw new IllegalArgumentException("Offsets must not be equal");
        }
        if (transition.getNano() != 0) {
            throw new IllegalArgumentException("Nano-of-second must be zero");
        }
        return new ZoneOffsetTransition(transition, offsetBefore, offsetAfter);
    }

    /** The change at a second of the epoch, between two offsets in seconds. */
    static ZoneOffsetTransition at(long epochSecond, int before, int after) {
        ZoneOffset offsetBefore = ZoneOffset.ofTotalSeconds(before);
        return new ZoneOffsetTransition(LocalDateTime.ofEpochSecond(epochSecond, 0, offsetBefore), offsetBefore,
                ZoneOffset.ofTotalSeconds(after));
    }

    public Instant getInstant() {
        return Instant.ofEpochSecond(epochSecond);
    }

    public long toEpochSecond() {
        return epochSecond;
    }

    public LocalDateTime getDateTimeBefore() {
        return transition;
    }

    public LocalDateTime getDateTimeAfter() {
        return transition.plusSeconds(offsetAfter.getTotalSeconds() - offsetBefore.getTotalSeconds());
    }

    public ZoneOffset getOffsetBefore() {
        return offsetBefore;
    }

    public ZoneOffset getOffsetAfter() {
        return offsetAfter;
    }

    public Duration getDuration() {
        return Duration.ofSeconds(offsetAfter.getTotalSeconds() - offsetBefore.getTotalSeconds());
    }

    public boolean isGap() {
        return offsetAfter.getTotalSeconds() > offsetBefore.getTotalSeconds();
    }

    public boolean isOverlap() {
        return offsetAfter.getTotalSeconds() < offsetBefore.getTotalSeconds();
    }

    public boolean isValidOffset(ZoneOffset offset) {
        return !isGap() && (offsetBefore.equals(offset) || offsetAfter.equals(offset));
    }

    public int compareTo(ZoneOffsetTransition other) {
        return Long.compare(epochSecond, other.epochSecond);
    }

    public boolean equals(Object other) {
        return other instanceof ZoneOffsetTransition that && that.epochSecond == epochSecond
                && that.offsetBefore.equals(offsetBefore) && that.offsetAfter.equals(offsetAfter);
    }

    public int hashCode() {
        return Long.hashCode(epochSecond) ^ offsetBefore.hashCode() ^ Integer.rotateLeft(offsetAfter.hashCode(), 16);
    }

    public String toString() {
        return "Transition[" + (isGap() ? "Gap" : "Overlap") + " at " + transition + offsetBefore + " to "
                + offsetAfter + "]";
    }
}
