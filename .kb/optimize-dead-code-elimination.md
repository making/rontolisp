# `--optimize` (levels; dead-code elimination, WASM + JVM)

On by default in the CLI (`--optimize[=LEVEL]`). `WasmLispCompiler` / `JvmLispCompiler` / `NoGcWasmCompiler` builders all take a
`compiler.OptimizeLevel` through `optimize(...)`.

## The levels

| spelling | `eliminatesDeadCode()` | `prefersSizeOverSpeed()` |
| --- | --- | --- |
| `--optimize=off` — `NONE` | no | no |
| (absent), `--optimize`, `--optimize=default` — `DEFAULT` | yes | no |
| `--optimize=size` — `SIZE` | yes | yes |

- **Invariant: the level-less compiler constructors are `DEFAULT`, not `NONE`** -- the browser
  playground (`RontoPlayground.compileJvm`/`compileWasm`) calls exactly those and has no flags.
  **A test asserting on the UNOPTIMIZED shape must say `OptimizeLevel.NONE` out loud.**
- **Invariant: the bare `--optimize` is `DEFAULT`**; an ABSENT flag is `DEFAULT` too
  (`OptimizeLevel.parse(null)`), and `NONE` is spelled `--optimize=off`. A near-miss spelling
  (`none`, `no`, `high`) is an `IllegalArgumentException`, never a silent `DEFAULT`.
- Those two predicates ARE the level: `OptimizeLevelTest.everyLevelIsDistinguishableAndSpellable`
  fails if two levels answer both the same way -- **do not ship a level that is an alias**.
- **A VALUE, not a second flag**: `--optimize` stays an `OPTIONAL` entry of `CliOptionTable` (no following value) and the
  parser takes the `--key=value` form -- making it a `VALUE` would make
  `rontolisp app.lisp --optimize -o out.wasm` read `-o` as the level.
- Pins: `OptimizeLevelTest.theBareFlagIsTheDefaultLevel` / `theAbsentFlagIsTheDefaultLevel` /
  `theOffSpellingIsNoOptimizationAtAll`, `CliOptionsTest.theBareFlagKeepsItsEmptyValue`,
  `JvmLispCompilerTest.theFlaglessBuildIsTheOptimizedOneAndOffIsTheWayBack`.

### What `SIZE` declines
Speed-for-size trades, on at `off` and `default` alike. **wasm-GC**: integer expression-tree fusion
where it is not the smaller emission (`.kb/wasm-int-fusion.md`) and unboxed dual-representation
locals (`.kb/wasm-unboxed-locals.md`),
both switched by `WasmIntFusionCompiler.speedTradesEnabled(ctx)`; the smaller inline fast paths
(vector element access, `length`, defstruct predicates/readers, comparison fixnum arms, host string
copy, `_hash`) each test `prefersSizeOverSpeed()` -- grep that name for the list.
**JVM**: typed numeric loops (`.kb/jvm-typed-loops.md`), integer fusion + unboxed locals
(`.kb/jvm-int-fusion.md`) -- `Ctx.typedLoops`, `Ctx.intFusion` -- and the value tails' dispatcher
copy `_vtcd<n>` (`.kb/jvm-tail-bounce.md`). **`--no-gc`** accepts the level and emits byte-identical
output (`NoGcWasmCompilerTest.theSizeLevelIsADocumentedNoOpOnThisBackend`), as does any program
without those shapes (`JvmLispCompilerTest.theSizeLevelChangesNothingWithoutASpeedForSizeTrade`).

The size win barely varies (-16% to -24%); the run-time price varies more than thirtyfold, because
only INTEGER arithmetic fuses. **A fused site that is the smaller emission is no trade**: the size
level measures each site and keeps those fused (`.kb/wasm-int-fusion.md`, "The size level: the
smaller emission of each site"); a Java `int` operation is one. `WasmLispCompilerTest`'s level
program uses a shadowed accumulator, so its sites keep their fallbacks and decline.
**The two wasm trades are ONE level, not two switches**: "fusion off / unboxed locals on" is
DOMINATED on both axes (larger than declining both, slower than keeping both).
`-Drontolisp.debug.norawlocals=true` still switches the locals alone.

## Before the wasm shaker: the type-test fold and the forwarder redirect
`am.ik.wasm.WasmRefTypeFolder.fold`, `WasmCallForwarding.redirect`, `WasmWriteOnlyGlobals.rewrite`
and `WasmPeephole.rewrite` run in that order in `WasmLispCompiler.shakeCore` ahead of
`WasmTreeShaker.shake` at every level but `off` (`.kb/wasm-ref-type-fold.md`: the licence, the
lattice, the numbers). All rewrite bodies in place and renumber nothing, so the claims below still
speak in pre-shake indices.

## Before the shakers: a `typecase` clause no call can select
Both shakers are name reachability, so neither can see a clause dead because of what the CALLER
passed. `compiler/DeadTypeBranchPruner` DELETES such a clause from the AST before Pass 1. Gated on
`eliminatesDeadCode()`; on wasm it runs **after** `NoWasiFilesystemStubs`, which closes the
funcall-dispatch gate on a clack Worker -- the pruner declines while the gate is open. Shares
`ArgumentShapes.maySatisfy` with the `--no-wasi` build warning (`.kb/wasm-export-no-wasi.md`).
Pinned by `DeadTypeBranchPrunerTest`.

- **Deletion is the ONLY rewrite**, so no evaluation-order reasoning is needed; an
  `(if (typep x 'pathname) ...)` is left alone.
- A parameter's shape is the JOIN over EVERY call site; a name taken as a VALUE (or occurring in
  quoted data) states nothing; a name with two definitions, or also a
  `defmacro`/`defmethod`/`defgeneric`, is left alone; the pass declines entirely under
  `RuntimeNameProducers.anyNameResolvable`.
- Over-COUNTING a call site is harmless (it widens the join toward UNKNOWN), so the call scan is
  deliberately dumb; missing one is the unsafe direction, covered by the escape rules.
- A `labels` local's parameters stay unknown.

## A dispatcher branch no call site can select
The defgeneric twin (`.kb/clos.md`): `compiler/GenericDispatchNarrowing` joins `ArgumentShapes`
over every call site of each generic, and `generateDispatcher` omits each branch no site's shapes
may satisfy; the SHAKERS drop the then-unreferenced method bodies. Only an optimizing, early-bound
compile narrows (not the interpreter, `ShadowedBuiltins`, `--dynamic` or `NONE`).

- **Satisfiability leans permissive**: DEFAULT and EQL specializers always keep a branch; a CLASS
  specializer needs INSTANCE/UNKNOWN; a TYPE name defers to `maySatisfy`, except that INSTANCE
  satisfies ANY type name.
- **Escape rules** mirror the funcall-dispatch gate's probes: `#'g` outside the direct funcall/apply
  target position, `g` in quoted data or a user macro call's arguments, and -- only while a symbol
  BUILDER is present -- any string/keyword literal spelling `g`'s member name. `anyNameResolvable`
  or any async operator declines the analysis; so does a program that appends registry-derived
  runtimes after the slot fill (MOP family, `#'make-instance` as a value, symbol-function
  forwarders). Excluded wholesale (call sites synthesized in Pass 2): `cl`-symbol names,
  accessor/writer generics, `%`-internal names, gray-stream packages, short-form combinations, any
  generic with a nested method defun.
- **The liveness fixpoint**: only the method-body defuns of narrowable generics, and plain defuns
  whose every head-position reference sits inside such bodies, may be dead; everything else is live
  with UNKNOWN parameters. An `apply` with a literal target is a CALL SITE (leading arguments
  only), not an escape.
- Pinned by `JvmDeadMethodEliminationTest.anUnselectableGenericBranchAndItsMethodShakeOut`, its
  `WasmTreeShakerTest` twin, and a program in `optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo`.
  Open: async programs are declined wholesale; an EQL specializer is never ruled out; a
  struct-name specializer is undecided in the lattice.

## The wasm tree shaker
`am.ik.wasm.WasmTreeShaker` (language-independent) runs on the finished **core module** bytes --
**including under `--component`**, after `WasmImportInjector.inject` and before the component
wrapper is built. Reachability from the roots (exports + `_start`/start section) over the actual
`call`/`ref.func` immediates; drops the rest **including unused WASI function imports**; renumbers
every surviving function AND type reference. Reachability is exact: with `eval`/`load`/`apply` the
dispatch bodies contain real `call`s to every registered function. **This is the one place the
fixed-index invariant is deliberately broken**, and only because every reference site is rewritten
in lockstep.

