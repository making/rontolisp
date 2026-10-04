# var-get

`(var-get v)`

`clojure.core/var-get`: the root of the var `v`, what `deref` of the var answers (a
`binding` in effect included). Unlike `deref`, a value that is no var signals, an atom
included. As a value a one-argument function.

```clojure
(def x 5)
(println (var-get #'x))            ; 5
(def ^:dynamic *d* 1)
(println (binding [*d* 2] (var-get #'*d*))) ; 2
(println (map var-get [#'x #'*d*])) ; (5 1)
```
