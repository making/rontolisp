# not

`(not expr)`

Answers `true` when `expr` is falsey and `false` when it is truthy; the check is an
explicit null-or-false test, so `nil` and `false` are both falsey and everything
else -- `0`, the empty string, an empty collection -- is truthy.

```clojure
(println (not nil))    ; true
(println (not false))  ; true
(println (not 1))      ; false
```
