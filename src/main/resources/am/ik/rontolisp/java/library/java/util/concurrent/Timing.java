// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

// The waits with a limit, which this package's classes share.
final class Timing {

    private Timing() {
    }

    /** When a wait of a duration from now is over, as System.nanoTime() counts. */
    static long deadline(long timeout, TimeUnit unit) {
        return System.nanoTime() + unit.toNanos(timeout);
    }

    /**
     * Waits on an object whose monitor the thread holds until it is notified or the
     * time is up: false when it was up already.
     */
    static boolean await(Object lock, long deadline) throws InterruptedException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            return false;
        }
        lock.wait((left + 999999L) / 1000000L);
        return true;
    }
}
