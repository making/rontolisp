// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// A task that ends when the tasks it waits for have told it so: each holds a count of
// what is still pending and the task to tell, its completer. The count is read and
// written under the task's monitor. Only the task at the top of a chain ends as a
// future ends; the ones below it are told through onCompletion.
package java.util.concurrent;

public abstract class CountedCompleter<T> extends ForkJoinTask<T> {

    private final CountedCompleter<?> completer;

    private int pending;

    protected CountedCompleter(CountedCompleter<?> completer, int initialPendingCount) {
        this.completer = completer;
        this.pending = initialPendingCount;
    }

    protected CountedCompleter(CountedCompleter<?> completer) {
        this(completer, 0);
    }

    protected CountedCompleter() {
        this(null, 0);
    }

    public abstract void compute();

    public void onCompletion(CountedCompleter<?> caller) {
    }

    public boolean onExceptionalCompletion(Throwable ex, CountedCompleter<?> caller) {
        return true;
    }

    public final CountedCompleter<?> getCompleter() {
        return this.completer;
    }

    public final synchronized int getPendingCount() {
        return this.pending;
    }

    public final synchronized void setPendingCount(int count) {
        this.pending = count;
    }

    public final synchronized void addToPendingCount(int delta) {
        this.pending += delta;
    }

    public final synchronized boolean compareAndSetPendingCount(int expected, int count) {
        if (this.pending != expected) {
            return false;
        }
        this.pending = count;
        return true;
    }

    /** One less pending unless none is: the count there was. */
    public final synchronized int decrementPendingCountUnlessZero() {
        int was = this.pending;
        if (was != 0) {
            this.pending = was - 1;
        }
        return was;
    }

    public final CountedCompleter<?> getRoot() {
        CountedCompleter<?> top = this;
        while (top.completer != null) {
            top = top.completer;
        }
        return top;
    }

    /** Up the chain: one less pending where something is, else the task is told and its completer is next. */
    public final void tryComplete() {
        CountedCompleter<?> task = this;
        CountedCompleter<?> from = this;
        while (task.decrementPendingCountUnlessZero() == 0) {
            task.onCompletion(from);
            if (task.completer == null) {
                task.quietlyComplete();
                return;
            }
            from = task;
            task = task.completer;
        }
    }

    /** As tryComplete, telling no task on the way. */
    public final void propagateCompletion() {
        CountedCompleter<?> task = this;
        while (task.decrementPendingCountUnlessZero() == 0) {
            if (task.completer == null) {
                task.quietlyComplete();
                return;
            }
            task = task.completer;
        }
    }

    /** Ends this task with a value whatever is pending, and tells its completer. */
    public void complete(T rawResult) {
        setRawResult(rawResult);
        onCompletion(this);
        quietlyComplete();
        if (this.completer != null) {
            this.completer.tryComplete();
        }
    }

    public final CountedCompleter<?> firstComplete() {
        return decrementPendingCountUnlessZero() == 0 ? this : null;
    }

    public final CountedCompleter<?> nextComplete() {
        if (this.completer != null) {
            return this.completer.firstComplete();
        }
        quietlyComplete();
        return null;
    }

    public final void quietlyCompleteRoot() {
        getRoot().quietlyComplete();
    }

    /** No thread takes another's tasks here, so there is none to help with. */
    public final void helpComplete(int maxTasks) {
    }

    /** An exception climbs the chain while each task it reaches says so. */
    void failed(Throwable ex) {
        CountedCompleter<?> task = this;
        CountedCompleter<?> from = this;
        while (task.onExceptionalCompletion(ex, from)) {
            from = task;
            task = task.completer;
            if (task == null || !task.fail(ex)) {
                return;
            }
        }
    }

    public T getRawResult() {
        return null;
    }

    protected void setRawResult(T value) {
    }

    protected final boolean exec() {
        compute();
        return false;
    }
}
