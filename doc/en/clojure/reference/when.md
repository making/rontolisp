# when

`(when test body...)`

Evaluates `test` and, when it is truthy, evaluates the body forms and answers the
last; otherwise answers `nil` without touching the body. `nil` and `false` are both
falsey, like every test here.

```clojure
(println (when true 1 2))  ; 2
(println (when nil 1 2))   ; nil
(println (when false 1))   ; nil
```
