# unchecked-remainder-int

`(unchecked-remainder-int a b)`

Casts its arguments to int as `unchecked-add-int` does and answers the remainder, signed like `a`. A zero divisor signals. As a value a two-argument function.

```clojure
(println (unchecked-remainder-int -7 2)) ; -1
```
