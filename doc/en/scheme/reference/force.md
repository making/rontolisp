# force

`(force promise)`

Answers the value of `promise`, evaluating its expression the first time and remembering the value. `force` of an object that is not a promise answers the object itself.

```scheme
(force (delay (+ 1 2))) ; => 3
(force (make-promise 7)) ; => 7
(force 5) ; => 5
```
