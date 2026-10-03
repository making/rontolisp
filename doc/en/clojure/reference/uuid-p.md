# uuid?

`(uuid? x)`

`clojure.core/uuid?`: `true` for a host `java.util.UUID`, which only interop builds (interpreter and JVM); on wasm every value is `false`. As a value a one-argument function.

```clojure
(println (uuid? "x"))  ; false
```
