# `--optimize` (levels; dead-code elimination, WASM + JVM)

On by default in the CLI (`--optimize[=LEVEL]`, absent flag = `DEFAULT`, declined with
`--optimize=off`). `WasmLispCompiler(dynamic, component, noWasi, optimize)` /
`JvmLispCompiler(className, dynamic, optimize)` / `NoGcWasmCompiler(optimize)` all take a
`compiler.OptimizeLevel`.

## The levels

**Invariant: the level-less compiler constructors are `DEFAULT`, not `NONE`.**
`new WasmLispCompiler()`, `new JvmLispCompiler(name)`, `new NoGcWasmCompiler()` compile the
same shape the flagless CLI does. The browser playground
(`RontoPlayground.compileJvm`/`compileWasm`, `src/web/java`) calls exactly those and has no
flags, so a `NONE` default there is one nobody can override. `WasmLispCompiler`'s internal
`Ctx.optimize` and its nested builder field say `DEFAULT` for the same reason (both are
overwritten by every constructor and only asked `prefersSizeOverSpeed()`). **A test that
asserts on the UNOPTIMIZED shape must say `OptimizeLevel.NONE` out loud.**

**Invariant: the bare `--optimize` is `DEFAULT` and emits exactly the bytes it always did**
-- it is in every doc page, `.kb` passage, CI job, example and the README, so it may not be
redefined. **An ABSENT `--optimize` is `DEFAULT` too; `NONE` is spelled `--optimize=off`.**
`OptimizeLevel.parse(null)` answers `DEFAULT`; every level has a spelling. `off` promises
byte-identity with what a flagless build used to produce (it gates the same six
`eliminatesDeadCode()` sites). A near-miss spelling (`none`, `no`, `high`) is an
`IllegalArgumentException` naming the accepted set, never a silent `DEFAULT`.

| spelling | `eliminatesDeadCode()` | `prefersSizeOverSpeed()` |
| --- | --- | --- |
| `--optimize=off` — `NONE` | no | no |
| (absent), `--optimize`, `--optimize=default` — `DEFAULT` | yes | no |
| `--optimize=size` — `SIZE` | yes | yes |

Those two predicates ARE the level:
`OptimizeLevelTest.everyLevelIsDistinguishableAndSpellable` fails if two levels answer both
the same way -- **do not ship a level that is an alias of another**. There is no `high`:
nothing is held back for being too aggressive.

Pins: `OptimizeLevelTest.theBareFlagIsTheDefaultLevel` /
`theAbsentFlagIsTheDefaultLevel` / `theOffSpellingIsNoOptimizationAtAll`,
`CliOptionsTest.theBareFlagKeepsItsEmptyValue`,
`JvmLispCompilerTest.theFlaglessBuildIsTheOptimizedOneAndOffIsTheWayBack`.

**A VALUE, not a second flag**: `--optimize-size` / `-Os` / `--no-optimize` beside
`--optimize` cannot say how the two relate. CLI consequence: `--optimize` stays in
`CliOptions.noValueKeys` and the parser learned the `--key=value` form (`CliOptions.build`)
-- moving it out of that set would make `rontolisp app.lisp --optimize -o out.wasm` read
`-o` as the level.

### What `SIZE` declines

The speed-for-size trades, all on at `off` and `default` alike:

- **wasm-GC (Preview 1 and `--component`)**: integer expression-tree fusion
  (`.kb/wasm-int-fusion.md`; a fused site emits its tree TWICE, raw plus generic fallback)
  and unboxed dual-representation locals (`.kb/wasm-unboxed-locals.md`). One predicate
  switches both: `WasmIntFusionCompiler.speedTradesEnabled(ctx)`, read at the three fusion
  entry points and at `WasmLetCompiler`'s eligibility scan.
- **JVM**: typed numeric loops (`.kb/jvm-typed-loops.md`; a `dotimes` over packed float
  arrays emits its body up to three times) and integer fusion + unboxed dual-representation
  locals (`.kb/jvm-int-fusion.md`; every outlined `_fx$N` carries its tree twice).
  `Ctx.typedLoops` and `Ctx.intFusion`, both `!prefersSizeOverSpeed()`.
- **`--no-gc`** accepts the level and emits byte-identical output
  (`NoGcWasmCompilerTest.theSizeLevelIsADocumentedNoOpOnThisBackend`).

A program without those shapes is byte-identical across levels
(`JvmLispCompilerTest.theSizeLevelChangesNothingWithoutASpeedForSizeTrade`); one with them
is smaller and slower at `=size` (`typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem`,
`fusedIntegerExpressionTreesMatchTheGenericPath`). The level is accepted everywhere rather
than rejected so one build script can pass it for every target.

Shape of the trade: the SIZE win barely varies (-16% to -24% across every program measured,
library-heavy or not) while the run-time price varies more than thirtyfold (+9% to +280%),
because only INTEGER arithmetic fuses -- a `vec:`/`linalg:` kernel pays it on loop indices,
an ironclad round on every operation. A program that is not integer-hot gets the size win
nearly free.

**Why the two wasm trades are ONE level, not two switches**: the "fusion off / unboxed
locals on" configuration is DOMINATED on both axes -- larger than `=size` and necessarily
slower than `=default` (an unboxed local's whole payoff is being read raw inside a fused
tree; with fusion off every assignment bails into the boxed shadow and every read goes
through `_ub_read`). A configuration nobody can want does not deserve a spelling.
`-Drontolisp.debug.norawlocals=true` still switches the unboxed locals alone, for A/B
profiling.

## Before the shakers: a `typecase` clause no call can select

Both shakers are name reachability (wasm over `call` immediates, JVM over method
references), so neither can see that a `typecase` clause is dead because of what the CALLER
passed. Standing case: clack's `clackup` dispatches `((or pathname string) (eval-file app))`
vs `(otherwise app)`, and every Worker calls `(clack:clackup #'app ...)` -- yet
`CLACK::%LOAD-FILE`, `CLACK:EVAL-FILE`, `PROBE-FILE` and the `NoWasiFilesystemStubs` error
string rode into every clack Worker.

`compiler/DeadTypeBranchPruner` DELETES such a clause from the AST before Pass 1, so the
shakers drop what it held by ordinary reachability. Gated on `eliminatesDeadCode()`, called
from `JvmLispCompiler.compile` (right after `flattenTopLevel`) and `WasmLispCompiler.compile`
(**after** `NoWasiFilesystemStubs`, which closes the funcall-dispatch gate on a clack Worker
-- the pruner declines while the gate is open). Shares its decision,
`ArgumentShapes.maySatisfy`, with the `--no-wasi` build warning
(`.kb/wasm-export-no-wasi.md`).

Deletion is the ONLY rewrite, so no evaluation-order reasoning is needed. An
`(if (typep x 'pathname) ...)` is left alone even though the warning pass skips it --
rewriting a test means deciding what happens to the operand's evaluation.

Soundness rules:

- a parameter's shape is the JOIN over EVERY call site -- one `(clackup "app.lisp")`
  anywhere keeps the clause;
- a name taken as a VALUE (`#'clackup`, or any occurrence inside quoted data) has no known
  call sites, so its parameters state nothing;
- a name with two definitions, or one that is also a `defmacro`/`defmethod`/`defgeneric`, is
  left alone; a user macro's ARGUMENTS are rewritten from the top-level scope;
- the pass declines entirely under `RuntimeNameProducers.anyNameResolvable`.

Over-COUNTING a call site is harmless (it widens the join toward UNKNOWN), so the call scan
is deliberately dumb: any cons whose head names a known function counts. Missing one is the
unsafe direction, covered by the escape rules.

Shapes flow through `let`/`let*`/`do`/`do*`, a `lambda`'s parameters (unknown) and `flet`
locals, whose parameters are joined over call sites in the `flet` body (clack's `typecase`
is inside a local). A `labels` local stays unknown: its siblings can call it.

Pinned by `DeadTypeBranchPrunerTest`. Note `hello-ningle` does not move: yason puts a `read`
in the program, so `anyNameResolvable` holds and the pruner declines -- while the build
WARNING still goes quiet there, because that walk is per-call-chain and needs no
whole-program agreement.

## A dispatcher branch no call site can select

The defgeneric twin: a generic's dispatcher is the generated arm list (`.kb/clos.md`), so
`DeadTypeBranchPruner` cannot see that a METHOD is dead. zlib's one entry is
`(chipz:decompress nil 'chipz:gzip <ub8-vector>)` and the artifact carried all 18
`%DECOMPRESS` variants plus a 2,838-B dispatcher.

