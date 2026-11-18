public class Plain {
    public static void main(String[] args) throws InterruptedException {
        long before = System.nanoTime();
        Thread.sleep(20);
        System.out.println("slept " + (System.nanoTime() - before >= 20_000_000L));
        Object lock = new Object();
        synchronized (lock) {
            lock.wait(10);
            System.out.println("waited");
        }
        Thread.currentThread().interrupt();
        try {
            Thread.sleep(10);
        }
        catch (InterruptedException e) {
            System.out.println("interrupted");
        }
        System.out.println(Thread.currentThread().getName());
    }
}
