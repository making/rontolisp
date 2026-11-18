public class Gate {
    static boolean open;
    static final Object gate = new Object();
    static int passed;
    public static void main(String[] args) throws InterruptedException {
        Thread[] threads = new Thread[3];
        for (int i = 0; i < 3; i++) {
            threads[i] = new Thread(() -> {
                synchronized (gate) {
                    try {
                        while (!open) {
                            gate.wait();
                        }
                    }
                    catch (InterruptedException e) {
                        return;
                    }
                    passed++;
                }
            });
            threads[i].start();
        }
        Thread.sleep(20);
        synchronized (gate) {
            System.out.println("passed before " + passed);
            open = true;
            gate.notifyAll();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("passed after " + passed);
    }
}
