# dissoc

`(dissoc m k ...)`

Answers a fresh map over `m`'s pairs minus the given keys; absent keys are ignored and
`m` is never mutated. `dissoc` of `nil` is `nil`.

Deviation: transients (`dissoc!`) are refused by name. Misuse of a non-map may signal
the Common Lisp type error instead of the oracle's.

```clojure
(println (get (dissoc {:a 1 :b 2} :a) :a)) ; nil
(println (count (dissoc {:a 1 :b 2} :a :b))) ; 0
(println (dissoc nil :a))                  ; nil
```
