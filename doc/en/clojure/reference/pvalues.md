# pvalues

`(pvalues expr...)`

Runs one future per expression on the interpreter and the JVM -- each expression
on its own thread, conveying the creation-time bindings -- and dereferences them
in order, so the values answer in order like the oracle's while the branches
overlap. A failure surfaces at the deref in order, after the other branches were
started, wrapped in the oracle's `ExecutionException` over its condition. On the
WASM legs, which have no threads, each expression runs in order on the calling
thread: the same printed seq. An empty `pvalues` answers `nil`,
where the oracle's empty lazy seq prints `()`. As a value it answers the
run-time once-expansion delegate (see [bound-fn](bound-fn.md)); the oracle
refuses a bare macro name in value position at compile time.

```clojure
(println (pvalues 1 (+ 1 2))) ; (1 3)
```
