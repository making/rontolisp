# WASM integer expression-tree fusion (the unboxed fast path)

**Invariant: fusing an integer expression tree must never change a result, an observable
side effect, or an error shape -- the fast path is an optimization with a total fallback,
not a semantic variant.**

The wasm-GC backend (Preview 1 AND `--component`; `--no-gc` is i64-native and unaffected)
compiles a nested arithmetic/bitwise tree over `+ - * mod rem logand logior logxor lognot
ash` (plus `1+`/`1-`, normalized into `+`/`-` with constant 1) into ONE unboxed
evaluation: non-constant leaves are evaluated once, left to right, into scratch eqref
locals; the interior stays raw `i64` on the wasm stack; only the root boxes, through
`_int_new`. Without it every interior op pays a generic-helper call plus an unbox/re-box
round trip, and outside the i31 range each re-box ALLOCATES a `TYPE_BIGNUM`.

## How exactness survives the raw path

- **Per-leaf guard**: each leaf load inlines the `_fx_val` semantics -- an i31 unboxes, a
  `TYPE_BIGNUM` reads its i64 field, ANYTHING else (float, ratio, `TYPE_BIGINT`,
  non-number) branches to the fallback.
- **Per-operation overflow check**: `_fx_add`/`_fx_sub` (the `_big_add` sign trick),
  `_fx_mul` (the `_big_mul` clz-magnitude guard -- conservative: the borderline band bails
  even when the product would fit), `_fx_ash` (left shift kept only when it shifts back)
  each return `(i64, i32 flag)`; a non-zero flag `br_if`s to the fallback.
  `logand/logior/logxor/lognot` need no check (i64 two's complement agrees with infinite
  precision on every value it holds); `mod`/`rem` results cannot leave the range
  (`_fx_mod`/`_fx_rem` trap explicitly on a zero divisor, preserving the generic
  `_big_divrem` trap shape).
- **The fallback recomputes the WHOLE tree from the SAME leaf locals** through the generic
  helpers the per-op compilers call (`_rat_add`-family, `_big_*` bitwise) -- identical bit
  for bit, including promotion into the limb tier and `.kb/wasm-bignum.md`'s
  narrowest-tier invariant. The ops are pure, so recomputation is safe, and the leaves'
  side effects ran once before the blocks were entered.
- Emitted shape: `block $done (result eqref) { block $bail { fast; _int_new; br $done }
  fallback } end`. A taken `br_if` discards the partial i64 operand stack, which is why
  the fast path needs no i64 scratch locals (checks needing an operand twice live in the
  `_fx_*` helpers, whose params are locals).
- Two exact strength reductions skip the helper call: `(mod x 2^k)` with a positive
  power-of-two literal is `x & (2^k - 1)` (two's complement gives the divisor-signed CL
  mod), and `(ash x -k)` with a literal non-positive count is an arithmetic right shift
  clamped at 63.

## What else rides the fast path

- **Leaf kinds**: `RawLeaf` snapshots of unboxed dual-representation LOCALS
  (`.kb/wasm-unboxed-locals.md`), `ArefLeaf` reads of packed integer vectors
  (`.kb/packed-integer-vectors.md`), `ConstLeaf`. A `dotimes` induction variable over a
  LITERAL bound registers as a COUNTED `RawLocal` (no shadow), so its `RawLeaf` needs no
  snapshot, guard or `_int_new` in the fallback -- that registration happens at every
  optimize level, unlike everything else here (`.kb/wasm-counted-loops.md`).
- Each leaf is guarded/unboxed ONCE into an i64 scratch local at the top of the bail block
  and re-read at every occurrence. Those i64 locals ride a second locals run patched in by
  `WasmLispCompiler.buildLocalsAndPatch` (3-byte padded-LEB placeholder indices, since
  every eqref local precedes the run).
- **Substitution**: fusion-inlinable defuns (closed one-liner integer wrappers like
  ironclad's `mod32+`/`rol32`) and `(funcall __FLETn_f ...)` of a let-bound local function
  with a closed integer body (`Ctx.localIntLambdas`, registered by `WasmLetCompiler`,
  `.kb/flet-labels.md`) splice their bodies. `eligibleLocalLambda` skips leading
  `(declare ...)` forms inside the flet lowering's `(block name ...)` wrapper (via
  `singleBodyExpr`) -- otherwise a declared flet param silently demotes to per-iteration
  funcall dispatch. An inlinable defun whose whole body is `(aref P I)` over
  parameters/literals (a `:type vector` struct accessor) substitutes as an `ArefLeaf` over
  the CALLER's operands when each is a bare symbol or literal (`inlineArefOperand`).
  **`substituteCall` ROLLS BACK leaves registered by a failed attempt** -- without it a
  side-effecting argument of a call whose body fails to classify evaluates twice.
- Leaf registration is in classify (source) order, not tree-walk order, to keep argument
  evaluation order under substitution. All-literal subtrees constant-fold exactly
  (overflow / zero divisor aborts the fold).
- **Masked-wrap peephole**: under a non-negative literal `logand` mask or power-of-two
  `mod`, the whole `+ - *`/left-`ash`-by-literal subtree emits as UNCHECKED wrap-around
  i64 (`emitFastWrapped`; the low `k <= 63` bits of a wrapped result equal the
  infinite-precision ones).
