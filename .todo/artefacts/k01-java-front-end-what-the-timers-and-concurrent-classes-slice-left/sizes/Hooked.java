import java.util.concurrent.*;

public class Hooked {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            protected void afterExecute(Runnable r, Throwable t) {
            }
        };
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.execute(done::countDown);
        }
        done.await();
        pool.shutdown();
        System.out.println(pool.awaitTermination(1, TimeUnit.SECONDS));
    }
}
