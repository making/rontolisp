import java.util.concurrent.*;

public class Exec {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.execute(done::countDown);
        }
        done.await();
        pool.shutdown();
        System.out.println(pool.awaitTermination(1, TimeUnit.SECONDS));
    }
}
