# get-in

`(get-in m keys)` / `(get-in m keys dflt)`

Answers the read folded down the key vector, the default threaded through every
level (so a missing middle answers the default too), like the oracle. A vector
level reads by index, like [get](get.md). As a value the key sequence walked at run
time.

```clojure
(println (get-in {:a {:b 1}} [:a :b])) ; 1
(println (get-in {} [:a :b] :dflt)) ; :dflt
(println (get-in [[0 1] [2 3]] [1 0])) ; 2
```
