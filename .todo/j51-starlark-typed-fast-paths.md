# j51. Starlark: typed fast paths in the lowering

Difficulty: High

After `.todo/j46`, and after `.todo/j47` so the corpus guards it. The prototype lowers every
operator to a generic call and materializes `range`. Measured 2026-11-15 (`.todo/artefacts/j45-starlark-front-end-prototype/`, `README.md`,
`bench.star`: `fib(27)` plus a 10^6-iteration loop): JVM 0.49 s, WASM 0.48 s / 0.36 s,
Java Starlark 0.30 s, CPython 0.14 s.

Measure first, per backend, which of these pays; a change the measurement says is not worth
it is not made:

- `for i in range(..)` as a counting loop with no vector.
- Integer arithmetic and comparison inline when both operands are known ints (literals,
  `range` variables, `len`), falling back to the generic call otherwise.
- Direct calls with defaults and keywords resolved at the call site, so `%sl-call`'s
  binding is left to `*args`/`**kwargs` and unknown callees.
- Truthiness and comparison fused through `and`/`or`/`not`.
- List `insert`/`pop`/`remove` in place (the prototype rebuilds the vector).

Record the numbers in the Starlark `.kb` file.
