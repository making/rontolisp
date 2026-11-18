// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// What a Timer runs. The task's state is read and written under the task's monitor.
public abstract class TimerTask implements Runnable {

    static final int NEW = 0;

    static final int SCHEDULED = 1;

    static final int EXECUTED = 2;

    static final int CANCELLED = 3;

    int state;

    /** When the task runs next, as System.nanoTime() counts and as the clock tells. */
    long time;

    long clock;

    /** Above 0 the time from one run's time to the next's, below 0 from one's start; 0 for one run. */
    long period;

    /** The time on the clock the run last started was meant for. */
    private long ran;

    protected TimerTask() {
    }

    public abstract void run();

    /** Whether a run that was to come will not. */
    public synchronized boolean cancel() {
        boolean pending = this.state == SCHEDULED;
        this.state = CANCELLED;
        return pending;
    }

    public synchronized long scheduledExecutionTime() {
        return this.ran;
    }

    /** A run starts: whether the task is to run, and its next time when it repeats. */
    synchronized boolean starts() {
        if (this.state == CANCELLED) {
            return false;
        }
        this.ran = this.clock;
        if (this.period == 0) {
            this.state = EXECUTED;
        }
        else if (this.period > 0) {
            this.time += this.period;
            this.clock += this.period / 1000000L;
        }
        else {
            this.time = System.nanoTime() - this.period;
            this.clock = System.currentTimeMillis() - this.period / 1000000L;
        }
        return true;
    }
}