- **Inline literal add/sub check** (`emitInlineCheckedLiteral`): a fused `+`/`-` with a
  LITERAL operand emits a plain i64 add/sub plus ONE signed compare against the recomputed
  accumulator (exact for every i64, `Long.MIN_VALUE` included; `k = 0` emits nothing)
  instead of `_fx_add`/`_fx_sub`. One shared i64 scratch slot per site
  (`Ctx.fxLitTempSlot`, reset after each site's `evalLeaves`).
- **Fused comparisons**: binary `= < > <= >=` emits a raw i64 compare (`tryCompileCompare`,
  hooked in `WasmComparisonCompiler`) with the generic `_rat_cmp_bits`-with-mask fallback
  from the same leaves. Its `boxResult = false` variant lets
  `WasmWhileCompiler`/`WasmIfCompiler` test the raw i32 via
  `WasmComparisonCompiler.tryCompileConditionI32` instead of boxing t/nil to null-test it.
- **Inline root boxing**: i31-range test + `ref.i31` inline; only out-of-i31 results call
  `_int_new`.
- **Raw stores**: `%aset` values compile raw through `tryCompileRaw` + `_iv_set`;
  `tryCompileRaw`/`compileRawStore` accept a bare ArefLeaf/ConstLeaf root (a raw-local
  SYMBOL copies both slots directly), so `(setf (aref dst i) (aref src j))` loops move
  bytes without boxing. Statement-position stores skip the value-as-stored box.
- `(ldb (byte s p) x)` with literal specs classifies through its expansion.
- The symbol `t` is built once in a module global (`_t_sym`, FUNC_T_SYM / TYPE_T_SYM / the
  always-last global); same id = the intern offset of "T", so eq/print are unchanged.

Adjacent (same sweep, other files' code): statement position propagates through
`let`/`let*`/`progn`; statement-position literals emit nothing; inline `stringp` tries
`TYPE_STRING` then `TYPE_CELL`, the latter calling `_charvec_p` (constant-time marker
test), never the LINEAR `_charvec_to_str` (`.kb/adjustable-arrays.md`); `expandReplace`
(both compile backends; the interpreter keeps native `replace`) branches on `(listp seq2)`
so an array source reads with `aref`.

## `--optimize=size` turns the whole thing off

The double emission is the price of the speed. `WasmIntFusionCompiler.speedTradesEnabled(ctx)`
(`ctx.optimize` -> `OptimizeLevel.prefersSizeOverSpeed()`) is read at the three entry
points -- `tryCompile`, `tryCompileCompare`, `tryCompileRaw` (`tryCompileLocalCall`
delegates to the first) -- and returning false emits NOTHING, so every caller falls through
to its per-op path. The same predicate gates `WasmLetCompiler`'s unboxed-local
eligibility, and the two are ONE switch on purpose: a raw local with fusion off bails into
its boxed shadow at every assignment, slower AND larger than either end (measurements in
`.kb/optimize-dead-code-elimination.md`).

## When fusion does NOT trigger (and must keep not triggering)

- Under `--optimize=size`.
- A single fusable op with neither a raw-reading leaf (unboxed local / packed aref) nor a
  literal operand -- two plain boxed leaves under one op run no leaner fused.
- More than 64 ops or 32 expression leaves (the site emits the tree twice;
  `.kb/wasm-function-body-size.md` bounds body growth).
- A node whose immediate argument is a literal double -- it becomes an unfused leaf so the
  `hasDoubleLiteral` f64 literal path keeps owning it (same for literal ratios / bigints).
- Async resume bodies (`ctx.asyncResume != null`): the await spine/hoist analysis owns
  argument shapes there.
- A call to an asyncMode `rontolisp:async-defun` whose rewritten plain defun would qualify
  textually: the name never enters `Ctx.inlinableDefuns`, because a call must answer the
  `TYPE_FUTURE` its entry+resume state machine builds (`.kb/async-await.md`).
- Division (`/`) is never fused (exact ratios). `(< x y)` over two plain leaves stays
  generic.

## Mechanics

`WasmIntFusionCompiler` (classify -> collect leaves -> emit fast + fallback), hooked into
`WasmExprCompiler`'s `+ - * mod rem logand logior logxor lognot ash` cases ahead of the
per-op compilers. `WasmFxRuntimeBuilder` builds the `_fx_*` helpers
(`FUNC_FX_VAL .. FUNC_FX_REM`, appended after the limb block;
`FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase on `FX_FUNC_LAST`). Three signature types
`TYPE_FX_VAL`/`TYPE_FX_BIN`/`TYPE_FX_DIV` (54-56; the first multi-result function types in
the module -- `(i64, i32)` pairs), after which the `--simd`/async/instance blocks shift via
`FX_TYPE_LAST`. The block is always present, like the limb runtime (`--optimize`
tree-shakes what a program does not reach). `_fx_val` itself is emitted for completeness
but the leaf guard inlines its body at every use.

## Profiling trap

wasmtime 47's guest profiler attributes samples inside a LARGE function to small CALLEE
functions -- a 60k-instruction fused body shows ~0 self while a tiny accessor or `_iv_set`
shows an impossible 30-60%. Check a suspicious self% against the callee's static call
count; wall-clock A/B against a standalone reproduction is the reliable oracle.

## Re-evaluation triggers

- If wasmtime gains cross-function inlining erasing small-helper call overhead, the
  `_fx_*` design could flatten further -- re-profile first.
- If the fusable-op set grows, every new op needs BOTH an exact raw-path story
  (overflow/edge analysis) and a generic fallback emission matching the per-op compiler's
  helper choice exactly.

## Tests

- `WasmLispCompilerIntegrationTest.fusedIntegerExpressionTreesMatchTheGenericPath`
  (overflow promotion, float/ratio bail, mod/rem/ash sign semantics, the two strength
  reductions, side-effects-once)
- `.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`,
  `.fusedComparisonsAndRawLeafStoresMatchTheGenericPath`,
  `.theSizeLevelDeclinesTheSpeedTradesWithoutChangingAnyResult`
- ci-spec `fused-integer-expression-trees`, `flet-fusion-and-unboxed-locals`,
  `fused-comparisons-and-raw-leaf-stores` (all four backends)
