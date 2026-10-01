# +

`(+ x...)`

Sums the arguments, left to right; `(+)` is `0`. Exactness follows the arguments: integers
and ratios stay exact, a double makes the answer a double.

```clojure
(println (+ 1 2 3)) ; 6
(println (+ 1/2 1/3)) ; 5/6
(println (+)) ; 0
```
