// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util.stream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import rontolisp.internal.Flow;

public final class Gatherers {

    private Gatherers() {
    }

    private static <R> Flow.Held<R> holding(R value) {
        Flow.Held<R> held = new Flow.Held<>();
        held.value = value;
        return held;
    }

    public static <T, R> Gatherer<T, ?, R> fold(Supplier<R> initial,
            BiFunction<? super R, ? super T, ? extends R> folder) {
        Objects.requireNonNull(initial, "'initial' must not be null");
        Objects.requireNonNull(folder, "'folder' must not be null");
        return Gatherer.<T, Flow.Held<R>, R>ofSequential(() -> holding(initial.get()), (held, element, downstream) -> {
            held.value = folder.apply(held.value, element);
            return true;
        }, (held, downstream) -> downstream.push(held.value));
    }

    public static <T, R> Gatherer<T, ?, R> scan(Supplier<R> initial,
            BiFunction<? super R, ? super T, ? extends R> scanner) {
        Objects.requireNonNull(initial, "'initial' must not be null");
        Objects.requireNonNull(scanner, "'scanner' must not be null");
        return Gatherer.<T, Flow.Held<R>, R>ofSequential(() -> holding(initial.get()), (held, element, downstream) -> {
            held.value = scanner.apply(held.value, element);
            return downstream.push(held.value);
        });
    }

    private static void atLeastOne(int windowSize) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("'windowSize' must be greater than zero");
        }
    }

    public static <TR> Gatherer<TR, ?, List<TR>> windowFixed(int windowSize) {
        atLeastOne(windowSize);
        return Gatherer.<TR, Flow.Held<ArrayList<TR>>, List<TR>>ofSequential(() -> holding(new ArrayList<TR>()),
                (held, element, downstream) -> {
                    ArrayList<TR> window = held.value;
                    window.add(element);
                    if (window.size() < windowSize) {
                        return true;
                    }
                    held.value = new ArrayList<TR>();
                    return downstream.push(Collections.unmodifiableList(window));
                }, (held, downstream) -> {
                    if (!held.value.isEmpty()) {
                        downstream.push(Collections.unmodifiableList(held.value));
                    }
                });
    }

    // the window so far, and whether one was handed on: a stream shorter than a window
    // gives the one window it has at its end
    private static final class Sliding<TR> {

        final ArrayList<TR> window = new ArrayList<>();

        boolean pushed;
    }

    public static <TR> Gatherer<TR, ?, List<TR>> windowSliding(int windowSize) {
        atLeastOne(windowSize);
        return Gatherer.<TR, Sliding<TR>, List<TR>>ofSequential(() -> new Sliding<TR>(),
                (sliding, element, downstream) -> {
                    ArrayList<TR> window = sliding.window;
                    if (window.size() == windowSize) {
                        window.remove(0);
                    }
                    window.add(element);
                    if (window.size() < windowSize) {
                        return true;
                    }
                    sliding.pushed = true;
                    return downstream.push(Collections.unmodifiableList(new ArrayList<TR>(window)));
                }, (sliding, downstream) -> {
                    if (!sliding.pushed && !sliding.window.isEmpty()) {
                        downstream.push(Collections.unmodifiableList(new ArrayList<TR>(sliding.window)));
                    }
                });
    }

    // one element's mapping under way on a thread of its own
    private static final class Mapping<T, R> implements Runnable {

        private final Function<? super T, ? extends R> mapper;

        private final T element;

        Thread thread;

        R result;

        Throwable failure;

        Mapping(Function<? super T, ? extends R> mapper, T element) {
            this.mapper = mapper;
            this.element = element;
        }

        public void run() {
            try {
                result = mapper.apply(element);
            }
            catch (Throwable failed) {
                failure = failed;
            }
        }

        // waits for the mapping's end whatever interrupts the wait, which is then the
        // waiting thread's again
        void ended() {
            boolean interrupted = false;
            for (;;) {
                try {
                    thread.join();
                    break;
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // The mappings under way, in the order of their elements: no more than the limit at
    // once, and one's result is handed on only after those before it. A stream that ends
    // early, and a mapping that fails, interrupt the others and wait for them.
    public static <T, R> Gatherer<T, ?, R> mapConcurrent(int maxConcurrency,
            Function<? super T, ? extends R> mapper) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("'maxConcurrency' must be greater than 0");
        }
        Objects.requireNonNull(mapper, "'mapper' must not be null");
        return Gatherer.<T, ArrayDeque<Mapping<T, R>>, R>ofSequential(() -> new ArrayDeque<Mapping<T, R>>(),
                (window, element, downstream) -> {
                    Mapping<T, R> mapping = new Mapping<T, R>(mapper, element);
                    window.add(mapping);
                    mapping.thread = Thread.startVirtualThread(mapping);
                    return window.size() < maxConcurrency || handedOn(window, downstream);
                }, (window, downstream) -> {
                    while (!window.isEmpty() && !downstream.isRejecting() && handedOn(window, downstream)) {
                    }
                    interrupted(window);
                });
    }

    private static <T, R> boolean handedOn(ArrayDeque<Mapping<T, R>> window, Gatherer.Downstream<? super R> downstream) {
        Mapping<T, R> first = window.remove();
        first.ended();
        Throwable failure = first.failure;
        if (failure == null) {
            return downstream.push(first.result);
        }
        interrupted(window);
        if (failure instanceof RuntimeException unchecked) {
            throw unchecked;
        }
        throw new RuntimeException(failure);
    }

    private static <T, R> void interrupted(ArrayDeque<Mapping<T, R>> window) {
        for (Mapping<T, R> mapping : window) {
            mapping.thread.interrupt();
        }
        while (!window.isEmpty()) {
            window.remove().ended();
        }
    }
}
