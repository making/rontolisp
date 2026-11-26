# unchecked-dec-int

`(unchecked-dec-int x)`

Casts its arguments to int (a double or a ratio truncates; one outside the 32-bit range, `nil` and non-numbers signal) and answers `x` minus one wrapped to 32 bits, so `(unchecked-dec-int -2147483648)` is `2147483647`. As a value a one-argument function.

```clojure
(println (unchecked-dec-int -2147483648)) ; 2147483647
```
