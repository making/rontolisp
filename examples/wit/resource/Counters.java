// counters in Java -- the world counters.lisp implements, as a class.
//
// It implements the world `counters` of wit/counters.wit: the interface `registry`
// is the member class Registry, and its resource `counter` the member class
// Registry.Counter, an ordinary class:
//
//   constructor(...)      new Counter(...)      the object made IS the resource
//   add: func(...)        add(...)              an instance method, on that object
//   merge: static func    Counter.merge(...)    a static method
//   (a handle released)   close()               optional (AutoCloseable)
//
// The host holds a handle of the object, and every method that takes or answers a
// `counter` gets or gives the object -- alone, in a List, in a result, anywhere.
//
//   API="--java-source-path ../../../rontolisp-java-api/src/main/java"
//   rontolisp Counters.java $API -o counters.wasm --component
//
// app.lisp calls the two interfaces from another component, whichever language
// implements them -- see README.md.

import java.util.List;

import am.ik.rontolisp.wit.WitException;
import am.ik.rontolisp.wit.WitExport;

@WitExport(value = "counters", wit = "wit/counters.wit")
public class Counters {

    // The counters made and not yet released, for `live`.
    static int live;

    public static class Registry {

        public record Reading(String name, int value) {
        }

        public static class Counter implements AutoCloseable {

            String name;

            int value;

            // WIT: constructor(name: string, start: u32)
            public Counter(String name, int start) {
                live++;
                this.name = name;
                this.value = start;
            }

            // WIT: add: func(by: u32) -> u32
            public int add(int by) {
                this.value += by;
                return this.value;
            }

            // WIT: read: func() -> reading
            public Reading read() {
                return new Reading(this.name, this.value);
            }

            // WIT: merge: static func(a: counter, b: borrow<counter>) -> counter
            // `a` arrives owned: the caller's handle of it is gone, and the object is
            // this method's to keep, change or hand back. `b` is only lent.
            public static Counter merge(Counter a, Counter b) {
                a.name = a.name + "+" + b.name;
                a.value += b.value;
                return a;
            }

            // Called when a handle of the object is released. A counter handed over
            // to an export (merge's `a`, leader's list) is not released: it moved here.
            @Override
            public void close() {
                live--;
            }
        }

        // WIT: total: func(counters: list<borrow<counter>>) -> u32
        public static int total(List<Counter> counters) {
            int sum = 0;
            for (Counter c : counters) {
                sum += c.value;
            }
            return sum;
        }

        // WIT: leader: func(counters: list<counter>) -> result<counter, string>
        // The counters arrive owned. The leader goes back to the caller; the others
        // stay here with no handle left, so they are closed by hand.
        public static Counter leader(List<Counter> counters) {
            if (counters.isEmpty()) {
                throw new WitException("no counter to lead", "no counter to lead");
            }
            Counter best = counters.get(0);
            for (Counter c : counters) {
                if (c.value > best.value) {
                    best = c;
                }
            }
            for (Counter c : counters) {
                if (c != best) {
                    c.close();
                }
            }
            return best;
        }

        // WIT: live: func() -> u32
        public static int live() {
            return live;
        }
    }

    public static class Report {

        // WIT (interface report): summary: func(c: borrow<counter>) -> string
        public static String summary(Registry.Counter c) {
            return c.name + " is at " + c.value;
        }
    }
}
