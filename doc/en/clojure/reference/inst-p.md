# inst?

`(inst? x)`

`clojure.core/inst?`: `true` for an instant the oracle's `Inst` protocol takes -- an `#inst`
(a `java.util.Date`), a [clojure.instant](clojure-instant.md) Timestamp, and on the
interpreter and the JVM a host `java.util.Date` or `java.time.Instant`; `false` for anything
else, a Calendar included. As a value a one-argument function.

```clojure
(println (inst? #inst "2020-01-01") (inst? "2020-01-01"))  ; true false
```
