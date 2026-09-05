# WASM integer expression-tree fusion (the unboxed fast path)

**Invariant: fusing an integer expression tree must never change a result, an observable side
effect, or an error shape -- the fast path is an optimization with a total fallback, not a
semantic variant.**

The wasm-GC backend (Preview 1 AND `--component`; `--no-gc` is i64-native and unaffected)
compiles a nested tree over `+ - * mod rem logand logior logxor lognot ash` (plus `1+`/`1-`,
normalized) into ONE unboxed evaluation: non-constant leaves are evaluated once, left to right,
into scratch locals; the interior stays raw `i64` on the wasm stack; only the root boxes,
through `_int_new`.

## How exactness survives the raw path

- **Per-leaf guard**: each leaf load inlines `_fx_val` semantics -- i31 unboxes, `TYPE_BIGNUM`
  reads its i64 field, ANYTHING else (float, ratio, `TYPE_BIGINT`, non-number) branches to the
  fallback.
- **Per-operation overflow check**: `_fx_add`/`_fx_sub`, `_fx_mul` (conservative -- the
  borderline band bails even when the product would fit), `_fx_ash` each return
  `(i64, i32 flag)`; a non-zero flag `br_if`s to the fallback. Bitwise ops need no check;
  `_fx_mod`/`_fx_rem` trap explicitly on a zero divisor, preserving the generic `_big_divrem`
  trap shape.
- **The fallback recomputes the WHOLE tree from the SAME leaf locals** through the generic
  helpers, identical bit for bit, including `.kb/wasm-bignum.md`'s narrowest-tier invariant.
- Emitted shape (the specification):
  `block $done (result eqref) { block $bail { fast; _int_new; br $done } fallback } end`.
- Exact strength reductions: `(mod x 2^k)` with a positive power-of-two literal is
  `x & (2^k - 1)`; `(ash x -k)` with a literal non-positive count is an arithmetic right shift
  clamped at 63.

## What else rides the fast path

- **Leaf kinds**: `RawLeaf` (unboxed dual-representation LOCALS, `.kb/wasm-unboxed-locals.md`),
  `ArefLeaf` (packed integer vectors, `.kb/packed-integer-vectors.md`), `ConstLeaf`. A `dotimes`
  induction variable over a LITERAL bound is a COUNTED `RawLocal` needing no snapshot, guard or
  `_int_new` -- registered at every optimize level, unlike everything else here
  (`.kb/wasm-counted-loops.md`). Each leaf is unboxed ONCE into an i64 scratch local; those
  locals ride a second locals run patched in by `WasmLispCompiler.buildLocalsAndPatch`.
  Registration is in classify (source) order, not tree-walk order.
- **Substitution**: fusion-inlinable defuns (ironclad's `mod32+`/`rol32`) and
  `(funcall __FLETn_f ...)` of a let-bound local function with a closed integer body
  (`Ctx.localIntLambdas`, `.kb/flet-labels.md`) splice their bodies; a body wholly `(aref P I)`
  over parameters/literals substitutes as an `ArefLeaf` over the CALLER's operands
  (`inlineArefOperand`). Traps: `eligibleLocalLambda` must skip leading `(declare ...)` inside
  the flet lowering's `(block name ...)` wrapper (via `singleBodyExpr`), or a declared flet param
  silently demotes to per-iteration funcall dispatch; **`substituteCall` ROLLS BACK leaves
  registered by a failed attempt**, or a side-effecting argument evaluates twice.
- Peepholes: `emitFastWrapped` (unchecked wrap-around i64 under a non-negative literal `logand`
  mask or power-of-two `mod`); `emitInlineCheckedLiteral` (a literal `+`/`-` operand becomes a
  plain i64 op plus ONE signed compare, exact for every i64 incl. `Long.MIN_VALUE`, `k = 0`
  emitting nothing; one shared slot `Ctx.fxLitTempSlot`); inline root boxing (i31 test +
  `ref.i31`, only out-of-i31 calls `_int_new`); all-literal subtrees constant-fold exactly
  (overflow / zero divisor aborts the fold).
- **Fused comparisons**: binary `= < > <= >=` via `tryCompileCompare` (hooked in
  `WasmComparisonCompiler`, `_rat_cmp_bits`-with-mask fallback); the `boxResult = false` variant
  lets `WasmWhileCompiler`/`WasmIfCompiler` test the raw i32 via `tryCompileConditionI32`.
- **Raw stores**: `%aset` values compile raw through `tryCompileRaw` + `_iv_set`;
  `tryCompileRaw`/`compileRawStore` accept a bare ArefLeaf/ConstLeaf root, so
  `(setf (aref dst i) (aref src j))` loops move bytes without boxing.
- `(ldb (byte s p) x)` with literal specs classifies through its expansion. The symbol `t` is
  built once in a module global (`_t_sym`, FUNC_T_SYM / TYPE_T_SYM / the always-last global);
  same id = the intern offset of "T".

## When fusion does NOT trigger (and must keep not triggering)

- Under `--optimize=size`: `WasmIntFusionCompiler.speedTradesEnabled(ctx)`
  (`OptimizeLevel.prefersSizeOverSpeed()`) is read at `tryCompile`, `tryCompileCompare`,
  `tryCompileRaw`, and returning false emits NOTHING. The same predicate gates
  `WasmLetCompiler`'s unboxed-local eligibility, ONE switch on purpose -- a raw local with fusion
  off bails into its boxed shadow at every assignment, slower AND larger than either end
  (`.kb/optimize-dead-code-elimination.md`).
- A single fusable op with neither a raw-reading leaf nor a literal operand.
- More than **64 ops or 32 expression leaves** (the site emits the tree twice;
  `.kb/wasm-function-body-size.md`).
- A node whose immediate argument is a literal double (the `hasDoubleLiteral` f64 path keeps
  owning it; same for literal ratios / bigints).
- Async resume bodies (`ctx.asyncResume != null`), and a call to an asyncMode
  `rontolisp:async-defun` -- the name never enters `Ctx.inlinableDefuns` because a call must
  answer the `TYPE_FUTURE` its state machine builds (`.kb/async-await.md`).
- Division (`/`) is never fused (exact ratios).

## Mechanics

`WasmIntFusionCompiler` (classify -> collect leaves -> emit fast + fallback), hooked into
`WasmExprCompiler`'s per-op cases ahead of the per-op compilers. `WasmFxRuntimeBuilder` builds the
`_fx_*` helpers (`FUNC_FX_VAL .. FUNC_FX_REM`, appended after the limb block;
`FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase on `FX_FUNC_LAST`). Three signature types
`TYPE_FX_VAL`/`TYPE_FX_BIN`/`TYPE_FX_DIV` (54-56; the first multi-result function types in the
module), after which the `--simd`/async/instance blocks shift via `FX_TYPE_LAST`. The block is
always present, like the limb runtime.

**Profiling trap**: wasmtime 47's guest profiler attributes samples inside a LARGE function to
small CALLEE functions -- a 60k-instruction fused body shows ~0 self while a tiny accessor shows
an impossible 30-60%. Wall-clock A/B against a standalone reproduction is the reliable oracle.

## Tests

`WasmLispCompilerIntegrationTest.fusedIntegerExpressionTreesMatchTheGenericPath`,
`.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`,
`.fusedComparisonsAndRawLeafStoresMatchTheGenericPath`,
`.theSizeLevelDeclinesTheSpeedTradesWithoutChangingAnyResult`; ci-spec
`fused-integer-expression-trees`, `flet-fusion-and-unboxed-locals`,
`fused-comparisons-and-raw-leaf-stores`.
