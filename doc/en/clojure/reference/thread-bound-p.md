# thread-bound?

`(thread-bound? & vars)`

`clojure.core/thread-bound?`: `true` while a `binding` of every given var is in effect. A root value, a non-`^:dynamic` var and a `^:dynamic` var no `binding` rebinds answer `false`; a value that is no var signals, like the oracle (only when reached: the answer is `false` at the first unbound var). With no vars, `true`. As a value a function of any number of vars. `#'*out*` and the other `clojure.core` vars are not supported as vars yet.

```clojure
(def ^:dynamic *tb* 1)
(println (thread-bound? #'*tb*)
         (binding [*tb* 2] (thread-bound? #'*tb*)))  ; false true
```
