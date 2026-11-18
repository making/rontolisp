import java.util.concurrent.*;
public class Msgs {
    static void attempt(Callable<Object> r) { try { System.out.println(r.call()); } catch (Throwable e) { System.out.println(e); } }
    public static void main(String[] args) {
        attempt(() -> new ThreadPoolExecutor(-1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        attempt(() -> new ThreadPoolExecutor(0, 0, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        attempt(() -> new ThreadPoolExecutor(2, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        attempt(() -> new ThreadPoolExecutor(1, 1, -1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()));
        attempt(() -> new ThreadPoolExecutor(-1, 0, -1, TimeUnit.SECONDS, null));
        attempt(() -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, null));
        attempt(() -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), (ThreadFactory) null));
        attempt(() -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), (RejectedExecutionHandler) null));
        attempt(() -> new ThreadPoolExecutor(1, 1, 0, null, new LinkedBlockingQueue<>()));
        attempt(() -> new ScheduledThreadPoolExecutor(-1));
        attempt(() -> new ScheduledThreadPoolExecutor(1, (ThreadFactory) null));
        attempt(() -> Executors.newFixedThreadPool(0));
        attempt(() -> new ForkJoinPool(0));
        attempt(() -> { new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()).setRejectedExecutionHandler(null); return 1; });
        attempt(() -> { new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()).execute(null); return 1; });
        attempt(() -> new CyclicBarrier(0));
        attempt(() -> new Semaphore(1).tryAcquire(-1));
        attempt(() -> Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> { }, 0, 0, TimeUnit.SECONDS));
        attempt(() -> new LinkedBlockingDeque<>(0));
        System.exit(0);
    }
}
