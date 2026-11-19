import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Conc {
    static List<String> log = Collections.synchronizedList(new ArrayList<>());

    static void show(String what, Object value) {
        List<String> sorted = new ArrayList<>(log);
        Collections.sort(sorted);
        System.out.println(what + ": " + value + (sorted.isEmpty() ? "" : " | " + sorted));
        log.clear();
    }

    static String chain(Throwable e) {
        String s = e.toString();
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            s += " <- " + c;
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        try {
            Stream.of(1, 2, 3).gather(Gatherers.mapConcurrent(2, x -> { if (x == 2) throw new IllegalStateException("two"); return x; })).toList();
        }
        catch (Throwable e) {
            show("runtime", chain(e));
        }
        // a short circuit: what becomes of the tasks under way
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch ended = new CountDownLatch(2);
        show("short", Stream.of(1, 2, 3, 4).gather(Gatherers.mapConcurrent(3, x -> {
            if (x == 1) {
                try {
                    started.await();
                }
                catch (InterruptedException e) {
                    log.add("first interrupted");
                }
                return x;
            }
            started.countDown();
            try {
                new CountDownLatch(1).await();
                log.add("woke " + x);
            }
            catch (InterruptedException e) {
                log.add("interrupted " + x);
            }
            ended.countDown();
            return x;
        })).limit(1).toList());
        show("short waited", ended.await(5, TimeUnit.SECONDS));
        // a failure: what becomes of the tasks under way
        CountDownLatch started2 = new CountDownLatch(1);
        CountDownLatch ended2 = new CountDownLatch(1);
        try {
            Stream.of(1, 2).gather(Gatherers.mapConcurrent(2, x -> {
                if (x == 1) {
                    try {
                        started2.await();
                    }
                    catch (InterruptedException e) {
                        log.add("first interrupted");
                    }
                    throw new IllegalArgumentException("one");
                }
                started2.countDown();
                try {
                    new CountDownLatch(1).await();
                }
                catch (InterruptedException e) {
                    log.add("interrupted " + x);
                }
                ended2.countDown();
                return x;
            })).toList();
        }
        catch (Throwable e) {
            show("failure", chain(e));
        }
        show("failure waited", ended2.await(5, TimeUnit.SECONDS));
        Gatherer<Integer, ?, Integer> g = Gatherers.mapConcurrent(2, x -> x);
        show("initializer", g.initializer() == Gatherer.defaultInitializer());
        show("combiner", g.combiner() == Gatherer.defaultCombiner());
        show("finisher", (Object) g.finisher() == (Object) Gatherer.defaultFinisher());
        Gatherer<Integer, ?, Integer> f = Gatherers.fold(() -> 1, (a, x) -> a);
        show("fold combiner", f.combiner() == Gatherer.defaultCombiner());
        Gatherer<Integer, ?, Integer> s = Gatherers.scan(() -> 1, (a, x) -> a);
        show("scan combiner", s.combiner() == Gatherer.defaultCombiner());
        show("scan finisher", (Object) s.finisher() == (Object) Gatherer.defaultFinisher());
        show("windowFixed combiner", Gatherers.windowFixed(2).combiner() == Gatherer.defaultCombiner());
        show("windowSliding combiner", Gatherers.windowSliding(2).combiner() == Gatherer.defaultCombiner());
        show("caller thread", Stream.of(1).gather(Gatherers.mapConcurrent(1, x -> Thread.currentThread() == Thread.currentThread())).toList());
        Thread caller = Thread.currentThread();
        show("another thread", Stream.of(1).gather(Gatherers.mapConcurrent(1, x -> Thread.currentThread() != caller)).toList());
        show("thread name", Stream.of(1).gather(Gatherers.mapConcurrent(1, x -> Thread.currentThread().getName())).toList());
    }
}
