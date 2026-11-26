# uuid?

`(uuid? x)`

`clojure.core/uuid?`: UUID なら `true` を返します。対象は `#uuid`、[random-uuid](random-uuid.md) と
[parse-uuid](parse-uuid.md) が返す値、インタプリタと JVM ではホストの `java.util.UUID` で、
それ以外（その文字列表記を含む）は `false` です。値としては1引数の関数です。

```clojure
(println (uuid? #uuid "1-1-1-1-1") (uuid? "00000001-0001-0001-0001-000000000001"))  ; true false
```
