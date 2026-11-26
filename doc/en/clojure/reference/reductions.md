# reductions

`(reductions f coll)` / `(reductions f init coll)`

The running reductions of `f`: `init` (or the first member) and each reduction after it,
stopping past a reduced answer's unwrapped value, like the oracle's. A lazy input answers a
lazy seq, a strict one a strict list. As a value two or three arguments.

```clojure
(println (reductions + [1 2 3])) ; (1 3 6)
(println (reductions + 10 [1 2])) ; (10 11 13)
```
