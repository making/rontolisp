# bound?

`(bound? & vars)`

`clojure.core/bound?`: `true` when every var holds a value. A var here always does (a `def` without a value binds `nil`, where the oracle's stays unbound), so every var answers `true`; anything that is no var signals, like the oracle. As a value a function of any number of vars.

```clojure
(def bp-x 1)
(println (bound? #'bp-x) (bound?))  ; true true
```
