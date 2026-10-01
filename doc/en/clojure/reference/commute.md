# commute

`(commute r f args...)`

Like `alter`, but the oracle may run `f` twice on a conflict and take the
commutative answer. Nothing ever conflicts here, so the function runs exactly
once and the two verbs are indistinguishable. Requires `dosync`, like `alter`.

```clojure
(def r (ref 1))
(dosync (commute r * 6))
(println @r) ; 6
```
