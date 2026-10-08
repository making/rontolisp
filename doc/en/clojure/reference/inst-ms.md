# inst-ms

`(inst-ms inst)`

`clojure.core/inst-ms`: the milliseconds since 1970-01-01T00:00:00Z of an instant -- an
`#inst`, a [clojure.instant](clojure-instant.md) Date or Timestamp, and on the interpreter and
the JVM a host `java.util.Date` or `java.time.Instant`. Anything else, a Calendar included, is
the oracle's `IllegalArgumentException`
(`No implementation of method: :inst-ms* of protocol: #'clojure.core/Inst found for class: ...`).
`inst-ms*` is the same function. As a value a one-argument function.

```clojure
(println (inst-ms #inst "1970-01-01T00:00:01.5Z") (inst-ms #inst "1969-12-31T23:59:59.999Z"))
(println (try (inst-ms "2020") (catch IllegalArgumentException e (ex-message e))))
```

```
1500 -1
No implementation of method: :inst-ms* of protocol: #'clojure.core/Inst found for class: java.lang.String
```
