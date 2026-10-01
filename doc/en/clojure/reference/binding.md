# binding

`(binding [var init ...] body...)`

Rebinds each var around the body with dynamic extent: code the body calls sees
the binding, and the root returns after. Only `^:dynamic` vars (and `*out*`/`*in*`,
already special) may be bound; anything else is refused, like the oracle's
non-dynamic error. Inits run sequentially, like `let`.

```clojure
(def ^:dynamic *loud* false)
(defn status [] (if *loud* :loud :quiet))
(println (status)) ; :quiet
(println (binding [*loud* true] (status))) ; :loud
(println (status)) ; :quiet
```

`*in*` is `*standard-input*` (never `nil`), so rebinding it feeds readers like
`(.readLine *in*)` -- which reads through `read-line` on a stream, answering `nil`
past the end:

```clojure
(println (nil? *in*)) ; false
(println (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "hi"))] (.readLine *in*))) ; hello
```
