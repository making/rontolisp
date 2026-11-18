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

    private static final class Entry {

        final Runnable task;

        final long time;

        Entry(Runnable task, long time) {
            this.task = task;
            this.time = time;
        }
    }

    private final int size;

    private final Threads threads;

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
            synchronized (this) {
                this.alive--;
                if (thrown) {
                    this.busy--;
                    this.dead = true;
                    this.closed = true;
                    this.entries.clear();
                }
                notifyAll();
            }
        }
    }

    /** The task due, taken out of the table; null when none is. */
    private Runnable due() {
        if (this.entries.isEmpty() || this.entries.get(0).time - System.nanoTime() > 0) {
            return null;
        }
        this.busy++;
        return this.entries.remove(0).task;
    }

    /**
     * The task a thread runs next; null when the thread is to end. The thread that had
     * no task longest waits for the soonest one's time, the others for their turn.
     */
    private synchronized Runnable next() {
        Thread current = Thread.currentThread();
        if (!this.line.contains(current)) {
            this.line.add(current);
        }
        try {
            while (true) {
                boolean first = this.line.get(0) == current;
                Runnable task = first ? due() : null;
                if (task != null) {
                    return task;
                }
                if (this.entries.isEmpty() && this.closed) {
                    return null;
                }
                try {
                    if (first && !this.entries.isEmpty()) {
                        long pause = this.entries.get(0).time - System.nanoTime();
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
    public synchronized void close() {
        this.closed = true;
        notifyAll();
    }

    /** Nothing runs any more: the tasks that waited. A pool interrupts the one running. */
    public synchronized List<Runnable> drop(boolean interrupting) {
        List<Runnable> waiting = tasks();
        this.entries.clear();
        this.closed = true;
        this.dead = true;
        if (interrupting) {
            for (Thread thread : this.made) {
                thread.interrupt();
            }
        }
        notifyAll();
        return waiting;
    }

    public synchronized boolean isDead() {
        return this.dead;
    }

    public synchronized boolean isEnded() {
        return this.closed && this.alive == 0 && this.entries.isEmpty();
    }

    /** Waits for the table's end until a time, as System.nanoTime() counts: whether it ended. */
    public synchronized boolean awaitEnd(long deadline) throws InterruptedException {
        while (!(this.closed && this.alive == 0 && this.entries.isEmpty())) {
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