`compiler/GenericDispatchNarrowing` (the `macro.DispatchNarrower` hook) runs inside
`expandTopLevelDefinitions`, after the walk registered every method and just before the
dispatcher slots are filled: it joins argument shapes over every call site of each generic
(the `ArgumentShapes` lattice, which gained a VECTOR shape --
`make-array`/`vector`/`make-string`/`subseq`/`copy-seq` return rules), and
`generateDispatcher` omits each branch (method, meet, exact-tag alike) whose specializer
vector no site's shapes may satisfy. The method-body defuns are still emitted; the SHAKERS
drop the unreferenced ones. Only an optimizing, early-bound compile narrows: the
interpreter, `ShadowedBuiltins`' regenerated comparison shape, `--dynamic` and every
`NONE`-level build pass a null narrower and stay byte-identical.

**Satisfiability** leans permissive: DEFAULT and EQL specializers always keep a branch; a
CLASS specializer is satisfiable only by INSTANCE/UNKNOWN shapes; a TYPE name defers to
`maySatisfy`, except that INSTANCE satisfies ANY type name (a gray-stream instance IS a
`stream`; a struct-name specializer is undecided in the lattice, so alone it never narrows).

**Escape rules** mirror the funcall-dispatch gate's probes: `#'g` outside the direct
funcall/apply target position, `g` in quoted data or in a user macro call's arguments, and
-- only while a symbol BUILDER is present -- any string/keyword literal spelling `g`'s
member name. `anyNameResolvable` or any async operator declines the whole analysis, and a
program that appends registry-derived runtimes after the slot fill (the metaobject/MOP
family, `#'make-instance` as a value, symbol-function forwarders) stands the narrower down.
Excluded wholesale (call sites synthesized during Pass 2): `cl`-symbol names,
accessor/writer generics (`structAccessors` entries and slot base names), `%`-internal
names, gray-stream packages, short-form combinations, any generic with a nested method
defun.

**The liveness fixpoint**: only two unit kinds may be dead -- the method-body defuns of
narrowable generics, and plain defuns whose every head-position reference sits inside such
bodies -- and only those get parameter-shape joins. Every other defun is live from the start
with UNKNOWN parameters. An `apply` with a literal target is read as a CALL SITE (leading
arguments only), not an escape.

Pinned by `JvmClassShakerTest.anUnselectableGenericBranchAndItsMethodShakeOut` (structural +
behavioral: the narrowed class runs; the string-site / value-escape / no-flag variants keep
the branch), its `WasmTreeShakerTest` twin, and a generic-with-dead-branch program in
`optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo`.

Re-evaluation triggers: (1) async programs are declined wholesale -- serve/fetch components
are the biggest CLOS-heavy artifacts left, and narrowing them needs the async lowering's
synthesized closures attributed; (2) an EQL specializer is never ruled out; (3) a
struct-name specializer is undecided in the lattice -- deciding it would let a SYMBOL/NULL
argument kill struct branches directly.

## The wasm tree shaker

`am.ik.wasm.WasmTreeShaker` (language-independent) runs on the finished **core module**
bytes in `WasmLispCompiler.compile` just before returning -- **including under
`--component`**, right after `WasmImportInjector.inject` and before the component wrapper is
built. It parses the sections, builds a call graph from the actual `call` (and `ref.func`)
immediates, computes reachability from the roots (exported functions + `_start`/start
section), drops the rest **including unused WASI function imports**, and renumbers every
surviving function reference. Reachability is exact: when `eval`/`load`/`apply` is used the
dispatch bodies contain real `call`s to every registered function. It renumbers **function**
AND **type** indices (memory and global sections keep their own index spaces). **This is the
one place the fixed-index invariant is deliberately broken**, and only because every
reference site is rewritten in lockstep.

### Type section

The same forward walk records every **type** immediate, so unreferenced definitions go and
survivors renumber.

- **roots** -- the function-section entry of each surviving defined function, each surviving
  function import's `typeidx`, every tag (the tag section is copied verbatim, so its type is
  always live), the global section's value types and initializer expressions, and every type
  immediate in a surviving body: GC-op `typeidx`es, `ref.test`/`ref.cast`/`ref.null` heap
  types, block types, locals' declarations;
- **edges** -- type-to-type references inside the type section: a struct/array field's
  `(ref null $t)`, a func type's params/results, a `sub` clause's supertypes;
- **a `rec` group is atomic.** Its members take consecutive indices and its structural
  identity under wasm-GC canonicalization is a property of the whole group -- which is what
  makes `ref.test $limbs` (`array (mut i32)` inside the `[limbs, bigint]` pair) discriminate
  against `TYPE_I32ARR` (the same `array (mut i32)` inside the `[i8arr, i16arr, i32arr]`
  triple). Naming one member keeps them all.

Encoding: a `typeidx` immediate is an unsigned LEB; a `heaptype`/blocktype is a signed s33
whose NEGATIVE values are the abstract shorthands (`eq`, `i31`, ...) and name no definition
-- `WasmTreeShaker.RefKind` carries which, and only non-negative heap types are rewritable.
Two decoder gaps throw rather than emit a corrupt module: a `table`/`element` section, and
the four GC ops carrying a `dataidx`/`elemidx` (`array.new_data` & co.), since the pass DROPS
data segments. The backend emits none of them.

Pinned by `WasmTreeShakerTest.dropsTypesTheSurvivorsNoLongerName`,
`keepsTheTypesAnEhModeModuleStillNames`, and `WasmTreeShakerCorpusTest` (`wasm-tools
validate` over the whole `ci-spec.yaml` corpus in both WASI modes).

### Owned data segments

The data section is copied verbatim EXCEPT segments the compiler declares as owned:
`WasmTreeShaker.OwnedDataSegment(segmentIndex, ownerFuncIndices)` names a segment whose bytes
are referenced exclusively by those functions, and `shake(module, owned)` drops it when every
owner is unreachable. **The shaker cannot verify the exclusivity claim** (a linear-memory
reference is an indistinguishable `i32.const`), so it is the CALLER's invariant. Today's only
claimants: the two Unicode case-fold range tables (~16.4 KB combined,
`WasmCaseFoldRuntimeBuilder`), each its own active segment at the addresses `appendBlob` gave
them, owned by `_char_upcase` / `_char_downcase` (`WasmLispCompiler.compile`,
"caseFoldSegments"; owner indices are shifted by the injected host-import count because the
shake runs after `WasmImportInjector`). Dropping leaves an all-zero hole nothing reachable
reads. Pinned by `WasmTreeShakerTest.orphanedCaseFoldTableSegmentsAreDropped`.

### String blob: droppable ranges

One segment holds the whole `StringTable` blob, so whole-segment ownership cannot express it:
the builtin WRAPPER bodies Pass 2a compiles intern their literals and the shaker then deletes
the wrappers, leaving the bytes. `WasmTreeShaker.DroppableDataRange(segmentIndex, start, end)`
is the sub-segment form: the pass cuts the range out and re-emits the segment as one active
segment per surviving run, **each at the address it already had**, so nothing relocates.

- **Candidacy is decided by OBSERVATION**: a range survives when any SURVIVING body (or a
  global initializer) holds an `i32.const` in `[address, address + length)` -- the HALF-OPEN
  interval. An unrelated constant landing in a range only KEEPS bytes (the safe direction). A
  declared-owner scheme was rejected: attribution would have to be right at all ~20
  `addString` call sites and at every emitter baking a cached `entry.offset()` into another
  body.
- **Half-open, not closed**: no emitter produces a bare one-past-the-end pointer (a body
  needs the base to read from; the only computed pointer in the backend is
  `WasmLiteralPrint`'s `framed.offset() + 1`, which is interior). The closed interval instead
  pinned every range whose end abutted a LIVE neighbour's start, and the blob is one dense
  run of abutting entries. Ranges with `end <= start` are skipped before the probe.
- **The COMPILER decides candidacy through a window**, because the scan cannot see a citation
  from DATA: `StringTable.attributing(true/false)` brackets passes 2a-2c (defun bodies, top
  level, lambda bodies), where a body's own `i32.const` is the only consumer.
  `StringTable.addBodyString` is the same grant spelled explicitly for runtime-body interns
  outside that window. **Interning with the window CLOSED retracts candidacy for good**,
  whichever came first -- which is what the instance-layout blob (built BEFORE Pass 2a), the
  `_lookup` registry rows, the `eval` special-form offsets and the reader's char-name /
  struct-directory tables rely on.
- **A baked packed-vector literal is a candidate too** (`StringTable.appendShakeableBlob`): a
  literal `(unsigned-byte 8|16|32)` table of 16+ elements goes into the same segment as raw
  little-endian bytes, its only reader being the copy loop's own `i32.const base`. A blob is
  never deduplicated, so one append is one range. **The loop must take its base from an
  `i32.const`, not from the load's memarg offset** -- the probe reads `i32.const` values and
  skips memargs, so a base hidden there would let the shaker cut a live table
  (`.kb/packed-integer-vectors.md`).
- **An OVERLAPPING entry is never a candidate** (`StringTable.addTailOf`): a layout's print
  name is interned as a view into its own `%class-` tag's bytes, and a cut range must own its
  bytes outright, so the reuse is declined when the container is already a candidate. Any
  future byte sharing owes the same rule.
- **TRAP: no generated string literal may spell a function name exactly.**
  `dispatchableFuncIds` reads a framed literal equal to a defun's name (or its bare member
  name) as something `intern` could hand to `funcall`, giving that defun a ladder case whose
  call edge keeps everything it reaches. Shortening a dispatcher's `"No applicable method: X
  on "` literal to just `"X"` cost **+11.5 KB on the hello-clack Worker** (the whole
  Gray-stream protocol came back), so the literal keeps its `" on "` separator
  (`LispMacroExpander.noApplicableMethod`). The gate now tells a generated SYMBOL from a
  user-written one (`%unspelled-quote`, below) and a framed STRING literal only probes while
  a symbol builder is present -- but with one present a generated string still arms.