### Type section
- **roots** -- each surviving function's and import's type, every tag (tag section copied
  verbatim), each SURVIVING global's value type and initializer, every type immediate in a
  surviving body.
- **edges** -- type-to-type references inside the type section.
- **a `rec` group is atomic**: its structural identity under wasm-GC canonicalization is a property
  of the whole group -- which is what makes `ref.test $limbs` discriminate against `TYPE_I32ARR`
  (the same `array (mut i32)` in a different group). Naming one member keeps them all.
- A `typeidx` is an unsigned LEB; a `heaptype`/blocktype is a signed s33 whose NEGATIVE values are
  abstract shorthands naming no definition (`WasmTreeShaker.RefKind`), so only non-negative heap
  types are rewritable. Two decoder gaps throw rather than emit a corrupt module: a
  `table`/`element` section, and the four GC ops carrying a `dataidx`/`elemidx` (the pass DROPS
  data segments). The backend emits none.
- Pins: `dropsTypesTheSurvivorsNoLongerName`, `keepsTheTypesAnEhModeModuleStillNames`,
  `WasmTreeShakerCorpusTest` (`wasm-tools validate` over the `ci-spec.yaml` corpus, both WASI modes).

### Global section
The backends emit one `(mut (ref null eq)) = null` per top-level Lisp variable whether or not the
program reads it.

- **roots** -- every IMPORTED global (its index is part of the host contract), every EXPORTED
  global, and every global a SURVIVING body reads or writes (`WasmSections.RefKind.GLOBAL`).
- **edges** -- a live global's initializer may name another global.
- A dead global's initializer may name a concrete type, whose whole `rec` group then retires with
  it: a shaken `(print 1)` carries no `rec` group at all, which is why
  `WasmLispCompilerTest.wasmContainsRecTypeGroup` asks the UNOPTIMIZED module.
- Pins: `WasmTreeShakerTest.dropsGlobalsNoSurvivorReadsAndRenumbersTheRest`,
  `keepsAnExportedGlobalAndTheOnesItsInitializerNames`.

### Host cell hooks: an export decided by who reads its cell
`WasmTreeShaker.HostCellHook(exportName, cellAddress)` drops an export that exists only so a HOST can
write one linear-memory cell, when no surviving function reads that cell. Claimants: the
`--no-wasi` setters `__ronto_seed_random` and `__ronto_set_time`; reasoning, fixpoint and pins in
`.kb/wasm-export-no-wasi.md`, "Both hooks are DROPPED from a module whose program cannot use them".

### Owned data segments
`WasmTreeShaker.OwnedDataSegment(segmentIndex, ownerFuncIndices)` names a segment whose bytes are
referenced exclusively by those functions, dropped when every owner is unreachable. **The shaker
cannot verify the exclusivity claim** (a linear-memory reference is an indistinguishable
`i32.const`), so it is the CALLER's invariant. Only claimants: the two Unicode case-fold range
tables (~16.4 KB, `WasmCaseFoldRuntimeBuilder`), owned by `_char_upcase`/`_char_downcase` -- owner
indices shifted by the injected host-import count, because the shake runs after
`WasmImportInjector`. Pinned by `orphanedCaseFoldTableSegmentsAreDropped`.

### String blob: droppable ranges
One segment holds the whole `StringTable` blob, so whole-segment ownership cannot express it.
`WasmTreeShaker.DroppableDataRange(segmentIndex, start, end)` cuts the range out and re-emits the
segment as one active segment per surviving run, **each at the address it already had**.

