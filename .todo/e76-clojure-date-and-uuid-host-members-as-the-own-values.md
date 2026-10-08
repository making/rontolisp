# e76. Clojure: `java.util.Date`/`UUID` interop as the instant and UUID values

Difficulty: High

`#inst`, `#uuid`, `random-uuid` and `parse-uuid` make values of this front end's own on every
backend (`.kb/clojure-frontend.md`, "Instants and UUIDs"), but the host members library code
spells for the same values still go to `java:`: `(java.util.Date.)`, `(java.util.Date. ms)`,
`(java.util.UUID/randomUUID)`, `(java.util.UUID/fromString s)`, `(java.util.UUID. msb lsb)`.
On wasm each is the `java:` call-time refusal; on the interpreter and the JVM it is a host
object that is never `=` to a read instant or UUID, does not sort beside one, and is a
different key. Library sites measured 2026-10-08 over the local Clojars/contrib jars: medley's
`random-uuid` and `uuid` (`:clj` branches), ring-core's session store and `ring.util.io`,
babashka.http-client's multipart boundary, test.check's UUID generator, spec.alpha's ids.

## What decides the design

- The constructions and statics above lower to the own values (the `Exception.` and
  `StringWriter.` precedent, "Java interop"), `System/currentTimeMillis` beside them.
- An own instant or UUID handed to a `java:` member (a `SimpleDateFormat`, a JDBC driver)
  needs the host object back: a conversion at the boundary both ways, the shape a map and
  `false` already cross in (`.kb/java-interop.md`), so the host API keeps working on the
  interpreter and the JVM.
- Measure first how much host code reaches a Date the program made, against what the
  conversion costs every `java:` program.

## Plan

1. Measure on clj 1.12.6 and in the probe libraries (`e43`) which members and host APIs
   meet these values.
2. The lowering of the constructions, the boundary conversion, `=` between host and own.
3. clojure-spec lines on all four backends; `ClojureInteropTest`; user docs.
