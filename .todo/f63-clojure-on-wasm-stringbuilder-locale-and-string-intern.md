# f63. Clojure on wasm: `StringBuilder`, `Locale` constants and `String.intern` as values of the front end's own

Difficulty: Medium

Found by the library probes (`.kb/clojure-frontend.md`, "Library probes", 2026-10-12): hiccup
2.0.0 and honeysql 2.7.1479 print the oracle's output on the interpreter (honeysql on the JVM
too), but both wasm backends trap with `The function JAVA:CALL is undefined`. Their `:clj`
branches build strings through host objects no wasm program has:

| library | host calls on the run-time path |
|---|---|
| hiccup 2.0.0 | `(StringBuilder.)` 116 sites (`build-string`, `render-html`), `.append`, `.toString` |
| honeysql 2.7.1479 | `(StringBuilder.)` 5 sites, `java.util.Locale/US` (`.toUpperCase s Locale/US`) |
| reitit-core 0.11.0 | `(.intern ^String path)` (`reitit.impl/parse`) |

A zero-argument `java.io.StringWriter` already lowers to a string output stream on every
backend (`%clojure-str-of`'s stream arm); `StringBuilder` is the same object to these programs
(`.append` of any value's `str`, `.toString`, `.length`, `.setLength`, `.charAt`).

## What decides the design

- The StringWriter precedent: construction lowers to the string stream where the class is
  `java.lang.StringBuilder`/`StringBuffer`, and the instance methods to its writes, so a
  program naming no `StringBuilder` lowers byte-identically.
- `Locale/US` (and `ROOT`, `ENGLISH`) as a value the case-mapping verbs take
  ("Case mapping": the mapping is locale-independent but for tr/az/lt).
- `.intern` of a string is the string (no identity distinction is observable but `identical?`).

## Plan

1. clojure-spec rows: `StringBuilder` building, `.toUpperCase` with `Locale/US`, `.intern`.
2. Re-run the hiccup and honeysql probes (`build/probe` shape in the kb) on both wasm backends.
3. User docs en + ja (`dot-name.md`, `deviations.md`).
