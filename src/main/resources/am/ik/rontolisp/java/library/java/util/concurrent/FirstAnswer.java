// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// invokeAny: the first value any of the tasks ends with, waited for under this
// object's monitor; when none ends with one, what the last of them threw.
final class FirstAnswer<T> {

    private boolean answered;

    private T answer;

    private Throwable last;

    private int left;

    private FirstAnswer(int tasks) {
        this.left = tasks;
    }

    private synchronized void answered(T value) {
        if (!this.answered) {
            this.answered = true;
            this.answer = value;
        }
        this.left--;
        notifyAll();
    }

    private synchronized void failed(Throwable thrown) {
        this.last = thrown;
        this.left--;
        notifyAll();
    }

    private synchronized T await(long nanos) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + nanos;
        while (!this.answered && this.left > 0) {
            if (nanos < 0) {
                wait();
            }
            else if (!Timing.await(this, deadline)) {
                throw new TimeoutException();
            }
        }
        if (this.answered) {
            return this.answer;
        }
        throw new ExecutionException(this.last);
    }

    /** Runs the tasks on an executor; a limit below 0 nanoseconds is none. */
    static <T> T of(ExecutorService executor, Collection<? extends Callable<T>> tasks, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks is empty");
        }
        FirstAnswer<T> first = new FirstAnswer<>(tasks.size());
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                if (task == null) {
                    throw new NullPointerException();
                }
                futures.add(executor.submit(() -> {
                    T value;
                    try {
                        value = task.call();
                    }
                    catch (Throwable thrown) {
                        first.failed(thrown);
                        throw thrown;
                    }
                    first.answered(value);
                    return value;
                }));
            }
            return first.await(nanos);
        }
        finally {
            for (Future<T> future : futures) {
                future.cancel(true);
            }
        }
    }
}