- **Candidacy is decided by OBSERVATION**: a range survives when any SURVIVING body or global
  initializer holds an `i32.const` in `[address, address + length)` -- **HALF-OPEN**, because no
  emitter produces a bare one-past-the-end pointer (the only computed pointer is
  `WasmLiteralPrint`'s `framed.offset() + 1`, interior) and a closed interval pinned every range
  whose end abutted a LIVE neighbour's start. An unrelated constant landing in a range only KEEPS
  bytes.
- **A closure's funcId is such a constant** (the `TYPE_CLOSURE` field is i32): on Preview 1 /
  `--no-wasi` the funcIds (~330 on a small program) fall inside the blob that starts at 256, so each
  live one keeps the string it lands in. Exact size equality across a funcId shift holds only under
  `--component`, whose data starts at 0x60000; the test
  (`WasmTreeShakerTest#aNameOnlyAFunctionNothingReachesSpellsHasNoCaseAndNoRow`) pins it there.
  **An i64 funcId does not pay**: no data section of the corpus shrank (a Worker's blob is nearly
  all live) and the code grew.
- **The COMPILER decides candidacy through a window**: `StringTable.attributing(true/false)`
  brackets passes 2a-2c, where a body's own `i32.const` is the only consumer;
  `StringTable.addBodyString` is the same grant spelled explicitly. **Interning with the window
  CLOSED retracts candidacy for good**, whichever came first -- which the instance-layout blob
  (built BEFORE Pass 2a), the `_lookup` rows, the `eval` special-form offsets and the reader's
  char-name / struct-directory tables rely on.
- **A baked packed-vector literal is a candidate too** (`StringTable.appendShakeableBlob`): a
  literal `(unsigned-byte 8|16|32)` table of 16+ elements, raw little-endian, never deduplicated.
  **The loop must take its base from an `i32.const`, not the load's memarg offset** -- the probe
  skips memargs, so a base hidden there would let the shaker cut a live table.
- **An OVERLAPPING entry is never a candidate** (`StringTable.addTailOf`): a cut range must own its
  bytes outright. Any future byte sharing owes the same rule.
- **TRAP: no generated string literal may spell a function name exactly.** `dispatchableFuncIds`
  reads a framed literal equal to a defun's name (or bare member name) as something `intern` could
  hand to `funcall`; shortening a dispatcher's `"No applicable method: X on "` literal to `"X"`
  grows the hello-clack Worker by ~11.5 KB, so it keeps the `" on "` separator
  (`LispMacroExpander.noApplicableMethod`).
- **A runtime table whose readers the compiler builds is decided by THOSE READERS, not observed**
  (`StringTable.appendReaderOwnedBlob`, `DroppableDataRange.readBy`): the Schubfach tables
  (`_schub_g`), the fdlibm reduction tables (the `addressesTables` functions), the operand-type
  operator table (`_type_err`) and the funcId -> name table (`_fun_name`), each kept while a
  registered reader (`StringTable.readBlob`) survives -- probed instead, a live constant equal to the
  base (chipz's `2048`) pinned dead tables. **Two obligations**: every body handed the base
  registers (for fdlibm,
  `WasmLispCompilerTest#everyFdlibmBodyThatCitesTheTablesIsOneTheShakerCountsAsTheirReader`), and
  `shakeCore` pins each reader against `WasmInliner`, which would otherwise move a small reader
  into its caller and leave the claim naming a function the shake kills. Pins:
  `anIntegerLiteralEqualToARuntimeTableAddressDoesNotKeepTheTable`,
  `WasmTreeShakerTest.aReaderDecidedRangeIgnoresAnUnrelatedConstantEqualToItsBase`.
- **The runtime intern table**: each candidate's `(offset, length)` row in `buildInternBlob` is a
  droppable range OF ITS OWN, probed on the STRING's interval (the five-argument
  `DroppableDataRange`; its only reader must tolerate cut bytes reading as zeros). `_intern` skips a
  row whose offset word is 0 (else a zero-length probe matches the first hole -- `'||` is a live
  zero-length entry); a candidate first interned AFTER the snapshot has no row, which is why `T` is
  interned before it.
- **`_fun_name` is the inverse shape**: a name's bytes are decided by the table's reader, but a
  deduplicated name is also a symbol a live body builds (`VECTOR`, `-`), so `ownCitationKeeps` keeps
  it while any body cites it, and `StringTable.addFunName` claims only a name no OTHER blob pinned
  (`WasmTreeShakerTest.aRangeProbedElsewhereStaysWhileItsOwnBytesAreStillCited`: `type-of`'s
  `VECTOR` printed as NULs).
- **The printer prologue is not exempt**: `StringTable`'s constructor interns 28 fixed entries plus
  `T`, each read by a RUNTIME body baking the offset as its own `i32.const`, so they go through
  `addBodyString` and stand or fall with those bodies.
- Pins: `dropsStringsOnlyDeadBodiesInterned`,
  `anInterningProgramOffersPerEntryRangesRowsFallingWithTheirBytes`,
  `dropsThePrinterPrologueNoLiveBodyReads`, `aBareOnePastTheEndPointerDoesNotKeepARange`, and
  behaviorally `optimizedProgramKeepsEveryStringALiveBodyStillAddresses` +
  `optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo` -- differential, because a wrongly-cut
  range prints garbage rather than trapping.

### The print family's literal fold
An output built-in whose argument is a LITERAL does not call the generic printer: the text is a
compile-time constant (every printer-control variable that could change it is inert,
`.kb/pretty-printer.md`), so `WasmLiteralPrint` interns the pre-rendered form and writes it through
`FUNC_WRITE_STR`, keeping the `*standard-output*` redirect semantics. The point is reachability:
the generic printer's integer arm alone pins the whole bignum print chain, the f64 renderer and the
ratio accessors.

**It is the FAMILY, not `print`.** `print`/`prin1`/`princ` are one emitter (`WasmPrintCompiler`)
precisely so the fold cannot exist for one spelling and not the others; `write-string`/`write-line`
fold through the same helper. Floats select the same Schubfach shortest decimal as
`LispDouble.print()` (`FloatText`). A COMPUTED argument reaches this fold when the computation is
itself constant (`.kb/pure-builtin-fold.md`). Pins: `everySpellingOfHelloWorldReachesTheSameFloor`
(< 1 KB), its `…ComponentFloor` twin (< 2 KB), `aFoldedLiteralPrintsWhatTheRuntimePrinterWouldHave`.

### The print family's static-TYPE shortcut
In `WasmPrintCompiler`, above the literal fold: **`princ` of a certainly-STRING form compiles as
`write-string`** (`compiler/StringValuedForms.certainlyString`), and **`princ`/`prin1`/`print` of a
certainly-DOUBLE form unboxes the `TYPE_FLOAT` struct and calls `_print_f64_no_nl`**
(`compiler/DoubleValuedForms.certainlyDouble`; only for the hard-coded standard output).
**The two predicates are the whole risk surface** -- a form wrongly admitted prints as the wrong
type or traps: `(princ (+ 3d0 #c(1d0 2d0)))` trapped on the `ref.cast` to `TYPE_FLOAT` while the
complex scan shared one pass with the double scan (it is a pass of its own now, ahead of it). A new
entry is earned by checking every backend's emission for that operator, and a new disqualifying
TYPE by checking it over every operand. Pinned by
`staticallyTypedPrintArgumentsPrintWhatTheValueDispatchWouldHave`, ci-spec
`statically-typed-print-arguments`.

### `%string-concat` byte-copies instead of rendering through the value printer
`%string-concat` (what `format`, `concatenate 'string` and every `(setf (aref s i) c)` spelling
lower through) is a byte copy (`WasmStringRuntimeBuilder.buildStringConcatBody`), not a capture-mode
render, so one immutable-string rebuild site does not drag in the value-printer family. Its operands
are strings by contract -- the interpreter throws and the JVM `CHECKCAST`s otherwise; on wasm a
non-string traps on the cast. `%schar-set-runtime` passes a one-element character vector, not
`(string c)`, for the same reason. It pays only where concat was the only printer edge (a print-free
concat-only module halves; `zlib` does not move). Pinned by
`LispEvaluatorTest#internalStringConcatCopiesBytes` and ci-spec
`internal-string-concat-copies-bytes`.

### The `name` section is DROPPED, not copied
It maps **function and type indices** to names and this pass has renumbered both. Dropped in
`WasmTreeShaker` (`SEC_CUSTOM`); every other custom section is index-free and still copied.
Pinned by `dropsTheNameSectionRenumberingHasInvalidated`.

### Identical bodies are emitted once
`am.ik.wasm.WasmBodyFolder` runs as the tail of `WasmTreeShaker.shakeWithRemap` at every
`eliminatesDeadCode()` level: functions with canonically-equal types and byte-identical code
entries collapse to one body, every reference redirected, to a fixpoint. "Canonically equal" = same
index, or same position in byte-identical `rec`-group entries NEITHER of which references its own
members (inside a self-referential group byte equality proves nothing). Matters only on `--no-gc`.

**Why folding is sound**: nothing observes a function's identity through its code index. A
first-class function value is a closure STRUCT whose dispatch id is plain `i32` data, so two folded
definitions keep distinct funcIds, `_lookup` rows and ladder arms; `eq` on WASM has NO closure arm
(`WasmEmitHelper.emitEqComparison`), so `(eq #'f #'g)` is NIL either way -- ci-spec
`identical-function-bodies-keep-distinct-identity` (`(eq #'f #'f)` is T on every backend through
the canonical per-defun box, so only DISTINCT definitions are pinned). `ref.func` values have no
comparator, and the component wrapper reaches core functions by export NAME only.

Identical bodies compress well, so on small clack Workers the RAW win comes with a few hundred
bytes MORE gzip. Pins: `WasmBodyFolderTest`; `-Drontolisp.wasm.debug-func-sizes` labels a folded
group by its survivor. **The JVM twin is measured, not implemented**: JVM methods are reachable BY
NAME, so the survivor set needs its own soundness argument.

### The adjacent-instruction peepholes
`am.ik.wasm.WasmPeephole.rewrite` runs between `WasmCallForwarding.redirect` and
`WasmInliner.inline` on BOTH wasm backends (`WasmLispCompiler.shakeCore`;
`NoGcWasmCompiler.compile`), at every `eliminatesDeadCode()` level. Every rewrite is decided by two
or three ADJACENT instructions in one block -- no dataflow, no renumbering -- and all DELETE bytes,
so raw and gzip move together (`size-measurement.md`).

The rules are listed in `WasmPeephole`. What the source does not say:

- The `if (result T) call F else ref.null end; ref.is_null` -> `i32.eqz` rule needs the caller to
  vouch for `F` as a pure non-null producer (`WasmLispCompiler.peepholePureNonNullCalls(importShift)`,
  shifted by the host-import count like the case-fold owner claims; `_t_sym`, plus `_str_build` and
  `_str_lit` for `call F; drop`). The `call` there is the lazily built `t` symbol of a predicate
  boxed into t/nil (`WasmEmitHelper.emitBoolFromI32`), not an error signal.
- `i32.eqz; i32.eqz` is removed only in front of an `if` or `br_if` -- an `i32.and` would see the
  value.
- `call F` of a function whose whole body is `i32.const K` is a SECOND pass (the fold's debris hides
  the constant). The case is `_eql_tail` in a module with no boxed number or character
  (`.kb/eq-numbers.md`): **a never-taken call still costs the caller ~8-10% in a tight loop**
  (wasmtime), which the byte count does not show.
- `br 0; end; unreachable; end` -> the `unreachable` goes. **Two conditions, and the rule is
  unsound without either.** The `end` must close a LOOP whose last instruction is that `br 0` (over
  a plain `block` the same `br 0` LEAVES the block and lands on the trap). And the loop must be the
  whole of an enclosing block that takes and leaves nothing -- otherwise the trap is what makes the
  module validate. That narrow form covers 185 of 185 sites on `zlib` and 1,481 of 1,488 on the
  Worker. **Every such site is the type-test fold's own**: `WasmRefTypeFolder`'s `stepEnd` writes the
  trap whenever a block's end is never reached, since it cannot look ahead; the two disagree by
  design -- a second `fold` writes the trap back
  (`WasmRefTypeFolderTest.theIntegerOnlyReactorLosesTheFloatAndRationalTiers` states the fixpoint
  on the fold's own output for this reason).

Pins: `WasmPeepholeTest` (one hand-assembled body per shape, including the two the loop-tail rule
must refuse and the box the pass must keep when nobody vouches for the call), and
`WasmTreeShakerCorpusTest`, which runs the pass over the whole `ci-spec` corpus in the compile
path's position with the same pure-call predicate -- it must shrink the module, and what it leaves
must validate and re-encode to itself.

### A test is compiled as a test, not as a value

`WasmConditionCompiler.compile(test, ctx, negated)` is what `if`, `while` and a value-position
`not`/`null` hand their test to: a raw i32, non-0 exactly when the test is true (false when
`negated`, which an `if` -- whose wasm THEN arm is the Lisp else arm -- and a loop's exit `br_if`
want). Predicates, `eq`/`eql`, numeric comparisons (`WasmComparisonCompiler.tryCompileConditionI32`)
and `and`/`or` chains never materialise a box; `WasmIfCompiler` selects the arm of a constant test
at compile time, and `LispMacroExpander.expandCondClauses` folds a `cond`'s `(t ...)` clause and
`expandAnd` emits nested `if`s, for every backend. Anything else is a value tested with one
`ref.is_null`, and the peephole above takes the predicates this compiler does not name. Pinned by
`WasmLispCompilerIntegrationTest#compileAndRunTestsCompiledAsTests` (the same program on the JVM
and the interpreter) and the corpus guard.

**Answering a `call` at the call site does NOT pay** (identity / constant callees in
`WasmCallForwarding`): the matching functions are already UNREFERENCED when the pass runs, so the
shaker collects them anyway, and the population is a fixed set of runtime helpers.

### The local renumbering

`am.ik.wasm.WasmLocalOrder.reorder` runs LAST on both wasm backends, over the shaken module. Past
128 locals every `local.*` immediate costs two bytes, and `Ctx.allocTemp` is `nextLocal++` (never
recycled). The pass gives the one-byte indices to the most-used locals (ties keep declaration
order), groups the hot and the cold set by type so the declaration vector stays a few runs, keeps
every parameter in place, and asks no liveness. A frame that fits in one byte is left
byte-identical. It is a permutation, so gzip follows raw (a percent or two on the real programs).
Recycling temporaries in the emitter would shrink FRAMES, not bytes, and was not built. Pins:
`WasmLocalOrderTest` (the hot local, the regrouped vector, the frame left alone) and
`WasmTreeShakerCorpusTest` (must not grow, must validate and round-trip over the corpus).

### Sparse arity ladders

`WasmRuntimeBuilder.emitCaseSelector`: the selector over a dispatcher's case blocks is one of
three shapes, chosen by exact byte count -- a `br_table` over `[0, max]`, the same table BIASED to
the smallest live id (a smaller id wraps to a huge unsigned index, which is the default), or a
comparison chain (`i64.const id; i64.eq; br_if depth` per live id, `br default` after) when the
chain is under HALF the table. A tie keeps the plainer shape, so a dense ladder is byte-identical.
Under `--optimize` a ladder carries only the callables taken as VALUES (the funcall-dispatch gate),
so a library program's ladders are sparse by nature. Two rules, each a measurement
(`size-measurement.md`):

- **The half rule is the raw/gzip trade.** A chain's bytes are incompressible (a distinct id per
  case) where a table's holes are a run of one byte gzip folds away: "chain whenever shorter" gave
  a raw win with a gzip LOSS.
- **The ids are i64 constants, never i32.** The tree shaker keeps any string-blob range a surviving
  `i32.const` lands in ("String blob: droppable ranges"), and funcIds live where the small literals
  do: spelled as `i32.const`, the chains pinned a large slice of `zlib`'s string blob. An i64 is
  never an address: the bias is `i64.extend_i32_u; i64.const min; i64.sub; i32.wrap_i64`.

The paged dispatcher's leaf pages use the same selector. `wasm-function-body-size.md`'s early-out
("the flat shape is not built when its label count alone settles the gate") is conservative rather
than exact now, and stays, because paging is always correct and the gate is about the body bound,
not bytes. Pinned by `WasmDispatchPagingTest#aSparseLadderSelectsByComparisonAndADenseOneByTable`
and `WasmLispCompilerIntegrationTest#aSparseArityLadderDispatchesTheSame`.

### A char comparison pair is one code-point compare

`WasmCharCompiler.compileChain` compiles a two-operand `char=`/`char<`/`char<=` as
`pushCode a; pushCode b; i32.eq` -- a literal's code point the `i32.const` it is -- boxed once in
value position and not at all as a test; the n-ary chain (eqref temps, boxed code points) keeps its
shape for three or more operands. Pinned by
`WasmLispCompilerIntegrationTest#aCharComparisonPairIsOneCodePointCompare`. An EH-mode non-literal
operand is `i32.const id; call _chr_code` rather than the trapping cast
([error-handling.md](error-handling.md), "One argument is still checked").

### A conditional in statement position compiles for effect

`WasmExprCompiler.compileForEffect` hands an `if` (and through their expansions
`when`/`unless`/`cond`/`and`/`or`) to `WasmIfCompiler.compileForEffect`: a void wasm `if` whose arms
compile for effect, no `drop` after; an arm that is nil or a literal is no arm, and no `else` is
written. Value position is untouched, and state-machine mode does not reach it (its `if` routes
resumes through its arms). Pinned by
`WasmLispCompilerIntegrationTest#aConditionalInStatementPositionCompilesForEffect`.

### The single-call-site move
`am.ik.wasm.WasmInliner.inline` runs between `WasmPeephole.rewrite` and `WasmTreeShaker.shake` on
BOTH wasm backends, at every `eliminatesDeadCode()` level. A defined function `call`ed from exactly
one place has its body moved to that call site and is left UNREFERENCED for the shake. Nothing is
renumbered here, so an `OwnedDataSegment` claim or a `-Drontolisp.wasm.debug-func-sizes` name map
still reads in the module's own indices. Declined: a callee PINNED by the backend
(`WasmLispCompiler` pins the case-fold owners: their segment claim names them by index); a callee
holding a `try_table` (moved, its catch edge would carry every caller local live across the call
site, which its pad does not refresh, `.kb/wasm-landing-pad-refresh.md`); a module with a table or
element section.

**The move alone is a LOSS** -- `wasm-opt --inlining` leaves `zlib` BIGGER, because handed-over
arguments become `local.set`/`local.get` pairs and every local index past 127 costs a second byte.
So the pass is the move plus its arithmetic, all load-bearing: stack hand-over (a callee reading each
parameter once, in order, first, needs no locals), argument substitution of re-materializable
pushes, and **a measured decision** -- the rewritten caller is encoded and abandoned unless strictly
smaller than caller plus callee, so the pass cannot grow the code or function section
(`WasmTreeShakerCorpusTest` asserts it over the corpus, both WASI modes).

- **The wrapper block a `return` needs must declare the handed-over values**:
  `block (type <the callee's own type index>)`, because values pushed before a block are unreachable
  inside one that declares no parameters. A bare `block (result t)` decodes and is invalid -- the bug
  wasm-tools caught that structure assertions could not.
- **Tail calls** (`.kb/wasm-tail-calls.md`): a `return_call X` inside a moved body is
  `call X; br <wrapper>`, except a TRAILING one, a bare `call X` -- the wrapper block would cost
  exactly what the move saves.

**Two things the measured decision alone does not see, both measured on `zlib`.**

1. **A body the duplicate fold would reclaim anyway is worth nothing here, and costs the caller its
   whole size.** A candidate whose (canonical type, code bytes) pair another defined function
   repeats is declined. A CALLER never has a twin (a byte-identical body repeats the same `call`
   immediate, making the callee's call-site count two), so only the callee side needs the test.
2. **Relocating bytes costs REPETITION, which is what the artifact's compressor lives on.** A move
   reclaims only per-function overhead (size prefix, locals byte, `end`, the `call`, a
   function-section entry: 6 to 12 bytes), never a function of body size. So
   `WasmInliner.MAX_MOVED_BODY` caps the moved code entry at **64 bytes**, a measurement: swept over
   the `size-report` corpus, the Cloudflare Worker family and the `--no-gc` browser reactor, the raw
   win grows with the cap, but past 64 bytes the compressed total turns into a LOSS on the GC-heavy
   artifacts, and a Worker's platform limit counts COMPRESSED bytes
   (`size-report/notes/cloudflare-workers.md`). 64 is the smallest budget at which the host-facing
   `--no-gc` modules reach their FULL win.

**The prize is the small host-facing module, not the library** (on the GC backend's library
programs the win is a rounding error by design; binaryen recovers more on `zlib` only by
re-encoding locals across the whole function afterwards).

### The single-use local
`am.ik.wasm.WasmLocalSink.sink` runs behind `WasmInliner.inline` and in front of the shake on BOTH
wasm backends, at every `eliminatesDeadCode()` level. A local written ONCE, by a pure expression,
and read ONCE, at a point the write dominates, is removed: the expression moves to the read, and
every local above it moves down an index. One decode pays for three things:

It also deletes dead writes and untouched locals, and writes an adjacent `set N; get N` as `tee N`;
the real programs' frames shed a third or more of their locals BEFORE `WasmLocalOrder` sees them.
It renumbers only a function's own locals, so every index-addressed claim the shake reads holds.

- **An allocation sinks only while it is still evaluated ONCE**: never as a copy, and never into a
  loop the write is outside of, where a closure built once would be rebuilt, cell and all, every
  iteration. The inputs' unchanged-range extends to the end of the outermost `loop` opened after the
  write and still open at the read; the write must dominate the read.
- A `tee` sinks a COPY, only when the copy is shorter than the `tee` plus the `get`.
- **A round is linear in the body, and a chain is one round**: a moved expression reading a local
  moved this round NESTS at the read, its legality facts carried up the nesting, its length held to
  `MAX_WALK` (32), which bounds the operand stack. One-link-per-round made a backquote template of N
  elements (the inliner's cons hand-over chain) quadratic.

**Only the bodies the shake will keep are sunk** (`sink(module, true)`): a body no export or start
function reaches through `call` edges (`WasmTreeShaker.reachableBodies`, a superset of the shake's
roots) is copied as is -- output-identical because the sink never adds a call, and it was nearly
all the pass's time. The peepholes and the move cannot skip dead bodies: the move counts call sites
and looks for byte-identical twins in EVERY body.

Pins: `WasmLocalSinkTest` (the unreachable body left alone, the sink, the gap, the renumbering, the
loop-carried input, the undominated read, the global across a call, the tee copy, the allocation
that is never copied nor sunk into a loop, the dead writes, the chained round, the adjacent pair, a
2,000-link hand-over chain collected in ONE round), `WideListStackTest`'s backquote leg on `.wasm`
(50,000 links), `WasmTreeShakerCorpusTest` (the whole `ci-spec` corpus at every level validates and
round-trips) and `WasmLispCompilerIntegrationTest.mapcanMapconLongInput` /
`sequenceBoundingKeywords` (a closure's cell mutated across iterations -- the two that caught the
allocation rule).

### The component WRAPPER: adapter + WASI surface
The adapter and the `wasi:*` declarations follow the core through one chain, every step *observed*
rather than declared (`WasmComponentBuilder.fixedSurface`, base variant only): the core's surviving
`wasi_snapshot_preview1` imports become exactly the adapter's exports, the adapter is shaken
**including its own `"w"` imports**, the surviving `"w"` names select their entries in `W_MEMBERS`,
and `ComponentImportBlock.prune` cuts the import blob to the members those name -- functions and
types inside an interface, since 2026-11-25 (`.kb/wasi-component.md`, "The prune drops members").

- **The projection closure is transitive, and one WIT `use` can widen a program's world**:
  `wasi:filesystem/types` `use`s `wasi:clocks/system-clock`'s `instant` for `descriptor-stat` and
  `new-timestamp`, so a program that asks for a file's date keeps `wasi:clocks/system-clock`,
  exporting `instant` alone. **Do not read an interface's presence as evidence that the program
  calls it** (`anOptimizedComponentThatOpensAFileKeepsTheFilesystemSurface`).
- **Nothing downstream may hold a fixed index.** The block's instance indices, the first free
  component type and the shifts all come back from the prune -- a stale constant yields a component
  that *validates* while binding the wrong interface, which is why `ComponentImportBlock.Pruned`
  returns the maps, not the bytes alone.
- **The filesystem is droppable only because the adapter is split**: `fd_write` dispatches on a
  runtime fd, so `adapter.wat` factors the fd-polymorphic shims into
  `$fd_write_stdio`/`$fd_write_file` and `$fd_read_stdin`/`$fd_read_file`. **`path_open` is the only
  writer of the adapter's fd table**, so a core that does not import it can never present a file fd:
  the wrapper then retains `fd_write_stdio` UNDER THE NAME `fd_write` (`WasmExports.retain` renames)
  -- the one place the component reads an adapter export whose name differs from `adapter.wat`'s.
- **A narrow half must be no more PERMISSIVE than the wide one.** `$fd_write_stdio` answers fd 1
  and 2 and TRAPS otherwise; `$fd_read_stdin` traps on any fd but 0. A SOCKET fd (>= 200) reaches
  `fd_write` whenever a write form escapes `WasmSocketsRewrite`'s dispatch table (`format` is one);
  a guard-less narrow `fd_write` would write those bytes to STDERR and return success
  (`anOptimizedComponentFailsAsLoudlyAsAPlainOneOnAnFdItCannotServe`). **Rule for any future
  narrow/wide pair: the narrow one rejects what it does not implement.**
- **The blob grammar is decoded, not pattern-matched**: `ComponentImportBlock` throws on any byte it
  cannot classify. `ComponentImportBlockTest` checks two prunes byte-for-byte against `wasm-tools`
  output (an interface cut, a member cut) and runs the 8,191 sets of `wasi:filesystem/types`'
  functions back through the parser.
- **`--emit-wit` moves with it** (`WasmComponentBuilder.wasiMembers` is the same map); package
  order follows the world's first references, as `wasm-tools component wit` prints them.
- **Serve is deliberately NOT pruned** (`WasmServeComponentBuilder` keeps its fixed block constants
  and embeds the preview1 bridge whole).

**The last fixed costs.** **`wasi:cli/stderr`, 185 B**: fd 2 is the RESERVED `*error-output*`
handle (`StreamDesignators.STANDARD_ERROR_HANDLE` alone), so "can this program present fd 2" is a
question about the SOURCE -- `WasmLispCompiler` answers it (`programUsesSymbol` over
`*ERROR-OUTPUT*` / `WARN` / `%WARN`, plus `--dynamic`) and hands it over as
`WasmComponentBuilder.Narrowing`; a third `fd_write`, `$fd_write_stdout` (fd 1 only), serves the
rest, and a program with `path_open` keeps the WIDE one and therefore stderr. *This depends on a
list being complete* (`.kb/standard-output-redirect.md`), and **a producer the compiler INJECTS
cannot join a scan of the user's text** -- the EH-mode landing pad (`WasmUncaughtReportCompiler`)
contributes its own emission fact (`emittedFor(ehMode)`, OR-ed into `reachesStandardError`), or
`--component --optimize` prunes the interface out from under the uncaught-condition report. Pinned
by `anOptimizedComponentWithAnUncaughtReportLandingPadKeepsTheStderrSurface`,
`anOptimizedComponentStillReportsAnUncaughtConditionOfItsEntryAndOfAnExport`.
**The shared `cabi_realloc`, 142 B**, is kept only when a canonical option references it
(`WMember.realloc()` is set only by the `lowerRealloc`/`builtinRealloc` factories, so it cannot
drift). **The floor**: the synchronous
`stream.write`/`future.read` built-ins sit behind `component-model-more-async-builtins`, not
default-on, while **rontolisp's contract is that a component runs with ZERO flags** -- so the
adapter's stream/future/waitable trio (~279 B) stays; when that gate opens, drop it from
`adapter.wat` and the async keyword from those two canons. The `"w"` field names (~232 B) are
deliberately left long.

**Decoder correctness** rests on the backend emitting (a) no `call_indirect`/element segments --
first-class calls go through dispatch functions with direct `call` -- and (b) a finite, enumerated
opcode set (`0xFB` GC ops, `0xFD` SIMD via `skipSimd`, `0xFC` saturating truncations, and
`block (result …)` blocktypes including the ONE-BYTE `eqref` spelling,
`.kb/wasm-shortest-encoding.md`). An unknown opcode throws rather than emit a corrupt module.

**Why renumbering the core is invisible to the wrapper**: every linkage is by NAME. The wrapper
reaches in only through `alias core func (instance N) "name"` (`ComponentWriter.aliasCoreFunc`), and
everything it names is a core EXPORT, hence already a shaker root; the core's imports are satisfied
`from-exports`, so a dropped import leaves one unused name in the map. The memory import is kept
verbatim with every other non-function import.

## What an external optimizer still finds, and what it is made of

**Measure what the emitter emits, not what an optimizer can recover from it** -- one of three ways
"smaller" splits into numbers that do not move together ([size-measurement.md](size-measurement.md)
names the other two). A binaryen residue is context, never a target, in both directions: a lowering
change (the `car`/`cdr` one, hello-clack 916,587 -> 815,414 bytes) walks straight past it -- binaryen
found the SAME residue before and after -- so a residue is not a ceiling; and a residue near zero
is not "nothing left to win" -- a reactor within bytes of `-Oz` still carried a literal string
walked out of linear memory into a GC array and back, which no optimizer sees as a round trip, and
removing it at the emitter ([[wasm-import]] "A literal `:string` argument does not round-trip")
made it smaller with no optimizer than `-Oz` had reached. The residue tables say WHERE to look,
nothing more; the census names a site, and the shape is only known once the site is read.

`wasm-opt` (binaryen 130/132) is a PROBE over the shaken output, never a build step (the core
libraries take no external dependency). Two traps when reading it:

- **Binaryen's writer is not rontolisp's**: the module re-encoded with NO passes grows by kilobytes
  on the large modules, so every single-pass number must be corrected for that tax.
- **`inlining-optimizing` is not the inliner**: it re-runs the whole function pipeline over nearly
  every function, so alone it dominates the residue. With `-Oz --skip-pass=inlining
  --skip-pass=inlining-optimizing` the residue is largely intact on the large modules; only on the
  small ones is inlining the enabler (of `heap2local`).

The residue is local plumbing and lowering shapes, not functions (`coalesce-locals`,
`simplify-locals-nostructure`, `merge-similar-functions`, `local-cse` dominate). The census shapes are the
sections above plus `.kb/cons-access-runtime.md` and `.kb/lambda-lists.md`. "A boxed variable built
empty then `struct.set`" is not a shape: those are closures with a null environment,
`(cons x nil)` and the `labels` case that needs the cell first.

## The funcall-dispatch gate (what makes `--optimize` reach library code)
**A function gets an arity-dispatch case only when the program can actually reach it as a function
VALUE (source 1) or through a NAME a run-time designator can carry (source 2), and a `_lookup`
registry row only in the second case.** Without this the shakers are nearly inert on any program
that loads a library: the ladders `call` every registered function. The dispatch set
(`valueFuncIds` plus the rows) and the rows (`Wasm/JvmLispCompiler.registryFuncIds`) are **computed
together so they cannot drift**: a row whose funcId had no case would resolve and then fall through
to the ladder's default arm.

**Source 1, `Ctx.valueFuncIds`** -- the funcIds Pass 2 actually materialized as a closure. Collected
DURING emission, not from a pre-scan: a `#'identity` a macro synthesizes in Pass 2 is invisible to
any source scan. A context that does not inherit the field loses every closure it makes;
`Ctx.builder(proto)` inherits every field (`CtxBuilderSeedTest`,
[wasm-function-body-size.md](wasm-function-body-size.md), "A chunk context inherits every
module-wide field by construction").

**Source 2, the names a runtime SYMBOL designator can resolve** -- the registry's rows; a function
VALUE carries its funcId to the ladder and never passes `_lookup` ("A registry row is a name, not a
value" below). `_lookup` matches interned offsets (WASM) / string constants (JVM), so a row is
reachable only when the program already put that exact name there for another reason. The probe set
is **`Ctx.spelledLiterals`** -- every spelling Pass 2 emits as a runtime VALUE
(`Wasm/JvmEmitHelper.compileStringLiteral`), not the whole string table / constant pool, which
would arm rows for slot names and layout tables.

- **Seven spellings** are tried: canonical; the `::`->`:` alias row's; the bare member name after
  the last colon; the FRAMED string-literal spelling of the full name and of the member (`"NAME"`,
  quotes included -- clack's handler discovery spells `"RUN"`, not `RUN`); and the two package-less
  SYMBOL spellings whose name is the member, `:member` and `#:member`. **The seven live in
  `compiler.DesignatorSpellings`**, not once per backend -- a spelling added to one and not the
  other is precisely the "resolves on the JVM, not on WASM" divergence. `of(name, symbolBuilders)`
  is the probe order, `anySpelled` the decision, `matched` the report (`DesignatorSpellingsTest`).
- **A literal the compiler SYNTHESIZES as pure result data is exempted through `%unspelled-quote`**
  (`LispNames.UNSPELLED_QUOTE`): compiles exactly like `quote` but records nothing (grep it for the
  emitters; a user-written `slot-value`'s name keeps the plain quote). This is the symbol half of
  **no generated literal may spell a defun name exactly**. Every walker that skips
  quoted data must skip it too (`LispNames.isQuote`, or `case QUOTE, UNSPELLED_QUOTE`): read as a
  call, its datum is a variable reference, and inside a lambda `FreeVarAnalyzer` fails with
  `Cannot find variable for closure`. Consequence: `(funcall (cell-error-name e) ...)` /
  `(funcall (type-of x) ...)` stop resolving like any forged name, loudly; `--dynamic` restores them.
- **A package walk's spellings count as spelled** (`RuntimeNameProducers.packageWalkSpellings`):
  the baked package table carries each universe as one packed string that `%split-packed` decodes
  and `intern`s at run time (`do-symbols`, `apropos-list`, ...), so a program defining that decoder
  holds every packed spelling and its member name; otherwise a walk symbol naming a defun nothing
  else spelled is undefined on the compiled backends only. `mayReachTrig` reads the same set. Pin:
  `PackageWalkDesignatorFixture` (interpreter, JVM, P1, component).
- **A gensym's prefix is no spelling**: neither backend emits the `"#:G"` prefix through
  `compileStringLiteral` (with a builder present the `#:member` probe would arm any defun named `G`).
- **The four widened spellings apply only while the program contains a symbol BUILDER at all** --
  `RuntimeNameProducers.anySymbolBuilder`: `intern`, `find-symbol`, `make-symbol`,
  `uiop:symbol-call`. `make-symbol` is the safe over-approximation (its product can never match a
  row on WASM, but the JVM registry compares string VALUES). Two of the compiler's own emissions
  are shape-exempt, provably unable to produce a FUNCTION designator: `(intern X :keyword)` and the
  injected `(defun %slot-name-key (n) (intern (symbol-name n)))`.
- Pins: `widenedProbesApplyOnlyWithASymbolBuilderPresent`,
  `theCompilersOwnInternShapesDoNotWidenTheProbes`, `aFramedSpellingWithoutABuilderDoesNotHoldARow`,
  `aCompilerInternedTableNameDoesNotArmTheDispatchGate`,
  `aGeneratedReaderBodySlotNameDoesNotArmTheDispatchGate` and their `JvmDeadMethodEliminationTest` twins.
  Debug: `-Drontolisp.debug.dispatchgate=true` prints `name-armed <defun> by <spelling>` and names
  the operator that turned the gate off.

**The gate turns itself off entirely** under `--dynamic` and whenever
`compiler/RuntimeNameProducers.anyNameResolvable` holds -- the program contains a DATA EVALUATOR
(`eval`/`read`/`read-from-string`/`load`, or the injected `~/name/` renderer arm
`FormatRenderer.FUNCTION_DESIGNATOR`). That class is shared by both backends on purpose. **Without
the data-evaluator bail the gate is not sound, and the failure is a trap rather than a diagnosis**
-- the clack path this exists for (`clackup` -> `find-handler` -> `find-package-or-load` ->
`(find-symbol "RUN" pkg)` -> `apply`) resolves through the framed-string probe.

**The symbol BUILDERS do not bail.** Sound against the probes: a symbol a builder produces is built
FROM A STRING, and any string the program holds is a compile-time constant the widened probes
already read. What escapes them is a name assembled out of COMPUTED pieces -- `LibraryDefunPruner`'s
carve-out (the ordinary undefined-function error; `--dynamic` restores late binding). Judging the
`intern` ARGUMENT shape instead was REJECTED: it shrank nothing and broke ci-spec
`intern-into-a-literal-package` (the two-argument lowering folds the literal into the qualified
symbol before either probe sees it). Tests:
`internDoesNotHoldTheFuncallDispatchGateOpen`, `internDoesNotHoldTheDispatchGateOpen`,
`keywordInternStaysInternedInAGateShakenModule`, `componentCoreIsTreeShakenUnderOptimize`,
`optimizedServeComponentStillServesUnderWasmtimeServe`,
`FormatRendererTest.theFunctionDesignatorArmIsInjectedOnlyForAProgramThatSpellsTheDirective`.

### A designator the compiler can READ never enters `valueFuncIds`
`Wasm/JvmDesignatorCall` is the one decision (a literal `#'name` / `'name` the backend's registry
answers at the arity in hand), asked by `funcall`, `mapcar`, `mapc`, `mapcan`, `reduce` and `sort`
on BOTH backends. A resolved site emits the direct call; the funcId is never materialized, so it
joins neither `valueFuncIds` nor the ladder.

**Deliberately NOT resolved**, all keeping the dispatcher: a computed designator; a name no registry
answers (a car/cdr composition, a `--dynamic` deferral); and **an arity the callee cannot take** --
the one not to "fix", because the arity contract of these operators is a RUN-time one, so
`(mapcar #'cons '(1 2))` must fail where it failed before (a WASM trap, the ladder's default arm on
the JVM) rather than becoming a compile error. On WASM the resolution is asked BEFORE the dispatch
ceiling check (`WasmFunctionCallCompiler.compileFuncall`), because a ceiling on the dispatchers
cannot bind a call that uses none. Lisp-2 shadowing needs no handling: `flet`/`labels` rewrite both
`(f x)` and `#'f` into their binding VARIABLE first (`.kb/flet-labels.md`).

Cost: a variadic callee reached wider than its required count links the rest list AT the call site,
so the no-flag JVM class grows slightly while `--optimize` pays back; dropping a value is worth
about ten times more on the JVM than on wasm.

**A gate test's scaffolding is affected, and silently.** `(print (funcall 'f))` used purely to keep
a ladder emitted is a direct call, so such probes must funcall a COMPUTED designator
(`(funcall (car (list #'f)))`). **Any future test about what the ladders keep alive owes the same
care.** Pins: `aLiteralDesignatorSiteBuysNoLadderCase` / `aLiteralDesignatorSiteBuysNoDispatchCase`,
`literalFunctionDesignatorsCompileAndRun` / `compileAndRunLiteralFunctionDesignators`,
`compileAndRunLiteralDesignatorOfTheWrongArityKeepsTheDispatcher`, ci-spec
`literal-function-designators-answer-like-computed-ones`.

### A designator BOUND to a temp is not a value either
Every expander that NAMES a designator to avoid re-evaluating it would undo the section above
(`expandMap`'s `(let ((__map_fn #'identity)) ...)`, reached by every `coerce` lowering;
`expandMapFamily`; `expandEverySomeFamily`). `compiler.LetBoundDesignators.propagate` closes it in
ONE place: **a `let` binding whose init is a literal designator naming a registered function, and
whose every use in the body is a function-designator position, is propagated into those uses and
the binding dropped.** `Jvm/WasmLetCompiler` call it on the way in. In the backends, not the
expanders, to keep the interpreter out of it: a literal AT the funcall site would evaluate the
designator once per element there, and a designator naming an UNDEFINED function would stop
signalling over an empty sequence.

**The safety argument is a COUNT, not a walker.** The pass certifies the occurrences it understands
(the designator argument of the six resolved operators) and separately counts EVERY occurrence of
the name with a shape-blind scan, rewriting only when the two agree. The certifying walk stays
opaque at data-carrying heads (`quote`, `declare`, the `def*` family, `case` keys, a lambda list):
CERTIFYING something non-evaluated would corrupt it. Further guards: a SPECIAL name is never
dropped; a name bound twice in one binding list is left alone; the designator must name a function
the registry answers (`ctx.functions`), which keeps `#'cadr` (a car/cdr composition synthesized per
site) out.

**Deliberately not listed**: `map`/`maplist`/`mapcon`/`mapl`/`every`/`some` bind the designator in
their own expansion, so only a literal reaching them through a HAND-WRITTEN variable stays outside.
Adding their slots to `designatorSlot` is the lever -- every slot there is a claim about an expander
that has to keep being true. Pins: `aDesignatorBoundToATempIsTheSameDirectCall` on both shaker tests
(byte-identical to the written-out literal's module), each paired with a VALUE use;
`LetBoundDesignatorsTest`; the ci-spec case's three shapes that KEEP the binding -- value use,
`setq`, shadowing.

### A ladder arm lives while a kept function makes its value
The WASM twin of the JVM section below. Every site that makes a function value goes through
`Ctx.noteFunctionValue`, which credits `WasmValueMakers` with the Pass 2 unit being compiled: a
defun (2a), `_start` (2b, its top-level chunks included), a lambda (2c). A body compiled while a
unit is open is reached only through code that unit emitted, so crediting the unit is never later
than the truth; a value noted outside every unit (import/export wrappers) is `UNATTRIBUTED` =
always made.

`WasmLispCompiler.narrowLadders` runs BEFORE `WasmImportInjector.inject` (a ladder rebuilt after it
would miss the renumbering), at every `eliminatesDeadCode()` level, over
`am.ik.wasm.WasmTreeShaker.valueReach`: a dispatcher's call to a valued target is followed only once
a reached function makes that value. A registry row's funcId is made by each function spelling its
name (next section), and by `FUNC_LOOKUP` where a run can produce any name; a dispatchable defun with
neither (`--dynamic` / `anyNameResolvable` without the registry) is always made. The ladders are
rebuilt over the made set (a slot left over gets an `unreachable` body the shake drops). The rebuild
may intern no string (the data section is already written; checked). `_fun_name` keeps the full
set, as on the JVM.

Pins: `WasmTreeShakerTest#aClosureWhoseEveryCreatorIsShakenTakesItsLadderArmAlong` (P1, component,
a host import), `#aValueCallIsFollowedOnlyOnceAReachedFunctionMakesTheValue`, the four ladder
programs in `WasmLispCompilerIntegrationTest#optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo`.

### A registry row lives while a kept function spells its name
`Ctx.spelledLiterals` is filled by every compiled body, dead ones included (a `defmacro` helper
defun that backquotes a name). `WasmValueMakers` credits each spelling to the unit emitting it, and
`narrowLadders` makes a row's funcId only from reached units holding one of its spellings
(`DesignatorSpellings.all`), or from `_lookup` where a run can produce any name. A spelling with no
unit (a package walk's) is always held. A dead row goes to `shakeCore` as a range no function reads;
`_lookup` is the stub when every row is. Under `!usesIntern` the row's name is a body string
(`addBodyString`): a row compares offsets and reads no bytes. With `_intern` the name stays pinned,
since `(intern "FOO")` finds `FOO` only through the table. **The JVM is not narrowed this way**: a forged name spelled only
in dead code resolves there and misses on WASM, both inside the documented carve-out, and a dead row
on the JVM costs class bytes no host pays for by the kilobyte.

Debug: `-Drontolisp.debug.dispatchgate=true` adds `made <value> by [<functions>]` per arm. Pins:
`WasmTreeShakerTest#aNameOnlyAFunctionNothingReachesSpellsHasNoCaseAndNoRow`, ci-spec
`what-a-kept-function-can-name-and-what-a-fold-may-assume`.

### What a `funcall` brought into a small module
One `funcall` of a function in a `defvar` once took a `--no-wasi --optimize=size` filter from 9 KB to
41 KB. Several roots held the same helpers, so each fix alone showed little. The roots:

- **A top-level `(function name)`** that `FunctionDesignators.withDataCalledReferences` appends to
  open a wrapper's gate (its trigger may be a false positive: `(ecase map (:request 0) ...)` reads
  as `(map <computed type> ...)`), evaluated by `_start`, made the value.
  `ToplevelStatements.pruneFunctionReferences` drops such a form once the gates have read it, on
  both backends (`toplevel-statement-values.md`).
- **Registry rows spelled only by dead bodies**: the previous section.
- **The generic sequence family** (`(nreverse acc)` dispatching on string/vector): decided by the
  type-test fold over a list the program built (`wasm-ref-type-fold.md`).
- **`_charvec_to_str` as an identity** handed `~a` every value any caller passes:
  `emitCharvecToStrLocal` tests `TYPE_CELL` first, so a printer keeps only the arms of what reaches it.
- **Reports nothing reads** (`--no-wasi`; `error-handling.md`, "A module with nowhere to report"):
  the not-a-function and arity reports a dispatcher builds, and a class's `:report` run at the
  signal by `%no-applicable-method` / `%slot-read`.
- **Writes nothing reads** (`WasmWriteOnlyGlobals`, below).

Left as they are:

- **Specializing `%pc-walk` on a constant `escape`** buys nothing: `~a` alone reaches no walk
  (`pretty-printer.md`), and a symbol and a framed string are one struct, so the fold cannot retire
  the symbol arm while a string reaches `%print-cased`.
- **A `print-object` route plus a print control** keeps both walks; a split would save under 2 KB.
- **`test_plugin` and `gateway`** are held by `%serialize-pairs`' `replace` over `(car pair)` and
  `~a` of what a cons holds: the fold's field sets are per type, so a cons of strings and a cons of
  anything read alike.
- **`(setf aref)` of a general vector** reaches `%SCHAR-SET-RUNTIME`: `_charvec_p` is no type
  question, so the fold cannot retire it.
- A second fold round behind the inliner: about 150 bytes, rejected.

### The write-only globals
`am.ik.wasm.WasmWriteOnlyGlobals.rewrite`, behind the fold and in front of the peepholes in
`shakeCore`. A global no reachable body reads: its writes become `drop`s, and an update
`global.get G; i32.const K; i32.add|sub; global.set G` goes whole. The shake then drops the global.
Exported and imported globals stay. A `(defvar *x* (f))` keeps its call. A start function it leaves
empty goes with its section (`WasmTreeShaker.dropUnreachable`), e.g. `_lit_init` when every literal
it built was a dead body's (`.kb/wasm-gc-strings.md`, "A literal is one object"). Two peepholes
behind it: `call <pure>; drop` goes for what `peepholePureNonNullCalls` vouches for (a `drop` per
parameter in its place), and `i32.const K; ref.i31; ref.cast (ref i31); i31.get_s` is `i32.const K`
where K fits 31 bits. Pins: `WasmWriteOnlyGlobalsTest`,
`WasmPeepholeTest#foldsALiteralBoxedForAParameterThatUnboxesIt`.

## cl-ppcre, decided
Adding tiny-routes to a Clack reactor nearly triples the module, and the extra is **cl-ppcre**: a
route template is compiled to a scanner at RUN time (`path-template.lisp` even builds one at LOAD
time, `*path-token-scanner*`), so the regex pipeline is genuinely reachable. **Quickloading cl-ppcre
alone puts the module in EH mode, and scanner building is live even for a single literal `scan`** --
the engine IS the anchor, so the shaking levers cannot pay.

1. **TAKEN -- the Worker examples build at `--optimize=size`** (the wasm-GC `build.sh` lines;
   `hello` stays `--optimize` because it is `--no-gc`, where the level is a no-op).
2. cl-ppcre's eight `define-compiler-macro`s never fire on the routing path, and firing would not
   shrink anything -- the scanner BUILDER still ships (`.kb/compiler-macros.md`).
3. A leaf-module substitution of `path-template.lisp` (`ShimLibraries.leafModuleForms`) was
   REJECTED AS A DEFAULT (it breaks `:regex t` and silently changes keyword-template semantics) and
   ships as the opt-in system **`tiny-routes/lite`** (`.kb/asdf.md`).
4. **Loaded-but-unreferenced cl-ppcre is anchored by its CLOS surface** -- handled by the pruner's
   CLOS candidates + per-method gates (`.kb/library-defun-pruning.md`), ~30% out of every clack
   Worker. What the AST argument cannot touch is the `let`-over-`defmethod` root.
5. Splitting the parse half from the match half is not a plan: a scanner is a tree of closures
   closing over each other, and `load-time-value` runs INSIDE the module at load time.

On a USING app CLOS-aware shaking cannot pay -- the engine's 27 defgenerics ARE the build pipeline
and every parse-tree node class is instantiable from `create-scanner`; only code DENSITY moves it.
Compile-time lowering of literal regexes stays un-taken: one dynamic regex brings the whole engine
back silently.

## JVM
The shake runs as `am.ik.jvm.JvmClassSplitter.write` writes the class, on the `ClassDefinition` as
DATA: the call graph comes from every body's `invoke*` operands (`am.ik.jvm.OwnCallGraph`), methods
reachable from `main` are kept (plus `_apply` when the program uses `java:` interop -- the embedded
bridge looks `_apply` up REFLECTIVELY, an edge bytecode cannot show; under `--no-main` there is no
`main` root), and an unreachable method, or a field only unreachable methods reference, is never
written. The pool holds only what the members reference, at every level including `off`. No
renumbering: JVM methods are referenced by name. Mechanics and cost:
[jvm-method-size-limits.md](jvm-method-size-limits.md), "How a class is written".

**A `rontolisp:jvm-export` wrapper is a third liveness source**, next to `main` and the
dispatchable-funcId set: every export's Java method name joins the roots (its caller is Java code
the bytecode cannot show), and the wrapper's `invokestatic` keeps the target defun's graph. This is
what makes a compiled LIBRARY survive `--optimize`. The wasm side needs no equivalent because a wasm
export IS a module export the shaker already treats as a root; an export root keeps a method, not a
registry row. Mechanics and pins: [jvm-export.md](jvm-export.md).

Tests: `JvmDeadMethodEliminationTest` (structural + behavior, incl. the `_apply` root) and
`JvmDeadMethodEliminationCorpusTest` (the whole `ci-spec.yaml` corpus at `off` and default, asserting
shrink + identical run output -- the completeness guard for `CodeReplay`'s record player, like
`WasmTreeShakerCorpusTest`); `JvmClassSplitterTest#aShakenDefinitionWritesOnlyWhatItsRootsReach`.
Limitations: README "Optimize".

### A dispatcher case lives while a kept body makes its value
A funcId joins `valueFuncIds` when Pass 2 COMPILES its `(lambda ...)` / `#'name`, wherever that code
sits, a defun the shake then drops included. `MethodCode` carries two facts the writer never writes:
`makesValueOf(name, desc)` (also every `_lookup` row) and `invokestaticThroughValue`, a dispatcher
case's call. `OwnCallGraph.reach` follows such a call only once a KEPT body makes the target's value.
A value is no instruction and mints no pool entry -- a master-pool entry minted early would shift
the `ldc` widths the size budgets read. A value recorded in dead instructions of a kept method still
counts.

Then `JvmLispCompiler.rebuildDispatchers` rebuilds the dispatch methods over the values a kept body
makes; an arity the fewer cases bring under one segment gains its copy there, and
`retargetValueTails` points its `_vtc<n>` at it ([jvm-tail-bounce.md](jvm-tail-bounce.md)).
**It is asked after `_unw` is written**, so every caller of a dispatcher counts: when every call
through a value is a tail call, the value tails' `_vtc<n>` and the trampoline's re-entries are the
only ones, and a closure made only behind them must keep its case or the call that lands on it
answers NIL. The trampoline gate asks before, over the same value semantics. `_funName` keeps
`dispatchableFuncIds`; `--optimize=off` writes every case. `-Drontolisp.debug.dispatchgate=true`
prints how many dispatchable funcIds no kept body makes.

The WASM twin: "A ladder arm lives while a kept function makes its value" above.

Pins: `JvmClassSplitterTest#aCallThroughAValueKeepsItsTargetOnlyWhileAKeptBodyMakesTheValue`,
`JvmDeadMethodEliminationTest#aClosureWhoseEveryCreatorIsShakenTakesItsDispatcherCaseAlong`,
`#theRegistryKeepsTheCaseOfEveryNameItAnswers`, `#aClosureMadeOnlyBehindATrampolineBounceKeepsItsCase`,
`#valuesThroughEveryDispatcherShapeAnswerAsUnoptimized`.

### A registry row is a name, not a value
Both backends. Every row is a maker of its funcId's value, so rows covering every dispatchable
funcId would keep a `#'name` compiled only in dead code. The rows are `registryFuncIds` -- the
name-armed set alone (all non-call-only defuns under `--dynamic` / `anyNameResolvable`) -- and a
value-only funcId's case lives only while a kept body makes the value. `_funName` / `_fun_name` keep
the dispatch set.

**The soundness argument**: a symbol reaches `_lookup` only by a path the probes read. A quoted
symbol or a string a builder interns is a spelled literal; a data evaluator turns the gate off; a
name built from COMPUTED pieces is the existing carve-out. No operator turns a function object back
into a symbol: `function-lambda-expression` answers a nil name, and the print table yields a string,
which needs `read` (gate off) or a computed `intern` (carve-out). The package walk is covered by
source 2's walk bullet. Consequence: `(funcall (intern <computed>))` naming a function the program
also takes as `#'name` signals undefined-function like any forged name (documented in
`doc/*/compiling/{jvm,wasm}.md`); `--dynamic` restores it.

Pins: `JvmDeadMethodEliminationTest#aFunctionValueWhoseNameNothingSpellsHasNoRegistryRow` (also
the gensym prefix), `#theRegistryKeepsTheCaseOfEveryNameItAnswers`, its
`WasmTreeShakerTest` twin, `PackageWalkDesignatorFixture` on the four backends.

### A table no kept body reads goes with its reader
The `_funcrefs` fill is straight-line code in the always-kept `<clinit>`, so it would keep the
field alive after `_funcref` shook away. `JvmLispCompiler.dropDeadFuncrefTable` (after the
dispatcher rebuild) drops field, method and fill segments when `_funcref` is unreachable and
reassembles `<clinit>` around the fill -- possible because the fill binds no label, so no branch
crosses a piece boundary. Sound because nothing but those pieces references the field (a kept eval
runtime boxes through `_funcref`). Pins: `JvmDeadMethodEliminationTest#dropsUnreferencedFields`
(the exact field list, plus `_funcref` gone and the class still printing `1`).
