public class Counter {
    static int count;
    static synchronized void bump() { count++; }
    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < 4; t++) {
            threads[t] = new Thread(() -> { for (int i = 0; i < 1000; i++) bump(); });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        System.out.println(count);
    }
}
