# f71. Clojure on wasm: `StringBuilder`/`StringBuffer` construction with an initial text or capacity

Difficulty: Small

Found while closing f63 (2026-10-13): a zero-argument `(StringBuilder.)` lowers to a
string output stream on every backend, but a one-argument construction keeps the host
`java:new`, which traps on both wasm backends:

| construction | interpreter | JVM | P1 | component |
|---|---|---|---|---|
| `(StringBuilder. "ab")` | `"ab"` | `"ab"` | trap (`JAVA:NEW is undefined`) | trap |
| `(StringBuilder. 16)` | `""` | `""` | trap | trap |
| `(StringBuffer. "ab")` | `"ab"` | `"ab"` | trap | trap |

(`ClojureInteropLowering.stringStreamConstruction` matches `argCount == 0` only; a
clojure-spec row drafting `(StringBuilder. "hi")` trapped on wasm and was rewritten to
the zero-argument shape before f63's commit.)

## What decides the design

- The zero-argument shape is the same object (a string output stream); a one-argument
  shape decides at run time: a string is written into the fresh stream, an integer is a
  capacity (ignored, the stream starts empty), anything else the oracle's refusal (no
  such constructor).
- A program naming no such construction lowers byte-identically (the arm is behind the
  one-argument construction only).

## Plan

1. clojure-spec rows: initial-text and capacity constructions on all four backends.
2. Re-run the builder portions of the f63 probes on both wasm backends.
3. User docs en + ja (`dot-name.md`, `deviations.md`).
