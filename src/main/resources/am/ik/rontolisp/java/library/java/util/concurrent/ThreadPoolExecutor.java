// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What Executors.newFixedThreadPool and the like answer. A worker is a thread running
// the tasks handed to the pool, one after another; the pool's state is read and written
// under its monitor, on which an idle worker waits for a task. Where the target runs
// the threads of a program on one stack (wasm) a worker with no task left cannot wait
// for one -- the thread to hand it one is suspended under it -- so it ends, and a later
// task starts a thread of the same name in its place.
package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import rontolisp.internal.Support;

public class ThreadPoolExecutor implements ExecutorService {

    /** The workers made, one a task, before a task waits for a worker. */
    private final int core;

    /** Whether a task no idle worker can take is given a worker of its own. */
    private final boolean growing;

    /** How long an idle worker waits for a task, in nanoseconds; 0 for ever. */
    private final long keepAlive;

    private final ThreadFactory factory;

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    private final ArrayList<Worker> workers = new ArrayList<>();

    /**
     * The workers with no thread running: one stack's that ended for want of a task,
     * and anywhere the one that takes the place of a worker a task's exception ended.
     */
    private final ArrayDeque<Worker> resting = new ArrayDeque<>();

    /** The workers waiting for a task. */
    private int idle;

    private boolean shutdown;

    ThreadPoolExecutor(int core, boolean growing, long keepAlive, ThreadFactory factory) {
        if (core < 0 || (core == 0 && !growing)) {
            throw new IllegalArgumentException();
        }
        if (factory == null) {
            throw new NullPointerException();
        }
        this.core = core;
        this.growing = growing;
        this.keepAlive = keepAlive;
        this.factory = factory;
    }

    private static final class Worker implements Runnable {

        private final ThreadPoolExecutor pool;

        private Runnable first;

        private Thread thread;

        Worker(ThreadPoolExecutor pool, Runnable first) {
            this.pool = pool;
            this.first = first;
        }

        public void run() {
            Runnable task = this.first;
            this.first = null;
            boolean dismissed = false;
            try {
                while (true) {
                    if (task == null) {
                        task = this.pool.next(this);
                        if (task == null) {
                            dismissed = true;
                            return;
                        }
                    }
                    Runnable running = task;
                    task = null;
                    running.run();
                }
            }
            finally {
                this.pool.ended(this, !dismissed);
            }
        }
    }

    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        Thread started = null;
        synchronized (this) {
            if (this.shutdown) {
                throw new RejectedExecutionException();
            }
            int made = this.workers.size() + this.resting.size();
            if (made < this.core || (this.growing && this.idle + this.resting.size() <= this.tasks.size())) {
                Worker worker = new Worker(this, command);
                worker.thread = this.factory.newThread(worker);
                this.workers.add(worker);
                started = worker.thread;
            }
            else {
                this.tasks.add(command);
                Worker rested = this.resting.poll();
                if (rested == null) {
                    notify();
                }
                else if (rested.thread.getState() == Thread.State.NEW) {
                    this.workers.add(rested);
                    started = rested.thread;
                }
                else {
                    // one stack: the thread that ended for want of a task, again
                    Thread again = new Thread(rested, rested.thread.getName());
                    again.setDaemon(rested.thread.isDaemon());
                    again.setPriority(rested.thread.getPriority());
                    rested.thread = again;
                    this.workers.add(rested);
                    started = again;
                }
            }
        }
        if (started != null) {
            started.start();
        }
    }

    /** The next task of a worker that ended one; null when the worker is to end. */
    private synchronized Runnable next(Worker worker) {
        long deadline = System.nanoTime() + this.keepAlive;
        while (true) {
            Runnable task = this.tasks.poll();
            if (task != null) {
                return task;
            }
            if (this.shutdown) {
                return null;
            }
            if (Support.oneStack()) {
                this.resting.add(worker);
                return null;
            }
            this.idle++;
            try {
                if (this.keepAlive == 0) {
                    wait();
                }
                else if (!Timing.await(this, deadline) && this.tasks.isEmpty()) {
                    return null;
                }
            }
            catch (InterruptedException e) {
                // shutdownNow(): the worker ends once it finds no task
            }
            finally {
                this.idle--;
            }
        }
    }

    /**
     * A worker's end. One a task's exception ended is replaced by a thread of the next
     * name: started with the next task when one waits, else kept for the task to come.
     */
    private void ended(Worker worker, boolean thrown) {
        Thread started = null;
        synchronized (this) {
            this.workers.remove(worker);
            if (thrown && !(this.shutdown && this.tasks.isEmpty())) {
                Worker next = new Worker(this, this.tasks.poll());
                next.thread = this.factory.newThread(next);
                if (next.first == null) {
                    this.resting.add(next);
                }
                else {
                    this.workers.add(next);
                    started = next.thread;
                }
            }
            if (this.workers.isEmpty()) {
                notifyAll();
            }
        }
        if (started != null) {
            started.start();
        }
    }

    public synchronized void shutdown() {
        this.shutdown = true;
        this.resting.clear();
        notifyAll();
    }

    public synchronized List<Runnable> shutdownNow() {
        this.shutdown = true;
        this.resting.clear();
        List<Runnable> waiting = new ArrayList<>(this.tasks);
        this.tasks.clear();
        for (Worker worker : this.workers) {
            worker.thread.interrupt();
        }
        notifyAll();
        return waiting;
    }

    public synchronized boolean isShutdown() {
        return this.shutdown;
    }

    public synchronized boolean isTerminated() {
        return this.shutdown && this.workers.isEmpty();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (!(this.shutdown && this.workers.isEmpty())) {
                if (!Timing.await(this, deadline)) {
                    return false;
                }
            }
            return true;
        }
    }

    public synchronized int getPoolSize() {
        return this.workers.size() + this.resting.size();
    }

    public int getCorePoolSize() {
        return this.core;
    }

    public synchronized int getActiveCount() {
        return this.workers.size() - this.idle;
    }

    public synchronized int getQueueSize() {
        return this.tasks.size();
    }
}
