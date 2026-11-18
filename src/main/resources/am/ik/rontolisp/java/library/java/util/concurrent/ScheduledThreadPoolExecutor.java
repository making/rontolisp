// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A pool whose tasks run at their times: a task is a future holding its time, kept in
// the table its threads take the due ones from (rontolisp.internal.Timetable). A task
// that repeats is added again after each run, a period later; one whose run throws is
// not. The threads are the table's, so what a ThreadPoolExecutor says of its workers
// is said here of the table.
package java.util.concurrent;

import java.util.List;

import rontolisp.internal.Timetable;

public class ScheduledThreadPoolExecutor extends ThreadPoolExecutor implements ScheduledExecutorService {

    private final Timetable table;

    private boolean stopped;

    private boolean periodicAfterShutdown;

    private boolean delayedAfterShutdown = true;

    private boolean removeOnCancel;

    public ScheduledThreadPoolExecutor(int corePoolSize) {
        this(corePoolSize, Executors.defaultThreadFactory());
    }

    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0L, TimeUnit.NANOSECONDS, new LinkedBlockingQueue<Runnable>(),
                threadFactory);
        this.table = new Timetable(corePoolSize == 0 ? 1 : corePoolSize,
                (Runnable body) -> getThreadFactory().newThread(body));
        this.table.atEnd(() -> terminated());
    }

    private static final class ScheduledFutureTask<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {

        private final ScheduledThreadPoolExecutor pool;

        /** When the task runs next, as System.nanoTime() counts. */
        private long time;

        /** Above 0 the time from one run's time to the next's, below 0 from one's end; 0 for one run. */
        private final long period;

        ScheduledFutureTask(ScheduledThreadPoolExecutor pool, Callable<V> callable, long time, long period) {
            super(callable);
            this.pool = pool;
            this.time = time;
            this.period = period;
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(this.time - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        public int compareTo(Delayed other) {
            long difference = other instanceof ScheduledFutureTask<?> task ? this.time - task.time
                    : getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return difference < 0 ? -1 : difference > 0 ? 1 : 0;
        }

        public boolean isPeriodic() {
            return this.period != 0;
        }

        public void run() {
            // a future's run throws nothing, so the two hooks need no catch around it
            this.pool.beforeExecute(Thread.currentThread(), this);
            if (!this.pool.runs(this)) {
                cancel(false);
            }
            else if (this.period == 0) {
                super.run();
            }
            else if (runAndReset()) {
                this.time = this.period > 0 ? this.time + this.period : System.nanoTime() - this.period;
                if (this.pool.runs(this)) {
                    this.pool.table.add(this, this.time);
                }
                else {
                    cancel(false);
                }
            }
            this.pool.afterExecute(this, null);
        }

        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && this.pool.removes()) {
                this.pool.table.remove(this);
            }
            return cancelled;
        }
    }

    /** Whether a task still runs: any until shutdown(), after it as the two policies say. */
    private synchronized boolean runs(ScheduledFutureTask<?> task) {
        return !this.stopped || (task.isPeriodic() ? this.periodicAfterShutdown : this.delayedAfterShutdown);
    }

    private synchronized boolean removes() {
        return this.removeOnCancel;
    }

    private static long time(long delay, TimeUnit unit) {
        long nanos = unit.toNanos(delay);
        return System.nanoTime() + (nanos < 0 ? 0 : nanos);
    }

    private <V> ScheduledFutureTask<V> queue(ScheduledFutureTask<V> task) {
        if (isShutdown() || !this.table.add(task, task.time)) {
            throw new RejectedExecutionException("Task " + task + " rejected from " + this);
        }
        return task;
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        return queue(new ScheduledFutureTask<Object>(this, Executors.callable(command, null), time(delay, unit), 0));
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (callable == null || unit == null) {
            throw new NullPointerException();
        }
        return queue(new ScheduledFutureTask<V>(this, callable, time(delay, unit), 0));
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        if (period <= 0) {
            throw new IllegalArgumentException();
        }
        return queue(new ScheduledFutureTask<Object>(this, Executors.callable(command, null),
                time(initialDelay, unit), unit.toNanos(period)));
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        if (delay <= 0) {
            throw new IllegalArgumentException();
        }
        return queue(new ScheduledFutureTask<Object>(this, Executors.callable(command, null),
                time(initialDelay, unit), -unit.toNanos(delay)));
    }

    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, TimeUnit.NANOSECONDS);
    }

    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, TimeUnit.NANOSECONDS);
    }

    public Future<?> submit(Runnable task) {
        return schedule(task, 0, TimeUnit.NANOSECONDS);
    }

    /** No more tasks: the ones that repeat end, the ones waiting for their time still run. */
    public void shutdown() {
        synchronized (this) {
            this.stopped = true;
        }
        for (Runnable waiting : this.table.tasks()) {
            ScheduledFutureTask<?> task = (ScheduledFutureTask<?>) waiting;
            if (task.isCancelled() || !runs(task)) {
                this.table.remove(task);
                task.cancel(false);
            }
        }
        this.table.close();
    }

    public List<Runnable> shutdownNow() {
        synchronized (this) {
            this.stopped = true;
        }
        return this.table.drop(true);
    }

    public synchronized boolean isShutdown() {
        return this.stopped;
    }

    public boolean isTerminated() {
        return isShutdown() && this.table.isEnded();
    }

    public boolean isTerminating() {
        return isShutdown() && !this.table.isEnded();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return this.table.awaitEnd(Timing.deadline(timeout, unit));
    }

    public int getPoolSize() {
        return this.table.threads();
    }

    public void setCorePoolSize(int corePoolSize) {
        super.setCorePoolSize(corePoolSize);
        this.table.resize(corePoolSize == 0 ? 1 : corePoolSize);
    }

    public boolean prestartCoreThread() {
        return getCorePoolSize() > 0 && !isShutdown() && this.table.start();
    }

    /** Takes the tasks that were cancelled out of the table. */
    public void purge() {
        for (Runnable waiting : this.table.tasks()) {
            if (((ScheduledFutureTask<?>) waiting).isCancelled()) {
                this.table.remove(waiting);
            }
        }
    }

    public int getActiveCount() {
        return this.table.busy();
    }

    int waitingTasks() {
        return this.table.waiting();
    }

    public long getCompletedTaskCount() {
        return this.table.completed();
    }

    public synchronized void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        this.periodicAfterShutdown = value;
    }

    public synchronized boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return this.periodicAfterShutdown;
    }

    public synchronized void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        this.delayedAfterShutdown = value;
    }

    public synchronized boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return this.delayedAfterShutdown;
    }

    public synchronized void setRemoveOnCancelPolicy(boolean value) {
        this.removeOnCancel = value;
    }

    public synchronized boolean getRemoveOnCancelPolicy() {
        return this.removeOnCancel;
    }
}
