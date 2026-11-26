// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

public interface Future<V> {

    boolean cancel(boolean mayInterruptIfRunning);

    boolean isCancelled();

    boolean isDone();

    V get() throws InterruptedException, ExecutionException;

    V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;

    enum State {
        RUNNING, SUCCESS, FAILED, CANCELLED
    }

    /** The value of a task that ended with one, with no wait. */
    default V resultNow() {
        if (!isDone()) {
            throw new IllegalStateException("Task has not completed");
        }
        if (isCancelled()) {
            throw new IllegalStateException("Task was cancelled");
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return get();
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
                catch (ExecutionException e) {
                    throw new IllegalStateException("Task completed with exception");
                }
            }
        }
        finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** What a task that threw threw, with no wait. */
    default Throwable exceptionNow() {
        if (!isDone()) {
            throw new IllegalStateException("Task has not completed");
        }
        if (isCancelled()) {
            throw new IllegalStateException("Task was cancelled");
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    get();
                    throw new IllegalStateException("Task completed with a result");
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
                catch (ExecutionException e) {
                    return e.getCause();
                }
            }
        }
        finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    default State state() {
        if (!isDone()) {
            return State.RUNNING;
        }
        if (isCancelled()) {
            return State.CANCELLED;
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    get();
                    return State.SUCCESS;
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
                catch (ExecutionException e) {
                    return State.FAILED;
                }
            }
        }
        finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
