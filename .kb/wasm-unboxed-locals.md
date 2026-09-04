# WASM unboxed (dual-representation) locals

**Invariant: the dual representation never changes a result, an error shape, or an observable side
effect** -- alternative STORAGE for the same Lisp value, with a total boxed escape hatch. wasm-GC
only (Preview 1 and `--component`); interpreter / JVM / `--no-gc` untouched.

## Representation

`WasmIntFusionCompiler.RawLocal` (`counted = false`), registered in `Ctx.rawLocals` by
`WasmLetCompiler`; no ordinary eqref local.

- **i64 slot** (second locals run): the raw value.
- **Boxed shadow slot** (eqref): holds the module's raw-local sentinel while the raw value is
  authoritative; any other content means "use the shadow, INCLUDING nil". Sentinel: a private
  `TYPE_CELL` in an immutable module global (constant-expression `struct.new`;
  `Ctx.rawSentinelGlobalIndex`, always the last global, next to the `_t_sym` cache).
- **Trap: null cannot be the marker** -- nil IS `ref.null`, so
  `(let ((frac-start nil)) ... (setq frac-start (+ k 1)))` would read the stale raw slot.

## Writes

`psetq`/`setf`/`rotatef`/`incf`/`multiple-value-setq` all expand to `setq` by compile time ->
`WasmSetqCompiler` -> `WasmIntFusionCompiler.compileRawStore`. Fused: raw + sentinel. Bail (i64
overflow to the limb tier, a float, any non-integer-tree value): BOXED result into the shadow; the
local stays boxed until the next raw store.

## Reads

- Fused tree: a `RawLeaf`. The (i64, shadow) pair is SNAPSHOTTED at the leaf's source position (a
  later leaf's setq must not change what an earlier read observed), resolved once in the unbox
  hoist: sentinel -> raw; i31/`TYPE_BIGNUM` -> unbox; else fall back. A local unassigned inside
  the site shares ONE snapshot.
- Elsewhere: `WasmExprCompiler.compileSymbolRef` -> `emitRawLocalBoxedRead` -> shared `_ub_read`
  (`FUNC_UB_READ`/`TYPE_UB_READ`, appended after the last fixed helper and type so no index above
  them shifts; `WasmFxRuntimeBuilder.buildUbReadBody`). Out of line because cold library sites
  dominate.
- Fused-site FALLBACK read of a snapshot: a bare `_int_new` call, stays inline.
- Statement position must propagate: `WasmExprCompiler.compileForEffect` routes
  `let`/`let*`/`progn` through forEffect overloads (`WasmLetCompiler.compile(cons, ctx, true)`,
  `WasmPrognCompiler.compileForEffect`) so their LAST form is a statement. **Trap**: otherwise a
  statement-position `let` whose tail is a `setf` compiles via `compilePair` and re-reads the
  just-stored raw local through `_ub_read` only for the statement to DROP it.

## `counted = true`

`shadowSlot = -1`: a `dotimes` induction variable over a LITERAL bound -- no shadow, guard or
fallback; reads resolve to the slot. Shares `Ctx.rawLocals` so shadowing, `defvar` collision and
symbol resolution need no new rules; at EVERY optimize level. All of it:
`.kb/wasm-counted-loops.md`.

## Eligibility (WasmLetCompiler) -- all must hold

- `WasmIntFusionCompiler.speedTradesEnabled(ctx)`, false under `--optimize=size`: one switch with
  fusion, since without fusion every assignment bails and every read is `_ub_read`, larger and
  slower than dropping both (`.kb/optimize-dead-code-elimination.md`).
  `-Drontolisp.debug.norawlocals` switches this half alone, for profiling.
- not special, not captured by a nested lambda (`FreeVarAnalyzer`; captures need cells), not a
  duplicate binding name in the same `let`.
- not under `--dynamic`, not in an async body (`ctx.asyncResume`/await -- the spill machinery owns
  locals there). **Top level is NOT excluded**: the eval mirror writes only names with a global
  backing store (`.kb/eval-runtime.md`), and a raw-eligible binding is neither special nor
  captured. A chunked top level reaches the sentinel through `WasmAsyncEmit.freshCtx`, which must
  carry `rawSentinelGlobalIndex`.
- at least one assignment (init, or a `setq`/`setf`-pair value found by a shadowing-blind body
  walk) is integer-tree-SHAPED (`isRawAssignShaped`) -- a heuristic; precision affects performance
  only, since `compileRawStore` boxes what does not classify. A bare raw-local SYMBOL value copies
  BOTH slots (every tier, no guard); a folded constant stores `i64.const` with no blocks; `aref`
  and an outer raw-local symbol count as raw-shaped.
- Scoping mirrors `ctx.locals`: registrations live for the body, inner `let`s REMOVE shadowed
  names, map + i64 watermark restore on exit. A name in `rawLocals` is never in `ctx.locals`
  (`WasmDefvarCompiler` checks both).

## The i64 locals run (buildLocalsAndPatch)

The eqref run's length is known only after the body is emitted, so an i64 slot's absolute index is
unknowable mid-emission: references emit as **3-byte fixed-width placeholders** in
`Ctx.i64LocalRefs`, and every body finalizer (defuns, lambdas, `_start`, the toplevel/async chunk
emitters) routes through `WasmLispCompiler.buildLocalsAndPatch`, resolving them to
`ctx.nextLocal + slot` and appending the `[eqref-run, i64-run]` declaration. Placeholders are
**spliced out, not overwritten in place**, so an index costs only the LEB length it needs
(`.kb/wasm-shortest-encoding.md`); **the recorded offsets must therefore be ASCENDING** -- they are
(one forward emission walk) and the splice loop throws rather than assume it. A body with no i64
locals is byte-identical to the pre-feature emission. Fused sites save/restore `Ctx.nextI64Local`
(slots reused; `maxI64Locals` is the declaration's high-water mark); let-scope raw locals allocate
below that watermark and restore at scope end.

## Tests

`WasmLispCompilerIntegrationTest#fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath` (overflow
promotion through the shadow, non-integer assignment, masked-wrap exactness, side-effects-once
under a failed substitution); ci-spec `flet-fusion-and-unboxed-locals` (all four backends).

## Re-evaluation triggers

- Static local types could drop the shadow for provably-u32 locals; it exists because a raw local
  can leave the i64 tier at ANY assignment and non-fused readers need an eqref.
- Params are NOT eligible (eqref by signature); a raw COPY of a hot integer param would extend the
  win to defun bodies whose temps are parameters.
- `_ub_read`'s body is the exact byte sequence to inline back if its call ever shows in a profile.
