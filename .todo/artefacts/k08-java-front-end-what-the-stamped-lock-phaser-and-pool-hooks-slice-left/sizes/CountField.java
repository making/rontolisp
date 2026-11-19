import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

// Count with the counter a volatile field under a field updater.
public class CountField {
    volatile int count;

    static final AtomicIntegerFieldUpdater<CountField> COUNT = AtomicIntegerFieldUpdater.newUpdater(CountField.class,
            "count");

    public static void main(String[] args) throws InterruptedException {
        CountField counted = new CountField();
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    COUNT.incrementAndGet(counted);
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        System.out.println(counted.count + " " + COUNT.get(counted));
    }
}
