# lazy-seq

`(lazy-seq body...)`

Answers a lazy seq: the body (an implicit `do`) runs the first time the seq is realized
-- through `first`/`rest`/`next`/`seq`/`take`/`drop`/`map`/`filter`/`concat` -- at most
once per seq object, and its answer is the seq's contents. Realization is per element,
so infinite seqs terminate behind `take`; a body that never gets taken never runs.
There is no chunking: every element realizes singly. The body is its own zero-arity
`recur` target: a `recur` in tail position re-runs the thunk itself (a recur with
arguments is an arity error).

A lazy seq prints as `#<LazySeq>` (a lazy tail truncates with `...`) instead of hanging
the printer, so only print a lazy seq behind `take`.

```clojure
(println (take 5 (lazy-seq (cons 1 (lazy-seq (cons 2 nil)))))) ; (1 2)
(def fibs (lazy-cat [0 1] (map + fibs (rest fibs))))
(println (take 8 fibs)) ; (0 1 1 2 3 5 8 13)
```
