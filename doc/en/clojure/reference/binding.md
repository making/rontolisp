# binding

`(binding [var init ...] body...)`

Rebinds each var around the body with dynamic extent: code the body calls sees
the binding, and the root returns after. Only `^:dynamic` vars (and `*out*`,
already special) may be bound; anything else is refused, like the oracle's
non-dynamic error. Inits run sequentially, like `let`.

```clojure
(def ^:dynamic *loud* false)
(defn status [] (if *loud* :loud :quiet))
(println (status)) ; :quiet
(println (binding [*loud* true] (status))) ; :loud
(println (status)) ; :quiet
```
