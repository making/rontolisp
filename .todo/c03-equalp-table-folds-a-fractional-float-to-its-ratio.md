# c03. An `equalp` table folds a fractional float to the ratio it equals

Difficulty: Medium

`LispEquality.equalpKey` folds a float to the integer it equals but leaves a float with a
fraction as its own key, so in an `equalp` table a key stored as `0.5` is missed by
`(gethash 1/2 h)` although `(equalp 0.5 1/2)` is true -- the second deviation in
`.kb/hash-tables.md`, "`equalp` is a KEY FOLD". It was kept because the WASM ratio held two
i32 components, too narrow for a float's power-of-two denominator. Since 2026-10-03 WASM ratio
components are exact integers (`.kb/wasm-bignum.md`, "Ratios"), so every backend can fold the
fraction the same way.

## Plan

- Interpreter: the float arm of `LispEquality.equalpKey` answers the float's exact rational
  (`mantissa * 2^exponent` through `LispRatio.valueOf`); the integer case it has now is the
  denominator-one instance of the same value. -0.0 and 0 stay one key; NaN and the infinities
  stay their own.
- JVM: the equalp key fold of the generated hash runtime, the same value.
- WASM: `_equalp_key` (`WasmEqualpKeyRuntimeBuilder`) already reads mantissa and exponent from
  the bits; a negative exponent builds the ratio through `_rat_new(mantissa, 2^-e)`.
- Remove the deviation from `.kb/hash-tables.md`, `LispEquality.equalpKey`'s doc and
  `LispEvaluatorTest.anEqualpHashTableFoldsAFloatToTheIntegerItEquals`'s comment.

## Pin

- ci-spec `equalp-hash-table-key-fold`: `0.5` / `1/2` and `0.1` /
  `3602879701896397/36028797018963968` as one key each, on all four backends.
- The `LispEvaluatorTest` case above and its JVM/WASM twins.
