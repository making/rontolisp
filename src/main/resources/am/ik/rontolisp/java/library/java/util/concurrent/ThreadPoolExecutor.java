// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// What Executors.newFixedThreadPool and the like answer, and what a program makes of a
// number of threads and a queue. A worker is a thread running the tasks handed to the
// pool, one after another; the pool's state is read and written under its monitor, on
// which an idle worker waits for a task. A task is given a new worker while fewer than
// the core number run, else it waits in the queue, else -- the queue full -- it is given
// a new worker up to the most the pool may have, else the pool's handler is asked. A
// SynchronousQueue holds nothing: a task waits in it only as long as it takes an idle
// worker to wake, so the pool keeps such a task itself. beforeExecute and afterExecute
// are asked only in a program one of whose classes declares one (hooks): afterExecute
// is handed what the task threw, which takes a catch of every exception around each
// task.
package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class ThreadPoolExecutor implements ExecutorService {

    /** A task the pool cannot take is refused. */
    public static class AbortPolicy implements RejectedExecutionHandler {

        public AbortPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r + " rejected from " + e);
        }
    }

    /** A task the pool cannot take runs on the thread handing it over. */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {

        public CallerRunsPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /** A task the pool cannot take is dropped. */
    public static class DiscardPolicy implements RejectedExecutionHandler {

        public DiscardPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /** A task the pool cannot take takes the place of the one that waited longest. */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {

        public DiscardOldestPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }

    /** What runs a task between the two hooks around it. */
    private interface Hooks {

        void run(ThreadPoolExecutor pool, Thread thread, Runnable task);
    }

    /** Made in a program one of whose classes declares a hook, which alone holds the catch. */
    private static final class Hooked implements Hooks {

        public void run(ThreadPoolExecutor pool, Thread thread, Runnable task) {
            pool.beforeExecute(thread, task);
            try {
                task.run();
                pool.afterExecute(task, null);
            }
            catch (Throwable thrown) {
                pool.afterExecute(task, thrown);
                throw thrown;
            }
        }
    }

    /** The workers made, one a task, before a task waits for a worker. */
    private int core;

    /** The most workers the pool has. */
    private int most;

    /** How long an idle worker above the core number waits for a task, in nanoseconds. */
    private long keepAlive;

    /** Whether the workers up to the core number end as the others, for want of a task. */
    private boolean coreEnds;

    private ThreadFactory factory;

    private final Hooks hooks = hooks();

    private RejectedExecutionHandler handler;

    /** Where a task waits for a worker: the program's own queue when it gave one. */
    private final BlockingQueue<Runnable> queue;

    /** The tasks a queue that holds nothing would hand an idle worker. */
    private final ArrayDeque<Runnable> handed;

    private final ArrayList<Worker> workers = new ArrayList<>();

    /**
     * The workers whose thread is not started yet: the one that takes the place of a
     * worker a task's exception ended, until a task comes for it.
     */
    private final ArrayDeque<Worker> resting = new ArrayDeque<>();

    /** The workers waiting for a task. */
    private int idle;

    private boolean shutdown;

    /** Whether terminated() was called, and whether it has answered. */
    private boolean closing;

    private boolean over;

    private long completed;

    private int largest;

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(),
                new AbortPolicy());
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, new AbortPolicy());
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(),
                handler);
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException("corePoolSize must be non-negative");
        }
        if (maximumPoolSize <= 0) {
            throw new IllegalArgumentException("maximumPoolSize must be positive");
        }
        if (maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maximumPoolSize must be greater than or equal to corePoolSize");
        }
        if (keepAliveTime < 0) {
            throw new IllegalArgumentException("keepAliveTime must be non-negative");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        if (workQueue == null) {
            throw new NullPointerException("workQueue");
        }
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.core = corePoolSize;
        this.most = maximumPoolSize;
        this.keepAlive = unit.toNanos(keepAliveTime);
        this.queue = workQueue;
        this.handed = workQueue instanceof SynchronousQueue ? new ArrayDeque<>() : null;
        this.factory = threadFactory;
        this.handler = handler;
    }

    /**
     * A Hooked in a program one of whose classes declares beforeExecute or afterExecute,
     * null in any other: answered where the program is lowered.
     */
    private static native Hooks hooks();

    private static Hooks hooked() {
        return new Hooked();
    }

    /** Asked on the worker's thread before it runs a task. */
    protected void beforeExecute(Thread t, Runnable r) {
    }

    /** Asked on the worker's thread after a task: with what the task threw, or null. */
    protected void afterExecute(Runnable r, Throwable t) {
    }

    /** Asked once, when the pool has ended. */
    protected void terminated() {
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
                    Hooks hooks = this.pool.hooks;
                    if (hooks == null) {
                        running.run();
                    }
                    else {
                        hooks.run(this.pool, this.thread, running);
                    }
                    this.pool.ran();
                }
            }
            finally {
                this.pool.ended(this, !dismissed);
            }
        }
    }

    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        Thread started = null;
        boolean refused = false;
        synchronized (this) {
            int made = this.workers.size() + this.resting.size();
            if (this.shutdown) {
                refused = true;
            }
            else if (made < this.core) {
                started = hire(command, made);
            }
            else if (waits(command)) {
                Worker rested = this.resting.poll();
                if (rested != null) {
                    this.workers.add(rested);
                    started = rested.thread;
                }
                else if (made == 0) {
                    // a pool with no core worker: the one that takes the task out again
                    started = hire(null, made);
                }
                else {
                    notify();
                }
            }
            else if (made < this.most) {
                started = hire(command, made);
            }
            else {
                refused = true;
            }
        }
        if (started != null) {
            started.start();
        }
        if (refused) {
            this.handler.rejectedExecution(command, this);
        }
    }

    /** A new worker whose first task is the one given, or with none yet: its thread, to start. */
    private Thread hire(Runnable command, int made) {
        Worker worker = new Worker(this, command);
        worker.thread = this.factory.newThread(worker);
        this.workers.add(worker);
        this.largest = Math.max(this.largest, made + 1);
        return worker.thread;
    }

    /**
     * Puts a task where it waits for a worker, when it can. A queue that holds nothing
     * takes a task only while a worker is free for it.
     */
    private boolean waits(Runnable command) {
        if (this.handed == null) {
            return this.queue.offer(command);
        }
        if (this.idle + this.resting.size() <= this.handed.size()) {
            return false;
        }
        this.handed.add(command);
        return true;
    }

    private Runnable waiting() {
        return this.handed == null ? this.queue.poll() : this.handed.poll();
    }

    private int queued() {
        return this.handed == null ? this.queue.size() : this.handed.size();
    }

    private synchronized void ran() {
        this.completed++;
    }

    /** The next task of a worker that ended one; null when the worker is to end. */
    private synchronized Runnable next(Worker worker) {
        long deadline = 0L;
        boolean timed = false;
        while (true) {
            Runnable task = waiting();
            if (task != null) {
                return task;
            }
            if (this.shutdown) {
                return null;
            }
            boolean ending = this.coreEnds || this.workers.size() > this.core;
            if (ending && this.keepAlive == 0) {
                return null;
            }
            if (ending && !timed) {
                // the time runs from where the worker found that it is to end
                deadline = System.nanoTime() + this.keepAlive;
            }
            timed = ending;
            this.idle++;
            try {
                if (!ending) {
                    wait();
                }
                else if (!Timing.await(this, deadline) && queued() == 0) {
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
            if (thrown) {
                // a task that threw is a task the pool is done with
                this.completed++;
            }
            if (thrown && !(this.shutdown && queued() == 0)) {
                Worker next = new Worker(this, waiting());
                next.thread = this.factory.newThread(next);
                if (next.first == null) {
                    this.resting.add(next);
                }
                else {
                    this.workers.add(next);
                    started = next.thread;
                }
            }
        }
        if (started != null) {
            started.start();
        }
        end();
    }

    /**
     * The pool's end, once it is shut down and its last worker is gone: terminated() is
     * asked on the thread that finds it so, outside the pool's monitor, and until it
     * answers the pool is ending and has not ended.
     */
    private void end() {
        synchronized (this) {
            if (!this.shutdown || this.closing || !this.workers.isEmpty()) {
                return;
            }
            this.closing = true;
        }
        try {
            terminated();
        }
        finally {
            synchronized (this) {
                this.over = true;
                notifyAll();
            }
        }
    }

    public void shutdown() {
        synchronized (this) {
            this.shutdown = true;
            this.resting.clear();
            notifyAll();
        }
        end();
    }

    public List<Runnable> shutdownNow() {
        List<Runnable> waiting = new ArrayList<>();
        synchronized (this) {
            this.shutdown = true;
            this.resting.clear();
            Runnable task;
            while ((task = waiting()) != null) {
                waiting.add(task);
            }
            for (Worker worker : this.workers) {
                worker.thread.interrupt();
            }
            notifyAll();
        }
        end();
        return waiting;
    }

    public synchronized boolean isShutdown() {
        return this.shutdown;
    }

    public synchronized boolean isTerminating() {
        return this.shutdown && !this.over;
    }

    public synchronized boolean isTerminated() {
        return this.over;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (!this.over) {
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

    public synchronized int getCorePoolSize() {
        return this.core;
    }

    /** A larger number starts a worker for each task that waits, up to the difference. */
    public void setCorePoolSize(int corePoolSize) {
        ArrayList<Thread> started = new ArrayList<>();
        synchronized (this) {
            if (corePoolSize < 0) {
                throw new IllegalArgumentException("corePoolSize must be non-negative");
            }
            if (corePoolSize > this.most) {
                throw new IllegalArgumentException("corePoolSize must be less than or equal to maximumPoolSize");
            }
            int more = corePoolSize - this.core;
            this.core = corePoolSize;
            while (more-- > 0 && !this.shutdown && this.workers.size() + this.resting.size() < corePoolSize) {
                Runnable task = waiting();
                if (task == null) {
                    break;
                }
                started.add(hire(task, this.workers.size() + this.resting.size()));
            }
            // an idle worker above the new number finds out that it is to end
            notifyAll();
        }
        for (Thread thread : started) {
            thread.start();
        }
    }

    /** Starts a worker with no task yet, while fewer than the core number run: whether it did. */
    public boolean prestartCoreThread() {
        Thread started;
        synchronized (this) {
            int made = this.workers.size() + this.resting.size();
            if (this.shutdown || made >= this.core) {
                return false;
            }
            started = hire(null, made);
        }
        started.start();
        return true;
    }

    public int prestartAllCoreThreads() {
        int started = 0;
        while (prestartCoreThread()) {
            started++;
        }
        return started;
    }

    public synchronized int getActiveCount() {
        return this.workers.size() - this.idle;
    }

    public synchronized int getMaximumPoolSize() {
        return this.most;
    }

    public synchronized void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0) {
            throw new IllegalArgumentException("maximumPoolSize must be positive");
        }
        if (maximumPoolSize < this.core) {
            throw new IllegalArgumentException("maximumPoolSize must be greater than or equal to corePoolSize");
        }
        this.most = maximumPoolSize;
        notifyAll();
    }

    public synchronized int getLargestPoolSize() {
        return this.largest;
    }

    public synchronized long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(this.keepAlive, TimeUnit.NANOSECONDS);
    }

    public synchronized void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0) {
            throw new IllegalArgumentException("time must be non-negative");
        }
        if (time == 0 && this.coreEnds) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        this.keepAlive = unit.toNanos(time);
        notifyAll();
    }

    public synchronized void allowCoreThreadTimeOut(boolean value) {
        if (value && this.keepAlive <= 0) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        this.coreEnds = value;
        notifyAll();
    }

    public synchronized boolean allowsCoreThreadTimeOut() {
        return this.coreEnds;
    }

    public BlockingQueue<Runnable> getQueue() {
        return this.queue;
    }

    public synchronized ThreadFactory getThreadFactory() {
        return this.factory;
    }

    public synchronized void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        this.factory = threadFactory;
    }

    public synchronized RejectedExecutionHandler getRejectedExecutionHandler() {
        return this.handler;
    }

    public synchronized void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
    }

    /** Takes a task that waits for a worker out of the queue. */
    public synchronized boolean remove(Runnable task) {
        return this.handed == null ? this.queue.remove(task) : this.handed.remove(task);
    }

    /** Takes the futures that were cancelled out of the queue. */
    public synchronized void purge() {
        for (Object waiting : this.handed == null ? this.queue.toArray() : this.handed.toArray()) {
            if (waiting instanceof Future<?> future && future.isCancelled()) {
                remove((Runnable) waiting);
            }
        }
    }

    public synchronized long getCompletedTaskCount() {
        return this.completed;
    }

    public synchronized long getTaskCount() {
        return this.completed + queued() + getActiveCount();
    }

    /** How many tasks wait for a worker: a scheduled pool's wait for their times. */
    int waitingTasks() {
        synchronized (this) {
            return queued();
        }
    }

    public String toString() {
        String state = !isShutdown() ? "Running" : isTerminated() ? "Terminated" : "Shutting down";
        return super.toString() + "[" + state + ", pool size = " + getPoolSize() + ", active threads = "
                + getActiveCount() + ", queued tasks = " + waitingTasks() + ", completed tasks = "
                + getCompletedTaskCount() + "]";
    }
}
