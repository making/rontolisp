# Instants and UUIDs

The values [`#inst` and `#uuid`](../syntax.md#tagged-literals) read as, on every backend. An
instant is the oracle's `java.util.Date`, its milliseconds since 1970-01-01T00:00:00Z. It
prints as `#inst "..."` in UTC under `print` and `pr` alike, and `str` of it is the oracle's
`toString` in UTC (`Wed Jan 01 00:00:00 UTC 2020`). A UUID is the oracle's `java.util.UUID`:
it prints as `#uuid "..."`, and `str` of it is its lowercase spelling. Two of a kind are `=`
when the oracle's are, `compare` orders them like the oracle (a UUID by its two halves as
signed longs), a map or set finds one by `=`, and neither is a collection or a function.
`class` answers `:java.util.Date` and `:java.util.UUID`, and a multimethod on `class` or a
protocol dispatches on those classes. [inst?](inst-p.md) and [uuid?](uuid-p.md) test the
kinds, and [clojure.instant](clojure-instant.md) also reads a timestamp into a
`java.sql.Timestamp` or a `java.util.Calendar`.

| Name | Example | Result |
|---|---|---|
| `inst-ms` | `(inst-ms #inst "1970-01-01T00:00:01Z")` | `1000` |
| `random-uuid` | `(uuid? (random-uuid))` | `true` |
| `parse-uuid` | `(parse-uuid "1-1-1-1-1")` | `#uuid "00000001-0001-0001-0001-000000000001"` |

```clojure
(def at #inst "2020-06-15T10:20:30.456+02:00")
(def id #uuid "550e8400-e29b-41d4-a716-446655440000")
(println at (str at))
(println (= at #inst "2020-06-15T08:20:30.456Z") (sort [#inst "2021" at #inst "1999"]))
(println id (get {id :found} (parse-uuid (str id))))
```

```
#inst "2020-06-15T08:20:30.456-00:00" Mon Jun 15 08:20:30 UTC 2020
true (#inst "1999-01-01T00:00:00.000-00:00" #inst "2020-06-15T08:20:30.456-00:00" #inst "2021-01-01T00:00:00.000-00:00")
#uuid "550e8400-e29b-41d4-a716-446655440000" :found
```

## Differences

- `class` answers a keyword, like for every value ([Deviations](../deviations.md)).
- `str` of an instant is the oracle's `toString` with its time zone UTC: the oracle answers
  it in its JVM's default time zone, which no wasm backend has.
- A Timestamp is `=`, `compare`s and dispatches a multimethod like the oracle's, but a
  protocol extended to `java.util.Date` does not reach it; extend `java.sql.Timestamp` too.
- A UUID's hex digits are the ASCII ones, where the oracle's `UUID.fromString` also takes
  any Unicode decimal digit and the fullwidth Latin letters.
- A host `java.util.Date`, `java.time.Instant` or `java.util.UUID` from interop
  (interpreter and JVM) stays a host object: `inst?`, `uuid?` and `inst-ms` take it, but it
  is never `=` to an instant or a UUID the program read.
