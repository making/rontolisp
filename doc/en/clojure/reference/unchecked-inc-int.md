# unchecked-inc-int

`(unchecked-inc-int x)`

Casts its arguments to int (a double or a ratio truncates; one outside the 32-bit range, `nil` and non-numbers signal) and answers `x` plus one wrapped to 32 bits, so `(unchecked-inc-int 2147483647)` is `-2147483648`. As a value a one-argument function.

```clojure
(println (unchecked-inc-int 2147483647)) ; -2147483648
```
