# delay

`(delay body...)`

A delay of the body: the body runs at most once, on the first [force](force.md) or
[deref](deref.md) (`@d`), and every later one answers the same value. A body that throws
throws the same exception again on every later force, and the delay is then realized, like the
oracle's. The body runs under the delay's own lock, so two request threads of a Ring handler
forcing one delay run it once (interpreter and JVM; a wasm module is single-threaded).
[realized?](realized-p.md) tells whether the body has run, [delay?](delay-p.md) answers `true`,
`instance?` of `clojure.lang.Delay`, `IDeref` and `IPending` is true, `=` is identity, and
`class` answers `:clojure.lang.Delay`. `deref` with a timeout signals `ClassCastException`, as
the oracle's cast to a future does. A delay prints as the oracle's
`#object[clojure.lang.Delay {:status :pending, :val nil}]` (`:ready` and its value once forced,
`:failed` and the exception after a throw) and its `str` is `clojure.lang.Delay`, both without the
identity hash. `delay` is a macro, so it has no value.

```clojure
(def config (delay (println "loading") {:port 8080}))
(println (realized? config)) ; false
(println (:port @config) (:port (force config))) ; loading, then 8080 8080
(println (realized? config)) ; true
```
