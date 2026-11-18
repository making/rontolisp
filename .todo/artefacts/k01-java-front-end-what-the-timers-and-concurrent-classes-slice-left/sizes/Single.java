import java.util.concurrent.*;

public class Single {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> answer = pool.submit(() -> 6 * 7);
        System.out.println(answer.get());
        pool.shutdown();
    }
}
