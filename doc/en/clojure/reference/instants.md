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

The interop spellings make the same values, on every backend: `(java.util.Date.)` is the
current instant, `(java.util.Date. ms)` and `(java.sql.Timestamp. ms)` the instant of those
milliseconds, `(java.util.UUID. msb lsb)` the UUID of the two halves, `UUID/randomUUID` and
`UUID/fromString` those of [random-uuid](random-uuid.md) and `#uuid`, and
`System/currentTimeMillis` answers the milliseconds since the epoch. A Date's `getTime`,
`setTime` (in place), `before`, `after` and `compareTo`, a Timestamp's `getNanos` and a UUID's
halves, `version` and `variant` answer everywhere. On the interpreter and the JVM such a value
crosses into a Java member as the host object (`(.format sdf (java.util.Date.))`), any other
method of its class is the host object's (`(.toInstant d)`), and a host Date or UUID a member
answers is `=` to it and compares beside it.

| Name | Example | Result |
|---|---|---|
| `inst-ms` | `(inst-ms #inst "1970-01-01T00:00:01Z")` | `1000` |
| `random-uuid` | `(uuid? (random-uuid))` | `true` |
| `parse-uuid` | `(parse-uuid "1-1-1-1-1")` | `#uuid "00000001-0001-0001-0001-000000000001"` |
| `java.util.Date.` | `(java.util.Date. 1000)` | `#inst "1970-01-01T00:00:01.000-00:00"` |
| `java.util.UUID/fromString` | `(= (java.util.UUID/fromString "1-1-1-1-1") #uuid "1-1-1-1-1")` | `true` |
| `System/currentTimeMillis` | `(integer? (System/currentTimeMillis))` | `true` |

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
- A UUID's hex digits are the ASCII ones, where the oracle's `UUID.fromString` also takes
  any Unicode decimal digit and the fullwidth Latin letters.
- A host `java.util.Date`, `java.sql.Timestamp` or `java.util.UUID` a Java member answers
  (interpreter and JVM) stays a host object: `=` to the value made here as the first one's
  `equals` decides, ordered beside it by `compareTo`, taken by `inst?`, `uuid?` and
  `inst-ms` (a `java.time.Instant` too), but it prints as `#<java java.util.Date>` and is
  another map key or set member than the value made here.
- `(java.util.Date. s)` of a string computed at run time is refused: the oracle's
  deprecated `Date(String)` parse is not here (a literal string still reaches the host's on
  the interpreter and the JVM). A Date's mutators but `setTime` (`setYear`, ...) are refused
  by name, where the oracle's Date changes.
- A [clojure.instant](clojure-instant.md) Calendar has no host object: it never crosses into
  a Java member, and its class's methods are refused by name. On wasm, so is a method of a
  Date or UUID only the host answers (`toInstant`, `hashCode`).
