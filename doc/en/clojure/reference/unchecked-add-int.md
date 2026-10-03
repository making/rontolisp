# unchecked-add-int

`(unchecked-add-int a b)`

Casts its arguments to int (a double or a ratio truncates; one outside the 32-bit range, `nil` and non-numbers signal) and answers the sum wrapped to 32 bits, so `(unchecked-add-int 2147483647 1)` is `-2147483648`. As a value a two-argument function.

```clojure
(println (unchecked-add-int 2147483647 1)) ; -2147483648
```
