// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// One thread running tasks at their times, out of the table a scheduled pool also uses
// (rontolisp.internal.Timetable, which says what one stack does). A task that throws
// ends the thread, and the timer with it.
package java.util;

import rontolisp.internal.Timetable;

public class Timer {

    /** How many timers were made with no name: the number the next one's thread carries. */
    private static int unnamed;

    private final Timetable table;

    private boolean cancelled;

    public Timer() {
        this("Timer-" + number(), false);
    }

    public Timer(boolean isDaemon) {
        this("Timer-" + number(), isDaemon);
    }

    public Timer(String name) {
        this(name, false);
    }

    public Timer(String name, boolean isDaemon) {
        if (name == null) {
            throw new NullPointerException();
        }
        this.table = new Timetable(1, (Runnable body) -> {
            Thread thread = new Thread(body, name);
            thread.setDaemon(isDaemon);
            return thread;
        });
        this.table.open();
    }

    private static synchronized int number() {
        return unnamed++;
    }

    /** One task's place in the table: the same for every run of a task that repeats. */
    private static final class Firing implements Runnable {

        private final Timetable table;

        final TimerTask task;

        Firing(Timetable table, TimerTask task) {
            this.table = table;
            this.task = task;
        }

        public void run() {
            if (this.task.starts()) {
                if (this.task.period != 0) {
                    this.table.add(this, this.task.time);
                }
                this.task.run();
            }
        }
    }

    public void schedule(TimerTask task, long delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("Negative delay.");
        }
        add(task, delay, 0);
    }

    public void schedule(TimerTask task, long delay, long period) {
        if (delay < 0) {
            throw new IllegalArgumentException("Negative delay.");
        }
        if (period <= 0) {
            throw new IllegalArgumentException("Non-positive period.");
        }
        add(task, delay, -period);
    }

    public void scheduleAtFixedRate(TimerTask task, long delay, long period) {
        if (delay < 0) {
            throw new IllegalArgumentException("Negative delay.");
        }
        if (period <= 0) {
            throw new IllegalArgumentException("Non-positive period.");
        }
        add(task, delay, period);
    }

    private void add(TimerTask task, long delay, long period) {
        synchronized (this) {
            if (this.cancelled || this.table.isDead()) {
                throw new IllegalStateException("Timer already cancelled.");
            }
        }
        synchronized (task) {
            if (task.state != TimerTask.NEW) {
                throw new IllegalStateException("Task already scheduled or cancelled");
            }
            task.state = TimerTask.SCHEDULED;
            task.time = System.nanoTime() + delay * 1000000L;
            task.clock = System.currentTimeMillis() + delay;
            task.period = period * 1000000L;
        }
        if (!this.table.add(new Firing(this.table, task), task.time)) {
            throw new IllegalStateException("Timer already cancelled.");
        }
    }

    /** No task runs any more; the one running goes on to its end. */
    public void cancel() {
        synchronized (this) {
            this.cancelled = true;
        }
        this.table.drop(false);
    }

    /** Takes the cancelled tasks out of the table: how many. */
    public int purge() {
        int removed = 0;
        for (Runnable waiting : this.table.tasks()) {
            TimerTask task = ((Firing) waiting).task;
            boolean cancelled;
            synchronized (task) {
                cancelled = task.state == TimerTask.CANCELLED;
            }
            if (cancelled && this.table.remove(waiting)) {
                removed++;
            }
        }
        return removed;
    }
}
