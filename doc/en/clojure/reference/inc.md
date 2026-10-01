# inc

`(inc x)`

One more. Works as a function value, so `map`/`filter` take it bare.

```clojure
(println (inc 5)) ; 6
(println (map inc '(1 2))) ; (2 3)
```
