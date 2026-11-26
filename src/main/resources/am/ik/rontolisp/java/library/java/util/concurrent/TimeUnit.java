// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

// A unit of time: how many nanoseconds one is. A duration too long for the unit asked
// is the longest that unit holds, of its sign.
public enum TimeUnit {

    NANOSECONDS(1L), MICROSECONDS(1000L), MILLISECONDS(1000000L), SECONDS(1000000000L), MINUTES(60000000000L),
    HOURS(3600000000000L), DAYS(86400000000000L);

    private final long nanos;

    TimeUnit(long nanos) {
        this.nanos = nanos;
    }

    private static long scale(long duration, long from, long to) {
        if (from == to) {
            return duration;
        }
        if (from < to) {
            return duration / (to / from);
        }
        long ratio = from / to;
        long most = Long.MAX_VALUE / ratio;
        if (duration > most) {
            return Long.MAX_VALUE;
        }
        if (duration < -most) {
            return Long.MIN_VALUE;
        }
        return duration * ratio;
    }

    public long convert(long duration, TimeUnit unit) {
        return scale(duration, unit.nanos, this.nanos);
    }

    public long toNanos(long duration) {
        return scale(duration, this.nanos, 1L);
    }

    public long toMicros(long duration) {
        return scale(duration, this.nanos, 1000L);
    }

    public long toMillis(long duration) {
        return scale(duration, this.nanos, 1000000L);
    }

    public long toSeconds(long duration) {
        return scale(duration, this.nanos, 1000000000L);
    }

    public long toMinutes(long duration) {
        return scale(duration, this.nanos, 60000000000L);
    }

    public long toHours(long duration) {
        return scale(duration, this.nanos, 3600000000000L);
    }

    public long toDays(long duration) {
        return scale(duration, this.nanos, 86400000000000L);
    }

    public void sleep(long timeout) throws InterruptedException {
        if (timeout > 0) {
            long millis = toMillis(timeout);
            Thread.sleep(millis, (int) (toNanos(timeout) - millis * 1000000L));
        }
    }

    public void timedJoin(Thread thread, long timeout) throws InterruptedException {
        if (timeout > 0) {
            long millis = toMillis(timeout);
            thread.join(millis == 0 ? 1 : millis);
        }
    }

    public void timedWait(Object object, long timeout) throws InterruptedException {
        if (timeout > 0) {
            long millis = toMillis(timeout);
            object.wait(millis == 0 ? 1 : millis);
        }
    }
}
