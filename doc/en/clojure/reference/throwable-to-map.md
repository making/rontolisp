# Throwable->map

`(Throwable->map ex)`

Answers an exception as data, like the oracle: `:via` holds one map per exception down the
cause chain (`:type` its class as a symbol, `:message` and `:data` when it has them), `:cause`
and `:data` are the root cause's message and data, `:phase` the `:clojure.error/phase` of
`ex`'s own data, and `:trace` the root's stack frames. An exception carries no frames here, so
`:trace` is `[]` and no `:via` map has `:at` -- what the oracle answers for an empty stack
trace. Works on an `ex-info`, a throwable construction, a runtime error, and on the
interpreter and the JVM a host exception; `nil` is the oracle's `NullPointerException`, any
other value its `ClassCastException`. Works as a function value too.
[clojure.datafy](clojure-datafy.md) answers this map for an exception.

```clojure
(def m (Throwable->map (ex-info "outer" {:id 1} (Exception. "root"))))
(println (map :type (:via m))) ; (clojure.lang.ExceptionInfo java.lang.Exception)
(println (:cause m) (:trace m)) ; root []
(println (:data (first (:via m)))) ; {:id 1}
```
