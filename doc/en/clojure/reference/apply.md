# apply

`(apply f x... args)`

Calls `f` with the leading arguments spread in front of the seq view of the last, like
the oracle and CL `apply`. Works as a value, so it composes with `reduce`.

```clojure
(println (apply max '(3 9 4)))  ; 9
(println (apply max [3 9 4]))   ; 9
(println (apply + 1 2 [3 4]))   ; 10
(println (apply concat [[1 2] [3]])) ; (1 2 3)
```
