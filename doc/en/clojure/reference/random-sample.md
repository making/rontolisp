# random-sample

`(random-sample prob coll)`

The members of `coll` kept with probability `prob`, the oracle's filter over its `rand`.
A lazy input answers a lazy seq, a strict one a strict list. As a value two arguments.

```clojure
(println (random-sample 1.0 [3 1 2])) ; (3 1 2)
```
