import java.util.concurrent.*;

public class Core0 {
    public static void main(String[] args) throws Exception {
        ThreadPoolExecutor none = new ThreadPoolExecutor(0, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        CountDownLatch ran = new CountDownLatch(2);
        none.execute(ran::countDown);
        none.execute(ran::countDown);
        System.out.println(ran.await(2, TimeUnit.SECONDS));
        ThreadPoolExecutor thrower = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r); t.setUncaughtExceptionHandler((th, e) -> { }); return t; });
        thrower.execute(() -> { throw new IllegalStateException("x"); });
        Thread.sleep(100);
        System.out.println(thrower.getCompletedTaskCount());
        System.exit(0);
    }
}
