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
- **Invariant: the bare `--optimize` is `DEFAULT` and emits exactly the bytes it always did**; an
  ABSENT flag is `DEFAULT` too (`OptimizeLevel.parse(null)`), and `NONE` is spelled
  `--optimize=off`. A near-miss spelling (`none`, `no`, `high`) is an `IllegalArgumentException`,
  never a silent `DEFAULT`.
- Those two predicates ARE the level: `OptimizeLevelTest.everyLevelIsDistinguishableAndSpellable`
  fails if two levels answer both the same way -- **do not ship a level that is an alias**.
- **A VALUE, not a second flag**: `--optimize` stays in `CliOptions.noValueKeys` and the parser
  learned the `--key=value` form -- moving it out of that set would make
  `rontolisp app.lisp --optimize -o out.wasm` read `-o` as the level.
- Pins: `OptimizeLevelTest.theBareFlagIsTheDefaultLevel` / `theAbsentFlagIsTheDefaultLevel` /
  `theOffSpellingIsNoOptimizationAtAll`, `CliOptionsTest.theBareFlagKeepsItsEmptyValue`,
  `JvmLispCompilerTest.theFlaglessBuildIsTheOptimizedOneAndOffIsTheWayBack`.

### What `SIZE` declines
Speed-for-size trades, on at `off` and `default` alike. **wasm-GC**: integer expression-tree fusion
(`.kb/wasm-int-fusion.md`) and unboxed dual-representation locals (`.kb/wasm-unboxed-locals.md`),
both switched by `WasmIntFusionCompiler.speedTradesEnabled(ctx)` (the three fusion entry points and
`WasmLetCompiler`'s eligibility scan). **JVM**: typed numeric loops (`.kb/jvm-typed-loops.md`) and
integer fusion + unboxed locals (`.kb/jvm-int-fusion.md`) -- `Ctx.typedLoops`, `Ctx.intFusion`,
both `!prefersSizeOverSpeed()`. **`--no-gc`** accepts the level and emits byte-identical output
(`NoGcWasmCompilerTest.theSizeLevelIsADocumentedNoOpOnThisBackend`), as does any program without
those shapes (`JvmLispCompilerTest.theSizeLevelChangesNothingWithoutASpeedForSizeTrade`).

The size win barely varies (-16% to -24%); the run-time price varies more than thirtyfold, because
only INTEGER arithmetic fuses. **The two wasm trades are ONE level, not two switches**: "fusion off
/ unboxed locals on" is DOMINATED on both axes. `-Drontolisp.debug.norawlocals=true` still switches
the locals alone.

## Before the wasm shaker: the type-test fold and the forwarder redirect
`am.ik.wasm.WasmRefTypeFolder.fold`, then `WasmCallForwarding.redirect`, then
`WasmPeephole.rewrite` ("The adjacent-instruction peepholes" below, which also picks up the
`i32.const; drop` / `ref.null; drop` debris the fold leaves) run in
`WasmLispCompiler.shakeCore` ahead of `WasmTreeShaker.shake` at every level but `off`: a
whole-module type-flow analysis folds every `ref.test`/`ref.cast`/`ref.is_null` the module's own
constructors decide, prunes the arms that die, and redirects calls through the forwarding stubs
that leaves -- the generic arithmetic's float and rational arms in an integer-only program, the
printer's arms for types the program never builds (`.kb/wasm-ref-type-fold.md`: the licence, the
lattice, the numbers). All three rewrite bodies in place and renumber nothing, so the claims below
still speak in pre-shake indices.

## Before the shakers: a `typecase` clause no call can select
Both shakers are name reachability (wasm over `call` immediates, JVM over method references), so
neither can see a clause dead because of what the CALLER passed. `compiler/DeadTypeBranchPruner`
DELETES such a clause from the AST before Pass 1. Gated on `eliminatesDeadCode()`, called from
`JvmLispCompiler.compile` (right after `flattenTopLevel`) and `WasmLispCompiler.compile` (**after**
`NoWasiFilesystemStubs`, which closes the funcall-dispatch gate on a clack Worker -- the pruner
declines while the gate is open). Shares `ArgumentShapes.maySatisfy` with the `--no-wasi` build
warning (`.kb/wasm-export-no-wasi.md`). Pinned by `DeadTypeBranchPrunerTest`.

- **Deletion is the ONLY rewrite**, so no evaluation-order reasoning is needed; an
  `(if (typep x 'pathname) ...)` is left alone.
- A parameter's shape is the JOIN over EVERY call site; a name taken as a VALUE (or occurring in
  quoted data) states nothing; a name with two definitions, or also a
  `defmacro`/`defmethod`/`defgeneric`, is left alone; the pass declines entirely under
  `RuntimeNameProducers.anyNameResolvable`.
- Over-COUNTING a call site is harmless (it widens the join toward UNKNOWN), so the call scan is
  deliberately dumb; missing one is the unsafe direction, covered by the escape rules.
- Shapes flow through `let`/`let*`/`do`/`do*`, a `lambda`'s parameters (unknown) and `flet` locals;
  a `labels` local stays unknown.

## A dispatcher branch no call site can select
The defgeneric twin: a generic's dispatcher is the generated arm list (`.kb/clos.md`).
`compiler/GenericDispatchNarrowing` (the `macro.DispatchNarrower` hook) runs inside
`expandTopLevelDefinitions`, after the walk registered every method and just before the dispatcher
slots are filled: it joins argument shapes over every call site of each generic (the
`ArgumentShapes` lattice, which gained a VECTOR shape from the
`make-array`/`vector`/`make-string`/`subseq`/`copy-seq` return rules), and `generateDispatcher`
omits each branch whose specializer vector no site's shapes may satisfy. Method-body defuns are
still emitted; the SHAKERS drop the unreferenced ones. Only an optimizing, early-bound compile
narrows -- the interpreter, `ShadowedBuiltins`, `--dynamic` and `NONE` pass a null narrower.

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
- Pinned by `JvmClassShakerTest.anUnselectableGenericBranchAndItsMethodShakeOut`, its
  `WasmTreeShakerTest` twin, and a program in `optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo`.
  Unfinished: async programs are declined wholesale; an EQL specializer is never ruled out; a
  struct-name specializer is undecided in the lattice.

## The wasm tree shaker
`am.ik.wasm.WasmTreeShaker` (language-independent) runs on the finished **core module** bytes in
`WasmLispCompiler.compile` just before returning -- **including under `--component`**, right after
`WasmImportInjector.inject` and before the component wrapper is built. Call graph from the actual
`call`/`ref.func` immediates, reachability from the roots (exported functions + `_start`/start
section), drops the rest **including unused WASI function imports**, renumbers every surviving
function AND type reference. Reachability is exact: with `eval`/`load`/`apply` the dispatch bodies
contain real `call`s to every registered function. **This is the one place the fixed-index
invariant is deliberately broken**, and only because every reference site is rewritten in lockstep.

