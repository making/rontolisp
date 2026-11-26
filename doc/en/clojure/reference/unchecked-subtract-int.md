# unchecked-subtract-int

`(unchecked-subtract-int a b)`

Casts its arguments to int (a double or a ratio truncates; one outside the 32-bit range, `nil` and non-numbers signal) and answers the difference wrapped to 32 bits, so `(unchecked-subtract-int -2147483648 1)` is `2147483647`. As a value a two-argument function.

```clojure
(println (unchecked-subtract-int -2147483648 1)) ; 2147483647
```
