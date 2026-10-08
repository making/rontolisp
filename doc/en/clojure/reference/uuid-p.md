# uuid?

`(uuid? x)`

`clojure.core/uuid?`: `true` for a UUID -- a `#uuid`, what [random-uuid](random-uuid.md) and
[parse-uuid](parse-uuid.md) answer, and on the interpreter and the JVM a host
`java.util.UUID`; `false` for anything else, its string spelling included. As a value a
one-argument function.

```clojure
(println (uuid? #uuid "1-1-1-1-1") (uuid? "00000001-0001-0001-0001-000000000001"))  ; true false
```
