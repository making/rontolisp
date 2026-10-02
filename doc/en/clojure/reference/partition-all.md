# partition-all

`(partition-all n coll)` / `(partition-all n step coll)`

Answers `coll`'s seq in groups of `n`, stepping by `step` (`n` without one); unlike
`partition`, the short tail is kept. A lazy input answers a lazy seq, a strict one a
strict list. A non-positive size or step signals (the oracle answers an endless seq
of `()`), and the one-argument transducer arity is refused by name. As a value it
takes two or three arguments.

```clojure
(println (partition-all 2 [1 2 3 4 5])) ; ((1 2) (3 4) (5))
(println (partition-all 2 1 [1 2 3])) ; ((1 2) (2 3) (3))
```
