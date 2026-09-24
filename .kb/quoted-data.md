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
- **JVM** (`JvmQuoteCompiler` + `JvmLispCompiler.QuotePool`): one private static
  **volatile** `Object` field `_qd$N` per datum, built lazily at the site (~12 bytes over
  the build). Volatile so racing first evaluations cannot expose a half-written
  `Object[]`.
- **WASM, Preview 1 and component** (`WasmQuoteCompiler` +
  `WasmLispCompiler.QuoteGlobals`): one `(mut (ref null eq)) = null` global per datum,
  appended AFTER every fixed-index global, filled lazily (~10 bytes). The allocator is
  shared into `WasmAsyncEmit`'s fresh contexts so async resume bodies reach the one
  table.
- **Trap: the JVM build must stay lazy at the site, not a `<clinit>` initializer.**
  `JvmClassShaker` runs on every build; with the injected wrapper defuns' package-registry
  constants pinned by `<clinit>` a three-defun program grew 5,898 -> 18,978 bytes. Do not
  "simplify" this into the `LayoutPool`/`BigIntPool` `<clinit>` shape.

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
- In every one of these modules a different function compiles for as long or longer. Tiny-routes'
  largest function takes 0.69 s. In ningle, fast-http's `parse-request` takes 46.6 s, which is
  the landing-pad refresh (`.kb/wasm-landing-pad-refresh.md`, "Cost").

Parallel compilation hides the datum's cost, so the split would save no wall time on any
measured artifact. It becomes worth building when a datum's function is a module's slowest.
Past about 10,000 cells that is 5 s. The split then builds such a datum in helper functions,
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
Runs: `WasmLispCompilerTest.aLongQuotedListKeepsNoMoreThanOneRunOfCellsOnTheOperandStack`,
`WasmLispCompilerIntegrationTest.aQuotedListLongerThanOneRunIsTheSameListAndStillOneConstant`.
