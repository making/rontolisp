# WASM integer expression-tree fusion (the unboxed fast path)

**Invariant: fusing an integer expression tree must never change a result, an observable side
effect, or an error shape -- the fast path is an optimization with a total fallback, not a
semantic variant.** The fallback is emitted whole; what the module SHIPS of it is decided
afterwards by the type-test fold (`.kb/wasm-ref-type-fold.md`), which prunes the fallback's arms no
leaf can reach -- the proof the emitter cannot make is made on the finished bytes instead.

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

## The interpreter's order

The JVM twin's rule and plan (`.kb/jvm-int-fusion.md`, "The interpreter's order"):
classification records leaf evaluations and applications in the interpreter's order, and
before a leaf whose evaluation is observable while applications are pending `evalLeaves`
runs a check -- `PendingGuard` (every leaf the pending operations read an i31 or a
`TYPE_BIGNUM`, divisors non-zero, shift counts no larger than an `i32`) or `LeafGuard`
(the leaf is integer arithmetic over quiet variables, which hold such integers); anything
no check stands in for -- an aref read before a leaf that may store, a computed divisor
or count -- is made opaque and evaluated in place. An aref leaf's evaluation is its array;
its index is part of the tree (below). What is this backend's own:

- **The probe is inline**: `block $checked { block $fails { checks; br $checked } probe }`,
  the probe being each pending root's generic fallback with its value dropped. A site whose
  probes would apply more than `MAX_OPS` operations between them, or whose checks would run
  more than `MAX_ORDER_CHECKS` leaf tests, declines fusion (`classifyOrdered` answers
  null), which bounds the body (`.kb/wasm-function-body-size.md`); the generic
  per-operation path keeps the order by construction.
- Every leaf already lands in a scratch local, so the checks read those; a site with no
  boundary emits what it did before.

## What else rides the fast path

