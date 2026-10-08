# e66. Clojure: a fn passed where a Java interface is expected receives the method name first

Difficulty: Medium

A Clojure `fn` handed to a Java parameter of interface type goes through the `java:`
surface's auto-proxy (`.kb/java-interop.md`, "Implementing interfaces"): every method calls
the callable with the method NAME first, `java:proxy`'s meaning. A Clojure fn takes only the
method's arguments (the oracle's fns implement `Runnable`/`Callable`, and 1.12 converts a fn
to any functional interface), so each call is an arity error. Measured 2026-10-08 on the
interpreter and the JVM at the develop tip:

- `(.start (Thread. (fn [] (println "ran"))))` -- `Function expects 0 arguments, got 1`.
- `(.forEach (java.util.ArrayList. [1 2]) (fn [x] (println x)))` -- `expects 1 argument, got 2`.
- `(.compute (java.util.HashMap. {"a" 1}) "a" (fn [k v] (inc v)))` -- the same.

The oracle prints `ran`, `1 2`, `2`. A `(proxy [Runnable] [] (run [] ...))` works (it is the
name-first callable by design), so `locking`'s threaded pin in `ClojureInteropTest` spells
its thread that way.

## Plan

1. Decide where the conversion lives: a Clojure-side marker on a lowered fn reaching a
   `java:new`/`java:call` argument (wrap it in a `java:reify`-like adapter dropping the
   name), or a `java:` surface variant the Clojure lowering asks for. CL's `java:proxy`
   meaning must not change.
2. Pin on the interpreter and the JVM (`ClojureInteropTest`), including a non-SAM interface
   (what the oracle does there: measure).
3. `.kb/clojure-frontend.md` "Java interop"; `doc/en` + `doc/ja` interop page.
