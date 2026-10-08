# clojure.instant

Reading RFC 3339 timestamps into instants. `clojure.instant` is loaded before the program, as
in Clojure, so `clojure.instant/read-instant-date` works without a `require`; it is Clojure
source written for rontolisp from the documented behavior of Clojure's namespace, and runs
the same on every backend. `#inst` reads through `read-instant-date`
([Syntax](../syntax.md#tagged-literals)).

| Var | Behavior |
|---|---|
| `parse-timestamp` | `(parse-timestamp new-instant cs)`: matches the timestamp `cs` and calls `new-instant` with ten integers -- years, months, days, hours, minutes, seconds, nanoseconds, the offset's sign (-1, 0 or 1), hours and minutes; a missing month or day is 1, any other missing number 0, a fraction counts its first nine digits; anything else is the oracle's `Unrecognized date/time syntax` |
| `validated` | `(validated new-instance)`: `new-instance` behind the oracle's range checks of the ten integers, refusing the first that fails in the oracle's words (`failed: (<= 1 months 12)`) |
| `read-instant-date` | `(read-instant-date cs)`: the `java.util.Date` of the timestamp, its offset folded into UTC |
| `read-instant-timestamp` | `(read-instant-timestamp cs)`: the `java.sql.Timestamp` of the timestamp, which keeps all nine digits of its fraction |
| `read-instant-calendar` | `(read-instant-calendar cs)`: the `java.util.Calendar` of the timestamp, which keeps its offset |

```clojure
(require '[clojure.instant :as inst])
(prn (inst/parse-timestamp vector "2020-01-01T10:20:30.5+05:30"))
(prn (inst/read-instant-timestamp "2020-01-01T10:20:30.123456789Z"))
(prn (inst/read-instant-calendar "2020-01-01T10:20:30+05:30"))
(println (try ((inst/validated vector) 2021 2 29 0 0 0 0 0 0 0) (catch RuntimeException e (ex-message e))))
```

```
[2020 1 1 10 20 30 500000000 1 5 30]
#inst "2020-01-01T10:20:30.123456789-00:00"
#inst "2020-01-01T10:20:30.000+05:30"
failed: (<= 1 days (days-in-month months (leap-year? years)))
```

A Timestamp is an instant like a Date: `inst?` and `inst-ms` take it, `str` of it is the
oracle's (`2020-01-01 10:20:30.123456789`), and like the oracle a Date is `=` to a Timestamp
of its milliseconds but a Timestamp is never `=` to a Date. A Calendar is no `inst?`; two are
`=` when their instants and zones are (`-00:00` is another zone than `Z`), and `compare` orders
them by instant.

## Differences

- `str` of a Calendar answers its printed `#inst`, where the oracle's dumps the calendar's
  fields.
- `*data-readers*` is not read, so binding it to one of these readers does not change what
  `#inst` reads in `read-string`; `clojure.edn`'s `:readers` takes them.
