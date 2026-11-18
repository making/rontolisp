import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class Transfer {
    static String tried(Supplier<Object> s) {
        try {
            return String.valueOf(s.get());
        }
        catch (Throwable t) {
            return "threw " + t;
        }
    }

    public static void main(String[] args) throws Exception {
        LinkedTransferQueue<String> q = new LinkedTransferQueue<>();
        TransferQueue<String> tq = q;
        BlockingQueue<String> bq = q;
        System.out.println(q + " " + q.size() + " " + q.isEmpty() + " " + q.remainingCapacity() + " " + q.peek() + " " + q.poll() + " " + q.hasWaitingConsumer() + " " + q.getWaitingConsumerCount());
        System.out.println(q.offer("a") + " " + q.add("b") + " " + q.offer("c", 1, TimeUnit.SECONDS) + " " + q.tryTransfer("nobody") + " " + q.tryTransfer("nobody", 10, TimeUnit.MILLISECONDS) + " " + q);
        q.put("d");
        System.out.println(q.peek() + " " + q.element() + " " + q.size() + " " + q.contains("c") + " " + q.remove("c") + " " + q.remove("zz") + " " + q + " " + q.take() + " " + q.poll() + " " + q.remove() + " " + q.poll(1, TimeUnit.MILLISECONDS) + " " + tried(q::remove) + " " + tried(q::element));
        System.out.println(tried(() -> q.offer(null)) + " " + tried(() -> q.tryTransfer(null)) + " " + tried(() -> { try { q.transfer(null); } catch (InterruptedException e) { } return "ok"; }) + " " + tried(() -> q.add(null)));
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        // a consumer waits: tryTransfer hands over, the counts tell
        Thread consumer = new Thread(() -> {
            try {
                log.add("took " + q.take());
                log.add("took " + q.poll(5, TimeUnit.SECONDS));
            }
            catch (InterruptedException e) {
                log.add("interrupted");
            }
        });
        consumer.start();
        while (!q.hasWaitingConsumer()) Thread.sleep(1);
        System.out.println(q.getWaitingConsumerCount() + " " + q.size() + " " + q.isEmpty() + " " + q.tryTransfer("handed") + " " + q);
        while (!q.hasWaitingConsumer()) Thread.sleep(1);
        System.out.println(q.offer("offered") + " " + q.size());
        consumer.join();
        System.out.println(log + " " + q.hasWaitingConsumer());
        // transfer waits for a consumer: elements ahead of it are taken first
        log.clear();
        q.add("ahead");
        Thread producer = new Thread(() -> {
            try {
                q.transfer("transferred");
                log.add("transfer returned");
            }
            catch (InterruptedException e) {
                log.add("interrupted");
            }
        });
        producer.start();
        while (q.size() < 2) Thread.sleep(1);
        Thread.sleep(20);
        System.out.println(producer.getState() + " " + q + " " + q.size() + " " + q.peek() + " " + q.contains("transferred") + " " + q.getWaitingConsumerCount());
        log.add("main takes " + q.take());
        Thread.sleep(20);
        log.add("still waiting " + producer.isAlive());
        log.add("main takes " + q.take());
        producer.join();
        System.out.println(log + " " + q);
        // a transfer that is interrupted or runs out of time takes its element back
        log.clear();
        Thread timed = new Thread(() -> {
            try {
                log.add("timed " + q.tryTransfer("timed", 20, TimeUnit.MILLISECONDS) + " " + q);
                q.transfer("lost");
            }
            catch (InterruptedException e) {
                log.add("interrupted " + q + " " + q.size());
            }
        });
        timed.start();
        while (q.size() < 1) Thread.sleep(1);
        System.out.println(q);
        while (!log.contains("timed false []")) Thread.sleep(1);
        while (q.size() < 1) Thread.sleep(1);
        timed.interrupt();
        timed.join();
        System.out.println(log);
        // the rest of a queue
        LinkedTransferQueue<Integer> n = new LinkedTransferQueue<>(List.of(3, 1, 2));
        List<Integer> into = new ArrayList<>();
        System.out.println(n + " " + n.drainTo(into, 2) + " " + into + " " + n.drainTo(into) + " " + into + " " + n + " " + tried(() -> n.drainTo(n)) + " " + tried(() -> n.drainTo(null)));
        n.addAll(List.of(5, 6, 7));
        Iterator<Integer> it = n.iterator();
        it.next();
        it.remove();
        System.out.println(n + " " + Arrays.toString(n.toArray()) + " " + n.removeIf(x -> x == 7) + " " + n);
        n.clear();
        System.out.println(n + " " + n.size());
    }
}
