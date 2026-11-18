// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A value or an exception set once, under the future's monitor, on which join() and
// get() wait; what depends on it runs where it is set, or at once when it was, in the
// order a JDK runs it (settle). An
// exception a stage's own function threw, and one a stage takes over from the stage
// before it, is held in a CompletionException; one given to completeExceptionally is
// held as it is. A task with no executor runs on a daemon thread of the pool every
// program shares (Common).
package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {

    /**
     * What runs once a future is done, handed the dependents still to run on the thread
     * that set it: null when it runs anywhere else.
     */
    private interface Dependent {

        void fire(ArrayDeque<Dependent> pending);
    }

    /** What ends the futures with a limit: one daemon thread, as a JDK's. */
    private static ScheduledThreadPoolExecutor delayer;

    private boolean done;

    private T value;

    private Throwable failure;

    /** What runs once the future is done, in the order it was added. */
    private ArrayList<Dependent> dependents;

    public CompletableFuture() {
    }

    private static Executor shared() {
        return Common.pool();
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

    private static synchronized ScheduledThreadPoolExecutor delayer() {
        if (delayer == null) {
            delayer = new ScheduledThreadPoolExecutor(1, (Runnable r) -> {
                Thread thread = new Thread(r, "CompletableFutureDelayScheduler");
                thread.setDaemon(true);
                return thread;
            });
            delayer.setRemoveOnCancelPolicy(true);
        }
        return delayer;
    }

    /**
     * Sets the value or the exception, once. What depends on the future runs on the
     * thread setting it, in the order a JDK runs it (measured): the dependents of the
     * future set first, the one added last first; those of a future one of them sets,
     * right after it, the one added first first.
     */
    private boolean settle(T value, Throwable failure, ArrayDeque<Dependent> pending) {
        ArrayList<Dependent> ready;
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
        if (ready == null) {
            return true;
        }
        if (pending != null) {
            for (int i = ready.size() - 1; i >= 0; i--) {
                pending.push(ready.get(i));
            }
            return true;
        }
        ArrayDeque<Dependent> stack = new ArrayDeque<>();
        for (Dependent dependent : ready) {
            stack.push(dependent);
        }
        Dependent next;
        while ((next = stack.poll()) != null) {
            next.fire(stack);
        }
        return true;
    }

    /** An action as it runs where its future is set: there, or handed to an executor. */
    private static Dependent on(Executor executor, Dependent action) {
        return executor == null ? action : (ArrayDeque<Dependent> pending) -> executor.execute(() -> action.fire(null));
    }

    /** Runs an action once the future is done: now when it is. */
    private void then(Executor executor, Dependent action) {
        then(executor, action, null);
    }

    /** As then, for a dependent added while others run: the ones still to run. */
    private void then(Executor executor, Dependent action, ArrayDeque<Dependent> pending) {
        Dependent dependent = on(executor, action);
        synchronized (this) {
            if (!this.done) {
                if (this.dependents == null) {
                    this.dependents = new ArrayList<>();
                }
                this.dependents.add(dependent);
                return;
            }
        }
        dependent.fire(pending);
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
                made.settle(null, held(thrown), null);
                return;
            }
            made.settle(answer, null, null);
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
        made.settle(value, null, null);
        return made;
    }

    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        CompletableFuture<U> made = new CompletableFuture<>();
        made.settle(null, given(ex), null);
        return made;
    }

    /** Done when every one is: failed as the first of them, in the order given, that failed. */
    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        CompletableFuture<Void> all = new CompletableFuture<>();
        int[] left = { cfs.length };
        if (cfs.length == 0) {
            all.settle(null, null, null);
        }
        for (CompletableFuture<?> each : cfs) {
            given(each).then(null, (ArrayDeque<Dependent> pending) -> {
                boolean last;
                synchronized (left) {
                    last = --left[0] == 0;
                }
                if (last) {
                    for (CompletableFuture<?> one : cfs) {
                        if (one.failure != null) {
                            all.settle(null, held(one.failure), pending);
                            return;
                        }
                    }
                    all.settle(null, null, pending);
                }
            });
        }
        return all;
    }

    /** Done as the first of them to be done. */
    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
        CompletableFuture<Object> any = new CompletableFuture<>();
        for (CompletableFuture<?> each : cfs) {
            given(each).then(null, (ArrayDeque<Dependent> pending) -> {
                if (each.failure != null) {
                    any.settle(null, held(each.failure), pending);
                }
                else {
                    any.settle(each.value, null, pending);
                }
            });
        }
        return any;
    }

    public boolean complete(T value) {
        return settle(value, null, null);
    }

    public boolean completeExceptionally(Throwable ex) {
        return settle(null, given(ex), null);
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        settle(null, new CancellationException(), null);
        return isCancelled();
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        return completeAsync(supplier, shared());
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        given(supplier);
        given(executor).execute(() -> {
            T answer;
            try {
                answer = supplier.get();
            }
            catch (Throwable thrown) {
                settle(null, held(thrown), null);
                return;
            }
            settle(answer, null, null);
        });
        return this;
    }

    /** Failed with a TimeoutException when nothing set it within the time. */
    public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        given(unit);
        if (!isDone()) {
            limit(delayer().schedule(() -> {
                settle(null, new TimeoutException(), null);
            }, timeout, unit));
        }
        return this;
    }

    public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        given(unit);
        if (!isDone()) {
            limit(delayer().schedule(() -> {
                settle(value, null, null);
            }, timeout, unit));
        }
        return this;
    }

    /** What was to end the future is taken back once the future is done. */
    private void limit(ScheduledFuture<?> ending) {
        then(null, (ArrayDeque<Dependent> pending) -> {
            ending.cancel(false);
        });
    }

    /** An executor handing what it is given to another once the time has passed. */
    public static Executor delayedExecutor(long delay, TimeUnit unit, Executor executor) {
        given(unit);
        given(executor);
        return (Runnable task) -> {
            delayer().schedule(() -> executor.execute(task), delay, unit);
        };
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit) {
        return delayedExecutor(delay, unit, shared());
    }

    public synchronized int getNumberOfDependents() {
        return this.dependents == null ? 0 : this.dependents.size();
    }

    public CompletableFuture<T> copy() {
        return applying((T taken) -> taken, null);
    }

    public synchronized State state() {
        return !this.done ? State.RUNNING : this.failure == null ? State.SUCCESS
                : this.failure instanceof CancellationException ? State.CANCELLED : State.FAILED;
    }

    public synchronized T resultNow() {
        if (!this.done || this.failure != null) {
            throw new IllegalStateException();
        }
        return this.value;
    }

    public synchronized Throwable exceptionNow() {
        if (this.failure == null || this.failure instanceof CancellationException) {
            throw new IllegalStateException();
        }
        return this.failure instanceof CompletionException && this.failure.getCause() != null
                ? this.failure.getCause() : this.failure;
    }

    public synchronized String toString() {
        String state;
        if (!this.done) {
            int waiting = this.dependents == null ? 0 : this.dependents.size();
            state = waiting == 0 ? "[Not completed]" : "[Not completed, " + waiting + " dependents]";
        }
        else {
            state = this.failure == null ? "[Completed normally]"
                    : "[Completed exceptionally: " + this.failure.getClass().getName() + "]";
        }
        return super.toString() + state;
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
        then(executor, (ArrayDeque<Dependent> pending) -> {
            if (this.failure != null) {
                next.settle(null, held(this.failure), pending);
                return;
            }
            U made;
            try {
                made = fn.apply(this.value);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown), pending);
                return;
            }
            next.settle(made, null, pending);
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
        then(executor, (ArrayDeque<Dependent> pending) -> {
            if (this.failure != null) {
                next.settle(null, held(this.failure), pending);
                return;
            }
            CompletableFuture<U> inner;
            try {
                inner = fn.apply(this.value).toCompletableFuture();
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown), pending);
                return;
            }
            inner.then(null, (ArrayDeque<Dependent> later) -> {
                if (inner.failure != null) {
                    next.settle(null, held(inner.failure), later);
                }
                else {
                    next.settle(inner.value, null, later);
                }
            }, pending);
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

    /**
     * What runs once two futures are done: added to both where it is asked for, run by
     * the one done last.
     */
    private <U, V> CompletableFuture<V> combining(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
        given(fn);
        CompletableFuture<? extends U> second = given(other).toCompletableFuture();
        CompletableFuture<V> next = new CompletableFuture<>();
        boolean[] ran = new boolean[1];
        Dependent run = on(executor, (ArrayDeque<Dependent> pending) -> {
            if (this.failure != null) {
                next.settle(null, held(this.failure), pending);
                return;
            }
            if (second.failure != null) {
                next.settle(null, held(second.failure), pending);
                return;
            }
            V made;
            try {
                made = fn.apply(this.value, second.value);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown), pending);
                return;
            }
            next.settle(made, null, pending);
        });
        Dependent both = (ArrayDeque<Dependent> pending) -> {
            if (!isDone() || !second.isDone()) {
                return;
            }
            synchronized (ran) {
                if (ran[0]) {
                    return;
                }
                ran[0] = true;
            }
            run.fire(pending);
        };
        then(null, both);
        second.then(null, both);
        return next;
    }

    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return combining(other, fn, null);
    }

    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return combining(other, fn, shared());
    }

    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
        return combining(other, fn, given(executor));
    }

    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return acceptingBoth(other, action, null);
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return acceptingBoth(other, action, shared());
    }

    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action, Executor executor) {
        return acceptingBoth(other, action, given(executor));
    }

    private <U> CompletableFuture<Void> acceptingBoth(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action, Executor executor) {
        given(action);
        return this.<U, Void>combining(other, (T first, U second) -> {
            action.accept(first, second);
            return null;
        }, executor);
    }

    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return runningAfterBoth(other, action, null);
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return runningAfterBoth(other, action, shared());
    }

    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return runningAfterBoth(other, action, given(executor));
    }

    private CompletableFuture<Void> runningAfterBoth(CompletionStage<?> other, Runnable action, Executor executor) {
        given(action);
        return this.<Object, Void>combining(other, (T first, Object second) -> {
            action.run();
            return null;
        }, executor);
    }

    /**
     * What runs once one of two futures is done: added to both, run by the one done
     * first, on what that one holds.
     */
    private <U> CompletableFuture<U> either(CompletionStage<? extends T> other, Function<? super T, U> fn,
            Executor executor) {
        given(fn);
        CompletableFuture<? extends T> second = given(other).toCompletableFuture();
        CompletableFuture<U> next = new CompletableFuture<>();
        boolean[] ran = new boolean[1];
        then(null, first(this, ran, fn, next, executor));
        second.then(null, first(second, ran, fn, next, executor));
        return next;
    }

    private static <S, U> Dependent first(CompletableFuture<? extends S> source, boolean[] ran,
            Function<? super S, U> fn, CompletableFuture<U> next, Executor executor) {
        Dependent run = on(executor, (ArrayDeque<Dependent> pending) -> {
            if (source.failure != null) {
                next.settle(null, held(source.failure), pending);
                return;
            }
            U made;
            try {
                made = fn.apply(source.value);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown), pending);
                return;
            }
            next.settle(made, null, pending);
        });
        return (ArrayDeque<Dependent> pending) -> {
            synchronized (ran) {
                if (ran[0]) {
                    return;
                }
                ran[0] = true;
            }
            run.fire(pending);
        };
    }

    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return either(other, fn, null);
    }

    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
            Function<? super T, U> fn) {
        return either(other, fn, shared());
    }

    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
            Function<? super T, U> fn, Executor executor) {
        return either(other, fn, given(executor));
    }

    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptingEither(other, action, null);
    }

    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
            Consumer<? super T> action) {
        return acceptingEither(other, action, shared());
    }

    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
            Consumer<? super T> action, Executor executor) {
        return acceptingEither(other, action, given(executor));
    }

    private CompletableFuture<Void> acceptingEither(CompletionStage<? extends T> other,
            Consumer<? super T> action, Executor executor) {
        given(action);
        Function<T, Void> accepting = (T taken) -> {
            action.accept(taken);
            return null;
        };
        return either(other, accepting, executor);
    }

    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return runningAfterEither(other, action, null);
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return runningAfterEither(other, action, shared());
    }

    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action,
            Executor executor) {
        return runningAfterEither(other, action, given(executor));
    }

    private CompletableFuture<Void> runningAfterEither(CompletionStage<?> other, Runnable action,
            Executor executor) {
        given(action);
        CompletableFuture<?> second = given(other).toCompletableFuture();
        CompletableFuture<Void> next = new CompletableFuture<>();
        boolean[] ran = new boolean[1];
        Function<Object, Void> running = (Object taken) -> {
            action.run();
            return null;
        };
        then(null, first(this, ran, running, next, executor));
        second.then(null, first(second, ran, running, next, executor));
        return next;
    }

    private <U> CompletableFuture<U> handling(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        given(fn);
        CompletableFuture<U> next = new CompletableFuture<>();
        then(executor, (ArrayDeque<Dependent> pending) -> {
            U made;
            try {
                made = fn.apply(this.value, this.failure);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown), pending);
                return;
            }
            next.settle(made, null, pending);
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
        then(executor, (ArrayDeque<Dependent> pending) -> {
            try {
                action.accept(this.value, this.failure);
            }
            catch (Throwable thrown) {
                // an exception of the action's is the stage's only when the future has none
                next.settle(null, held(this.failure != null ? this.failure : thrown), pending);
                return;
            }
            if (this.failure != null) {
                next.settle(null, held(this.failure), pending);
            }
            else {
                next.settle(this.value, null, pending);
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
        then(null, (ArrayDeque<Dependent> pending) -> {
            if (this.failure == null) {
                next.settle(this.value, null, pending);
                return;
            }
            T made;
            try {
                made = fn.apply(this.failure);
            }
            catch (Throwable thrown) {
                next.settle(null, held(thrown), pending);
                return;
            }
            next.settle(made, null, pending);
        });
        return next;
    }
}