### Type section
- **roots** -- each surviving function's function-section entry, each surviving import's `typeidx`,
  every tag (the tag section is copied verbatim), each SURVIVING global's value type and
  initializer (see "Global section" below -- a dead global's type references die with it), and every
  type immediate in a surviving body (GC-op `typeidx`es, `ref.test`/`ref.cast`/`ref.null` heap
  types, block types, locals).
- **edges** -- type-to-type references inside the type section: a struct/array field's
  `(ref null $t)`, a func type's params/results, a `sub` clause's supertypes.
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
The shaker walked functions, types and data; a GLOBAL nothing reads used to survive it. The backends
emit one `(mut (ref null eq)) = null` per top-level Lisp variable whether or not the program has a
reader, so the runtime's own specials travelled into every module: fifteen globals, 79 bytes, on a
1.8 KB `--no-wasi` reactor that read none of them.

- **roots** -- every IMPORTED global (dropping an import changes the host contract, and its index is
  part of it), every EXPORTED global, and every global a SURVIVING function body reads or writes
  (`global.get`/`global.set`, now `WasmSections.RefKind.GLOBAL` rather than a skipped LEB).
- **edges** -- a live global's initializer expression may name another global.
- Survivors are renumbered exactly like functions and types, in bodies, in initializers and in the
  export section (`rebuildExportSection` learned the global index space for this).
- The win is not only the global section: a dead global's initializer may name a concrete type, and
  that type's whole `rec` group then retires with it. `(print 1)` loses 34 bytes of type section
  that way -- one global built a struct in its initializer and nothing else named the group. Which
  is why `WasmLispCompilerTest.wasmContainsRecTypeGroup` asks the UNOPTIMIZED module: a shaken
  `(print 1)` carries no `rec` group at all.
- Pins: `WasmTreeShakerTest.dropsGlobalsNoSurvivorReadsAndRenumbersTheRest`,
  `keepsAnExportedGlobalAndTheOnesItsInitializerNames`.

### Host cell hooks: an export decided by who reads its cell
`WasmTreeShaker.HostCellHook(exportName, cellAddress)` drops an export that exists only so a HOST can
write one linear-memory cell, when no surviving function reads that cell. The two claimants are the
`--no-wasi` setters `__ronto_seed_random` and `__ronto_set_time`; the full reasoning, the fixpoint
and the pins are in `.kb/wasm-export-no-wasi.md`, "Both hooks are DROPPED from a module whose program
cannot use them".

### Owned data segments
`WasmTreeShaker.OwnedDataSegment(segmentIndex, ownerFuncIndices)` names a segment whose bytes are
referenced exclusively by those functions, dropped when every owner is unreachable. **The shaker
cannot verify the exclusivity claim** (a linear-memory reference is an indistinguishable
`i32.const`), so it is the CALLER's invariant. Only claimants: the two Unicode case-fold range
tables (~16.4 KB, `WasmCaseFoldRuntimeBuilder`), owned by `_char_upcase`/`_char_downcase` -- owner
indices shifted by the injected host-import count, because the shake runs after
`WasmImportInjector`. Pinned by `orphanedCaseFoldTableSegmentsAreDropped`.

### String blob: droppable ranges
One segment holds the whole `StringTable` blob, so whole-segment ownership cannot express it (the
builtin WRAPPER bodies Pass 2a compiles intern their literals and the shaker then deletes the
wrappers). `WasmTreeShaker.DroppableDataRange(segmentIndex, start, end)` cuts the range out and
re-emits the segment as one active segment per surviving run, **each at the address it already
had**.

- **Candidacy is decided by OBSERVATION**: a range survives when any SURVIVING body or global
  initializer holds an `i32.const` in `[address, address + length)` -- **HALF-OPEN**, because no
  emitter produces a bare one-past-the-end pointer (the only computed pointer is
  `WasmLiteralPrint`'s `framed.offset() + 1`, interior) and the closed interval pinned every range
  whose end abutted a LIVE neighbour's start in what is one dense run. An unrelated constant
  landing in a range only KEEPS bytes.
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
  cost **+11.5 KB on the hello-clack Worker**, so it keeps the `" on "` separator
  (`LispMacroExpander.noApplicableMethod`).
- **The runtime intern table is handled structurally.** Each candidate's 8-byte `(offset, length)`
  row in `buildInternBlob` is offered as a droppable range OF ITS OWN, probed on the STRING's
  interval -- the five-argument `DroppableDataRange`, whose extra interval is a caller claim: the
  only reader of the cut bytes must tolerate them reading as zeros. **The function-name table
  (`_fun_name`) is the inverse shape and needs the six-argument form**: the NAME's bytes are probed
  on the table's base word, but a deduplicated name is also the symbol a live body builds
  (`VECTOR`, `-`), so `ownCitationKeeps` keeps it while any body cites it -- and
  `StringTable.addFunName` offers the claim only for a name no OTHER blob pinned. Cutting on the
  probe alone printed `type-of`'s `VECTOR` as NULs the day the type-test fold retired the
  printer's closure arm (`WasmTreeShakerTest.aRangeProbedElsewhereStaysWhileItsOwnBytesAreStillCited`). Row and bytes fall together;
  `_intern` skips any row whose offset word is 0 (without the skip a zero-length probe matches the
  first hole, and `'||` holds a live zero-length entry to diverge onto); rows are sorted by string
  offset before the blob is built; a candidate first interned AFTER the snapshot has no row --
  which is why `T` is interned before it.
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
the generic printer's integer arm alone pins the whole bignum print chain (9 functions), the f64
renderer and the ratio accessors.

**It is the FAMILY, not `print`.** `print`/`prin1`/`princ` are one emitter (`WasmPrintCompiler`)
precisely so the fold cannot exist for one spelling and not the others; `write-string`/`write-line`
fold through the same helper. Folded types: string, fixnum, bignum, character, ratio, float, `nil`,
`t`; floats select the same Schubfach shortest decimal as `LispDouble.print()` (`FloatText`).
Strings cost no data (an escape-free readable form re-uses the literal's bytes, a DISPLAY rendering
points at `offset + 1`, `length - 2`). **A COMPUTED argument reaches this fold when the computation
is itself constant** (`.kb/pure-builtin-fold.md`): `(princ (* 6 7))` compiles to `(princ 42)`'s
module byte for byte. Pins: `everySpellingOfHelloWorldReachesTheSameFloor` (< 1 KB), its
`…ComponentFloor` twin (< 2 KB), `aFoldedLiteralPrintsWhatTheRuntimePrinterWouldHave`.

### The print family's static-TYPE shortcut
Two shortcuts in `WasmPrintCompiler`, above the literal fold, for an argument whose TYPE the
compiler knows (the generic dispatch drags in `_princ_val`, `_charvec_to_str` + `_charvec_p`, and
the bignum/ratio/character/cons/array printers): **`princ` of a certainly-STRING form compiles as
`write-string`** (`compiler/StringValuedForms.certainlyString`), and **`princ`/`prin1`/`print` of a
certainly-DOUBLE form unboxes the `TYPE_FLOAT` struct and calls `_print_f64_no_nl`**
(`compiler/DoubleValuedForms.certainlyDouble`: an immediate literal-double argument of `+ - * /`,
strictly narrower than `hasDoubleLiteral`; only for the hard-coded standard output, since an
explicit stream or an active `*standard-output*` rebinding renders to a string first).
**The two predicates are the whole risk surface** -- a form wrongly admitted prints as the wrong
type rather than failing, or worse: `certainlyDouble` scanned the operands in ONE pass and answered
true at the first literal double, so a complex standing to its right was never reached and
`(princ (+ 3d0 #c(1d0 2d0)))` TRAPPED the module on the `ref.cast` to `TYPE_FLOAT` (2026-09-11,
`.todo/779`; the complex scan is now a pass of its own, ahead of the double scan). A new entry is
earned by checking every backend's emission for that operator, and a new disqualifying TYPE by
checking it over every operand. Pinned by `staticallyTypedPrintArgumentsPrintWhatTheValueDispatchWouldHave`, ci-spec
`statically-typed-print-arguments`.

### `%string-concat` byte-copies instead of rendering through the value printer
The internal `%string-concat` (what `format`, `concatenate 'string` and every
`(setf (aref s i) c)` spelling lower through) used to share `_princ_to_str`'s capture-mode renderer
and drag the whole value-printer family into any module with one immutable-string rebuild site.
Its operands are strings by contract -- the interpreter throws and the JVM backend `CHECKCAST`s
otherwise, and every lowering passes literals, format pieces, rendered messages or subseq results
-- so `_string_concat` is now a byte copy in `WasmStringRuntimeBuilder.buildStringConcatBody`
(normalize via the already-live `_charvec_to_str`, copy both contents around their frames into
`HEAP_PTR` scratch, `_str_fresh` over the result; a non-string traps on the cast). The last
concat-adjacent printer holder was the `(string c)` in the compiler-generated
`%schar-set-runtime` defun -- now a one-element character vector, the shape `expandMakeString`
lowers to, which the concat normalizer renders (the probe `.todo/338` reverted while the concat
engine itself still rendered). Measured 2026-09-09: a print-free concat-only module
16,201 -> 8,710 B (`-46%`); the zlib `--optimize=size` row does NOT move (125,738 -> 125,817 --
the printer stays reachable through chipz's own `princ-to-string` uses and the `apply`-pulled
`eval` runtime, so the `.todo/338` chain premise is stale for that row: the mechanism pays where
concat was the only printer edge). Pinned by `internalStringConcatCopiesBytes` in
`LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` (empty operands,
multi-byte UTF-8, a character-vector operand, the `setf`-`aref` rebuild, and the non-string
contract error on the interpreter).

### The `name` section is DROPPED, not copied
It maps **function and type indices** to names and this pass has renumbered both. Dropped in
`WasmTreeShaker` (`SEC_CUSTOM`); every other custom section is index-free and still copied.
Decisive on the hand-written WAT blobs the component wrapper embeds. Pinned by
`dropsTheNameSectionRenumberingHasInvalidated`.

### Identical bodies are emitted once
`am.ik.wasm.WasmBodyFolder` runs as the tail of `WasmTreeShaker.shakeWithRemap`, so every shaken
artifact gets it at every `eliminatesDeadCode()` level: functions with canonically-equal types and
byte-for-byte identical code entries collapse to one body, every
`call`/`ref.func`/export/start/global-initializer reference redirected; it iterates to a fixpoint,
then one more `dropUnreachable` collects orphaned type entries. "Canonically equal" = same index,
or same position in byte-identical `rec`-group entries NEITHER of which references its own members
(inside a self-referential group byte equality proves nothing). Matters only on `--no-gc`.

**Why folding is sound**: nothing observes a function's identity through its code index. A
first-class function value is a closure STRUCT whose dispatch id is plain `i32` data, so two folded
definitions keep distinct funcIds, `_lookup` rows and ladder arms; `eq` on WASM is `ref.eq` plus
char/bignum/string value fallbacks with NO closure arm (`WasmEmitHelper.emitEqComparison`), so
`(eq #'f #'g)` is NIL either way -- ci-spec `identical-function-bodies-keep-distinct-identity`
(`(eq #'f #'f)` already diverges interpreter-vs-compilers and stays out of the pin). `ref.func`
values have no comparator, and the component wrapper reaches core functions by export NAME only.

Identical bodies compress well, so on small clack Workers the RAW win comes with a few hundred
bytes MORE gzip. Pins: `WasmBodyFolderTest`; `-Drontolisp.wasm.debug-func-sizes` labels a folded
group by its survivor. **The JVM twin is measured, not implemented** (zlib: 48 duplicate methods,
8,331 B): JVM methods are reachable BY NAME, so the survivor set needs its own soundness argument.

### The adjacent-instruction peepholes
`am.ik.wasm.WasmPeephole.rewrite` runs between `WasmCallForwarding.redirect` and
`WasmInliner.inline` on BOTH wasm backends (`WasmLispCompiler.shakeCore`;
`NoGcWasmCompiler.compile`), at every `eliminatesDeadCode()` level. Every rewrite is decided by two
or three instructions that are ADJACENT in one block's decoded instruction list -- no dataflow, no
renumbering, nothing whole-module -- and all of them DELETE bytes rather than relocating any, which
is why raw and gzip move together here and the move above has to argue about that (`size-measurement.md`).
**The number this pass is for is raw bytes of the `code` section**; gzip was measured as a check and
falls by the same fraction.

- `local.set N; local.get N` -> `local.tee N` (2 B). **The two-value shape
  `set b; set a; get a; get b` is this same rule**, applied to its middle pair: the census counted
  it separately (685 sites on the Worker, 87 on `zlib`) and it needs no rule of its own.
- `local.tee N; drop` -> `local.set N` (1 B), including the `tee` the rule above has just made, so a
  statement-position assignment (`set N; get N; drop`) collapses back to `set N`.
- a pure value then `drop` -> nothing (2-3 B): a constant, `ref.null`, `local.get`, `global.get`.
- `if (result T) call F else ref.null end; ref.is_null` -> `i32.eqz` (8 B and a call) when the
  caller names `F` as a pure non-null producer -- `WasmLispCompiler.peepholePureNonNullCalls()`,
  which is `_t_sym` alone: it lazily builds the `t` symbol into its global and answers it, and
  every other consumer of the symbol calls it for itself. The shape is a predicate's i32 boxed
  into t/nil (`WasmEmitHelper.emitBoolFromI32`) and tested for nil by its consumer; the census
  had read the `call` as an error signal ("an assertion in value position") -- it was the `t`
  literal, 3,304 times on the Worker. The emitter side of the same shape is
  `WasmConditionCompiler` (below); this rule is what catches the predicates it does not name.
- `i32.eqz; i32.eqz` in front of an `if` or `br_if` -> nothing (2 B): the branch asks only
  whether the operand is zero. Only there -- an `i32.and` would see the value.
- `call F` -> one `drop` per parameter + `i32.const K`, when F's whole code entry is
  `i32.const K` (no locals). A SECOND pass over the bodies the first pass left, since the fold's
  debris is what hides the constant. The case is `_eql_tail` in a module with no boxed float,
  character, integer, ratio or complex (`.kb/eq-numbers.md`): **a never-taken call still costs the
  caller ~8-10% in a tight loop** (registers a call clobbers; measured 2026-09-19, a symbol-`eq`
  search loop, wasmtime), which the byte count alone does not show.
- `if (result T) X else X end` (X one pure instruction, the same in both arms) -> `drop; X`, and a
  `ref.test`/`ref.is_null`/`i32.eqz` then `drop` -> `drop` -- what the rule above and the fold's
  own `if (ref.test <dead type>) 0 else 0` leave; the operand's push then goes with the drop.
- `br 0; end; unreachable; end` -> the `unreachable` goes (1 B). **Two conditions, and the pass is
  unsound without either.** The `end` must close a LOOP whose last instruction is that `br 0`, so
  nothing falls out of it and the trap is dynamically dead -- over a plain `block` the same `br 0`
  LEAVES the block and lands on the trap. And the loop must be the whole of an enclosing block that
  takes and leaves nothing, so the stack at that point is already what the enclosing `end` wants --
  otherwise the trap is what makes the module validate (`i32.const 1; loop ... end; unreachable;
  end` in a result-less block is legal and stops being legal without it). That narrow form is not a
  compromise: it covers 185 of 185 sites on `zlib` and 1,481 of 1,488 on the Worker.
  **Every one of those sites is the type-test fold's own**: `--optimize=off` emits none, and
  `WasmRefTypeFolder`'s `stepEnd` writes the trap whenever a block's end is never reached, because
  at that point it cannot know whether what follows is the enclosing `end` or code that would stop
  validating without it. The peephole is the lookahead the fold cannot do, which is why the two
  disagree by design -- a second `fold` over the compiler's output writes the trap back
  (`WasmRefTypeFolderTest.theIntegerOnlyReactorLosesTheFloatAndRationalTiers` states the fixpoint
  on the fold's own output for exactly this reason).

The local rules run as one left-to-right pass that collapses the output's tail to a fixpoint, so a
pair a rewrite creates is taken without a second traversal. Measured 2026-09-13 (`--optimize=size`
unless the artifact's own flags say otherwise), raw / gzip:

| artifact | before | after | gzip before | gzip after |
| --- | ---: | ---: | ---: | ---: |
| hello-clack Worker | 815,348 | 795,062 (-2.5%) | 215,054 | 209,489 (-2.6%) |
| hello-tiny-routes Worker | 860,937 | 839,368 | 226,882 | 220,880 |
| hello-ningle Worker | 3,438,910 | 3,373,895 | 703,627 | 684,364 |
| httpbin Worker | 176,793 | 172,614 | 58,057 | 57,195 |
| `zlib` | 90,817 | 88,315 (-2.8%) | 30,957 | 30,134 |
| `pi_approx` | 1,544 | 1,504 | 925 | 910 |
| `hello_world` | 500 | 487 | 393 | 388 |
| `pi_approx` (`--no-gc`) | 3,333 | 3,289 | | |
| `hello` Worker (`--no-gc`) | 507 | 489 | | |

Pins: `WasmPeepholeTest` (one hand-assembled body per shape, including the two the loop-tail rule
must refuse and the box the pass must keep when nobody vouches for the call), and
`WasmTreeShakerCorpusTest`, which runs the pass by hand over the whole `ci-spec` corpus in the
position the compile path runs it, with the same pure-call predicate -- it must shrink the module,
and what it leaves must validate and re-encode to itself.

### A test is compiled as a test, not as a value

`WasmConditionCompiler.compile(test, ctx, negated)` is what `if`, `while` and a value-position
`not`/`null` hand their test to: a raw i32, non-0 exactly when the test is true (false when
`negated`, which is what an `if` -- whose wasm THEN arm is the Lisp else arm -- and a loop's exit
`br_if` want). `not`/`null` flip `negated` and recurse; `consp`/`atom` are one `ref.test`;
`eq`/`eql` the comparison's own i32; a numeric comparison goes to
`WasmComparisonCompiler.tryCompileConditionI32` (the fused or `_rat_cmp_bits` i32, the hook that
used to be the whole of this); `and`/`or` short-circuit through `if (result i32)` blocks over their
operands compiled the same way, so a chain of predicates never materialises a box; `t`/`nil` are
constants, and `WasmIfCompiler` selects the arm of a constant test at compile time (a `cond`'s
`(t ...)` clause used to reach the backend as `(if t x nil)`, its `t` a `_t_sym` call tested and
never false -- `LispMacroExpander.expandCondClauses` now folds it, and `expandAnd` produces
`(if x (if y z nil) nil)` instead of `(cond ((not x) nil) ... (t z))`, for every backend).
Anything else is compiled as a value and tested with one `ref.is_null`; the peephole above then
takes the predicates this compiler does not name (`stringp`, `symbolp`, `%obj-is`, ...), whose
box it turns back into the `i32.eqz` a chain operand wrote, and the double-negation rule folds
the pair. Measured 2026-09-13 (after the `&key` helpers, `--optimize=size`, raw / gzip):
hello-clack Worker 775,987 -> 734,519 (-5.3%) / 206,264 -> 200,909, `zlib` 85,687 -> 81,726
(-4.6%), httpbin Worker 172,507 -> 161,833 (-6.2%), hello-tiny-routes 814,808 -> 771,400 -- of
which the peephole rule's own share, over what the compiler leaves, is 9,096 B on the Worker
and 1,012 on `zlib`; the boxed-and-tested census row is 2,911 -> 951 -> 0 on the Worker. The
predicate is shifted by the host-import count like the case-fold owner claims
(`peepholePureNonNullCalls(importShift)`): the first cut named the emitted index and missed
every site of the httpbin Worker, whose two `env` imports sit in front. Pinned by
`WasmLispCompilerIntegrationTest#compileAndRunTestsCompiledAsTests` (every shape on a program
that runs, the same program on the JVM and the interpreter) and the corpus guard.

**What did NOT pay, measured and not landed: answering a `call` at the call site.**
`.todo/798` also asked for two rewrites in `WasmCallForwarding` for the bodies the type-test fold
leaves beside its forwarders -- the identity (`local.get 0; end`, so the `call` is deleted) and the
constant (`() -> (t)` whose body is one constant, so the `call` becomes that constant). Both were
built and measured over 13 artifacts: **0 bytes on twelve of them and 153 B on one** (the 3.4 MB
ningle Worker, 0.005%). The shapes are there -- 7 to 8 functions match in every module -- but they
are already UNREFERENCED when the pass runs, so the shaker was collecting them for free and
rewriting their call sites rewrites nothing. The population is a fixed set of runtime helpers, so
it will not grow. The prediction the item carried ("20-30 B on each small module") is what this
replaces.

### The local renumbering

`am.ik.wasm.WasmLocalOrder.reorder` runs LAST on both wasm backends -- over the shaken module
(`WasmLispCompiler.shakeCore`, `NoGcWasmCompiler.compile`), because a permutation of one
function's own locals can follow everything that reads function indices or moves bodies
between them. In a function with more than 128 locals (parameters included) every
`local.get`/`set`/`tee` of a local from index 128 up costs two bytes, and `Ctx.allocTemp` is
`nextLocal++` -- a fresh local per temporary, never recycled, so the locals a body uses most sit
wherever they were allocated (`USOCKET:SOCKET-CONNECT` declares ~120; 22 Worker functions
exceed 128). The pass gives the one-byte indices to the locals with the most uses (ties keep
declaration order), groups each of the hot and the cold set by type so the declaration vector
stays a few runs (a 130-run alternating vector becomes four), keeps every parameter where it is,
and asks no liveness. A frame that fits in one byte is left byte-identical. **The number this pass
is for is raw bytes of the code section**, and it is a permutation, so gzip follows: measured
2026-09-13 over the condition-mode numbers above, hello-clack Worker 734,519 -> 730,394 raw /
200,909 -> 197,943 gzip (the regrouped vectors compress better than the bytes they lose),
hello-tiny-routes 771,400 -> 767,253, httpbin 161,833 -> 161,569, `zlib` 81,726 -> 81,720 (its one
wide function has no hot set to speak of: 152 -> 146 two-byte immediates), the small modules
untouched; the Worker's two-byte `local.*` immediates 6,314 -> 2,189. Recycling temporaries in
the emitter (the `Ctx` scratch slot the census proposed, with its chunk-cut trap) would shrink
FRAMES, not bytes, and was not built. Pins: `WasmLocalOrderTest` (the hot local, the regrouped
vector, the frame left alone) and `WasmTreeShakerCorpusTest` (must not grow, must validate and
round-trip over the corpus).

### Sparse arity ladders

`WasmRuntimeBuilder.emitCaseSelector`: the selector over a dispatcher's case blocks is one of
three shapes, chosen by exact byte count -- a `br_table` over `[0, max]` (one label per id, the
holes naming the default), the same table BIASED to the smallest live id (the id less `min`
indexes it; a smaller id wraps to a huge unsigned index, which is the default), or a comparison
chain (`i64.const id; i64.eq; br_if depth` per live id, `br default` after) when the chain is
under HALF the table. A tie keeps the plainer shape, so a dense ladder is byte-identical: the
hello-clack Worker's six ladders are 902 live of 903 and did not move. Under `--optimize` a
ladder carries only the callables taken as VALUES (the funcall-dispatch gate below), so a library
program's ladders are sparse by nature: `zlib`'s arity-0 ladder tabled 419 labels for one
callable at 418, its arity-3/4/5 ladders 2-4 callables over 239-514 slots, and its two dense
ones started 133 slots in; the httpbin Worker's five ladders each started at 190. Two rules
the first cut lacked, each a measurement (`size-measurement.md`):

- **The half rule is the raw/gzip trade.** A chain's bytes are incompressible (a distinct id per
  case) where a table's holes are a run of one byte gzip folds to nothing: with "chain whenever
  shorter", `zlib`'s two 57/69-of-558 ladders became chains and the module went -2,086 B raw and
  **+1,168 B gzip**; under the half rule they stay biased tables and both numbers fall.
- **The ids are i64 constants, never i32.** The tree shaker keeps a string-blob range that any
  surviving `i32.const` lands in ("String blob: droppable ranges" -- an address is an
  indistinguishable `i32.const`), and funcIds live exactly where the small literals do: spelled as
  `i32.const`, the chains pinned 770 B of `zlib`'s blob (a 756-byte printer range plus
  `"Infinity"`). An i64 is never an address, so the bias is `i64.extend_i32_u; i64.const min;
  i64.sub; i32.wrap_i64` and a compare `i64.extend_i32_u; i64.const id; i64.eq` -- one byte more
  per compare, and no coupling to the shake.

Measured 2026-09-13 (`--optimize=size`, raw / gzip): `zlib` 81,720 -> 79,005 (-3.3%) / 28,737 ->
28,692, httpbin Worker 161,569 -> 160,649 / 55,412 -> 55,400, hello-clack and hello-tiny-routes
byte-identical; `zlib`'s default labels 3,799 -> 989. The paged dispatcher's leaf pages use the
same selector. `wasm-function-body-size.md`'s early-out ("the flat shape is not built when its
label count alone settles the gate") is now conservative rather than exact -- a sparse ladder past
the gate would have been a small chain -- and stays, because paging is always correct and the
gate is about the body bound, not bytes. Pinned by
`WasmDispatchPagingTest#aSparseLadderSelectsByComparisonAndADenseOneByTable` (the i64 compare
included) and `WasmLispCompilerIntegrationTest#aSparseArityLadderDispatchesTheSame`.

### A char comparison pair is one code-point compare

`WasmCharCompiler.compileChain` compiled every `char=`/`char<`/`char<=` -- nearly all of them
two-operand -- as the n-ary chain: three eqref temps, each code point boxed as an i31 to be
stored and cast back to be compared, and a literal `#\~` built as a char struct, cast and read
back first (the census's "literal compared through its box", 228 sites on the Worker). The pair
is now `pushCode a; pushCode b; i32.eq` -- a literal's code point the `i32.const` it is -- boxed
once in value position and, through `WasmConditionCompiler`, not at all as a test; the n-ary
chain keeps its shape for the rare three-operand call. Measured 2026-09-13 over the ladder
numbers (`--optimize=size`, raw / gzip): hello-clack Worker 730,394 -> 719,754 (-1.5%) / 197,943
-> 194,536, httpbin Worker 160,649 -> 155,838 (-3.0%) / 55,400 -> 53,849, hello-tiny-routes
767,253 -> 756,000, `zlib` 79,005 -> 78,572; the box-then-unbox census row 228 -> 68 on the
Worker (what is left is a char-valued runtime call whose result is read back, not a literal).
Pinned by `WasmLispCompilerIntegrationTest#aCharComparisonPairIsOneCodePointCompare`.

### A conditional in statement position compiles for effect

`WasmExprCompiler.compileForEffect` hands an `if` -- and, through their expansions, a
`when`/`unless`/`cond`/`and`/`or` -- to `WasmIfCompiler.compileForEffect`: a void wasm `if`
whose arms compile for effect, so neither arm materialises a value and no `drop` follows; an arm
that is nil or a literal is no arm at all, the test's polarity picks the live one and no `else`
is written. Value position is untouched (`(if c a b)` as a function's last form still answers),
and state-machine mode does not reach it (its `if` routes resumes through its arms). Before, a
statement `(when c (setq x ...))` was `if (result eqref) ... ref.null eq else ... end; drop`: the
Worker had 743 such blocks, 44 with a bare nil arm. Measured 2026-09-13 over the char-pair
numbers (`--optimize=size`, raw / gzip): hello-clack Worker 719,754 -> 716,057 / 194,536 ->
194,305, hello-tiny-routes 756,000 -> 752,239, httpbin 155,838 -> 154,998, `zlib` 78,572 -> 78,330.
Pinned by `WasmLispCompilerIntegrationTest#aConditionalInStatementPositionCompilesForEffect`.

### The single-call-site move
`am.ik.wasm.WasmInliner.inline` runs between `WasmPeephole.rewrite` and
`WasmTreeShaker.shake` on BOTH wasm backends (`WasmLispCompiler.shakeCore`;
`NoGcWasmCompiler.compile`), at every `eliminatesDeadCode()` level. A defined function the whole
module `call`s from exactly one place has its body moved to that call site and is left
UNREFERENCED -- the shake behind it is what deletes the code entry, the function-section entry and
any type only that entry named. Nothing is renumbered here, so an `OwnedDataSegment` claim or a
`-Drontolisp.wasm.debug-func-sizes` name map still reads in the module's own indices. Out of scope:
a callee that is exported, named by the start section, recursive, its own caller, or PINNED by the
backend (`WasmLispCompiler` pins the three case-fold owners: their segment claim names them by
index, so a moved body would take the table with it). A module with a table or element section is
declined wholesale; `ref.func` takes it out through `WasmCodeModel`, which refuses to decode one.

**The move alone is a LOSS** -- `wasm-opt --inlining` makes `zlib` 15,214 B BIGGER, because handed-
over arguments become `local.set`/`local.get` pairs the caller never had and every local index past
127 costs a second byte. So the pass is the move plus its arithmetic, and all three parts are load-
bearing:

- **Stack hand-over.** A callee that reads each parameter exactly once, in order, as the first
  instructions of its body -- every forwarder, every thin import wrapper -- needs no locals at all:
  the arguments are already on the stack in that order, so those leading `local.get`s are dropped.
  **The wrapper block a `return` needs then has to declare them**: it is emitted as
  `block (type <the callee's own type index>)`, because values pushed before a block are
  unreachable from inside one that declares no parameters. A bare `block (result t)` there decodes,
  validates nothing, and is the one bug in this pass wasm-tools caught that structure assertions
  could not.
- **Argument substitution.** When the instruction that pushes an argument is re-materializable (a
  constant, or a `local.get` of a local the moved body never writes), every parameter read becomes
  that instruction again and the push is deleted -- no local, no `local.set`. Chosen per parameter,
  by byte count against the local it would replace.
- **A measured decision.** The rewritten caller is encoded and compared against the caller plus the
  callee it replaces; anything not strictly smaller is abandoned. The pass therefore cannot grow
  the code or function section of any module whatever its local numbering does
  (`WasmTreeShakerCorpusTest` asserts it over the whole `ci-spec` corpus, both WASI modes).
- **Tail calls** (`.kb/wasm-tail-calls.md`, 2026-09-19). A `return_call callee` site is a call
  site whose moved body ends in a `return` (a dispatcher case falls through into the next case);
  a `return_call X` inside a moved body is `call X; br <wrapper>` -- as deep as the tail call
  left the stack, since the callee's frame is gone either way -- except a TRAILING one (the
  body's last instruction at depth 0, every forwarder), which is a bare `call X` falling off the
  end: the wrapper block would cost exactly what the move saves.

**Two things that guard alone does not see, both measured the hard way on `zlib` (2026-09-13).**

1. **A body the duplicate fold was going to reclaim anyway is worth nothing here, and costs the
   caller its whole size.** `WasmBodyFolder` (above) drops all but one of a set of byte-identical
   bodies for free; moving the once-called member removes a body the fold was removing and leaves
   the caller carrying those bytes for good. Ignoring this, the pass claimed 530 B over 134 moves
   and DELIVERED +274 -- the first run of it made `zlib` bigger. A candidate whose (canonical type,
   code bytes) pair another defined function repeats is therefore declined. A CALLER never has a
   twin, so only the callee side needs the test: a byte-identical body repeats the same `call`
   immediate, which would make its callee's call site count two.
2. **Relocating bytes costs REPETITION, which is what the artifact's compressor lives on.** What a
   move reclaims is the per-function overhead -- a size prefix, the locals-vector byte, the
   terminating `end`, the `call`, a function-section entry: 6 to 12 bytes, never a function of how
   big the body is. So `WasmInliner.MAX_MOVED_BODY` caps the moved code entry at **64 bytes**, and
   that number is a measurement, not a taste. Over 23 artifacts -- the `size-report` corpus, the
   Cloudflare Worker family and the `--no-gc` browser reactor of `.todo/804` -- summed against the
   same build without the pass:

   | budget | raw | gzipped | worst gzip row |
   | --- | ---: | ---: | --- |
   | 64 bytes (shipped) | **-911** | **-95** | `zlib_optimize` +103 (+0.26%) |
   | 256 bytes | -4,143 | +3,670 | `zlib_size` +1,018 (+3.3%) |
   | no budget | -50,722 | +16,064 | `hello-ningle` +3,686 |

   A Worker's platform limit counts COMPRESSED bytes (`size-report/notes/cloudflare-workers.md`),
   so the uncapped row is not a win on the artifacts that consume the GC backend most. 64 is the
   knee: the smallest budget at which the host-facing `--no-gc` modules -- the ones this pass
   exists for -- reach their FULL win.

**What it is worth, at that budget** (2026-09-13, `--optimize=size` unless stated): the `.todo/804`
browser reactor 1,090 -> **1,011 B (-7.2%)**, its code section 360 -> 300 in 17 -> 8 functions --
one byte off the 299 a hand-written non-GC toolchain emits for the same program, with `.todo/805`
still to come. `hello`, the `--no-gc` Worker: 532 -> 510 (-4.1% raw, -3.2% gz).
`hello_world_nogc` 244 -> 232. On the GC backend it is a rounding error by design: `zlib` -87 of
90,904, the big clack/ningle Workers -24 to -88 of 800 KB. That is the honest correction to
`.todo/791` item 3's reading of binaryen's 1,985-byte inlining share on `zlib`: binaryen recovers
those bytes by re-encoding locals across the whole function afterwards, which this pass does not
do and which is not worth its compressed cost. **The prize is the small host-facing module, not
the library.**

### The single-use local
`am.ik.wasm.WasmLocalSink.sink` runs behind `WasmInliner.inline` and in front of the shake on BOTH
wasm backends (`WasmLispCompiler.shakeCore`; `NoGcWasmCompiler.compile`), at every
`eliminatesDeadCode()` level. A local written ONCE, by a pure expression, and read ONCE, at a point
the write dominates, does not need to exist: the expression is moved to the read, the `local.set`
and the `local.get` go, the local leaves the declaration vector and every local above it moves down
an index. Three things pay for the one decode:

- **The sink itself.** The expression is pure (reads of locals and globals, constants, non-trapping
  numeric ops, `ref.i31`/`ref.test`/`ref.is_null`/`ref.eq`, `struct.new*`, `array.new_fixed`, and
  `array.new`/`array.new_default` of a small constant length -- an allocation nothing but the sunk
  local can reach before the read, **so long as it is still evaluated ONCE**: never as a copy, and
  never into a loop the write is outside of, where the read runs per iteration and a closure built
  once would be rebuilt, cell and all, every time -- `mapcanMapconLongInput`'s counter closure
  counted nothing until that rule existed); its inputs are unchanged from the expression to the read
  (no write of a local it reads; no `global.set` and no `call` when it reads a global), the range
  extended to the end of the outermost `loop` opened after the write and still open at the read;
  and the write dominates the read (no `else`/`end` between them closes a block open at the write).
  The expression may sit behind a stack-neutral gap -- the inliner's reverse hand-over
  `e1; e2; set b; set a` -- whose instructions join the scanned range. A `tee` sinks a COPY, taken
  only when the copy is shorter than the `tee` plus the `get`.
- **Dead writes.** A local nothing reads: each `tee` is deleted, each `set` becomes a `drop` or --
  when the walk back finds a pure expression feeding it -- goes with it. `zlib` had 162 such
  locals, the hello-clack Worker 664.
- **The frame.** A local nothing touches leaves the declaration, and a `set N; get N` pair the
  deletions (or the inliner) leave adjacent is written as `tee N` on the way out, so the peephole
  in front does not have to run again. `zlib`'s frames: 2,475 -> 1,510 locals; hello-clack's
  15,244 -> 10,860, its two-byte `local.*` immediates 3,175 -> 1,807 BEFORE `WasmLocalOrder` sees
  them.

Bodies are re-sunk to a fixpoint (a local whose expression reads a sunk local waits a round), and
a function's own locals are all it renumbers, so every index-addressed claim the shake reads is
untouched. **The number this pass is for is raw bytes of the code section, and it DELETES bytes**,
so gzip follows -- and falls faster, because a fresh local per temporary is exactly the
low-repetition byte a compressor cannot fold.

**Measured 2026-09-14 against `a4239f80d`, `size-report/measure.sh`, both families**
(`--optimize=size` unless the row says otherwise; raw / gzip where the report records gzip):

| artifact | before | after | |
| --- | ---: | ---: | --- |
| `zlib` `--optimize=size` | 78,330 | 76,011 | -3.0% |
| `zlib` `--optimize` | 102,909 | 100,597 | -2.2% |
| `pi_approx` | 1,504 | 1,489 | |
| `hello_world` | 487 | 480 | |
| `pi_approx_nogc` | 3,289 | 3,279 | |
| `dom_reactor` `--no-gc` (the `.todo/812` reactor) | 856 | 847 | code 200 -> 191 |
| hello-clack Worker | 716,057 / 194,305 | 706,036 / 186,368 | -1.4% / -4.1% |
| hello-ningle Worker | 2,977,818 / 619,011 | 2,932,835 / 577,583 | -1.5% / -6.7% |
| httpbin Worker | 154,998 / 53,809 | 151,076 / 51,056 | -2.5% / -5.1% |
| the Worker family, 16 rows | 15,354,384 / 3,788,422 | 15,110,090 / 3,571,238 | **-1.6% / -5.7%** |

Every row is smaller on both axes; the worst gzip row is `hello` at -4 B.

**The item's premise was measured the wrong way round.** `.todo/812` counted the single-use
residue AFTER the whole pipeline -- 723 locals on `zlib`, one on the reactor, "single-digit bytes"
if only the inliner's hand-over was the target -- and split the fix into a cheap copy-propagation
half (the definition is `local.get M`, `M` never written: 107 of the 723) and an expression-sinking
half worth doing only if the first left a large remainder. Both halves are one walk over the
decoded body with one legality argument, the copy is the expression of length one, and the split
was never worth making. What the census could not see was the other two populations the same
renumbering collects for free, and that the Worker family carries proportionally far more of all
three than `zlib` does (`residue.sh` on hello-clack: 4,320 single-use locals, 2,174 remain, nearly
all a `call` result or a block result stored and used once, which no pure-expression rule
reaches). The post-pipeline residue on `zlib` is now 218: 131 `call` results, 29 block results,
17 `array.new` of a computed length.

Pins: `WasmLocalSinkTest` (the sink, the gap, the renumbering, the loop-carried input, the
undominated read, the global across a call, the tee copy, the allocation that is never copied nor
sunk into a loop, the dead writes, the chained round, the adjacent pair),
`WasmTreeShakerCorpusTest` (the whole `ci-spec` corpus at every level validates and round-trips
with the pass in the pipeline) and `WasmLispCompilerIntegrationTest.mapcanMapconLongInput` /
`sequenceBoundingKeywords` (a closure's cell mutated across iterations -- the two that caught the
allocation rule).

### The component WRAPPER: adapter + WASI surface
The adapter and the `wasi:*` declarations follow the core through one chain, every step *observed*
rather than declared (`WasmComponentBuilder.fixedSurface`, base variant only): the core's surviving
`wasi_snapshot_preview1` imports (`WasmImports.functionFields`) -> `WasmExports.retain` makes
exactly those the adapter's exports and `WasmTreeShaker.shake` deletes everything unreachable from
them, **including the adapter's own `"w"` imports** -> the surviving `"w"` names select their
`canon lower` / built-in entries out of one declarative table (`W_MEMBERS`, naming `BLOCK_FUNCS`
and `PROJECTED_TYPES`/`DEFINED_TYPES`) -> `ComponentImportBlock.prune` cuts the import blob down to
the interfaces those name, closing over projection edges and renumbering.

- **The projection closure is transitive, and one WIT `use` can widen a program's world**:
  `wasi:filesystem/types` `use`s `wasi:clocks/system-clock`'s `instant`, so any program that opens
  a file keeps `wasi:clocks/system-clock`. **Do not read an interface's presence as evidence that
  the program calls it** (`anOptimizedComponentThatOpensAFileKeepsTheFilesystemSurface`).
- **Nothing downstream may hold a fixed index.** The old `INST_*` / `T_*` constants are gone: the
  block's instance indices, the first free component type and the shift the user imports and the
  `run`/export wiring take all come back from the prune -- a stale constant yields a component that
  *validates* while binding the wrong interface, which is why `ComponentImportBlock.Pruned` returns
  the maps, not the bytes alone.
- **The adapter needed splitting to make the filesystem droppable**: `fd_write` dispatches on a
  runtime fd, so the call graph reached `append-via-stream` from any printing program.
  `adapter.wat` factors the fd-polymorphic shims into `$fd_write_stdio`/`$fd_write_file` and
  `$fd_read_stdin`/`$fd_read_file`. **`path_open` is the only writer of the adapter's fd table**,
  so a core that does not import it can never present a file fd: the wrapper then retains
  `fd_write_stdio` UNDER THE NAME `fd_write` (`WasmExports.retain` renames) -- the one place the
  component reads an adapter export whose name differs from `adapter.wat`'s.
- **A narrow half must be no more PERMISSIVE than the wide one.** `$fd_write_stdio` answers fd 1
  and 2 and TRAPS otherwise; `$fd_read_stdin` traps on any fd but 0. A SOCKET fd (>= 200) reaches
  `fd_write` whenever a write form escapes `WasmSocketsRewrite`'s dispatch table (`format` is one),
  and a guard-less narrow `fd_write` would have written those bytes to STDERR and returned success
  -- `--optimize` alone turning a crash into a protocol desync
  (`anOptimizedComponentFailsAsLoudlyAsAPlainOneOnAnFdItCannotServe`). **Rule for any future
  narrow/wide pair: the narrow one rejects what it does not implement.**
- **The blob grammar is decoded, not pattern-matched.** `ComponentImportBlock` classifies every
  byte and throws on anything else; only three immediates point outside their own entry (an alias
  section's instance index, an `alias outer` type index, an import's instance-type index) and only
  those are rewritten. `ComponentImportBlockTest` checks a prune byte-for-byte against
  `wasm-tools` output and runs all 2,047 non-empty subsets back through the parser.
- **`--emit-wit` moves with it** (`WasmComponentBuilder.wasiInterfaces` is the same set);
  `WitEmitter.orderPackagesByFirstReference` derives package order from the world, because
  `wasm-tools component wit` prints definitions in the order the world first names them.
- **Serve is deliberately NOT pruned** (`WasmServeComponentBuilder` keeps its fixed block constants
  and embeds the preview1 bridge whole).

**The last fixed costs.** **`wasi:cli/stderr`, 185 B**: fd 2 is the RESERVED `*error-output*`
handle, materialized in `StreamDesignators.STANDARD_ERROR_HANDLE` alone, so "can this program
present fd 2" is a question about the SOURCE -- `WasmLispCompiler` answers it (`programUsesSymbol`
over `*ERROR-OUTPUT*` / `WARN` / `%WARN`, plus `--dynamic`) and hands it over as
`WasmComponentBuilder.Narrowing`; `adapter.wat` gained a third `fd_write`, `$fd_write_stdout` (fd 1
only), and a program with `path_open` keeps the WIDE one and therefore stderr. *This depends on a
list being complete* (`.kb/standard-output-redirect.md`), and **a producer the compiler INJECTS
cannot join a scan of the user's text** -- the EH-mode landing pad (`WasmUncaughtReportCompiler`)
did not, and `--component --optimize` pruned the interface out from under the uncaught-condition
report; such a producer contributes its own emission fact instead (`emittedFor(ehMode)`, OR-ed into
`reachesStandardError`). Pinned by
`anOptimizedComponentWithAnUncaughtReportLandingPadKeepsTheStderrSurface`,
`anOptimizedComponentStillReportsAnUncaughtCondition`.
**The shared `cabi_realloc`, 142 B**, is kept only when a canonical option references it, and
`WMember.realloc()` cannot drift from the encoders because the index is reachable only through the
`lowerRealloc`/`builtinRealloc` factories that set the flag; a `wasm-export`'s string ABI lifts
through the CORE module's own `cabi_realloc` instead. **The floor**: the synchronous
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
reaches in only through `alias core func (instance N) "name"` (`ComponentWriter.aliasCoreFunc`) --
`run`, `handle`, `async_cb`, each `wasm-export` wrapper, `cabi_realloc`, `cabi_post_*`, **all core
EXPORTS and hence already shaker roots**; the core's imports are satisfied `from-exports`, so a
dropped import leaves one unused name in the map. `WasmComponentBuilder.memModuleFor` reads the
core's `mem`/`memory` **memory** import, kept verbatim with every other non-function import.

## What an external optimizer still finds, and what it is made of

**The rule this section exists to serve: measure what the emitter emits, not what an
optimizer can recover from it.** It is one of three ways "smaller" splits into numbers that
do not move together -- [size-measurement.md](size-measurement.md) names the other two. A binaryen number is context, never a target -- it does
not bound how small a module can get and it does not measure what is left to win. Both
directions have now been walked into:

- The `car`/`cdr` lowering change below took the hello-clack Worker from 916,587 to 815,414
  bytes, and **binaryen found the SAME 182,697 bytes in the module before and after it**.
  The optimizer's reach is a fixed quantity a lowering change walks straight past, so a
  residue is not a ceiling.
- The `.todo/789` reactor was 1,658 bytes and `-Oz` stopped at 1,495 -- within five bytes
  of what a hand-written non-GC toolchain emits for the same program. A residue near zero
  is not "nothing left to win" either: 182 of those bytes were a literal string walked out
  of linear memory into a GC array and back, which no optimizer can see as a round trip.
  todo 801 removed it at the emitter (2026-09-13, [[wasm-import]] "A literal `:string`
  argument does not round-trip"): the same reactor is **1,308 bytes with no optimizer at
  all**, 187 BELOW the figure `-Oz` had "stopped at". The residue was never the floor.

So the measurements that pay are the pass ranking below, a per-function decomposition of
one module, and the shape census -- what the backend chose to emit. The residue tables are
how you find WHERE to look, and nothing more. (The first version of the ranking below was
read the other way round, which is how both corrections were found.)

`wasm-opt` (binaryen 130/132) run as a PROBE over the shaken output -- never a build step;
the core libraries take no external dependency. Two corrections to how the residue was
first read (`.todo/791`), so the next measurement does not repeat them:

- **Binaryen's writer is not rontolisp's**: the module re-encoded with NO passes grows by
  1,452 B on `zlib` and 13,080 B on the hello-clack Worker (~0 on the small modules), so a
  pass run alone "grows the module" by that tax before it saves anything, and every number
  below is `size - optimized + tax`.
- **`inlining-optimizing` is not the inliner**: it re-runs the whole default function
  pipeline over every function a call site changed, which is nearly all of them, so alone it
  ranked first (71-90% of the residue). With inlining skipped (`-Oz
  --skip-pass=inlining --skip-pass=inlining-optimizing`) the residue is 90% intact on the two
  large modules; only on the small ones is inlining the enabler (of `heap2local`: a boxed
  float built by one function and unboxed by the next).

`--optimize=size`, `--no-wasi`, measured 2026-09-12 on `e0bcf42f0` (before the cons readers
below):

| Program | size | `-Oz` residue | without inlining | inlining's marginal |
| --- | ---: | ---: | ---: | ---: |
| `zlib` (size-report) | 94,069 | 20,237 (21.5%) | 18,252 | 1,985 (10%) |
| hello-clack Worker | 916,587 | 204,809 (22.3%) | 184,091 | 20,718 (10%) |
| `pi_approx` | 1,528 | 506 (33%) | 249 | 257 (51%) |
| the `.todo/789` reactor | 1,658 | 160 (9.7%) | 111 | 49 (31%) |
| `webgl-triangle` (`--optimize`) | 1,683 | 305 (18%) | 108 | 197 (65%) |

Single passes alone, tax-corrected, `zlib` / Worker: `coalesce-locals` 3,627 / 40,542,
`simplify-locals-nostructure` 3,761 / 30,927, `merge-similar-functions` 1,662 / 31,236,
`local-cse` 1,063 / 15,125, `remove-unused-brs` 1,590 / 695, `vacuum` 1,035 / 6,200,
`precompute-propagate` 827 / 6,662, `optimize-instructions` 440 / 3,700, `rse` 409 / 2,908,
`code-folding` 189, `dce` 4. **The residue is local plumbing and lowering shapes, not
functions**, and a per-function diff against binaryen's text (the scripts and tables:
`.todo/artefacts/791-module-level-slack-globals-types-data-hooks/`) names the shapes. A
census over the flat `wasm-tools print` of the Worker / `zlib`:

| shape | Worker | `zlib` | per site |
| --- | ---: | ---: | --- |
| `car`/`cdr`: `local.get; local.set t; local.get t; ref.is_null; if ... end` (every site a fresh temp) | 8,141 | 344 | 17-21 B -> **landed**, `.kb/cons-access-runtime.md` |
| `local.set N; local.get N` (a `tee`) | 9,700 | 869 | 2 B -> **landed**, "The adjacent-instruction peepholes" |
| `local.get A; local.set B; local.get B` (a copy into a temp) | 4,181 | 342 | 4 B + a local -> **landed**, "The single-use local" (the expression of length one) |
| `if (result eqref) call err else nil end; ref.is_null` (a predicate boxed into t/nil and tested -- the `call` is `_t_sym`, not an error) | 3,304 | 303 | ~8 B -> **landed**, "A test is compiled as a test" |
| a pure value then `drop` (`ref.null eq`, a constant, a `local.get`) | 1,416 | 267 | 2-3 B -> **landed** |
| `local.tee N; drop` (a statement `setq`) | 1,197 | 127 | 1 B -> **landed** |
| `br 0; end; unreachable; end` (a non-terminating loop's tail, written by the FOLD) | 1,488 | 185 | 1 B -> **landed** |
| the `&key` prologue's per-keyword `do` loop (`LambdaLists.keyCellScan`) -- 106 + 10 of the Worker's sites were the prologue, the rest other list loops | 701 | 22 | ~140 B -> **landed**, `.kb/lambda-lists.md` |
| a char literal built, cast and read back in a `char=` chain (`struct.new $char; ref.cast; struct.get`) | 228 | 14 | ~10 B -> **landed**, "A char comparison pair" |
| a boxed variable built empty then `struct.set` (`ref.null; struct.new; local.set`) | 204 | 45 | ~8 B -> **not a shape**: by type the Worker's are 69 closures with a null environment and 50 `(cons x nil)`; 6 (Worker) / 32 (`zlib`) are cells, all the `labels` case that needs the cell first |
| a `local.*` immediate of 128 or more (a 2-byte index; `Ctx.allocTemp` never recycles) | 11,646 | 240 | 1 B -> **landed**, "The local renumbering" |
| `br_table` labels naming the default arm (a sparse arity ladder) | 6 | 3,785 | 1 B -> **landed**, "Sparse arity ladders" |

The first row is `.kb/cons-access-runtime.md`; the four marked **landed** first are the peepholes
("The adjacent-instruction peepholes" above, `.todo/798`); the lowering shapes landed 2026-09-13
as `.todo/799`, one section each above ("A test is compiled as a test", "The local renumbering",
"Sparse arity ladders", "A char comparison pair", "A conditional in statement position", and
`.kb/lambda-lists.md` for the `&key` prologue), and the single-call-site move is landed too
("The single-call-site move" above -- where the reading of the `zlib`/Worker inlining share is
corrected). After the first row landed: `zlib` 90,874 (residue 18,542), Worker 815,414 (residue
182,697 -- the same bytes as before, so the 101 KB was outside binaryen's reach: it does not
outline). After the peepholes: `zlib` 88,315, Worker 795,062. **After the lowering shapes**
(`--optimize=size`, raw / gzip): hello-clack Worker 716,057 (-9.9%) / 194,305 (-7.2%), `zlib`
78,330 (-11.3%) / 28,452, httpbin Worker 154,998 (-10.2%) / 53,809, hello-tiny-routes 752,239
(-10.4%) / 204,488, `hello_world` and `pi_approx` byte-identical. Three of the census's rows read
their bytes wrong, and each correction is in its section: the "assertion" was the `t` literal, the
701 "keyword loops" were 116 keyword sites, and the "boxed variable built empty" was closures and
conses -- the census names WHERE to look, and the shape is only known once the site is read.

**A residue is a property of the real output, never of a spike**, and a pass ranked by
running it ALONE says how much binaryen's writer costs as much as what the pass does. The
"~114 bytes of tidying" this section first estimated came from `-Oz` on a hand-spiked
1,090-byte module; the corpus said 19-33% of every module.

## The funcall-dispatch gate (what makes `--optimize` reach library code)
**A function gets an arity-dispatch case, and a `_lookup` registry row, only when the program can
actually reach it as a function VALUE.** Without this the shakers are nearly inert on any program
that loads a library: the ladders `call` every registered function.
`Wasm/JvmLispCompiler.dispatchableFuncIds` compute the set;
`WasmRuntimeBuilder.buildDispatchBody` / `JvmRuntimeBuilder.buildDispatchMethods` filter their
targets by it, and the registry (the WASM data blob / `JvmEvalRuntimeBuilder.lookupSegments`)
filters its rows by the SAME set -- **computed together so they cannot drift**: a row whose funcId
has no case would resolve and then fall through to the ladder's default arm.

**Source 1, `Ctx.valueFuncIds`** -- the funcIds Pass 2 actually materialized as a closure
(`Wasm/JvmFunctionFormCompiler` for `#'name`, `Wasm/JvmLambdaCompiler` for every `(lambda ...)`
value, `WasmAsyncEmit`'s waiter closure). Collected DURING emission, not from a pre-scan, which is
the whole point: a `#'identity` a macro synthesizes in Pass 2 is invisible to any source scan.
**TRAP: `WasmAsyncEmit.freshCtx` rebuilds a `Ctx` field by field** and also builds the SYNCHRONOUS
top level; omitting `valueFuncIds` there silently lost every closure the top level makes and
`(funcall f 1)` trapped. **Any module-wide MUTABLE `Ctx` field must be listed there.**

**Source 2, the names a runtime SYMBOL designator can resolve** (on WASM live when the registry is:
`usesEval || usesRuntimeDesignator || usesApplyRuntime`, `.kb/eval-runtime.md`). `_lookup` matches
interned offsets (WASM) / string constants (JVM), so a row is reachable only when the program
already put that exact name there for another reason. The probe set is **`Ctx.spelledLiterals`** --
every spelling Pass 2 emits as a runtime VALUE, recorded in `Wasm/JvmEmitHelper.compileStringLiteral`
exactly as `valueFuncIds` records closures (it used to be the whole string table / constant pool,
arming rows for slot names, the printer prologue's `"-"`/`"/"` and the JVM layout tables).

- **Seven spellings** are tried: canonical; the `::`->`:` alias row's; the bare member name after
  the last colon; the FRAMED string-literal spelling of the full name and of the member (`"NAME"`,
  quotes included -- clack's handler discovery spells `"RUN"`, not `RUN`); and the two package-less
  SYMBOL spellings whose name is the member, `:member` and `#:member`. **The seven live in
  `compiler.DesignatorSpellings`**, not once per backend -- a spelling added to one and not the
  other is precisely the "resolves on the JVM, not on WASM" divergence. `of(name, symbolBuilders)`
  is the probe order, `anySpelled` the decision, `matched` the report (`DesignatorSpellingsTest`).
- **A literal the compiler SYNTHESIZES as pure result data is exempted through `%unspelled-quote`**
  (`LispNames.UNSPELLED_QUOTE`): compiles exactly like `quote` on every backend but records
  nothing. Two emitters use it: the generated `:reader`/`:accessor` body's slot name
  (`LispMacroExpander.checkedSlotRead`; a user-written `slot-value`'s name keeps the plain quote)
  and `expandClassDesignator`'s type-name results inside `%no-applicable-method`. This is the
  symbol half of **no generated literal may spell a defun name exactly**. Consequence:
  `(funcall (cell-error-name e) ...)` / `(funcall (type-of x) ...)` stop resolving like any forged
  name, loudly; `--dynamic` restores them.
- **The four widened spellings apply only while the program contains a symbol BUILDER at all** --
  `RuntimeNameProducers.anySymbolBuilder`: `intern`, `find-symbol`, `make-symbol`,
  `uiop:symbol-call`. `make-symbol` is the safe over-approximation (its product can never match a
  row on WASM, but the JVM registry compares string VALUES). Two of the compiler's own emissions
  are shape-exempt, provably unable to produce a FUNCTION designator: `(intern X :keyword)` and the
  injected `(defun %slot-name-key (n) (intern (symbol-name n)))`.
- Pins: `widenedProbesApplyOnlyWithASymbolBuilderPresent`,
  `theCompilersOwnInternShapesDoNotWidenTheProbes`, `aFramedSpellingWithoutABuilderDoesNotHoldARow`,
  `aCompilerInternedTableNameDoesNotArmTheDispatchGate`,
  `aGeneratedReaderBodySlotNameDoesNotArmTheDispatchGate` and their `JvmClassShakerTest` twins.
  Debug: `-Drontolisp.debug.dispatchgate=true` prints `name-armed <defun> by <spelling>` and names
  the operator that turned the gate off.

**The gate turns itself off entirely** under `--dynamic` and whenever
`compiler/RuntimeNameProducers.anyNameResolvable` holds -- the program contains a DATA EVALUATOR
(`eval`/`read`/`read-from-string`/`load`, or the injected `~/name/` renderer arm
`FormatRenderer.FUNCTION_DESIGNATOR`). That class is shared by both backends on purpose. **Without
the data-evaluator bail the gate is not sound, and the failure is a trap rather than a diagnosis**
-- the clack path this exists for (`clackup` -> `find-handler` -> `find-package-or-load` ->
`(find-symbol "RUN" pkg)` -> `apply`) resolves through the framed-string probe.

**The symbol BUILDERS no longer bail** (they used to turn the gate off wholesale). The split is
sound against the probes: a symbol a builder produces is built FROM A STRING, and any string the
program holds is a compile-time constant the widened probes already read in every spelling the
lowerings emit. What escapes them is a name assembled out of COMPUTED pieces -- verbatim
`LibraryDefunPruner`'s carve-out (the ordinary undefined-function error; `--dynamic` restores late
binding). Two rontolisp-owned blockers had to be retired first: the runtime `format` renderer's
`%fmt-function-designator`, now a separately-injected arm (`.kb/format.md`), and the generated
slot-name fold's `intern`. Judging the `intern` ARGUMENT shape was tried and REJECTED: it shrank
nothing and broke `internIntoALiteralPackage`, because the two-argument lowering folds the literal
into the qualified symbol before either probe sees it. Tests:
`internDoesNotHoldTheFuncallDispatchGateOpen`, `internDoesNotHoldTheDispatchGateOpen`,
`keywordInternStaysInternedInAGateShakenModule`, `componentCoreIsTreeShakenUnderOptimize`,
`optimizedServeComponentStillServesUnderWasmtimeServe`,
`FormatRendererTest.theFunctionDesignatorArmIsInjectedOnlyForAProgramThatSpellsTheDirective`.

### A designator the compiler can READ never enters `valueFuncIds`
`Wasm/JvmDesignatorCall` is the one decision -- `compiler.FunctionDesignators.literalName` (a
literal `#'name` / `'name`, `normalize`d) plus the backend's registry at the arity in hand -- and
the six sites that ask it are `funcall`, `mapcar`, `mapc`, `mapcan`, `reduce` and `sort` on BOTH
backends. A resolved site emits the direct call its head-position spelling would have emitted; the
funcId is never materialized, so it joins neither `valueFuncIds` nor the ladder. The direct call IS
the ladder case's instruction sequence (`WasmRuntimeBuilder.buildDispatchBody` pushes the closure's
env, the arguments and, for a variadic target, the surplus linked into the rest list, then `call`s;
`JvmRuntimeBuilder.renderCase` the same minus the env).

**Deliberately NOT resolved**, all keeping the dispatcher: a computed designator; a name no registry
answers (a car/cdr composition, a `--dynamic` deferral); and **an arity the callee cannot take** --
the one not to "fix", because the arity contract of these operators is a RUN-time one, so
`(mapcar #'cons '(1 2))` must fail where it failed before (a WASM trap, the ladder's default arm on
the JVM) rather than becoming a compile error. On WASM the resolution is asked BEFORE the dispatch
ceiling check (`WasmFunctionCallCompiler.compileFuncall`), because a ceiling on the dispatchers
cannot bind a call that uses none. Lisp-2 shadowing needs no handling: `flet`/`labels` rewrite both
`(f x)` and `#'f` into their binding VARIABLE first (`.kb/flet-labels.md`).

What moves bytes is what the ladder stops fanning out to. Honest cost: a variadic callee reached
wider than its required count links the rest list AT the call site now, so the no-flag JVM class
grows slightly while `--optimize` pays back; the JVM pays more for a ladder case, so dropping the
value is worth about ten times more there than on wasm.

**A gate test's scaffolding is affected, and silently.** `(print (funcall 'f))` used purely to keep
a ladder emitted is now a direct call, so such probes must funcall a COMPUTED designator
(`(funcall (car (list #'f)))`). **Any future test about what the ladders keep alive owes the same
care.** Pins: `aLiteralDesignatorSiteBuysNoLadderCase` / `aLiteralDesignatorSiteBuysNoDispatchCase`,
`literalFunctionDesignatorsCompileAndRun` / `compileAndRunLiteralFunctionDesignators`,
`compileAndRunLiteralDesignatorOfTheWrongArityKeepsTheDispatcher`, ci-spec
`literal-function-designators-answer-like-computed-ones`.

### A designator BOUND to a temp is not a value either
Every expander that NAMES a designator to avoid re-evaluating it undoes the section above
(`expandMap`'s `(let ((__map_fn #'identity)) ...)`, reached by every `coerce` lowering;
`expandMapFamily`; `expandEverySomeFamily`). `compiler.LetBoundDesignators.propagate` closes it in
ONE place: **a `let` binding whose init is a literal designator naming a registered function, and
whose every use in the body is a function-designator position, is propagated into those uses and
the binding dropped.** `Jvm/WasmLetCompiler` call it on the way in, so a hand-written `let`, the
nested lets `let*` lowers to, and every macro-generated binding go through one rule. Doing it in
the backends rather than the expanders keeps the interpreter out of it: leaving the literal AT the
funcall site would evaluate the designator once per element there, and a designator naming an
UNDEFINED function would stop signalling over an empty sequence.

**The safety argument is a COUNT, not a walker.** The pass certifies the occurrences it understands
(the designator argument of the six resolved operators) and separately counts EVERY occurrence of
the name with a deliberately shape-blind scan -- quoted data, binding lists, dotted tails and all --
rewriting only when the two agree, which lets the substitution be shape-blind too. Anything the
certifying walk does not understand keeps the binding: a plain VALUE use, a `setq`, an inner
binding or lambda parameter of the same name, a `(funcall f ...)`-shaped datum. The walk stays
opaque at data-carrying heads (`quote`, `declare`, the `def*` family, a `case` clause's keys, a
lambda list): descending somewhere non-evaluated is free, but CERTIFYING something non-evaluated
would corrupt it.

**Three guards beyond the count**: a SPECIAL name is never dropped; a name bound twice in the same
binding list is left alone; and the designator must name a function the backend's registry answers
(`ctx.functions`) -- what makes the substitution value-identical (both spellings compile to the
same static funcId, `--dynamic` included) and what keeps `#'cadr` out, whose value is a car/cdr
composition SYNTHESIZED per site. The WASM fusion registry is untouched by construction (it
registers a `__FLET*` binding whose init is an integer-tree LAMBDA).

**Deliberately not listed**: the certified positions are the six operators the backends RESOLVE.
`map`/`maplist`/`mapcon`/`mapl`/`every`/`some` bind the designator in their own expansion, so a
LITERAL written there is taken anyway; what stays outside is a literal reaching them through a
HAND-WRITTEN variable. Adding those operators' designator slots to `designatorSlot` is the lever --
every slot in that table is a claim about an expander that has to keep being true. Pins:
`aDesignatorBoundToATempIsTheSameDirectCall` on both shaker tests (the bound spelling is the
written-out literal's own module, byte for byte), each paired with the same binding plus a VALUE
use; `LetBoundDesignatorsTest`; the ci-spec case grew the three shapes that KEEP the binding --
value use, `setq`, shadowing.

## cl-ppcre, decided
Adding tiny-routes to a Clack reactor nearly triples the module, and the extra is **cl-ppcre, its
only dependency**: a route template is compiled to a scanner at RUN time (`path-template.lisp` even
builds one at LOAD time, `*path-token-scanner*`), so the whole regex pipeline is genuinely
reachable and the shaker is right to keep it. **Quickloading cl-ppcre alone puts the module in EH
mode, and scanner building is live even for a single literal `scan`** -- the whole engine cost IS
the anchor, so the shaking levers cannot pay.

1. **TAKEN -- the Worker examples build at `--optimize=size`** (all four wasm-GC `build.sh` lines;
   `hello` stays `--optimize` because it is `--no-gc`, where the level is a no-op).
2. cl-ppcre's eight `define-compiler-macro`s never fire on the routing path, and firing would not
   shrink anything -- the scanner BUILDER still ships (`.kb/compiler-macros.md`).
3. A leaf-module substitution of `path-template.lisp` (`ShimLibraries.leafModuleForms`) was
   REJECTED AS A DEFAULT (it breaks `:regex t` and silently changes keyword-template semantics) and
   shipped as the opt-in system **`tiny-routes/lite`** (`.kb/asdf.md`).
4. **Loaded-but-unreferenced cl-ppcre is anchored by its CLOS surface** -- LANDED: the pruner's
   CLOS candidates + per-method gates (`.kb/library-defun-pruning.md`) collect ~30% out of every
   clack Worker. What the AST argument cannot touch is the `let`-over-`defmethod` root.
5. Splitting the parse half from the match half is not a plan: a scanner is a tree of closures
   closing over each other, and `load-time-value` runs INSIDE the module at load time.

On a USING app the defun-level pruner leaves ~zero residual (what stays is CLOS-anchored), and
CLOS-aware shaking cannot pay -- the engine's 27 defgenerics ARE the build pipeline and every
parse-tree node class is instantiable from `create-scanner`. **What CAN move such a module is code
DENSITY, not shaking**: the `%seq-to-*` conversion trio (`.kb/seq-conversion-runtime.md`), then the
shared `%no-applicable-method` defun and the variadic dispatchers' ALIGNED apply fast path
(`.kb/clos.md`). Compile-time lowering of literal regexes stays un-taken: one dynamic regex brings
the whole engine back silently.

**A correctness hole these probes surfaced, distinct from size:** a `return-from` crossing a lambda
boundary skipped the special-binding restore, which corrupted cl-ppcre's own scanners (a
zero-register scan after a failing register-regex loop returned stale `*reg-starts*`; interpreter
correct, JVM + both wasm-GC wrong). Closed 2026-09-12: a special `let` is now an unwind-protect
region on both compile paths (`.kb/dynamic-special-variables.md`), at +4.1% on cl-ppcre's `.class`
and +1.0% on its wasm module.

## JVM
`am.ik.jvm.JvmClassShaker` runs at the end of `JvmLispCompiler.compile`: parses the finished class,
builds the call graph from `invoke*` constant-pool immediates, keeps methods reachable from `main`
(plus `_apply` as an extra root when the program uses `java:` interop -- the embedded bridge looks
`_apply` up REFLECTIVELY, an edge bytecode cannot show; under `--no-main` there is no `main` root at
all), drops unreachable methods and any static field only they referenced, and **compacts the
constant pool**, rewriting every CP index immediate in the surviving bytecode in place. Sizes never
change (u2 stays u2; an `ldc` u1 index only shrinks because compaction preserves order), so
exception-table pcs and switch padding stay valid, and no method renumbering is needed since JVM
methods are referenced by name. The shaker throws on anything it does not recognize (unknown
opcode/constant tag, any attribute other than a single `Code` per method).

**A `rontolisp:jvm-export` wrapper is a third liveness source**, next to `main` and the
dispatchable-funcId set: every export's Java method name joins the roots (its caller is Java code
the bytecode cannot show), and the wrapper's `invokestatic` keeps the target defun's graph. This is
what makes a compiled LIBRARY survive `--optimize`. The wasm side has no equivalent root because a
wasm export IS a module export the shaker already treats as a root; an export root keeps a method,
not a registry row. Mechanics and pins: [jvm-export.md](jvm-export.md).

Tests: `JvmClassShakerTest` (structural + behavior, incl. the `_apply` root) and
`JvmClassShakerCorpusTest` (the whole `ci-spec.yaml` corpus with `--optimize`, asserting shrink +
identical run output -- the decoder-completeness guard, like `WasmTreeShakerCorpusTest`).
Limitations: README "Optimize".
