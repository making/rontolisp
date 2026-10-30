# A quoted datum is ONE shared constant, on all four backends

**Invariant: every evaluation of one `quote` site answers the SAME object on the
interpreter, the JVM and both WASM backends** -- `(eq (f) (f))` is `T` for
`(defun f () '(1 2 3))`, and a write through the datum is visible next evaluation (CLHS
leaves writing into a literal undefined). Complement of `.kb/array-literals.md`: a BARE
array literal is a CONSTRUCTOR (fresh per evaluation), the same syntax under `quote` a
CONSTANT.

- Only quoted AGGREGATES are memoized (cons, general array, instance, packed float/int
  array); atoms keep inline emission. Both compile paths key the memo by the DATUM'S
  IDENTITY (`IdentityHashMap`), so textually equal quote sites stay distinct while a macro
  splicing ONE template datum into several sites shares one constant.
- **Interpreter**: `LispEvaluator.evalQuote` and `eval`'s `LispInstance` arm hand the
  datum back verbatim and MUST -- `(quote <value>)` is also the live-value splice
  (`quoteValue`). Fresh-per-evaluation is rejected, do not re-attempt: it breaks
  `read-sequence`.
- **JVM** (`JvmQuoteCompiler` + `JvmQuotePool`): one SLOT per datum in a class-wide
  `Object[] _qd`, named at the site by an int operand and built lazily there: `_qd(slot)`, on null the build, then `_qdSet(datum, slot)`. A slot holds
  the datum wrapped in a `java.util.Optional`: its final `value` publishes the whole
  datum fully built to a thread that reads the slot without synchronizing (JLS 17.5), so
  the read is a plain load. `_qdSet` is `synchronized`, creates the table on first use and
  answers the datum already in the slot if a racing thread stored first, so every
  evaluation sees one object even under a race. See "The JVM table" below. A string
  constant past one `CONSTANT_Utf8` (any string, not only a quoted one) takes a slot too,
  keyed by CONTENT, built by joining its pieces (`.kb/jvm-method-size-limits.md`).
- **WASM, Preview 1 and component** (`WasmQuoteCompiler` +
  `WasmLispCompiler.QuoteGlobals`): one `(mut (ref null eq)) = null` global per datum,
  appended AFTER every fixed-index global, filled lazily. The allocator is
  shared into `WasmAsyncEmit`'s fresh contexts so async resume bodies reach the one
  table.
- **Trap: the JVM build must stay lazy at the site, not a `<clinit>` initializer.**
  The writer shakes on every build; with the injected wrapper defuns' package-registry
  constants pinned by `<clinit>` the class balloons. Do not
  "simplify" this into the `LayoutPool`/`BigIntPool` `<clinit>` shape. The same holds for
  the table itself: `_qdSet` creates it, so the field and both helpers go with the last
  surviving site, where a `<clinit>` allocation would pin them in every class whose
  quoted datums all sat in dropped wrappers.

## The JVM table
Until 2026-09-27 each datum was a volatile static field `_qd$N`, three constant-pool
entries apiece (`Fieldref`, `NameAndType`, name). Those were the one per-SITE pool cost in
the ci-spec corpus class (`JvmDeadMethodEliminationCorpusTest`, `--optimize=off`), whose tripwire
kept forcing ci-spec rows to be cut; the table replaced the fields on 2026-09-27 and the
corpus class's pool and size shrank accordingly.

Nothing else in the pool is per site: the `String`s are symbol
names and string literals, each deduped class-wide (the quote-framed ones are
the runtime's string representation, some also spelled as a bare symbol name -- a
representation cost, not a site cost), and the own-class
`Methodref`s are one per callee (`_lambda_N`, `_fx$N`, ...).

**The corpus is split, and the one-class tripwire is gone (measured 2026-10-31).** The
corpus class's pool count (`--optimize=off`, build info included) had a tripwire under it:
52,000 until 2026-10-07, 54,000 until 2026-10-17, 61,000 until 2026-10-30, 63,000 until
2026-10-31, 64,000 for a day (59,131 entries on 2026-10-17, 61,217 on 2026-10-30, 63,387
on 2026-10-31). It guarded that the corpus stayed ONE class, the only shape the
run-and-compare then covered: past 65,534 the class splits instead of failing
(`.kb/jvm-method-size-limits.md`), and the harness wrote only `Test.class`. On 2026-10-31
ci-spec growth tipped it: `Test` 61,223 entries + `Test$Part1` 26,149 at `off`, 61,222 +
26,049 at default, and the run died on `NoClassDefFoundError: Test$Part1`. The tripwire
could not fire: it read `Test`'s own count, which the splitter caps at its budget
(`MAX_INDEX - RESERVED_ENTRIES` = 61,438) below the alarm. Now the harness writes every file
`runtimeClassFiles()` returns beside the class and runs, shrinks and OSR-checks the class
and its parts, so the split shape is covered and the tripwire went with what it protected. Raising
it again would have made it a budget per ci-spec row, which it never was.

Other costs of the change:

- **Speed.** The volatile field could not be hoisted; the plain read through the final
  field can, and the hoisted read is measurably faster on a
  `member`-heavy defun called in a tight loop. `AtomicReferenceArray` was tried first and
  read no faster than the plain array, so
  the wrapper costs nothing and the volatile ordering was the expense.
