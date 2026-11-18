// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

public final class Executors {

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
        return newFixedThreadPool(1, defaultThreadFactory());
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
        return newFixedThreadPool(1, threadFactory);
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
        return new ScheduledThreadPoolExecutor(1);
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory threadFactory) {
        return new ScheduledThreadPoolExecutor(1, threadFactory);
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
