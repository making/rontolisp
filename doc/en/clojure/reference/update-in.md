# update-in

`(update-in m keys f args...)`

Answers the nested update down the key vector, building nothing: a missing
level applies `f` to `nil` (which signals for arithmetic, like the oracle). An
empty key vector is refused by name. A vector level steps by index, like
[assoc](assoc.md). As a value the key sequence walked at run time.

```clojure
(println (update-in {:a {:b 1}} [:a :b] inc)) ; {:a {:b 2}}
(println (update-in [[0 0] [0 0]] [1 0] inc)) ; [[0 0] [1 0]]
```
