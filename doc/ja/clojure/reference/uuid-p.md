# uuid?

`(uuid? x)`

`clojure.core/uuid?`: ホストの `java.util.UUID` なら `true` を返します。これは interop でしか作れない（インタプリタと JVM）ため、wasm ではどの値も `false` です。値としては1引数の関数です。

```clojure
(println (uuid? "x"))  ; false
```
