# WASM unboxed (dual-representation) locals

> 2026-08-16 (todo 413): statement position propagates through the sequencing
> forms -- `WasmExprCompiler.compileForEffect` routes `let`/`let*`/`progn`
> through forEffect overloads (`WasmLetCompiler.compile(cons, ctx, true)`,
> `WasmPrognCompiler.compileForEffect`) so their LAST form is a statement too.
> Before this, a statement-position `(let ((x ...)) (setf d .. h ..))` -- every
> SHA-256 round -- compiled its tail setq via `compilePair`, which re-reads the
> just-stored raw local through `_ub_read` as the let's value, only for the
> enclosing statement to DROP it: ~24% of the ironclad PBKDF2 profile was that
> read-and-drop.

**Invariant: giving a `let` binding the dual representation must never change a
result, an error shape, or an observable side effect -- it is an alternative
STORAGE for the same Lisp value, with a total boxed escape hatch.** Introduced
by todo 194 stage 3 for the wasm-GC backend (Preview 1 AND `--component`;
interpreter/JVM/`--no-gc` untouched): SHA-256's round temps and loop counters
were paying an `_int_new` box per assignment (a `TYPE_BIGNUM` allocation for
out-of-i31 u32 words) and a guarded unbox per fused read.

## Representation

An eligible binding gets NO ordinary eqref local. Instead
(`WasmIntFusionCompiler.RawLocal` with `counted = false`, registered in
`Ctx.rawLocals` by `WasmLetCompiler`):

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
  `emitRawLocalBoxedRead`): box on demand, through the shared **`_ub_read`**
  helper (`FUNC_UB_READ` / `TYPE_UB_READ`, both appended after the last fixed
  helper and type so no index above them shifts;
  `WasmFxRuntimeBuilder.buildUbReadBody`). Three instructions at the site --
  `local.get shadow`, the i64 slot, `call _ub_read` -- against the ~42 bytes the
  same sequence used to inline; the helper body IS that sequence, unchanged
  (sentinel test, then the i31-range `ref.i31` with `_int_new` outside it).

  This is the file's own "if call sites ever bloat, re-measure before
  restructuring" trigger, fired and acted on (2026-08-05). What fired it: a
  cl-postgres `--component` carried **19,392** of these reads, 9.5% of an 8.5 MB
  module. What made the inline version obsolete: stage 4's fused comparisons
  took the hot loop-counter read -- the case the inlining was FOR -- onto the
  raw `RawLeaf` path, which never reaches here; what is left is cold generic
  readers in library code, and their out-of-i31 arm was already a call.
  Measured, `--component` cl-postgres 8,519,343 -> 8,212,025 bytes (-3.6%, 7,976
  sites moved out of line), ironclad PBKDF2-HMAC-SHA256 4096 rounds unchanged at
  ~0.82 s under wasmtime 47 (three runs each, byte-identical output).

  The fused-site FALLBACK's boxed read of a snapshot (11,417 sites) is a
  DIFFERENT, already-compact shape -- a bare `_int_new` call with no inline i31
  test, ~14 bytes -- and stays inline: routing it through `_ub_read` too would
  save ~68 KB (0.8%) and is not worth a second shape to reason about.

## The other flavour in the same map

`RawLocal` has a second shape, `counted = true` (`shadowSlot = -1`), for a
`dotimes` induction variable over a LITERAL bound: no shadow, no guard, no
fallback, and reads that resolve to the slot itself. It shares `Ctx.rawLocals`
so that shadowing, `defvar` collision and symbol resolution need no new rules,
and it is at EVERY optimize level because it is not a speed-for-size trade.
Everything about it -- the emission, the four `counted()` branches, the
eligibility scan -- is `.kb/wasm-counted-loops.md`.

## Eligibility (WasmLetCompiler)

A `let` binding qualifies when ALL hold; everything else keeps the ordinary
eqref local:

- the module emits the speed-for-size trades at all --
  `WasmIntFusionCompiler.speedTradesEnabled(ctx)`, false under
  `--optimize=size`. This feature and fusion share ONE switch because a raw
  local is only ever a win THROUGH a fused tree: with fusion off every
  assignment bails into the boxed shadow and every read goes through
  `_ub_read`, which measures larger than dropping both and slower than keeping
  both (the four-way table is in `.kb/optimize-dead-code-elimination.md`).
  `-Drontolisp.debug.norawlocals` remains the way to switch THIS half alone,
  for profiling;
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
index is unknowable mid-emission. References emit as **3-byte fixed-width
placeholders** recorded in `Ctx.i64LocalRefs`, and every function-body
finalizer routes through `WasmLispCompiler.buildLocalsAndPatch` (defuns,
lambdas, `_start`, the toplevel/async chunk emitters), which resolves them to
`ctx.nextLocal + slot` and appends the `[eqref-run, i64-run]` declaration.

The placeholder is **spliced out, not overwritten in place** (todo-274): a
resolved reference costs the LEB length its index actually needs, so a slot
below 128 is one byte rather than three. That is what makes the encoding
minimal (`.kb/wasm-shortest-encoding.md`) and it is why the recorded offsets
must be ASCENDING -- they are, being appended by one forward emission walk, and
the splice loop throws rather than assume it.

A body with no i64 locals is byte-identical to the pre-stage-3 emission. Fused
sites save/restore `Ctx.nextI64Local` (slots are reused across sites;
`maxI64Locals` keeps the declaration's high-water mark); let-scope raw locals
allocate below the sites' watermark and restore at scope end.

Stage-4 additions (2026-07-27): an assignment whose value is a bare raw-local
SYMBOL copies BOTH slots directly (total for every tier, no guard); a value
that classifies to a folded constant stores `i64.const` with no blocks; `aref`
and an outer raw-local symbol count as raw-shaped for eligibility
(`isRawAssignShaped`), so byte-copy temporaries qualify.

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
- `emitRawLocalBoxedRead` is now a `_ub_read` call (see Reads above). The
  reverse trigger: if a profile ever shows the call itself in a hot path, the
  helper body is the exact byte sequence to inline back, and the decision should
  be re-taken against a measurement rather than restored wholesale -- the reason
  it went out of line (thousands of cold library sites) is orthogonal to the
  reason it was inline (one hot counter read per loop iteration).
