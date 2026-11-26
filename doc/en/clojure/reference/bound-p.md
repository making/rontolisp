# bound?

`(bound? & vars)`

`clojure.core/bound?`: `true` when every var holds a value. A declared-but-never-defined name and a `def` without a value are unbound until a definition binds them; a macro's var is bound. It stops at the first unbound var, like the oracle's `every?`; a value that is no var signals when reached. As a value a function of any number of vars.

```clojure
(def bp-x 1)
(declare bp-y)
(println (bound? #'bp-x) (bound?) (bound? #'bp-y))  ; true true false
```