- **The runtime intern table is handled structurally.** `buildInternBlob` (scanned by
  `_intern` on offset equality) cites every entry, which used to disqualify every candidate of
  a program with `usesIntern`. Now each candidate's 8-byte `(offset, length)` row is offered
  as a droppable range OF ITS OWN, probed on the STRING's interval -- the five-argument
  `DroppableDataRange` form, whose extra interval is a caller claim in the
  `OwnedDataSegment` sense: the only reader of the cut bytes must tolerate them reading as
  zeros. Row and bytes fall together, and `_intern` skips any row whose offset word is 0 (no
  real entry sits at address 0; without the skip a zero-length probe would match the first
  hole, and `'||` holds a live zero-length entry to diverge onto). Rows are sorted by string
  offset before the blob is built, so a run of dead entries cuts as one hole. A candidate
  first interned AFTER the blob snapshot has no row and offers only its bytes -- which is why
  `T` is interned before the snapshot.
- **The printer prologue is not exempt.** `StringTable`'s constructor interns 28 fixed
  entries -- `NIL`, the list punctuation `(` `)` ` ` `" . "`, `\n`, the `#<function>` /
  `#<FUTURE>` tags, the array prefixes `#(` `#` `A(` `#d(` `#f(`, the number pieces `-` `.`
  `/` `NaN` `Infinity` `E`, the `#\` prefix and the eight character names `Space`..`Rubout`
  -- plus `T`. Each is read by a RUNTIME body that bakes the offset as its own `i32.const`,
  so they are interned through `addBodyString` and stand or fall with those bodies (`\n` is
  the one a literal write reaches directly, `WasmLiteralPrint.emitNewline`). The retraction
  rule still covers the blob citers: the reader's char-name table re-interns
  `Space`..`Rubout` with the window closed, and `_lookup`/the intern blob do the same for `T`.

Pins: `WasmTreeShakerTest.dropsStringsOnlyDeadBodiesInterned`,
`anInterningProgramOffersPerEntryRangesRowsFallingWithTheirBytes`,
`dropsThePrinterPrologueNoLiveBodyReads`, `aBareOnePastTheEndPointerDoesNotKeepARange`, and
behaviorally
`WasmLispCompilerIntegrationTest.optimizedProgramKeepsEveryStringALiveBodyStillAddresses`
plus `optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo` -- a differential run, because a
wrongly-cut range prints garbage rather than trapping.

### The print family's literal fold

An output built-in whose argument is a LITERAL does not call the generic printer at all: the
text is a compile-time constant (every printer-control variable that could change it is
inert, `.kb/pretty-printer.md`), so `WasmLiteralPrint` interns the pre-rendered form and
writes it through `FUNC_WRITE_STR`, keeping the `*standard-output*` redirect semantics. The
`print-object` hook cannot fire here (inside `compilePrintOperator`'s print-object-free
gate). The point is reachability: the generic printer's integer arm alone pins the whole
bignum print chain (9 functions), the f64 renderer and the ratio accessors.

**It is the FAMILY, not `print`.** `print` / `prin1` / `princ` are one emitter
(`WasmPrintCompiler`, switched on readable-vs-display and newline-vs-not) precisely so the
fold cannot exist for one spelling and not the others; `write-string` / `write-line` fold a
string literal through the same helper. Folded types: string, fixnum, bignum, character,
ratio, float, `nil`, `t` -- everything whose `LispVal.print()` / `display()` the emitted
renderer reproduces exactly. Floats: the emitted printer selects the same Schubfach shortest
decimal as `LispDouble.print()` (`FloatText`, `.kb/format.md`), so a program whose only float
use is printing a literal carries none of the printer runtime.

The fold costs no data for strings: an escape-free readable form re-uses the literal's own
interned bytes, and a DISPLAY rendering points at the interior of the same framed literal
(`offset + 1`, `length - 2`), which is what `_princ_val` computes at run time.

**A COMPUTED argument reaches this fold when the computation is itself constant**
(`.kb/pure-builtin-fold.md`): a pure built-in over literal arguments is evaluated by the
compiler before either backend sees the print, so `(princ (* 6 7))` and
`(princ (length "Hello World!"))` compile to the same module as `(princ 42)`, byte for byte.

Pins: `WasmTreeShakerTest.everySpellingOfHelloWorldReachesTheSameFloor` (< 1 KB for every
spelling), its `…ComponentFloor` twin (< 2 KB, and the imported-interface set), and
`WasmLispCompilerIntegrationTest.aFoldedLiteralPrintsWhatTheRuntimePrinterWouldHave` (a
differential run of every folded literal against the same value passed through a function
parameter).

### The print family's static-TYPE shortcut

The same reachability argument covers an argument that is not a literal but whose TYPE the
compiler knows, and the numbers are bigger: what the generic dispatch drags in is every OTHER
renderer -- `_princ_val` itself, the character-vector normalizer it calls on every value
(`_charvec_to_str` plus its `_charvec_p` shape test) and the bignum / ratio / character /
cons / array printers reachable only from it. Two shortcuts in `WasmPrintCompiler`, both
above the literal fold:

- **`princ` of a certainly-STRING form compiles as `write-string`**
  (`compiler/StringValuedForms.certainlyString`, the predicate `write-string` already
  consults to skip `_charvec_to_str`). Same text, same returned object; works with an
  explicit stream because `write-string` takes one.
- **`princ` / `prin1` / `print` of a certainly-DOUBLE form unboxes the `TYPE_FLOAT` struct
  and calls `_print_f64_no_nl` directly** (`compiler/DoubleValuedForms.certainlyDouble`: an
  immediate literal-double argument of `+ - * /`, strictly narrower than the backends'
  `hasDoubleLiteral` f64-path predicate, so every accepted form is one the arithmetic already
  compiled to a boxed double). That IS the arm both dispatches take for a float, so output is
  identical by construction. Only for the hard-coded standard output: an explicit stream, or
  an active `*standard-output*` rebinding, renders to a string first.

Pinned by
`WasmLispCompilerIntegrationTest.staticallyTypedPrintArgumentsPrintWhatTheValueDispatchWouldHave`
and ci-spec `statically-typed-print-arguments` (all four backends).

**Re-evaluation trigger:** the two predicates are the whole risk surface -- a form wrongly
admitted prints as the wrong type rather than failing -- so a new entry is earned by checking
every backend's emission for that operator. Obvious next entries: the stream-carrying
spellings and `format`'s `~A` runtime path.

The last format spellings needed the lowering to stop hiding its constants: a literal
argument is no longer bound to a temp, and a `~a`/`~s` piece prints straight to the
destination instead of building a string first (`.kb/format.md`, "What the LITERAL path
lowers to").

### The `name` section is DROPPED, not copied

A `name` custom section maps **function and type indices** to names and this pass has just
renumbered both, so copying it through would describe the old shape. Dropped in
`WasmTreeShaker` (`SEC_CUSTOM`); every other custom section is index-free and still copied.
The rontolisp backend emits none, so this is invisible on a compiled core and decisive on the
hand-written WAT blobs the component wrapper embeds (the base adapter's name section alone is
1,438 B of its 3,953). Pinned by
`WasmTreeShakerTest.dropsTheNameSectionRenumberingHasInvalidated`.

### Identical bodies are emitted once

`am.ik.wasm.WasmBodyFolder` runs as the tail of `WasmTreeShaker.shakeWithRemap`, so every
shaken artifact gets it (GC Preview 1, the `--component` core, the shaken adapter,
`--no-gc`) at every `eliminatesDeadCode()` level: when two or more defined functions declare
canonically-equal types and carry byte-for-byte identical code entries, one body survives and
every `call`/`ref.func`/export/start/global-initializer reference is redirected to it. The
pass iterates to a fixpoint (folding twins can make their CALLERS byte-identical in turn),
then one more `dropUnreachable` collects orphaned type entries.

"Canonically equal" = same index, or same position in byte-identical `rec`-group entries
NEITHER of which references its own members: byte-identical entries name identical EXTERNAL
indices so their closures are equal under wasm-GC canonicalization, while inside a
self-referential group byte equality proves nothing. Matters only on `--no-gc` (one type
entry per function); the GC writer shares signature entries.

**The identity question (why folding is sound):** nothing in the emitted shapes can observe a
function's identity through its code index. A first-class function value is a closure STRUCT
whose dispatch id is plain `i32` data -- two folded definitions keep distinct funcIds,
`_lookup` rows and dispatch-ladder arms; the arms just `call` the same body. `eq` on WASM is
`ref.eq` plus char/bignum/string value fallbacks with NO closure arm
(`WasmEmitHelper.emitEqComparison`), so `(eq #'f #'g)` is NIL for two identically-bodied
defuns on every backend, fold or no fold -- pinned four-backend by ci-spec
`identical-function-bodies-keep-distinct-identity` and under `--optimize` by the fold program
in `optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo`. (`(eq #'f #'f)` already diverges
interpreter-vs-compilers -- fresh struct per `#'` -- and stays out of the pin.) `ref.func`
values have no comparator, multiple exports may alias one function index, and the component
wrapper reaches core functions by export NAME only.

Note: identical bodies compress well, so on small clack Workers the RAW win comes with a few
hundred bytes MORE gzip (renumbered call immediates compress a little worse); big modules win
on both axes. `size-report/results/` carries both columns.

Structural pins: `WasmBodyFolderTest` -- a module with N identical bodies emits one and holds
NO duplicate (type, body) pair at all (the fixpoint), on GC at both levels and on `--no-gc`.
Corpus coverage: `WasmTreeShakerCorpusTest` + `JvmClassShakerCorpusTest`. The
`-Drontolisp.wasm.debug-func-sizes` dump labels a folded group by its survivor.

**The JVM twin is measured, not implemented**: the zlib class at `--optimize` has 353 `Code`
methods, 48 duplicates, 8,331 B redundant (5.2% of code bytes). Folding there means
redirecting `invokestatic` constant-pool immediates and letting `JvmClassShaker` drop the
orphaned method, but JVM methods are reachable BY NAME (dispatch/eval roots, the reflective
`_apply` edge), so the survivor set needs its own soundness argument.

### The component WRAPPER: adapter + WASI surface

The wrapper used to be fixed cost (the whole 9-shim preview1 adapter and all eleven `wasi:*`
interface declarations). Both now follow the core through one chain, every link exact and
every step *observed* rather than declared (`WasmComponentBuilder.fixedSurface`, base variant
only):

1. the core's surviving `wasi_snapshot_preview1` imports (`WasmImports.functionFields`) are
   the adapter entry points that still have a caller;
2. `WasmExports.retain` makes exactly those the adapter's exports, and `WasmTreeShaker.shake`
   deletes everything unreachable from them -- **including the adapter's own `"w"` imports**,
   which is the measurement the rest reads;
3. the surviving `"w"` names select their `canon lower` / built-in entries out of one
   declarative table (`W_MEMBERS`), naming the WASI functions to alias (`BLOCK_FUNCS`) and
   the component types to declare (`PROJECTED_TYPES` / `DEFINED_TYPES`, closed over their own
   dependencies);
4. those name the interfaces, and `ComponentImportBlock.prune` cuts the import blob down to
   them -- closing over projection edges (`preopens` aliases `filesystem/types`' `descriptor`,
   so it cannot outlive it) and renumbering what is left.

**The projection closure is transitive, and one WIT `use` can widen a program's world.**
`wasi:filesystem/types` `use`s `wasi:clocks/system-clock`'s `instant` for `descriptor-stat`'s
timestamps (since `descriptor.stat`, the `fd_filestat_get` import behind `file-length`, was
declared), so ANY program that opens a file keeps `wasi:clocks/system-clock` in its world
whether or not it reads the clock. That is a declared TYPE, not a reachable capability;
**do not read an interface's presence as evidence that the program calls it**
(`WasmLispCompilerTest#anOptimizedComponentThatOpensAFileKeepsTheFilesystemSurface`). Base
block type count went 16 -> 17 with it.

**Nothing downstream may hold a fixed index.** The old `INST_*` / `T_*` constants are gone:
the block's instance indices, the first free component type, and the count the user imports
and the `run`/export wiring shift by all come back from the prune -- a stale constant yields
a component that *validates* while binding the wrong interface, which is why
`ComponentImportBlock.Pruned` returns the maps rather than the bytes alone.

**The adapter needed splitting to make the filesystem droppable.** `fd_write` dispatches on a
runtime fd (1 stdout, 2 stderr, else a file), so the call graph reached `append-via-stream`
from *any* printing program and `wasi:filesystem` (1,229 B, the block's biggest group) never
left. `adapter.wat` factors the two fd-polymorphic shims into `$fd_write_stdio`/`$fd_write_file`
and `$fd_read_stdin`/`$fd_read_file` over shared helpers and exports the narrow halves too.
**`path_open` is the only writer of the adapter's fd table**, so a core that does not import
it can never present a file fd: the wrapper then retains `fd_write_stdio` UNDER THE NAME
`fd_write` (`WasmExports.retain` renames) and the whole filesystem surface goes. This is the
one place the component reads an adapter export whose name differs from `adapter.wat`'s.

**A narrow half must be no more PERMISSIVE than the wide one.** `$fd_write_stdio` answers fd
1 and 2 and TRAPS (`unreachable`) on anything else; `$fd_read_stdin` traps on any fd but 0. A
SOCKET fd (>= 200) also reaches `fd_write` whenever a write form escapes
`WasmSocketsRewrite`'s dispatch table (`format` is one such form), and under the wide adapter
that walked off the fd table and trapped inside the host. A guard-less narrow `fd_write`
would instead have written those bytes to STDERR and returned success, so `--optimize` alone
would have turned a crash into a protocol desync. Pinned by
`WasmLispCompilerIntegrationTest.anOptimizedComponentFailsAsLoudlyAsAPlainOneOnAnFdItCannotServe`.
**Rule for any future narrow/wide pair: the narrow one rejects what it does not implement, it
never approximates it.**

**The blob grammar is decoded, not pattern-matched.** `ComponentImportBlock` classifies every
byte of the block (instance-type declarators, the whole defvaltype set, extern descriptors,
aliases) and throws on anything else; only three immediates point outside their own entry (an
alias section's instance index, an `alias outer` type index, an import's instance-type index)
and only those are rewritten. Checked against `wasm-tools` itself: pruning
`import-block.bin` to `{wasi:cli/types, wasi:cli/stdout}` is **byte-identical to
`import-block-nogc-print.bin`** (`ComponentImportBlockTest`, which also runs all 2,047
non-empty subsets back through the parser).

**`--emit-wit` moves with it.** `WitEmitter` filters the variant document's world imports and
drops package definitions nothing references, from the SAME set the builder prunes to
(`WasmComponentBuilder.wasiInterfaces`). Ordering rule that had been true by accident:
`wasm-tools component wit` prints package DEFINITIONS in the order the world first names them
(imports in order, then exports), and the templates matched only because `wasi:cli/types` is
always the first import -- drop every `wasi:cli` import and the package survives only through
the fixed `export wasi:cli/run`, printed LAST. `WitEmitter.orderPackagesByFirstReference`
derives the order from the world, covering the appended user-import and exported-interface
blocks too (`aPrunedWorldWithNoWasiCliImportStillOrdersItsPackagesLikeWasmTools`).
`WitOracleE2eTest` gained its first `--optimize` legs here.

**Serve is deliberately NOT pruned** (`WasmServeComponentBuilder` keeps its fixed block
constants and embeds the preview1 bridge whole): its block declares nine interfaces
http.lisp's glue reaches anyway, and its floor is the ~280 KB core, so the win is the
bridge's ~0.7 KB. **Re-evaluation trigger**: if the serve core stops reaching the
`wasi:cli`/`wasi:clocks`/`wasi:random` halves, or the serve floor drops by an order of
magnitude, the same three steps apply unchanged.

### The wrapper's last two fixed costs, and the floor under them

**`wasi:cli/stderr`, 185 B.** `fd_write` dispatches on a runtime fd, so `$fd_write_stdio`
reaches `stderr-write` from any printing program and the whole interface rode along. But fd 2
is the RESERVED `*error-output*` handle (`.kb/standard-output-redirect.md`), materialized in
`StreamDesignators.STANDARD_ERROR_HANDLE` alone, so "can this program present fd 2" is a
question about the SOURCE. `WasmLispCompiler` answers it (`programUsesSymbol` over
`*ERROR-OUTPUT*` / `WARN` / `%WARN`, plus `--dynamic`) and hands it to the wrapper as
`WasmComponentBuilder.Narrowing`; `adapter.wat` gained a third `fd_write`,
`$fd_write_stdout` (fd 1 only, `unreachable` otherwise), and the retain table picks between
the three. A program with `path_open` keeps the WIDE `fd_write` and therefore stderr.

*This depends on a list being complete.* Anything new that can put handle 2 into a stream
designator must join that gate; the producer list lives in
`.kb/standard-output-redirect.md`. **A producer the compiler INJECTS cannot join a scan of
the user's text** -- the EH-mode landing pad (`WasmUncaughtReportCompiler`, synthesizing its
`%warn` in pass 2) did not, and `--component --optimize` pruned `wasi:cli/stderr` out from
under the uncaught-condition report at both levels. Such a producer contributes its own
emission fact instead: `emittedFor(ehMode)` is read by the pad's emission AND OR-ed into
`reachesStandardError`. Pinned by
`WasmLispCompilerTest.anOptimizedComponentWithAnUncaughtReportLandingPadKeepsTheStderrSurface`
(which also re-asserts a print-only component DROPS the interface, at `--optimize=size` too)
and `WasmLispCompilerIntegrationTest.anOptimizedComponentStillReportsAnUncaughtCondition`.

**The shared `cabi_realloc`, 142 B.** The mem module exists for its MEMORY: the `"w"`
lowerings' canonical options name a core memory, and that memory must belong to an instance
older than the adapter they are grouped for -- a circularity no other module can break. Its
allocator half is answered by whether any canonical option references it. For a print-only
program none does (`stdout-write` lowers bare; the stream/future/waitable built-ins take
`(memory 0)` only), so the alias goes, `nextCoreFunc` starts at 0 instead of 1, and the
module is shaken down to a bare `(memory 6) (export "memory")`. `WMember.realloc()` cannot
drift from the encoders because the realloc index is reachable only through the
`lowerRealloc` / `builtinRealloc` factories that also set the flag. Block-bound and user
interface imports are answered with a plain yes (`needsSharedRealloc`). A `wasm-export`'s
string ABI is NOT part of the question -- it lifts through the CORE module's own
`cabi_realloc`.

**The floor: WASI 0.3 streams are asynchronous, and the blocking spelling is gated.** The
adapter builds a stream, a future and a waitable set for one constant write -- ~279 B of the
wrapper (the `waitable-set-new` / `waitable-join` / `waitable-set-wait` canons, their `"w"`
entries and imports, `$ensure_ws` / `$await_waitable` and the two BLOCKED-retry wrappers).
The component model's synchronous `stream.write` / `future.read` built-ins would delete all
of it, but they sit behind the spec's more-async-builtins tier: `wasm-tools validate` and
wasmtime 47 both reject them without `component-model-more-async-builtins`, which is not
default-on. **rontolisp's contract is that a component runs with ZERO flags**, so this is the
floor. **Re-evaluation trigger: when more-async-builtins becomes default-on, drop the
waitable trio from `adapter.wat` and the async keyword from those two canon encodings.**

**The `"w"` field names, ~232 B, deliberately left long.** Each of the nine members a
printing program keeps (`stdout-write`, `stream-new`, `stream-write`, `stream-drop-w`,
`future-read-cli`, `future-drop-cli`, `waitable-set-new`, `waitable-join`,
`waitable-set-wait` = 125 chars) is spelled twice: the adapter's `(import "w" "<name>" …)`
and the `(export "<name>" (func n))` of the instance built to satisfy it. One character each
would save ~232 B (13.1% of a hello component). It is safe (`"w"` is private linkage between
two artifacts this repo ships together, and `fixedSurface` throws when the adapter imports a
`w` member the wiring does not declare) but declined: `wasm-tools print` goes opaque, the
mapping has to be read in two places, `adapter-http-server-p1.wat` /
`WasmServeComponentBuilder.BRIDGE_FUNCS` would have to follow -- and the ~279 B the async
gate holds is a strictly larger win an upstream default will hand over for free.

Re-open only when (a) the more-async-builtins gate opens (do THAT first, re-measure, re-read
this) or (b) a host makes the component floor matter more than its legibility (then take the
~232 B for BOTH adapters in one pass). If taken, keep `W_MEMBERS` keyed by the descriptive
name and give each member an explicit wire field, so the mapping lives in one table next to
the encoders (the shape `WMember.realloc()` uses).

**Decoder correctness** rests on the backend emitting (a) no `call_indirect`/element segments
-- first-class calls go through dispatch functions with direct `call`, so `call` is the only
function reference -- and (b) a finite, enumerated opcode set (incl. the `0xFB` GC ops, the
`0xFD` fixed-width SIMD ops via `skipSimd`, needed since the `--no-gc` `vec:` kernels emit
`v128`/`f64x2`/`f32x4`; the `0xFC` misc-prefix saturating truncations; and
`block (result …)` blocktypes including the ONE-BYTE `eqref` spelling,
`.kb/wasm-shortest-encoding.md`). An unknown opcode or SIMD sub-opcode throws rather than
emit a corrupt module. Tests: `WasmTreeShakerTest` (structural, no Docker: shrinkage, import
drop, well-formedness via a mini-parser, idempotence) plus optimize cases in
`WasmLispCompilerIntegrationTest` (wasmtime behavior parity, incl. `--no-gc --optimize`
f64x2/f32x4 vec kernels).

### Why the component path is safe

Every core <-> component linkage is **by name**, in both directions, so renumbering the
core's functions is invisible to the wrapper:

- the wrapper reaches into the core only through `alias core func (instance N) "name"`
  (`ComponentWriter.aliasCoreFunc`, encoded `sort=core func, target=0x01 <instance> <name>`)
  -- `run`, `handle`, `async_cb`, each `wasm-export` wrapper, `cabi_realloc`, `cabi_post_*`.
  **All are core EXPORTS, hence already shaker roots**, including the two the core never
  `call`s itself (serve's `handle` and `async_cb`, reached only from the
  `canon lift ... async (callback ...)` declaration);
- the core's imports are satisfied by `core:instantiate <module> vec((name, instanceidx))`
  with per-interface instances built `from-exports` as `(field name -> func)` maps, so a
  dropped function import just leaves one unused name in the map -- nothing is positional;
- the Preview-1 adapters (`adapter.wat`, `adapter-http-server-p1.wat`) never reference the
  core: they are instantiated BEFORE it and the core binds them by name.

`WasmComponentBuilder.memModuleFor` reads the core's `mem`/`memory` **memory** import, which
the shaker keeps verbatim along with every other non-function import.

Where the shaker earns its keep is a non-serve component (such a program never reaches the
arity dispatch). A **serve** component was long the counter-example (the Clack model
`funcall`s the handler, so the dispatch bodies were live and `call`ed every registered
builtin wrapper -- a ~4% drop) until the three rontolisp-owned gate blockers were retired.
Note what the bytes do NOT buy on `wasmtime serve`: rps at `--max-instance-reuse-count` 1 and
128 is unchanged within noise, because the module is compiled once per server run. The size
win is transfer, disk, and compile-time cold start (wasmCloud-shaped hosts), not the reuse
loop.

## The funcall-dispatch gate (what makes `--optimize` reach library code)

**A function gets an arity-dispatch case, and a `_lookup` registry row, only when the program
can actually reach it as a function VALUE.** Without this the shakers are nearly inert on any
program that loads a library: the ladders `call` every registered function.

`WasmLispCompiler.dispatchableFuncIds` / `JvmLispCompiler.dispatchableFuncIds` compute the
set; `WasmRuntimeBuilder.buildDispatchBody` and `JvmRuntimeBuilder.buildDispatchMethods`
filter their targets by it, and the registry (the WASM data blob /
`JvmEvalRuntimeBuilder.lookupSegments`) filters its rows by the SAME set -- **computed
together so they cannot drift**: a row whose funcId has no case would resolve and then fall
through to the ladder's default arm.

Two sources, both EXACT rather than heuristic:

- **`Ctx.valueFuncIds`** -- the funcIds Pass 2 actually materialized as a closure:
  `WasmFunctionFormCompiler.compileNamed` / `JvmFunctionFormCompiler` (`#'name`),
  `WasmLambdaCompiler.emitClosureValue` / `JvmLambdaCompiler` (every `(lambda ...)` value),
  and `WasmAsyncEmit`'s waiter closure over a resume function. Collected DURING emission, not
  from a pre-scan, which is the whole point: a `#'identity` or `%seq-string` reference a
  macro synthesizes during Pass 2 is invisible to any scan of the source. Every body is
  emitted before the ladders are built, so the set is complete when read.
  **TRAP: `WasmAsyncEmit.freshCtx` rebuilds a `Ctx` field by field** and also builds the
  SYNCHRONOUS top level. Omitting `valueFuncIds` there silently lost every closure the top
  level makes and `(funcall f 1)` trapped. **Any module-wide MUTABLE `Ctx` field must be
  listed there.**
- **the names a runtime SYMBOL designator can resolve.** On WASM this source is live when the
  registry is (`usesEval || usesRuntimeDesignator || usesApplyRuntime`,
  `.kb/eval-runtime.md`). `_lookup` matches interned offsets (WASM) / string constants (JVM),
  so a registry row is reachable only when the program already put that exact name there for
  another reason. The probe set is **`Ctx.spelledLiterals`** on both backends: every spelling
  Pass 2 emits as a runtime VALUE, recorded in `Wasm/JvmEmitHelper.compileStringLiteral`
  exactly as `valueFuncIds` records closures. It used to be the whole string table /
  constant pool (`StringTable.isInterned` / `ConstantPool.hasStringConstant`), an
  over-approximation that armed rows for the instance-layout directory's slot names, the
  printer prologue's `"-"`/`"/"` and the JVM layout tables -- none producible as a designator.

  **Seven spellings** are tried: canonical; the `::`->`:` alias row's spelling; the bare
  member name after the last colon; the FRAMED string-literal spelling of the full name and
  of the member (`"NAME"`, quotes included -- a string literal interns via
  `LispString.literal()`, so `(intern "RUN" pkg)`, clack's handler discovery, spells `"RUN"`
  not `RUN`); and the two package-less SYMBOL spellings whose name is the member, `:member`
  and `#:member` (`uiop:symbol-call :pkg :member` spells both halves as keywords;
  `(uiop:symbol-call '#:pkg '#:member ...)` -- dexador's, and what a `.asd` uses -- spells
  them uninterned, and `(string '#:MEMBER)` is `"MEMBER"` exactly as `(string :MEMBER)` is).

  **The seven live in `compiler.DesignatorSpellings`**, not once per backend -- a spelling
  added to one and not the other is precisely the "resolves on the JVM, not on WASM"
  divergence. `of(name, symbolBuilders)` is the probe order, `anySpelled` the decision,
  `matched` the report. Pinned by `DesignatorSpellingsTest`.

  **A literal the compiler SYNTHESIZES as pure result data is exempted through
  `%unspelled-quote`** (`LispNames.UNSPELLED_QUOTE`): it compiles exactly like `quote` on
  every backend (`Wasm/JvmEmitHelper.compileUnspelledLiteral`; the interpreter evaluates it as
  `quote`) but records nothing. Two emitters use it: the generated `:reader`/`:accessor`
  body's slot name (`LispMacroExpander.checkedSlotRead` quotes it for the `unbound-slot`
  signal's `:name` -- in an EH-mode program this used to arm a row + ladder case for EVERY
  slot name of EVERY class; a user-written `slot-value`'s name keeps the plain quote), and
  `expandClassDesignator`'s type-name results (`'STRING`/`'CONS`/..., inside
  `%no-applicable-method` in every program with a generic dispatcher, which kept the
  same-named builtin wrappers dispatchable). This is the symbol half of the rule **no
  generated literal may spell a defun name exactly**. Consequence:
  `(funcall (cell-error-name e) ...)` / `(funcall (type-of x) ...)` stop resolving like any
  forged name, loudly; `--dynamic` restores them.

  **The four widened spellings apply only while the program contains a symbol BUILDER at
  all** -- `RuntimeNameProducers.anySymbolBuilder`: `intern`, `find-symbol`, `make-symbol`,
  `uiop:symbol-call`. Without one, no runtime path can turn a string, keyword or uninterned
  symbol constant into a designator. `make-symbol` is in the set as the safe
  over-approximation: its product can never match a registry row on WASM (a fresh,
  never-canonicalized offset) but the JVM registry compares string VALUES. Two of the
  compiler's own emissions are shape-exempt, each provably unable to produce a FUNCTION
  designator: the literal keyword-package intern `(intern X :keyword)` (http-server.lisp
  interns the request method and protocol this way; it only makes keywords, and no row key
  begins with a colon) and the injected `(defun %slot-name-key (n) (intern (symbol-name n)))`
  (`LispMacroExpander.slotNameKeyDefun`). Pinned by
  `widenedProbesApplyOnlyWithASymbolBuilderPresent`,
  `theCompilersOwnInternShapesDoNotWidenTheProbes` (WasmTreeShakerTest, paired-difference
  function counts) and `aFramedSpellingWithoutABuilderDoesNotHoldARow` (JvmClassShakerTest).

