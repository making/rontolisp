# f81. Clojure on wasm: data.json's `read-str`/`write-str`/`pprint` past the host surface

Difficulty: High

Found by the f62 re-probe (`.kb/clojure-frontend.md`, "Library probes", 2026-10-13):
`org.clojure/data.json` 2.5.2 prints the oracle's lines on the interpreter and the
JVM (`write-str`, `read-str` with `:key-fn`, big numbers, `pprint`), but its load
fails on both wasm backends with the `java:` call-time error (`The function
JAVA:FIELD is undefined`).

## What decides the design

- The load evaluates `default-write-options`, whose `:date-formatter` is the static
  field `java.time.format.DateTimeFormatter/ISO_INSTANT`. A portable formatter value
  (and `.format` over this front end's instants) is its own java.time design, not a
  special case for one library.
- Past the load (a `:local/root` copy with `:date-formatter nil`): `write-str`
  prints the oracle's line on wasm, but `read-str` stops at the first integer on
  `Long/valueOf` (`The function JAVA:STATIC is undefined`). The read path also calls
  `Double/valueOf`, `Integer/parseInt`, `Math/min` and `Integer/toHexString` (write
  escapes), and the write path `.isInfinite`/`.isNaN` on doubles (no value arm takes
  a double: `%clojure-value-receiver-p` answers false for one). Each is a pure
  function, so portable lowerings are legitimate infrastructure (like
  `String/valueOf` where no host is), measured against clj 1.12.6 one by one -- but
  only together with the formatter do they make the verbatim probe green, so they
  belong to this item, not f62.

## Plan

1. The portable `java.time` formatter (or the load-time refusal it degrades to).
2. The numeric statics and the double predicates, each oracle-measured.
3. The probe's own `deps.edn` re-run (`write-str`, `read-str` with `:key-fn`, big
   numbers, `pprint`) on all four backends.
