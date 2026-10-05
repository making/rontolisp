# deref

`(deref ref)`

Reads an atom or volatile, the value inside a [`reduced`](reduced.md), or a [var](var.md)'s root. The reader form `@x` is the same operation. Works as a function
value, so `map`/`reduce` take it bare (one argument there).

On the interpreter and the JVM, a Java `java.util.concurrent.Future` from interop is read through its `get`, so a failed one signals `ExecutionException` and a cancelled one `CancellationException`. `(deref f ms timeout-val)` is `get` within `ms` milliseconds and answers `timeout-val` on `TimeoutException`; with any value that is no `Future` it signals `ClassCastException` (`nil`: `NullPointerException`), as the oracle's `IBlockingDeref` cast does.

```clojure
(def a (atom 1))
(println (deref a)) ; 1
(println @a) ; 1
(println (map deref [(atom 1) (atom 2)])) ; (1 2)
(println @(reduced 3)) ; 3
```