Other pins for the spelledLiterals narrowing:
`WasmTreeShakerTest.aCompilerInternedTableNameDoesNotArmTheDispatchGate` /
`aGeneratedReaderBodySlotNameDoesNotArmTheDispatchGate` and their `JvmClassShakerTest` twins
(the JVM pair also runs the module). Debug:
`-Drontolisp.debug.dispatchgate=true` prints `name-armed <defun> by <spelling>` for every row
held by a literal rather than a value, and names the operator that turned the gate off.

**The gate turns itself off entirely** (every function stays dispatchable) under `--dynamic`
and whenever `compiler/RuntimeNameProducers.anyNameResolvable` holds -- the program contains
a DATA EVALUATOR: `eval`/`read`/`read-from-string`/`load`, or the injected `~/name/` renderer
arm (`FormatRenderer.FUNCTION_DESIGNATOR`; a control string is runtime data and the arm is
injected exactly when a control string spells the directive, so its presence IS the trigger).
That class is shared by both backends on purpose: a name that stops resolving on one has to
stop resolving on the other.

**The symbol BUILDERS no longer bail.** `intern`, `find-symbol`, `make-symbol`,
`symbol-function`, `fdefinition`, `fboundp`, `uiop:symbol-call` used to turn the gate off
wholesale, which is why a clack program shipped every defun dispatchable (lack's
`locate-symbol` is `(find-symbol "RUN" pkg)` and it is LIVE). The split is sound against the
probes: a symbol a builder produces is built FROM A STRING, and any string the program holds
is a compile-time constant the widened probes already read in every spelling the lowerings
emit. What escapes them is a name assembled out of COMPUTED pieces -- verbatim
`LibraryDefunPruner`'s documented carve-out (the ordinary undefined-function error;
`--dynamic` to restore late binding). This retired both scan exemptions with the trigger that
made them necessary. Pins: `internDoesNotHoldTheFuncallDispatchGateOpen` (WasmTreeShakerTest
-- computed intern shakes like keyword intern; `(eval (read))` still bails),
`internDoesNotHoldTheDispatchGateOpen` (JvmClassShakerTest -- incl. the quoted-intern shape,
the framed-string resolution of a literal-intern funcall, and the eval bail),
`keywordInternStaysInternedInAGateShakenModule` (WasmLispCompilerIntegrationTest).

