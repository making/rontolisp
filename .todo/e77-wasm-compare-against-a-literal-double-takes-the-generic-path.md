# e77. wasm: a comparison against a literal double takes the generic path

Difficulty: Medium

`WasmComparisonCompiler.compile` takes the raw `f64` compare only when BOTH operands are
`isDefinitelyDouble`; `tryCompileConditionI32` declines any form with a double literal. So
`(< (abs x) 9.223372036854775807e18)` with `x` a run-time double calls `_rat_cmp_bits`.
Measured 2026-10-08 (wasm P1, 20M calls of a defun over `(* i 0.5)`, the loop with a bare
`truncate` 657 ms):

| test before `truncate` | ms |
|---|---|
| `(> x 9.2e18)` | 936 |
| `(> x *global-double*)` | 903 |
| `(/= x x)` | 1192 |
| `(> x 1073741823)` (integer literal, exact float-vs-integer compare) | 2881 |

About 14 ns per float compare (110 ns against an integer literal); the JVM shows no cost.
The Clojure `int`/`long` casts pay one per double argument (`.kb/clojure-frontend.md`,
the `int`/`long` measurement bullet: a double-casting loop 3.0 s -> 4.2 s on wasm).

## Plan

1. With one operand a double literal (or both of unknown type), emit an inline test that the
   other is a boxed double and compare raw `f64`, falling back to `_rat_cmp_bits` otherwise
   -- in `compile` and in the condition path. NaN must still fail every operator.
2. Consider the integer-literal case: a double against an integer literal that a double
   represents exactly can compare as `f64` too.
3. Re-measure the table above and the Clojure cast loop; record in `.kb`.
