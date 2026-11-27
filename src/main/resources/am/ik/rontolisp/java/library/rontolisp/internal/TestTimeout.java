// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.platform.commons.PreconditionViolationException;

// The time a @Timeout gives a test or what runs around tests, kept as JUnit Jupiter kept
// it under a JDK: the method runs on the thread of the run, which another thread
// interrupts once when the time is over, so a wait of the method ends with an
// InterruptedException and a method that never waits runs to its end. Either way the
// method then failed with a TimeoutException naming it and the time, what it threw
// suppressed in that -- an assumption that did not hold too --, and the thread is no
// longer interrupted for what runs next. Where threads take turns, the interrupting one
// gets none while the method never waits: the time is then read once the method ended.
public final class TestTimeout {

    private TestTimeout() {
    }

    // What is called with a time for it.
    @FunctionalInterface
    public interface Call<T> {

        T call() throws Throwable;
    }

    // What interrupts the thread of the run when the time is over, unless the call ended.
    private static final class Watch implements Runnable {

        final Thread running;

        final long nanos;

        boolean ended;

        boolean over;

        Watch(Thread running, long nanos) {
            this.running = running;
            this.nanos = nanos;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(nanos / 1000000, (int) (nanos % 1000000));
            }
            catch (InterruptedException called) {
                return;
            }
            synchronized (this) {
                if (!ended) {
                    over = true;
                    running.interrupt();
                }
            }
        }

        synchronized boolean end() {
            ended = true;
            return over;
        }
    }

    // Refuses a time that is not positive, in JUnit's words.
    public static void positive(long value) {
        if (value <= 0) {
            throw new PreconditionViolationException("timeout duration must be a positive number: " + value);
        }
    }

    public static void timed(long value, TimeUnit unit, String method, TestRun.Body body) throws Throwable {
        called(value, unit, method, () -> {
            body.run();
            return null;
        });
    }

    // method: the method as JUnit names it, its name and its parameters' types.
    public static <T> T called(long value, TimeUnit unit, String method, Call<T> call) throws Throwable {
        positive(value);
        long nanos = unit.toNanos(value);
        Watch watch = new Watch(Thread.currentThread(), nanos);
        Thread watching = new Thread(watch);
        watching.setDaemon(true);
        long started = System.nanoTime();
        watching.start();
        T returned = null;
        Throwable thrown = null;
        try {
            returned = call.call();
        }
        catch (Throwable caught) {
            thrown = caught;
        }
        boolean over = watch.end() || System.nanoTime() - started >= nanos;
        if (over) {
            Thread.interrupted();
        }
        watching.interrupt();
        watching.join();
        if (over) {
            String units = unit.name().toLowerCase();
            TimeoutException timeout = new TimeoutException(method + " timed out after " + value + " "
                    + (value == 1 ? units.substring(0, units.length() - 1) : units));
            if (thrown != null) {
                timeout.addSuppressed(thrown);
            }
            throw timeout;
        }
        if (thrown != null) {
            throw thrown;
        }
        return returned;
    }
}
