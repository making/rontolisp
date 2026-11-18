public class CounterTry {
    static int count;
    static synchronized void bump() { count++; }
    public static void main(String[] args) {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < 4; t++) {
            threads[t] = new Thread(() -> { for (int i = 0; i < 1000; i++) bump(); });
            threads[t].start();
        }
        try {
            for (Thread t : threads) t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println(count);
    }
}
