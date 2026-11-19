// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

import java.util.Collection;
import java.util.List;

public final class Executors {

    /**
     * A pool behind the members of an ExecutorService alone: what the pools of one
     * thread are, so no program sets the size of one, and what
     * unconfigurableExecutorService answers.
     */
    private static class DelegatedExecutorService implements ExecutorService {

        private final ExecutorService pool;

        DelegatedExecutorService(ExecutorService pool) {
            if (pool == null) {
                throw new NullPointerException();
            }
            this.pool = pool;
        }

        public void execute(Runnable command) {
            this.pool.execute(command);
        }

        public void shutdown() {
            this.pool.shutdown();
        }

        public List<Runnable> shutdownNow() {
            return this.pool.shutdownNow();
        }

        public boolean isShutdown() {
            return this.pool.isShutdown();
        }

        public boolean isTerminated() {
            return this.pool.isTerminated();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return this.pool.awaitTermination(timeout, unit);
        }

        public <T> Future<T> submit(Callable<T> task) {
            return this.pool.submit(task);
        }

        public <T> Future<T> submit(Runnable task, T result) {
            return this.pool.submit(task, result);
        }

        public Future<?> submit(Runnable task) {
            return this.pool.submit(task);
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return this.pool.invokeAll(tasks);
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            return this.pool.invokeAll(tasks, timeout, unit);
        }

        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException,
                ExecutionException {
            return this.pool.invokeAny(tasks);
        }

        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return this.pool.invokeAny(tasks, timeout, unit);
        }
    }

    /** What newSingleThreadExecutor answers. */
    private static final class AutoShutdownDelegatedExecutorService extends DelegatedExecutorService {

        AutoShutdownDelegatedExecutorService(ExecutorService pool) {
            super(pool);
        }
    }

    private static final class DelegatedScheduledExecutorService extends DelegatedExecutorService
            implements ScheduledExecutorService {

        private final ScheduledExecutorService scheduled;

        DelegatedScheduledExecutorService(ScheduledExecutorService pool) {
            super(pool);
            this.scheduled = pool;
        }

        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return this.scheduled.schedule(command, delay, unit);
        }

        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return this.scheduled.schedule(callable, delay, unit);
        }

        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                TimeUnit unit) {
            return this.scheduled.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                TimeUnit unit) {
            return this.scheduled.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
    }

    /** How many default factories were made: the number the next one's threads carry. */
    private static int pools;

    private Executors() {
    }

    private static synchronized int nextPool() {
        return ++pools;
    }

    /** Threads named pool-N-thread-M: N the factory's number, M the thread's. */
    private static final class Numbering implements ThreadFactory {

        private final String prefix = "pool-" + nextPool() + "-thread-";

        private int threads;

        public synchronized Thread newThread(Runnable r) {
            Thread made = new Thread(r, this.prefix + ++this.threads);
            made.setDaemon(false);
            made.setPriority(Thread.NORM_PRIORITY);
            return made;
        }
    }

    public static ThreadFactory defaultThreadFactory() {
        return new Numbering();
    }

    public static ExecutorService newFixedThreadPool(int nThreads) {
        return newFixedThreadPool(nThreads, defaultThreadFactory());
    }

    public static ExecutorService newFixedThreadPool(int nThreads, ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), threadFactory);
    }

    public static ExecutorService newSingleThreadExecutor() {
        return newSingleThreadExecutor(defaultThreadFactory());
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
        return new AutoShutdownDelegatedExecutorService(newFixedThreadPool(1, threadFactory));
    }

    public static ExecutorService unconfigurableExecutorService(ExecutorService executor) {
        return new DelegatedExecutorService(executor);
    }

    public static ScheduledExecutorService unconfigurableScheduledExecutorService(ScheduledExecutorService executor) {
        return new DelegatedScheduledExecutorService(executor);
    }

    public static ExecutorService newCachedThreadPool() {
        return newCachedThreadPool(defaultThreadFactory());
    }

    public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                threadFactory);
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        return new ScheduledThreadPoolExecutor(corePoolSize);
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, ThreadFactory threadFactory) {
        return new ScheduledThreadPoolExecutor(corePoolSize, threadFactory);
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return new DelegatedScheduledExecutorService(new ScheduledThreadPoolExecutor(1));
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory threadFactory) {
        return new DelegatedScheduledExecutorService(new ScheduledThreadPoolExecutor(1, threadFactory));
    }

    public static ExecutorService newThreadPerTaskExecutor(ThreadFactory threadFactory) {
        return new ThreadPerTaskExecutor(threadFactory);
    }

    public static ExecutorService newVirtualThreadPerTaskExecutor() {
        return new ThreadPerTaskExecutor((Runnable r) -> Thread.ofVirtual().unstarted(r));
    }

    public static <T> Callable<T> callable(Runnable task, T result) {
        if (task == null) {
            throw new NullPointerException();
        }
        return () -> {
            task.run();
            return result;
        };
    }

    public static Callable<Object> callable(Runnable task) {
        return callable(task, null);
    }
}
