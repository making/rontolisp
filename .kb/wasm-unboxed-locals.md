# WASM unboxed (dual-representation) locals

**Invariant: the dual representation never changes a result, an error shape, or an observable side
effect** -- alternative STORAGE for the same Lisp value, with a total boxed escape hatch. wasm-GC
only (Preview 1 and `--component`); interpreter / JVM / `--no-gc` untouched.

## Representation

`WasmIntFusionCompiler.RawLocal`, registered in `Ctx.rawLocals` by `WasmLetCompiler`; no ordinary
eqref local. An i64 slot (second locals run) holds the raw value; an eqref shadow slot holds the
module's raw-local sentinel while the raw value is authoritative, any other content meaning "use the
shadow, INCLUDING nil". Sentinel: a private `TYPE_CELL` in an immutable module global
(`Ctx.rawSentinelGlobalIndex`, always the last global).

- **Trap: null cannot be the marker** -- nil IS `ref.null`, so `(let ((s nil)) ... (setq s (+ k 1)))`
  would read the stale raw slot.
- Writes: `WasmSetqCompiler` -> `WasmIntFusionCompiler.compileRawStore` (fused: raw + sentinel;
  bail: BOXED into the shadow, and the local stays boxed until the next raw store).
- Fused reads are a `RawLeaf`, SNAPSHOTTED at the leaf's source position so a later leaf's setq
  cannot change what an earlier read observed. Reads elsewhere:
  `WasmExprCompiler.compileSymbolRef` -> `emitRawLocalBoxedRead` -> shared out-of-line `_ub_read`
  (`FUNC_UB_READ`/`TYPE_UB_READ`, appended after the last fixed helper and type so no index above
  them shifts; `WasmFxRuntimeBuilder.buildUbReadBody`).
- **Trap**: statement position must propagate -- `WasmExprCompiler.compileForEffect` routes
  `let`/`let*`/`progn` through forEffect overloads (`WasmLetCompiler.compile(cons, ctx, true)`,
  `WasmPrognCompiler.compileForEffect`); otherwise a statement-position `let` tailing in `setf`
  re-reads the just-stored local through `_ub_read` only to DROP it.
- `counted = true` (`shadowSlot = -1`) is a `dotimes` induction variable over a literal bound: no
  shadow, guard or fallback, at every optimize level. `.kb/wasm-counted-loops.md`.

## Eligibility (`WasmLetCompiler`) -- all must hold

- `WasmIntFusionCompiler.speedTradesEnabled(ctx)`, false under `--optimize=size` -- one switch with
  fusion (`.kb/optimize-dead-code-elimination.md`); `-Drontolisp.debug.norawlocals` switches this
  half alone.
- Not special, not captured by a nested lambda (`FreeVarAnalyzer`), not a duplicate binding name in
  the same `let`; not under `--dynamic`, not in an async body (`ctx.asyncResume`/await).
- **Top level is NOT excluded** (`.kb/eval-runtime.md`); a chunked top level reaches the sentinel
  through `WasmAsyncEmit.freshCtx`, which must carry `rawSentinelGlobalIndex`.
- At least one assignment is integer-tree-SHAPED (`isRawAssignShaped`) -- a heuristic; precision
  affects performance only, since `compileRawStore` boxes what does not classify.
- Scoping mirrors `ctx.locals`. A name in `rawLocals` is never in `ctx.locals`
  (`WasmDefvarCompiler` checks both).

## The i64 locals run (`buildLocalsAndPatch`)

An i64 slot's absolute index is unknowable mid-emission, so references emit as **3-byte fixed-width
placeholders** in `Ctx.i64LocalRefs` and every body finalizer routes through
`WasmLispCompiler.buildLocalsAndPatch`, resolving them to `ctx.nextLocal + slot`. Placeholders are
**spliced out, not overwritten in place** (`.kb/wasm-shortest-encoding.md`); **the recorded offsets
must therefore be ASCENDING** -- they are, and the splice loop throws rather than assume it. A body
with no i64 locals is byte-identical to the pre-feature emission. `Ctx.nextI64Local` is saved and
restored per fused site; `maxI64Locals` is the declaration's high-water mark.

## Tests

`WasmLispCompilerIntegrationTest#fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`; ci-spec
`flet-fusion-and-unboxed-locals`.
