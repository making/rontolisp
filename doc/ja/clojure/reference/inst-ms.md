# inst-ms

`(inst-ms inst)`

`clojure.core/inst-ms`: インスタントの 1970-01-01T00:00:00Z からのミリ秒を返します。対象は
`#inst`、[clojure.instant](clojure-instant.md) の Date と Timestamp、インタプリタと JVM では
ホストの `java.util.Date` と `java.time.Instant` です。それ以外の値（Calendar を含む）は、
オラクルと同じ `IllegalArgumentException`
（`No implementation of method: :inst-ms* of protocol: #'clojure.core/Inst found for class: ...`）
になります。`inst-ms*` は同じ関数です。値としては1引数の関数です。

```clojure
(println (inst-ms #inst "1970-01-01T00:00:01.5Z") (inst-ms #inst "1969-12-31T23:59:59.999Z"))
(println (try (inst-ms "2020") (catch IllegalArgumentException e (ex-message e))))
```

```
1500 -1
No implementation of method: :inst-ms* of protocol: #'clojure.core/Inst found for class: java.lang.String
```
