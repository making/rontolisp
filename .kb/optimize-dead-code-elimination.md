# `--optimize` (levels; dead-code elimination, WASM + JVM)

On by default in the CLI (`--optimize[=LEVEL]`). `WasmLispCompiler(dynamic, component, noWasi,
optimize)` / `JvmLispCompiler(className, dynamic, optimize)` / `NoGcWasmCompiler(optimize)` all
take a `compiler.OptimizeLevel`.

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
  every tag (the tag section is copied verbatim), the global section's value types and
  initializers, and every type immediate in a surviving body (GC-op `typeidx`es,
  `ref.test`/`ref.cast`/`ref.null` heap types, block types, locals).
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
  only reader of the cut bytes must tolerate them reading as zeros. Row and bytes fall together;
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
type rather than failing -- so a new entry is earned by checking every backend's emission for that
operator. Pinned by `staticallyTypedPrintArgumentsPrintWhatTheValueDispatchWouldHave`, ci-spec
`statically-typed-print-arguments`.

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
boundary skips the special-binding restore, which corrupts cl-ppcre's own scanners (a zero-register
scan after a failing register-regex loop returns stale `*reg-starts*`; interpreter correct, JVM +
both wasm-GC wrong). Until it is fixed, the interpreter is the only backend that runs the real
engine's scan SEQUENCES per the standard.

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
