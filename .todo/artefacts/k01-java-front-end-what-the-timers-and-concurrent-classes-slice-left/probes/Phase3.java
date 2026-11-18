import java.util.concurrent.*;

public class Phase3 {
    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 3; round++) {
            Phaser three = new Phaser(1) {
                protected boolean onAdvance(int phase, int registeredParties) {
                    return phase >= 2;
                }
            };
            Thread[] workers = new Thread[3];
            for (int i = 0; i < workers.length; i++) {
                three.register();
                workers[i] = new Thread(() -> {
                    while (three.arriveAndAwaitAdvance() >= 0) {
                        Thread.yield();
                    }
                });
                workers[i].start();
            }
            while (three.getArrivedParties() < 3) {
                Thread.sleep(1);
            }
            int first = three.arriveAndAwaitAdvance();
            int second = three.arriveAndAwaitAdvance();
            int last = three.arriveAndAwaitAdvance();
            for (Thread worker : workers) {
                worker.join();
            }
            System.out.println(first + " " + second + " " + last + " " + (last - Integer.MIN_VALUE) + " " + (Integer.MIN_VALUE + 3) + " " + three.getPhase());
        }
        int x = -2147483645;
        System.out.println(x - Integer.MIN_VALUE);
    }
}
