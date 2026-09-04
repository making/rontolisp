# `replace` / `fill` / `map-into` are shared callees, not per-site code

**Invariant 1: no `replace`, `fill` or `map-into` site emits its runtime dispatch inline when
the program carries the matching helper. The program carries each of them once.**

**Invariant 2: the shared `replace` / `fill` runtime is TWO functions — the wide dispatch and
its `%arrayp` arm — and a site whose DESTINATION is provably an array calls the arm directly.
A program with no unproven site leaves the wide dispatch without a caller for the shaker.**

Same lesson as `.kb/subseq-runtime.md` (direct precedent — read its mechanics first; this
file records only the deltas), `.kb/seq-conversion-runtime.md`, `.kb/string-write-runtime.md`,
`.kb/format.md`'s `%fixed-decimal`: a per-site expansion past a few hundred bytes becomes a
callee.

Marginal cost of one more site, wasm-GC `--optimize=size`, before -> after:
`replace` 3,806 -> 21; `map-into` 1,949 -> 17; `fill` 1,718 -> 15. (For scale, the operators
already shared: `sort` 533, `position` 449, `reduce` 105, `concatenate` 23, `search`/`subseq`
15.) `CHIPZ::UPDATE-WINDOW` was 18,149 B, 9.5% of the whole zlib module, for four `replace`
calls.

## The lowering

Builders in `LispMacroExpander`, each the body its `expand*` used to inline:

- `replaceRuntimeWrapper()` — `(%replace-runtime seq1 seq2 start1 end1 start2 end2)`
- `fillRuntimeWrapper()` — `(%fill-runtime seq item start end)`
- `mapIntoRuntimeWrapper(n)` — `(%map-into-runtime-<n> result fn s0 ... s<n-1>)`
- `replaceArrayRuntimeWrapper()` / `fillArrayRuntimeWrapper()` — same call-site shapes for
  `%replace-runtime-array` / `%fill-runtime-array`, the `%arrayp` arm of the two above.

**The bounds are PARAMETERS, nil meaning "the default"** (what the inline form's
`(or expr default)` already allowed at run time), so ONE call-site shape serves every keyword
combination. Argument order is the canonical keyword order, i.e. the order the inline `let*`
bound them, so evaluation order is unchanged.

**`map-into` gets one helper per SOURCE-SEQUENCE COUNT**, not one taking the sources as a
list: its loop body is a `funcall` of exactly that many arguments, and a list would need
`apply`, dragging in the spread dispatcher (12 KB on this module). Count is read off the call
shape (`LispMacroExpander.sequenceOpRuntimeWrappers`), capped at 8 (`MAX_MAP_INTO_SOURCES`).

Routing is per site at the compilers' existing `REPLACE` / `FILL` / `MAP_INTO` cases, on
`ctx.functions.containsKey(<helper name>)`, so an under-predicting gate costs sharing, never
correctness.

## Injection

- **The BACKEND's**, in the same loop that adds `BuiltinFunctionWrappers` (`JvmLispCompiler` /
  `WasmLispCompiler`): the `#'replace` and `#'fill` wrapper bodies are sites of their own and
  do not exist until the backend generates them.
- **Before the `%subseq-runtime` injection, and scanned by it** — the `replace`/`fill` bodies
  call `subseq`, so the subseq gate takes the seq-op helpers as a third form group.
- The three OPERATORS are **gated apart** (unlike the `seqConversionWrappers` trio, which must
  travel together because its arm choice is a runtime fact). `replace` and `fill` each inject
  a PAIR, because which of the two a site can reach is a compile-time fact the pre-expansion
  scan cannot see.
- **The two backends gate differently**, same asymmetry as `%subseq-runtime`: wasm injects on
  the name scan alone; the JVM additionally requires `programUsesAnyArrayOp`, because each
  body's destructive arm names `aref`/`%row-major-aset` and mis-firing the array gate costs
  ~120 KB. With the JVM gate off, every site keeps its inline lowering.
- The interpreter never sees this: `replace`/`fill` are native built-ins, `map-into` expands
  the inline form.

## The bulk-copy arm

`%replace-runtime-array`'s element loop is fronted by `(%replace-bulk dst src s1 s2 n)`
(`LispNames.REPLACE_BULK`, emitted ONLY in the narrow helper's body by `replaceDispatch`;
inline `SeqOpArms.ALL` sites keep the plain loop): true = the n elements moved in one
engine-level copy, nil = nothing happened and the loop runs.
- wasm-GC (`WasmArrayCompiler.compileReplaceBulk`) fires when both sequences are DISTINCT
  packed integer vectors of the SAME width, bounds are non-negative i31s and both ranges are
  in bounds — then one `array.copy`. It declines everything else so the loop keeps owning the
  error shape, the overlap-forward semantics of a same-object replace, and mixed-width /
  general-array pairs.
