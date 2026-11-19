// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

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
    }

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
