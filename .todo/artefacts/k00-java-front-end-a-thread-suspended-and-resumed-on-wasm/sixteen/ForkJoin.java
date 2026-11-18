public class ForkJoin {
    static int[] results = new int[4];
    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final int n = i;
            threads[i] = new Thread(() -> {
                int sum = 0;
                for (int k = 0; k <= n * 100; k++) {
                    sum += k;
                }
                results[n] = sum;
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        for (int r : results) {
            System.out.println(r);
        }
    }
}
