// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What tells the present instant and the zone a date is read from it in: the host's
// clock, one that stands still, one a fixed time ahead of another, one that moves in
// steps, one over a source of instants. A program may write its own.
package java.time;

import java.io.Serializable;

public abstract class Clock implements InstantSource {

    protected Clock() {
    }

    public static Clock systemUTC() {
        return new SystemClock(ZoneOffset.UTC);
    }

    public static Clock systemDefaultZone() {
        return new SystemClock(ZoneId.systemDefault());
    }

    public static Clock system(ZoneId zone) {
        if (zone == null) {
            throw new NullPointerException("zone");
        }
        return new SystemClock(zone);
    }

    public static Clock tickMillis(ZoneId zone) {
        return new TickClock(system(zone), 1000000L);
    }

    public static Clock tickSeconds(ZoneId zone) {
        return new TickClock(system(zone), Iso.SECOND);
    }

    public static Clock tickMinutes(ZoneId zone) {
        return new TickClock(system(zone), 60 * Iso.SECOND);
    }

    public static Clock tick(Clock baseClock, Duration tickDuration) {
        if (tickDuration.isNegative()) {
            throw new IllegalArgumentException("Tick duration must not be negative");
        }
        long step = tickDuration.toNanos();
        if (step % 1000000 != 0 && Iso.SECOND % step != 0) {
            throw new IllegalArgumentException("Invalid tick duration");
        }
        return step <= 1 ? baseClock : new TickClock(baseClock, step);
    }

    public static Clock fixed(Instant fixedInstant, ZoneId zone) {
        if (fixedInstant == null) {
            throw new NullPointerException("fixedInstant");
        }
        if (zone == null) {
            throw new NullPointerException("zone");
        }
        return new FixedClock(fixedInstant, zone);
    }

    public static Clock offset(Clock baseClock, Duration offsetDuration) {
        return offsetDuration.equals(Duration.ZERO) ? baseClock : new OffsetClock(baseClock, offsetDuration);
    }

    public abstract ZoneId getZone();

    public abstract Clock withZone(ZoneId zone);

    public abstract Instant instant();

    public long millis() {
        return instant().toEpochMilli();
    }

    private static final class SystemClock extends Clock implements Serializable {

        private final ZoneId zone;

        SystemClock(ZoneId zone) {
            this.zone = zone;
        }

        public ZoneId getZone() {
            return zone;
        }

        public Clock withZone(ZoneId zone) {
            return zone.equals(this.zone) ? this : new SystemClock(zone);
        }

        public Instant instant() {
            return Instant.now();
        }

        public boolean equals(Object other) {
            return other instanceof SystemClock that && that.zone.equals(zone);
        }

        public int hashCode() {
            return zone.hashCode() + 1;
        }

        public String toString() {
            return "SystemClock[" + zone + "]";
        }
    }

    private static final class FixedClock extends Clock implements Serializable {

        private final Instant instant;

        private final ZoneId zone;

        FixedClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        public ZoneId getZone() {
            return zone;
        }

        public Clock withZone(ZoneId zone) {
            return zone.equals(this.zone) ? this : new FixedClock(instant, zone);
        }

        public Instant instant() {
            return instant;
        }

        public boolean equals(Object other) {
            return other instanceof FixedClock that && that.instant.equals(instant) && that.zone.equals(zone);
        }

        public int hashCode() {
            return instant.hashCode() ^ zone.hashCode();
        }

        public String toString() {
            return "FixedClock[" + instant + "," + zone + "]";
        }
    }

    private static final class OffsetClock extends Clock implements Serializable {

        private final Clock base;

        private final Duration offset;

        OffsetClock(Clock base, Duration offset) {
            this.base = base;
            this.offset = offset;
        }

        public ZoneId getZone() {
            return base.getZone();
        }

        public Clock withZone(ZoneId zone) {
            return zone.equals(base.getZone()) ? this : new OffsetClock(base.withZone(zone), offset);
        }

        public Instant instant() {
            return base.instant().plus(offset);
        }

        public boolean equals(Object other) {
            return other instanceof OffsetClock that && that.base.equals(base) && that.offset.equals(offset);
        }

        public int hashCode() {
            return base.hashCode() ^ offset.hashCode();
        }

        public String toString() {
            return "OffsetClock[" + base + "," + offset + "]";
        }
    }

    /** The host's instant, of no zone: what InstantSource.system() answers. */
    static final class SystemInstantSource implements InstantSource {

        static final SystemInstantSource INSTANCE = new SystemInstantSource();

        private SystemInstantSource() {
        }

        public Instant instant() {
            return Instant.now();
        }

        public Clock withZone(ZoneId zone) {
            return Clock.system(zone);
        }

        public String toString() {
            return "SystemInstantSource";
        }
    }

    /** A source of instants given a zone. */
    static final class SourceClock extends Clock {

        private final InstantSource source;

        private final ZoneId zone;

        SourceClock(InstantSource source, ZoneId zone) {
            this.source = source;
            this.zone = zone;
        }

        public ZoneId getZone() {
            return zone;
        }

        public Clock withZone(ZoneId zone) {
            return zone.equals(this.zone) ? this : new SourceClock(source, zone);
        }

        public Instant instant() {
            return source.instant();
        }

        public long millis() {
            return source.millis();
        }

        public boolean equals(Object other) {
            return other instanceof SourceClock that && that.source.equals(source) && that.zone.equals(zone);
        }

        public int hashCode() {
            return source.hashCode() ^ zone.hashCode();
        }

        public String toString() {
            return "SourceClock[" + source + "," + zone + "]";
        }
    }

    private static final class TickClock extends Clock implements Serializable {

        private final Clock base;

        private final long step;

        TickClock(Clock base, long step) {
            this.base = base;
            this.step = step;
        }

        public ZoneId getZone() {
            return base.getZone();
        }

        public Clock withZone(ZoneId zone) {
            return zone.equals(base.getZone()) ? this : new TickClock(base.withZone(zone), step);
        }

        /** The base's instant at the last step: whole milliseconds, or parts of a second. */
        public Instant instant() {
            Instant now = base.instant();
            if (step % 1000000 == 0) {
                long millis = now.toEpochMilli();
                return Instant.ofEpochMilli(millis - Math.floorMod(millis, step / 1000000));
            }
            return now.minusNanos(Math.floorMod((long) now.getNano(), step));
        }

        public boolean equals(Object other) {
            return other instanceof TickClock that && that.base.equals(base) && that.step == step;
        }

        public int hashCode() {
            return base.hashCode() ^ Long.hashCode(step);
        }

        public String toString() {
            return "TickClock[" + base + "," + Duration.ofNanos(step) + "]";
        }
    }
}
