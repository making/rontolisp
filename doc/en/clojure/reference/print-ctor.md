# print-ctor

`(print-ctor x writer-fn w)`

The `#=` construction of `x`'s class around what `writer-fn` writes of `x` on `w`,
answering `nil`, like the oracle's. As a value three arguments.

```clojure
(println (with-out-str (print-ctor {:a 1} (fn [o w] (print-dup o w)) *out*))) ; #=(clojure.lang.PersistentArrayMap. #=(clojure.lang.PersistentArrayMap/create {:a 1}))
```
