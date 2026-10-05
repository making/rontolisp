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
  `Object[] _qd`, named at the site by an int operand and built lazily there (~17 bytes
  over the build): `_qd(slot)`, on null the build, then `_qdSet(datum, slot)`. A slot holds
  the datum wrapped in a `java.util.Optional`: its final `value` publishes the whole
  datum fully built to a thread that reads the slot without synchronizing (JLS 17.5), so
  the read is a plain load. `_qdSet` is `synchronized`, creates the table on first use and
  answers the datum already in the slot if a racing thread stored first, so every
  evaluation sees one object even under a race. See "The JVM table" below. A string
  constant past one `CONSTANT_Utf8` (any string, not only a quoted one) takes a slot too,
  keyed by CONTENT, built by joining its pieces (`.kb/jvm-method-size-limits.md`).
- **WASM, Preview 1 and component** (`WasmQuoteCompiler` +
  `WasmLispCompiler.QuoteGlobals`): one `(mut (ref null eq)) = null` global per datum,
  appended AFTER every fixed-index global, filled lazily (~10 bytes). The allocator is
  shared into `WasmAsyncEmit`'s fresh contexts so async resume bodies reach the one
  table.
- **Trap: the JVM build must stay lazy at the site, not a `<clinit>` initializer.**
  The writer shakes on every build; with the injected wrapper defuns' package-registry
  constants pinned by `<clinit>` a three-defun program grew 5,898 -> 18,978 bytes. Do not
  "simplify" this into the `LayoutPool`/`BigIntPool` `<clinit>` shape. The same holds for
  the table itself: `_qdSet` creates it, so the field and both helpers go with the last
  surviving site, where a `<clinit>` allocation would pin them in every class whose
  quoted datums all sat in dropped wrappers.

## The JVM table
Until 2026-09-27 each datum was a volatile static field `_qd$N`, three constant-pool
entries apiece (`Fieldref`, `NameAndType`, name). Those were the one per-SITE pool cost in
the ci-spec corpus class (`JvmDeadMethodEliminationCorpusTest`, `--optimize=off`), whose 52,000
tripwire kept forcing ci-spec rows to be cut. Measured 2026-09-27, linux-x64, JDK 25:

| corpus class | fields | table |
| --- | ---: | ---: |
| pool entries, `--optimize=off` | 51,945 | 43,694 |
| pool entries, default | 51,823 | 43,572 |
| class bytes, `--optimize=off` | 7,608,664 | 7,539,720 |
| `_qd$N` fields | 2,755 | 0 |

The pool at 51,945 held 19,358 `Utf8`, 10,258 `NameAndType`, 10,057 `String`, 6,462
`Methodref`, 3,865 `Fieldref`. Nothing else in it is per site: the `String`s are symbol
names and string literals, each deduped class-wide (3,548 are quote-framed string literals,
the runtime's string representation, 907 of them spelling a symbol name that is also
there bare -- a representation cost, not a site cost), and the 6,131 own-class
`Methodref`s are one per callee (1,333 `_lambda_N`, 615 `_fx$N`, ...). The 52,000 tripwire
stays: past 65,534 the class now splits instead of failing (`.kb/jvm-method-size-limits.md`),
so what it guards is that the corpus class stays ONE class, the shape its run-and-compare
covers; the split's placement is pinned by the split tests. Since 2026-09-29 every level's
class is written with a pool of its own holding only what its members reference; at
`--optimize=off` that dropped 35 entries of the corpus class's 44,118 (CLI, the same day).

Other costs of the change:

- **Speed.** The volatile field could not be hoisted; the plain read through the final
  field can. `(defun hit (x) (if (member x '(a b c d)) 1 0))` called 50,000,000 times: 558
  -> 420 ms steady state. `AtomicReferenceArray` was tried first and cost 663 ms; a bare
  `Object[]` read with no wrapper (unsafe, measured only as the floor) was the same 420, so
  the wrapper costs nothing and the volatile ordering was the expense.
- **Small programs** pay the table's fixed part once: `(print (mapcar (lambda (x) (* x x))
  '(1 2 3)))` 10,680 -> 11,035 bytes (+355), two quoted defuns 5,146 -> 5,451. A field
  cost ~27 bytes a datum, so the table is smaller past ~15 datums; `(print (+ 1 2))` is
  unchanged (4,773).
- The site grew ~6 bytes (two `int` operands and two calls in place of `GETSTATIC` /
  `DUP; PUTSTATIC`); the corpus class still shrank 69 KB with the fields gone.

