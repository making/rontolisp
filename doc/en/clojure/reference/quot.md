# quot

`(quot n d)`

Integer division truncating toward zero; `(quot -7 2)` is `-3`. As a value, a
two-argument lambda over the same operation, so `apply` and `map` take it bare with the
Clojure argument order.

```clojure
(println (quot 7 2)) ; 3
(println (quot -7 2)) ; -3
(println (apply quot [7 2])) ; 3
```
