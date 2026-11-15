# j51. Starlark: typed fast paths in the lowering

Difficulty: High

The conformance corpus (`StarlarkSpecE2eTest#conformance`) guards it. The lowering makes every operator a generic call
and materializes `range`. Measured 2026-11-15 (`.kb/starlark-frontend.md`, "Measured";
`bench.star` of `.todo/artefacts/j45-starlark-front-end-prototype/`: `fib(27)` plus a
10^6-iteration loop): JVM 0.29-0.36 s, WASM 0.28-0.37 s / 0.28-0.33 s, Java Starlark
0.26-0.29 s, CPython 0.10-0.13 s.

Measure first, per backend, which of these pays; a change the measurement says is not worth
it is not made:

- `for i in range(..)` as a counting loop with no vector.
- Integer arithmetic and comparison inline when both operands are known ints (literals,
  `range` variables, `len`), falling back to the generic call otherwise.
- Direct calls of a `def` with defaults and keywords resolved at the call site, as a
  built-in's already are (`StarlarkLowering.direct`), so `%starlark-call`'s binding is left
  to `*args`/`**kwargs` and unknown callees. A default is evaluated when the `def` runs, so
  the call site needs its value, not its expression.
- `a, b = b, a + b`: a tuple is built and unpacked for a target of as many names.
- A list, tuple or dict expression whose elements are constants as quoted data. It is one
  call of as many arguments today, which for 3,000 elements passes the JVM's method size
  (`.kb/jvm-method-size-limits.md`); a quoted list of 3,000 compiles.
- Truthiness and comparison fused through `and`/`or`/`not`.
- List `insert`/`pop`/`remove` in place (`starlark.lisp` rebuilds the vector).

Record the numbers in the Starlark `.kb` file.
