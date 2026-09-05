# `replace` / `fill` / `map-into` are shared callees, not per-site code

**Invariant 1: no `replace`, `fill` or `map-into` site emits its runtime dispatch inline when the
program carries the matching helper. The program carries each of them once.**

**Invariant 2: the shared `replace` / `fill` runtime is TWO functions -- the wide dispatch and its
`%arrayp` arm -- and a site whose DESTINATION is provably an array calls the arm directly.** A
program with no unproven site leaves the wide dispatch without a caller for the shaker.

Same lesson as `.kb/subseq-runtime.md` (direct precedent; read its mechanics first, this file
records only the deltas), `.kb/seq-conversion-runtime.md`, `.kb/string-write-runtime.md`,
`.kb/format.md`'s `%fixed-decimal`: a per-site expansion past a few hundred bytes becomes a callee.
Marginal cost of one more site, wasm-GC `--optimize=size`: `replace` 3,806 -> 21; `map-into`
1,949 -> 17; `fill` 1,718 -> 15.

## The lowering
Builders in `LispMacroExpander`, each the body its `expand*` used to inline:
`replaceRuntimeWrapper()` -> `(%replace-runtime seq1 seq2 start1 end1 start2 end2)`;
`fillRuntimeWrapper()` -> `(%fill-runtime seq item start end)`;
`mapIntoRuntimeWrapper(n)` -> `(%map-into-runtime-<n> result fn s0 ... s<n-1>)`;
`replaceArrayRuntimeWrapper()` / `fillArrayRuntimeWrapper()` for the `%arrayp` arms.

- **The bounds are PARAMETERS, nil meaning "the default"**, so ONE call-site shape serves every
  keyword combination. Argument order is the canonical keyword order (the inline `let*` order), so
  evaluation order is unchanged.
- **`map-into` gets one helper per SOURCE-SEQUENCE COUNT**, not one taking a list: its loop body is
  a `funcall` of exactly that many arguments, and a list would need `apply` and the spread
  dispatcher (12 KB). Count read off the call shape (`sequenceOpRuntimeWrappers`), capped at 8
  (`MAX_MAP_INTO_SOURCES`).
- Routing is per site at the compilers' `REPLACE`/`FILL`/`MAP_INTO` cases, on
  `ctx.functions.containsKey(<helper name>)`, so an under-predicting gate costs sharing, never
  correctness.

## Injection
- **The BACKEND's**, in the same loop that adds `BuiltinFunctionWrappers` (`JvmLispCompiler` /
  `WasmLispCompiler`): the `#'replace`/`#'fill` wrapper bodies are sites of their own and do not
  exist until the backend generates them.
- **Before the `%subseq-runtime` injection, and scanned by it** -- the bodies call `subseq`.
- The three OPERATORS are gated APART; `replace` and `fill` each inject a PAIR, because which of
  the two a site can reach is a compile-time fact the pre-expansion scan cannot see.
- **The two backends gate differently** (same asymmetry as `%subseq-runtime`): wasm injects on the
  name scan alone, the JVM additionally requires `programUsesAnyArrayOp` (mis-firing the array gate
  costs ~120 KB). With the JVM gate off, every site keeps its inline lowering.
- The interpreter never sees this: `replace`/`fill` are native built-ins.

