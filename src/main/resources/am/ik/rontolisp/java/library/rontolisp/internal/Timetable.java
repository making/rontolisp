// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// Tasks run at their times by a fixed number of threads: what a scheduled pool and a
// Timer share. The table is read and written under its monitor, on which a thread with
// no task due waits for the soonest one's time.
package rontolisp.internal;

import java.util.ArrayList;
import java.util.List;

public final class Timetable {

    /** What makes the table's threads: a pool's factory, a Timer's own. */
    public interface Threads {

        Thread make(Runnable body);
    }

    /**
     * What is told once the last thread is gone: a pool's terminated(). An interface of
     * its own, so that what a call of it can reach is that member and not every Runnable
     * of the program.
     */
    public interface End {

        void ended();
    }

    private static final class Entry {

        final Runnable task;

        final long time;

        Entry(Runnable task, long time) {
            this.task = task;
            this.time = time;
        }
    }

    private int size;

    private final Threads threads;

    /** What is run once, when the last thread is gone: a pool's terminated(). */
    private End atEnd;

    /** Whether a thread took it on itself to run that, and whether it has answered: the table's end. */
    private boolean ending;

    private boolean over;

    /** The tasks to run, the soonest first, the one added first among equal times. */
    private final ArrayList<Entry> entries = new ArrayList<>();

    /** The table's threads, as they were made. */
    private final ArrayList<Thread> made = new ArrayList<>();

    /** The threads with no task, the one that had none longest first: it takes the next. */
    private final ArrayList<Thread> line = new ArrayList<>();

    /** The threads that have not ended. */
    private int alive;

    private int busy;

    private long completed;

    /** No thread waits for a task once none is left. */
    private boolean closed;

    /** A task threw, or the table was dropped: nothing runs any more. */
    private boolean dead;

    public Timetable(int size, Threads threads) {
        this.size = size;
        this.threads = threads;
    }

    /** What to run when the last thread is gone, outside the table's monitor. */
    public synchronized void atEnd(End atEnd) {
        this.atEnd = atEnd;
    }

    /** Under the monitor: whether the last thread is gone and the caller is the one to end the table. */
    private boolean ends() {
        if (this.ending || !(this.closed && this.alive == 0 && this.entries.isEmpty())) {
            return false;
        }
        this.ending = true;
        return true;
    }

    /** Ends the table, outside its monitor: it has ended once what is run then has answered. */
    private void end() {
        try {
            if (this.atEnd != null) {
                this.atEnd.ended();
            }
        }
        finally {
            synchronized (this) {
                this.over = true;
                notifyAll();
            }
        }
    }

    /** How many threads the table has from now on; the ones above the number stay. */
    public synchronized void resize(int size) {
        this.size = size;
    }

    /** Starts one more thread ahead of any task, while fewer than the table's number were made. */
    public boolean start() {
        Thread started;
        synchronized (this) {
            if (this.closed || this.made.size() >= this.size) {
                return false;
            }
            started = another();
        }
        started.start();
        return true;
    }

    /** Starts the first thread ahead of any task: a Timer's own. */
    public void open() {
        Thread started = null;
        synchronized (this) {
            if (this.made.isEmpty()) {
                started = another();
            }
        }
        if (started != null) {
            started.start();
        }
    }

    private Thread another() {
        Thread thread = this.threads.make(() -> work());
        this.made.add(thread);
        this.line.add(thread);
        this.alive++;
        return thread;
    }

    /** A task to run at a time, as System.nanoTime() counts; false when nothing runs any more. */
    public boolean add(Runnable task, long time) {
        Thread started = null;
        synchronized (this) {
            if (this.dead) {
                return false;
            }
            int at = this.entries.size();
            while (at > 0 && this.entries.get(at - 1).time - time > 0) {
                at--;
            }
            this.entries.add(at, new Entry(task, time));
            if (this.made.size() < this.size) {
                started = another();
            }
            else {
                notifyAll();
            }
        }
        if (started != null) {
            started.start();
        }
        return true;
    }

    /** What a thread of the table runs: the tasks due, one after another. */
    private void work() {
        boolean thrown = true;
        try {
            Runnable task;
            while ((task = next()) != null) {
                task.run();
                synchronized (this) {
                    this.busy--;
                    this.completed++;
                }
            }
            thrown = false;
        }
        finally {
            boolean ended;
            synchronized (this) {
                this.alive--;
                if (thrown) {
                    this.busy--;
                    this.dead = true;
                    this.closed = true;
                    this.entries.clear();
                }
                ended = ends();
                notifyAll();
            }
            if (ended) {
                end();
            }
        }
    }

    /**
     * The task a thread runs next, taken out of the table; null when the thread is to
     * end. The thread that had no task longest waits for the soonest one's time, the
     * others for their turn. ONE reading of the clock says both whether that task is due
     * and how long to wait for it: by a second reading the time may have come, and a
     * wait for no time is a wait with no limit, which nothing would end.
     */
    private synchronized Runnable next() {
        Thread current = Thread.currentThread();
        if (!this.line.contains(current)) {
            this.line.add(current);
        }
        try {
            while (true) {
                boolean timed = this.line.get(0) == current && !this.entries.isEmpty();
                long pause = timed ? this.entries.get(0).time - System.nanoTime() : 0;
                if (timed && pause <= 0) {
                    this.busy++;
                    return this.entries.remove(0).task;
                }
                if (this.entries.isEmpty() && this.closed) {
                    return null;
                }
                try {
                    if (timed) {
                        wait((pause + 999999L) / 1000000L);
                    }
                    else {
                        wait();
                    }
                }
                catch (InterruptedException e) {
                    // drop(true): the thread ends once it finds no task
                }
            }
        }
        finally {
            this.line.remove(current);
            notifyAll();
        }
    }

    public synchronized boolean remove(Runnable task) {
        for (int i = 0; i < this.entries.size(); i++) {
            if (this.entries.get(i).task == task) {
                this.entries.remove(i);
                notifyAll();
                return true;
            }
        }
        return false;
    }

    /** The tasks waiting for their times, the soonest first. */
    public synchronized List<Runnable> tasks() {
        List<Runnable> waiting = new ArrayList<>();
        for (Entry entry : this.entries) {
            waiting.add(entry.task);
        }
        return waiting;
    }

    /** The threads end once no task is left. */
    public void close() {
        boolean ended;
        synchronized (this) {
            this.closed = true;
            ended = ends();
            notifyAll();
        }
        if (ended) {
            end();
        }
    }

    /** Nothing runs any more: the tasks that waited. A pool interrupts the one running. */
    public List<Runnable> drop(boolean interrupting) {
        List<Runnable> waiting;
        boolean ended;
        synchronized (this) {
            waiting = tasks();
            this.entries.clear();
            this.closed = true;
            this.dead = true;
            if (interrupting) {
                for (Thread thread : this.made) {
                    thread.interrupt();
                }
            }
            ended = ends();
            notifyAll();
        }
        if (ended) {
            end();
        }
        return waiting;
    }

    public synchronized boolean isDead() {
        return this.dead;
    }

    public synchronized boolean isEnded() {
        return this.over;
    }

    /** Waits for the table's end until a time, as System.nanoTime() counts: whether it ended. */
    public synchronized boolean awaitEnd(long deadline) throws InterruptedException {
        while (!this.over) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            wait((left + 999999L) / 1000000L);
        }
        return true;
    }

    public synchronized int threads() {
        return this.alive;
    }

    public synchronized int busy() {
        return this.busy;
    }

    public synchronized int waiting() {
        return this.entries.size();
    }

    public synchronized long completed() {
        return this.completed;
    }
}
