# sorted-map-by

`(sorted-map-by comparator k v ...)`

`clojure.core/sorted-map-by`: a sorted map ordered by `comparator`, a function of two
keys read like the oracle's: a number orders by its integer part's sign (`compare`,
`(- a b)`; `0.5` is equal), `true` puts the first key before the second and `false`
asks the reversed call (`<`, `>`). Keys it calls equal are one key. Every verb keeps the
comparator. The keys are not checked the way `sorted-map` checks them, and anything but
a function as the comparator signals. As a value a function of a comparator and pairs.

```clojure
(println (sorted-map-by > 1 :a 2 :b 3 :c))         ; {3 :c, 2 :b, 1 :a}
(prn (sorted-map-by #(compare %2 %1) "b" 1 "a" 2)) ; {"b" 1, "a" 2}
```
