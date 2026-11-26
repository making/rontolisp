# default-data-readers

`default-data-readers`

`clojure.core/default-data-readers`: 2 つの既定のタグを、そのリーダの var に対応づけるオラクルのマップです。`uuid` は `#'clojure.uuid/default-uuid-reader`、`inst` は `#'clojure.instant/read-instant-date` です。ソースでも [read-string](read-string.md) でも、そのタグの[データリーダ](../syntax.md#tagged-literals)がないときに `#uuid` と `#inst` を読むのがこれらです。var を呼ぶとそのリーダを呼びます。すべてのバックエンドで動きます。

```clojure
(println (get default-data-readers 'inst))
(println ((get default-data-readers 'uuid) "1-1-1-1-1"))
```

```
#'clojure.instant/read-instant-date
#uuid "00000001-0001-0001-0001-000000000001"
```
