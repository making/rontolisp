# default-data-readers

`default-data-readers`

`clojure.core/default-data-readers`: the oracle's map of the two default tags to their
readers' vars, `uuid` to `#'clojure.uuid/default-uuid-reader` and `inst` to
`#'clojure.instant/read-instant-date` -- what reads `#uuid` and `#inst` where no
[data reader](../syntax.md#tagged-literals) of the tag does, in source and under
[read-string](read-string.md). Calling a var calls its reader. Runs on every backend.

```clojure
(println (get default-data-readers 'inst))
(println ((get default-data-readers 'uuid) "1-1-1-1-1"))
```

```
#'clojure.instant/read-instant-date
#uuid "00000001-0001-0001-0001-000000000001"
```
