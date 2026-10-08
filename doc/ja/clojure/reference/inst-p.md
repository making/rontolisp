# inst?

`(inst? x)`

`clojure.core/inst?`: オラクルの `Inst` プロトコルが受け付けるインスタントなら `true` を返します。
対象は `#inst`（`java.util.Date`）、[clojure.instant](clojure-instant.md) の Timestamp、
インタプリタと JVM ではホストの `java.util.Date` と `java.time.Instant` で、それ以外
（Calendar を含む）は `false` です。値としては1引数の関数です。

```clojure
(println (inst? #inst "2020-01-01") (inst? "2020-01-01"))  ; true false
```
