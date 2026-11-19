import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Gather2 {
    static StringBuilder log = new StringBuilder();

    static synchronized <T> T seen(T x) {
        log.append(x).append(' ');
        return x;
    }

    static void show(String what, Object value) {
        System.out.println(what + ": " + value + (log.isEmpty() ? "" : " | " + log.toString().trim()));
        log.setLength(0);
    }

    static void fails(String what, Runnable r) {
        try {
            r.run();
            show(what, "ran");
        }
        catch (NullPointerException e) {
            show(what, e.getClass().getName());
        }
        catch (RuntimeException e) {
            show(what, e + (e.getCause() == null ? "" : " cause " + e.getCause()));
        }
    }

    static Gatherer<Integer, int[], Integer> ending(String name) {
        return Gatherer.ofSequential(() -> { seen(name + "init"); return new int[1]; },
                (s, x, down) -> { seen(name + "in" + x); return down.push(x); },
                (s, down) -> seen(name + "finish" + (down.isRejecting() ? "!" : "")));
    }

    static Stream<Integer> inner(int x) {
        return Stream.of(x, x + 10).onClose(() -> seen("close" + x));
    }

    public static void main(String[] args) throws Exception {
        show("limit 0", Stream.of(1, 2).gather(ending("")).limit(0).toList());
        show("flat first", Stream.of(1, 2).flatMap(Gather2::inner).gather(ending("")).findFirst());
        show("flat limit", Stream.of(1, 2).flatMap(Gather2::inner).gather(ending("")).limit(1).toList());
        show("gather flat first", Stream.of(1, 2).gather(ending("")).flatMap(Gather2::inner).findFirst());
        show("two first", Stream.of(1, 2).gather(ending("a")).gather(ending("b")).findFirst());
        show("two all", Stream.of(1, 2).gather(ending("a")).gather(ending("b")).toList());
        show("two limit", Stream.of(1, 2, 3).gather(ending("a")).gather(ending("b")).limit(2).toList());
        show("composed first", Stream.of(1, 2).gather(ending("a").andThen(ending("b"))).findFirst());
        show("composed all", Stream.of(1, 2).gather(ending("a").andThen(ending("b"))).toList());
        show("flat two any", Stream.of(1, 2).flatMap(Gather2::inner).gather(ending("a")).flatMap(Gather2::inner).gather(ending("b")).anyMatch(x -> x == 11));
        Gatherer<Integer, ?, Integer> plain = Gatherer.of((s, x, down) -> down.push(x));
        fails("of combiner", () -> show("of combiner", ((BinaryOperator<Object>) (Object) plain.combiner()).apply("a", "b")));
        Gatherer<Integer, ?, Integer> two = plain.andThen(plain);
        show("andThen initializer", two.initializer() == Gatherer.defaultInitializer());
        show("andThen finisher", (Object) two.finisher() == (Object) Gatherer.defaultFinisher());
        show("andThen combiner", two.combiner() == Gatherer.defaultCombiner());
        Gatherer<Integer, ?, Integer> seqs = Gatherer.<Integer, Integer>ofSequential((s, x, down) -> down.push(x)).andThen(plain);
        show("andThen seq combiner", seqs.combiner() == Gatherer.defaultCombiner());
        show("andThen seq initializer", seqs.initializer() == Gatherer.defaultInitializer());
        show("fold lazy", Stream.of(1, 2, 3).peek(Gather2::seen).gather(Gatherers.fold(() -> seen(10), (a, x) -> a + x)).peek(Gather2::seen).findFirst());
        show("scan state", Stream.of(1, 2, 3).gather(Gatherers.scan(() -> seen(10), (a, x) -> a + x)).limit(0).toList());
        show("windowFixed 5 of 2", Stream.of(1, 2).gather(Gatherers.windowFixed(5)).toList());
        show("window class", Stream.of(1, 2).gather(Gatherers.windowFixed(2)).findFirst().get().contains(null));
        fails("gather twice", () -> { Stream<Integer> s = Stream.of(1); s.gather(plain); s.gather(plain); });
        show("gather count", Stream.of(1, 2, 3).peek(Gather2::seen).gather(plain).count());
        show("gather throws", "");
        fails("gather throws", () -> Stream.of(1, 2).gather(Gatherer.<Integer, Integer>ofSequential((s, x, down) -> { throw new IllegalStateException("in " + x); }, (s, down) -> seen("finish"))).toList());

        // mapConcurrent: how many run at once, and when a result is handed on
        CountDownLatch third = new CountDownLatch(1);
        show("mapConcurrent third frees first", Stream.of(1, 2, 3).gather(Gatherers.mapConcurrent(2, x -> {
            try {
                if (x == 1) {
                    return third.await(2, TimeUnit.SECONDS) ? 1 : -1;
                }
                if (x == 3) {
                    third.countDown();
                }
            }
            catch (InterruptedException e) {
                return -2;
            }
            return x;
        })).toList());
        CountDownLatch second = new CountDownLatch(1);
        show("mapConcurrent one at a time", Stream.of(1, 2).gather(Gatherers.mapConcurrent(1, x -> {
            try {
                if (x == 1) {
                    return second.await(300, TimeUnit.MILLISECONDS) ? 1 : -1;
                }
                second.countDown();
            }
            catch (InterruptedException e) {
                return -2;
            }
            return x;
        })).toList());
        show("mapConcurrent lazy", Stream.of(1, 2, 3, 4, 5, 6).peek(Gather2::seen).gather(Gatherers.mapConcurrent(2, x -> x)).peek(Gather2::seen).limit(1).toList());
        show("mapConcurrent order", Stream.of(1, 2, 3, 4, 5, 6).peek(Gather2::seen).gather(Gatherers.mapConcurrent(2, x -> x)).peek(Gather2::seen).toList());
        show("mapConcurrent order 3", Stream.of(1, 2, 3, 4, 5, 6).peek(Gather2::seen).gather(Gatherers.mapConcurrent(3, x -> x)).peek(Gather2::seen).toList());
        fails("mapConcurrent checked", () -> Stream.of(1, 2, 3).gather(Gatherers.mapConcurrent(2, x -> { if (x == 2) throw new Error("two"); return x; })).toList());
        try {
            Stream.of(1, 2, 3).gather(Gatherers.mapConcurrent(2, x -> { if (x == 2) throw new StackOverflowError("two"); return x; })).toList();
        }
        catch (Throwable e) {
            show("mapConcurrent error", e + (e.getCause() == null ? "" : " cause " + e.getCause()));
        }
        show("mapConcurrent null result", Stream.of(1, 2).gather(Gatherers.mapConcurrent(2, x -> x == 1 ? null : x)).toList());
        show("mapConcurrent interrupted", Stream.of(1, 2, 3).gather(Gatherers.mapConcurrent(1, x -> { seen("run" + x); return x; })).limit(1).toList());
    }
}
