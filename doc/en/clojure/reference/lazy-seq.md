# lazy-seq

`(lazy-seq body...)`

Answers a lazy seq: the body (an implicit `do`) runs the first time the seq is realized
-- through `first`/`rest`/`next`/`seq`/`take`/`drop`/`map`/`filter`/`concat` -- at most
once per seq object, and its answer is the seq's contents. Realization is per element,
so infinite seqs terminate behind `take`; a body that never gets taken never runs.
There is no chunking: every element realizes singly. The body is its own zero-arity
`recur` target: a `recur` in tail position re-runs the thunk itself (a recur with
arguments is an arity error). A body answering another lazy seq is forced in a loop, like
the oracle, so a body that skips by answering the next `lazy-seq` runs in constant stack
however long the run.

Printing realizes a lazy seq, like the oracle: an empty one prints `()`, and an infinite
one prints without end, so print an infinite seq behind `take`.

```clojure
(println (take 5 (lazy-seq (cons 1 (lazy-seq (cons 2 nil)))))) ; (1 2)
(def powers-of-3 (lazy-cat [1] (map #(* 3 %) powers-of-3)))
(println (take 6 powers-of-3)) ; (1 3 9 27 81 243)
```
