# WASM integer expression-tree fusion (the unboxed fast path)

**Invariant: fusing an integer expression tree must never change a result, an observable side
effect, or an error shape -- the fast path is an optimization with a total fallback, not a
semantic variant.** The fallback is emitted whole; what the module SHIPS of it is decided
afterwards by the type-test fold (`.kb/wasm-ref-type-fold.md`), which prunes the arms no leaf can
reach -- the proof the emitter cannot make is made on the finished bytes.

The wasm-GC backend (Preview 1 AND `--component`; `--no-gc` is i64-native and unaffected)
compiles a nested tree over `+ - * mod rem logand logior logxor lognot ash` (`1+`/`1-`
normalized) into ONE unboxed evaluation: leaves evaluated once, left to right, into scratch
locals; interior raw `i64`; only the root boxes, through `_int_new`.

## How exactness survives the raw path

- **Per-leaf guard**: i31 unboxes, `TYPE_BIGNUM` reads its i64 field, ANYTHING else (float,
  ratio, `TYPE_BIGINT`, non-number) branches to the fallback.
- **Per-operation overflow check**: `_fx_add`/`_fx_sub`, `_fx_mul` (conservative: the borderline
  band bails even when the product would fit), `_fx_ash` return `(i64, i32 flag)`; a flag
  `br_if`s to the fallback. `_fx_mod`/`_fx_rem` trap explicitly on a zero divisor, preserving the
  generic `_big_divrem` trap shape.
- **The fallback recomputes the WHOLE tree from the SAME leaf locals** through the generic
  helpers, bit for bit, including `.kb/wasm-bignum.md`'s narrowest-tier invariant.
- Emitted shape: `block $done (result eqref) { block $bail { fast; _int_new; br $done } fallback } end`.
- Exact strength reductions: `(mod x 2^k)` -> mask; `(ash x -k)` -> arithmetic shift clamped at 63.

## The interpreter's order

