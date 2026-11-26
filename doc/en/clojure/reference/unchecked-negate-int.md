# unchecked-negate-int

`(unchecked-negate-int x)`

Casts its arguments to int (a double or a ratio truncates; one outside the 32-bit range, `nil` and non-numbers signal) and answers minus `x` wrapped to 32 bits, so `(unchecked-negate-int -2147483648)` is `-2147483648`. As a value a one-argument function.

```clojure
(println (unchecked-negate-int -2147483648)) ; -2147483648
```
