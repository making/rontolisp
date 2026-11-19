// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.time.temporal;

import java.time.Duration;

// The units of the ISO calendar, each with the time it lasts; a month and the units
// above it last what they do on average over 400 years.
public enum ChronoUnit implements TemporalUnit {

    NANOS("Nanos", 0L, 1), MICROS("Micros", 0L, 1000), MILLIS("Millis", 0L, 1000000), SECONDS("Seconds", 1L, 0),
    MINUTES("Minutes", 60L, 0), HOURS("Hours", 3600L, 0), HALF_DAYS("HalfDays", 43200L, 0), DAYS("Days", 86400L, 0),
    WEEKS("Weeks", 604800L, 0), MONTHS("Months", 2629746L, 0), YEARS("Years", 31556952L, 0),
    DECADES("Decades", 315569520L, 0), CENTURIES("Centuries", 3155695200L, 0),
    MILLENNIA("Millennia", 31556952000L, 0), ERAS("Eras", 31556952000000000L, 0),
    FOREVER("Forever", Long.MAX_VALUE, 999999999);

    private final String name;

    private final long seconds;

    private final int nanos;

    ChronoUnit(String name, long seconds, int nanos) {
        this.name = name;
        this.seconds = seconds;
        this.nanos = nanos;
    }

    public Duration getDuration() {
        return Duration.ofSeconds(seconds, nanos);
    }

    public boolean isDurationEstimated() {
        return compareTo(DAYS) >= 0;
    }

    public boolean isDateBased() {
        return compareTo(DAYS) >= 0 && this != FOREVER;
    }

    public boolean isTimeBased() {
        return compareTo(DAYS) < 0;
    }

    @SuppressWarnings("unchecked")
    public <R extends Temporal> R addTo(R temporal, long amount) {
        return (R) temporal.plus(amount, this);
    }

    public long between(Temporal temporal1Inclusive, Temporal temporal2Exclusive) {
        return temporal1Inclusive.until(temporal2Exclusive, this);
    }

    public String toString() {
        return name;
    }
}
