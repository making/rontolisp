# alter

`(alter r f args...)`

Applies `f` to the ref's old value and the arguments inside `dosync`, writes
the answer through the ref's validator, and answers the new value. Works as a
function value, like `swap!`.

```clojure
(def r (ref 0))
(def bump alter)
(dosync (bump r + 40 2))
(println @r) ; 42
```
