# e57. Clojure: `clojure.math`

Difficulty: High

`clojure.math` (Clojure 1.11) is `java.lang.Math` as functions: `sin` ... `tanh`, `exp`,
`log`, `log10`, `log1p`, `expm1`, `sqrt`, `cbrt`, `pow`, `hypot`, `atan2`, `floor`, `ceil`,
`rint`, `round`, `floor-div`, `floor-mod`, `signum`, `ulp`, `copy-sign`, `get-exponent`,
`next-after`/`next-up`/`next-down`, `scalb`, `IEEE-remainder`, the `*-exact` six, `random`,
`E`, `PI`. `Math/sin` interop is a call-time error on wasm, so a portable namespace runs on
the run-time library's own math.

## What decides the design

- The CL transcendentals are fdlibm (`StrictMath`) on every backend, bit-identical
  (`.kb/transcendentals.md`). The oracle's `clojure.math` calls `Math`, which is per-CPU:
  measure how often `Math` and `StrictMath` disagree on x86-64 for each function (random and
  edge inputs) and record it; a disagreement is a stated deviation, not a reason to leave
  fdlibm.
- `log10`, `cbrt`, `expm1`, `IEEE-remainder`, `ulp`, `next-after`, `scalb`, `get-exponent`
  have no CL counterpart reaching fdlibm (or an exact bit operation) on every backend today;
  each needs a runtime function on the interpreter, the JVM and both wasm targets
  (`WasmFdlibmRuntimeBuilder` already holds `expm1`).
- `*-exact` overflow is the oracle's `ArithmeticException` (`long overflow`, `integer
  overflow`); `round` of a double answers a long, `floor-div`/`floor-mod` take longs.

## Plan

1. The measurement above, then kernels (`rontolisp.internal.math`, `ClojureKernelLowering`)
   under a Clojure-source namespace, or lowering rows where an inline call is smaller.
2. clojure-spec lines printing full digits on all four backends; `doc/*/clojure/reference/`.
