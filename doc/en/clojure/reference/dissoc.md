# dissoc

`(dissoc m k ...)`

Answers a fresh map over `m`'s pairs minus the given keys; absent keys are ignored and
`m` is never mutated. `dissoc` of `nil` is `nil`. Of a record the type survives while every declared field is still present and drops to a plain map otherwise, like the oracle.

As a value a map plus a rest list of keys.

[`dissoc!`](dissoc-bang.md) removes keys from a transient map in place.

Deviation: misuse of a non-map may signal
the Common Lisp type error instead of the oracle's.

```clojure
(println (get (dissoc {:a 1 :b 2} :a) :a)) ; nil
(println (count (dissoc {:a 1 :b 2} :a :b))) ; 0
(println (dissoc nil :a))                  ; nil
(println (map dissoc [{:a 1 :b 2}] [:a]))  ; ({:b 2})
```
