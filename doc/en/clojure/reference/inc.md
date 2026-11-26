# inc

`(inc x)`

One more. Works as a function value, so `map`/`filter` take it bare. A direct call wraps
mod 2^64 while `*unchecked-math*` is truthy; the function value keeps the plain verb on
every flag.

```clojure
(println (inc 5)) ; 6
(println (map inc '(1 2))) ; (2 3)
```
