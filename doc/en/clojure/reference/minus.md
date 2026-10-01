# -

`(- z)` `(- z1 z2 ...)`

With one argument, the negation; with more, subtracts the rest from the first, left to
right. At least one argument is required.

```clojure
(println (- 10 4)) ; 6
(println (- 5)) ; -5
```
