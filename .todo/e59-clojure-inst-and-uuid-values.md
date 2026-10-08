# e59. Clojure: `#inst`/`#uuid` values, `clojure.instant`, `clojure.uuid`

Difficulty: High

`#inst` and `#uuid` are refused by the reader; `inst?`/`uuid?` answer false for every value
a program builds (only a host `Date`/`UUID` passes, interpreter and JVM). The oracle loads
`clojure.instant` and `clojure.uuid` before the program and reads both tags by default, in
source and in EDN (`clojure.edn/read-string` included).

## What decides the design

- A value of this front end's own for an instant (milliseconds, printing
  `#inst "2020-01-01T00:00:00.000-00:00"`) and a UUID (two longs, printing
  `#uuid "..."`), on all four backends; `=`/`hash`/`compare` like the oracle's
  `java.util.Date`/`UUID`; `inst-ms`, `random-uuid`, `parse-uuid`.
- `clojure.instant/read-instant-date` and friends parse RFC 3339 with the oracle's
  offset and fraction rules (`parse-timestamp`, `validated`).
- `e54` (`data_readers.clj`) moves the reader's tag table; these two tags are its defaults.

## Plan

1. Measure the oracle: printing, `=`, `compare`, `hash` between the two kinds and strings,
   EDN round trips, the timestamp grammar's edges.
2. The representation, the reader tags, the two namespaces.
3. clojure-spec lines on all four backends; `doc/*/clojure/syntax.md`, reference pages.
