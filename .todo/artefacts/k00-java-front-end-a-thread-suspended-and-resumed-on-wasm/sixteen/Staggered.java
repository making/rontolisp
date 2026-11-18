public class Staggered {
    public static void main(String[] args) throws InterruptedException {
        int[] delays = { 90, 30, 60 };
        Thread[] threads = new Thread[3];
        for (int i = 0; i < 3; i++) {
            final int delay = delays[i];
            threads[i] = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                }
                catch (InterruptedException e) {
                    return;
                }
                System.out.println("slept " + delay);
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("end");
    }
}
