// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A task that splits into tasks. Its state is read and written under the task's
// monitor, on which the threads waiting for it wait. One thread runs it: a thread of
// the pool it was handed to, or -- before any has -- the pool's thread that joins it,
// so a task waiting for the tasks it forked needs no thread but its own.
package java.util.concurrent;

import java.util.Collection;

public abstract class ForkJoinTask<V> implements Future<V> {

    private static final int WAITING = 0;

    private static final int RUNNING = 1;

    private static final int ENDED = 2;

    private static final int THROWN = 3;

    private static final int CANCELLED = 4;

    /** The pool whose task the thread runs. */
    private static final ThreadLocal<ForkJoinPool> running = new ThreadLocal<>();

    private int state;

    private Throwable thrown;

    public ForkJoinTask() {
    }

    public abstract V getRawResult();

    protected abstract void setRawResult(V value);

    protected abstract boolean exec();

    /** Runs the task as a pool's, when no thread has: whether this thread ran it. */
    final boolean run(ForkJoinPool pool) {
        synchronized (this) {
            if (this.state != WAITING) {
                return false;
            }
            this.state = RUNNING;
        }
        ForkJoinPool around = running.get();
        running.set(pool);
        boolean ended = false;
        Throwable failure = null;
        try {
            ended = exec();
        }
        catch (Throwable caught) {
            failure = caught;
        }
        running.set(around);
        synchronized (this) {
            if (this.state == RUNNING) {
                if (failure != null) {
                    this.thrown = failure;
                    this.state = THROWN;
                }
                else if (ended) {
                    this.state = ENDED;
                }
            }
            notifyAll();
        }
        return true;
    }

    /** Hands the task to the pool the thread runs a task of, else to the one every program shares. */
    public final ForkJoinTask<V> fork() {
        ForkJoinPool pool = running.get();
        (pool != null ? pool : ForkJoinPool.commonPool()).push(this);
        return this;
    }

    /** Waits for the task's end, whatever interrupts. */
    final void await() {
        boolean interrupted = false;
        synchronized (this) {
            while (this.state < ENDED) {
                try {
                    wait();
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** What the task ended with: its value, or what it threw, thrown again. */
    final V report() {
        Throwable failure;
        synchronized (this) {
            if (this.state == CANCELLED) {
                throw new CancellationException();
            }
            failure = this.state == THROWN ? this.thrown : null;
        }
        if (failure instanceof RuntimeException unchecked) {
            throw unchecked;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new RuntimeException(failure);
        }
        return getRawResult();
    }

    public final V join() {
        ForkJoinPool pool = running.get();
        if (pool != null) {
            run(pool);
        }
        await();
        return report();
    }

    /** Runs the task on this thread, when no thread has, and answers its value. */
    public final V invoke() {
        run(running.get());
        await();
        return report();
    }

    public final void quietlyJoin() {
        ForkJoinPool pool = running.get();
        if (pool != null) {
            run(pool);
        }
        await();
    }

    public final void quietlyInvoke() {
        run(running.get());
        await();
    }

    public static void invokeAll(ForkJoinTask<?> t1, ForkJoinTask<?> t2) {
        t2.fork();
        t1.quietlyInvoke();
        t2.quietlyJoin();
        t1.report();
        t2.report();
    }

    public static void invokeAll(ForkJoinTask<?>... tasks) {
        for (int i = tasks.length - 1; i > 0; i--) {
            tasks[i].fork();
        }
        if (tasks.length > 0) {
            tasks[0].quietlyInvoke();
        }
        for (int i = 1; i < tasks.length; i++) {
            tasks[i].quietlyJoin();
        }
        for (ForkJoinTask<?> task : tasks) {
            task.report();
        }
    }

    public static <T extends ForkJoinTask<?>> Collection<T> invokeAll(Collection<T> tasks) {
        ForkJoinTask<?> first = null;
        for (T task : tasks) {
            if (first == null) {
                first = task;
            }
            else {
                task.fork();
            }
        }
        if (first != null) {
            first.quietlyInvoke();
        }
        for (T task : tasks) {
            task.quietlyJoin();
        }
        for (T task : tasks) {
            task.report();
        }
        return tasks;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (this) {
            if (this.state == WAITING) {
                this.state = CANCELLED;
                notifyAll();
            }
            return this.state == CANCELLED;
        }
    }

    public final synchronized boolean isDone() {
        return this.state >= ENDED;
    }

    public final synchronized boolean isCancelled() {
        return this.state == CANCELLED;
    }

    public final synchronized boolean isCompletedNormally() {
        return this.state == ENDED;
    }

    public final synchronized boolean isCompletedAbnormally() {
        return this.state > ENDED;
    }

    public final synchronized Throwable getException() {
        return this.state == THROWN ? this.thrown : this.state == CANCELLED ? new CancellationException() : null;
    }

    public void complete(V value) {
        setRawResult(value);
        synchronized (this) {
            if (this.state < ENDED) {
                this.state = ENDED;
                notifyAll();
            }
        }
    }

    public void completeExceptionally(Throwable ex) {
        synchronized (this) {
            if (this.state < ENDED) {
                this.thrown = ex;
                this.state = THROWN;
                notifyAll();
            }
        }
    }

    private V got() throws ExecutionException {
        Throwable failure;
        synchronized (this) {
            if (this.state == CANCELLED) {
                throw new CancellationException();
            }
            failure = this.state == THROWN ? this.thrown : null;
        }
        if (failure != null) {
            throw new ExecutionException(failure);
        }
        return getRawResult();
    }

    public final V get() throws InterruptedException, ExecutionException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        ForkJoinPool pool = running.get();
        if (pool != null) {
            run(pool);
        }
        synchronized (this) {
            while (this.state < ENDED) {
                wait();
            }
        }
        return got();
    }

    public final V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
            TimeoutException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        ForkJoinPool pool = running.get();
        if (pool != null) {
            run(pool);
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (this.state < ENDED) {
                if (!Timing.await(this, deadline)) {
                    throw new TimeoutException();
                }
            }
        }
        return got();
    }

    /** The pool whose task the thread runs, or null. */
    public static ForkJoinPool getPool() {
        return running.get();
    }

    public static boolean inForkJoinPool() {
        return running.get() != null;
    }

    private static final class Called<T> extends ForkJoinTask<T> {

        private final Callable<? extends T> callable;

        private T result;

        Called(Callable<? extends T> callable) {
            if (callable == null) {
                throw new NullPointerException();
            }
            this.callable = callable;
        }

        public T getRawResult() {
            return this.result;
        }

        protected void setRawResult(T value) {
            this.result = value;
        }

        protected boolean exec() {
            try {
                this.result = this.callable.call();
            }
            catch (RuntimeException e) {
                throw e;
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        }
    }

    public static <T> ForkJoinTask<T> adapt(Callable<? extends T> callable) {
        return new Called<T>(callable);
    }

    public static <T> ForkJoinTask<T> adapt(Runnable runnable, T result) {
        return new Called<T>(Executors.callable(runnable, result));
    }

    public static ForkJoinTask<?> adapt(Runnable runnable) {
        return new Called<Object>(Executors.callable(runnable, null));
    }
}
