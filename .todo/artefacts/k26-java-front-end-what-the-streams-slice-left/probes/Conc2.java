import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class Conc2 {
    public static void main(String[] args) throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Thread.currentThread().interrupt();
        try {
            System.out.println(Stream.of(1, 2, 3).gather(Gatherers.mapConcurrent(1, x -> { log.add("run" + x + Thread.currentThread().isInterrupted()); return x; })).toList());
        }
        catch (Throwable e) {
            System.out.println("thrown " + e + " <- " + e.getCause());
        }
        System.out.println("flag " + Thread.interrupted() + " " + log);
        // a checked-like failure through a sneaky throw is not possible; an Exception subclass through a mapper needs unchecked
        try {
            Stream.of(1).gather(Gatherers.mapConcurrent(1, x -> { throw new ArithmeticException("a"); })).toList();
        }
        catch (Throwable e) {
            System.out.println("thrown " + e + " <- " + e.getCause());
        }
        // order of starts against results with a slow head
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> r = Stream.of(1, 2, 3, 4).gather(Gatherers.mapConcurrent(4, x -> { if (x == 1) { try { go.await(); } catch (InterruptedException e) { } } else if (x == 4) go.countDown(); return x * 10; })).toList();
        System.out.println(r);
    }
}
