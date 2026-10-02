# partition-all

`(partition-all n coll)` / `(partition-all n step coll)` / `(partition-all n)`

Answers `coll`'s seq in groups of `n`, stepping by `step` (`n` without one); unlike
`partition`, the short tail is kept. A lazy input answers a lazy seq, a strict one a
strict list. A non-positive size or step signals (the oracle answers an endless seq
of `()`). `(partition-all n)` is its [transducer](transducers.md): it steps vectors and
flushes the short tail at the end. As a value it takes one, two or three arguments.

```clojure
(println (partition-all 2 [1 2 3 4 5])) ; ((1 2) (3 4) (5))
(println (partition-all 2 1 [1 2 3])) ; ((1 2) (2 3) (3))
(println (into [] (partition-all 2) [1 2 3])) ; [[1 2] [3]]
```
