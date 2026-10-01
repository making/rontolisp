# update-in

`(update-in m keys f args...)`

Answers the nested update down the key vector, building nothing: a missing
level applies `f` to `nil` (which signals for arithmetic, like the oracle). An
empty key vector is refused by name. As a value the key sequence walked at run
time.

```clojure
(println (update-in {:a {:b 1}} [:a :b] inc)) ; {:a {:b 2}}
```
