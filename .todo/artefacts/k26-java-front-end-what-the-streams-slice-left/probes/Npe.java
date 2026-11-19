import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Npe {
    static void f(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ran");
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e);
        }
    }

    public static void main(String[] args) {
        Gatherer<Integer, ?, Integer> plain = Gatherer.of((s, x, down) -> down.push(x));
        f("fold initial", () -> Gatherers.fold(null, (a, x) -> a));
        f("fold folder", () -> Gatherers.fold(() -> 1, null));
        f("scan initial", () -> Gatherers.scan(null, (a, x) -> a));
        f("scan scanner", () -> Gatherers.scan(() -> 1, null));
        f("mapConcurrent", () -> Gatherers.mapConcurrent(1, null));
        f("gather", () -> Stream.of(1).gather(null));
        f("andThen", () -> plain.andThen(null));
        f("of", () -> Gatherer.of((Gatherer.Integrator<Void, Integer, Integer>) null));
        f("of finisher", () -> Gatherer.of((s, x, down) -> true, null));
        f("of four initializer", () -> Gatherer.of(null, (s, x, down) -> true, (a, b) -> a, (s, d) -> { }));
        f("of four combiner", () -> Gatherer.of(() -> 1, (s, x, down) -> true, null, (s, d) -> { }));
        f("ofSequential", () -> Gatherer.ofSequential((Gatherer.Integrator<Void, Integer, Integer>) null));
        f("ofSequential finisher", () -> Gatherer.ofSequential((s, x, down) -> true, null));
        f("ofSequential initializer", () -> Gatherer.ofSequential(null, (s, x, down) -> true));
        f("Integrator.of", () -> Gatherer.Integrator.of(null));
        f("Integrator.ofGreedy", () -> Gatherer.Integrator.ofGreedy(null));
        f("mapMulti", () -> Stream.of(1).mapMulti(null));
        f("windowFixed", () -> Gatherers.windowFixed(-1));
        f("mapConcurrent -1", () -> Gatherers.mapConcurrent(-1, x -> x));
    }
}
