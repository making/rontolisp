# WASM unboxed (dual-representation) locals

**Invariant: giving a `let` binding the dual representation must never change a
result, an error shape, or an observable side effect -- it is an alternative
STORAGE for the same Lisp value, with a total boxed escape hatch.** Introduced
by todo 194 stage 3 for the wasm-GC backend (Preview 1 AND `--component`;
interpreter/JVM/`--no-gc` untouched): SHA-256's round temps and loop counters
were paying an `_int_new` box per assignment (a `TYPE_BIGNUM` allocation for
out-of-i31 u32 words) and a guarded unbox per fused read.

## Representation

An eligible binding gets NO ordinary eqref local. Instead
(`WasmIntFusionCompiler.RawLocal`, registered in `Ctx.rawLocals` by
`WasmLetCompiler`):

- an **i64 slot** (in the second locals run, see below) holding the raw value,
- a **boxed shadow slot** (ordinary eqref local): it holds the module's
  **raw-local sentinel** while the raw value is authoritative; anything else
  means "use the shadow, whatever it holds -- INCLUDING nil". The sentinel is a
  private `TYPE_CELL` instance in an immutable module global (initialized by the
  constant expression `struct.new`; `Ctx.rawSentinelGlobalIndex`, always the
  last global next to the `_t_sym` cache). Null CANNOT be the marker: nil IS
  `ref.null`, and `(let ((frac-start nil)) ... (setq frac-start (+ k 1)))`
  (jzon's number parser) must read nil back as nil, not as the stale raw slot
  -- the bug the first cut of this feature shipped and the json ci case caught.

Every assignment funnels through `WasmSetqCompiler` (`psetq`/`setf`/`rotatef`/
`incf`/`multiple-value-setq`/... all expand to `setq` by compile time) and
compiles via `WasmIntFusionCompiler.compileRawStore`: the fused fast path
stores raw and sets the shadow to the sentinel; a bail (i64 overflow promoting
to the limb tier, a float, any value that does not classify as an integer tree)
stores the BOXED result into the shadow. So promotion, floats, lists, nil --
anything -- still works: the local silently degrades to boxed until the next
raw store.

Reads:

- inside a fused tree: a `RawLeaf` -- the (i64, shadow) pair is SNAPSHOTTED at
  the leaf's source position (a later leaf's setq must not change what an
  earlier read observed), then resolved once in the unbox hoist (shadow ==
  sentinel -> raw, i31/TYPE_BIGNUM shadow -> unbox, anything else -> fall
  back). A local never assigned inside the site shares ONE snapshot across its
  occurrences.
- anywhere else (`WasmExprCompiler.compileSymbolRef` ->
  `emitRawLocalBoxedRead`): box on demand -- the i31 range boxes INLINE with
  `ref.i31` (allocation- and call-free; a loop counter's `(< i 64)` read costs
  a few instructions), only out-of-i31 raw values call `_int_new`.

## Eligibility (WasmLetCompiler)

A `let` binding qualifies when ALL hold; everything else keeps the ordinary
eqref local:

- not special, not captured by a nested lambda (`FreeVarAnalyzer` -- captures
  need cells), not a duplicate binding name in the same `let`;
- not at top level (the eval-mirror reads boxed slots), not under `--dynamic`,
  not in an async body (`ctx.asyncResume`/await -- the spill machinery owns
  locals there; the `rontolisp.debug.norawlocals` property force-disables for
  A/B profiling);
- at least one assignment (the init, or a `setq`/`setf`-pair value found by a
  shadowing-blind body walk) is integer-tree-SHAPED (`isRawAssignShaped`) --
  a pure heuristic: precision only affects performance, never correctness,
  because `compileRawStore` boxes into the shadow for anything that does not
  actually classify.

Scoping mirrors `ctx.locals`: registrations live for the body, inner `let`s
REMOVE shadowed names whatever the new binding's representation, and the whole
map + the i64 watermark restore on exit. A name in `rawLocals` is never in
`ctx.locals` (`WasmDefvarCompiler` checks both).

## The i64 locals run (buildLocalsAndPatch)

Wasm locals declare as typed runs; every eqref local (whose count is known only
after the body is emitted) precedes the i64 run, so an i64 local's absolute
index is unknowable mid-emission. References emit as **3-byte padded-LEB
placeholders** recorded in `Ctx.i64LocalRefs`, and every function-body
finalizer routes through `WasmLispCompiler.buildLocalsAndPatch` (defuns,
lambdas, `_start`, the toplevel/async chunk emitters), which patches them to
`ctx.nextLocal + slot` and appends the `[eqref-run, i64-run]` declaration. A
body with no i64 locals is byte-identical to the pre-stage-3 emission. Fused
sites save/restore `Ctx.nextI64Local` (slots are reused across sites;
`maxI64Locals` keeps the declaration's high-water mark); let-scope raw locals
allocate below the sites' watermark and restore at scope end.

## Pinning tests

`WasmLispCompilerIntegrationTest.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`
(overflow promotion through the shadow, non-integer assignment, masked-wrap
exactness, side-effects-once under a failed substitution) and the
`flet-fusion-and-unboxed-locals` ci-spec case (all four backends).

## Re-evaluation triggers

- The boxed-shadow design exists because a raw local's value can leave the i64
  tier at ANY assignment (overflow promotion) and because non-fused readers
  need an eqref. If locals ever get static types (a type-inference pass), the
  shadow could disappear for provably-u32 locals.
- Params are NOT eligible (they arrive as eqref by signature). A raw COPY of a
  hot integer param would extend the win to defun bodies whose temps are
  parameters -- profile first.
- `emitRawLocalBoxedRead` inlines the i31-range box because `_int_new`'s call
  overhead dominated for loop counters; if call sites ever bloat, re-measure
  before restructuring.
