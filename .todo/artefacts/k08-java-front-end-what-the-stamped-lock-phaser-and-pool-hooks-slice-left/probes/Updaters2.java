import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

// The order of newUpdater's refusals and an updater's class.
public class Updaters2 {

    static class Node {

        long wide;

        int plain;

        String text;

        static volatile int shared;

        static int still;

        static volatile long sharedWide;

        static volatile Node sharedNode;

        static Node stillNode;

        volatile int hits;

        volatile long total;

        volatile Node next;

        volatile java.util.List<String> names;

        volatile Runnable task;

        volatile boolean flag;

        volatile char letter;

        volatile double real;

    }

    interface Shape {

        int ZERO = 0;

    }

    enum Level {

        LOW, HIGH;

        volatile int seen;

    }

    static void attempt(String what, Supplier<Object> made) {
        try {
            Object answer = made.get();
            System.out.println(what + ": made " + answer.getClass().getName().replaceAll("@.*", ""));
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e + " / " + e.getCause());
        }
        catch (Error e) {
            System.out.println(what + ": error " + e);
        }
    }

    public static void main(String[] args) {
        attempt("int: a long, not volatile", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "wide"));
        attempt("int: static, not volatile", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "still"));
        attempt("int: static volatile long", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "sharedWide"));
        attempt("int: a boolean", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "flag"));
        attempt("int: a char", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "letter"));
        attempt("int: an interface's constant", () -> AtomicIntegerFieldUpdater.newUpdater(Shape.class, "ZERO"));
        attempt("int: an enum's field", () -> AtomicIntegerFieldUpdater.newUpdater(Level.class, "seen"));
        attempt("int: an enum's constant", () -> AtomicIntegerFieldUpdater.newUpdater(Level.class, "LOW"));
        attempt("long: an int, not volatile", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "plain"));
        attempt("long: static volatile long", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "sharedWide"));
        attempt("long: a double", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "real"));
        attempt("long", () -> AtomicLongFieldUpdater.newUpdater(Node.class, "total"));
        attempt("reference: another class, not volatile", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "text"));
        attempt("reference: static volatile", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "sharedNode"));
        attempt("reference: static, not volatile", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "stillNode"));
        attempt("reference: a parameterized type", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, java.util.List.class, "names"));
        attempt("reference: an interface", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Runnable.class, "task"));
        attempt("int", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, "hits"));
        attempt("reference", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, Node.class, "next"));
        AtomicIntegerFieldUpdater<Level> seen = AtomicIntegerFieldUpdater.newUpdater(Level.class, "seen");
        System.out.println(seen.incrementAndGet(Level.HIGH) + " " + Level.HIGH.seen + " " + Level.LOW.seen);
        AtomicIntegerFieldUpdater<Node> hits = AtomicIntegerFieldUpdater.newUpdater(Node.class, "hits");
        System.out.println(hits.toString().replaceAll("@.*", "") + " " + (hits.hashCode() == hits.hashCode()));
    }

}
