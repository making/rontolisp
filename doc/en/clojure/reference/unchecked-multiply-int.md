# unchecked-multiply-int

`(unchecked-multiply-int a b)`

Casts its arguments to int (a double or a ratio truncates; one outside the 32-bit range, `nil` and non-numbers signal) and answers the product wrapped to 32 bits, so `(unchecked-multiply-int 65536 65536)` is `0`. As a value a two-argument function.

```clojure
(println (unchecked-multiply-int 65536 65536)) ; 0
```
