# ref-set

`(ref-set r v)`

Replaces the ref's value inside `dosync` and answers the value; the ref's validator
runs on its final value when the transaction ends. Works as a function value, like
`reset!`.

```clojure
(def r (ref 0))
(dosync (ref-set r 41))
(println @r) ; 41
```
