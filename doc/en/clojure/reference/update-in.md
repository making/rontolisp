# update-in

`(update-in m keys f args...)`

Answers the nested update down the key path, building nothing: a missing
level applies `f` to `nil` (which signals for arithmetic, like the oracle). The
path is any seqable; with no keys, updates under `nil`, like the oracle. A vector
level steps by index, like [assoc](assoc.md).

```clojure
(println (update-in {:a {:b 1}} [:a :b] inc)) ; {:a {:b 2}}
(println (update-in [[0 0] [0 0]] [1 0] inc)) ; [[0 0] [1 0]]
```
