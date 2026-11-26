// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Spliterator;

// What the four kinds of stream are made of. A stream hands its elements on one at a
// time through a function, its pull: each call answers the next element, and a call
// that finds none sets the ending the function was made with and answers nothing that
// counts. A stage that hands on what the stage before it gives shares that ending; one
// that goes on past it (a sort, a concatenation) has its own.
public abstract class Flow {

    public static final class Ending {

        public boolean reached;
    }

    // what the stages of one stream share from its source on
    public static final class Origin {

        boolean parallel;

        Runnable closing;

        Told told;
    }

    // What the stages of one stream are told, the stage that was made last first; each
    // hands it on to the one before it. LEAVE, where a stage or the last operation asks
    // for no more elements: a stream flatMap has not finished is closed on the way. END,
    // after it: a gatherer finishes on the way back, the one nearest the source first.
    // BEGIN, before a gatherer asks for its first element: the gatherers before it make
    // their states first.
    public interface Told {

        void of(int what);
    }

    static final int BEGIN = 0;

    static final int LEAVE = 1;

    static final int END = 2;

    public static final class Held<V> {

        public V value;
    }

    public interface Pull<T> {

        T next();
    }

    public interface IntPull {

        int next();
    }

    public interface LongPull {

        long next();
    }

    public interface DoublePull {

        double next();
    }

    // how many elements a stream holds, asked when the stream ends: null where its
    // source or one of its stages cannot tell without running it
    public interface Count {

        long now();
    }

    final Origin origin;

    final Ending ending;

    final Count count;

    private boolean linked;

    Flow(Origin origin, Ending ending, Count count) {
        this.origin = origin;
        this.ending = ending;
        this.count = count;
    }

    // The spliterator of a stream, and of the numbers of an array or an iterator: the
    // elements a pull gives. Its size is a count where one is known -- taken when first
    // asked for, and going down by one an element where it is counted down -- and else
    // what it is given to estimate, or as many as a long counts.
    public abstract static class Walked<T> {

        private final Count sized;

        private final Count about;

        private final int marks;

        private final boolean counted;

        private long left = -1;

        Walked(Count sized, Count about, int marks, boolean counted) {
            this.sized = sized;
            this.about = about;
            this.marks = marks;
            this.counted = counted;
        }

        public long estimateSize() {
            if (left < 0) {
                Count known = sized != null ? sized : about;
                left = known == null ? Long.MAX_VALUE : known.now();
            }
            return left;
        }

        // whether an element was taken: one fewer is left
        final boolean took(Ending ending) {
            if (ending.reached) {
                return false;
            }
            estimateSize();
            if (counted && sized != null && left > 0) {
                left--;
            }
            return true;
        }

        public int characteristics() {
            return marks;
        }

        public Comparator<? super T> getComparator() {
            if ((marks & Spliterator.SORTED) != 0) {
                return null;
            }
            throw new IllegalStateException();
        }
    }

    // What a stream's spliterator reports: an order, and its size where the stream knows
    // its count. The marks a JDK keeps track of through the stages are not kept here.
    final int marks() {
        return count == null ? Spliterator.ORDERED
                : Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED;
    }

    // a stream is asked once, for its elements or for the stream after it
    final void link() {
        if (linked) {
            throw new IllegalStateException("stream has already been operated upon or closed");
        }
        linked = true;
    }

    public boolean isParallel() {
        return origin.parallel;
    }

    public void close() {
        linked = true;
        Runnable handlers = origin.closing;
        origin.closing = null;
        if (handlers != null) {
            handlers.run();
        }
    }

    // no more elements are asked of the stages a notice reaches
    static void leave(Told told) {
        if (told != null) {
            told.of(LEAVE);
            told.of(END);
        }
    }

    // a stream flatMap was reading when no more was asked of it
    final void left() {
        leave(origin.told);
        close();
    }

    // a stage reading the streams it is given one after the other: the one it has not
    // finished is closed where no more is asked of the stage
    static void within(Origin origin, Held<? extends Flow> inner) {
        Told before = origin.told;
        origin.told = what -> {
            Flow open = inner.value;
            if (what == LEAVE && open != null) {
                inner.value = null;
                open.left();
            }
            if (before != null) {
                before.of(what);
            }
        };
    }

    final void closing(Runnable handler) {
        if (linked) {
            throw new IllegalStateException("stream has already been operated upon or closed");
        }
        Runnable before = origin.closing;
        origin.closing = before == null ? handler : both(before, handler);
    }

    // both run; of two failures the first is thrown and holds the second
    static Runnable both(Runnable first, Runnable second) {
        return () -> {
            try {
                first.run();
            }
            catch (Throwable failed) {
                try {
                    second.run();
                }
                catch (Throwable also) {
                    failed.addSuppressed(also);
                }
                throw failed;
            }
            second.run();
        };
    }

    // the origin of a stream of two streams one after the other
    static Origin joined(Flow first, Flow second) {
        Origin origin = new Origin();
        origin.parallel = first.origin.parallel || second.origin.parallel;
        origin.closing = both(first::close, second::close);
        Told one = first.origin.told;
        Told two = second.origin.told;
        if (one != null || two != null) {
            origin.told = what -> {
                if (two != null) {
                    two.of(what);
                }
                if (one != null) {
                    one.of(what);
                }
            };
        }
        return origin;
    }

    static Count sum(Count first, Count second) {
        return first == null || second == null ? null : () -> first.now() + second.now();
    }

    static Count less(Count count, long skipped) {
        return count == null ? null : () -> Math.max(0, count.now() - skipped);
    }

    static Count most(Count count, long limit) {
        return count == null ? null : () -> Math.min(count.now(), limit);
    }

    // A sum of doubles is kept in three: the sum, what the additions so far lost of it
    // (Kahan's compensated summation) and the plain sum, which is the answer where the
    // first two are no number and it is infinite.
    public static void add(double[] sum, double value) {
        double corrected = value - sum[1];
        double next = sum[0] + corrected;
        sum[1] = (next - sum[0]) - corrected;
        sum[0] = next;
        sum[2] += value;
    }

    // The elements as a list of List.of's kind that holds nulls, what Stream.toList() and a
    // gatherer's windows answer: java.util.ImmutableCollections.listed, a class of a
    // package this one cannot name (JavaIntrinsics' natives).
    public static native <E> List<E> listed(Object[] held);

    public static double total(double[] sum) {
        double total = sum[0] - sum[1];
        return Double.isNaN(total) && Double.isInfinite(sum[2]) ? sum[2] : total;
    }

    static void given(Object function) {
        if (function == null) {
            throw new NullPointerException();
        }
    }

    static void atLeastZero(long n) {
        if (n < 0) {
            throw new IllegalArgumentException(Long.toString(n));
        }
    }
}
