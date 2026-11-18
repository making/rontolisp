import java.util.ArrayList;
import java.util.List;

public class ProducerConsumer {
    static final List<Integer> queue = new ArrayList<>();
    static boolean done;
    public static void main(String[] args) throws InterruptedException {
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                synchronized (queue) {
                    queue.add(i * 10);
                    queue.notifyAll();
                }
            }
            synchronized (queue) {
                done = true;
                queue.notifyAll();
            }
        });
        Thread consumer = new Thread(() -> {
            int total = 0;
            try {
                while (true) {
                    int item;
                    synchronized (queue) {
                        while (queue.isEmpty() && !done) {
                            queue.wait();
                        }
                        if (queue.isEmpty()) {
                            break;
                        }
                        item = queue.remove(0);
                    }
                    total += item;
                }
            }
            catch (InterruptedException e) {
                System.out.println("interrupted");
            }
            System.out.println("total " + total);
        });
        consumer.start();
        producer.start();
        producer.join();
        consumer.join();
        System.out.println("done");
    }
}
