import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class Multi {
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

    static Stream<Integer> inner(int x, int n) {
        return IntStream.range(0, n).mapToObj(i -> x * 10 + i).onClose(() -> log.append("close").append(x).append(' '));
    }

    public static void main(String[] args) {
        // mapMulti
        show("mapMulti", Stream.of(1, 2, 3).<Integer>mapMulti((x, to) -> { for (int i = 0; i < x; i++) to.accept(x * 10 + i); }).toList());
        show("mapMulti none", Stream.of(1, 2, 3).<Integer>mapMulti((x, to) -> { }).toList());
        show("mapMulti null element", Stream.of(1, 2).<Integer>mapMulti((x, to) -> { to.accept(null); to.accept(x); }).toList());
        show("mapMulti lazy", Stream.of(1, 2, 3).peek(Multi::seen).<Integer>mapMulti((x, to) -> { to.accept(x * 10); to.accept(x * 10 + 1); }).peek(Multi::seen).limit(3).toList());
        show("mapMulti first", Stream.of(1, 2, 3).peek(Multi::seen).<Integer>mapMulti((x, to) -> { seen("m" + x); to.accept(x * 10); seen("n" + x); to.accept(x * 10 + 1); seen("o" + x); }).peek(Multi::seen).findFirst());
        show("mapMulti count", Stream.of(1, 2, 3).peek(Multi::seen).<Integer>mapMulti((x, to) -> to.accept(x)).count());
        show("mapMulti iterator", iterated(Stream.of(1, 2, 3).peek(Multi::seen).<Integer>mapMulti((x, to) -> { to.accept(x * 10); to.accept(x * 10 + 1); }).peek(Multi::seen).iterator()));
        fails("mapMulti null", () -> Stream.of(1).mapMulti(null));
        show("mapMultiToInt", Stream.of(1, 2, 3).mapMultiToInt((x, to) -> { to.accept(x); to.accept(-x); }).sum());
        show("mapMultiToLong", Stream.of(1, 2, 3).mapMultiToLong((x, to) -> { to.accept(x); to.accept(x * 100L); }).boxed().toList());
        show("mapMultiToDouble", Stream.of(1, 2, 3).mapMultiToDouble((x, to) -> { if (x != 2) to.accept(x / 2.0); }).boxed().toList());
        show("int mapMulti", IntStream.range(0, 4).mapMulti((x, to) -> { for (int i = 0; i < x; i++) to.accept(x); }).boxed().toList());
        show("long mapMulti", LongStream.range(0, 4).mapMulti((x, to) -> { for (int i = 0; i < x; i++) to.accept(x); }).boxed().toList());
        show("double mapMulti", DoubleStream.of(1.5, 2.5).mapMulti((x, to) -> { to.accept(x); to.accept(-x); }).boxed().toList());
        show("int mapMulti lazy", IntStream.range(0, 4).peek(Multi::seen).mapMulti((x, to) -> { to.accept(x * 10); to.accept(x * 10 + 1); }).peek(Multi::seen).limit(3).boxed().toList());
        fails("int mapMulti null", () -> IntStream.of(1).mapMulti(null));
        Consumer<Object>[] kept = new Consumer[1];
        show("mapMulti kept", Stream.of(1, 2).<Object>mapMulti((x, to) -> { kept[0] = to; to.accept(x); }).toList());
        fails("mapMulti after", () -> kept[0].accept(9));
        // which stage sees what when
        show("mapMulti any", Stream.of(1, 2, 3).peek(Multi::seen).<Integer>mapMulti((x, to) -> { to.accept(x * 10); to.accept(x * 10 + 1); }).peek(Multi::seen).anyMatch(x -> x == 11));
        show("mapMulti takeWhile", Stream.of(1, 2, 3).peek(Multi::seen).<Integer>mapMulti((x, to) -> { to.accept(x * 10); to.accept(x * 10 + 1); }).peek(Multi::seen).takeWhile(x -> x < 11).toList());
        show("mapMulti sorted", Stream.of(3, 1, 2).peek(Multi::seen).<Integer>mapMulti((x, to) -> { to.accept(x * 10); to.accept(x * 10 + 1); }).sorted().peek(Multi::seen).limit(2).toList());

        // inner streams closed
        show("flatMap all", Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).toList());
        show("flatMap first", Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).findFirst());
        show("flatMap limit", Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).limit(1).map(Multi::seen).toList());
        show("flatMap limit forEach", collected(Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).limit(1)));
        show("flatMap limit 3", Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).limit(3).map(Multi::seen).toList());
        show("flatMap limit 0", Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).limit(0).map(Multi::seen).toList());
        show("flatMap any", Stream.of(1, 2).flatMap(x -> inner(x, 3)).peek(Multi::seen).anyMatch(x -> x == 11));
        show("flatMap all match", Stream.of(1, 2).flatMap(x -> inner(x, 3)).peek(Multi::seen).allMatch(x -> x < 11));
        show("flatMap none match", Stream.of(1, 2).flatMap(x -> inner(x, 3)).peek(Multi::seen).noneMatch(x -> x == 11));
        show("flatMap takeWhile", Stream.of(1, 2).flatMap(x -> inner(x, 3)).peek(Multi::seen).takeWhile(x -> x < 11).map(Multi::seen).toList());
        show("flatMap nested", Stream.of(1, 2).flatMap(x -> Stream.of(x, x + 4).flatMap(y -> inner(y, 2)).onClose(() -> seen("outer" + x))).peek(Multi::seen).findFirst());
        show("flatMap twice", Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).flatMap(x -> inner(x, 2)).findFirst());
        show("flatMap twice limit", Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).flatMap(x -> inner(x, 2)).limit(3).toList());
        show("flatMap after limit", Stream.of(1, 2).flatMap(x -> inner(x, 3)).limit(2).flatMap(x -> inner(x, 2)).peek(Multi::seen).toList());
        fails("flatMap throws", () -> Stream.of(1, 2).flatMap(x -> inner(x, 3)).peek(x -> { if (x == 11) throw new IllegalStateException("stop"); }).forEach(Multi::seen));
        show("flatMap iterator", iterated(Stream.of(1, 2).flatMap(x -> inner(x, 2)).peek(Multi::seen).iterator()));
        Iterator<Integer> it = Stream.of(1, 2).flatMap(x -> inner(x, 2)).iterator();
        show("flatMap iterator one", it.next());
        show("flatMapToInt first", Stream.of(1, 2).flatMapToInt(x -> IntStream.range(0, 2).onClose(() -> seen("close" + x))).peek(Multi::seen).findFirst());
        show("flatMapToLong limit", Stream.of(1, 2).flatMapToLong(x -> LongStream.range(0, 2).onClose(() -> seen("close" + x))).peek(Multi::seen).limit(1).sum());
        show("flatMapToDouble any", Stream.of(1, 2).flatMapToDouble(x -> DoubleStream.of(0.5, 1.5).onClose(() -> seen("close" + x))).peek(Multi::seen).anyMatch(x -> x > 0));
        show("int flatMap first", IntStream.of(1, 2).flatMap(x -> IntStream.range(0, 2).onClose(() -> seen("close" + x))).peek(Multi::seen).findFirst());
        show("long flatMap first", LongStream.of(1, 2).flatMap(x -> LongStream.range(0, 2).onClose(() -> seen("close" + x))).peek(Multi::seen).findAny());
        show("double flatMap none", DoubleStream.of(1, 2).flatMap(x -> DoubleStream.of(0.5, 1.5).onClose(() -> seen("close" + x))).peek(Multi::seen).noneMatch(x -> x > 0));
        show("flatMap skip limit", Stream.of(1, 2, 3).flatMap(x -> inner(x, 2)).skip(1).limit(2).peek(Multi::seen).toList());
        show("flatMap limit count", Stream.of(1, 2, 3).flatMap(x -> inner(x, 2)).limit(3).count());
        show("flatMap concat", Stream.concat(Stream.of(1, 2).flatMap(x -> inner(x, 2)), Stream.of(7).flatMap(x -> inner(x, 2))).peek(Multi::seen).limit(1).toList());
        show("flatMap concat second", Stream.concat(Stream.of(1).flatMap(x -> inner(x, 2)), Stream.of(7).flatMap(x -> inner(x, 2))).peek(Multi::seen).limit(3).toList());
        show("flatMap close fails", "");
        fails("flatMap close fails", () -> Stream.of(1, 2).flatMap(x -> inner(x, 2).onClose(() -> { throw new IllegalStateException("closing " + x); })).peek(Multi::seen).findFirst());
    }

    static <T> List<T> collected(Stream<T> s) {
        List<T> all = new ArrayList<>();
        s.forEach(x -> { seen("each" + x); all.add(x); });
        return all;
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
