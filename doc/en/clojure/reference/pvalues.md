# pvalues

`(pvalues expr...)`

Evaluates each expression in order on the calling thread and answers a seq of
the values. There is no thread pool on any backend (like [`pmap`](pmap.md),
which is `map`), so the evaluation is sequential: a throwing expression stops
the later ones with its own exception, and a thread-local write stays visible
where the oracle's pool thread refuses it. An empty `pvalues` answers `nil`,
where the oracle's empty lazy seq prints `()`. As a value it stays a macro
(`(def x pvalues)` refuses): macro var values come later, so their pinning
stays there.

```clojure
(println (pvalues 1 (+ 1 2))) ; (1 3)
```
