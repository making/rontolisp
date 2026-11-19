import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

// The field updaters: what each member answers, and what newUpdater refuses.
public class Updaters {

    static class Node {

        volatile int hits;

        volatile long total;

        volatile Node next;

        volatile String label = "none";

        volatile Object any;

        volatile Integer boxed;

        int plain;

        long wide;

        String text;

        static volatile int shared;

        final int fixed = 1;

        private volatile int hidden;

        protected volatile int guarded;

        volatile short small;

        volatile int[] cells;

        static final AtomicIntegerFieldUpdater<Node> HIDDEN = AtomicIntegerFieldUpdater.newUpdater(Node.class,
                "hidden");

        int hidden() {
            return this.hidden;
        }

    }

    static class Leaf extends Node {

        volatile int own;

    }

    static class Other {

        volatile int hits;

    }

    static final AtomicIntegerFieldUpdater<Node> HITS = AtomicIntegerFieldUpdater.newUpdater(Node.class, "hits");

    static final AtomicLongFieldUpdater<Node> TOTAL = AtomicLongFieldUpdater.newUpdater(Node.class, "total");

    static final AtomicReferenceFieldUpdater<Node, Node> NEXT = AtomicReferenceFieldUpdater.newUpdater(Node.class,
            Node.class, "next");

