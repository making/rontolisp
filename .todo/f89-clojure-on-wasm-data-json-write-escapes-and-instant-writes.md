# f89. Clojure on wasm: data.json's write escapes and instant writes past the host surface

Difficulty: Medium

Found by the f81 extended probe (`.kb/clojure-frontend.md`, "The ISO_INSTANT
formatter", 2026-10-13): with f81's read path green, `write-str` of a string needing
escapes fails on every backend (`java:call expects a java object as the first
argument, got #<STREAM>`), and `write-str` of an instant fails on wasm (`Method
toInstant taking 0 args is not supported for class java.util.Date`).

## What decides the design

- `write-string`'s slow path calls the three-argument `Writer.append` (`.append out
  s 0 i`); the stream lowering maps `append` of one argument only, so the call falls
  through to `java:call`, which takes no stream. The oracle appends the range and
  answers the writer. A portable arm is the same shape as the one-argument one.
- `write-date` calls `.toInstant` on the instant first; on wasm no host answers it.
  The portable answer that keeps `write-str` oracle-identical is the instant itself,
  which the ISO_INSTANT arm (f81) then formats. A `toInstant` row answers every such
  site on every backend, so it must read as the host's where the host is -- or stay
  wasm-only like the formatter.
- Neither blocks f81's probe (`write-str` without escapes, no instants), so both
  belong here, not f81.

## Plan

1. The three-argument `append` arm, oracle-measured (answer the stream).
2. `.toInstant` of an own Date or Timestamp on wasm, oracle-measured through
   `write-str`.
3. The extended probe (`write-str` with escapes, `write-str` of instants) on all four
   backends.