The JVM twin's rule and plan (`.kb/jvm-int-fusion.md`, "The interpreter's order"): before an
observable leaf while applications are pending, `evalLeaves` runs `PendingGuard` (the pending
operations' leaves are i31 or `TYPE_BIGNUM`, divisors non-zero, counts within `i32`) or
`LeafGuard`; anything else (an aref read before a leaf that may store, a computed divisor or
count) is made opaque. This backend's own:

- **The probe is inline**: `block $checked { block $fails { checks; br $checked } probe }`, the
  probe being each pending root's generic fallback, value dropped. More than `MAX_OPS` probe
  operations or `MAX_ORDER_CHECKS` leaf tests declines fusion (`classifyOrdered` answers null),
  bounding the body (`.kb/wasm-function-body-size.md`).
- Every leaf already lands in a scratch local, so the checks read those; a site with no boundary
  emits what it did before.

## What else rides the fast path

- **Leaf kinds**: `RawLeaf` (unboxed locals, `.kb/wasm-unboxed-locals.md`), `ArefLeaf` (packed
  integer vectors, `.kb/packed-integer-vectors.md`), `ConstLeaf`. An aref's INDEX is an operand
  of the tree (`arefLeaf`), as on the JVM (here it also gains in steady state); reads run after
  every other leaf's unbox and bail unless the index is an i31. The fallback checks in the
  interpreter's order: subscript an integer (`_idx_chk`), rank 1 (`_arr_check_rank`), bound,
  read. A `dotimes` variable over a LITERAL bound is a COUNTED `RawLocal` (no snapshot, guard or
  `_int_new`), registered at every optimize level (`.kb/wasm-counted-loops.md`). Registration is
  in classify (source) order, not tree-walk order.
- **Substitution**: fusion-inlinable defuns (ironclad's `mod32+`/`rol32`) and
  `(funcall __FLETn_f ...)` of a let-bound local function with a closed integer body
  (`Ctx.localIntLambdas`, `.kb/flet-labels.md`) splice their bodies; a body wholly `(aref P I)`
  substitutes as an `ArefLeaf` over the CALLER's operands (`inlineArefOperand`). Traps:
  `eligibleLocalLambda` must skip leading `(declare ...)` inside the flet lowering's
  `(block name ...)` (via `singleBodyExpr`), or a declared flet param silently demotes to
  per-iteration funcall dispatch; **`substituteCall` ROLLS BACK leaves registered by a failed
  attempt**, or a side-effecting argument evaluates twice.
- **A settled defun tail is still a substitutable body.** Any multiple-value operator settles
  every defun tail (`LispMacroExpander.settleDefunTails`, [multiple-values.md](multiple-values.md)),
  wrapping it in a `%mv-spill` clear; `inlinableBody` reads through
  `LispMacroExpander.clearedForm` (the JVM's too), and a root-position call clears the channel
  after its tree. Trap: declining the wrapper gives identical results, so only a cost pin catches
  it (ironclad PBKDF2 x4096 1230 ms declined vs 385 ms).
  Pinned by `WasmLispCompilerIntegrationTest.compileInlinableDefunsFuseWithoutChangingResults`
  (a fuel budget: a million substituted iterations 45.8 M, as calls 233.6 M) and the `ci-svc-inc`
  line of `multipleValueChannelIsExactInSingleValueContexts` (the clear).
- **A name with a cell is substituted too** (`CelledCall`). A program holding `eval` gives every
  named defun a cell ([symbol-runtime-api.md](symbol-runtime-api.md), "A write through a computed
  name"; clack's `eval-file` holds `eval`). Each fast path first bails if any substituted name's
  cell is filled (`emitCellChecks`); the fallback CALLS the named entry, once per argument use
  (`emitFallbacks`). Order: substitutes only when every leaf after a celled call's application
  begins is inert (`keepsCelledOrder`), else those calls stay leaves. A root call in tail position
  stays a call (its `return_call` keeps a tail call through a replaced name proper). Fuel with
  `eval`: 23.9 G as calls, 7.76 G substituted (no `eval` 7.20 G). Pinned by the `eval` leg of the
  same fuel budget (45.9 M) and ci-spec
  `a-defun-written-through-a-computed-name-answers-the-new-definition` (`cwn-add32`: the
  replacement called once, in the interpreter's order, all four backends). The JVM does the same
  in its outlined method ([jvm-int-fusion.md](jvm-int-fusion.md)). A leaf calling such names
  compiles twice instead ("A leaf compiled twice").
- **Masked signed field**: `(%mask-signed-field k x)` with literal `1 <= k <= 64` is an op node
  whose operand emits WRAPPED and sign-extends below 64: the JVM twin's node, same fallback under
  the `LOGXOR`/`LOGAND`/`+` operators. A program spelling the name gets the `LOGXOR` row of the
  operator table (`WasmOperandTypes.LOWERED_TO`), else the report would carry no operator. Pinned
  by `WasmLispCompilerIntegrationTest.aMaskedSignedFieldAnswersTheSameFusedUnfusedAndAsAComponent`.
- Peepholes: `emitFastWrapped` (unchecked wrap under a non-negative literal `logand` mask or
  power-of-two `mod`); `emitInlineCheckedLiteral` (a literal `+`/`-` operand is a plain i64 op
  plus ONE signed compare, exact for every i64 incl. `Long.MIN_VALUE`; one shared slot
  `Ctx.fxLitTempSlot`); inline root boxing (only out-of-i31 calls `_int_new`); all-literal
  subtrees constant-fold exactly (overflow / zero divisor aborts the fold).
- **Fused comparisons**: binary `= < > <= >=` via `tryCompileCompare` (`_rat_cmp_bits`-with-mask
  fallback); `WasmWhileCompiler`/`WasmIfCompiler` test the raw i32 via `tryCompileConditionI32`,
  which emits the generic `_rat_cmp_bits & mask` RAW too when fusion declines or is off, at every
  level (`.kb/wasm-ref-type-fold.md`): a condition never boxes to `t`/`nil`.
- **Raw stores**: `%aset` values compile raw through `tryCompileRaw` + `_iv_set`; a bare
  ArefLeaf/ConstLeaf root is accepted, so `(setf (aref dst i) (aref src j))` moves bytes unboxed.
  A pinned packed store's subscript compiles raw where its site cannot bail
  (`tryCompileRawSubscript`; [declarations-type-checks.md](declarations-type-checks.md),
  "Declaration-driven array emission").
- `(ldb (byte s p) x)` with literal specs classifies through its expansion. The symbol `t` is
  built once in a module global (`_t_sym`, the always-last global).

## Ranges, and sites with nothing to bail on

Every node has a `range` (a constant's, a packed element's width, a counted or exact local's,
all of i64 for a boxed leaf or a dual local); an operation's follows by interval arithmetic in
`emitFast`'s order. A step whose exact result provably stays in i64 (`stepRange`) is the plain
i64 op. A site that cannot bail (`canBail`: no boxed leaf, shadowed local, non-direct read, cell
or checked step; `fastBails` mirrors `emitFast`'s choices) emits its fast path alone, no
`block $bail`, no fallback -- in `tryCompile`, `tryCompileCompare`, `tryCompileRaw` and
`compileRawStore`.

- **The snapshot barrier** (`emitSnapshotBarrier`). A local's raw value is a PURE expression from
  its last join on; with no-bail stores an exact local's runs through every assignment (a whole
  unrolled SHA-256 block). Cranelift places a pure value at its first use, and a use in a fallback
  dominates nothing after it, so each later use rebuilt the chain, quadratically (block 0.78 ->
  1.3 MB of machine code, 2.9x slower). A site that can bail turns its non-counted raw snapshots
  into block parameters first -- `memory.size; i32.eqz; if; (i64.const 0; local.set snap)*; end`,
  a branch never taken that the engine cannot prove -- before each order boundary and the fast
  path (0.52 MB, 35% fewer cycles).
- **Direct reads** (`ArefLeaf.direct`, `markDirectReads`). A read of a packed vector pinned by a
  declaration or a constant does not bail: a false declaration's `ref.cast` traps (as the unfused
  declared read does, `.kb/declarations-type-checks.md`), a bad bound or missing constant array
  reports through the one `_idx_ref` call (EH mode) and `unreachable`. Only when no operation the
  interpreter applies before the read can signal over integers (except those its own index
  computes): then the first thing to go wrong in the interpreter's order is the read itself. A
  pinned read in a site that keeps its fallback tests ONE width (`ArefLeaf.packedType`).
- **A general array's integer elements** (`ArefLeaf.elements`, `emitGeneralArefRead`): a read of
  an array a declaration pins to the general kind with integer elements
  (`.kb/declarations-type-checks.md`, "Declaration-driven array emission"), or that
  `(the spec (aref ..))` asserts, takes the element off the storage when the cell names it and the
  index is in bound, and unboxes it in range (`emitTrustedUnbox`, trap on contradiction). Anything
  else bails, or in a direct read is the generic read (`_idx_ref` of rank 1, then `_arr_get`), so
  nil, a view and an out-of-bound index report what they report anywhere. A Java `long[]` is such
  an array: `LongSum` 93.8 s -> 1.44 s (the JVM 0.45 s).
- **A declared leaf in the i31 range has one arm**: such an integer is never a `TYPE_BIGNUM`
  ([wasm-bignum.md](wasm-bignum.md)), so `emitTrustedUnbox` casts to i31 and checks the range.
- **Constant arrays** (`WasmArrayCompiler.constantArrays`). A top-level `(defconstant name V)`,
  V a packed vector, named only as a rank-1 `aref`'s array, one definition, in a program that
  names no variable at run time (no `eval`, `set`, `symbol-value`, `progv`, `--dynamic`): a fused
  read knows its kind and length (ironclad's `+sha256-round-constants+`: no bound check). Fused
  reads only -- `arrayKindOfExpr` does not answer it, so the unfused read stays generic for a
  read before the definition.
- **Array parameters** (`arrayParams`). A substituted body's parameter that only names an
  `aref`'s array reads the caller's variable, which must be bare and unassigned by the other
  arguments; not for a celled call, whose fallback passes every argument's node. With
  `(dpb new (byte s p) n)` classified through `expandDpb` and `inlinableDefuns` grown to a
  fixpoint, ironclad's `ub32ref/be` substitutes whole into `fill-block-ub8-be`.
- Pinned by the fuel budget in
  `WasmLispCompilerIntegrationTest.compileInlinableDefunsFuseWithoutChangingResults` (a
  SHA-256-shaped compression, 61.0 M against 95.5 M with shadowed round variables) and the
  exact-locals program in `.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`. In a program
  holding `eval` a celled root proves no range and no celled site is free of bails; "A leaf
  compiled twice" recovers it.

## Declared leaves

- **A declared leaf has no bail** (`ExprLeaf.trusted`): a variable a declaration gives an integer
  range within i64, or a `(the spec X)` leaf, unboxes checked against the range, trapping on
  contradiction (`emitTrustedUnbox`; [declarations-type-checks.md](declarations-type-checks.md),
  "Declared integer ranges"). A site over such leaves and wrapped or range-proven steps has no
  fallback.
- **A shift by a counted range is one i64 shift** (left within `[0, 62]`, wrapped under a mask
  within `[0, 63]`, right within `[-63, 0]`): Java's `(logand n 31)` counts. The order guards
  treat a count whose range fits an i32 as non-signalling, a divisor whose range excludes 0 as
  non-zero.
- **`logand`, `logior`, `logxor` stay in the narrowest signed field holding every operand**
  (`bitwiseRange`, fused ranges and `valueRange` alike): an `int`'s `|=` stays an `int`.
  `valueRange` reads `%mask-signed-field` as its field, so a binding assigned one is exact.
- **An order boundary with nothing to check is no boundary** (`planOrder`): its probe could
  never run.
- **A masked signed field over a lone leaf it cannot bail on is fused** (unfused it is the inline
  lowering, `expandMaskSignedField`); over a leaf that can bail it declines. The JVM fuses every
  such field ([jvm-int-fusion.md](jvm-int-fusion.md)).
- Numbers: [java-frontend.md](java-frontend.md), "Numbers".

## Quotients of two integers

**`(truncate a b)` and `(floor a b)` whose operands read no leaf that can bail are an operation
of the tree** (`emitQuotient`): `i64.div_s`, and for `floor` one step down where the exact
remainder differs in sign from the divisor. `(values (floor a b))`, Java's `floorDiv`, reads as
its `floor`. A literal divisor is an `i64.const` (Cranelift multiplies).

- **A divisor whose range holds 0** is tested and calls `_div_zero` (`unreachable` outside EH
  mode), the generic division's report; `signalsOnIntegers`, the order guard (`pendingGuard`,
  `divides`) and the fold treat a quotient as `mod`/`rem`.
- **`MIN_VALUE / -1`** (2^63, and `i64.div_s`'s trap): where the ranges hold it, a bail; under a
  mask (`emitFastWrapped`, Java's `long` `/` and `floorDiv`) the negation, which wraps to the
  bits the mask keeps. An `int`'s range never holds it.
- **The fallback** is the unfused quotient (`WasmIntConvCompiler.emitQuotientOfSlots`:
  `_big_fdiv` for two exact integers, float and ratio arms otherwise, no i31 arm).
- **Over a leaf that can bail it stays a leaf** (`readsBailingLeaf`; classification rolls back
  what its operands registered): unfused it divides two i31s inline (`.kb/linalg-simd.md`, "Two
  integers"), while fused its generic division went into the fallback and every order probe and
  grew functions (`gguf::%type-bytes` 598 -> 919 B), bignums bailing every time.
- Measured: one `long` `a / b` 715 -> 102 B; `DivLoop`
  1.80 -> 0.67 s; no function grew.
- Pins: `testsupport/DeclaredIntegerProgram` (`quot`, `qf`: `MIN_VALUE / -1` masked at 32 and 64
  bits and exact, a zero divisor, a float and a ratio through the fallback; JVM, P1 and the
  component), `FastPathEvaluationOrderFixture` (a zero divisor before a later leaf, a symbol
  dividend), ci-spec `declared-integer-ranges-answer-what-undeclared-code-answers`, java-spec
  `a-long-wraps-at-64-bits` (`Long.MIN_VALUE / -1` and `floorDiv` of it).

## A leaf compiled twice

**A leaf defun runs nothing that can run user code, so what holds when it starts holds until it
returns.** `WasmLeafVersioning` compiles such a body twice into one function: `if` every
assumption holds, the body compiled on them, `else` the body with the speed trades declined
(`Ctx.declinesSpeedTrades`, as `--optimize=size` does, so the cold copy stays small). The test
runs once per call, at entry.

- **The assumptions**: every cell of an inlinable defun the body may call is empty
  (`Ctx.uncelled`), and every constant of `Ctx.leafConstantArrays` it names holds a packed vector
  of its kind at least as long as its definition's (`emitHoldsVector`) -- `constantArrays`'
  analysis in a program that CAN name a variable at run time, `defconst`'s unfolded
  `(if (boundp 'n) (symbol-value 'n) V)` counting as a definition.
- **The leaf** (`Scan`; anything unknown is no leaf): sequencing and binding special forms,
  `dotimes`, `PURE_OPERATORS`, calls of inlinable defuns, and a `funcall` of a let-bound `lambda`
  nothing assigns. An error a helper signals never returns into it (no correctable operator offers
  a restart). A quoted aggregate is refused: one site compiled twice would hold two constants.
- **Each `lambda` registers once**: `flet` expansions are memoized (`Ctx.fletExpansions`), so both
  copies meet the same conses and share a funcId (`Ctx.lambdaRegistrations`).
- Measured: `update-sha256-block` with `eval` 156 KB -> 60 KB; ten 4096-round derivations
  3.7-4.1 s -> 1.34-1.53 s, as fast as without `eval`. SCRAM logins in [asdf.md](asdf.md).
- Pinned by the `eval` leg of the compression fuel budget in
  `WasmLispCompilerIntegrationTest.compileInlinableDefunsFuseWithoutChangingResults` (62.1 M, the
  no-`eval` budget; fuel cannot see the table half, whose cost is machine code), the `eval` leg of
  the exact-locals program in `.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath` (a table
  read before its definition takes the cold copy), and ci-spec
  `a-defun-written-through-a-computed-name-answers-the-new-definition` (`cwn-mix32` and
  `cwn-double32` are leaves: the replacement through the cold copy).

## The size level: the smaller emission of each site

**Invariant: under `--optimize=size` a site is fused exactly when its fused emission is strictly
smaller than its unfused one, measured in the body being written** (`WasmSizeChoice`). Speed
levels never measure. `tryCompile` (value), `tryCompileCompare` (the i32 truth; the box is the
caller's either way) and `tryCompileRaw` (a packed store's value) each hand over their caller's
unfused path; each trial writes into the body, is measured (an i64 local reference at its final
width, not its 3-byte placeholder) and taken back (`Mark`: bytes, locals, i64 references,
scratch locals, landing-pad carries), and the winner is written for real.

- **A trial compiles no user code.** Every compound leaf form (`ExprLeaf.expr`,
  `ArefLeaf.arrayExpr`, and the operand under each `the`) is read from a placeholder local in
  both trials (`WasmExprCompiler.compileExpr` asks `WasmSizeChoice.placeholder` first), so what
  is measured is what differs. **Each placeholder must be read exactly once per trial**, else the
  site stays unfused: a compiler reaching a leaf past `compileExpr` (an f64 operand compiled raw)
  measured the leaf's whole code on one side only, and the first cut fused sites 50% larger.
  A leaf form outside the site's own form (a substituted body's) or naming `lambda`/`flet`/
  `labels` stays unfused; a trial that registered a lambda anyway throws (a lambda cannot be
  taken back).
- The unfused trial re-enters the dispatch (`WasmExprCompiler.compileDispatched`) with the entry
  point declining that one form (`WasmSizeChoice.declines`, by kind and identity); nested sites
  measure themselves. A choice is remembered per body by kind and form, so a nested site is
  measured once, not once per enclosing trial.
- The speed levels' heuristic gates (a lone operation over plain leaves) do not apply: the
  measurement decides. The both-plain-leaves comparison stays unfused, and a raw subscript
  (`tryCompileRawSubscript`) and the unboxed locals stay declined: a local's choice is per
  binding, over a body that is user code, so no trial can measure it.
- What it measures is the site, not the module: the `_fx_*` helpers a fused site keeps and the
  helpers an unfused one keeps are not counted, nor are the later passes. Measured where it
  matters, the module (ci-spec corpus, P1).
- Measured 2026-11-25 (`--optimize=size`): `hello-java` 5,000 -> 3,331 B (default 3,956);
  `examples/java/http/Server.java` 444,990 -> 430,192; ci-spec corpus 13,162,417 -> 13,155,486,
  compile 63.0 -> 69.7 s. The unboxed locals on top (exact and declared, declared parameters):
  `hello-java` 3,198, `Server.java` 429,371, the corpus +4,215 against fusion alone -- not taken.
  size-report: no row grew raw; the Java Servlet and WebMvc.fn rows -35 to -47 KB (gzip -1.7 to
  -2.9 KB), `zlib` -52 B; every clack Worker -17 B from one comparison
  (`(< (raw) 22)` raw instead of boxed through `_rat_cmp_bits`), its gzip moving -61 to +8 B.
- Pinned by `WasmLispCompilerTest.theSizeLevelShrinksTheModuleAndTheDefaultLevelIsTheBareFlag`
  (masked 32-bit fields over trusted leaves: no larger at `size` than at `default`; 3,299 against
  1,044 before) and
  `WasmLispCompilerIntegrationTest.theSizeLevelTakesTheSmallerEmissionOfEachSiteWithoutChangingAnyResult`
  (the same answers at both levels). The order fixture runs at every level.

## When fusion does NOT trigger (and must keep not triggering)

- Under `--optimize=size`, a site whose fused emission is not strictly the smaller ("The size
  level: the smaller emission of each site"), and every site in a body under `--report-locations`.
- A single fusable op with neither a raw-reading leaf nor a literal operand: it calls
  `_rat_add`/`_rat_sub`/`_rat_mul`, whose i31 head (below) makes it fast; behind `integerp` tests
  of both operands it is a fused tree over two exact locals ("An integer test compiled twice").
- More than **64 ops or 32 expression leaves** (the tree is emitted twice;
  `.kb/wasm-function-body-size.md`).
- A node whose immediate argument is a literal double (`hasDoubleLiteral` f64 path), ratio or
  bigint.
- Async resume bodies (`ctx.asyncResume != null`), and a call to an asyncMode
  `rontolisp:async-defun` -- never in `Ctx.inlinableDefuns`, because a call must answer the
  `TYPE_FUTURE` its state machine builds (`.kb/async-await.md`).
- Division (`/`) is never fused (exact ratios).
- A `floor`/`truncate` over a leaf that can bail ("Quotients of two integers").

## The generic helpers' i31 head (the unfused single op)

**Invariant: `_rat_add`/`_rat_sub`/`_rat_mul` answer two i31 operands exactly as their `_big_*`
path would, before any dispatch, and `_rat_cmp_bits` compares them.** At every level
(`WasmRatioRuntimeBuilder.buildRatBinaryBody`) each opens with
`if (a is i31 & b is i31) return _int_new(a op b)` in i64 (exact: `|a|,|b| <= 2^30`). Without it
an unfused `(+ a b)` over boxed operands (every recursive `(+ (f ...) (f ...))` tail) paid
`_rat_add -> _big_add -> _int_val x2 -> _int_new`; a head in `_big_add` alone does not close the
gap, the `_rat_add` hop is the other half.
- `--optimize=size` takes it too even though it costs the type-test fold its `_rat_add` ->
  `_big_add` forwarder (.kb/wasm-ref-type-fold.md "What follows it"): fusion is off there, so
  every `+` comes here (`http_headers` 28,126 -> 22,200 B, `.kb/proxy-wasm-sdk.md`; +102 B on
  `zlib`).
- **A comparison of two plain operands has the head at its site**
  (`WasmComparisonCompiler.emitUnfusedCompare`, speed levels, value and test positions): both
  operands in temps, two `br_on_cast_fail` to i31 and the i32 compare, the generic comparison
  behind them. The both-plain-leaves shape stays out of `tryCompileCompare` (its fallback would
  be this again, twice the bytes). 38 B a site; `sorting.star` 4.28 -> 4.01 s, modules 0.7-0.8%
  larger. The call it saves spills the live references around it, which the helper's own head
  cannot buy back.
- Tests: `WasmRatioRuntimeBuilderTest`,
  `WasmLispCompilerIntegrationTest.theGenericArithmeticHelpersAnswerEveryTierThroughTheirI31Head`
  (tier boundaries at every level, SBCL output, the five comparisons of two parameters too),
  ci-spec `eql-of-a-literal-and-a-literal-evaluated-twice` (the comparisons).

## An integer test compiled twice

**Invariant: `(if (and (integerp a) (integerp b) rest..) then else)` answers what it answers
compiled once.** At the speed levels `WasmFixnumVersioning` emits it twice: `block $end {
block $else { block $miss { each variable `br_on_cast_fail $miss i31` into an i64 local; rest
(complemented) `br_if $else`; then; `br $end` } drop; test (complemented) `br_if $else`;
then; `br $end` } else }`. In the first copy each tested variable IS an exact unboxed local of
the i31 range (`RawLocal.exact`, `.kb/wasm-unboxed-locals.md`; it leaves `Ctx.locals` for
`Ctx.rawLocals` for the rest of the test and the then-arm), so every raw-local consumer reads it:
a fused leaf with no guard, `+` of two such as `i64.add` with nothing to bail on, a comparison
raw, a subscript with no type check (`answersInteger`). The second copy is the `if` as written.

- **Why**: every operator reading the variable asked the test again (a type test is about a
  dozen instructions, below), and a single operator over two plain variables is no fused tree,
  so `(if (and (integerp a) (integerp b)) (+ a b) ..)` was six `ref.test`s and a CALL of
  `_rat_add`. That is what the Starlark lowering writes for every operator over possible ints
  (`.kb/starlark-frontend.md`, "What the lowering knows").
- **Which variables**: an `integerp` argument that is an ordinary local (no cell, not special,
  not unboxed already) the copied forms neither assign nor capture.
- **Which sites**: copied forms within `MAX_COPIED_CONSES` (emitted twice). Not at
  `--optimize=size`, under `--dynamic`, in an async body or at top level, and not inside the
  second copy (`Ctx.declinesFixnumVersions`), so a nest is copied once per level, not `2^depth`.
- **Every position**: value, tail, effect, and a TEST: `WasmConditionCompiler` compiles
  `(if a b c)` in a test as `a; if (result i32) [b as a test] else [c as a test]` (`tryCompileIf`)
  instead of boxing the value, so Starlark's `if n < 2:`
  (`(if (if (integerp n) (< n 2) (generic n 2)) ..)`) stays raw.
- **A literal `mod`, `rem` and `*` is inline in a fused tree** (`emitInlineLiteralOp`):
  `(rem a k)` with `k > 0` is `i64.rem_s`, `(mod a k)` adds `k` to a negative remainder
  (`r + ((r >> 63) & k)`), `(* a k)` with `|k| <= 2^31` bails unless `a` is within i32. Why: the
  helper's divisor is a variable to the engine, a 64-bit division where a constant is a
  multiplication (30,000,000 `(mod i 7)`: 0.63 -> 0.35 s).
- Measured: `fib.star` 0.36 -> 0.16 s,
  `loop.star` 0.62 -> 0.39.
- **What an instruction costs is how many there are.** `wasmtime objdump --filter
  'function[N]' m.cwasm` prints a function's machine code, what to read when the guest profiler
  is not believable. On wasmtime 49 x86-64 a struct `ref.test` / `br_on_cast` of a
  `(ref null eq)` is about twelve instructions and four loads (the heap base reloaded at every
  test); an i31 test is one `test`; `array.get` by a CONSTANT index some fifteen; a reference live
  across a never-taken call is still spilled to its stack-map slot and reloaded.
- Tests: `WasmLispCompilerIntegrationTest.theGenericArithmeticHelpersAnswerEveryTierThroughTheirI31Head`
  (the `v`-functions: every position, a variable assigned, captured and rebound, every tier and a
  non-integer through both copies, the literal operations past each boundary; every level, SBCL's
  output) and ci-spec `an-if-over-integerp-tests-answers-for-every-tier` (the four backends).

## Mechanics

`WasmIntFusionCompiler` (classify -> collect leaves -> emit fast + fallback), hooked ahead of
`WasmExprCompiler`'s per-op compilers. The fallback hands `--report-locations` each operation's
source (`Site.source`, `bodyOwner`; [error-handling.md](error-handling.md)). `WasmFxRuntimeBuilder`
builds the `_fx_*` helpers, always present like the limb runtime; their types are the module's
first multi-result function types.

**Profiling trap**: wasmtime's guest profiler attributes samples inside a LARGE function to small
CALLEE functions (a 60k-instruction fused body shows ~0 self, a tiny accessor an impossible
30-60%): it samples at function entries and loop back edges, so the time before a call is the
callee's. Wall-clock A/B against a standalone reproduction is the reliable oracle. **A sampler
that does not**: a
`ptrace` PC + frame-pointer sampler named through `wasmtime run --profile=perfmap`; per-instruction
samples go beside binutils' `objdump -d` of the `.cwasm` (`wasmtime objdump` stops at a
function's first `br_table` jump table). No `perf` needed. Findings: `.kb/starlark-frontend.md`,
"Where the time is".

## Tests

`WasmLispCompilerIntegrationTest.fusedIntegerExpressionTreesMatchTheGenericPath`,
`.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`,
`.fusedComparisonsAndRawLeafStoresMatchTheGenericPath`,
`.theSizeLevelTakesTheSmallerEmissionOfEachSiteWithoutChangingAnyResult`,
`.arefChecksItsArraysRankAndItsSubscriptsType` (every level, P1 and component); ci-spec
`fused-integer-expression-trees`, `flet-fusion-and-unboxed-locals`,
`fused-comparisons-and-raw-leaf-stores`. The order: `FastPathEvaluationOrderFixture`
(`.fastPathsKeepTheInterpretersEvaluationOrder`, every level, P1 and component) and ci-spec
`fast-paths-keep-the-evaluation-order`.
