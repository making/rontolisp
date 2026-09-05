# The numeric-to-`f64` coercion is ONE function, not an inlined ladder

**Invariant: no site emits the numeric type ladder inline. Every place that wants an
unboxed `f64` out of a Lisp value emits `call FUNC_AS_F64` and nothing else.**

- `_as_f64 ((ref null eq)) -> f64` — `WasmEmitHelper.buildAsF64Body` /
  `emitAsF64FromLocal`, index `FUNC_AS_F64`, type `TYPE_BIG_TO_F64`.
- Ladder order: **`TYPE_FLOAT` FIRST**, then i31 fixnum, `TYPE_BIGNUM`, `TYPE_BIGINT`
  via `_big_to_f64`, `TYPE_RATIO`. Tiers: `.kb/wasm-bignum.md`.
- The final arm is CHECKED (test-then-cast), never a bare `ref.cast`: a NON-number lands
  in `_type_err_num` — catchable `Expected number, got: <prin1>` in EH mode, an
  `unreachable` trap outside it (`.kb/error-handling.md`).
- The ladder is ~80 bytes per OPERAND, not per operation; sharing also stops
  `castFloatGetF64` calling `ctx.allocTemp()` per site (compile-path temps are never
  released).
- Both optimize levels emit the call — not a `prefersSizeOverSpeed()` trade
  (`.kb/wasm-int-fusion.md`, `.kb/wasm-unboxed-locals.md`).
- A new tier means editing `emitAsF64FromLocal` alone; if `_as_f64` gets hot, add a fast
  path inside it, never a return to inlining.
- `--no-gc` has no ladder (`.kb/no-gc-scalar-wasm.md`); the JVM equivalent is the
  generated `_dbl` method, already out of line.
