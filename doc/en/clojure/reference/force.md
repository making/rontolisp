# force

`(force x)`

The value of the [delay](delay.md) `x`, running its body the first time; any other value is
answered itself. As a value a one-argument function.

```clojure
(println (force (delay (+ 1 2))) (force 5) (map force [(delay :a) :b])) ; 3 5 (:a :b)
```
