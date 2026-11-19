import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Gather {
    static StringBuilder log = new StringBuilder();

    static <T> T seen(T x) {
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
            show(what, e);
        }
    }

    // pushes each element n times, saying what each push answered
    static Gatherer<Integer, ?, Integer> times(int n) {
        return Gatherer.of((state, x, down) -> {
            for (int i = 0; i < n; i++) {
                boolean more = down.push(x * 10 + i);
                seen("push" + (x * 10 + i) + "=" + more + (down.isRejecting() ? "!" : ""));
            }
            return true;
        });
    }

    static Gatherer<Integer, int[], Integer> firstTwo() {
        return Gatherer.ofSequential(() -> { seen("init"); return new int[1]; }, (state, x, down) -> {
            seen("in" + x);
            down.push(x);
            return ++state[0] < 2;
        }, (state, down) -> { seen("finish" + state[0] + (down.isRejecting() ? "!" : "")); down.push(-1); });
    }

    public static void main(String[] args) {
        show("fold", Stream.of(1, 2, 3).gather(Gatherers.fold(() -> 10, (a, x) -> a + x)).toList());
        show("fold none", Stream.<Integer>empty().gather(Gatherers.fold(() -> 10, (a, x) -> a + x)).toList());
        show("scan", Stream.of(1, 2, 3).gather(Gatherers.scan(() -> "", (a, x) -> a + x)).toList());
        show("scan none", Stream.<Integer>empty().gather(Gatherers.scan(() -> "", (a, x) -> a + x)).toList());
        show("scan lazy", Stream.of(1, 2, 3).peek(Gather::seen).gather(Gatherers.scan(() -> 0, (a, x) -> a + x)).peek(Gather::seen).limit(2).toList());
        show("windowFixed", Stream.of(1, 2, 3, 4, 5).gather(Gatherers.windowFixed(2)).toList());
        show("windowFixed exact", Stream.of(1, 2, 3, 4).gather(Gatherers.windowFixed(2)).toList());
        show("windowFixed none", Stream.empty().gather(Gatherers.windowFixed(2)).toList());
        show("windowFixed null", Stream.of(1, null, 3).gather(Gatherers.windowFixed(2)).toList());
        show("windowFixed lazy", Stream.of(1, 2, 3, 4, 5).peek(Gather::seen).gather(Gatherers.windowFixed(2)).peek(Gather::seen).findFirst());
        show("windowSliding", Stream.of(1, 2, 3, 4, 5).gather(Gatherers.windowSliding(3)).toList());
        show("windowSliding short", Stream.of(1, 2).gather(Gatherers.windowSliding(3)).toList());
        show("windowSliding exact", Stream.of(1, 2, 3).gather(Gatherers.windowSliding(3)).toList());
        show("windowSliding one", Stream.of(1, 2, 3).gather(Gatherers.windowSliding(1)).toList());
        show("windowSliding none", Stream.empty().gather(Gatherers.windowSliding(3)).toList());
        show("windowSliding lazy", Stream.of(1, 2, 3, 4, 5).peek(Gather::seen).gather(Gatherers.windowSliding(2)).peek(Gather::seen).limit(2).toList());
        fails("windowFixed 0", () -> Gatherers.windowFixed(0));
        fails("windowSliding 0", () -> Gatherers.windowSliding(0));
        fails("windowSliding -3", () -> Gatherers.windowSliding(-3));
        fails("fold null", () -> Gatherers.fold(null, (a, x) -> a));
        fails("fold null folder", () -> Gatherers.fold(() -> 1, null));
        fails("scan null", () -> Gatherers.scan(null, (a, x) -> a));
        fails("gather null", () -> Stream.of(1).gather(null));
        List<Integer> window = Stream.of(1, 2, 3).gather(Gatherers.windowFixed(2)).findFirst().get();
        fails("window add", () -> window.add(1));
        fails("window set", () -> window.set(0, 1));
        List<Integer> sliding = Stream.of(1, 2, 3).gather(Gatherers.windowSliding(2)).findFirst().get();
        fails("sliding add", () -> sliding.add(1));

        show("times", Stream.of(1, 2).gather(times(2)).toList());
        show("times limit", Stream.of(1, 2).gather(times(3)).limit(2).toList());
        show("times limit peek", Stream.of(1, 2).peek(Gather::seen).gather(times(3)).peek(Gather::seen).limit(2).toList());
        show("times first", Stream.of(1, 2).gather(times(3)).findFirst());
        show("times any", Stream.of(1, 2).gather(times(3)).anyMatch(x -> x == 11));
        show("times takeWhile", Stream.of(1, 2).gather(times(3)).takeWhile(x -> x < 11).toList());
        show("times count", Stream.of(1, 2).gather(times(2)).count());
        show("times twice", Stream.of(1, 2).gather(times(2)).gather(times(2)).limit(3).toList());
        show("times iterator", iterated(Stream.of(1, 2).gather(times(2)).iterator()));
        show("firstTwo", Stream.of(5, 6, 7, 8).peek(Gather::seen).gather(firstTwo()).toList());
        show("firstTwo short", Stream.of(5).peek(Gather::seen).gather(firstTwo()).toList());
        show("firstTwo first", Stream.of(5, 6, 7, 8).peek(Gather::seen).gather(firstTwo()).findFirst());
        show("firstTwo limit 2", Stream.of(5, 6, 7, 8).peek(Gather::seen).gather(firstTwo()).limit(2).toList());
        show("firstTwo limit 1", Stream.of(5, 6, 7, 8).peek(Gather::seen).gather(firstTwo()).limit(1).toList());
        show("firstTwo limit 3", Stream.of(5, 6, 7, 8).peek(Gather::seen).gather(firstTwo()).limit(3).toList());
        show("firstTwo none", Stream.<Integer>empty().gather(firstTwo()).toList());
        show("firstTwo flat", Stream.of(1, 2, 3).flatMap(x -> Stream.of(x, x + 10).onClose(() -> seen("close" + x))).gather(firstTwo()).toList());
        Stream<Integer> lazy = Stream.of(1, 2).gather(firstTwo());
        show("not yet", "made");
        show("then", lazy.toList());

        Gatherer<Integer, ?, Integer> doubled = Gatherer.of((s, x, down) -> down.push(x * 2));
        Gatherer<Integer, ?, String> texts = Gatherer.of(Gatherer.Integrator.ofGreedy((s, x, down) -> down.push("<" + x + ">")));
        show("andThen", Stream.of(1, 2, 3).gather(doubled.andThen(texts)).toList());
        show("andThen builtin", Stream.of(1, 2, 3, 4, 5).gather(Gatherers.windowFixed(2).andThen(Gatherers.scan(() -> 0, (a, w) -> a + w.size()))).toList());
        show("andThen firstTwo", Stream.of(5, 6, 7, 8).peek(Gather::seen).gather(firstTwo().andThen(times(2))).toList());
        show("andThen times firstTwo", Stream.of(5, 6, 7, 8).peek(Gather::seen).gather(times(2).andThen(firstTwo())).toList());
        show("andThen fold", Stream.of(1, 2, 3).gather(Gatherers.<Integer, Integer>scan(() -> 0, (a, x) -> a + x).andThen(Gatherers.fold(() -> "", (a, x) -> a + x))).toList());
        fails("andThen null", () -> doubled.andThen(null));

        Gatherer<Integer, ?, Integer> plain = Gatherer.of((s, x, down) -> down.push(x));
        show("default initializer", plain.initializer() == Gatherer.defaultInitializer());
        show("default combiner", plain.combiner() == Gatherer.defaultCombiner());
        show("default finisher", (Object) plain.finisher() == (Object) Gatherer.defaultFinisher());
        Gatherer<Integer, ?, Integer> seq = Gatherer.ofSequential((s, x, down) -> down.push(x));
        show("sequential initializer", seq.initializer() == Gatherer.defaultInitializer());
        show("sequential combiner", seq.combiner() == Gatherer.defaultCombiner());
        show("sequential finisher", (Object) seq.finisher() == (Object) Gatherer.defaultFinisher());
        show("state null", plain.initializer().get());
        Gatherer<Integer, ?, Integer> ended = Gatherer.of((s, x, down) -> down.push(x), (s, down) -> down.push(99));
        show("of finisher", Stream.of(1).gather(ended).toList());
        show("of finisher combiner", ended.combiner() == Gatherer.defaultCombiner());
        Gatherer<Integer, int[], Integer> four = Gatherer.of(() -> new int[1], (s, x, down) -> { s[0] += x; return true; }, (a, b) -> a, (s, down) -> down.push(s[0]));
        show("of four", Stream.of(1, 2, 3).gather(four).toList());
        Gatherer<Integer, int[], Integer> seqState = Gatherer.ofSequential(() -> new int[1], (s, x, down) -> down.push(s[0] += x));
        show("ofSequential state", Stream.of(1, 2, 3).gather(seqState).toList());
        show("ofSequential state finisher", (Object) seqState.finisher() == (Object) Gatherer.defaultFinisher());
        Gatherer<Integer, ?, Integer> seqEnd = Gatherer.ofSequential((s, x, down) -> true, (s, down) -> down.push(7));
        show("ofSequential finisher", Stream.of(1, 2, 3).gather(seqEnd).toList());
        fails("of null", () -> Gatherer.of((Gatherer.Integrator<Void, Integer, Integer>) null));
        fails("ofSequential null", () -> Gatherer.ofSequential((Gatherer.Integrator<Void, Integer, Integer>) null));
        fails("default combiner called", () -> Gatherer.<int[]>defaultCombiner().apply(new int[1], new int[1]));
        Gatherer.Integrator<Void, Integer, Integer> same = (s, x, down) -> down.push(x);
        show("Integrator.of", Gatherer.Integrator.of(same) == same);
        Gatherer.Integrator.Greedy<Void, Integer, Integer> greedy = (s, x, down) -> down.push(x);
        show("Integrator.ofGreedy", Gatherer.Integrator.ofGreedy(greedy) == greedy);
        show("integrator kept", Gatherer.of(same).integrator() == same);

        // a gatherer of the program's own class
        Gatherer<String, StringBuilder, String> joined = new Gatherer<>() {
            public Supplier<StringBuilder> initializer() {
                return StringBuilder::new;
            }

            public Integrator<StringBuilder, String, String> integrator() {
                return (sb, s, down) -> { sb.append(s); return true; };
            }

            public BiConsumer<StringBuilder, Downstream<? super String>> finisher() {
                return (sb, down) -> down.push(sb.toString());
            }
        };
        show("own class", Stream.of("a", "b", "c").gather(joined).toList());
        show("own class then", Stream.of("a", "b", "c").gather(joined.andThen(Gatherers.scan(() -> "", (a, x) -> a + x + x))).toList());
        Gatherer.Downstream<Integer> sink = x -> x < 3;
        show("downstream default", sink.isRejecting());

        // the finisher after an end the stages below asked for
        Gatherer<Integer, ?, Integer> ending = Gatherer.ofSequential((s, x, down) -> { seen("in" + x); return down.push(x); }, (s, down) -> seen("finish" + (down.isRejecting() ? "!" : "")));
        show("finisher after limit", Stream.of(1, 2, 3).gather(ending).limit(1).toList());
        show("finisher after first", Stream.of(1, 2, 3).gather(ending).findFirst());
        show("finisher after all", Stream.of(1, 2, 3).gather(ending).toList());
        show("finisher takeWhile", Stream.of(1, 2, 3).gather(ending).takeWhile(x -> x < 2).toList());
        show("push after reject", Stream.of(1, 2, 3).gather(Gatherer.<Integer, Integer>ofSequential((s, x, down) -> { seen(down.push(x)); seen(down.push(x + 10)); seen(down.isRejecting()); return true; })).limit(1).toList());
        show("gather sorted", Stream.of(3, 1, 2).gather(ending).sorted().toList());
        show("gather parallel flag", Stream.of(3, 1, 2).parallel().gather(ending).isParallel());
        show("gather closes", "");
        try (Stream<Integer> s = Stream.of(1).onClose(() -> seen("closed")).gather(ending)) {
            show("inside", s.toList());
        }
        show("after", "");
        show("mapConcurrent", Stream.of(1, 2, 3, 4, 5).gather(Gatherers.mapConcurrent(2, x -> x * x)).toList());
        show("mapConcurrent none", Stream.<Integer>empty().gather(Gatherers.mapConcurrent(2, x -> x * x)).toList());
        show("mapConcurrent limit", Stream.of(1, 2, 3, 4, 5).gather(Gatherers.mapConcurrent(2, x -> x * x)).limit(2).toList());
        fails("mapConcurrent 0", () -> Gatherers.mapConcurrent(0, x -> x));
        fails("mapConcurrent null", () -> Gatherers.mapConcurrent(1, null));
        fails("mapConcurrent throws", () -> Stream.of(1, 2, 3).gather(Gatherers.mapConcurrent(2, x -> { if (x == 2) throw new IllegalStateException("two"); return x; })).toList());
        CountDownLatch both = new CountDownLatch(2);
        show("mapConcurrent together", Stream.of(1, 2).gather(Gatherers.mapConcurrent(2, x -> { both.countDown(); try { both.await(); } catch (InterruptedException e) { return -1; } return x; })).toList());
        show("mapConcurrent thread", Stream.of(1).gather(Gatherers.mapConcurrent(1, x -> Thread.currentThread().isVirtual())).toList());
    }

    static <T> List<T> iterated(Iterator<T> it) {
        List<T> all = new ArrayList<>();
        while (it.hasNext()) {
            all.add(it.next());
            seen("/");
        }
        return all;
    }
}
