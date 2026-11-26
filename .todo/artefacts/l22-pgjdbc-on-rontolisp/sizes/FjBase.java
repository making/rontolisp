import java.util.concurrent.ForkJoinPool;
public class FjBase {
    public static void main(String[] args) throws Exception {
        System.out.println(ForkJoinPool.commonPool().submit(() -> 6 * 7).get());
        Thread t = Thread.currentThread();
        System.out.println(t.getContextClassLoader() != null);
    }
}
