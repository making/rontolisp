# ref-set

`(ref-set r v)`

Replaces the ref's value inside `dosync`, through the ref's validator, and
answers the value. Works as a function value, like `reset!`.

```clojure
(def r (ref 0))
(dosync (ref-set r 41))
(println @r) ; 41
```
