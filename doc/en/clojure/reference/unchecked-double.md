# unchecked-double

`(unchecked-double x)`

Answers a number widened to a double, like `double`. A character, `nil` and other non-numbers signal. As a value a one-argument function.

```clojure
(println (unchecked-double 1) (unchecked-double 1/2)) ; 1.0 0.5
```
