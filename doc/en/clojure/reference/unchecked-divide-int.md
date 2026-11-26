# unchecked-divide-int

`(unchecked-divide-int a b)`

Casts its arguments to int as `unchecked-add-int` does and answers the quotient truncated toward zero; only `-2147483648` divided by `-1` wraps, to `-2147483648`. A zero divisor signals. As a value a two-argument function.

```clojure
(println (unchecked-divide-int -7 2)) ; -3
```
