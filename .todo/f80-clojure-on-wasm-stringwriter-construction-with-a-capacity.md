# f80. Clojure on wasm: `StringWriter` construction with an initial capacity

Difficulty: Small

Found while closing f71 (2026-10-13): f71 lowered one-argument
`StringBuilder`/`StringBuffer` constructions to a pre-filled string output
stream on every backend, but a one-argument `java.io.StringWriter`
construction keeps the host `java:new`, which traps on both wasm backends:

| construction | interpreter | JVM | P1 | component |
|---|---|---|---|---|
| `(java.io.StringWriter. 16)` | `""` | `""` | trap (`JAVA:NEW is undefined`) | trap |

(`ClojureInteropLowering.stringBuilderConstruction` matches
`java.lang.StringBuilder`/`java.lang.StringBuffer` only; `ClojureLoweringTest`
pins the `StringWriter` capacity as host in
`aZeroArgumentStringWriterLowersToAStringOutputStream`.)

## What decides the design

- The oracle's capacity shape is trivial: `(str (java.io.StringWriter. 16))`
  is `""`, like a capacity-built `StringBuilder` here (ignored, the stream
  starts empty).
- The oracle's text shape is NOT trivial: `java.io.StringWriter` has no
  `String` constructor, and the oracle answers it with a `ClassCastException`
  (`String cannot be cast to Number`), not the `No matching ctor` refusal a
  shared helper would give. A `StringWriter` capacity helper therefore needs
  its own refusal arm (or its own helper), not a reuse of
  `%clojure-string-builder-1`.
- A program naming no such construction lowers byte-identically (the arm is
  behind the one-argument construction only).

## Plan

1. clojure-spec rows: capacity construction on all four backends, plus the
   refusal shape decided against the oracle (clj 1.12.6).
2. Lowering pin (`ClojureLoweringTest`), wasm-arms pin
   (`ClojureWasmHostArmsTest`), user docs en + ja.
