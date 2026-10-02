# binding

`(binding [var init ...] body...)`

Rebinds each var around the body with dynamic extent: code the body calls sees
the binding, and the root returns after. Only `^:dynamic` vars (and `*out*`/`*in*`,
already special -- bare or `clojure.core/`-qualified, since a syntax-quote
qualifies them) may be bound; anything else is refused, like the oracle's
non-dynamic error. A `^:dynamic` `defn` holds its function in the var, so it
rebinds too (any other `defn` stays refused). Inits run sequentially, like `let`.

```clojure
(def ^:dynamic *loud* false)
(defn status [] (if *loud* :loud :quiet))
(println (status)) ; :quiet
(println (binding [*loud* true] (status))) ; :loud
(println (status)) ; :quiet
```

```clojure
(defn ^:dynamic slow-double [n] (* n 2))
(defn calls-slow-double [] (slow-double 21))
(println (calls-slow-double)) ; 42
(println (binding [slow-double (memoize slow-double)] (calls-slow-double))) ; 42
(println (calls-slow-double)) ; 42
```

`*in*` is `*standard-input*` (never `nil`), so rebinding it feeds readers like
`(.readLine *in*)` -- which reads through `read-line` on a stream, answering `nil`
past the end:

```clojure
(println (nil? *in*)) ; false
(println (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "hi"))] (.readLine *in*))) ; hello
```
