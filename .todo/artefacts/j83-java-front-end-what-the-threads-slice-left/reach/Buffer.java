import java.util.ArrayDeque;

public class Buffer {
    static final ArrayDeque<Integer> items = new ArrayDeque<>();
    static final Object lock = new Object();

    static void put(int n) throws InterruptedException {
        synchronized (lock) {
            while (items.size() == 2) {
                lock.wait();
            }
            items.add(n);
            lock.notifyAll();
        }
    }

    static int take() throws InterruptedException {
        synchronized (lock) {
            while (items.isEmpty()) {
                lock.wait();
            }
            int n = items.poll();
            lock.notifyAll();
            return n;
        }
    }

    static int square(int n) {
        return n * n;
    }

    public static void main(String[] args) throws InterruptedException {
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    put(square(i));
                }
            }
            catch (InterruptedException e) {
                System.out.println(e);
            }
        });
        producer.start();
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += take();
        }
        producer.join();
        System.out.println(sum);
    }
}
