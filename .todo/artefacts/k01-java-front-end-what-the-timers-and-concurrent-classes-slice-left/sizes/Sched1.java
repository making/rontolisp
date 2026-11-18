import java.util.concurrent.*;

public class Sched1 {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
        System.out.println(pool.schedule(() -> 6 * 7, 5, TimeUnit.MILLISECONDS).get());
        pool.shutdown();
    }
}