- **Leaf kinds**: `RawLeaf` (unboxed dual-representation LOCALS, `.kb/wasm-unboxed-locals.md`),
  `ArefLeaf` (packed integer vectors, `.kb/packed-integer-vectors.md`), `ConstLeaf`. An aref's
  INDEX is an operand of the tree (`arefLeaf`), as on the JVM: a literal folds, an unboxed
  local reads its snapshot, an operation computes raw; the reads run after every other leaf's
  unbox, an aref in another's index first (`emitArefReads`), and bail unless the index is an
  i31, as when the index arrived boxed. The fallback computes it generically into a temp (an
  expression leaf's own slot otherwise), then checks in the interpreter's order: the subscript an
  integer (`_idx_chk`, EH mode), the array rank 1 (`_arr_check_rank`), then the bound and the
  read. The fast path takes only a packed integer vector, rank 1 by construction. Measured
  2026-10-07 (wasmtime 49, the JVM file's kernels): the fused kernels run faster than the
  scalar ones on decode and fill, and shade the JVM's `flexi-streams:octets-to-string` and
  `md5sum-sequence`; the kernel module shrinks, as does one that loads flexi-streams and
  md5. A `dotimes`
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
- **A settled defun tail is still a substitutable body.** A program with any multiple-value
  operator -- every real one, ironclad included -- settles every defun tail
  (`LispMacroExpander.settleDefunTails`, [multiple-values.md](multiple-values.md)), so `mod32+`
  arrives as `(let ((__mvN_v X)) (progn (setq %mv-spill nil) __mvN_v))` or `(progn (setq
  %mv-spill nil) X)`. `inlinableBody` reads `X` through `LispMacroExpander.clearedForm` (the JVM
  twin's too), and a root-position call (`tryCompileDefunCall`, like `tryCompileLocalCall`)
  clears the channel after its tree; an interior call needs no clear, its value reaches no
  consumer. From 2026-09-19 to 2026-11-09 `isInlinableDefun` declined that wrapper in every such
  program, and identical results hid it. Measured 2026-11-09 (Xeon E5-2697A v4, wasmtime 49,
  `--component`): ironclad's `pbkdf2-hash-password :sha256` x4096 1230 ms declined, 385 ms
  substituted, 298 ms on the 2026-08-16 build; a SHA-256 block 67 us against 13 us. Pinned by
  `WasmLispCompilerIntegrationTest.compileInlinableDefunsFuseWithoutChangingResults` (a fuel
  budget: a million substituted iterations 45.8 M, as calls 233.6 M) and the `ci-svc-inc` line
  of `multipleValueChannelIsExactInSingleValueContexts` (the clear).
- **A name with a cell is substituted too** (`CelledCall`, 2026-11-09). A program holding `eval`
  gives every named defun a cell ([symbol-runtime-api.md](symbol-runtime-api.md), "A write through a
  computed name"), and from 2026-11-05 `inlinableDefuns` left those names out: a clack app (clack's
  `eval-file` holds `eval`) lost ironclad's fusion. Now the body substitutes as a `CelledCall` node: each
  fast path first bails if any substituted name's cell is filled (`emitCellChecks`, one
  `global.get`/`ref.is_null` per name), and the fallback CALLS the named entry over the arguments --
  the compiled definition while the cell is empty, the replacement once written -- once per argument
  use however many places read it (`emitFallbacks`). Order: the fused evaluation runs every leaf
  before any application, so a site substitutes only when every leaf after a celled call's
  application begins is inert (`keepsCelledOrder`: a constant, an unboxed or uncaptured lexical
  variable, a `defconstant`); otherwise those calls stay leaves for that site. A root call in tail
  position stays a call (its `return_call` keeps a tail call through a replaced name proper). A
  program whose defuns have no cell compiles byte-identically (`postgres-web.lisp` checked). Measured
  2026-11-09 (Xeon E5-2697A v4, wasmtime 49), `gen-salted-password` x4096 six times, Preview 1 fuel:
  no `eval` 7.20 G; with `eval` 23.9 G as calls, 7.76 G substituted, 7.57 G without the checks;
  Shiori's SCRAM login under `wasmtime serve` 2.09-2.30 s -> 0.59-0.89 s (0.51-0.64 s substituted with
  no check at all, unsound). Pinned by the `eval` leg of the same fuel budget (45.9 M) and
  ci-spec `a-defun-written-through-a-computed-name-answers-the-new-definition` (`cwn-add32`: the
  replacement called once, in the interpreter's order, all four backends). The JVM twin does the same
  in its outlined method ([jvm-int-fusion.md](jvm-int-fusion.md), "A name with a cell").
- **Masked signed field**: `(%mask-signed-field k x)` with a literal `1 <= k <= 64` is an op node
  whose operand emits WRAPPED and sign-extends below 64 (`i64.shl`/`i64.shr_s` by `64 - k`): the
  JVM twin's node (`.kb/jvm-int-fusion.md`), same fallback through `_big_xor`/`_big_and`/`_rat_add`
  under the `LOGXOR`/`LOGAND`/`+` operators. A program spelling the name gets the `LOGXOR` row of
  the operator table (`WasmOperandTypes.LOWERED_TO`), else the report would carry no operator.
  Pinned by `WasmLispCompilerIntegrationTest.aMaskedSignedFieldAnswersTheSameFusedUnfusedAndAsAComponent`.
- Peepholes: `emitFastWrapped` (unchecked wrap-around i64 under a non-negative literal `logand`
  mask or power-of-two `mod`); `emitInlineCheckedLiteral` (a literal `+`/`-` operand becomes a
  plain i64 op plus ONE signed compare, exact for every i64 incl. `Long.MIN_VALUE`, `k = 0`
  emitting nothing; one shared slot `Ctx.fxLitTempSlot`); inline root boxing (i31 test +
  `ref.i31`, only out-of-i31 calls `_int_new`); all-literal subtrees constant-fold exactly
  (overflow / zero divisor aborts the fold).
- **Fused comparisons**: binary `= < > <= >=` via `tryCompileCompare` (hooked in
  `WasmComparisonCompiler`, `_rat_cmp_bits`-with-mask fallback); the `boxResult = false` variant
  lets `WasmWhileCompiler`/`WasmIfCompiler` test the raw i32 via `tryCompileConditionI32` -- which
  emits the generic `_rat_cmp_bits & mask` RAW too when fusion declines or is off, at every level
  (`.kb/wasm-ref-type-fold.md`): a condition never boxes to `t`/`nil`.
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
- A single fusable op with neither a raw-reading leaf nor a literal operand. It calls
  `_rat_add`/`_rat_sub`/`_rat_mul`, whose i31 head (below) is what makes it fast.
- More than **64 ops or 32 expression leaves** (the site emits the tree twice;
  `.kb/wasm-function-body-size.md`).
- A node whose immediate argument is a literal double (the `hasDoubleLiteral` f64 path keeps
  owning it; same for literal ratios / bigints).
- Async resume bodies (`ctx.asyncResume != null`), and a call to an asyncMode
  `rontolisp:async-defun` -- the name never enters `Ctx.inlinableDefuns` because a call must
  answer the `TYPE_FUTURE` its state machine builds (`.kb/async-await.md`).
- Division (`/`) is never fused (exact ratios).

## The generic helpers' i31 head (the unfused single op)

**Invariant: `_rat_add`/`_rat_sub`/`_rat_mul` answer two i31 operands exactly as their
`_big_*` path would, before any dispatch, and `_rat_cmp_bits` compares them.** At every level
(`WasmRatioRuntimeBuilder.buildRatBinaryBody`) each opens with
`if (a is i31 & b is i31) return _int_new(a op b)` in i64 -- exact, `|a|,|b| <= 2^30`, and
`_int_new` picks the narrowest tier. Without it an unfused `(+ a b)` over plain boxed
operands (every recursive function's `(+ (f ...) (f ...))` tail, a `+` of two parameters)
paid `_rat_add -> _big_add -> _int_val x2 -> _int_new`.
- Measured 2026-09-19 (x86-64, wasmtime 47, repeated `fib` runs, alternating runs):
  with the head, the bare `(if (< n 2) n (+ (fib ...) (fib ...)))` tail runs as fast as the
  let-bound tail (.kb/multiple-values.md "Cost") -- the gap between the
  two shapes was this dispatch, not the `if`. A head in `_big_add` alone (hand-patched
  module) does not close it: the `_rat_add` hop is the other half. The other bench
  programs are within noise.
- Size: a few bytes to a hundred-odd per module; a program with no generic `+ - *`
  (`hello_world`) byte-identical. `--optimize=size` kept the dispatch-only body until 2026-11-05 so
  the type-test fold could reduce `_rat_add` to a pure forwarder of `_big_add` in an integer-only
  module (.kb/wasm-ref-type-fold.md "What follows it"). Fusion is off at that level, so every `+`
  comes here, and measured there: +102 bytes on `zlib` (0.06%), +84 on `dom_reactor` (3%), which is the
  forwarder lost, against `(+ cells 4)` at 31 instructions for 95 and a third of a filter's instructions
  going through it: `http_headers` 28,126 -> 22,200 (`.kb/proxy-wasm-sdk.md`).
- Tests: `WasmRatioRuntimeBuilderTest`,
  `WasmLispCompilerIntegrationTest.theGenericArithmeticHelpersAnswerEveryTierThroughTheirI31Head`
  (tier boundaries at every level, SBCL output), ci-spec `eql-of-a-literal-and-a-literal-evaluated-twice`
  (the comparisons).

## Mechanics

`WasmIntFusionCompiler` (classify -> collect leaves -> emit fast + fallback), hooked into
`WasmExprCompiler`'s per-op cases ahead of the per-op compilers. Each `OpNode`/`ArefLeaf` keeps
the innermost located form it evaluates under and the inlined defun holding it (`Site.source`,
`bodyOwner`), which the fallback hands `--report-locations` per operation
([error-handling.md](error-handling.md)); without the option nothing is emitted for them. `WasmFxRuntimeBuilder` builds the
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
`.theSizeLevelDeclinesTheSpeedTradesWithoutChangingAnyResult`,
`.arefChecksItsArraysRankAndItsSubscriptsType` (every level, P1 and component); ci-spec
`fused-integer-expression-trees`, `flet-fusion-and-unboxed-locals`,
`fused-comparisons-and-raw-leaf-stores`. The order: `FastPathEvaluationOrderFixture`
(`.fastPathsKeepTheInterpretersEvaluationOrder`, every level, P1 and component) and
ci-spec `fast-paths-keep-the-evaluation-order`.
