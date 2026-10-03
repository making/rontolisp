# inst?

`(inst? x)`

`clojure.core/inst?`: ホストの `java.util.Date` または `java.time.Instant` なら `true` を返します。これらは interop でしか作れない（インタプリタと JVM）ため、wasm ではどの値も `false` です。値としては1引数の関数です。

```clojure
(println (inst? "2020-01-01"))  ; false
```
