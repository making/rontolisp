# and

`(and expr...)`

Evaluates the forms in order and answers the first falsey one, or the last value when
all are truthy. `nil` and `false` are both falsey, like every test here.

```clojure
(println (and 1 2 3))    ; 3
(println (and 1 nil 3))  ; nil
(println (and 1 false 3)) ; false
```
