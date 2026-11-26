# mod

`(mod n d)`

The floor modulus: the result carries `d`'s sign, where `rem` carries `n`'s.

```clojure
(println (mod -7 2)) ; 1
(println (rem -7 2)) ; -1
```