## A BARE instance literal shares the same slot
**Invariant: a `#P"..."` / `#S(...)` in CODE position -- outside any `quote` -- is one
shared constant per site on all four backends.** The one literal family not following
`.kb/array-literals.md`'s freshness rule: an instance is self-evaluating (CLHS 3.1.2.1.3),
so the compile side meets the interpreter. Same memo, via
`Jvm/WasmQuoteCompiler.emitSharedConstant`, called from `compile` (quoted aggregate) and
`compileLiteralInstance` (bare). Costs ~+40 bytes on wasm (orphaned null globals;
`WasmTreeShaker` does not drop globals), zero on the interpreter.

## A long list is built in runs (wasm-GC)
`WasmQuoteCompiler.compileQuotedCons` pushes every car, the tail, then one `struct.new` per
cell -- for up to `QUOTED_RUN` (16) cells. A longer list is built from its tail a run at a time,
the list so far carried through one local, so the operand stack never holds more than a run's
cars. The cost is what Cranelift does with a deep stack across CALLS: a symbol or string car is
a call, and every value live across it is spilled and listed in its stack map. Measured
2026-09-24, linux-x64, wasmtime 49.0.0, `wasmtime compile` of `(defparameter *table* '(s0 s1 ...))`:

| symbols | every car first | runs of 16 |
| ---: | ---: | ---: |
| 5,000 | 10.8 s | 1.9 s |
| 20,000 | 168 s | 16.7 s |
| 50,000 | -- | 94 s |

A list of NUMBERS (no calls) never cared: every car first compiles as fast as runs. Runs of 64
cost ~20% more compile time than 16 on hand-written modules; 16 costs 4 bytes per 16 cells,
+0.03% over every example and size-report build (`zlib` +16 B), and a list of 16 or fewer is
unchanged.

**What is left is not the stack.** Wasmtime's time grows with the square of the GC
allocations in ONE function, whatever the stack does: N x `ref.i31; ref.null; struct.new;
drop` compiles in 1.4 / 4.5 / 18.8 s at 5,000 / 10,000 / 20,000, while the same 20,000 cells
split into helper functions of 64 compile in 0.2 s. It is wasmtime's, with no rontolisp in it:
N x `struct.new` of an EMPTY struct takes 1.6 / 4.8 / 15.2 s. Those numbers are under the
DEFAULT collector, which in wasmtime 49 is `copying`. At 10,000, register allocation is 3.8 of
the 4.4 s. `drc` compiles the same module in 0.15 s, `null` in 0.6 s and single-pass regalloc
in 0.7 s. Generators: `.todo/artefacts/953-wasm-compile-quadratic-in-list-length/` (list
shapes) and `.todo/artefacts/955-wasmtime-compile-quadratic-in-allocations-per-function/`
(the reducer, and an upstream report that is drafted but not filed).

**Long data is NOT split into helper functions (measured 2026-09-24).** Real programs do
reach thousands of allocations in one function, but those functions never set the compile
time. Across every GC-wasm leg of `examples/`:

- Most programs stay at or below 317 allocations per function (`llm`).
- The HTTP Worker family goes higher, because its baked data is one function per datum:
  5,813 in the ningle Worker's `%asdf-registry%` (1.8 s on one core), 4,692 in its
  `%class-meta-table`, and 3,493 in tiny-routes (0.67 s).
- In tiny-routes and clack a different function compiles for as long or longer: tiny-routes'
  largest takes 0.69 s. In ningle that was fast-http's `parse-request`, at 46.6 s, until the
  landing-pad refresh was narrowed to the live locals (`.kb/wasm-landing-pad-refresh.md`,
  "Cost"). Since then (re-measured the same day, `check.lisp --optimize`, serial, three runs)
  ningle's slowest function IS the `%asdf-registry%` datum, 1.0-1.1 s, then `%class-meta-table`
  0.9 s, then `parse-request` 0.5-0.6 s. The datum's body did not change; the 1.8 s above was
  measured with the unnarrowed `parse-request` compiled before it in the same process. The
  module compiles in 1.9 s wall on 64 cores and 30 s of CPU.

Parallel compilation hides the datum's cost. On ningle the split would take at most ~0.6 s
off a 1.9 s wall on 64 cores, and nothing on a machine whose cores are all busy (the datum is
under 4% of the CPU). That is not worth a new emission shape, so the split is not built. It becomes
worth building when a datum's function sets a module's wall time by seconds: past about
10,000 cells that is 5 s. The split then builds such a datum in helper functions,
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