- JVM compiles the form to constant nil (no bulk path yet). Interpreter never sees it.

## The list arm of `replace`

`replace` into a LIST was broken on both compile paths: a list `seq1` is not `%arrayp`, so it
fell through to the immutable-string rebuild — JVM threw `ClassCastException`, WASM printed
the three subsequences as text (`"(1)(9 9)(4 5)"`), and `(setf (subseq l ...))` on a list
(which lowers to `replace`) was a **silent no-op** on WASM.

Dispatch is now `%arrayp` -> element store, else `listp` -> a `nthcdr`/`rplaca` cursor walk
(what `fill` already did), else the string rebuild. The arm costs 641 bytes, paid once.

## Narrowing the arms to the ones a site can reach

Sharing made a SITE 21 bytes; it did not make the runtime small. One `%REPLACE-RUNTIME` was
4,463 B, 3.2% of the zlib `--optimize=size` artifact. Two changes, in order:

**1. A LIST source is walked with a cursor, not indexed with `elt`.** That arm runs only under
`(listp seq2)`, so every `elt` was an `nth` walk from the head per element (quadratic) through
a full representation dispatch. Now the same `nthcdr`/`cdr` cursor the list DESTINATION arm
uses, with the same `(null cell)` stop. 1,570 -> ~450 bytes, O(n^2) -> O(n).

**Trap: the `(null cell)` stop changes an INVALID call's behavior on the compile paths,
deliberately.** `(replace <array> '(1 2) :end2 4)` names a bounding index the source lacks
(CLHS requires it to be valid). The compile paths used to answer `#(1 2 NIL NIL 0)` (and a
PACKED destination trapped on the first nil); they now copy what there is and stop, matching
the destination arm and the interpreter's mirror case `(replace (list 1 2) #(5 6 7 8) :end1 4)`
-> `(5 6)`. The interpreter's native `replace` still SIGNALS on the source side
(`sequence-ref: index 2 out of range`), so the disagreement is now interpreter-signals /
compile-paths-truncate. **Re-evaluation trigger: if the error case is made uniform, the
honest fix is the interpreter's check on both sides of both arms, not an `elt` per element
back in the loop.**

The `search`/`mismatch` prelude bodies took the same cursor and could NOT take the same stop
(`.kb/seq-coerce-runtime.md`): the interpreter's native scan arm declines every out-of-range
bound so those bodies own what they answer there; their cursor falls back to the original
`elt` call instead of stopping. That is why theirs GREW (+634 B of `--optimize=size` wasm)
where this one shrank.

**The list DESTINATION arm's SOURCE read** was still `(rplaca cell (elt r2 (+ vs2 k)))`, so
list-into-list `replace` — what `(setf (subseq <list> ...))` lowers to — stayed quadratic on
all three compile paths (4,000 elements: 10.6/11.35/16.5/16.6 ms -> 0.023/0.019/0.051/0.051
for interpreter/JVM/WASM p1/component). This cursor could NOT take the `(null cell)` stop
either (the stop belongs to the destination walk, which owns running out of cells); it falls
back to the `elt` call: `(if (consp c) (prog1 (car c) (setq c (cdr c))) (elt r2 (+ vs2 k)))`,
seeded `(nthcdr start2 source)` only when the source is a `listp` and `start2` is a
non-negative integer. Costs +248 B once, 0 per site (43 B before and after).

**The INTERPRETER's native `replace` had the mirror defect on the source side.**
`Environment`'s `replace` read `sequenceRef(source, start2 + k)` per element, and
`sequenceRef`'s list arm walks from the head. One `Environment.SequenceSourceCursor` now
serves all three destination arms (`LispArray`, `LispIntVector`, list); it is monotonic,
re-seeds if a caller reads backwards, and keeps `sequenceRef` for every non-list
representation, so strings, general arrays and packed vectors are untouched (a null check, not
a `consp`, per element). 627x at n = 4000 (10.6 -> 0.0169 ms). **It still SIGNALS** — the
cursor raises the same message from the same element for a proper list run out, a dotted tail
and a `:start2` past the end, so interpreter-signals / compile-paths-truncate is unchanged.

**2. The `%arrayp` arm is its own function, and a site that proves an array calls it.**
`%replace-runtime-array` / `%fill-runtime-array` hold that arm; the wide helper's array arm is
a CALL to it, so the pair holds ONE copy of the copy loop and a program reaching both pays one
extra call, not a second loop. `LispMacroExpander.sequenceOpRuntimeWrappers` answers them as a
PAIR.

