# inst?

`(inst? x)`

`clojure.core/inst?`: `true` for a host `java.util.Date` or `java.time.Instant`, which only interop builds (interpreter and JVM); on wasm every value is `false`. As a value a one-argument function.

```clojure
(println (inst? "2020-01-01"))  ; false
```
