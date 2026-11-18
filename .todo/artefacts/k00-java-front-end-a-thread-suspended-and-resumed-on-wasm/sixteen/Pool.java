import java.util.ArrayDeque;

public class Pool {
    static final ArrayDeque<Integer> tasks = new ArrayDeque<>();
    static int sum;
    static boolean closed;
    public static void main(String[] args) throws InterruptedException {
        Thread[] workers = new Thread[3];
        for (int i = 0; i < 3; i++) {
            workers[i] = new Thread(() -> {
                while (true) {
                    int task;
                    synchronized (tasks) {
                        while (tasks.isEmpty() && !closed) {
                            try {
                                tasks.wait();
                            }
                            catch (InterruptedException e) {
                                return;
                            }
                        }
                        if (tasks.isEmpty()) {
                            return;
                        }
                        task = tasks.poll();
                    }
                    int square = task * task;
                    synchronized (tasks) {
                        sum += square;
                    }
                }
            });
            workers[i].start();
        }
        for (int i = 1; i <= 20; i++) {
            synchronized (tasks) {
                tasks.add(i);
                tasks.notify();
            }
        }
        synchronized (tasks) {
            closed = true;
            tasks.notifyAll();
        }
        for (Thread w : workers) {
            w.join();
        }
        System.out.println(sum);
    }
}
