# alter

`(alter r f args...)`

Applies `f` to the ref's old value and the arguments inside `dosync`, writes the
answer, and answers the new value; the ref's validator runs on its final value when
the transaction ends. Works as a function value, like `swap!`.

```clojure
(def r (ref 0))
(def bump alter)
(dosync (bump r + 40 2))
(println @r) ; 42
```
