# f33. Clojure: a fn's answer reaches Java as the oracle's own object

Difficulty: High

A fn Java calls (the functional interface a fn converts to at a host call) answers Java through
`%clojure-host-fn-answer`, which converts a byte array only (`.kb/clojure-frontend.md`, "Java
interop", the f24 bullet): the fn cannot know whether the method answers anything, and
converting an answer a void method drops would realize a lazy seq or walk a collection on every
call (`(.forEach l (fn [x] (swap! acc conj x)))` turns quadratic). A `proxy` method answering a
reference already hands Java the value's own object. Measured 2026-10-10 against clj 1.12.6,
the interpreter and the JVM alike:

| program | oracle | here |
|---|---|---|
| `(.computeIfAbsent (java.util.HashMap.) "a" (fn [k] {:a 1}))` | `{:a 1}` | `java:reify: cannot return #<HASH-TABLE :TEST EQUAL :COUNT 1> as class java.lang.Object from java.util.function.Function.apply` |
| `(.computeIfAbsent (java.util.HashMap.) "a" (fn [k] :kw))` | `:kw` | `java:reify: cannot return (:C%KEYWORD "kw") as class java.lang.Object ...` |
| `(let [m (java.util.HashMap.)] (.computeIfAbsent m "b" (fn [k] [1 2])) (vector? (.get m "b")))` | `true` | `false` (an `ArrayList` copy) |
| `(let [f (fn [a b] 0)] (identical? f (.comparator (java.util.TreeSet. f))))` | `true` | `false` |

## What decides the design

- Only the `java:` layer knows the slot's return type where the answer is converted (`_jimpl$K`,
  the interpreter's `ImplementationHandler`, the bridge's `callback`): the conversion belongs
  there, applied to a non-void answer only. A language hook -- a function the form or call
  names, called on the answer before it is marshalled -- is a new `java:` convention with three
  copies and a parity pin.
- The last row: the oracle's fn IS a `Runnable`, `Callable` and `Comparator`, while a fn it
  adapts to another functional interface is not handed back as itself
  (`.getUncaughtExceptionHandler` answers the adapter). A function converted to one of those
  three could stand for the fn (`RontoJavaValue`, as a face stands for its value) -- for the
  fn the program handed over, not for the `%clojure-host-fn` adapter.

## Plan

1. `ClojureInteropTest` rows for the table, interpreter and JVM.
2. The hook on the three copies (and the stand-in, if the measurement keeps it), then
   `%clojure-host-fn-answer`'s byte-array arm folded into it.
3. `doc/*/clojure/deviations.md`'s fn bullet.
