# reduce

`(reduce f coll)` / `(reduce f val coll)`

Folds `f` over the seq view of `coll`, left to right. The 3-arity takes the Clojure
argument order -- function, initial value, collection -- mapped onto the underlying
`reduce`'s `:initial-value`; the 2-arity folds with no seed.

```clojure
(println (reduce + '(1 2 3)))  ; 6
(println (reduce + 0 [1 2 3])) ; 6
```