**Without the data-evaluator bail the gate is not sound, and the failure is a trap rather
than a diagnosis.** The clack runtime path this exists for -- `clackup` -> `find-handler` ->
`find-package-or-load` -> `(find-symbol "RUN" pkg)` -> `apply` -- resolves through the
framed-string probe.

The two rontolisp-owned blockers that had to be retired first, each masking the next:

1. the spliced runtime `format` renderer's `%fmt-function-designator` (the `~/name/`
   directive resolves its target out of the control string and funcalls it), now a
   separately-injected arm -- `.kb/format.md`, "The `~/name/` arm is injected SEPARATELY";
2. with that gone, the generated slot-name fold's `intern` became the blocker for cl-ppcre --
   moot since the builder split (an `intern` no longer triggers at all).

Library-side symbol builders (`jzon`'s `(fdefinition key-fn)`, cl-postgres's
`(intern (string '#:make-ssl-client-stream) :cl+ssl)`) now gate rather than bail: jzon's
runtime `key-fn` designator arrives as a quoted symbol at every real call site, and
cl-postgres's SSL forge is dead at run time and simply stops resolving.

One refinement was tried and REJECTED on measurement: judging the `intern` ARGUMENT shape
shrank nothing and broke `internIntoALiteralPackage` on both backends, because the
two-argument lowering folds the literal into the qualified symbol before either probe sees it
-- which is WHY the split judges nothing per-site and lets the spelling probes carry the
weight.

