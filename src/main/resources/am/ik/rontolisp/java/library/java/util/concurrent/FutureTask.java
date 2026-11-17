// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

// A task run once, whose value or exception the threads in get() wait for under the
// task's monitor.
public class FutureTask<V> implements RunnableFuture<V> {

    private static final int WAITING = 0;

    private static final int RUNNING = 1;

    private static final int ANSWERED = 2;

    private static final int FAILED = 3;

    private static final int CANCELLED = 4;

    private Callable<V> callable;

    private int state;

    private V answer;

    private Throwable failure;

    private Thread runner;

    public FutureTask(Callable<V> callable) {
        if (callable == null) {
            throw new NullPointerException();
        }
        this.callable = callable;
    }

    public FutureTask(Runnable runnable, V result) {
        this(Executors.callable(runnable, result));
    }

    public void run() {
        Callable<V> task;
        synchronized (this) {
            if (this.state != WAITING) {
                return;
            }
            this.state = RUNNING;
            this.runner = Thread.currentThread();
            task = this.callable;
        }
        V value = null;
        Throwable thrown = null;
        try {
            value = task.call();
        }
        catch (Throwable t) {
            thrown = t;
        }
        synchronized (this) {
            this.runner = null;
            this.callable = null;
            if (this.state != RUNNING) {
                return;
            }
            if (thrown == null) {
                this.answer = value;
                this.state = ANSWERED;
            }
            else {
                this.failure = thrown;
                this.state = FAILED;
            }
            notifyAll();
        }
        done();
    }

    protected void done() {
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (this) {
            if (this.state >= ANSWERED) {
                return false;
            }
            if (mayInterruptIfRunning && this.runner != null) {
                this.runner.interrupt();
            }
            this.state = CANCELLED;
            this.callable = null;
            notifyAll();
        }
        done();
        return true;
    }

    public synchronized boolean isCancelled() {
        return this.state == CANCELLED;
    }

    public synchronized boolean isDone() {
        return this.state >= ANSWERED;
    }

    private V report() throws ExecutionException {
        if (this.state == CANCELLED) {
            throw new CancellationException();
        }
        if (this.state == FAILED) {
            throw new ExecutionException(this.failure);
        }
        return this.answer;
    }

    public V get() throws InterruptedException, ExecutionException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (this) {
            while (this.state < ANSWERED) {
                wait();
            }
            return report();
        }
    }

    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (this.state < ANSWERED) {
                if (!Timing.await(this, deadline)) {
                    throw new TimeoutException();
                }
            }
            return report();
        }
    }
}
