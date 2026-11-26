# if

`(if test then else?)`

Evaluates `test` and answers `then` when it is truthy, else `else` (or `nil` without
one). `nil` and `false` are both falsey and everything else is truthy -- the test is
an explicit null-or-false check, bound once to a temporary.

```clojure
(println (if (< 1 2) :yes :no)) ; yes
(println (if false :yes))       ; nil
(println (if 0 :zero :empty))   ; zero
```
