# /

`(/ z)` `(/ z1 z2 ...)`

Divides, exactly when the arguments are: integer division that does not split evenly
answers a ratio, never a truncated double (`(/ 7 2)` is `7/2`). With one argument, the
reciprocal. A double argument makes the answer a double.

```clojure
(println (/ 7 2)) ; 7/2
(println (/ 8 2)) ; 4
(println (/ 2)) ; 1/2
```