**Why routing per SITE and not narrowing the one helper**: the `#'replace` wrapper body is a
site of its own, generated for every program, taking whatever it is handed — a whole-program
scan that had to cover it would keep every arm in every program. Per-site routing lets chipz's
five proven sites call the arm while the wrapper (and any unproven user site) keeps the wide
dispatch; the shaker drops whichever is left uncalled.

### Soundness

The gate answers ONE question — *can this value be a list or an immutable string?* — and only
narrows on a definite no (`WasmArrayCompiler.provesArrayValue`). Three sources, each already
trusted at the same site for array EMISSION (`.kb/declarations-type-checks.md`):

1. a pinned non-`STRING` representation kind (`arrayKindOfExpr`: a `declare (type ...)`, a
   `defstruct` slot `:type` read through its accessor, a `(the ...)` wrap, a `let` init this
   compile chose a representation for);
2. `Ctx.arrayLocals` — a `let`-bound name whose init proves the WEAKER fact and which the body
   never reassigns;
3. a `make-array` call right at the site.

Source 2 exists because the weak fact is not the kind:
`(make-array (* 2 (length output)) :element-type '(unsigned-byte 8))` has no KIND at compile
time (an integer size packs, a dimension LIST would not, and the size is computed), but
`%arrayp` is true either way. `initExprKind` must keep refusing it (it picks an accessor, and a
wrong rank is a wrong accessor); `provesArrayValue` must not. `Ctx.arrayLocals` carries that
weaker fact, scoped/shadowed/restored beside `Ctx.declaredArrays` in `WasmLetCompiler`.

`makeArrayBuildsArrayValue` is conservative in three shapes `compileMake` can route to a
string: a CHARACTER element type, an element type spelled as a bare symbol (a variable holding
a run-time designator that can name `character`), and `:displaced-to`.

A WRONG answer is possible only via source 1 and only through a false declaration (UB in CL).
It lands on the array arm's `%row-major-aset`, whose general lane casts — so a list or string
TRAPS deterministically. Never silent wrong data. Sources 2 and 3 cannot be wrong.
Under-predicting costs only the narrower callee.

**wasm-GC only.** Both backends inject the pair (the JVM under its existing array gate), but
only wasm routes: `DeclaredArrayTypes` has no JVM consumer, so `JvmExprCompiler` passes
`arrayHelperTarget` false at every site. **Re-evaluation trigger: when the JVM adopts
`DeclaredArrayTypes`, give `JvmExprCompiler`'s `REPLACE`/`FILL` cases the same third argument
— nothing else moves.**

Helper bodies, wasm-GC `--optimize=size`: `%REPLACE-RUNTIME` 4,445 -> 1,900,
`%REPLACE-RUNTIME-ARRAY` 2,328; `%FILL-RUNTIME` 1,680 -> 1,347, `%FILL-RUNTIME-ARRAY` 727.
The zlib artifact keeps only the two ARRAY helpers, so `%REPLACE-RUNTIME`, `%FILL-RUNTIME` and
`%SEQ-TO-STRING` leave the module entirely (290 -> 281 functions); zlib fell 6.5-6.7% at every
`--optimize` level and still gunzips its fixture byte for byte. A program reaching BOTH (e.g.
`hello-ningle`, whose `#'replace` wrapper is live) pays +221 bytes: `fill`'s prologue is now
spelled in both helpers, since a site calls the narrow one directly.

## Re-evaluation triggers

- **A program with EXACTLY ONE site pays a little more** (+419 bytes for one `replace` at
  `--optimize=size`). Break-even is between one and two sites. A site-count threshold was
  rejected: at `--optimize` the un-shaken `#'replace` wrapper is itself a site, so counting
  before the shake answers 2 for a one-site program. The honest fix would be to ask the
  SHAKER, not the pre-expansion scan.
- **The un-shaken `(none)` build of a program with no site of its own grew by 132 bytes**
  (the wrapper catalog now carries the helper as well as the wrapper — the list arm's 641
  bytes minus the sharing).
- **If a helper becomes hot**: each call is one extra frame per `replace`/`fill`/`map-into`
  CALL, not per element. The packed-integer fused store still fires inside the helper (a
  syntactic match on `(%row-major-aset dst i (aref src j))`,
  `.kb/packed-integer-vectors.md`). If a site needs more, add a fast path at the SITE before
  the call, never re-inline the dispatch.
- **A program reaching BOTH the wide helper and its array arm pays one extra call per
  `replace`/`fill` into an array** — one frame per call, not per element, and it is what keeps
  the pair from holding two copies of the copy loop.
