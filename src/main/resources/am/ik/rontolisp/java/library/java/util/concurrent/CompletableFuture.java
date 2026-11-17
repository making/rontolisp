// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A value or an exception set once, under the future's monitor, on which join() and
// get() wait; what depends on it runs where it is set, or at once when it was. An
// exception a stage's own function threw, and one a stage takes over from the stage
// before it, is held in a CompletionException; one given to completeExceptionally is
// held as it is. A task with no executor runs on a daemon thread of the pool every
// program shares.
package java.util.concurrent;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {

    private static Executor shared;

    private boolean done;

    private T value;

    private Throwable failure;

    /** What runs once the future is done. */
    private ArrayList<Runnable> dependents;

    public CompletableFuture() {
    }

    /** Threads named as the workers of the pool a JDK shares, which are daemons. */
    private static final class Workers implements ThreadFactory {

        private int made;

        public synchronized Thread newThread(Runnable r) {
            Thread worker = new Thread(r, "ForkJoinPool.commonPool-worker-" + ++this.made);
            worker.setDaemon(true);
            return worker;
        }
    }

    private static synchronized Executor shared() {
        if (shared == null) {
            shared = new ThreadPoolExecutor(0, true, 60000000000L, new Workers());
        }
        return shared;
    }

    private static <V> V given(V value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return value;
    }

    private static Throwable held(Throwable thrown) {
        return thrown instanceof CompletionException ? thrown : new CompletionException(thrown);
    }

    private boolean settle(T value, Throwable failure) {
        ArrayList<Runnable> ready;
        synchronized (this) {
            if (this.done) {
                return false;
            }
            this.done = true;
            this.value = value;
            this.failure = failure;
            ready = this.dependents;
            this.dependents = null;
            notifyAll();
        }
        if (ready != null) {
            for (Runnable dependent : ready) {
                dependent.run();
            }
        }
        return true;
    }

    /** Runs an action once the future is done: now when it is. */
    private void then(Executor executor, Runnable action) {
        Runnable dependent = executor == null ? action : () -> executor.execute(action);
        synchronized (this) {
            if (!this.done) {
                if (this.dependents == null) {
                    this.dependents = new ArrayList<>();
                }
                this.dependents.add(dependent);
                return;
            }
        }
        dependent.run();
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return supplyAsync(supplier, shared());
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
        given(supplier);
        CompletableFuture<U> made = new CompletableFuture<>();
        given(executor).execute(() -> {
            U answer;
            try {
                answer = supplier.get();
            }
            catch (Throwable thrown) {
                made.settle(null, held(thrown));
                return;
            }
            made.settle(answer, null);
        });
        return made;
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return runAsync(runnable, shared());
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        given(runnable);
        return supplyAsync(() -> {
            runnable.run();
            return null;
        }, executor);
    }

    public static <U> CompletableFuture<U> completedFuture(U value) {
        CompletableFuture<U> made = new CompletableFuture<>();
        made.settle(value, null);
        return made;
    }

    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        CompletableFuture<U> made = new CompletableFuture<>();
        made.settle(null, given(ex));
        return made;
    }

    /** Done when every one is: failed as the first of them, in the order given, that failed. */
    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        CompletableFuture<Void> all = new CompletableFuture<>();
        int[] left = { cfs.length };
        if (cfs.length == 0) {
            all.settle(null, null);
        }
        for (CompletableFuture<?> each : cfs) {
            given(each).then(null, () -> {
                boolean last;
                synchronized (left) {
                    last = --left[0] == 0;
                }
                if (last) {
                    for (CompletableFuture<?> one : cfs) {
                        if (one.failure != null) {
                            all.settle(null, held(one.failure));
                            return;
                        }
                    }
                    all.settle(null, null);
                }
            });
        }
        return all;
    }

    /** Done as the first of them to be done. */
    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
        CompletableFuture<Object> any = new CompletableFuture<>();
        for (CompletableFuture<?> each : cfs) {
            given(each).then(null, () -> {
                if (each.failure != null) {
                    any.settle(null, held(each.failure));
                }
                else {
                    any.settle(each.value, null);
                }
            });
        }
        return any;
    }

    public boolean complete(T value) {
        return settle(value, null);
    }

    public boolean completeExceptionally(Throwable ex) {
        return settle(null, given(ex));
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        settle(null, new CancellationException());
        return isCancelled();
    }

    public synchronized boolean isDone() {
        return this.done;
    }

    public synchronized boolean isCancelled() {
        return this.failure instanceof CancellationException;
    }

    public synchronized boolean isCompletedExceptionally() {
        return this.failure != null;
    }

    private T joined() {
        if (this.failure == null) {
            return this.value;
        }
        if (this.failure instanceof CancellationException) {
            throw cancelled("join");
        }
        throw (CompletionException) held(this.failure);
    }

    /** What a cancelled future throws: named by the member asked, the cancelling its cause. */
    private CancellationException cancelled(String asked) {
        CancellationException thrown = new CancellationException(asked);
        thrown.initCause(this.failure);
        return thrown;
    }

    public T join() {
        boolean interrupted = false;
        synchronized (this) {
            while (!this.done) {
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
        return joined();
    }

    public synchronized T getNow(T valueIfAbsent) {
        return this.done ? joined() : valueIfAbsent;
    }

    private T got() throws ExecutionException {
        if (this.failure == null) {
            return this.value;
        }
        if (this.failure instanceof CancellationException) {
            throw cancelled("get");
        }
        Throwable cause = this.failure instanceof CompletionException && this.failure.getCause() != null
                ? this.failure.getCause() : this.failure;
        throw new ExecutionException(cause);
    }

    public T get() throws InterruptedException, ExecutionException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (this) {
            while (!this.done) {
                wait();
            }
        }
        return got();
    }

    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long deadline = Timing.deadline(timeout, unit);
        synchronized (this) {
            while (!this.done) {
                if (!Timing.await(this, deadline)) {
                    throw new TimeoutException();
                }
            }
        }
        return got();
    }

    public CompletableFuture<T> toCompletableFuture() {
        return this;
    }

    private <U> CompletableFuture<U> applying(Function<? super T, ? extends U> fn, Executor executor) {
        given(fn);
        CompletableFuture<U> next = new CompletableFuture<>();
        then(executor, () -> {
            if (this.failure != null) {
                next.settle(null, held(this.failure));
                return;
            }
            U made;
            try {
                made = fn.apply(this.value);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown));
                return;
            }
            next.settle(made, null);
        });
        return next;
    }

    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return applying(fn, null);
    }

    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return applying(fn, shared());
    }

    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        return applying(fn, given(executor));
    }

    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        given(action);
        return applying((T taken) -> {
            action.accept(taken);
            return null;
        }, null);
    }

    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return thenAcceptAsync(action, shared());
    }

    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        given(action);
        return applying((T taken) -> {
            action.accept(taken);
            return null;
        }, given(executor));
    }

    public CompletableFuture<Void> thenRun(Runnable action) {
        given(action);
        return applying((T taken) -> {
            action.run();
            return null;
        }, null);
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return thenRunAsync(action, shared());
    }

    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        given(action);
        return applying((T taken) -> {
            action.run();
            return null;
        }, given(executor));
    }

    private <U> CompletableFuture<U> composing(Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor) {
        given(fn);
        CompletableFuture<U> next = new CompletableFuture<>();
        then(executor, () -> {
            if (this.failure != null) {
                next.settle(null, held(this.failure));
                return;
            }
            CompletableFuture<U> inner;
            try {
                inner = fn.apply(this.value).toCompletableFuture();
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown));
                return;
            }
            inner.then(null, () -> {
                if (inner.failure != null) {
                    next.settle(null, held(inner.failure));
                }
                else {
                    next.settle(inner.value, null);
                }
            });
        });
        return next;
    }

    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return composing(fn, null);
    }

    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return composing(fn, shared());
    }

    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor) {
        return composing(fn, given(executor));
    }

    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        given(fn);
        CompletableFuture<? extends U> second = given(other).toCompletableFuture();
        CompletableFuture<V> next = new CompletableFuture<>();
        then(null, () -> second.then(null, () -> {
            if (this.failure != null) {
                next.settle(null, held(this.failure));
                return;
            }
            if (second.failure != null) {
                next.settle(null, held(second.failure));
                return;
            }
            V made;
            try {
                made = fn.apply(this.value, second.value);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown));
                return;
            }
            next.settle(made, null);
        }));
        return next;
    }

    private <U> CompletableFuture<U> handling(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        given(fn);
        CompletableFuture<U> next = new CompletableFuture<>();
        then(executor, () -> {
            U made;
            try {
                made = fn.apply(this.value, this.failure);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown));
                return;
            }
            next.settle(made, null);
        });
        return next;
    }

    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return handling(fn, null);
    }

    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return handling(fn, shared());
    }

    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        return handling(fn, given(executor));
    }

    private CompletableFuture<T> completing(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        given(action);
        CompletableFuture<T> next = new CompletableFuture<>();
        then(executor, () -> {
            try {
                action.accept(this.value, this.failure);
            }
            catch (Throwable thrown) {
                // an exception of the action's is the stage's only when the future has none
                next.settle(null, held(this.failure != null ? this.failure : thrown));
                return;
            }
            if (this.failure != null) {
                next.settle(null, held(this.failure));
            }
            else {
                next.settle(this.value, null);
            }
        });
        return next;
    }

    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return completing(action, null);
    }

    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return completing(action, shared());
    }

    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
            Executor executor) {
        return completing(action, given(executor));
    }

    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        given(fn);
        CompletableFuture<T> next = new CompletableFuture<>();
        then(null, () -> {
            if (this.failure == null) {
                next.settle(this.value, null);
                return;
            }
            T made;
            try {
                made = fn.apply(this.failure);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown));
                return;
            }
            next.settle(made, null);
        });
        return next;
    }
}
