// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public interface ExecutorService extends Executor, AutoCloseable {

    void shutdown();

    List<Runnable> shutdownNow();

    boolean isShutdown();

    boolean isTerminated();

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    default <T> Future<T> submit(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        execute(future);
        return future;
    }

    default <T> Future<T> submit(Runnable task, T result) {
        FutureTask<T> future = new FutureTask<>(task, result);
        execute(future);
        return future;
    }

    default Future<?> submit(Runnable task) {
        FutureTask<Object> future = new FutureTask<>(task, null);
        execute(future);
        return future;
    }

    /** Every task run to its end, in the order given. */
    default <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }
        for (Future<T> future : futures) {
            try {
                future.get();
            }
            catch (ExecutionException | CancellationException e) {
                // the caller reads it from the future
            }
        }
        return futures;
    }

    /**
     * Every task run to its end or until the time is up, whichever comes first: a task
     * is handed over while time is left, and one still open when the wait ends -- by
     * the limit, or by an interrupt -- is cancelled.
     */
    default <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        List<Future<T>> futures = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        for (Callable<T> task : tasks) {
            FutureTask<T> future = new FutureTask<>(task);
            futures.add(future);
            runs.add(future);
        }
        long deadline = System.nanoTime() + nanos;
        try {
            long left = nanos;
            for (int i = 0; i < runs.size() && left > 0; i++) {
                execute(runs.get(i));
                left = deadline - System.nanoTime();
            }
            for (int i = 0; i < futures.size() && left > 0; i++) {
                try {
                    futures.get(i).get(left, TimeUnit.NANOSECONDS);
                }
                catch (ExecutionException | CancellationException e) {
                    // the caller reads it from the future
                }
                catch (TimeoutException e) {
                    break;
                }
                left = deadline - System.nanoTime();
            }
        }
        finally {
            for (Future<T> future : futures) {
                future.cancel(true);
            }
        }
        return futures;
    }

    /** The value of the first task to end with one; the others are cancelled. */
    default <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        try {
            return FirstAnswer.of(this, tasks, -1L);
        }
        catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    default <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        return FirstAnswer.of(this, tasks, nanos < 0 ? 0 : nanos);
    }

    /**
     * No more tasks, and the ones given all ended: an interrupt meanwhile stops them
     * as shutdownNow() does, and is the thread's again at the end.
     */
    default void close() {
        if (isTerminated()) {
            return;
        }
        shutdown();
        boolean interrupted = false;
        boolean ended = false;
        while (!ended) {
            try {
                ended = awaitTermination(1L, TimeUnit.DAYS);
            }
            catch (InterruptedException e) {
                if (!interrupted) {
                    shutdownNow();
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