## The bulk-copy arm
`%replace-runtime-array`'s element loop is fronted by `(%replace-bulk dst src s1 s2 n)`
(`LispNames.REPLACE_BULK`, emitted ONLY in the narrow helper's body by `replaceDispatch`): true =
the n elements moved in one engine-level copy, nil = nothing happened and the loop runs.
`WasmArrayCompiler.compileReplaceBulk` fires only for DISTINCT packed integer vectors of the SAME
width with in-bounds non-negative i31 bounds, then one `array.copy`; it declines everything else so
the loop keeps owning the error shape and same-object overlap-forward semantics. The JVM compiles
the form to constant nil.

## The arms
- **`replace` into a LIST** used to fall through to the immutable-string rebuild (JVM
  `ClassCastException`, WASM printing `"(1)(9 9)(4 5)"`, and `(setf (subseq l ...))` a silent
  no-op). Dispatch is now `%arrayp` -> element store, else `listp` -> a `nthcdr`/`rplaca` cursor
  walk, else the string rebuild.
- **A LIST source is walked with a cursor, not indexed with `elt`** (every `elt` was an `nth` walk
  from the head: quadratic). **Trap: the `(null cell)` stop changes an INVALID call's behavior on
  the compile paths, deliberately** -- `(replace <array> '(1 2) :end2 4)` now copies what there is
  and stops, where the interpreter's native `replace` still SIGNALS. The disagreement is
  interpreter-signals / compile-paths-truncate. The `search`/`mismatch` prelude bodies took the
  same cursor but could NOT take the same stop (`.kb/seq-coerce-runtime.md`); theirs falls back to
  the `elt` call, which is why theirs grew where this one shrank.
- The list DESTINATION arm's SOURCE read likewise falls back rather than stopping:
  `(if (consp c) (prog1 (car c) (setq c (cdr c))) (elt r2 (+ vs2 k)))`, seeded
  `(nthcdr start2 source)` only when the source is `listp` and `start2` a non-negative integer.
- **The INTERPRETER had the mirror defect**: `Environment`'s `replace` read
  `sequenceRef(source, start2 + k)` per element. One `Environment.SequenceSourceCursor` serves all
  three destination arms, monotonic, re-seeding if a caller reads backwards, keeping `sequenceRef`
  for every non-list representation. **It still SIGNALS**, from the same element, for a proper list
  run out, a dotted tail and a `:start2` past the end.

## Narrowing the arms to the ones a site can reach
`%replace-runtime-array` / `%fill-runtime-array` hold the `%arrayp` arm; the wide helper's array
arm is a CALL to it, so the pair holds ONE copy of the copy loop.
`LispMacroExpander.sequenceOpRuntimeWrappers` answers them as a PAIR. Routing is per SITE, not a
narrowed single helper, because the `#'replace` wrapper body is itself a site taking whatever it is
handed -- a whole-program scan covering it would keep every arm in every program.

**Soundness.** The gate answers ONE question -- *can this value be a list or an immutable string?*
-- and narrows only on a definite no (`WasmArrayCompiler.provesArrayValue`). Three sources, each
already trusted at the same site for array EMISSION (`.kb/declarations-type-checks.md`):
1. a pinned non-`STRING` representation kind (`arrayKindOfExpr`: a `declare (type ...)`, a
   `defstruct` slot `:type`, a `(the ...)` wrap, a `let` init with a chosen representation);
2. `Ctx.arrayLocals` -- a `let`-bound name whose init proves the WEAKER fact and which the body
   never reassigns;
3. a `make-array` call right at the site.

Source 2 exists because the weak fact is not the kind:
`(make-array (* 2 (length output)) :element-type '(unsigned-byte 8))` has no compile-time KIND but
is `%arrayp` either way. `initExprKind` must keep refusing it (a wrong rank is a wrong accessor);
`provesArrayValue` must not. `Ctx.arrayLocals` is scoped/shadowed/restored beside
`Ctx.declaredArrays` in `WasmLetCompiler`. `makeArrayBuildsArrayValue` is conservative in the three
shapes `compileMake` can route to a string: a CHARACTER element type, an element type spelled as a
bare symbol, and `:displaced-to`.

A WRONG answer is possible only via source 1 and only through a false declaration (UB in CL); it
lands on `%row-major-aset`, whose general lane casts, so a list or string TRAPS deterministically --
never silent wrong data.

**wasm-GC only**: both backends inject the pair, but `DeclaredArrayTypes` has no JVM consumer, so
`JvmExprCompiler` passes `arrayHelperTarget` false at every site. When the JVM adopts
`DeclaredArrayTypes`, give its `REPLACE`/`FILL` cases the same third argument -- nothing else moves.

**`map-into` was deliberately left wide** (its arms are `elt` reads and a runtime-dispatching
`(setf (elt ...))` store); no measured artifact carries a live `%map-into-runtime-<n>`. Its
LOWERING carries a cursor per source and one for the result (`mapIntoDispatch`), and
`BuiltinFunctionWrappers.mapIntoWrapper` carries the same result cursor -- its store was
`(setf (elt r i) v)`, an O(i) head-walk into a list destination.

## Pinning tests
- `LispMacroExpanderTest.aDestructiveSequenceOperatorSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`,
  `.theSharedSequenceOpDispatchesAnswerTheSameThingAsTheInlinedOnes`,
  `.theSequenceOpRuntimeGateInjectsOnlyTheHelpersThatWouldHaveACaller`.
- `WasmLispCompilerTest.aDestructiveSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime`
  (marginal byte budgets),
  `.aProvenArrayDestinationLeavesTheSharedRuntimesNonArrayArmsWithoutACaller`.
- Behavior: ci-spec `replace-into-a-list`, `sequence-op-runtime-arm-routing`;
  `JvmLispCompilerTest.compileAndRunReplaceIntoAList`,
  `WasmLispCompilerIntegrationTest.replaceIntoAList` / `.sequenceOpRuntimeArmRouting`.
- Source cursors:
  `LispEvaluatorTest.replaceReadsAListSourceThroughACursorRatherThanIndexingItFromTheHead` (every
  destination arm, a self-aliased `replace`, a 2,000-element source, and the three
  `sequence-ref: index N out of range` signals a silent stop would have erased);
  `JvmLispCompilerTest.compileAndRunAListIntoAListReplaceReadsItsSourceWithACursor`.

## Related
The spread dispatcher (12,156 B on zlib) was turned on by
`WasmArityBundler.spreadOverArityFuncalls` rewriting an over-wide `funcall` to
`(apply f (list ...))`. **The arity ceiling is now derived, not fixed at 10**: a site 11..14
arguments wide gets its own per-arity dispatcher APPENDED after the fixed block so no existing
index moves (`MAX_CALLABLE_ARITY` is an index origin, `.kb/wasm-callable-arity.md`).
`%SEQ-INT-VECTOR`, `%SEQ-TO-LIST` and `%SUBSEQ-RUNTIME` are whole-representation bodies but NOT
this shape -- see `.kb/seq-conversion-runtime.md`, `.kb/concatenate-result-families.md`.
