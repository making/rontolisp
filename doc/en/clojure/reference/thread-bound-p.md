# thread-bound?

`(thread-bound? & vars)`

`clojure.core/thread-bound?`: `true` while a `binding` of every given var is in effect. A root value, a non-`^:dynamic` var and a `^:dynamic` var no `binding` rebinds answer `false`; a value that is no var signals, like the oracle (only when reached: the answer is `false` at the first unbound var). With no vars, `true`. As a value a function of any number of vars. `#'*out*`, `#'*in*`, `#'*err*` and `#'*agent*` are `false` at the root (the oracle's `clojure.main` binds none of them) and `true` under a `binding` of the special, inside `with-out-str` (`*out*`), `with-in-str` (`*in*`) and an agent action (`*agent*`). The flags `clojure.main` binds around a script (`*print-length*`, `*assert*`, `*warn-on-reflection*`, ...) are always `true`; `*print-dup*`, `*flush-on-newline*`, `*compile-files*` and the other flags are `false` at the root. Any other `clojure.core` var is `false`.

```clojure
(def ^:dynamic *tb* 1)
(println (thread-bound? #'*tb*)
         (binding [*tb* 2] (thread-bound? #'*tb*)))  ; false true
(println (thread-bound? #'*out*)
         (with-out-str (print (thread-bound? #'*out*))))  ; false true
```
