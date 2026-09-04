# The numeric-to-`f64` coercion is ONE function, not an inlined ladder

**Invariant: no site emits the numeric type ladder inline. Every place that wants an
unboxed `f64` out of a Lisp value emits `call FUNC_AS_F64` and nothing else.**

- `_as_f64 ((ref null eq)) -> f64` — `WasmEmitHelper.buildAsF64Body` /
  `emitAsF64FromLocal`, index `FUNC_AS_F64`, type `TYPE_BIG_TO_F64`.
- Ladder order: **`TYPE_FLOAT` FIRST** (cast + read field), then i31 fixnum,
  `TYPE_BIGNUM` (i64 field), `TYPE_BIGINT` via `_big_to_f64`, `TYPE_RATIO`
  (num/den, float contagion). Tiers: `.kb/wasm-bignum.md`.
- The final arm is CHECKED (test-then-cast), never a bare `ref.cast`: a NON-number lands
  in `_type_err_num` — a catchable `Expected number, got: <prin1>` in EH mode, an
  `unreachable` trap outside it (`.kb/error-handling.md`).

## Why a function, and at the DEFAULT optimize level

- The ladder is a five-way `ref.test` chain, ~80 bytes per site, and it is per OPERAND,
  not per operation.
- `WasmRatioRuntimeBuilder.emitLocalToF64` used to carry its own copy across
  `_rat_add`/`_rat_div`/`_rat_rem`/`_rat_cmp`/`_rat_cmp_bits`.
- Sharing also stops `castFloatGetF64` calling `ctx.allocTemp()` per site (compile-path
  temps are never released, so every ladder widened the enclosing local vector).
- Both optimize levels emit the call — NOT one of the trades `prefersSizeOverSpeed()`
  switches (`.kb/wasm-int-fusion.md`, `.kb/wasm-unboxed-locals.md`).

## Re-evaluation trigger

If a future tier makes `_as_f64` hot, add a FAST PATH inside the shared function (or an
i31-only guard before the call) — **not** a return to inlining. Adding a tier means
editing `emitAsF64FromLocal` alone; being the only copy is the property worth keeping.

`--no-gc` has no ladder at all (`.kb/no-gc-scalar-wasm.md`); the JVM equivalent is the
generated `_dbl` method, already out of line.
