# g48: Audit the wasm-GC arity-report path for a span cliff like g46's

Difficulty: Easy

Parent: `.todo/g46-jvm-leg-arity-messages-lost-in-full-corpus-runs-since-g42.md` (the
JVM leg's `_arityErr` table silently dropped past a 30,568-funcId span; chunked in
g46).

## Question

The wasm-GC legs stay green, but the corpus keeps growing. Does the wasm arity
report (`WasmRuntimeBuilder.ArityReport`, `_arity_message`'s funcId selection,
`_arity_chk`, the spread cases) carry any per-program constant or table with a hard
ceiling and a silent fallback -- a `nil` answer, an `unreachable`, a dropped name --
past some funcId count, operator count or message size?

## Acceptance

- Each mechanism with a ceiling named with its limit, its over-limit behavior and
  its pin; or a short note that the path is unbounded, with the measurement behind
  it (a synthetic program past the JVM's old 30k cliff is the probe to reuse).
- No behavior change required unless the audit finds a live cliff.
