# WASM integer expression-tree fusion (the unboxed fast path)

> Stage-2 additions (2026-07-27, todo 194): `(aref a i)` leaves read packed
> integer vectors raw, `%aset` values compile raw through `tryCompileRaw` +
> `_iv_set`, statement-position stores skip the value-as-stored box entirely,
> `(ldb (byte s p) x)` literal specs classify through their expansion, and
> calls to fusion-inlinable defuns (closed one-liner integer wrappers like
> ironclad's `mod32+`/`rol32`) substitute their bodies -- see
> `.kb/packed-integer-vectors.md` for the packed representation and the
> inlining criteria. The invariants below are unchanged; leaf registration
> moved from tree-walk order to classify (source) order to keep argument
> evaluation order under substitution.

> Stage-3 additions (2026-07-27, todo 194; 1.45 s -> **~0.93 s** on the PBKDF2
> benchmark, both wasm backends): (1) `(funcall __FLETn_f ...)` of a let-bound
> local function whose body is a closed integer tree substitutes like an
> inlinable defun (`Ctx.localIntLambdas`, registered by `WasmLetCompiler`,
> `.kb/flet-labels.md`); (2) each leaf is guarded/unboxed ONCE into an **i64
> scratch local** at the top of the bail block and re-read at every occurrence
> (previously the guard re-emitted per occurrence -- inlined bodies made that
> quadratic-ish); the i64 locals ride a new second locals run patched in by
> `WasmLispCompiler.buildLocalsAndPatch` (3-byte padded-LEB placeholder
> indices, since every eqref local precedes the run); (3) all-literal subtrees
> constant-fold exactly (overflow/zero-divisor aborts the fold); (4) unboxed
> dual-representation LOCALS (`.kb/wasm-unboxed-locals.md`) read as `RawLeaf`
> snapshots; (5) the **masked-wrap peephole**: under a non-negative literal
> `logand` mask or power-of-two `mod`, the whole `+ - *`/left-`ash`-by-literal
> subtree emits as UNCHECKED wrap-around i64 (`emitFastWrapped`; low `k <= 63`
> bits of a wrapped result equal the infinite-precision ones), so
> `mod32+`/`rol32`-shaped code pays no `_fx_*` calls at all; (6) `substituteCall`
> ROLLS BACK leaves registered by a failed substitution attempt -- without it a
> side-effecting argument of a call whose body fails to classify (the
> parameter-shaped aref) evaluated twice (a latent stage-2 bug); (7) the symbol
> `t` is built once and cached in a module global (`_t_sym`, FUNC_T_SYM /
> TYPE_T_SYM / the always-last global) -- every comparison used to allocate a
> fresh `$str_bytes` per true result, ~8% of the profile (same id = the intern
> offset of "T", so eq/print are unchanged). Pinned by
> `fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath` and the
> `flet-fusion-and-unboxed-locals` ci-spec case.

> Stage-4 additions (2026-07-27, todo 194 close-out; ~0.93 s -> **~0.70 s** on
> the PBKDF2 benchmark, both wasm backends -- JVM parity at 0.69 s):
> (1) **fused comparisons**: a binary `= < > <= >=` whose operands classify as
> integer trees emits a raw i64 compare (`tryCompileCompare`, hooked in
> `WasmComparisonCompiler`) with the generic `_rat_cmp_bits`-with-mask fallback
> from the same leaves -- NaN/float/ratio/limb-tier operands bail and keep the
> generic result; the both-plain-`ExprLeaf` shape (`(< x y)`, nothing fusable)
> keeps the generic emission unchanged. Erased `_rat_cmp`/`_rat_cmp_bits`/
> `_big_cmp` (~7%) from the profile.
> (2) **single-op fusion**: one fused op qualifies when a leaf reads raw
> (RawLeaf/ArefLeaf) or the root has a literal operand -- `(- i 2)` index math
> and the `(+ start 1)` an incf of a parameter expands to no longer pay a
> `_rat_add`/`_rat_sub` dispatch. Two plain boxed leaves still keep the generic
> single call.
> (3) **inline root boxing**: a fused site's root boxes through an i31-range
> test + `ref.i31` inline; only out-of-i31 results call `_int_new`.
> (4) **raw leaf-root stores**: `tryCompileRaw`/`compileRawStore` accept a bare
> ArefLeaf/ConstLeaf root (and a raw-local SYMBOL copies both slots directly),
> so `(setf (aref dst i) (aref src j))` copy loops move bytes without boxing.
> (5) **statement-position literals emit nothing** (defun/lambda bodies now
> compile non-tail statements via `compileForEffect`): a DOCSTRING in a hot
> defun used to `_str_build` a fresh string per call (ironclad's
> fill-block-ub8-be, ~1.6%).
> (6) **stringp tests inline**: quote-framed `TYPE_STRING` and `TYPE_CELL` test
> first; only a TYPE_CELL pays the `_charvec_to_str` normalization call --
> `(setf (aref v i) x)` on a variable place runs a stringp per store and paid
> the call unconditionally (~2%).
> (7) `expandReplace` (shared by BOTH compile backends; the interpreter keeps
> its native replace) branches the copy loop on `(listp seq2)`: an array source
> reads with `aref` (raw for packed vectors under (4)) instead of boxing every
> element through `elt`.
> Pinned by `fusedComparisonsAndRawLeafStoresMatchTheGenericPath` and the
> `fused-comparisons-and-raw-leaf-stores` ci-spec case. Post-stage-4 profile:
> UPDATE-SHA256-BLOCK ~31% + SHA256-EXPAND-BLOCK ~11% self (the fused rounds --
> wasmtime codegen is the floor), `_int_new` ~4.5% (out-of-i31 boundary
> crossings), `_iv_set` ~2.5%, residual `_rat_add` ~2% (incf of eqref
> PARAMETERS -- params have no raw representation; see the unboxed-locals
> re-evaluation trigger).

> Counted-loop leaves (2026-08-08): a `dotimes` induction variable over a
> LITERAL bound registers as a COUNTED `RawLocal` (no shadow), and its `RawLeaf`
> therefore needs no snapshot, no guard and no `_int_new` in the fallback -- it
> reads the i64 slot directly, so `(- i 2)` index math and `(+ s i)`
> accumulation fuse with nothing in front of them. That registration is at every
> optimize level, unlike everything else in this file; the classifier only sees
> it when fusion is on. `.kb/wasm-counted-loops.md`.

**Invariant: fusing an integer expression tree must never change a result, an
observable side effect, or an error shape -- the fast path is an optimization
with a total fallback, not a semantic variant.** The wasm-GC backend (Preview 1
AND `--component`; `--no-gc` is i64-native and unaffected) compiles a nested
arithmetic/bitwise tree over `+ - * mod rem logand logior logxor lognot ash`
(plus `1+`/`1-`, which normalize into `+`/`-` with a constant 1) into ONE
unboxed evaluation: the non-constant leaves are evaluated once, left to right,
into scratch eqref locals; the interior stays raw `i64` on the wasm stack; only
the root boxes, through `_int_new`. Without this every interior operation pays a
generic-helper call plus an unbox/re-box round trip, and outside the i31 range
each re-box ALLOCATES a `TYPE_BIGNUM` -- boxing traffic was ~45% of the
PBKDF2-HMAC-SHA256 profile (todo 194 stage 1; 2.9 s -> ~2.0 s on both wasm
backends, JVM 0.69 s, 2026-07-27, wasmtime 47.0.2).

## How exactness survives the raw path

- **Per-leaf guard**: each leaf load inlines the `_fx_val` semantics -- an i31
  unboxes, a `TYPE_BIGNUM` reads its i64 field, ANYTHING else (float, ratio,
  `TYPE_BIGINT`, non-number) branches to the fallback. So a float reaching a
  fused site simply never takes the raw path.
- **Per-operation overflow check**: `_fx_add`/`_fx_sub` (the `_big_add` sign
  trick), `_fx_mul` (the `_big_mul` clz-magnitude guard -- conservative: the
  borderline band bails even when the product would fit), `_fx_ash` (left shift
  kept only when it shifts back) each return `(i64, i32 flag)`; a non-zero flag
  `br_if`s to the fallback. `logand/logior/logxor/lognot` need no check (i64
  two's complement agrees with infinite precision on every value it holds), and
  `mod`/`rem` results cannot leave the range (`_fx_mod`/`_fx_rem` trap
  explicitly on a zero divisor so the generic `_big_divrem` trap shape is
  preserved).
- **The fallback recomputes the WHOLE tree from the SAME leaf locals** through
  the generic helpers the per-op compilers call (`_rat_add`-family, `_big_*`
  bitwise) -- identical results bit for bit, including promotion into the limb
  tier and `.kb/wasm-bignum.md`'s narrowest-tier invariant; the operations are
  pure, so recomputation is safe, and the leaves' side effects ran exactly once
  before the blocks were entered.
- Emitted shape: `block $done (result eqref) { block $bail { fast; _int_new;
  br $done } fallback } end`. A taken `br_if` discards the partial i64 operand
  stack (wasm branch semantics), which is why the fast path needs no i64
  scratch locals -- the checks that need an operand twice live in the `_fx_*`
  helpers, whose params are locals.

Two strength reductions ride the fast path (both proven exact for ANY i64
input, so they skip the helper call entirely): `(mod x 2^k)` with a positive
power-of-two literal is `x & (2^k - 1)` (two's complement makes that the
divisor-signed CL mod), and `(ash x -k)` with a literal non-positive count is
an arithmetic right shift clamped at 63.

## `--optimize=size` turns the whole thing off

**The double emission is the price of the speed, and a build may decline to pay
it.** `WasmIntFusionCompiler.speedTradesEnabled(ctx)` (`ctx.optimize` ->
`OptimizeLevel.prefersSizeOverSpeed()`) is read at the three entry points --
`tryCompile`, `tryCompileCompare`, `tryCompileRaw` (`tryCompileLocalCall`
delegates to the first) -- and returning false there emits NOTHING, so every
caller falls through to the per-op path it already had. The same predicate gates
`WasmLetCompiler`'s unboxed-local eligibility, and the two are ONE switch on
purpose: a raw local with fusion off bails into its boxed shadow at every
assignment, which is both slower and larger than either end (the four-way
measurement lives in `.kb/optimize-dead-code-elimination.md`). Worth **-24.8%**
on the ironclad demo, at 3.8x the run time on its PBKDF2 loop.

That the generic path still answers identically is not a hope: it is the same
fallback every bail already takes, pinned between the two levels by
`WasmLispCompilerIntegrationTest.theSizeLevelDeclinesTheSpeedTradesWithoutChangingAnyResult`
(and, the broadest check available, the whole `ci-spec.yaml` corpus compiled at
both levels producing identical output).

## When fusion does NOT trigger (and must keep not triggering)

- Under `--optimize=size` (above), which is the only reason the fast path is
  optional at all -- everything else here is a shape it cannot handle.
- A single fusable operation with neither a raw-reading leaf (unboxed local /
  packed aref) nor a literal operand -- two plain boxed leaves under one op run
  no leaner fused, so the generic call keeps owning that shape (and the
  emission of existing generic code stays byte-stable). More than 64 ops or 32
  expression leaves (the site emits the tree twice, and
  `.kb/wasm-function-body-size.md` bounds body growth).
- A node whose immediate argument is a literal double -- the node becomes an
  unfused leaf so the `hasDoubleLiteral` f64 literal path keeps owning it
  (same for literal ratios / big integers).
- Async resume bodies (`ctx.asyncResume != null`): the await spine/hoist
  analysis owns argument shapes there.
- A call to an asyncMode `rontolisp:async-defun` whose rewritten plain defun
  would qualify textually (a one-form integer body): the name never enters
  `Ctx.inlinableDefuns`, because a call must answer the `TYPE_FUTURE` its
  entry+resume state machine builds -- splicing the raw body handed a
  synchronous caller the value where every other backend hands a future
  (`.kb/async-await.md`).
- Division (`/`) is never fused (exact ratios). Binary comparisons fuse through
  their own entry point (`tryCompileCompare`, stage 4) -- but only when a side
  is fusable; `(< x y)` over two plain leaves stays generic.

## Mechanics

`WasmIntFusionCompiler` (classify -> collect leaves -> emit fast + fallback),
hooked into `WasmExprCompiler`'s `+ - * mod rem logand logior logxor lognot
ash` cases ahead of the per-op compilers. `WasmFxRuntimeBuilder` builds the
`_fx_*` helpers (`FUNC_FX_VAL .. FUNC_FX_REM`, appended after the limb block;
`FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase on `FX_FUNC_LAST`). Three new
signature types `TYPE_FX_VAL`/`TYPE_FX_BIN`/`TYPE_FX_DIV` (54-56; the first
multi-result function types in the module -- `(i64, i32)` pairs), after which
the `--simd`/async/instance blocks shift via `FX_TYPE_LAST`. The block is
always present, like the limb runtime (`--optimize` tree-shakes what a program
does not reach; hello-world `--optimize` 21,952 -> 21,981 bytes); `_fx_val`
itself is emitted for completeness but the leaf guard inlines its body at every
use (the call wrapper alone profiled at 16.5%).

Pinning tests: `WasmLispCompilerIntegrationTest.fusedIntegerExpressionTreesMatchTheGenericPath`
(overflow promotion, float/ratio bail, mod/rem/ash sign semantics, the two
strength reductions, side-effects-once) and the `fused-integer-expression-trees`
ci-spec case (all four backends).

## Re-evaluation triggers

- If wasmtime gains cross-function inlining that erases small-helper call
  overhead, the `_fx_*` helper-call design could flatten further -- re-profile
  before restructuring; the stage-1 profile's remaining costs were `_int_new`
  root boxing (~9%), sequence-normalization helpers in hmac re-keying
  (`_charvec_to_str`/`_str_build`, ~11%) and boxed `aref`/`aset` traffic, which
  fusion cannot reach (todo 194 stage 2: unboxed i64 locals + typed integer
  arrays).
- If the fusable-op set grows, every new op needs BOTH an exact raw-path story
  (overflow/edge analysis) and a generic fallback emission that matches the
  per-op compiler's helper choice exactly.
