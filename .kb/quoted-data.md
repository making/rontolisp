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
drop` compiles in 1.4 / 4.5 / 18.8 s at 5,000 / 10,000 / 20,000 (`-C collector=null` 1.2 s and
`-O regalloc-algorithm=single-pass` 1.35 s at 10,000: the DRC allocation path under the
backtracking allocator), while the same 20,000 cells split into helper functions of 64 compile
in 0.2 s. The generator for these modules is
`.todo/artefacts/953-wasm-compile-quadratic-in-list-length/`.

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
