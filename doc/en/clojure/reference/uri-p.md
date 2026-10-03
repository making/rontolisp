# uri?

`(uri? x)`

`clojure.core/uri?`: `true` for a host `java.net.URI`, which only interop builds (interpreter and JVM); a string is `false`, and on wasm every value is. As a value a one-argument function.

```clojure
(println (uri? "http://a"))  ; false
```
