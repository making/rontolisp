# or

`(or expr...)`

Evaluates the forms in order and answers the first truthy one, or the last value when
all are falsey. `nil` and `false` are both falsey, so `(or nil nil 3)` passes over
both and answers `3`.

```clojure
(println (or nil nil 3))  ; 3
(println (or false 2))    ; 2
(println (or nil false))  ; false
```