Tests: `componentCoreIsTreeShakenUnderOptimize` (shrinkage + a scalar and a string-returning
export invoked under wasmtime) and `optimizedServeComponentStillServesUnderWasmtimeServe`,
both in `WasmLispCompilerIntegrationTest`;
`FormatRendererTest.theFunctionDesignatorArmIsInjectedOnlyForAProgramThatSpellsTheDirective`.

### A designator the compiler can READ never enters `valueFuncIds`

The other half: an operator that funcalls a function argument no longer MAKES a value out of
a designator it can read. `Wasm/JvmDesignatorCall` is the one decision --
`compiler.FunctionDesignators.literalName` (a literal `#'name` / `'name`, `normalize`d) plus
the backend's own registry at the arity in hand -- and the six sites that ask it are
`funcall`, `mapcar`, `mapc`, `mapcan`, `reduce` and `sort` on BOTH compile backends. A
resolved site emits the direct call its head-position spelling would have emitted; the funcId
is never materialized, so it joins neither `valueFuncIds` nor the ladder.

**Why the direct call is the same call.** A ladder case IS that instruction sequence:
`WasmRuntimeBuilder.buildDispatchBody` pushes the closure's env (null for a `#'name` value),
the arguments, and -- for a variadic target -- the surplus arguments linked into the rest
list, then `call`s; `JvmRuntimeBuilder.renderCase` is the same minus the env.
`WasmDesignatorCall.emitCall` reproduces exactly that: at the required count it appends the
empty rest list; wider, it evaluates every argument into a temp first (left to right, as the
dispatching route does) and links the surplus.

**Deliberately NOT resolved**, all three keeping the dispatcher: a computed designator; a
name no registry answers (a car/cdr composition that synthesizes a lambda, a `--dynamic`
deferral); and **an arity the callee cannot take**. That last is the one not to "fix": the
arity contract of these operators is a RUN-time one, so `(mapcar #'cons '(1 2))` must fail
where it failed before -- a WASM trap, the ladder's default arm (nil) on the JVM -- rather
than becoming the compile error the head position would give. On WASM the resolution is asked
BEFORE the dispatch ceiling check (`WasmFunctionCallCompiler.compileFuncall`), because a
ceiling on the dispatchers cannot bind a call that uses none.

Lisp-2 shadowing needs no handling: `flet`/`labels` rewrite both `(f x)` and `#'f` into their
binding VARIABLE before any backend sees the form (`.kb/flet-labels.md`).

What moves bytes is NOT the callee -- a mapped function is called from the site either way --
but what the ladder stops fanning out to. `STRING=` is the shape: `expandRuntimeFindPackage`
emits `(assoc key '(...) :test #'string=)` on a path the program never runs, and the live
arity-2 ladder kept it anyway; reading the designator turns that into a direct call FROM DEAD
CODE, and dead code shakes. Honest cost, and only there: a variadic callee reached wider than
its required count links the rest list AT the call site now, where the ladder case used to
hold that code once for every caller -- so the no-flag JVM class grows slightly while
`--optimize` pays back.

Pins: `WasmTreeShakerTest.aLiteralDesignatorSiteBuysNoLadderCase` and
`JvmClassShakerTest.aLiteralDesignatorSiteBuysNoDispatchCase` (paired difference: a literal
`mapcar` designator against the same designator behind a variable);
`WasmLispCompilerIntegrationTest.literalFunctionDesignatorsCompileAndRun` /
`JvmLispCompilerTest.compileAndRunLiteralFunctionDesignators` (both variadic shapes),
`compileAndRunLiteralDesignatorOfTheWrongArityKeepsTheDispatcher`, and the four-backend
ci-spec case `literal-function-designators-answer-like-computed-ones`.

**A gate test's scaffolding is affected, and silently.** `(print (funcall 'f))` used purely
to keep a ladder emitted is now a direct call, so the ladder disappears and such probes have
nothing left to keep -- one test turned red and the paired-count ones would have gone
vacuously green. They funcall a COMPUTED designator now (`(funcall (car (list #'f)))`; a
plain variable was the first fix and stopped working when the section below landed). **Any
future test about what the ladders keep alive owes the same care.**

### A designator BOUND to a temp is not a value either

Every expander that NAMES a designator to avoid re-evaluating it undoes the section above:
`LispMacroExpander.expandMap` binds `(let ((__map_fn #'identity)) ... (funcall __map_fn (elt
...)))` -- and the `coerce` lowering emits `(map 'list #'identity x)`, so every program that
coerces a string carried one -- as do `expandMapFamily` (`maplist`/`mapcon`/`mapl`) and
`expandEverySomeFamily`.

`compiler.LetBoundDesignators.propagate` closes it in ONE place: **a `let` binding whose init
is a literal designator naming a registered function, and whose every use in the body is a
function-designator position, is propagated into those uses and the binding dropped.**
`Jvm/WasmLetCompiler` call it on the way in, so a hand-written `let`, the nested lets `let*`
lowers to, and every macro-generated binding go through the same rule; the resolved sites are
then ordinary written-out literals to `Jvm/WasmDesignatorCall`.

**Why the backends and not the expanders.** Leaving the literal AT the funcall site in
`expandMap` was measured first and declined: the interpreter would evaluate the designator
once per element instead of once, and a designator naming an UNDEFINED function would stop
signalling over an empty sequence. Rewriting in the backends keeps the interpreter out of it
and covers every binder with one rule.

**The safety argument is a COUNT, not a walker.** The pass certifies the occurrences it
understands (the designator argument of the six operators the backends resolve) and
separately counts EVERY occurrence of the name in the body with a deliberately shape-blind
scan -- quoted data, binding lists, dotted tails and all. It rewrites only when the two
agree, which lets the substitution be shape-blind too. Anything the certifying walk does not
understand shows up as an uncertified occurrence and keeps the binding: a use as a plain
VALUE, a `setq`, an inner binding or lambda parameter of the same name, a `(funcall f ...)`
shaped list that is a datum. The walk stays opaque at data-carrying heads (`quote`,
`declare`, the `def*` family, a `case` clause's keys, a lambda list) for one reason:
descending somewhere non-evaluated is free (the occurrence is uncertified and the count
refuses), but CERTIFYING something non-evaluated would corrupt it.

**Three guards beyond the count.** A SPECIAL name is never dropped (a dynamic binding is one
a callee reads). A name bound twice in the same binding list is left alone. And the
designator must name a function the backend's registry answers (`ctx.functions`): that is
what makes the substitution value-identical -- both spellings compile to the same static
funcId, `--dynamic` included, since `Jvm/WasmFunctionFormCompiler` defer to the runtime only
for a name the registry does NOT hold -- and it keeps `#'cadr` out, whose value is a car/cdr
composition SYNTHESIZED per site (`.kb/core-representation.md`).

The WASM fusion registry is untouched by construction: it registers a `__FLET*` binding whose
init is an eligible integer-tree LAMBDA (`.kb/wasm-int-fusion.md`); this pass only takes a
binding whose init is a literal designator.

**What is deliberately not listed.** The certified positions are the six operators the
backends RESOLVE, not every operator that funcalls its argument.
`map`/`maplist`/`mapcon`/`mapl`/`every`/`some` bind the designator in their own expansion, so
a LITERAL written at one of those sites is taken here anyway. What stays outside is a literal
that reached them through a HAND-WRITTEN variable (`(let ((f #'oddp)) (every f xs))`).
Adding those operators' designator slots to `designatorSlot` is the lever if that shape turns
up in a real program; every slot in that table is a claim about an expander that has to keep
being true.

The JVM pays more for a ladder case (a variadic callee's rest-list linking lives in the case,
not at the site), so dropping the value is worth about ten times more there than on wasm.

Pins: `WasmTreeShakerTest.aDesignatorBoundToATempIsTheSameDirectCall` (the bound spelling is
the written-out literal's own module, byte for byte) and
`JvmClassShakerTest.aDesignatorBoundToATempIsTheSameDirectCall`, each paired with the same
binding plus a VALUE use, which still dispatches. `LetBoundDesignatorsTest` covers the rule.
Behaviorally the four-backend `literal-function-designators-answer-like-computed-ones` case
grew the three shapes that KEEP the binding -- value use, `setq`, shadowing.

## What ROUTING costs a clack module: cl-ppcre, decided

Adding tiny-routes to a Clack reactor nearly triples the module, and the extra is not
tiny-routes (72 functions, ~72 KB) -- it is **cl-ppcre, its only dependency**: a route
template is compiled to a scanner at RUN time (`path-template.lisp` even builds one at LOAD
time, `*path-token-scanner*`), so the whole regex pipeline (lexer -> parser -> converter ->
optimizer -> closure compiler) is genuinely reachable and the shaker is right to keep it.

Five levers were measured; one taken, the rest recorded so they are not re-derived:

1. **TAKEN -- the Worker examples build at `--optimize=size`** (all four wasm-GC `build.sh`
   lines; `hello` stays `--optimize` because it is `--no-gc`, where the level is a no-op).
   The price on a Worker is the right trade both ways: warm requests +24-42% RELATIVE but
   3-11 us ABSOLUTE, while `_initialize` gets FASTER (less code for V8 to compile) -- and
   isolate startup, not the microseconds, is what Cloudflare budget-checks.
2. **cl-ppcre's eight `define-compiler-macro`s never fire on the routing path, and firing
   would not shrink anything.** The routed module is BYTE-IDENTICAL with all eight stripped:
   every regex designator `path-template.lisp` passes is a variable or a computed
   `concatenate`, never `constantp`. Where one does fire (a user's literal
   `(ppcre:scan "…" x)`) it ADDS 179 B (the `load-time-value` slot) and removes nothing,
   because the scanner BUILDER still ships. Worth having for run/start-up time
   (`.kb/compiler-macros.md`), never for size.
3. **REJECTED AS A DEFAULT, delivered AS AN OPT-IN** -- a leaf-module substitution of
   tiny-routes' `path-template.lisp` (the `ShimLibraries.leafModuleForms` tier,
   `.kb/asdf.md`). The shim ALONE buys -0.9%, so delivering the win also requires a
   replacement `.asd` that DROPS the `:cl-ppcre` dependency (the two-tier substitution reaches
   -60.9%). As a DEFAULT it breaks `:regex t`, silently changes keyword-template semantics
   (upstream interprets the NON-token template text as regex), and any program touching
   `ppcre:` gets the engine back with routes matching by different rules. Shipped instead as
   the opt-in system **`tiny-routes/lite`**: the user asks for it by name, the matcher signals
   at route-build time on everything it does not reproduce (metachar templates, `:regex t`),
   the accepted `:name`-token subset is pinned template-for-template against the real engine,
   and co-loading the two systems is refused. Mechanics + pins in `.kb/asdf.md`.
4. **Loaded-but-unreferenced cl-ppcre is anchored by its CLOS surface.** With zero references
   remaining, only 0.9% left: `LibraryDefunPruner` KEPT every
   `defgeneric`/`defmethod`/`defclass`/`define-condition`/`defstruct` as a root
   (`.kb/library-defun-pruning.md`), and at module level every method body is materialized as
   a closure at load time, so it is in `valueFuncIds`, dispatchable, and live through the
   ladders. **The lever LANDED**: the pruner's CLOS candidates + per-method gates
   (`.kb/library-defun-pruning.md`, "The CLOS definition kinds are candidates too") collect
   dead CLOS at the AST level before either module half sees it -- ~30% out of every clack
   Worker -- and moot a separate `valueFuncIds` half (a pruned method is never emitted; a kept
   one is genuinely dispatchable). What the AST argument still cannot touch is the
   `let`-over-`defmethod` root (build-replacement-template's binding initform calls
   `create-scanner`).
5. **Splitting cl-ppcre's parse half from its match half: investigated, not a plan.** It would
   pay only if a scanner could be built at COMPILE time and the builder left out; a cl-ppcre
   scanner is a tree of closures closing over each other (`closures.lisp`), which nothing can
   serialize into an artifact -- `load-time-value` runs INSIDE the module at load time, so the
   builder ships regardless (also why lever 2 cannot shrink anything).

## What a cl-ppcre-USING application costs

Per-feature probes (each `(ql:quickload "cl-ppcre")` plus the named calls, wasm-GC Preview 1
at `--optimize=size`). **Quickloading cl-ppcre alone puts the module in EH mode.**

**Verdict: scanner building is live even for a single literal `scan`, so the whole engine cost
IS the anchor and the shaking levers cannot pay.** A literal `scan` costs +286 B over merely
loading the engine; the spread between the cheapest and richest API usage is ~22 KB on a
~748 KB module (register groups +9.5 KB, `regex-replace-all` with `\1` +9.8 KB,
`create-scanner` over runtime input +178 B, `split` +13.2 KB).

- **Defun-level pruning over spliced trees is ALREADY DEPLOYED and is why the increments are
  this small.** `LibraryDefunPruner` covers ASDF-spliced third-party trees
  (`.kb/library-defun-pruning.md`, "Prunable set, part 2"): a scan-only probe does not pay
  split's or replace's machinery, because the unused API surface leaves at the AST level. Its
  residual on a USING app is ~zero -- what stays is CLOS-anchored, not defun-anchored.
- **CLOS-aware shaking cannot pay on a USING app.** The engine's 27 defgenerics
  (`convert-compound-parse-tree`/`convert-simple-parse-tree`, the
  `flatten`/`gather-strings`/`compute-min-rest`/`compute-offsets`/`start-anchored-p`/
  `end-string-aux` optimize walkers, `create-matcher-aux` + the seven repetition-closures
  matcher builders, `copy-regex`/`regex-length`/`case-mode`, and the `scan`/`create-scanner`
  API generics) ARE the build pipeline. The parse tree is runtime data -- every node class is
  instantiable from `create-scanner`, so every method is reachable. The only sheddable method
  surface is the replace family's. For a program that loads the engine but never calls it the
  answer is not a shaker: it is not loading the engine, and the AST pruner's CLOS candidates
  collect the loaded-but-unreferenced side anyway.
- **What CAN move a module that keeps the REAL engine is code DENSITY, not shaking.** The
  probe is 93% code section. Landed levers: the `%seq-to-*` conversion trio
  (`.kb/seq-conversion-runtime.md`, -9.2%), then two CLOS-lowering halves (-13.7% on top,
  cumulative -21.7%; the JVM twin -24.7%): (1) the generic dispatchers' inlined
  no-applicable-method error tail became the shared `%no-applicable-method` defun (each
  synthesized slot accessor carried its own condition-construction + class-naming render,
  1,721 -> 389 B of bytecode per reader on the JVM), and (2) the variadic dispatchers'
  `apply` forwarding stopped building-then-unpacking its argument list (the ALIGNED apply fast
  path, `.kb/clos.md`). The candidate per-OPERATOR callee lever was measured OUT for this
  module (~28 generic-sequence call sites at 0.1-0.9 KB residual each, a ~2% bound) and stays
  recorded in `.kb/seq-conversion-runtime.md`'s re-evaluation trigger. An opt-in engine subset
  was built, parity-pinned and then REJECTED by user decision. Compile-time lowering of
  literal regexes stays un-taken: identical semantics cannot be promised beyond a pinned
  subset, and one dynamic regex brings the whole engine back silently.

**A correctness hole these probes surfaced, distinct from size:** a `return-from` crossing a
lambda boundary skips the special-binding restore, which corrupts cl-ppcre's own scanners (a
zero-register scan after a failing register-regex loop returns stale `*reg-starts*`;
interpreter correct, JVM + both wasm-GC wrong). Until it is fixed, the interpreter is the only
backend that runs the real engine's scan SEQUENCES per the standard. (`ClPpcreE2eTest` still
passes because of case order.)

## JVM

`am.ik.jvm.JvmClassShaker` runs at the end of `JvmLispCompiler.compile`. It parses the
finished class, builds the call graph from `invoke*` constant-pool immediates, keeps methods
reachable from `main` (plus `_apply` as an extra root when the program uses `java:` interop --
the embedded bridge looks `_apply` up REFLECTIVELY, an edge bytecode cannot show; under
`--no-main` there is no `main` root at all), drops unreachable methods and any static field
only they referenced, and **compacts the constant pool**, rewriting every CP index immediate
in the surviving bytecode in place. Sizes never change (u2 stays u2; an `ldc` u1 index only
shrinks because compaction preserves order), so exception-table pcs and switch padding stay
valid, and no method renumbering is needed since JVM methods are referenced by name. Dispatch
methods keep eval/funcall/`#'` targets alive exactly as on WASM. The shaker throws on anything
it does not recognize (unknown opcode/constant tag, any attribute other than a single `Code`
per method) rather than emit a corrupt class.

**A `rontolisp:jvm-export` wrapper is a third liveness source**, next to `main` and the
dispatchable-funcId set: every export's Java method name joins the roots (its caller is Java
code the bytecode cannot show), and the wrapper's `invokestatic` keeps the target defun's
graph. This is what makes a compiled LIBRARY survive `--optimize`. The wasm side has no
equivalent root because a wasm export IS a module export the shaker already treats as a root;
the two backends still must agree about which symbol DESIGNATOR resolves (the
`dispatchableFuncIds` contract above), and an export root does not change that set -- it keeps
a method, not a registry row. Mechanics and pins: [jvm-export.md](jvm-export.md).

Tests: `JvmClassShakerTest` (structural + behavior, incl. the `_apply` root) and
`JvmClassShakerCorpusTest` (compiles the whole `ci-spec.yaml` corpus with `--optimize`,
asserts shrink + identical run output -- the decoder-completeness guard, like
`WasmTreeShakerCorpusTest`). Limitations: README "Optimize".
