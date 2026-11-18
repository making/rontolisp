// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
//
// The pool of the tasks that split (ForkJoinTask, which says who runs one). Its
// threads are a ThreadPoolExecutor's: daemons, one more whenever none is free for a
// task -- so its parallelism is a number it answers, not a bound. The one every program
// shares runs on the threads a CompletableFuture's tasks run on (Common).
package java.util.concurrent;

import java.util.List;

public class ForkJoinPool implements ExecutorService {

    /** How many pools were made: the number the next one's threads carry. */
    private static int pools;

    private static ForkJoinPool common;

    private final ThreadPoolExecutor workers;

    private final int parallelism;

    private final boolean shared;

    public ForkJoinPool() {
        this(2);
    }

    public ForkJoinPool(int parallelism) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException();
        }
        this.parallelism = parallelism;
        this.shared = false;
        this.workers = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new Workers("ForkJoinPool-" + number() + "-worker-"));
    }

    private ForkJoinPool(ThreadPoolExecutor workers) {
        this.parallelism = 1;
        this.shared = true;
        this.workers = workers;
    }

    private static synchronized int number() {
        return ++pools;
    }

    private static final class Workers implements ThreadFactory {

        private final String prefix;

        private int made;

        Workers(String prefix) {
            this.prefix = prefix;
        }

        public synchronized Thread newThread(Runnable r) {
            Thread worker = new Thread(r, this.prefix + ++this.made);
            worker.setDaemon(true);
            return worker;
        }
    }

    public static synchronized ForkJoinPool commonPool() {
        if (common == null) {
            common = new ForkJoinPool(Common.pool());
        }
        return common;
    }

    public static int getCommonPoolParallelism() {
        return commonPool().parallelism;
    }

    public int getParallelism() {
        return this.parallelism;
    }

    public int getPoolSize() {
        return this.workers.getPoolSize();
    }

    /** Hands a task to the pool's threads: the first of them free runs it, unless a thread joining it has. */
    void push(ForkJoinTask<?> task) {
        if (task == null) {
            throw new NullPointerException();
        }
        if (this.workers.isShutdown()) {
            throw new RejectedExecutionException();
        }
        this.workers.execute(() -> {
            task.run(this);
        });
    }

    public <T> T invoke(ForkJoinTask<T> task) {
        push(task);
        task.await();
        return task.report();
    }

    public void execute(ForkJoinTask<?> task) {
        push(task);
    }

    public void execute(Runnable task) {
        push(ForkJoinTask.adapt(task));
    }

    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        push(task);
        return task;
    }

    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        return submit(ForkJoinTask.adapt(task));
    }

    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        return submit(ForkJoinTask.adapt(task, result));
    }

    @SuppressWarnings("unchecked")
    public ForkJoinTask<?> submit(Runnable task) {
        return submit((ForkJoinTask<Object>) ForkJoinTask.adapt(task));
    }

    /** No more tasks; the pool every program shares takes them for as long as the program runs. */
    public void shutdown() {
        if (!this.shared) {
            this.workers.shutdown();
        }
    }

    public List<Runnable> shutdownNow() {
        return this.shared ? new java.util.ArrayList<>() : this.workers.shutdownNow();
    }

    public boolean isShutdown() {
        return this.workers.isShutdown();
    }

    public boolean isTerminated() {
        return this.workers.isTerminated();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return this.workers.awaitTermination(timeout, unit);
    }

    public void close() {
        if (!this.shared) {
            ExecutorService.super.close();
        }
    }
}
