import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class Counted {
    static String tried(Supplier<Object> s) {
        try {
            return String.valueOf(s.get());
        }
        catch (Throwable t) {
            return "threw " + t + (t.getCause() != null ? " <- " + t.getCause() : "");
        }
    }

    /** Sums an array by halves: the classic shape, a completer per split. */
    static class Sum extends CountedCompleter<Long> {
        final int[] data;
        final int from, to;
        Sum left, right;
        long sum;

        Sum(CountedCompleter<?> parent, int[] data, int from, int to) {
            super(parent);
            this.data = data;
            this.from = from;
            this.to = to;
        }

        public void compute() {
            if (to - from <= 4) {
                for (int i = from; i < to; i++) sum += data[i];
            }
            else {
                int mid = (from + to) >>> 1;
                left = new Sum(this, data, from, mid);
                right = new Sum(this, data, mid, to);
                setPendingCount(2);
                left.fork();
                right.fork();
            }
            tryComplete();
        }

        public void onCompletion(CountedCompleter<?> caller) {
            if (left != null) sum = left.sum + right.sum;
        }

        public Long getRawResult() {
            return sum;
        }
    }

    /** A loop over leaves with one completer: addToPendingCount before each fork. */
    static class Each extends CountedCompleter<Void> {
        final int from, to;
        final AtomicInteger hits;

        Each(CountedCompleter<?> parent, int from, int to, AtomicInteger hits) {
            super(parent);
            this.from = from;
            this.to = to;
            this.hits = hits;
        }

        public void compute() {
            int lo = from, hi = to;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                addToPendingCount(1);
                new Each(this, mid, hi, hits).fork();
                hi = mid;
            }
            hits.addAndGet(lo);
            propagateCompletion();
        }
    }

    /** A search: the first to find completes the root. */
    static class Find extends CountedCompleter<String> {
        final String[] words;
        final int from, to;
        final AtomicReference<String> found;

        Find(CountedCompleter<?> parent, String[] words, int from, int to, AtomicReference<String> found) {
            super(parent);
            this.words = words;
            this.from = from;
            this.to = to;
            this.found = found;
        }

        public String getRawResult() {
            return found.get();
        }

        public void compute() {
            int lo = from, hi = to;
            while (found.get() == null && hi >= lo) {
                if (hi - lo >= 2) {
                    int mid = (lo + hi) >>> 1;
                    addToPendingCount(1);
                    new Find(this, words, mid, hi, found).fork();
                    hi = mid;
                }
                else {
                    String word = words[lo];
                    if (word.startsWith("q") && found.compareAndSet(null, word)) {
                        quietlyCompleteRoot();
                    }
                    break;
                }
            }
            tryComplete();
        }
    }

    static final List<String> log = Collections.synchronizedList(new ArrayList<>());

    static class Step extends CountedCompleter<String> {
        final String name;
        String result;

        Step(CountedCompleter<?> parent, String name, int pending) {
            super(parent, pending);
            this.name = name;
        }

        Step(String name) {
            this.name = name;
        }

        public void compute() {
            log.add("compute " + name);
        }

        public void onCompletion(CountedCompleter<?> caller) {
            log.add("completion of " + name + " by " + ((Step) caller).name);
        }

        public boolean onExceptionalCompletion(Throwable ex, CountedCompleter<?> caller) {
            log.add("exceptional completion of " + name + " by " + ((Step) caller).name + ": " + ex);
            return !name.startsWith("stop");
        }

        public String getRawResult() {
            return result;
        }

        protected void setRawResult(String value) {
            result = value;
        }
    }

    static void dump(String title) {
        System.out.println(title + ": " + log);
        log.clear();
    }

    public static void main(String[] args) throws Exception {
        int[] data = new int[100];
        for (int i = 0; i < data.length; i++) data[i] = i + 1;
        System.out.println(new Sum(null, data, 0, data.length).invoke());
        ForkJoinPool pool = new ForkJoinPool(3);
        System.out.println(pool.invoke(new Sum(null, data, 0, data.length)));
        Sum forked = new Sum(null, data, 0, 50);
        pool.submit(forked);
        System.out.println(forked.get() + " " + forked.join() + " " + forked.isDone() + " " + forked.isCompletedNormally() + " " + forked.getPendingCount() + " " + forked.getCompleter() + " " + (forked.getRoot() == forked) + " " + (forked.left.getRoot() == forked) + " " + (forked.left.getCompleter() == forked));
        AtomicInteger hits = new AtomicInteger();
        new Each(null, 0, 64, hits).invoke();
        System.out.println(hits.get());
        String[] words = { "apple", "pear", "quince", "plum", "fig", "kiwi", "lime", "date" };
        System.out.println(pool.invoke(new Find(null, words, 0, words.length - 1, new AtomicReference<>())));
        System.out.println(pool.invoke(new Find(null, new String[] { "a", "b", "c" }, 0, 2, new AtomicReference<>())));
        // the counts, step by step
        Step root = new Step("root");
        Step mid = new Step(root, "mid", 1);
        Step leaf = new Step(mid, "leaf", 0);
        Step other = new Step(mid, "other", 0);
        root.setPendingCount(1);
        System.out.println(root.getPendingCount() + " " + mid.getPendingCount() + " " + leaf.getPendingCount() + " " + (leaf.getRoot() == root) + " " + root.isDone());
        leaf.tryComplete();
        dump("leaf.tryComplete");
        System.out.println(root.getPendingCount() + " " + mid.getPendingCount() + " " + leaf.isDone() + " " + mid.isDone() + " " + root.isDone());
        other.tryComplete();
        dump("other.tryComplete");
        System.out.println(root.getPendingCount() + " " + mid.getPendingCount() + " " + other.isDone() + " " + mid.isDone() + " " + root.isDone());
        mid.tryComplete();
        dump("mid.tryComplete");
        System.out.println(root.getPendingCount() + " " + mid.isDone() + " " + root.isDone() + " " + root.join());
        // propagateCompletion calls no onCompletion; complete calls it of the task alone
        Step r2 = new Step("r2");
        Step m2 = new Step(r2, "m2", 0);
        Step l2 = new Step(m2, "l2", 0);
        l2.propagateCompletion();
        dump("propagateCompletion");
        System.out.println(l2.isDone() + " " + m2.isDone() + " " + r2.isDone());
        Step r3 = new Step("r3");
        Step m3 = new Step(r3, "m3", 5);
        Step l3 = new Step(m3, "l3", 0);
        r3.addToPendingCount(1);
        m3.complete("given");
        dump("complete");
        System.out.println(m3.isDone() + " " + m3.getRawResult() + " " + m3.getPendingCount() + " " + r3.isDone() + " " + r3.getPendingCount() + " " + l3.isDone());
        m3.complete("again");
        dump("complete again");
        System.out.println(m3.getRawResult() + " " + r3.isDone() + " " + r3.getPendingCount());
        // firstComplete / nextComplete, the counts' own members
        Step r4 = new Step("r4");
        Step m4 = new Step(r4, "m4", 1);
        System.out.println((m4.firstComplete() == null) + " " + m4.getPendingCount() + " " + (m4.firstComplete() == m4) + " " + (m4.nextComplete() == r4) + " " + r4.isDone() + " " + (r4.nextComplete() == null) + " " + r4.isDone() + " " + m4.isDone());
        dump("first/next");
        Step c = new Step("c");
        c.setPendingCount(3);
        System.out.println(c.compareAndSetPendingCount(2, 9) + " " + c.compareAndSetPendingCount(3, 1) + " " + c.decrementPendingCountUnlessZero() + " " + c.decrementPendingCountUnlessZero() + " " + c.getPendingCount());
        c.addToPendingCount(-4);
        System.out.println(c.getPendingCount());
        // quietlyCompleteRoot from a leaf
        Step r5 = new Step("r5");
        Step l5 = new Step(new Step(r5, "m5", 3), "l5", 2);
        l5.quietlyCompleteRoot();
        dump("quietlyCompleteRoot");
        System.out.println(r5.isDone() + " " + l5.isDone() + " " + l5.getCompleter().isDone());
        // exceptions climb while onExceptionalCompletion says so
        Step r6 = new Step("r6");
        Step stop = new Step(r6, "stop", 1);
        Step m6 = new Step(stop, "m6", 1);
        Step l6 = new Step(m6, "l6", 0);
        l6.completeExceptionally(new IllegalStateException("broken"));
        dump("completeExceptionally");
        System.out.println(l6.isCompletedAbnormally() + " " + m6.isCompletedAbnormally() + " " + stop.isCompletedAbnormally() + " " + r6.isDone() + " " + m6.getException() + " " + tried(m6::join) + " " + stop.getPendingCount());
        CountedCompleter<Void> thrower = new CountedCompleter<Void>(new Step("above")) {
            public void compute() {
                throw new IllegalArgumentException("in compute");
            }
            public boolean onExceptionalCompletion(Throwable ex, CountedCompleter<?> caller) {
                log.add("thrower sees " + ex + " " + (caller == this));
                return true;
            }
        };
        System.out.println(tried(thrower::invoke) + " " + thrower.getCompleter().isCompletedAbnormally() + " " + tried(() -> thrower.getCompleter().join()));
        dump("thrown in compute");
        System.out.println(tried(() -> pool.invoke(new CountedCompleter<Void>() {
            public void compute() {
                throw new IllegalArgumentException("in a pool");
            }
        })));
        // a completer whose compute ends without completing is not done
        Step open = new Step("open");
        open.setPendingCount(1);
        ForkJoinTask<String> task = pool.submit(open);
        Thread.sleep(50);
        System.out.println(task.isDone() + " " + open.getPendingCount());
        open.tryComplete();
        System.out.println(task.isDone() + " " + open.getPendingCount());
        open.tryComplete();
        System.out.println(task.isDone() + " " + task.get());
        dump("open");
        // helpComplete and the rest
        Step h = new Step("h");
        h.helpComplete(4);
        System.out.println(h.isDone() + " " + h.getRawResult());
        pool.shutdown();
    }
}
