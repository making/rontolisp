import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

// What the subset does not take of newUpdater, on a JDK alone: a class literal of a
// primitive or an array type, a name that is no constant, a field out of the caller's
// reach (refused by name when the program is lowered); and a swap of two boxes of one
// large value, which are two objects on a JDK and one here.
public class Updaters3 {

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

        volatile int[] cells;

        volatile Integer boxed;

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
        attempt("reference: a long as long", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, long.class, "total"));
        attempt("reference: an int as int, no such field", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, int.class, "nope"));
        attempt("reference: an int as int, not volatile", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, int.class, "plain"));
        attempt("reference: a reference as int", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, int.class, "next"));
        attempt("another class's private field", () -> Far.hidden());
        attempt("another class's package field", () -> AtomicIntegerFieldUpdater.newUpdater(Far.class, "open"));
        attempt("reference: an array", () -> AtomicReferenceFieldUpdater.newUpdater(Node.class, int[].class, "cells"));
        attempt("no name", () -> AtomicIntegerFieldUpdater.newUpdater(Node.class, null));
        // two boxes of one large value are two objects on a JDK
        AtomicReferenceFieldUpdater<Node, Integer> boxed = AtomicReferenceFieldUpdater.newUpdater(Node.class,
                Integer.class, "boxed");
        Node node = new Node();
        boxed.set(node, 1000);
        System.out.println(boxed.compareAndSet(node, 1000, 5) + " " + boxed.compareAndSet(node, node.boxed, 5));
    }

}

class Far {

    private volatile int hidden;

    volatile int open;

    static Object hidden() {
        return Near.reach();
    }

}

class Near {

    static Object reach() {
        return AtomicIntegerFieldUpdater.newUpdater(Far.class, "hidden");
    }

}