- **`map-into` was deliberately left wide.** Its arms are `elt` reads and a
  runtime-dispatching `(setf (elt ...))` store rather than the `%arrayp`/`listp`/string split;
  no measured artifact carries a live `%map-into-runtime-<n>`. Reopen against a module that
  does. Its LOWERING carries a cursor per source and one for the result (`mapIntoDispatch`),
  and the `#'map-into` WRAPPER (`BuiltinFunctionWrappers.mapIntoWrapper`) now carries the same
  result cursor — its store was `(setf (elt r i) v)`, an O(i) head-walk into a list
  destination (`.kb/seq-coerce-runtime.md`). At n = 4000, list into list: JVM 11.40 -> 0.031
  ms, wasm-GC 21.85 -> 0.181, interpreter 18.13 -> 3.89, and every after-row doubles per
  doubling of n where every before-row quadrupled. An ARRAY destination keeps the indexed
  store and pays one `consp` per element for an unused cursor: +25% in the interpreter, flat
  on JVM and wasm.
- **`%SEQ-INT-VECTOR` (2,136 B), `%SEQ-TO-LIST` (1,592) and `%SUBSEQ-RUNTIME` (1,114) are
  still whole-representation bodies** in the same artifact but are NOT this shape: the
  conversions are one arm each (`.kb/seq-conversion-runtime.md`), `%subseq-runtime`'s narrow
  arm IS its expensive one, and `%seq-int-vector` is a `concatenate` result-family builder
  (`.kb/concatenate-result-families.md`). Each needs its own measurement.

## The spread dispatcher on this module was NOT an over-approximating scan

`_dispatch_spread` was another 12,156 B of the zlib artifact. What turned `needsApplyRuntime`
on was **`WasmArityBundler.spreadOverArityFuncalls`**: a `funcall` with more arguments than the
per-arity dispatchers take was rewritten to `(apply f (list ...))`, and chipz's

```lisp
(funcall fun state input output :input-start s :input-end e :output-start s :output-end e)
```

is eleven of them. The designator is a variable, so the rewritten `apply` needed `_apply` and
the spread dispatcher for real. **The ceiling is now derived, not fixed at 10**: a call site
11..14 arguments wide gets its own per-arity dispatcher, APPENDED after the fixed block so no
existing index moves (`MAX_CALLABLE_ARITY` is an index origin — `.kb/wasm-callable-arity.md`).
`needsApplyRuntime` answers false and `_dispatch_11` costs 975 B where the spread dispatcher
cost 12,156; zlib `--optimize=size` 149,054 -> 137,430 (-7.8%).

## Pinning tests

- `LispMacroExpanderTest.aDestructiveSequenceOperatorSiteIsOneCallWhenTheProgramCarriesTheSharedDispatch`
  — each site routes with the helper and keeps the inline lowering without it; `map-into`
  routes to the helper of its own source count; a PROVEN array destination routes to the array
  arm on the same call-site shape.
- `LispMacroExpanderTest.theSharedSequenceOpDispatchesAnswerTheSameThingAsTheInlinedOnes` —
  one body, two homes; or-defaulted bound parameters; no helper calls its own operator; the
  wide helper's array arm is a CALL and the array helper carries no dispatch, no `rplaca` and
  no `concatenate`; the list SOURCE is a cursor walk, not an `elt` index.
- `LispMacroExpanderTest.theSequenceOpRuntimeGateInjectsOnlyTheHelpersThatWouldHaveACaller` —
  the injection gate both directions, `replace`/`fill` answering a PAIR, one `map-into` helper
  per source count, and that the helpers reach `subseq`.
- `WasmLispCompilerTest.aDestructiveSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime`
  — the marginal byte budgets.
- `WasmLispCompilerTest.aProvenArrayDestinationLeavesTheSharedRuntimesNonArrayArmsWithoutACaller`
  — the narrowing gate at module level.
- Behavior: ci-spec `replace-into-a-list` and `sequence-op-runtime-arm-routing` (both sides of
  the gate in one program) plus the sequence cases, all four backends; `LispEvaluatorTest`,
  `JvmLispCompilerTest.compileAndRunReplaceIntoAList`,
  `WasmLispCompilerIntegrationTest.replaceIntoAList` / `.sequenceOpRuntimeArmRouting`.
- The source cursors:
  `LispEvaluatorTest.replaceReadsAListSourceThroughACursorRatherThanIndexingItFromTheHead`
  (every destination arm, a self-aliased `replace`, a 2,000-element source, and the three
  `sequence-ref: index N out of range` signals a silent stop would have erased);
  `JvmLispCompilerTest.compileAndRunAListIntoAListReplaceReadsItsSourceWithACursor`; the
  list-source rows in `WasmLispCompilerIntegrationTest.replaceIntoAList` and in ci-spec's
  `replace-into-a-list`.
