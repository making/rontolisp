// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.concurrent;

// The pool every program shares: what runs a CompletableFuture's task with no executor,
// and what ForkJoinPool.commonPool() runs its tasks on. Its threads are daemons named
// as a JDK's common pool's, one more whenever none is free.
final class Common {

    private static ThreadPoolExecutor pool;

    private Common() {
    }

    private static final class Workers implements ThreadFactory {

        private int made;

        public synchronized Thread newThread(Runnable r) {
            Thread worker = new Thread(r, "ForkJoinPool.commonPool-worker-" + ++this.made);
            worker.setDaemon(true);
            return worker;
        }
    }

    static synchronized ThreadPoolExecutor pool() {
        if (pool == null) {
            pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), new Workers());
        }
        return pool;
    }
}
