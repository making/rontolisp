# get-in

`(get-in m keys)` / `(get-in m keys dflt)`

Answers the read folded down the key path, which is any seqable (no keys answer
`m`). The default answers at the first missing level, so a missing middle answers it
too and `(get-in {} [:a :b] {:b 1})` is `{:b 1}`, like the oracle. A vector level
reads by index, like [get](get.md).

```clojure
(println (get-in {:a {:b 1}} [:a :b])) ; 1
(println (get-in {} [:a :b] :dflt)) ; :dflt
(println (get-in [[0 1] [2 3]] [1 0])) ; 2
```