- **Small programs** pay the table's fixed part once. A field
  cost per datum, so the table is smaller past ~15 datums; `(print (+ 1 2))` is
  unchanged.
- The site replaces `GETSTATIC` /
  `DUP; PUTSTATIC` with two `int` operands and two calls; the corpus class still shrank with the fields gone.

## A BARE instance literal shares the same slot
**Invariant: a `#P"..."` / `#S(...)` in CODE position -- outside any `quote` -- is one
shared constant per site on all four backends.** The one literal family not following
`.kb/array-literals.md`'s freshness rule: an instance is self-evaluating (CLHS 3.1.2.1.3),
so the compile side meets the interpreter. Same memo, via
`Jvm/WasmQuoteCompiler.emitSharedConstant`, called from `compile` (quoted aggregate) and
`compileLiteralInstance` (bare). Costs orphaned null globals on wasm
(`WasmTreeShaker` does not drop globals), nothing on the interpreter.

## A long list is built in runs (wasm-GC)
`WasmQuoteCompiler.compileQuotedCons` pushes every car, the tail, then one `struct.new` per
cell -- for up to `QUOTED_RUN` (16) cells. A longer list is built from its tail a run at a time,
the list so far carried through one local, so the operand stack never holds more than a run's
cars. The cost is what Cranelift does with a deep stack across CALLS: a symbol or string car is
a call, and every value live across it is spilled and listed in its stack map: on `wasmtime
compile` of a long literal symbol list, building every car first compiles far slower than
runs, and the gap grows with the list length.

A list of NUMBERS (no calls) never cared: every car first compiles as fast as runs. Runs of 64
compile slower than 16 on hand-written modules; 16 costs a few bytes per run,
and a list of 16 or fewer is
unchanged.

**What is left is not the stack.** Wasmtime's compile time grows with the square of the GC
allocations in ONE function, whatever the stack does: the same cells
split into helper functions of 64 compile in a fraction of the time, and
N x `struct.new` of an EMPTY struct -- no rontolisp in it -- shows the same curve. Those numbers are under the
DEFAULT collector, which in wasmtime 49 is `copying`;
`drc`, `null` and single-pass regalloc all compile the same module far faster.
Generators: `.todo/artefacts/953-wasm-compile-quadratic-in-list-length/` (list
shapes) and `.todo/artefacts/955-wasmtime-compile-quadratic-in-allocations-per-function/`
(the reducer, and an upstream report that is drafted but not filed).

**Long data is NOT split into helper functions (measured 2026-09-24).** Real programs do
reach thousands of allocations in one function, but those functions never set the compile
time. Across every GC-wasm leg of `examples/`:

- Most programs stay at or below 317 allocations per function (`llm`).
- The HTTP Worker family goes higher, because its baked data is one function per datum:
  5,813 allocations in the ningle Worker's `%asdf-registry%`, 4,692 in its
  `%class-meta-table`, and 3,493 in tiny-routes.
- In tiny-routes and clack a different function compiles for as long or longer. In ningle that
  was fast-http's `parse-request`, until the
  landing-pad refresh was narrowed to the live locals (`.kb/wasm-landing-pad-refresh.md`,
  "Cost"). Since then
  ningle's slowest function IS the `%asdf-registry%` datum. The datum's body did not change; the
  earlier ordering was
  measured with the unnarrowed `parse-request` compiled before it in the same process.

Parallel compilation hides the datum's cost: on ningle the split would shave only a small
part of the wall time, and nothing on a machine whose cores are all busy. That is not worth a new emission shape, so the split is not built. It becomes
worth building when a datum's function sets a module's wall time by seconds (past about
10,000 cells it does). The split then builds such a datum in helper functions,
one per run, each taking the tail and returning the list; a tree splits per subtree. A helper
is larger than `WasmInliner.MAX_MOVED_BODY`, so the inliner leaves it out of line.

## Not covered
Two DIFFERENT quote sites spelling the same text are not `eq` (CLHS permits but does not
require coalescing). `--no-gc` is scalar-only (`.kb/no-gc-scalar-wasm.md`).

## Tests
ci-spec `quoted-datum-shared-cross-backend`, `instance-literal-shared-cross-backend`;
`LispEvaluatorTest.{aQuotedDatum,anInstanceLiteral}IsOneSharedConstantOnEveryBackend`;
`{aQuotedDatum,aBareInstanceLiteral}IsOneSharedConstantAcrossEvaluations` in
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (Preview 1 AND component).
JVM table: `JvmLispCompilerTest.aQuotedDatumCostsNoConstantPoolEntryOfItsOwn` (3 and 300
datums, one pool size), `aRacingFirstBuildOfAQuotedDatumAnswersTheDatumThatWon`.
Long strings: `LongStringConstantFixture` (interpreter, JVM, Preview 1 and component).
Runs: `WasmLispCompilerTest.aLongQuotedListKeepsNoMoreThanOneRunOfCellsOnTheOperandStack`,
`WasmLispCompilerIntegrationTest.aQuotedListLongerThanOneRunIsTheSameListAndStillOneConstant`.
