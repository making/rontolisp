import java.util.*;
import java.util.concurrent.*;

public class Transfer2 {
    public static void main(String[] args) throws Exception {
        LinkedTransferQueue<String> q = new LinkedTransferQueue<>();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Thread producer = new Thread(() -> {
            try {
                q.transfer("x");
                log.add("returned");
            }
            catch (InterruptedException e) {
                log.add("interrupted");
            }
        });
        producer.start();
        while (q.isEmpty()) Thread.sleep(1);
        System.out.println(q.remove("x") + " " + q);
        producer.join(500);
        System.out.println(producer.isAlive() + " " + log);
        producer.interrupt();
        producer.join();
        Thread second = new Thread(() -> {
            try {
                q.transfer("y");
                log.add("returned");
            }
            catch (InterruptedException e) {
                log.add("interrupted");
            }
        });
        second.start();
        while (q.isEmpty()) Thread.sleep(1);
        q.clear();
        second.join(500);
        System.out.println(second.isAlive() + " " + log + " " + q);
        second.interrupt();
        second.join();
        Thread third = new Thread(() -> {
            try {
                q.transfer("z");
                log.add("returned");
            }
            catch (InterruptedException e) {
                log.add("interrupted");
            }
        });
        third.start();
        while (q.isEmpty()) Thread.sleep(1);
        List<String> into = new ArrayList<>();
        q.drainTo(into);
        third.join(500);
        System.out.println(third.isAlive() + " " + log + " " + into);
        third.interrupt();
    }
}
