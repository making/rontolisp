# WASM integer expression-tree fusion (the unboxed fast path)

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

## When fusion does NOT trigger (and must keep not triggering)

- Fewer than two fusable operations (a single op is already one generic call),
  more than 64 ops or 32 expression leaves (the site emits the tree twice, and
  `.kb/wasm-function-body-size.md` bounds body growth).
- A node whose immediate argument is a literal double -- the node becomes an
  unfused leaf so the `hasDoubleLiteral` f64 literal path keeps owning it
  (same for literal ratios / big integers).
- Async resume bodies (`ctx.asyncResume != null`): the await spine/hoist
  analysis owns argument shapes there.
- Division (`/`) is never fused (exact ratios), nor are comparisons.

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
