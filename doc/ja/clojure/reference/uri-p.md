# uri?

`(uri? x)`

`clojure.core/uri?`: ホストの `java.net.URI` なら `true` を返します。これは interop でしか作れない（インタプリタと JVM）ため、文字列は `false` で、wasm ではどの値も `false` です。値としては1引数の関数です。

```clojure
(println (uri? "http://a"))  ; false
```