    static void attempt(String what, Supplier<Object> made) {
        try {
            Object answer = made.get();
            System.out.println(what + ": made " + (answer != null));
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e + " / " + e.getCause());
        }
        catch (Error e) {
            System.out.println(what + ": error " + e);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void main(String[] args) throws InterruptedException {
        Node node = new Node();
        // an int
        System.out.println(HITS.get(node) + " " + HITS.incrementAndGet(node) + " " + HITS.getAndIncrement(node) + " "
                + HITS.addAndGet(node, 10) + " " + HITS.getAndAdd(node, -4) + " " + HITS.decrementAndGet(node) + " "
                + HITS.getAndDecrement(node) + " " + HITS.getAndSet(node, 40) + " " + node.hits);
        System.out.println(HITS.compareAndSet(node, 40, 41) + " " + HITS.compareAndSet(node, 40, 42) + " "
                + HITS.weakCompareAndSet(node, 41, 43) + " " + node.hits + " "
                + HITS.updateAndGet(node, x -> x * 2) + " " + HITS.getAndUpdate(node, x -> x + 1) + " "
                + HITS.accumulateAndGet(node, 5, (x, y) -> x - y) + " " + HITS.getAndAccumulate(node, 3, Math::max)
                + " " + HITS.get(node));
        HITS.set(node, Integer.MAX_VALUE);
        HITS.lazySet(node, HITS.get(node));
        System.out.println(HITS.incrementAndGet(node) + " " + HITS.addAndGet(node, -1) + " " + node.hits);
        // a long
        System.out.println(TOTAL.get(node) + " " + TOTAL.incrementAndGet(node) + " " + TOTAL.getAndIncrement(node)
                + " " + TOTAL.addAndGet(node, 1L << 40) + " " + TOTAL.getAndAdd(node, -4) + " "
                + TOTAL.decrementAndGet(node) + " " + TOTAL.getAndDecrement(node) + " "
                + TOTAL.getAndSet(node, Long.MAX_VALUE) + " " + node.total);
        System.out.println(TOTAL.incrementAndGet(node) + " " + TOTAL.compareAndSet(node, Long.MIN_VALUE, 7) + " "
                + TOTAL.compareAndSet(node, 8, 9) + " " + TOTAL.weakCompareAndSet(node, 7, 8) + " "
                + TOTAL.updateAndGet(node, x -> x << 33) + " " + TOTAL.getAndUpdate(node, x -> x + 1) + " "
                + TOTAL.accumulateAndGet(node, 5, (x, y) -> x - y) + " " + TOTAL.getAndAccumulate(node, 3, Math::min)
                + " " + TOTAL.get(node));
        TOTAL.set(node, 12);
        TOTAL.lazySet(node, TOTAL.get(node) + 1);
        System.out.println(node.total);
        // a reference
        Node second = new Node();
        Leaf leaf = new Leaf();
        System.out.println((NEXT.get(node) == null) + " " + NEXT.compareAndSet(node, null, second) + " "
                + NEXT.compareAndSet(node, null, leaf) + " " + (NEXT.get(node) == second) + " "
                + (NEXT.getAndSet(node, leaf) == second) + " " + (node.next == leaf) + " "
                + NEXT.weakCompareAndSet(node, leaf, null) + " " + (node.next == null));
        NEXT.set(node, second);
        NEXT.lazySet(second, node);
        System.out.println((NEXT.updateAndGet(node, n -> n.next) == node) + " "
                + (NEXT.getAndUpdate(node, n -> null) == node) + " "
                + (NEXT.accumulateAndGet(node, leaf, (old, given) -> old == null ? given : old) == leaf) + " "
                + (NEXT.getAndAccumulate(node, second, (old, given) -> given) == leaf) + " " + (node.next == second));
        AtomicReferenceFieldUpdater<Node, String> label = AtomicReferenceFieldUpdater.newUpdater(Node.class,
                String.class, "label");
        String fresh = new String("none");
        System.out.println(label.get(node) + " " + label.compareAndSet(node, fresh, "x") + " "
                + label.compareAndSet(node, "none", "named") + " " + label.getAndSet(node, "again") + " "
                + node.label);
        AtomicReferenceFieldUpdater<Node, Integer> boxed = AtomicReferenceFieldUpdater.newUpdater(Node.class,
                Integer.class, "boxed");
        // two boxes of one small value are one object; of a large one they are two on a
        // JDK and one here (Updaters3)
        boxed.set(node, 100);
        System.out.println(boxed.get(node) + " " + boxed.compareAndSet(node, 101, 5) + " "
                + boxed.compareAndSet(node, node.boxed, 5) + " " + boxed.compareAndSet(node, 5, 6) + " "
                + node.boxed);
        // an object of a class under the updater's, a private field from its own class
        System.out.println(HITS.incrementAndGet(leaf) + " " + leaf.hits + " " + Node.HIDDEN.addAndGet(node, 3) + " "
                + node.hidden() + " " + TOTAL.addAndGet(leaf, 2));
        AtomicIntegerFieldUpdater<Leaf> own = AtomicIntegerFieldUpdater.newUpdater(Leaf.class, "own");
        System.out.println(own.incrementAndGet(leaf) + " " + leaf.own);
        // what an updater says of itself: nothing of the field
        System.out.println(HITS.equals(HITS) + " " + HITS.equals(AtomicIntegerFieldUpdater.newUpdater(Node.class, "hits")));
        // the wrong object
        AtomicIntegerFieldUpdater raw = HITS;
        AtomicLongFieldUpdater rawLong = TOTAL;
        AtomicReferenceFieldUpdater rawNext = NEXT;
        Object[] wrong = { new Other(), "text", null };
        for (Object object : wrong) {
            attempt("get of " + (object == null ? null : object.getClass().getSimpleName()), () -> raw.get(object));
            attempt("set", () -> {
                raw.set(object, 1);
                return 1;
            });
            attempt("compareAndSet", () -> raw.compareAndSet(object, 0, 1));
            attempt("incrementAndGet", () -> raw.incrementAndGet(object));
            attempt("long get", () -> rawLong.get(object));
            attempt("long addAndGet", () -> rawLong.addAndGet(object, 1));
            attempt("reference get", () -> rawNext.get(object));
            attempt("reference getAndSet", () -> rawNext.getAndSet(object, null));
        }
        // a value of the wrong class
        attempt("a value of another class", () -> rawNext.compareAndSet(node, second, "text"));
        attempt("set of another class", () -> {
            rawNext.set(node, "text");
            return 1;
        });
        attempt("getAndSet of another class", () -> rawNext.getAndSet(node, new Other()));
        attempt("expected of another class", () -> rawNext.compareAndSet(node, "text", null));
        System.out.println(node.next == second);
        // what newUpdater refuses
        attempt("no such field", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "nope"));
        attempt("not volatile", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "plain"));
        attempt("a long as an int", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "total"));
        attempt("a short as an int", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "small"));
        attempt("a reference as an int", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "next"));
        attempt("a static field", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "shared"));
        attempt("a final field", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "fixed"));
        attempt("a private field", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "hidden"));
        attempt("a protected field", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "guarded"));
        attempt("an inherited field", () -> AtomicIntegerFieldUpdater.newUpdater(Leaf.class, "hits"));
        attempt("long: no such field", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "nope"));
        attempt("long: not volatile", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "wide"));
        attempt("long: an int", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "hits"));
        attempt("long: a static field", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "shared"));
        attempt("reference: no such field", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "nope"));
        attempt("reference: not volatile", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, String.class, "text"));
        attempt("reference: an int", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Integer.class, "hits"));
        attempt("reference: a class above the field's", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Object.class, "next"));
        attempt("reference: a class under the field's", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Leaf.class, "next"));
        attempt("reference: another class", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, String.class, "next"));
        attempt("reference: Object", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Object.class, "any"));
        attempt("reference: a static field", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "shared"));
        // four threads, one field of one object
        Node counted = new Node();
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            new Thread(() -> {
                for (int j = 0; j < 2000; j++) {
                    HITS.incrementAndGet(counted);
                    TOTAL.addAndGet(counted, 3);
                    int seen;
                    do {
                        seen = Node.HIDDEN.get(counted);
                    }
                    while (!Node.HIDDEN.compareAndSet(counted, seen, seen + 2));
                }
                done.countDown();
            }).start();
        }
        done.await();
        System.out.println(counted.hits + " " + counted.total + " " + counted.hidden());
    }

}
